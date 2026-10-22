// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.DeliveryState
import kotlinx.coroutines.delay
import app.relay.app.model.MessageUi
import app.relay.core.protocol.ConvId
import app.relay.core.transport.SendOutcome
import app.relay.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * **挂死的发送必须在兜底超时后折成失败**，不能永远停在「发送中」。
 *
 * ## 真机现场
 *
 * 一条群消息从发出到被查看，**5 分 33 秒**仍是 `Sending`。而：
 *
 *  · `DeliveryState.Sending` 在界面上**没有重发出口**（只有 `Failed` 才挂重发）；
 *  · 它的 KDoc 承诺过「不会永远转下去」「重启后折成失败」。
 *
 * 根因：`runCatching { send() }` **只接异常，接不住「永不返回」**。
 * 内层 fan-out 有 10 秒预算，正常路径用不到这条兜底 —— 能走到这里的只有真正的挂死。
 *
 * ## 为什么用毫秒级超时
 *
 * 生产值是 60 秒。用真值测一条用例要等一分钟，那种测试没人会跑，等于没有。
 * 所以超时做成形参，这里注入 50ms —— 断言的是**行为**（超时 ⇒ 失败），不是那个数字。
 */
class GroupSendHangTimeoutTest {

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val lin = "11111111-1111-4111-8111-111111111111"

    /**
     * **send 永不返回 ⇒ 折成 `Failed`**（不是一直 `Sending`）。
     */
    @Test
    fun aHangingSendBecomesFailedInsteadOfStayingInSending() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("上线小组", listOf(lin))!!
        val convId = ConvId.group(groupId)
        val envelopeId = "e-挂死"

        val outcome = controller.groupsCtl.sendGroupTextOptimistic(
            groupId, "在吗", null,
            envelopeId = envelopeId,
            hardTimeoutMs = 50L,
        ) {
            // 永不返回：模拟「某个 await 永远完不成」。
            awaitCancellation()
        }

        assertNull(outcome, "挂死应当返回 null（没有结论）")
        val row = withTimeout(2_000) {
            awaitMessage(controller, convId) { it.id == envelopeId }
        }
        assertEquals(
            DeliveryState.Failed,
            row.delivery,
            "挂死必须折成失败 —— 停在 Sending 的消息没有重发出口，用户只能干看着",
        )
    }

    /** 正常返回时不受兜底超时影响（别把慢网误伤成失败）。 */
    @Test
    fun aNormalSendIsUnaffectedByTheGuard() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("上线小组", listOf(lin))!!
        val convId = ConvId.group(groupId)
        val envelopeId = "e-正常"

        val outcome = controller.groupsCtl.sendGroupTextOptimistic(
            groupId, "在吗", null,
            envelopeId = envelopeId,
            hardTimeoutMs = 5_000L,
        ) { SendOutcome.SENT }

        assertEquals(SendOutcome.SENT, outcome)
        val row = withTimeout(2_000) {
            awaitMessage(controller, convId) { it.id == envelopeId }
        }
        assertEquals(DeliveryState.Sent, row.delivery)
    }

    private suspend fun awaitMessage(
        controller: RelayController,
        convId: String,
        predicate: (MessageUi) -> Boolean,
    ): MessageUi {
        var found: MessageUi? = null
        withTimeout(2_000) {
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
