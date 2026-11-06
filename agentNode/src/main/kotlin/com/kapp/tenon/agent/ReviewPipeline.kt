// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

/** 审查棒的结论。只认显式结论行，散文里出现的 pass/reject 不算数。 */
enum class ReviewVerdict {
    PASS,
    REJECT,
    ;

    companion object {
        private val EXPLICIT_REJECT = Regex("(?i)\\bVERDICT\\s*[:=]\\s*REJECT\\b")
        private val EXPLICIT_PASS = Regex("(?i)\\bVERDICT\\s*[:=]\\s*PASS\\b")
        private val LINE_VERDICT = Regex("(?im)^\\s*(PASS|REJECT)\\b")

        /**
         * 从驱动输出里解析结论：显式 `VERDICT: X` 优先，其次是独立的结论行。
         * 同一份输出里两种结论都出现时按更严的 REJECT 裁决 —— 自相矛盾的审查不是通过。
         * 解析不到返回 null，调用方必须 fail-closed，不许当成 PASS。
         */
        fun parseOf(text: String?): ReviewVerdict? {
            if (text.isNullOrBlank()) return null
            if (EXPLICIT_REJECT.containsMatchIn(text)) return REJECT
            if (EXPLICIT_PASS.containsMatchIn(text)) return PASS
            val lines = LINE_VERDICT.findAll(text).map { it.groupValues[1].uppercase() }.toList()
            return if ("REJECT" in lines) REJECT else if ("PASS" in lines) PASS else null
        }
    }
}

/**
 * 确定性**线性多棒**审查流水线（P2 规格 3）：一条 List&lt;Stage&gt; 单向推进，
 * 没有 DAG、没有扇出、没有投票。REJECT 把意见打包打回 Worker 并让自愈轮次 +1，
 * 所有审查棒 PASS 才给合流信号；轮次用尽即熔断，绝不无限循环。
 *
 * 纯函数、无内部状态：attempt 由调用方传入，便于测试与并发安全，也避免跨链串状态。
 */
class ReviewPipeline(private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS) {

    sealed interface Advance {
        /** 走到下一根棒（保持声明顺序）。 */
        data class Next(val stageIndex: Int) : Advance

        /** REJECT：带着审查意见回到第 0 棒，attempt 已经 +1。 */
        data class BackToWorker(val attempt: Int, val feedback: String) : Advance

        /** 轮次用尽 / 结论缺失 / 形状非法：当场停机，不许合流。 */
        data class Blocked(val reason: String) : Advance

        /** 全部审查棒 PASS：给宿主发合流信号（真正落盘仍走既有合流动作）。 */
        data object Merge : Advance
    }

    fun advance(
        stageIndex: Int,
        stageCount: Int,
        attempt: Int,
        verdict: ReviewVerdict?,
        feedback: String,
    ): Advance {
        if (stageCount < 2 || stageIndex !in 0 until stageCount) {
            return Advance.Blocked("review pipeline needs one worker and at least one reviewer")
        }
        // Worker 棒没有审查结论：单向推进到第一根审查棒。
        if (stageIndex == 0) return Advance.Next(1)
        // 审查棒没给结论 = fail-closed。把「没说」记成通过是最危险的假全绿。
        if (verdict == null) {
            return Advance.Blocked("review verdict missing at stage " + (stageIndex + 1))
        }
        if (verdict == ReviewVerdict.REJECT) {
            if (attempt >= maxAttempts) {
                return Advance.Blocked(
                    "review rejected after " + attempt + " attempts: " + feedback.take(StageChain.MAX_SUMMARY),
                )
            }
            return Advance.BackToWorker(attempt + 1, feedback.take(StageChain.MAX_SUMMARY))
        }
        return if (stageIndex == stageCount - 1) Advance.Merge else Advance.Next(stageIndex + 1)
    }

    companion object {
        /** 与单棒工单的自愈上限同数：审查打回最多 3 轮，之后转人工。 */
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
    }
}
