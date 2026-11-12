// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks 汇总的诚实判据：SKIP 在线上借 Pending 承载（StageChain/ReviewerAudit
 * 的既定约定），**任何非 Done 项都不能被汇总成“全部通过”**。
 */
class AgentCardChecksSummaryTest {

    private fun check(id: String, status: AgentCardStepStatus) =
        AgentCardCheck(id = id, label = id, status = status)

    @Test
    fun allPendingIsIncompleteNotPassed() {
        val summary = agentCardChecksSummaryOf(
            listOf(
                check("a", AgentCardStepStatus.Pending),
                check("b", AgentCardStepStatus.Pending),
                check("c", AgentCardStepStatus.Pending),
            ),
        )
        assertEquals(0, summary.passed)
        assertEquals(3, summary.incomplete)
        assertEquals(0, summary.failed + summary.blocked)
    }

    @Test
    fun mixedDoneAndSkippedHasIncomplete() {
        val summary = agentCardChecksSummaryOf(
            listOf(
                check("a", AgentCardStepStatus.Done),
                check("b", AgentCardStepStatus.Pending),
                check("c", AgentCardStepStatus.Pending),
            ),
        )
        assertEquals(1, summary.passed)
        assertEquals(2, summary.incomplete)
        assertTrue(summary.incomplete > 0)
    }

    @Test
    fun blockedAndFailedAreReportedSeparatelyFromIncomplete() {
        val summary = agentCardChecksSummaryOf(
            listOf(
                check("a", AgentCardStepStatus.Done),
                check("b", AgentCardStepStatus.Failed),
                check("c", AgentCardStepStatus.Blocked),
                check("d", AgentCardStepStatus.Running),
            ),
        )
        assertEquals(1, summary.failed)
        assertEquals(1, summary.blocked)
        assertEquals(1, summary.incomplete)
        assertEquals(4, summary.total)
    }
}
