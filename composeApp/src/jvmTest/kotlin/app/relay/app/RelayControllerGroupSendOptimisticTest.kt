// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.core.protocol.ConvId
import app.relay.core.transport.SendOutcome
import app.relay.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 群聊文本的**发送时序**：气泡必须在发送路径返回之前就出现。
 *
 * ## 为什么单独一条测试
 *
 * 群里最慢的是扇出：旧实现逐成员串行 await，4 人群 3 人离线就是三倍退避 + TCP 兜底。
 * 那段时间界面原本**什么都不显示** —— 用户以为没点上而重复发送。这条测试用一道闸门
 * （[CompletableDeferred]）把发送卡在半路，在**放行之前**断言气泡已经在列表里，
 * 所以它测的是时序，不是终态。
 *
 * 只断言「最终变成 Sent」「最终只有一条」是**不可区分**的：乐观插入与「发完再插」
 * 在这两点上完全一样。闸门才是区分两者的那个量。
 */
class RelayControllerGroupSendOptimisticTest {

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val lin = "11111111-1111-4111-8111-111111111111"
    private val zhou = "22222222-2222-4222-8222-222222222222"

    @Test
    fun groupBubbleAppearsBeforeSendReturns() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("上线小组", listOf(lin, zhou))!!
        val convId = ConvId.group(groupId)
        val gate = CompletableDeferred<Unit>()

        val envelopeId = "e-在吗"
        val job = launch {
            controller.groupsCtl.sendGroupTextOptimistic(groupId, "在吗", null, envelopeId = envelopeId) {
                gate.await()
                SendOutcome.SENT
            }
        }

        // 闸门还没放行：气泡就必须已经在了。
        val sending = awaitMessage(controller, convId) { it.delivery == DeliveryState.Sending }
        assertEquals(envelopeId, sending.id, "本地行 id 从第一帧起就等于信封 id（重发对账都靠它）")
        assertTrue(sending.mine)
        assertEquals("在吗", sending.text)
        assertEquals(1, controller.state.value.messagesWith(convId).size, "放行前就该有且只有这一条气泡")

        gate.complete(Unit)
        job.join()

        val rows = controller.state.value.messagesWith(convId).filter { it.text == "在吗" }
        assertEquals(1, rows.size, "就地改写后仍只有一条：不能先消失再出现，更不能两条")
        val settled = rows.single()
        assertEquals(envelopeId, settled.id, "终态沿用同一个 id：不能换 id（换 id = 对端第二条）")
        assertEquals(DeliveryState.Sent, settled.delivery)
    }

    @Test
    fun groupBubbleBecomesFailedWhenSendThrows() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("上线小组", listOf(lin, zhou))!!
        val convId = ConvId.group(groupId)

        launch {
            controller.groupsCtl.sendGroupTextOptimistic(groupId, "发不出去", null) {
                throw IllegalStateException("boom")
            }
        }.join()

        val rows = controller.state.value.messagesWith(convId).filter { it.text == "发不出去" }
        assertEquals(1, rows.size, "失败也必须留下那一条，不能消失")
        assertEquals(
            DeliveryState.Failed,
            rows.single().delivery,
            "发送路径抛异常必须把「发送中」收尾成失败 —— 不能永远转圈",
        )
    }

    @Test
    fun groupBubbleSettlesAsSentEvenWhenTheOutcomeIsQueued() = runBlocking {
        // 刻意的语义选择（见 sendGroupTextOptimistic 的 KDoc）：群扇出没有逐成员回执，
        // QUEUED 只表示「至少一个成员那份还在队列里」，另一些成员可能已 ACK ——
        // 折成「未送达」会把已经收到的人标错。所以终态恒为 Sent，与旧行为一致。
        val controller = controller()
        val groupId = controller.createGroup("上线小组", listOf(lin, zhou))!!
        val convId = ConvId.group(groupId)

        launch {
            controller.groupsCtl.sendGroupTextOptimistic(groupId, "离线也发", null) { SendOutcome.QUEUED }
        }.join()

        assertEquals(
            DeliveryState.Sent,
            controller.state.value.messagesWith(convId).single { it.text == "离线也发" }.delivery,
        )
    }

    private suspend fun awaitMessage(
        controller: RelayController,
        convId: String,
        predicate: (MessageUi) -> Boolean,
    ): MessageUi {
        var found: MessageUi? = null
        withTimeout(5_000) {
            while (found == null) {
                found = controller.state.value.messagesWith(convId).firstOrNull(predicate)
                if (found == null) delay(5)
            }
        }
        return found ?: error("等待的消息没有出现")
    }

    private fun CoroutineScope.controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
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
