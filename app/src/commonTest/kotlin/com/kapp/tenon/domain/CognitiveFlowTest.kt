// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分诊表（P2 规格 1 的判据本体）。
 *
 * 这些断言是**方向性**的：误判成「做」会写别人的仓库（不可撤销），误判成「聊」只是
 * 少干一次活（用户补一句「开工」即可）。所以每条都在钉「什么时候必须判聊」。
 */
class CognitiveFlowTest {

    private fun deliver(text: String) =
        assertEquals(CognitiveFlow.Intent.Deliver, CognitiveFlow.classify(text), "应判派单：$text")

    private fun discuss(text: String) =
        assertEquals(CognitiveFlow.Intent.Discuss, CognitiveFlow.classify(text), "应判只读讨论：$text")

    @Test
    fun hardDispatchCommandsAreDeliverEvenWhenPhrasedAsAQuestion() {
        deliver("组队: 优化持久层接口")
        deliver("组队：优化持久层接口")
        deliver("team: 优化持久层接口")
        deliver("开发: 实现 A\n审核: 检查 B")
        deliver("/status")
        deliver("/test")
        // 硬指令不被问句覆盖：人已经按了「开工」那个动作，问号只是语气。
        deliver("组队: 帮我分析下这个方案？")
    }

    @Test
    fun plainImperativesAreDeliver() {
        deliver("实现这个接口")
        deliver("修复登录页的白屏")
        deliver("重构 RelayController")
        deliver("把配色改成深色")
        deliver("implement the sqlite queue")
        deliver("refactor the storage layer")
    }

    @Test
    fun questionsStayDiscussEvenWithAnImperativeVerbInside() {
        discuss("怎么实现这个接口？")
        discuss("这个接口如何设计，有什么风险")
        discuss("为什么要重构持久层")
        discuss("是否要新增一张表")
        discuss("how should we implement the offline queue")
    }

    @Test
    fun multiAgentResearchIsDiscuss() {
        discuss("@claude @dsh 你们调研分析下这个方案")
        discuss("@claude 我们打算把离线队列从内存切到 SQLite，怎么设计？")
        discuss("帮我看看这段代码有没有并发风险")
    }

    @Test
    fun emptyOrSmallTalkIsDiscuss() {
        discuss("")
        discuss("   ")
        discuss("@codex 在吗")
        discuss("谢谢")
    }

    @Test
    fun theReadOnlyDirectiveIsPrependedAndTheUserTextIsUntouched() {
        val prompt = CognitiveFlow.readOnlyPrompt("怎么设计这个队列？")
        assertTrue(prompt.startsWith(CognitiveFlow.READ_ONLY_DIRECTIVE), "只读前置必须在最前")
        assertTrue(prompt.endsWith("怎么设计这个队列？"), "用户原话一字不动")
        // 三条禁令缺一条就给「调研」留了个写盘的后门。
        assertTrue(CognitiveFlow.READ_ONLY_DIRECTIVE.contains("Do NOT create, edit, delete"), "禁写文件")
        assertTrue(CognitiveFlow.READ_ONLY_DIRECTIVE.contains("git commands that write"), "禁写 git")
        assertTrue(CognitiveFlow.READ_ONLY_DIRECTIVE.contains("builds, installs"), "禁跑改动工作区的命令")
    }

    @Test
    fun readOnlyToolAllowlistFailsClosed() {
        assertTrue(CognitiveFlow.allowsTool("Read"))
        assertTrue(CognitiveFlow.allowsTool("read_file"))
        assertTrue(CognitiveFlow.allowsTool("mcp__fs__read_file"), "命名空间前缀要归一后再比")
        assertTrue(CognitiveFlow.allowsTool("Grep"))
        assertTrue(CognitiveFlow.allowsTool("web_search"))

        assertFalse(CognitiveFlow.allowsTool("Write"), "写工具必须拦")
        assertFalse(CognitiveFlow.allowsTool("edit"))
        assertFalse(CognitiveFlow.allowsTool("apply_patch"))
        assertFalse(CognitiveFlow.allowsTool("Bash"), "能改盘的 shell 一律不放行（白名单口径）")
        assertFalse(CognitiveFlow.allowsTool(null), "没有名字无法证明是读 ⇒ fail-closed")
        assertFalse(CognitiveFlow.allowsTool("   "))
        assertFalse(CognitiveFlow.allowsTool("some_new_tool_v3"), "未知工具默认不放行")
    }

    @Test
    fun oneTurnPerAgent() {
        assertEquals(1, CognitiveFlow.MAX_AGENT_TURNS, "规格 1.2：每人只发言 1 轮")
    }
}
