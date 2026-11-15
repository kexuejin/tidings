// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun rejectEncodingClampsExcessiveFeedbackWithinProtocolBudget() {
        val longFeedback = "请保留 build/outputs 目录下的测试报告，仅清理缓存；重新跑一次单元测试与门禁验证，确保跨平台构建无报错与编译警告！"
        val actionId = rejectRevisionActionIdOf(longFeedback)
        assertTrue(
            actionId.length <= com.kapp.tenon.core.protocol.Limits.AGENT_RUN_ID,
            "编码后的 actionId 长度 (${actionId.length}) 必须 <= Limits.AGENT_RUN_ID (64)",
        )
        val feedback = feedbackTextOf(actionId)
        assertEquals(MAX_REJECT_REVISION_FEEDBACK_CHARS, feedback?.length)

        // 验证通过 CodecInvoke 校验网门（模拟宿主接收端 validateInvoke）：
        val invokePayload = kotlinx.serialization.json.buildJsonObject {
            put("invokeId", kotlinx.serialization.json.JsonPrimitive("inv-12345678"))
            put("op", kotlinx.serialization.json.JsonPrimitive(com.kapp.tenon.core.protocol.CodecInvoke.InvokeOps.ACTION))
            put("agentId", kotlinx.serialization.json.JsonPrimitive("agent-code"))
            put("requestedBy", kotlinx.serialization.json.JsonPrimitive("node-phone"))
            put("deadlineMs", kotlinx.serialization.json.JsonPrimitive(30_000))
            put("cardId", kotlinx.serialization.json.JsonPrimitive("card-task-1"))
            put("cardRev", kotlinx.serialization.json.JsonPrimitive(1))
            put("actionId", kotlinx.serialization.json.JsonPrimitive(actionId))
            put("decidedBy", kotlinx.serialization.json.JsonPrimitive("node-phone"))
        }
        assertTrue(
            com.kapp.tenon.core.protocol.CodecInvoke.validateInvoke(invokePayload),
            "超长反馈经 clamp 后必须顺利通过对端 CodecInvoke.validateInvoke 校验，绝不整帧拒收",
        )
    }

    // ── shouldStackCardActionsVertically：按钮自适应垂直堆叠判据 ─────────────

    @Test
    fun adaptiveButtonStackingDecisions() {
        // 单按钮不堆叠
        assertFalse(
            shouldStackCardActionsVertically(1, listOf("停止"), 360f),
            "单按钮应横向满宽，不堆叠",
        )

        // 3 颗按钮恒堆叠
        assertTrue(
            shouldStackCardActionsVertically(3, listOf("A", "B", "C"), 400f),
            "3 颗按钮应垂直堆叠",
        )

        // 2 颗短中文按钮在正常宽度下横排
        assertFalse(
            shouldStackCardActionsVertically(2, listOf("打回", "批准"), 360f),
            "2 颗简短按钮应横排并列",
        )

        // 2 颗长中文按钮触发垂直堆叠
        assertTrue(
            shouldStackCardActionsVertically(2, listOf("在当前草稿上继续修改", "放弃当前，开启新任务"), 360f),
            "长中文词条应垂直堆叠",
        )

        // 2 颗英文长词条触发垂直堆叠
        assertTrue(
            shouldStackCardActionsVertically(2, listOf("Reject & Request Changes", "Approve & Merge Code Patch"), 360f),
            "英文长词条应垂直堆叠",
        )

        // 极窄屏幕（<= 320dp）中长词条触发垂直堆叠
        assertTrue(
            shouldStackCardActionsVertically(2, listOf("合并此项补丁", "查看思考过程"), 300f),
            "极窄屏幕下中长词条应垂直堆叠",
        )
    }
}
