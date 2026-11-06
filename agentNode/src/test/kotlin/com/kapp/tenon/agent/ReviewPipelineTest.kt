// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2 规格 3：确定性**线性多棒**审查。拒绝 DAG、拒绝投票 —— 一条 List&lt;Stage&gt;
 * 单向推进，REJECT 打包意见打回 Worker，全棒 PASS 才给合流信号。
 */
class ReviewPipelineTest {

    private val pipeline = ReviewPipeline(maxAttempts = 3)

    @Test
    fun workerAlwaysAdvancesToTheFirstReviewer() {
        val advance = pipeline.advance(0, 3, attempt = 1, verdict = null, feedback = "")
        assertEquals(ReviewPipeline.Advance.Next(1), advance)
    }

    @Test
    fun allReviewersPassingTriggersMergeOnlyAtTheLastStage() {
        assertEquals(ReviewPipeline.Advance.Next(2),
            pipeline.advance(1, 3, attempt = 1, verdict = ReviewVerdict.PASS, feedback = ""))
        assertEquals(ReviewPipeline.Advance.Merge,
            pipeline.advance(2, 3, attempt = 1, verdict = ReviewVerdict.PASS, feedback = ""))
    }

    @Test
    fun aRejectPacksItsFeedbackAndReturnsToTheWorker() {
        val advance = pipeline.advance(2, 3, attempt = 1,
            verdict = ReviewVerdict.REJECT, feedback = "性能审查：高并发锁耗时超标")
        assertTrue(advance is ReviewPipeline.Advance.BackToWorker, "REJECT 必须打回 Worker，实际 $advance")
        assertEquals(2, advance.attempt, "自愈轮次 +1")
        assertEquals("性能审查：高并发锁耗时超标", advance.feedback, "审查意见要原样打包给下一轮")
    }

    @Test
    fun repeatedRejectsBlockAfterTheAttemptCapInsteadOfLoopingForever() {
        val first = pipeline.advance(2, 3, attempt = 1, verdict = ReviewVerdict.REJECT, feedback = "x")
        assertEquals(2, (first as ReviewPipeline.Advance.BackToWorker).attempt)
        val second = pipeline.advance(2, 3, attempt = 2, verdict = ReviewVerdict.REJECT, feedback = "x")
        assertEquals(3, (second as ReviewPipeline.Advance.BackToWorker).attempt)
        val third = pipeline.advance(2, 3, attempt = 3, verdict = ReviewVerdict.REJECT, feedback = "x")
        assertTrue(third is ReviewPipeline.Advance.Blocked, "第 3 轮仍 REJECT 必须熔断，不许无限循环，实际 $third")
    }

    @Test
    fun aMissingVerdictFailsClosedInsteadOfCountingAsPass() {
        val advance = pipeline.advance(1, 3, attempt = 1, verdict = null, feedback = "")
        assertTrue(advance is ReviewPipeline.Advance.Blocked,
            "审查棒没给结论 = 不许冒充通过，实际 $advance")
    }

    @Test
    fun aSingleStageChainCannotClaimAMerge() {
        val advance = pipeline.advance(0, 1, attempt = 1, verdict = null, feedback = "")
        assertTrue(advance is ReviewPipeline.Advance.Blocked, "没有审查棒就没有「全棒 PASS」")
    }

    @Test
    fun verdictParsingOnlyAcceptsAnExplicitVerdictLine() {
        assertEquals(ReviewVerdict.PASS, ReviewVerdict.parseOf("review done\nVERDICT: PASS\nthanks"))
        assertEquals(ReviewVerdict.REJECT, ReviewVerdict.parseOf("VERDICT: REJECT - lock contention"))
        assertEquals(ReviewVerdict.PASS, ReviewVerdict.parseOf("PASS"))
        assertNull(ReviewVerdict.parseOf("the patch was not rejected, all good"), "散文里的 pass/reject 不算结论")
        assertNull(ReviewVerdict.parseOf(null))
        assertEquals(ReviewVerdict.REJECT, ReviewVerdict.parseOf("VERDICT: PASS but earlier VERDICT: REJECT"),
            "一份输出里出现两种结论时按更严的裁决")
    }
}
