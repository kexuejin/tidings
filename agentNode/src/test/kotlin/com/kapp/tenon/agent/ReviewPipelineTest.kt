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
        assertTrue(advance.feedback.contains("性能审查：高并发锁耗时超标"), "审查意见要原样打包给下一轮")
        assertTrue(advance.feedback.contains("返工"), "「这是返工」要显式写进指令，不能只当背景交接")
    }

    /**
     * 真机反向验证：审查意见的「问题清单 + VERDICT」在发言**尾部**，而开场核对在头部。
     *
     * 曾经 REJECT 反馈被 `take(StageChain.MAX_SUMMARY)` 砍成头 200 字，worker 只看到
     * 「基线核对通过…我只审查」，于是判断「活已干完」⇒ 零补丁 ⇒ 链停摆。
     * 把实现改回 200 字头部预算，这条用例必须报红。
     */
    @Test
    fun aRejectKeepsTheProblemListAndVerdictThatLiveAtTheTail() {
        val feedback = buildString {
            appendLine("基线核对通过：HEAD 与交接一致。我只审查，没有改实现。")
            repeat(12) { i -> appendLine("逐条核对 AC$i 的实现细节与测试输出，均无异常。") }
            appendLine("## 问题清单")
            appendLine("1. [阻塞，AC4] float() 接受 nan/inf，退出码 0，需 math.isfinite 校验。")
            appendLine("## 最终判定")
            appendLine("VERDICT: REJECT")
        }
        assertTrue(feedback.length > StageChain.MAX_SUMMARY, "探针前提：意见长过 200 字交接预算")
        val advance = pipeline.advance(2, 3, attempt = 1,
            verdict = ReviewVerdict.REJECT, feedback = feedback)
        assertTrue(advance is ReviewPipeline.Advance.BackToWorker)
        assertTrue(advance.feedback.contains("AC4"), "问题清单不能被交接摘要预算砍掉")
        assertTrue(advance.feedback.contains("math.isfinite"), "具体修法要送达 worker")
        assertTrue(advance.feedback.contains("VERDICT: REJECT"), "结论行要送达 worker")
        assertTrue(advance.feedback.length > StageChain.MAX_SUMMARY, "返工意见不受 200 字交接预算约束")
    }

    /** 超长意见：宁可省掉开场复述，也要把尾部的结论留下。 */
    @Test
    fun oversizedReviewFeedbackKeepsTheTailWhereTheVerdictLives() {
        val feedback = "开场与逐条复述。".repeat(500) + "问题清单：AC4 nan/inf 退出码 0。VERDICT: REJECT"
        val advance = pipeline.advance(2, 3, attempt = 1,
            verdict = ReviewVerdict.REJECT, feedback = feedback)
        assertTrue(advance is ReviewPipeline.Advance.BackToWorker)
        assertTrue(advance.feedback.contains("VERDICT: REJECT"), "超长时不能把结论砍掉")
        assertTrue(advance.feedback.contains("问题清单"))
        assertTrue(
            advance.feedback.length <= ReviewPipeline.MAX_REVIEW_FEEDBACK + 200,
            "超长意见仍要有上限，不能把整篇日志塞进 prompt",
        )
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
