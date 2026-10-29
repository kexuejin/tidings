// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.AgentSessionRegistry
import com.kapp.tidings.agent.drivers.DriverEvent
import com.kapp.tidings.agent.drivers.DriverRegistry
import com.kapp.tidings.domain.RelayController
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDshAgentInteractionTest {

    /** 测试样本种类：kind 是声明数据的替身，不是生产默认（债#6）。 */
    private val KINDS = setOf("dsh")


    @Test
    fun testDshMentionDetectionAndPromptExtraction() {
        assertTrue(RelayController.isMentioningAgent("@DSH please refactor code", KINDS))
        assertTrue(RelayController.isMentioningAgent("Hey @dsh what is the weather?", KINDS))
        assertTrue(!RelayController.isMentioningAgent("Hello world without mention", KINDS))
        assertTrue(!RelayController.isMentioningAgent("@dsh_extra", KINDS))

        assertEquals("please refactor code", RelayController.extractAgentPrompt("@DSH please refactor code", KINDS))
        // 期望侧**不许**再对实际值做 replace/trim —— 那种断言即使函数原样返回整句也会绿。
        // 产品口径（lead 裁决）：去掉 mention、其余原样保留、**归一化空白**（删 mention 会留下双空格）。
        assertEquals("Hey what is the weather?", RelayController.extractAgentPrompt("Hey @dsh what is the weather?", KINDS))
    }

    @Test
    fun testDshDriverExecutionStreamAndReplyFormatting() = runBlocking {
        val registry = DriverRegistry()
        val driver = registry.getDriver("ds-bot")
        val sessionRegistry = AgentSessionRegistry()

        val sessionId = "s-test-123"
        val thoughts = StringBuilder()
        val answer = StringBuilder()
        var toolCalled: String? = null

        driver?.executeTask(sessionId, "search Kotlin P2P") { event ->
            when (event) {
                is DriverEvent.ThoughtDelta -> {
                    thoughts.append(event.delta).append("\n")
                    sessionRegistry.recordEvent(
                        nodeId = "test-node",
                        sessionId = sessionId,
                        cursor = System.currentTimeMillis(),
                        eventType = "thought",
                        summary = event.delta,
                        body = event.delta,
                    )
                }
                is DriverEvent.ToolCall -> {
                    toolCalled = event.tool
                    sessionRegistry.recordEvent(
                        nodeId = "test-node",
                        sessionId = sessionId,
                        cursor = System.currentTimeMillis(),
                        eventType = "tool.started",
                        summary = "tool: ${event.tool}",
                        body = event.inputJson,
                    )
                }
                is DriverEvent.TokenDelta -> {
                    answer.append(event.delta)
                }
                is DriverEvent.Finished -> {
                    sessionRegistry.recordEvent(
                        nodeId = "test-node",
                        sessionId = sessionId,
                        cursor = System.currentTimeMillis(),
                        eventType = "finished",
                        summary = "Execution completed",
                        body = "tokens: ${event.totalTokens}",
                    )
                }
                is DriverEvent.Error -> {
                    answer.append(event.message)
                }
                is DriverEvent.Card -> {
                    // 演示驱动不发卡片；分支只为完备（M2 起 DriverEvent 有卡片事件）。
                    sessionRegistry.recordEvent(
                        nodeId = "test-node",
                        sessionId = sessionId,
                        cursor = System.currentTimeMillis(),
                        eventType = "card",
                        summary = "card",
                        body = event.cardJson,
                    )
                }
            }
        }

        assertTrue(thoughts.isNotEmpty(), "Thoughts must be captured")
        // task-58：模拟器发的卡片必须**标为示例·未执行**，不许让人以为真跑了 websearch。
        assertTrue(
            toolCalled?.startsWith("websearch") == true && toolCalled?.contains("未执行") == true,
            "演示卡片必须标注未执行，实际：" + toolCalled,
        )
        assertTrue(answer.isNotEmpty(), "Answer must be generated")
        // 而且这条回答**不许**出现完成断言（task-58）：原来钉的是"回答非空"，
        // 那让「为没发生过的活报完成」变成了规格。
        assertTrue(!answer.toString().contains("完成"), "演示回答不许断言完成：" + answer)
        assertTrue(!answer.toString().contains("正常执行完毕"), "这句原话必须删掉：" + answer)
        assertTrue(answer.toString().contains("演示"), "必须如实说明是演示：" + answer)

        val replyText = "<think>\n${thoughts.toString().trim()}\n</think>\n\n${answer.toString().trim()}"
        assertTrue(replyText.startsWith("<think>"), "Formatted reply must contain think block")
        assertTrue(replyText.contains("</think>"), "Formatted reply must close think block")

        val events = sessionRegistry.events("test-node", sessionId)
        assertTrue(events.isNotEmpty(), "Events must be recorded in session registry")
        assertTrue(events.any { it.eventType == "thought" }, "Thought event must exist")
        assertTrue(events.any { it.eventType == "tool.started" }, "Tool start event must exist")
        assertTrue(events.any { it.eventType == "finished" }, "Finished event must exist")
    }
}
