// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

/**
 * 快路径 + 慢路径：常规流转交给确定性规约，**只有规约判不了的时候才请主理人**。
 *
 * 为什么不是「每一步都问一次」：一条四棒链加两轮返工就是八到十次模型调用，
 * 每次十几秒，用户看到的是「卡住了」而不是「在思考」。而绝大多数流转是编制里
 * 已经写明的常识（干完就该审、全 PASS 就该收工）—— 让模型重新想一遍既贵又会漂。
 *
 * 反过来说，**规约说不清的地方必须有人接**：审查没给结论、同一棒第二次被打回、
 * 返工额度用尽。这些正是「专家该出场」的时刻，也正是从前直接判死的地方。
 */
class EscalatingStepDecider(
    private val rules: StepDecider,
    private val leader: StepDecider? = null,
    /** 规约的返工上限（与装配进去的 [ReviewPipeline] 同数）：升级后的额外额度按它算。 */
    private val maxAttempts: Int = ReviewPipeline.DEFAULT_MAX_ATTEMPTS,
) : StepDecider {

    override suspend fun decide(input: StepInput): StepDecision {
        val baseline = rules.decide(input)
        val expert = leader ?: return baseline
        if (!needsLeader(input, baseline)) return baseline
        // 主理人不可用（超时 / 解析失败 / 抛异常）⇒ 回落规约的结论。
        // 绝不因为「请不动专家」就把一段本来能跑的链停下。
        val escalated = runCatching { expert.decide(input) }.getOrNull() ?: return baseline
        return guard(input, escalated) ?: baseline
    }

    /**
     * 歧义信号（纯函数，便于钉住）：只有「规约给不出正常推进」时才升级。
     *
     * 注意 [StepDecision.Finish] 与 [StepDecision.Next] **不升级**：它们是收工与常规推进，
     * 在这两处插入一次模型调用，是纯粹的延迟与费用。
     */
    fun needsLeader(input: StepInput, baseline: StepDecision): Boolean = when (baseline) {
        is StepDecision.Next -> false
        StepDecision.Finish -> false
        is StepDecision.Spawn -> false
        // 审查没给可判结论（格式没遵守 / 真问题）或返工额度用尽：值得问一次。
        is StepDecision.Stop -> true
        // 同一棒第二次被打回：方向可能就错了，返工第三次大概率还是错。
        is StepDecision.Rework -> input.history.any { it.action.startsWith("rework") }
    }

    /**
     * 硬边界：主理人的决定也不许突破安全阀。返回 null = 这个决定不能用，回落规约。
     *
     * 两条判据，各封一种「专家越权」：
     * 1. **返工次数**有封顶 —— 没有上限的自我延期等于没有熔断；
     * 2. **审查没给 PASS 时不许收工** —— 这是最危险的跳级：一句 REJECT（或一句
     *    "结论没写清"）被主理人判成 "finish"，用户看到的是一张绿色的合流卡，
     *    而底下是一份被否决过的改动。专家可以换人、可以加棒、可以叫停，但不能
     *    把"没通过"说成"通过"。
     *
     * 插棒数量不在这一层判 —— 执行器有 [StageChainRunner.MAX_DYNAMIC_STAGES] 兜底，
     * 两处都判会让「上限是几」出现两个答案。
     */
    private fun guard(input: StepInput, decision: StepDecision): StepDecision? {
        val reviewNotPassed = input.verdict != ReviewVerdict.PASS && input.stageIndex > 0
        return when (decision) {
            is StepDecision.Finish -> if (reviewNotPassed) null else decision
            is StepDecision.Rework ->
                if (decision.attempt > maxAttempts + ESCALATION_ATTEMPT_ALLOWANCE) null else decision
            is StepDecision.Next ->
                if (decision.stageIndex in 0..input.stages.size) decision else null
            is StepDecision.Spawn -> decision
            is StepDecision.Stop -> decision
        }
    }

    companion object {
        /**
         * 升级之后允许在规约熔断点再试几轮：这是「专家接管」的价值所在，
         * 但仍要封顶 —— 没有上限的自我延期等于没有熔断。
         */
        const val ESCALATION_ATTEMPT_ALLOWANCE: Int = 2
    }
}
