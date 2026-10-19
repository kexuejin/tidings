// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 事件路由诊断（task-57）：**诊断行不含正文**，计数与环形缓冲有界。
 *
 * 背景（workspace-ia 的 task-50 第三轮审查）：热路径原来直接 println —— 每条事件一条，
 * 还把**用户消息正文**打出去（私有信使的 stdout 会进终端/日志/被别的工具抓走）。
 * 收敛之后：正文在诊断行里**没有参数位**（只有长度），逐事件 stdout 默认关闭。
 */
class EventRouterDiagnosticsTest {

    @Test
    fun theDiagnosticLineHasNoRoomForContent() {
        assertEquals(
            "type=user.message session=s-1 cursor=42 len=7 reason=duplicate-in-chat",
            eventDiagnosticLine("user.message", "s-1", 42L, 7, "duplicate-in-chat"),
        )
    }

    @Test
    fun theLineOnlyCarriesALengthNotTheText() {
        val secret = "这是用户正文，绝不该出现在任何输出里"
        val line = eventDiagnosticLine("user.message", "s-1", 1L, secret.length, "no-group-bound")
        assertFalse(line.contains(secret), "诊断行不许包含正文：" + line)
        assertTrue(line.contains("len=" + secret.length), "只留长度：" + line)
    }

    @Test
    fun countersAggregateByReasonMostFrequentFirst() {
        val diagnostics = EventRouterDiagnostics()
        repeat(3) { diagnostics.record("no-group-bound", "type=x reason=no-group-bound") }
        diagnostics.record("duplicate-in-chat", "type=y reason=duplicate-in-chat")
        val snapshot = diagnostics.snapshot()
        assertEquals(3L, snapshot["no-group-bound"])
        assertEquals(1L, snapshot["duplicate-in-chat"])
        assertEquals("no-group-bound", snapshot.keys.first(), "多的排前面（读起来先看到主因）")
    }

    @Test
    fun theRingIsBounded() {
        val diagnostics = EventRouterDiagnostics(capacity = 8)
        repeat(20) { i -> diagnostics.record("dispatch", "cursor=" + i) }
        val recent = diagnostics.recentLines()
        assertEquals(8, recent.size, "环形缓冲必须有界（否则又是一个长跑泄漏）")
        assertTrue(recent.last().contains("cursor=19"), "保留最新的：" + recent.last())
        assertFalse(recent.any { it.contains("cursor=0") }, "最旧的被挤掉")
    }
}

