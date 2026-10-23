// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain


import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.core.session.OutgoingText
import com.kapp.tidings.core.transport.SendOutcome
import com.kapp.tidings.platform.InMemoryAppPlatform
import com.kapp.tidings.domain.transferIdDetailLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 「发送中」态与乐观插入（真机反馈的高频路径）。
 *
 * ## 缺陷
 *
 * 发送是异步的：`sendTextWithEnvelope` 要在候选端点上跑 UDP 退避（1s/2s/4s）再对每个端点
 * 试一次 TCP（各 7s），跨网最坏 **40 秒量级**。在 `DeliveryState.Sending` 之前，这整段时间
 * 界面**什么都不显示**：气泡不出现、发送按钮还能再点 ⇒ 用户以为没点上，连点三次发三条。
 *
 * ## 这条测试守什么
 *
 * 不守"最终变成 Sent"（那本来就测过），守的是**时序**：
 *   1. 发送还没返回时，气泡**已经在**列表里且状态是 Sending；
 *   2. 返回后仍然**只有一条**（就地改写，不是先消失再出现、更不是两条）。
 *
 * 第 2 点尤其重要：乐观插入最常见的翻车方式是"临时气泡 + 真实气泡"并存。
 *
 * 只断言终态是**不可区分**的：乐观插入与"发完再插"在"最终只有一条、最终是 Sent"
 * 这两点上完全一样。所以时序用例用一道闸门（[CompletableDeferred]）把发送卡在半路，
 * 在**放行之前**断言气泡已经存在。
 */
class RelayControllerSendOptimisticTest {

    private val peer = "peer-a"

    /**
     * 重启后残留的 [DeliveryState.Sending] 必须折成失败。
     *
     * 进程都换了一个，那次发送**必然**已结束，无从恢复；而 Sending 在界面上连「重发」
     * 出口都没有（只有 Failed 才挂），留着它等于让用户看着一条永远转下去的消息。
     * 这是 `Sending` 的 KDoc 承诺过的"重启后不会残留"，这里钉住它的实现点。
     */
    @Test
    fun aStaleSendingStateBecomesFailedOnRestore() {
        val stale = MessageUi(
            id = "local-1",
            peerId = "p1",
            mine = true,
            text = "还在发",
            at = 1L,
            delivery = DeliveryState.Sending,
        )
        assertEquals(
            DeliveryState.Failed,
            stale.toRestored().delivery,
            "重启后不能还停在「发送中」—— 那次发送已经结束，界面必须给出可重试的终态",
        )
    }

    @Test
    fun aSentMessageIsNeverDowngradedOnRestore() {
        // 回归护栏：ACK 与重启是两条独立的路，别把「已送达」改回失败（那是撒谎）。
        val sent = MessageUi(
            id = "m1",
            peerId = "p1",
            mine = true,
            text = "已送达的",
            at = 1L,
            delivery = DeliveryState.Sent,
        )
        assertEquals(DeliveryState.Sent, sent.toRestored().delivery)
    }

    @Test
    fun anIncomingMessageIsNeverGivenADeliveryState() {
        // 入站消息本来就没有投递态，不该因为这条规则凭空长出一个红字。
        val incoming = MessageUi(
            id = "m2",
            peerId = "p1",
            mine = false,
            text = "对方发的",
            at = 1L,
            delivery = null,
        )
        assertEquals(null, incoming.toRestored().delivery)
    }

    @Test
    fun privateBubbleAppearsBeforeSendReturns() = runBlocking {
        val controller = controller()
        val gate = CompletableDeferred<Unit>()

        val job = launch {
            controller.messagesCtl.sendTextOptimistic(peerId = peer, body = "在吗") {
                gate.await()
                OutgoingText(outcome = SendOutcome.SENT, envelopeId = "env-1", envelopeJson = "{}")
            }
        }

        // 闸门还没放行：气泡就必须已经在了。
        val sending = awaitMessage(controller) { it.delivery == DeliveryState.Sending }
        assertTrue(sending.id.startsWith("local-"), "乐观气泡先挂临时 id —— 真实 id 要等发送返回")
        assertTrue(sending.mine)
        assertEquals("在吗", sending.text)
        assertEquals(1, controller.state.value.messagesWith(peer).size, "放行前就该有且只有这一条气泡")

        gate.complete(Unit)
        job.join()

        val rows = controller.state.value.messagesWith(peer).filter { it.text == "在吗" }
        assertEquals(1, rows.size, "就地改写后仍只有一条：不能先消失再出现，更不能两条")
        val settled = rows.single()
        assertEquals("env-1", settled.id, "返回后 id 必须换成真实 envelopeId，回执/撤回才对得上")
        assertEquals(DeliveryState.Sent, settled.delivery)
    }

    @Test
    fun privateBubbleBecomesFailedWhenSendThrows() = runBlocking {
        val controller = controller()

        launch {
            controller.messagesCtl.sendTextOptimistic(peerId = peer, body = "发不出去") {
                throw IllegalStateException("boom")
            }
        }.join()

        val rows = controller.state.value.messagesWith(peer).filter { it.text == "发不出去" }
        assertEquals(1, rows.size, "失败也必须留下那一条，不能消失")
        assertEquals(
            DeliveryState.Failed,
            rows.single().delivery,
            "发送路径抛异常必须把「发送中」收尾成失败 —— 不能永远转圈",
        )
    }

    private suspend fun awaitMessage(
        controller: RelayController,
        predicate: (MessageUi) -> Boolean,
    ): MessageUi {
        var found: MessageUi? = null
        withTimeout(5_000) {
            while (found == null) {
                found = controller.state.value.messagesWith(peer).firstOrNull(predicate)
                if (found == null) delay(5)
            }
        }
        return found ?: error("等待的消息没有出现")
    }

    private fun CoroutineScope.controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(peer),
        scope = this,
        version = "relay-test",
    )

    private class MemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }
}

/**
 * 文件详情行**不给用户看内部临时传输键**（task-1 自己指出的 pending- 泄漏）。
 *
 * 乐观插入会在报价返回前把卡片挂在 `pending-<msgId>` 上（见 `RelayControllerTransfersComponent`
 * 的 pendingTransferKey）。那是本端实现细节，不是用户认得出的传输编号 —— 详情行要么显示
 * 真实 transferId 的前 12 位，要么整行不画。
 */
class TransferIdDetailLabelTest {

    @Test
    fun internalPendingKeyIsNotShown() {
        assertNull(transferIdDetailLabel("pending-abc-123"), "内部临时键不该上屏")
    }

    @Test
    fun aRealTransferIdIsShownShortened() {
        val id = "01234567-8901-4111-8111-111111111111"
        assertEquals(id.take(12), transferIdDetailLabel(id))
    }

    @Test
    fun blankIsNotShown() {
        assertNull(transferIdDetailLabel(null))
        assertNull(transferIdDetailLabel(""))
        assertNull(transferIdDetailLabel("   "))
    }

}
