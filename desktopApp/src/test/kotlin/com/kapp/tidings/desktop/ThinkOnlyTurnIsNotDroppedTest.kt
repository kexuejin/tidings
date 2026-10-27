// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.AgentSessionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 只有思考、没有正文的回合**不许静默消失**（真机缺陷）。
 *
 * 现场：插件在回合结束、整轮没有任何正文时，发来 body 只有 `<think>…</think>` 的
 * `assistant.text`（summary `思考完成`）。桌面端剥掉思考块后正文为空，此前直接
 * `return@onRecord` ⇒ 用户看到「回合结束了，群里一个字都没有」。真机上连测到三条这种
 * 形态：都进了台账、都没进群。
 *
 * 判据（这条测试钉的）：剥完之后**思考非空**的，必须产出一条消息（思考块 + 替代正文）；
 * 连思考都没有的，仍然不产（那才是真正的空事件，不该凭空造气泡）。
 *
 * 反向验证：把 `agentChatMessageFor` 的 `thinkOnly` 分支改回 `if (!hasVisibleText(finalAnswer)) return null`，
 * 第一条用例在 assertNotNull 处变红。
 */
class ThinkOnlyTurnIsNotDroppedTest {

    private val fallback = "这一轮只有思考过程，没有正文"

    private fun record(type: String, body: String, cursor: Long = 1L) = AgentSessionRegistry.EventRecord(
        nodeId = "node-1",
        sessionId = "session-probe",
        cursor = cursor,
        eventType = type,
        summary = null,
        body = body,
        ref = null,
        at = 1_792_991_844_000L,
    )

    private fun messageFor(type: String, body: String) = agentChatMessageFor(
        rec = record(type, body),
        sessionTitle = "排查",
        selfNodeId = "node-1",
        agentKind = "dsh",
        format = { r, _ -> r.body },
        thinkingOnlyFallback = fallback,
    )

    @Test
    fun thinkingOnlyTurnStillProducesAMessage() {
        val message = messageFor("assistant.text", "<think>用户说继续，我先看日志……</think>")
        assertNotNull(message, "只有思考的回合被整个丢掉了 ⇒ 用户看到的是「这一轮没有下文」")
        assertEquals(fallback, message.text, "正文应当是一句如实的话，而不是空白或伪造内容")
        assertTrue(
            message.reasoning?.contains("我先看日志") == true,
            "思考块必须留在 reasoning 里（界面上折叠显示），否则用户只剩一句无从验证的话",
        )
    }

    @Test
    fun trulyEmptyEventIsStillNotABubble() {
        assertNull(
            messageFor("assistant.text", "<think></think>"),
            "连思考都没有的事件不该凭空造气泡（那是旧行为的另一半，必须保留）",
        )
    }

    @Test
    fun normalAnswerIsUntouched() {
        val message = messageFor("assistant.text", "<think>想了想</think>\n修复已提交")
        assertNotNull(message)
        assertEquals("修复已提交", message.text, "有正文时不许被替代文案顶掉")
    }

    @Test
    fun withoutFallbackTheOldBehaviorIsKept() {
        val message = agentChatMessageFor(
            rec = record("assistant.text", "<think>只有思考</think>"),
            sessionTitle = null,
            selfNodeId = "node-1",
            agentKind = "dsh",
            format = { r, _ -> r.body },
            thinkingOnlyFallback = "",
        )
        assertNull(message, "取不到替代文案时应退回旧行为，而不是编一句中文")
    }
}
