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
            return Advance.BackToWorker(attempt + 1, reworkInstruction(feedback))
        }
        return if (stageIndex == stageCount - 1) Advance.Merge else Advance.Next(stageIndex + 1)
    }

    /**
     * 打回 worker 的**返工指令**：不是棒间交接摘要，不能套 `StageChain.MAX_SUMMARY`
     * 那 200 字的头部预算。
     *
     * 审查发言的形状是「开场核对 → 逐条验证 → 问题清单 → VERDICT」，结论恒在尾部。
     * 真机上把 REJECT 意见 `take(200)` 之后，worker 只收到「基线核对通过…我只审查」，
     * 问题清单和结论全没了 ⇒ 它判断「活已干完」⇒ 零补丁 ⇒ 链停摆。摘要可以截，
     * 返工意见截不得；超长时也要**保留尾部**而不是砍掉最要紧的那一段。
     *
     * 前缀是给 worker 的显式语义：「上一棒交接」这个标题会让人把它读成背景资料，
     * 而返工要求的是逐条修复并产生新改动。
     */
    private fun reworkInstruction(raw: String): String {
        val text = raw.trim()
        val kept = if (text.length <= MAX_REVIEW_FEEDBACK) {
            text
        } else {
            "…（审查发言前段省略）\n" + text.takeLast(MAX_REVIEW_FEEDBACK)
        }
        return REWORK_PREFIX + kept
    }

    companion object {
        /** 与单棒工单的自愈上限同数：审查打回最多 3 轮，之后转人工。 */
        const val DEFAULT_MAX_ATTEMPTS: Int = 3

        /** 返工意见的预算：只进 prompt、不进协议帧，不必受卡面摘要的 200 字约束。 */
        const val MAX_REVIEW_FEEDBACK: Int = 4000

        private const val REWORK_PREFIX =
            "【审查未通过：本棒是返工】请逐条修复下列审查意见指出的问题，改完必须产生新的文件改动；" +
                "确实无需改动时，逐条说明为什么。\n"
    }
}
