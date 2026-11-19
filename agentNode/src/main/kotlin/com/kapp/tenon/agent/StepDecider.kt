// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

/**
 * 一棒跑完之后「接下来怎么走」的裁决。
 *
 * 存在的理由：从前这一步写死在 [StageChainRunner] 里 —— 要么 `index += 1`，
 * 要么交给 [ReviewPipeline] 那张四选一的纯函数表。于是「计划」只能是一条编译期
 * 定死的直线，唯一的条件分支是「审查 REJECT 就回第 0 棒」。
 *
 * 把它抽成接口之后，宿主可以在同一个插槽里放两种东西：
 * - [DeterministicStepDecider]：既有的规约（逐字等价的默认行为）；
 * - 宿主自己的「主理人」决策器：每步看到真实产出后决定派人 / 返工 / 收工。
 *
 * **接口在 `:agentNode`，进程与模型都在外面** —— 这一层仍然不碰进程、不碰 git、
 * 不碰 UI，判据可以写成普通断言。
 */
fun interface StepDecider {

    /** 同步语义（`suspend` 只是为了让它能等一次模型调用），必须自己保证不抛 —— 抛了按 [StepDecision.Stop] 处置。 */
    suspend fun decide(input: StepInput): StepDecision
}

/**
 * 决策的输入。
 *
 * 每一棒都给**全量上下文**：局部失忆的决策者比没有决策者更危险 —— 它会一本正经地
 * 把已经做完的事再做一遍。
 *
 * @param goal 原始目标（不是改写后的棒指令）：判断「有没有跑偏」的唯一基准。
 * @param stages 当前计划快照（含刚刚跑完的那一棒）；决策器可以据此看还剩谁没跑。
 * @param verdict 本棒的审查结论；null = 不是审查棒，或审查棒没给可判结论（fail-closed）。
 * @param patchPath 本棒收割到的补丁文件指针（驱动侧产物）；null = 这一棒没产出改动。
 *   决策者要判断「改得对不对」必须能看到它 —— 只给 200 字摘要等于让它盲判。
 * @param history 已发生的每一步决策，按发生顺序。
 */
data class StepInput(
    val chainId: String,
    val goal: String,
    val stageIndex: Int,
    val stages: List<ChainStage>,
    val attempt: Int,
    val verdict: ReviewVerdict?,
    val feedback: String,
    val history: List<StepRecord>,
    val budget: StepBudget,
    /** 本棒收割到的补丁文件指针；null = 这一棒没产出改动。 */
    val patchPath: String? = null,
)

/**
 * 决策的**硬边界**（不是策略）。
 *
 * [maxAttempts] 与 [maxStages] 是「宿主还扛得住」的事实：每次返工都重跑整条链、
 * 每次插棒都多占一个进程与一份 worktree 提交。它们不表达「应该跑几轮」，
 * 只表达「再跑就失控了」。
 */
data class StepBudget(
    val attempt: Int,
    val stageCount: Int,
    /** 宿主给的返工上限；null = 不设（规则决策器按自己的熔断走）。 */
    val maxAttempts: Int? = null,
    /** 宿主给的插棒上限；null = 不设。 */
    val maxStages: Int? = null,
) {
    val attemptsLeft: Int? get() = maxAttempts?.let { (it - attempt).coerceAtLeast(0) }
    val stagesLeft: Int? get() = maxStages?.let { (it - stageCount).coerceAtLeast(0) }
}

/** 决策的四种落点（执行器只认这四种，多一种都没有消费路径）。 */
sealed interface StepDecision {

    /** 推进到指定棒（可跳棒，不必是当前 +1）。 */
    data class Next(val stageIndex: Int) : StepDecision

    /**
     * 返工：把 [feedback] 交给第 [stageIndex] 棒重跑，[attempt] 由决策者给出（已 +1）。
     * 默认回第 0 棒（干活的那一棒），但允许回中间棒 —— 「方案错了、实现没错」时
     * 不必把实现重做一遍。
     */
    data class Rework(
        val attempt: Int,
        val feedback: String,
        val stageIndex: Int = 0,
    ) : StepDecision

    /**
     * 插一棒：在 [afterIndex] 之后插入 [stage] 并立刻执行它。
     *
     * 这是「主理人现场加人」的唯一通路 —— 审查发现方向性问题时插入一根调研棒，
     * 而不是硬让现有 worker 返工。计划从此不再是编译期定死的直线。
     */
    data class Spawn(
        val afterIndex: Int,
        val stage: ChainStage,
        val reason: String? = null,
    ) : StepDecision

    /** 收工：交给既有合流路径（review_ready → 机主点合并）。 */
    data object Finish : StepDecision

    /** 停下：预算用尽或无法继续。不是一个「失败结论」，是暂停待续。 */
    data class Stop(val reason: String) : StepDecision
}

/**
 * 主理人（团队 Lead）的决策上下文。
 *
 * **只带身份与编制事实，不带驱动旗标**：模型只决定「谁、做什么」，用哪个底座由
 * 编制回查 —— 让它输出 `driverKey` 是幻觉的第一来源（它会编出 `dsh-gpt5` 这种键）。
 */
data class LeaderBrief(
    val leaderAgentId: String,
    val leaderName: String,
    /** lead 自己的岗位指令（团队档案里配的那段）；null = 没配。 */
    val leaderPrompt: String?,
    val teamName: String,
    val goal: String,
    val members: List<LeaderMember>,
)

/** 主理人可点将的一个人（编制里的成员，不是群名册）。 */
data class LeaderMember(
    val agentId: String,
    val name: String,
    val role: String,
    val isWorker: Boolean,
    val driverKey: String,
    val model: String? = null,
)

/**
 * 一步决策的留痕。卡面与飞行记录消费它 —— 「为什么又返工了」必须能被人读到，
 * 否则自主决策在用户眼里就只是「它自己乱跑」。
 *
 * @param decidedBy `rule` = 确定性规约；`leader` = 主理人裁决。两者的可信度不同，
 *   不能混成一句「系统决定」。
 */
data class StepRecord(
    val stageIndex: Int,
    val stageRole: String,
    val verdict: ReviewVerdict?,
    val action: String,
    val decidedBy: String,
    val reason: String? = null,
) {
    companion object {
        const val BY_RULE: String = "rule"
        const val BY_LEADER: String = "leader"
    }
}

/**
 * 既有规约的决策器：把 [ReviewPipeline] 那张四选一表原样包成 [StepDecision]。
 *
 * **行为必须与 `review = ReviewPipeline(...)` 逐字一致** —— 这是重构而不是改版：
 * 装配方换用它之后，既有链的棒序、卡面、熔断点都不许变。等价性由
 * `StepDeciderTest` 与 `StageChainReviewTest` 双向钉住。
 */
class DeterministicStepDecider(private val pipeline: ReviewPipeline) : StepDecider {

    override suspend fun decide(input: StepInput): StepDecision =
        when (
            val advance = pipeline.advance(
                stageIndex = input.stageIndex,
                stageCount = input.stages.size,
                attempt = input.attempt,
                verdict = input.verdict,
                feedback = input.feedback,
            )
        ) {
            is ReviewPipeline.Advance.Next -> StepDecision.Next(advance.stageIndex)
            is ReviewPipeline.Advance.BackToWorker ->
                StepDecision.Rework(advance.attempt, advance.feedback, stageIndex = 0)
            is ReviewPipeline.Advance.Merge -> StepDecision.Finish
            is ReviewPipeline.Advance.Blocked -> StepDecision.Stop(advance.reason)
        }
}
