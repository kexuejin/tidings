// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import com.kapp.tidings.core.protocol.AgentSessionPick.Candidate
import com.kapp.tidings.core.protocol.AgentSessionPick.Result
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「这条消息交给哪个会话」的判定。
 *
 * 用户的规则：**绑了工作区就在该工作区做；没绑就在未绑工作区的会话做。**
 * agent 本身（dsh / codex）不绑工作区也能对话 —— 所以「未绑定」是一等状态。
 */
class AgentSessionPickTest {

    private fun c(id: String, ws: String? = null) = Candidate(sessionId = id, wsId = ws)

    /**
     * **绑定了工作区**：只在该工作区的会话里选。
     */
    @Test
    fun aBoundAgentPicksFromItsWorkspace() {
        val got = AgentSessionPick.pick(
            boundWsId = "ws-1",
            candidates = listOf(c("s-unbound"), c("s-ws1", "ws-1"), c("s-ws2", "ws-2")),
        )
        assertEquals(Result.Pick("s-ws1"), got)
    }

    /**
     * **绑定了但该工作区没有会话 ⇒ 明确拒绝，不跨工作区借用。**
     *
     * 借用会让命令跑在另一个项目目录里 —— 那正是最需要防的一类错误。
     */
    @Test
    fun aBoundAgentNeverBorrowsFromAnotherWorkspace() {
        val got = AgentSessionPick.pick(
            boundWsId = "ws-1",
            candidates = listOf(c("s-unbound"), c("s-ws2", "ws-2")),
        )
        assertEquals(Result.NoSessionInWorkspace, got)
    }

    /**
     * **未绑定**：只在不带工作区的会话里选 —— 这是用户说的「不绑也能对话」。
     */
    @Test
    fun anUnboundAgentPicksAnUnboundSession() {
        val got = AgentSessionPick.pick(
            boundWsId = null,
            candidates = listOf(c("s-ws1", "ws-1"), c("s-free")),
        )
        assertEquals(Result.Pick("s-free"), got)
    }

    /**
     * **未绑定时不许借用一个有工作区的会话。**
     *
     * 借了等于「没绑定」在执行时悄悄变成「绑定到别人挑的那个工作区」，
     * 与用户明确说过的语义相反。
     */
    @Test
    fun anUnboundAgentNeverBorrowsAWorkspaceSession() {
        val got = AgentSessionPick.pick(
            boundWsId = null,
            candidates = listOf(c("s-ws1", "ws-1"), c("s-ws2", "ws-2")),
        )
        assertEquals(Result.NoUnboundSession, got)
    }

    /** 候选顺序由调用方给（通常 updated_at 倒序）：同组内取第一个。 */
    @Test
    fun theCallersOrderDecidesWithinTheSameGroup() {
        val got = AgentSessionPick.pick(
            boundWsId = "ws-1",
            candidates = listOf(c("s-recent", "ws-1"), c("s-older", "ws-1")),
        )
        assertEquals(Result.Pick("s-recent"), got)
    }

    /** 空串工作区等同未绑定（线上取值可能带空串，别让它成为「绑定了一个叫空串的工作区」）。 */
    @Test
    fun aBlankWorkspaceIdCountsAsUnbound() {
        val got = AgentSessionPick.pick(
            boundWsId = "   ",
            candidates = listOf(c("s-free")),
        )
        assertEquals(Result.Pick("s-free"), got)
    }

    /** 完全没有会话时，按绑定与否给出不同的原因。 */
    @Test
    fun anEmptyCandidateListReportsTheRightReason() {
        assertEquals(
            Result.NoSessionInWorkspace,
            AgentSessionPick.pick(boundWsId = "ws-1", candidates = emptyList()),
        )
        assertEquals(
            Result.NoUnboundSession,
            AgentSessionPick.pick(boundWsId = null, candidates = emptyList()),
        )
    }
    // ------------------------------------------------ 用户显式指定会话

    /**
     * **指定了就按指定的来**（哪怕它不是最活跃的那条）。
     *
     * 「可以指定，也可以不指定，不指定就按默认的来。」
     */
    @Test
    fun anExplicitChoiceWinsOverTheDefault() {
        val got = AgentSessionPick.pick(
            boundWsId = "ws-1",
            candidates = listOf(c("s-recent", "ws-1"), c("s-chosen", "ws-1")),
            requestedSessionId = "s-chosen",
        )
        assertEquals(Result.Pick("s-chosen"), got)
    }

    /**
     * **指定的会话已经不在时，如实报「它没了」，不偷偷回退到默认。**
     *
     * 回退会让用户以为命令进了 A、实际进了 B —— 这比报错严重得多。
     */
    @Test
    fun aRequestedSessionThatIsGoneIsReportedNotSwapped() {
        val got = AgentSessionPick.pick(
            boundWsId = "ws-1",
            candidates = listOf(c("s-other", "ws-1")),
            requestedSessionId = "s-gone",
        )
        assertEquals(Result.RequestedSessionGone, got)
    }

    /** 指定值里的空白等于没指定（走默认）。 */
    @Test
    fun aBlankRequestedIdFallsBackToTheDefault() {
        val got = AgentSessionPick.pick(
            boundWsId = "ws-1",
            candidates = listOf(c("s-recent", "ws-1")),
            requestedSessionId = "   ",
        )
        assertEquals(Result.Pick("s-recent"), got)
    }

    /**
     * **指定也受工作区约束**：指定的会话若属于别的工作区，不在候选里 ⇒ 报不存在。
     *
     * 这不是多余的限制：候选本来就只是「该 agent 在该工作区的会话」，
     * 指定一个别的工作区的会话，等于绕过归属判定。
     */
    @Test
    fun aRequestedSessionOutsideTheCandidatesIsRejected() {
        val got = AgentSessionPick.pick(
            boundWsId = "ws-1",
            candidates = listOf(c("s-ws1", "ws-1")),
            requestedSessionId = "s-ws2",
        )
        assertEquals(Result.RequestedSessionGone, got)
    }
}
