// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 内置 CLI 驱动的**身份卡**：一张纯数据表，回答「每家是什么」，不回答「它在不在」。
 *
 * ## 为什么要有它
 *
 * 新增一家 CLI 曾经要改六处（发现、装配、岗位档案、模型旗标、凭证白名单、UI 显示名），
 * 少改一处就能编译通过、测试全绿，只在真机派单时以「装了却看不见 / 配了模型不生效 /
 * 派单报 no cli driver」的形式暴露。这张表把其中**四处纯数据**收敛成单一来源：
 *
 * - [AgentProfiles] 的岗位档案（key / vendor / displayName / 岗位）从这里派生；
 * - [AgentProfiles] 的干活棒 / 审核棒优先序从这里派生；
 * - [DriverModelFlags] 的按次选模型能力从这里派生；
 * - UI 显示名与另两处装配面通过 `CliDriverCoverageGuardTest` 与本表对账。
 *
 * 发现路径、参数模板、凭证白名单的**值**仍是各自的代码与数据 —— 它们是行为与敏感值，
 * 不搬进身份卡；守卫只钉「每家在这些面上都有一个分支」，不搬动实现。
 *
 * ## 形状约束
 *
 * 本对象在 `:agent-contract`（纯接口、零 IO、不依赖任何上游模块），所以任何字段都必须是
 * 不可变纯数据。岗位枚举独立于 [AgentProfiles.Role] —— 后者是对外契约类型、引用面广，
 * 派生处做一次显式映射，避免为了一张表改动所有调用点。
 */
object CliDriverSpecs {

    /** 岗位：与 [AgentProfiles.Role] 同域，派生时显式映射。 */
    enum class Role { WORKER, REVIEWER, BOTH }

    /**
     * 一家内置 CLI 的身份。
     *
     * [supportsPerTaskModel] 与 [modelFlag] 必须一致：支持却没旗标会在派生时给出空能力，
     * 变成「配了模型却静默跑默认」——那正是 [DriverModelFlags] 要防的缺陷；不支持却带旗标
     * 则相反，会把按次选模型开放给其实做不到的驱动。构造期就拒绝这两种不一致。
     */
    data class Spec(
        val key: String,
        val displayName: String,
        val vendor: String,
        val role: Role,
        val supportsPerTaskModel: Boolean,
        val modelFlag: String? = null,
        /** 干活棒优先序（数字小者优先）；null = 不参与自动组队。 */
        val workerPriority: Int? = null,
        /** 审核棒优先序（数字小者优先）；null = 不参与自动审核。 */
        val reviewerPriority: Int? = null,
    ) {
        init {
            require(key.isNotBlank()) { "driver spec key must not be blank" }
            require(supportsPerTaskModel == (modelFlag != null)) {
                "$key: supportsPerTaskModel and modelFlag must agree (absence means unsupported)"
            }
        }
    }

    /**
     * 七家内置 CLI。顺序即档案登记顺序；优先序单独由 [workerPriority]/[reviewerPriority] 给出，
     * 不靠列表位置 —— 读表的人不必从排列顺序推断行为。
     */
    val builtins: List<Spec> = listOf(
        Spec("codex", "Codex", "openai", Role.WORKER, true, "--model", workerPriority = 1),
        Spec("claude", "Claude Code", "anthropic", Role.WORKER, true, "--model", workerPriority = 2, reviewerPriority = 3),
        Spec("agy", "Antigravity", "google", Role.WORKER, true, "--model", workerPriority = 4, reviewerPriority = 4),
        Spec("opencode", "OpenCode", "opencode", Role.BOTH, true, "--model", workerPriority = 3, reviewerPriority = 2),
        Spec("dsh", "DeepSeek Harness", "deepseek", Role.BOTH, false, null, workerPriority = 5, reviewerPriority = 1),
        Spec("codebuddy", "CodeBuddy", "tencent", Role.BOTH, true, "--model", workerPriority = 6, reviewerPriority = 5),
        Spec("zcode", "ZCode", "zhipu", Role.BOTH, false, null, workerPriority = 7, reviewerPriority = 6),
    )

    val byKey: Map<String, Spec> = builtins.associateBy { it.key }

    val keys: Set<String> = byKey.keys

    /** 干活棒优先序；由 spec 的优先级字段排序得出，不在别处另写一份列表。 */
    val workerKeys: List<String> =
        builtins.filter { it.workerPriority != null }.sortedBy { it.workerPriority }.map { it.key }

    /** 审核棒优先序；同样来自 spec。 */
    val reviewerKeys: List<String> =
        builtins.filter { it.reviewerPriority != null }.sortedBy { it.reviewerPriority }.map { it.key }

    /** 支持按次选模型的档案键（[DriverModelFlags] 的能力来源）。 */
    val modelCapableKeys: Set<String> =
        builtins.filter { it.supportsPerTaskModel }.map { it.key }.toSet()
}
