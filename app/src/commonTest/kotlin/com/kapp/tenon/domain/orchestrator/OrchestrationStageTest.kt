// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 协同工单生命周期阶段推导测试。
 *
 * 验证基于“当前在等谁”的纯函数优先管道判定准则。
 */
class OrchestrationStageTest {

    @Test
    fun deriveEndedReturnsMergedOrDiscarded() {
        val merged = OrchestrationStage.derive(isEnded = true, isMerged = true)
        assertEquals(OrchestrationStage.Ended.Merged, merged)

        val discarded = OrchestrationStage.derive(isEnded = true, isMerged = false, discardReason = "user cancelled")
        assertTrue(discarded is OrchestrationStage.Ended.Discarded)
        assertEquals("user cancelled", discarded.reason)
    }

    @Test
    fun deriveClarificationPreemptsOtherWaitingStates() {
        val stage = OrchestrationStage.derive(
            isEnded = false,
            hasPendingClarification = true,
            isReadyForReview = true,
            blockedReason = "some error",
        )
        assertEquals(OrchestrationStage.PersonWaiting.Clarification, stage)
    }

    @Test
    fun deriveReviewWhenReady() {
        val stage = OrchestrationStage.derive(
            isEnded = false,
            isReadyForReview = true,
        )
        assertEquals(OrchestrationStage.PersonWaiting.Review, stage)
    }

    @Test
    fun deriveBlockedWhenBlockedReasonPresent() {
        val stage = OrchestrationStage.derive(
            isEnded = false,
            blockedReason = "git apply conflict in LazyList.kt",
        )
        assertTrue(stage is OrchestrationStage.PersonWaiting.Blocked)
        assertEquals("git apply conflict in LazyList.kt", stage.reason)
    }

    @Test
    fun deriveExecutingWithProgress() {
        val stage = OrchestrationStage.derive(
            isEnded = false,
            isExecuting = true,
            currentPhase = "运行单元测试",
            progressPercent = 80,
        )
        assertTrue(stage is OrchestrationStage.Working.Executing)
        assertEquals("运行单元测试", stage.phase)
        assertEquals(80, stage.progressPercent)
    }

    @Test
    fun deriveIdleByDefaultWhenNoActionPending() {
        val stage = OrchestrationStage.derive(isEnded = false)
        assertEquals(OrchestrationStage.Idle, stage)
    }
}
