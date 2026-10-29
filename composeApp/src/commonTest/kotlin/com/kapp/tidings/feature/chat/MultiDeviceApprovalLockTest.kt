// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layer 5（方案 §8）：多端分布式审批防重击（Atomic Action Lock，§7.3-2 / §5.3）。
 *
 * **仿真判据，不赌真机广播**：跨端 ack 广播是 M4 的交付物，M3 钉的是 UI 锁定与
 * disabled 语义 —— 两端各有一份独立的渲染状态（pending 集合 + 消息库读回的卡片），
 * 中间用一个**进程内的仿真宿主**代替真广播：它按 (cardId, actionId) 幂等收下第一个
 * ack，把后续点击丢弃，并把判定以「rev+1 的新帧」回放给两端（与方案 §5.3 定死的
 * 存储语义一致：审批锁定事件走新帧，不重写旧行）。
 *
 * 核心断言（§8 原文）：
 * 1. 两端同时触发 onClick，**只有第一个请求产出 agent-ack**（宿主幂等丢弃第二个）；
 * 2. 第二端收到状态广播后按钮处于 disabled，并显示批准人。
 *
 * 夹具都声明在**类外**（文件私有）：嵌套类不能用外层类作接收者，而夹具之间
 * 互不持有状态 —— 平级最诚实。
 */

/** 仿真宿主：按 (cardId, actionId) 幂等，只有第一个 ack 生效。 */
private class SimulatedHost {
    val accepted = mutableListOf<Pair<String, String>>()
    private val decided = mutableMapOf<Pair<String, String>, String>()

    /** 返回 true = 本条被接受（产出 agent-ack）；false = 幂等丢弃。 */
    fun onAck(cardId: String, actionId: String, decidedBy: String): Boolean {
        val key = cardId to actionId
        if (key in decided) return false
        decided[key] = decidedBy
        accepted += key
        return true
    }

    /** 判定以 rev+1 的新帧广播（同 cardId、新行）。 */
    fun broadcastVerdict(base: JsonObject): JsonObject {
        val cardId = base["cardId"]?.toString().orEmpty().trim('"')
        val decidedKey = decided.entries.first { it.key.first == cardId }
        val rev = base["rev"]?.toString()?.toInt() ?: 0
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("rev", rev + 1)
            put("decidedBy", decidedKey.value)
            put("decidedActionId", decidedKey.key.second)
        }
    }
}

private fun approvalCard(rev: Int): JsonObject = buildJsonObject {
    put("type", "approval")
    put("version", 1)
    put("cardId", "task-89")
    put("rev", rev)
    put("title", "重构网络传输层")
    put(
        "actions",
        Json.parseToJsonElement("""[{"actionId":"approve","label":"批准","style":"primary","approval":true}]"""),
    )
}

/** 一端的渲染状态：pending 是本机记账，card 是消息库读回的最新 rev。 */
private class DeviceEnd {
    var pending: Set<String> = emptySet()
    var card: AgentCardUi = parseAgentCard(approvalCard(rev = 1))!!
    val ackRequests = mutableListOf<Pair<String, String>>()

    fun onClick(actionId: String, host: SimulatedHost) {
        // UI 层的完整动作：先乐观记账，再发 ack 请求（M4 才有真传输，这里直连仿真宿主）。
        pending = pending + "${card.cardId}|$actionId"
        if (host.onAck(card.cardId, actionId, decidedBy = "Alice")) {
            ackRequests += card.cardId to actionId
        }
    }

    fun onBroadcast(json: JsonObject) {
        parseAgentCard(json)?.let { card = it }
    }

    fun lockOf(actionId: String): CardActionUi =
        cardActionUiOf(card, pendingActions = pending, actionId = actionId)
}

class MultiDeviceApprovalLockTest {

    @Test
    fun onlyTheFirstClickProducesAnAck() {
        val host = SimulatedHost()
        val phone = DeviceEnd()
        val desktop = DeviceEnd()

        // 两端"同时"点击同一颗审批按钮。
        phone.onClick("approve", host)
        desktop.onClick("approve", host)

        // 宿主幂等：两个请求里只有第一个产出 agent-ack。
        assertEquals(1, host.accepted.size, "防重击：同 (cardId, actionId) 只接受第一个 ack")
        assertEquals(listOf("task-89" to "approve"), host.accepted)

        // 各自本机的乐观状态都已置灰（§5.3：一人点击，本机立即置灰）。
        assertEquals(CardActionLock.PendingConfirm, phone.lockOf("approve").lock)
        assertEquals(CardActionLock.PendingConfirm, desktop.lockOf("approve").lock)
        assertEquals(1, phone.ackRequests.size, "本端自己的 ack 请求产出一次")
        assertEquals(0, desktop.ackRequests.size, "第二端的请求被宿主幂等丢弃，不重复产出")
    }

    @Test
    fun secondEndRendersDisabledWithTheApproverAfterTheBroadcast() {
        val host = SimulatedHost()
        val phone = DeviceEnd()
        val desktop = DeviceEnd()
        phone.onClick("approve", host)
        desktop.onClick("approve", host)

        // 宿主判定以 rev+1 新帧广播，两端各自落库、读回。
        val verdict = host.broadcastVerdict(approvalCard(rev = 1))
        phone.onBroadcast(verdict)
        desktop.onBroadcast(verdict)

        for ((name, end) in listOf("phone" to phone, "desktop" to desktop)) {
            val ui = end.lockOf("approve")
            assertEquals(CardActionLock.Decided, ui.lock, "$name 端按钮必须进终态")
            assertTrue(ui.disabled, "$name 端按钮必须 disabled（§8 的核心断言）")
            assertEquals("Alice", ui.decidedBy, "$name 端要显示批准人（§5.3：全员刷「已由 X 批准」）")
        }
        assertEquals(2, verdict["rev"]?.toString()?.toInt(), "广播的是 rev+1 的新帧")
    }

    @Test
    fun optimisticGreySurvivesUntilTheVerdictArrives() {
        val host = SimulatedHost()
        val end = DeviceEnd()
        end.onClick("approve", host)
        // 判定还没到（旧 rev 还在库里）：按钮保持乐观置灰，不许回弹成可点。
        end.onBroadcast(approvalCard(rev = 1))
        assertEquals(CardActionLock.PendingConfirm, end.lockOf("approve").lock)
        assertTrue(end.lockOf("approve").disabled)
    }
}
