// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.TeamMemberInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 卡上「请 TA 复检」的两条判据：**该说什么话**、**该找谁**。
 *
 * 两条都要在纯函数层钉死，因为它们各自的错法都不会报错：
 * - 提示词里不抄 diff ⇒ 审查员审的是**改动前的**工作区，会给出一份诚实但与本次改动
 *   无关的意见（最贵的那种假结论）；
 * - 找错人 / 找了一个不在位的人 ⇒ 卡上那颗按钮点下去要么静默失败，要么派给一个
 *   `driverKey` 不在本机的组员，换一张「no driver in place」的失败卡。
 */
class CardReviewAskTest {

    private fun spec(
        agentId: String,
        kind: String,
        driverKey: String,
        customPrompt: String? = null,
    ) = TeamExecutionMemberSpec(
        agentId = agentId,
        kind = kind,
        driverKey = driverKey,
        customPrompt = customPrompt,
    )

    private fun declared(agentId: String, name: String, role: String = "reviewer") =
        TeamMemberInfo(agentId = agentId, name = name, role = role)

    private fun profile(vararg members: TeamExecutionMemberSpec) = TeamExecutionProfile(
        dirId = "dir-1",
        leaderAgentId = "lead",
        updatedAt = 0L,
        members = members.toList(),
    )

    // ------------------------------------------------------------ 说什么话

    @Test
    fun theDiffIsInlinedBecauseTheWorkspaceIsStillUnpatched() {
        val instruction = CardReviewAsk.instruction(
            goal = "修好顶栏溢出",
            roleLabel = "质量",
            patch = "diff --git a/A.kt b/A.kt\n+++ b/A.kt\n@@ -1 +1 @@\n-old\n+new\n",
        )
        assertTrue(
            instruction.contains("NOT in the workspace yet"),
            "必须说清改动还没落地，否则审查员会去审旧基线：$instruction",
        )
        assertTrue(instruction.contains("+new"), "diff 必须抄进提示词：$instruction")
        assertTrue(instruction.contains("-old"))
        assertTrue(instruction.contains("修好顶栏溢出"), "目标要在里面：$instruction")
        assertTrue(
            instruction.contains("explicit final verdict line") ||
                instruction.contains("verdict"),
            "复用审查棒指令 ⇒ 必须要求一句显式结论：$instruction",
        )
        assertFalse(instruction.contains("diff truncated"), "没超预算就不许说截断")
    }

    @Test
    fun aHugePatchIsCutAtALineBoundaryAndSaysHowMuchIsMissing() {
        val line = "+" + "x".repeat(60) + "\n"
        val huge = buildString {
            append("diff --git a/A.kt b/A.kt\n")
            repeat(200) { append(line) }
        }
        val cut = CardReviewAsk.excerpt(huge, budget = 500)
        assertTrue(cut.truncated)
        assertTrue(cut.droppedChars > 0, "裁掉多少要说得出：${cut.droppedChars}")
        assertTrue(cut.text.endsWith("x"), "裁在行边界：最后一行是完整的 diff 行")
        assertEquals(60 + 1, cut.text.lines().last().length, "整行保留，不切半行")

        val instruction = CardReviewAsk.instruction(goal = "g", roleLabel = null, patch = huge, budget = 500)
        assertTrue(instruction.contains("diff truncated"), "截断必须如实说：$instruction")
        assertTrue(instruction.contains("${cut.droppedChars}"), "尾注要带被裁的字符数")
    }

    @Test
    fun aTaskWithoutAPatchSaysSoInsteadOfInventingAnEmptyDiff() {
        val instruction = CardReviewAsk.instruction(goal = "g", roleLabel = null, patch = "   \n ")
        assertTrue(instruction.contains("no patch was harvested"), "没有补丁就如实说没有：$instruction")
        assertFalse(instruction.contains("```diff"), "不许摆一个空 diff 块装样子")
    }

    // -------------------------------------------------------------- 找谁

    @Test
    fun theFirstReviewerThatIsActuallyInPlaceGetsTheButton() {
        val p = profile(
            spec("w1", TeamExecutionKinds.WORKER, "codex"),
            // 第一位审查员的底座不在本机：跳过它，别给一颗点了必失败的按钮。
            spec("r1", TeamExecutionKinds.REVIEWER, "claude"),
            spec("r2", TeamExecutionKinds.REVIEWER, "opencode"),
        )
        val picked = assertNotNull(
            CardReviewAsk.pickReviewTarget(
                profile = p,
                declared = listOf(declared("w1", "coder", "开发"), declared("r1", "qa"), declared("r2", "质量")),
                available = setOf("codex", "opencode"),
                excludeAgentId = "w1",
            ),
        )
        assertEquals("质量", picked.name, "编制顺序即候选顺序")
        assertEquals("质量", picked.token, "动作串里用的是组员名（token 判据按 name 匹配）")
    }

    @Test
    fun noReviewerOrNoControlPlaneMeansNoButtonAtAll() {
        val workersOnly = profile(spec("w1", TeamExecutionKinds.WORKER, "codex"))
        assertNull(
            CardReviewAsk.pickReviewTarget(workersOnly, listOf(declared("w1", "coder")), setOf("codex"), "w1"),
            "编制里没有审查员 ⇒ 没人可问，不给按钮",
        )
        val withReviewer = profile(
            spec("w1", TeamExecutionKinds.WORKER, "codex"),
            spec("r1", TeamExecutionKinds.REVIEWER, "claude"),
        )
        assertNull(
            CardReviewAsk.pickReviewTarget(withReviewer, listOf(declared("r1", "qa")), null, "w1"),
            "控制面读不到（available == null）时不猜：读不到 ≠ 可用",
        )
        assertNull(
            CardReviewAsk.pickReviewTarget(withReviewer, listOf(declared("r1", "qa")), setOf("codex"), "w1"),
            "审查员底座不在位 ⇒ 不派一颗必然失败的按钮",
        )
    }

    @Test
    fun aReviewerWhoseSeatNameIsGoneIsSkippedInsteadOfGuessed() {
        // 编制里有这个人、但席位声明里查不到名字 ⇒ 动作串没法落（token 判据按 name）。
        // 猜一个 agentId 顶上去，等于让卡上的按钮指向一个 @ 不到的人。
        val p = profile(
            spec("w1", TeamExecutionKinds.WORKER, "codex"),
            spec("r1", TeamExecutionKinds.REVIEWER, "claude"),
        )
        assertNull(
            CardReviewAsk.pickReviewTarget(p, listOf(declared("w1", "coder")), setOf("codex", "claude"), "w1"),
        )
    }

    @Test
    fun theWorkerNeverGetsAskedToReviewItsOwnWork() {
        // 同一个人既标了 worker 又标了 reviewer（手改档）时，别把活派回给刚干完的那个人。
        val p = profile(
            spec("w1", TeamExecutionKinds.WORKER, "codex"),
            spec("w1", TeamExecutionKinds.REVIEWER, "codex"),
        )
        assertNull(
            CardReviewAsk.pickReviewTarget(p, listOf(declared("w1", "coder")), setOf("codex"), "w1"),
        )
    }
}
