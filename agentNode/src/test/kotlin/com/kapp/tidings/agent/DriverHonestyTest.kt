// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.agent.drivers.DriverEvent
import com.kapp.tidings.agent.drivers.DsBotDriver
import com.kapp.tidings.agent.drivers.nextToolCallId
import com.kapp.tidings.agent.drivers.selectDriverIdForUserPrompt
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 驱动诚信（task-58）：模拟器驱动不得对真用户断言「已完成」，也不得被默认选中。
 *
 * 事实（workspace-ia 的 task-50 第四轮审查）：DsBotDriver.executeTask **从不碰网络**
 * （只有 delay(15) + 固定文案），却会发工具卡片、报 128 tokens、正文逐字断言
 * 「已在当前工作区完成分析与协同处理，任务管道正常执行完毕」；而 DesktopExtension
 * 对真用户 prompt **优先选它**。用户看到的是「为没发生过的活报完成的智能体」。
 *
 * 这份测试钉住三件事：① 演示回复**不含任何完成断言**；② 工具卡片标为示例·未执行；
 * ③ **默认不选演示驱动**（真用户 prompt 走 native）。
 */
class DriverHonestyTest {

    private fun runDemo(prompt: String): List<DriverEvent> = runBlocking {
        val events = mutableListOf<DriverEvent>()
        DsBotDriver().executeTask("s-demo", prompt) { events += it }
        events
    }

    private fun replyText(events: List<DriverEvent>): String =
        events.filterIsInstance<DriverEvent.TokenDelta>().joinToString("") { it.delta }

    @Test
    fun theDemoReplyNeverClaimsCompletion() {
        val text = replyText(runDemo("帮我 search 一下这个 API"))
        assertFalse(text.contains("完成"), "不许出现完成断言：" + text)
        assertFalse(text.contains("正常执行完毕"), "这句是这次整改要删的原话：" + text)
        assertTrue(text.contains("演示"), "必须如实说明是演示：" + text)
        assertTrue(text.contains("没有执行任何真实任务"), "必须如实说明没干活：" + text)
    }

    @Test
    fun theDemoThoughtsAlsoSayItIsADemo() {
        val thoughts = runDemo("随便说点什么").filterIsInstance<DriverEvent.ThoughtDelta>()
        assertTrue(thoughts.isNotEmpty())
        for (t in thoughts) {
            assertTrue(t.delta.contains("演示"), "思考流也要如实标注：" + t.delta)
        }
    }

    @Test
    fun toolCardsAreMarkedAsExamplesNotExecuted() {
        val calls = runDemo("search something in the repo").filterIsInstance<DriverEvent.ToolCall>()
        assertTrue(calls.isNotEmpty(), "演示驱动会发示例卡片")
        for (c in calls) {
            assertTrue(c.tool.contains("示例"), "卡片必须标为示例：" + c.tool)
            assertTrue(c.tool.contains("未执行"), "卡片必须标为未执行：" + c.tool)
        }
    }

    @Test
    fun theDemoReportsNoTokens() {
        val finished = runDemo("hi").filterIsInstance<DriverEvent.Finished>().single()
        assertEquals(0, finished.totalTokens, "没调用模型就不该报 token 数（原来恒 128）")
    }

    @Test
    fun toolCallIdsDoNotCollideInTheSameMillisecond() {
        val ids = (1..64).map { nextToolCallId() }
        assertEquals(ids.size, ids.toSet().size, "同一毫秒内多次调用也不能撞 id（原来用 currentTimeMillis）")
    }

    @Test
    fun theDemoDriverIsNeverSelectedByDefault() {
        assertEquals(
            "native",
            selectDriverIdForUserPrompt(demoRequested = false),
            "真用户 prompt 默认必须走真实驱动；演示驱动只能在显式开关下进入",
        )
        assertEquals("ds-bot", selectDriverIdForUserPrompt(demoRequested = true))
    }
}

