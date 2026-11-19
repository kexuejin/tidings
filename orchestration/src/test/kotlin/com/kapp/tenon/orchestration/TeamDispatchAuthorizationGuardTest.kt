// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「授权跟着**编译结果**走，且锚点先判」的接线守卫。
 *
 * 单测钉得住 [TeamDispatchAuthorization] 与 `Compiled.participants`，但钉不住派单出口
 * 把哪个东西喂给了它、以及**判在编译之前还是之后**：
 *
 * - 只判锚点 ⇒ 主理人换人干活时「拿 A 的授权跑 B」，现场没有任何报错；
 * - 判在编译之后 ⇒ 一个本来无权的发送者，会先拿到「本机没装这个底座」这类
 *   只有宿主才知道的结论（授权拒绝该排在最前面）。
 */
class TeamDispatchAuthorizationGuardTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )

    private val teamExit: String = GuardSourceScan.bracedBlockAfter(
        orchestrator,
        "private suspend fun runTeamDispatch(",
    )

    /** 锚点（被点名的人）必须在编译**之前**判：失败快、不让人借一次必拒的派单试探底座、也不给顶上。 */
    @Test
    fun theAnchorIsJudgedBeforeCompiling() {
        val anchorAt = teamExit.indexOf("verdictFor(target.member.agentId)")
        val compileAt = teamExit.indexOf("TeamPlanCompiler.compile(")
        assertTrue(anchorAt >= 0, "入口必须有一次锚点授权判定")
        assertTrue(compileAt > anchorAt, "锚点判定必须排在编译之前（否则无权者会先读到宿主的驱动清单）")
        assertTrue(
            teamExit.contains("publishUnauthorizedDispatch("),
            "锚点被拒要出卡并记申请（不能只是 return）",
        )
    }

    /** 编译之后再判**这次真正会被调起的人**；主理人换人时，这条是唯一的防线。 */
    @Test
    fun theCompiledParticipantsAreJudgedAfterCompiling() {
        val compileAt = teamExit.indexOf("TeamPlanCompiler.compile(")
        val participantsAt = teamExit.indexOf("firstDenied(compiled.participants)")
        assertTrue(participantsAt > compileAt, "参与人判定必须在编译之后（编译才知道谁真的会跑）")
        assertTrue(teamExit.contains("verdictFor(it)"), "判定要逐人做，不能复用一份总判定")
        assertFalse(
            teamExit.contains("val verdict = AgentExecAuthorizer.authorize("),
            "入口那一次「只判锚点就放行整条链」的写法已删除",
        )
    }

    /** 两个拒绝出口共用一份卡面/审批文案：各写一遍必然漂移成「同一件事两种说法」。 */
    @Test
    fun bothDenialExitsShareOnePublisher() {
        var count = 0
        var from = 0
        while (true) {
            val at = teamExit.indexOf("publishUnauthorizedDispatch(", from)
            if (at < 0) break
            count++
            from = at + 1
        }
        assertEquals(2, count, "锚点早拒 + 编译后新增的人被拒，两个出口都走同一个发布器（实际 $count 处）")
        assertFalse(
            teamExit.contains("not authorized for "),
            "拒绝文案只许住在 publishUnauthorizedDispatch 里",
        )
        assertTrue(
            orchestrator.contains("senderPeerId, denial.agentId"),
            "审批申请必须按被拒的那个人记（记成锚点，机主批完还是过不去）",
        )
    }

    /** 守卫自验证：把接线改回「只判锚点」，上面两条必须真的变红。 */
    @Test
    fun removingTheWiringMakesTheGuardFail() {
        val anchorOnly = teamExit.replace(
            "TeamDispatchAuthorization.firstDenied(compiled.participants)",
            "TeamDispatchAuthorization.firstDenied(listOf(target.member.agentId))",
        )
        assertTrue(
            anchorOnly.indexOf("firstDenied(compiled.participants)") < 0,
            "退回锚点授权后，「按参与人判」那一步就不存在了 —— 守卫必须因此变红",
        )
        val noAnchor = teamExit.replace("verdictFor(target.member.agentId)", "")
        assertFalse(
            noAnchor.indexOf("verdictFor(target.member.agentId)") >= 0,
            "摘掉锚点判定后，「判在编译之前」这条必须变红",
        )
    }
}
