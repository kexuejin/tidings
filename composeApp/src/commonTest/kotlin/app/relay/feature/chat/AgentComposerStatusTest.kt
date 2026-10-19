// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import app.relay.app.STALE_BUSY_TIMEOUT_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 输入区 agent 状态行的判据（真机反馈 #3）。
 *
 * 每一条都对应一个**真实信号**的翻译；重点是「没有证据时不出现」——
 * 编一个「思考中」正是这条需求要避免的事。僵死判据与工作区看板、控制器自愈
 * 同源（`isStaleBusy` / [STALE_BUSY_TIMEOUT_MS]）。
 */
class AgentComposerStatusTest {

    private val now = 1_700_000_000_000L

    private fun status(
        agentInvolved: Boolean = true,
        sessionState: String? = "busy",
        lastActivityAt: Long = now - 1_000L,
        hasPendingApproval: Boolean = false,
        hasActiveRun: Boolean = false,
    ) = agentComposerStatusOf(
        agentInvolved = agentInvolved,
        sessionState = sessionState,
        lastActivityAt = lastActivityAt,
        hasPendingApproval = hasPendingApproval,
        hasActiveRun = hasActiveRun,
        now = now,
    )

    @Test
    fun plainHumanConversationStaysSilent() {
        assertNull(status(agentInvolved = false, sessionState = "busy"))
    }

    @Test
    fun pendingApprovalWinsOverBusy() {
        // 用户此刻唯一能推进的事，比「busy」更该被说出来。
        assertEquals(
            AgentComposerStatus.AwaitingApproval,
            status(sessionState = "busy", hasPendingApproval = true),
        )
    }

    @Test
    fun recentBusyIsThinking() {
        assertEquals(AgentComposerStatus.Thinking, status(sessionState = "busy"))
    }

    @Test
    fun staleBusyIsUnresponsive() {
        assertEquals(
            AgentComposerStatus.Unresponsive,
            status(sessionState = "busy", lastActivityAt = now - STALE_BUSY_TIMEOUT_MS - 1),
        )
    }

    @Test
    fun busyWithoutAnyTimestampStaysThinking() {
        // lastActivityAt = 0 = 对端没报时间。isStaleBusy 答「无法判断」，那就保留
        // 宿主自报的 busy，不凭空说它死了。
        assertEquals(AgentComposerStatus.Thinking, status(sessionState = "busy", lastActivityAt = 0L))
    }

    @Test
    fun runningRunCountsAsThinkingEvenWhenSessionSaysIdle() {
        assertEquals(
            AgentComposerStatus.Thinking,
            status(sessionState = "idle", hasActiveRun = true),
        )
    }

    @Test
    fun runningRunWithStaleActivityIsUnresponsive() {
        assertEquals(
            AgentComposerStatus.Unresponsive,
            status(
                sessionState = "idle",
                hasActiveRun = true,
                lastActivityAt = now - STALE_BUSY_TIMEOUT_MS - 1,
            ),
        )
    }

    @Test
    fun errorIsStopped() {
        assertEquals(AgentComposerStatus.Stopped, status(sessionState = "error"))
    }

    @Test
    fun idleAndDoneAndUnknownStatesStaySilent() {
        assertNull(status(sessionState = "idle"))
        assertNull(status(sessionState = "done"))
        assertNull(status(sessionState = "archived"))
        // 协议取值域不封闭：将来多一档不该在界面上被翻译成一句假话。
        assertNull(status(sessionState = "some-future-state"))
        assertNull(status(sessionState = null))
    }
}
