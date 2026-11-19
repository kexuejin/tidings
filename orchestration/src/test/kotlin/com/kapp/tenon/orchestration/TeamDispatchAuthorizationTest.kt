// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AgentExecAuthorizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 参与人授权判据的钉子。
 *
 * 最要紧的一条：**判据的输入是「编译结果里的人」，不是 `@` 锚点**。这条规则一旦回退，
 * 主理人换人干活的那一刻就变成「拿 A 的授权跑 B」，而现场没有任何报错。
 */
class TeamDispatchAuthorizationTest {

    private fun verdictOf(vararg allowed: String): (String) -> AgentExecAuthorizer.Verdict = { id ->
        if (id in allowed) AgentExecAuthorizer.Verdict.GrantedDevice
        else AgentExecAuthorizer.Verdict.MemberOnly
    }

    @Test
    fun everyoneAllowedMeansNoDenial() {
        assertNull(TeamDispatchAuthorization.firstDenied(
            listOf("m-coder", "m-reviewer"), verdictOf("m-coder", "m-reviewer"),
        ))
    }

    @Test
    fun nobodyInvokedMeansNoDenial() {
        assertNull(
            TeamDispatchAuthorization.firstDenied(emptyList()) { AgentExecAuthorizer.Verdict.NotAMember },
            "编译被拒时没有授权对象 —— 空集不是「跳过授权」，是没有人要跑",
        )
    }

    /** 按链序报第一个：一次只暴露一个阻塞点，批完再发一次就推进到下一个。 */
    @Test
    fun theFirstDeniedParticipantInChainOrderWins() {
        val denial = TeamDispatchAuthorization.firstDenied(
            listOf("m-coder", "m-reviewer", "m-tester"),
            verdictOf("m-coder", "m-tester"),
        )
        assertEquals("m-reviewer", denial?.agentId, "审查棒没授权时不能说「都通过了」")
        assertEquals(AgentExecAuthorizer.Verdict.MemberOnly, denial?.verdict)
    }

    /** 群主 / 管理员**不默认放行**（口径来自可信设备面板），这里只钉「它们算拒绝」。 */
    @Test
    fun ownerNeedsGrantIsStillADenial() {
        val denial = TeamDispatchAuthorization.firstDenied(listOf("m-coder")) {
            AgentExecAuthorizer.Verdict.OwnerNeedsGrant
        }
        assertEquals(AgentExecAuthorizer.Verdict.OwnerNeedsGrant, denial?.verdict)
    }

    /** 三档放行里的另外两档（本机用户 / 群内任意成员）同样算通过，不能只认「已批设备」。 */
    @Test
    fun agentHostAndAnyMemberBothCountAsAllowed() {
        listOf(
            AgentExecAuthorizer.Verdict.AgentHost,
            AgentExecAuthorizer.Verdict.AnyMemberGranted,
        ).forEach { verdict ->
            assertNull(
                TeamDispatchAuthorization.firstDenied(listOf("m-coder")) { verdict },
                "$verdict 是放行档，不能被判成拒绝",
            )
        }
    }

    /**
     * 同一个人在一份产物里出现两次时只判一次：两次判定之间用户可能刚好批了，
     * 重复判定会让同一份产物给出两种结论。
     */
    @Test
    fun theSamePersonIsJudgedOnce() {
        var calls = 0
        TeamDispatchAuthorization.firstDenied(listOf("m-coder", "m-coder")) {
            calls++
            AgentExecAuthorizer.Verdict.GrantedDevice
        }
        assertEquals(1, calls)
    }

    // ---------------------------------------------------------------- 入口闸

    /**
     * **整队入口的锚点没授权 ≠ 整队没人能干**。锚点只是编制首位（没有点将建议时的
     * 默认落点），把它当成「用户指定的人」会把另一位已被授权的同事能干的活一起挡掉。
     */
    @Test
    fun aTeamEntryPassesWhenAnyOtherWorkerIsAllowed() {
        assertTrue(
            TeamDispatchAuthorization.entryPermits(
                entry = DispatchEntry.TEAM,
                anchorVerdict = AgentExecAuthorizer.Verdict.MemberOnly,
                workers = listOf("m-coder", "m-backend"),
                verdictOf = verdictOf("m-backend"),
            ),
            "锚点被拒、另一位 worker 被允许 ⇒ 整队仍可派（主理人有点将权）",
        )
    }

    /** 一个人都不能干活才拒 —— 这一档必须仍然 fail-closed。 */
    @Test
    fun aTeamEntryIsRejectedWhenNoWorkerIsAllowed() {
        assertFalse(
            TeamDispatchAuthorization.entryPermits(
                entry = DispatchEntry.TEAM,
                anchorVerdict = AgentExecAuthorizer.Verdict.MemberOnly,
                workers = listOf("m-coder", "m-backend"),
                verdictOf = verdictOf("m-reviewer"),
            ),
        )
    }

    /**
     * **单兵入口不许"换人顶上"**：用户点的是这个人，不是这件事。给 [DispatchEntry.MEMBER]
     * 套上「还有别人能干活」的宽容，等于「点 A 派给 B」。
     */
    @Test
    fun aMemberEntryStaysStrictlyOnTheAnchor() {
        assertFalse(
            TeamDispatchAuthorization.entryPermits(
                entry = DispatchEntry.MEMBER,
                anchorVerdict = AgentExecAuthorizer.Verdict.MemberOnly,
                workers = listOf("m-coder", "m-backend"),
                verdictOf = verdictOf("m-backend"),
            ),
            "单兵入口只认锚点：别人被授权不代表可以点 A 派 B",
        )
        assertTrue(
            TeamDispatchAuthorization.entryPermits(
                entry = DispatchEntry.MEMBER,
                anchorVerdict = AgentExecAuthorizer.Verdict.GrantedDevice,
                workers = emptyList(),
                verdictOf = verdictOf(),
            ),
        )
    }

    /** 锚点自己放行就当通过，不必再问别人（少一次台账查询，也少一次口径分叉的机会）。 */
    @Test
    fun anAllowedAnchorShortCircuitsTheGate() {
        var extraCalls = 0
        assertTrue(
            TeamDispatchAuthorization.entryPermits(
                entry = DispatchEntry.TEAM,
                anchorVerdict = AgentExecAuthorizer.Verdict.AgentHost,
                workers = listOf("m-coder", "m-backend"),
                verdictOf = { extraCalls++; AgentExecAuthorizer.Verdict.MemberOnly },
            ),
        )
        assertEquals(0, extraCalls, "锚点已放行时不该再逐个问一遍")
    }
}
