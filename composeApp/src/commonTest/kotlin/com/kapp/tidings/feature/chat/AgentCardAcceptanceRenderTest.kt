// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance 升级（Phase 2/3）渲染判据：hunk 行号推演与打回反馈编码都是**纯函数**，
 * 用合成输入钉死 —— 与策略层同一条纪律（不读时钟、不碰 Compose）。
 */
class AgentCardAcceptanceRenderTest {

    // ── agentCardHunkLinesOf：行号只从 hunk 头推演 ────────────────────────

    @Test
    fun hunkLinesNumberContextAndAdditionsSequentially() {
        val lines = agentCardHunkLinesOf(
            listOf(
                "@@ -45,7 +45,8 @@ fun clamp()",
                "-    if (payload.length > 800)",
                "+    val maxBytes = Limits.GROUP_TEXT_BYTES",
                "+    return CodecMsg.clampUtf8(payload, maxBytes)",
                "     }",
            ),
        )
        assertEquals(5, lines.size)
        assertEquals(AgentCardHunkKind.HunkHead, lines[0].kind)
        assertNull(lines[0].number)
        // 删除行只存在于旧侧：不领新侧行号，也不推进新侧光标。
        assertEquals(AgentCardHunkKind.Del, lines[1].kind)
        assertNull(lines[1].number)
        // 新增行从 hunk 头的新侧起点（45）连号。
        assertEquals(AgentCardHunkKind.Add, lines[2].kind)
        assertEquals(45, lines[2].number)
        assertEquals(46, lines[3].number)
        // 上下文行同样领号并推进。
        assertEquals(AgentCardHunkKind.Context, lines[4].kind)
        assertEquals(47, lines[4].number)
    }

    @Test
    fun hunkLinesWithoutHeaderLeaveGutterEmpty() {
        // hunk 头被插件截掉：行照样渲染，gutter 留空 —— 少一个数字比整段不画诚实。
        val lines = agentCardHunkLinesOf(listOf("+added", " ctx"))
        assertEquals(AgentCardHunkKind.Add, lines[0].kind)
        assertNull(lines[0].number)
        assertEquals(AgentCardHunkKind.Context, lines[1].kind)
        assertNull(lines[1].number)
    }

    @Test
    fun hunkLinesTolerateFileHeaderNoise() {
        // 前导噪声行不吃 +/− 极性 —— 按上下文画，但没有 hunk 头 ⇒ 无号。
        // 插件侧截断碎片的宽容解析与 parseAgentCard 同一口径。
        val lines = agentCardHunkLinesOf(listOf("diff --git a/x b/x", "@@ -1,2 +1,3 @@", "+one"))
        assertEquals(AgentCardHunkKind.Context, lines[0].kind)
        assertNull(lines[0].number)
        assertEquals(1, lines[2].number)
    }

    // ── rejectRevisionActionIdOf / feedbackTextOf：单槽编码 ───────────────

    @Test
    fun rejectEncodingCarriesFeedbackAfterPrefix() {
        val actionId = rejectRevisionActionIdOf("补单测；边界 800 换行")
        assertTrue(actionId.startsWith(SYSTEM_ACTION_REJECT_REVISION + "|"))
        assertEquals("补单测；边界 800 换行", feedbackTextOf(actionId))
    }

    @Test
    fun rejectEncodingSeparatorsAreSanitized() {
        // 文本里的竖线会破坏单槽编码的两段结构 —— 编码侧统一换斜杠。
        val actionId = rejectRevisionActionIdOf("a|b")
        assertEquals(SYSTEM_ACTION_REJECT_REVISION + "|a/b", actionId)
        assertEquals("a/b", feedbackTextOf(actionId))
    }

    @Test
    fun rejectEncodingBlankFeedbackKeepsBareAction() {
        assertEquals(SYSTEM_ACTION_REJECT_REVISION, rejectRevisionActionIdOf("   "))
        assertEquals(SYSTEM_ACTION_REJECT_REVISION, rejectRevisionActionIdOf(""))
        // 裸打回（无竖线）不带反馈文本。
        assertNull(feedbackTextOf(SYSTEM_ACTION_REJECT_REVISION))
    }

    @Test
    fun feedbackTextOfIgnoresNonRejectActions() {
        assertNull(feedbackTextOf(SYSTEM_ACTION_MERGE_PATCH))
        assertNull(feedbackTextOf("approve"))
    }
}
