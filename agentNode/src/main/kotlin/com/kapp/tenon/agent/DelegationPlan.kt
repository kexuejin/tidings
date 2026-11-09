// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.agent.drivers.AgentProfiles

/**
 * 委派计划 —— 「计划即数据」的委派形态（编排模型适配设计 §二）。
 *
 * 两种挡位产出**同一种**计划、走同一条执行面（[StageChainRunner] 多棒链），
 * 区别只在计划由谁产出：
 * - [Source.EXPLICIT]（挡位 A）：人在一条消息里点名多个 agent
 *   （`@codex 实现 X，@dsh 审核`），解析器只做「谁干什么」的归位，不替人做决定；
 * - [Source.AUTO]（挡位 B）：`组队: <目标>` —— 按岗位档案自动组一队
 *   （干活棒 + 异厂商审核棒），人只说目标。
 *
 * 计划不是执行许可：硬约束（在位、异厂商审核）由编排层的 StageChainGate 裁决，
 * 冲突时代码门赢。
 */
data class DelegationPlan(
    /** 委派目标（进链标题，也是审核棒任务书里复述的目标）。 */
    val goal: String,
    /** 编译后的链：干活棒在前、审核棒收尾。 */
    val stages: List<ChainStage>,
    val source: Source,
    /**
     * §16.4：命中**群模板**（[DelegationTeam]）的自动计划 —— 编排器据此**跳过
     * PlannerLlm**（人已显式选定团队，模型不再越俎代庖；省 Token 与 20s 延迟），
     * 并在拒绝卡 banner 注入「Team template: 」前缀标识来源。
     * 挡位 A 与启发式保底恒为 false。默认 false ⇒ 既有构造点零改动。
     */
    val fromTemplate: Boolean = false,
    val target: String? = null,
    val module: String? = null,
    val context: String? = null,
) {
    enum class Source { EXPLICIT, AUTO }

    val workerStage: ChainStage? get() = stages.firstOrNull()
    val reviewerStage: ChainStage? get() = stages.lastOrNull()
}

/**
 * 委派团队模板 —— 挡位 B 的按群配置化选棒（编排规范 §16）。
 *
 * 用户显式选定「这个群默认谁干活、谁审核」：配置里没有关键词、不读 goal 内容、
 * 不新增协议键、不引入每轮 LLM 调度。持久化在宿主 `delegation-teams.json`
 * （编排层 `DelegationTeamStore`）；本类是**纯数据**，两个键都必须已经过
 * [AgentProfiles.normalizeKey] 归一（保存侧负责校验，解析侧防御性再归一一次）。
 */
data class DelegationTeam(
    /**
     * 唯一键是 groupId 不是 wsId（§16.1）：派单发生于 resolveWorkspacePath 之前，
     * 两条派单入口手里只有 convId / groupId；私聊无 groupId 自然无模板。
     */
    val groupId: String,
    val workerKey: String,
    val reviewerKey: String,
    val updatedAt: Long,
)

/**
 * 消息 → [DelegationPlan]。不是委派就返回 null，调用方回落既有路径
 * （单棒 / 旧的 `角色(驱动键): 任务` 阶段语法）——**宁可判窄不判宽**：
 * 误吞一条普通派单的代价，远高于漏掉一次委派。
 *
 * 触发判据：
 * - 挡位 B：正文以 `组队:` / `组队：` 起头（@ 提及被域层剥掉后恰好剩这个前缀，
 *   所以真机 `@codex 组队: 修复登录` 也能到这）；
 * - 挡位 A：分句（`，` `，` `;` `；` 换行）后，**≥2 个不同**的已知 agent 键
 *   恰好落在句首（`@codex 实现 X，@dsh 审核`）。句中顺嘴提到 agent 名不算 ——
 *   「把 codex 的输出贴给 dsh」只有一个分句、句首不是键，判 null。
 *
 * @param availableKeys 本机在位的 agent 档案键（已归一化）；空集直接判 null。
 */
object DelegationParser {

    /**
     * 挡位 B 前缀：`组队:` 是产品主形态；`team:` 是**等价别名** —— 真机 adb 派单
     * 打不出中文字，英文别名让自动化与西文用户都能进同一挡位（语义零差异）。
     */
    private val AUTO_PREFIX = Regex("""^(?:组队|team)\s*[:：]\s*(.+)$""", setOf(RegexOption.DOT_MATCHES_ALL))

    /** 含这些词的分句归审核棒（中英都认，按子串匹配）。 */
    private val REVIEW_KEYWORD = Regex("""(?i)审核|把关|评审|验收|检查|review""")

    private val CLAUSE_SPLIT = Regex("""[\r\n;；，,]""")

    private const val WORKER_ROLE = "开发"
    private const val REVIEWER_ROLE = "审核"

    fun parse(
        text: String,
        availableKeys: Set<String>,
        autoKeys: Set<String> = availableKeys,
        team: DelegationTeam? = null,
    ): DelegationPlan? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || availableKeys.isEmpty()) return null
        parseAuto(trimmed, availableKeys, autoKeys, team)?.let { return it }
        return parseExplicit(trimmed, availableKeys)
    }

    /**
     * 挡位 B：`组队: <目标>` → 干活棒 + 异厂商审核棒。
     * 选棒顺序（§16.4）：**群模板优先**（用户显式选定），否则按 [autoKeys]
     * （健康过滤后的名册，G1：二进制在位 ≠ 健康）启发式；
     * 找不到第二个厂商时只给一棒 —— 不是解析失败，门会把它降级成单棒而不是吞单。
     */
    private fun parseAuto(
        text: String,
        availableKeys: Set<String>,
        autoKeys: Set<String>,
        team: DelegationTeam?,
    ): DelegationPlan? {
        val match = AUTO_PREFIX.find(text) ?: return null
        val goal = match.groupValues[1].trim()
        if (goal.isEmpty()) return null
        // 群模板（§16.4）：用户显式选定团队 ⇒ 直接采用，**不看名册也不看健康表** ——
        // 成员不在位是门的事（Reject 出卡点名模板与可用名单），不是解析器的事；
        // 解析器若在这里静默换人，就违反 fail-closed（§15 第 8 条）。
        // 键归一失败（文件里是档案表没有的名字）⇒ 模板不可信 ⇒ 落回启发式保底（§16.3 损坏语义）。
        if (team != null) {
            val worker = AgentProfiles.normalizeKey(team.workerKey)
            val reviewer = AgentProfiles.normalizeKey(team.reviewerKey)
            if (worker != null && reviewer != null) {
                return DelegationPlan(
                    goal = goal.take(120),
                    stages = listOf(
                        ChainStage(role = WORKER_ROLE, agentKey = worker, instruction = goal),
                        ChainStage(
                            role = REVIEWER_ROLE, agentKey = reviewer,
                            instruction = reviewInstruction(goal, REVIEWER_ROLE),
                        ),
                    ),
                    source = DelegationPlan.Source.AUTO,
                    fromTemplate = true,
                )
            }
        }
        val usable = autoKeys.intersect(availableKeys).ifEmpty { availableKeys }
        val worker = AgentProfiles.defaultWorkerKey(usable) ?: return null
        val reviewer = AgentProfiles.defaultReviewerKey(worker, usable)
        val stages = buildList {
            add(ChainStage(role = WORKER_ROLE, agentKey = worker, instruction = goal))
            if (reviewer != null) {
                add(ChainStage(role = REVIEWER_ROLE, agentKey = reviewer, instruction = reviewInstruction(goal, REVIEWER_ROLE)))
            }
        }
        return DelegationPlan(goal = goal.take(120), stages = stages, source = DelegationPlan.Source.AUTO)
    }

    /**
     * 挡位 A：句首点名的 ≥2 个不同 agent。含审核关键词的句子归审核棒并**排到队尾**
     * （「A 干 B 审核」的形状由结构保证，不依赖人把审核写在最后）。
     * 全是干活或全是审核 → 不是委派形状，null。
     */
    private fun parseExplicit(text: String, availableKeys: Set<String>): DelegationPlan? {
        val clauses = mutableListOf<Pair<String, String>>() // 归一键 -> 指令
        for (raw in text.split(CLAUSE_SPLIT)) {
            val clause = raw.trim()
            if (clause.isEmpty()) continue
            val leading = leadingAgentKey(clause, availableKeys) ?: continue
            clauses.add(leading.first to leading.second)
        }
        val distinctKeys = clauses.map { it.first }.distinct()
        if (distinctKeys.size < 2) return null

        val goal = clauses.first().second
        val workers = mutableListOf<ChainStage>()
        val reviewers = mutableListOf<ChainStage>()
        for ((key, instruction) in clauses) {
            if (REVIEW_KEYWORD.containsMatchIn(instruction)) {
                reviewers.add(ChainStage(role = REVIEWER_ROLE, agentKey = key, instruction = reviewInstruction(goal, instruction)))
            } else {
                workers.add(ChainStage(role = WORKER_ROLE, agentKey = key, instruction = instruction))
            }
        }
        if (workers.isEmpty() || reviewers.isEmpty()) return null
        val stages = workers + reviewers
        return DelegationPlan(goal = goal.take(120), stages = stages, source = DelegationPlan.Source.EXPLICIT)
    }

    /**
     * 句首的已知 agent 键：`@codex …` / `codex: …` / `codex-cli …`，
     * 以及群席位全名 `@dsh-<host>-<workspace> …`（按前缀归位到档案键）。
     * 只认紧跟在句首（可带 @）的键 token；返回 归一键 to 其后的指令文本。
     */
    private fun leadingAgentKey(clause: String, availableKeys: Set<String>): Pair<String, String>? {
        var index = 0
        if (index < clause.length && clause[index] == '@') index++
        val start = index
        while (index < clause.length &&
            (clause[index].isLetterOrDigit() || clause[index] == '-' || clause[index] == '_')
        ) {
            index++
        }
        if (index == start) return null
        // 席位全名（`@dsh-<host>-<workspace>`）不是档案键：交给 resolveKey 按前缀归位
        // 到在位的档案键，否则显式点名的委派计划在真机上永远解析不出来。
        val key = AgentProfiles.resolveKey(clause.substring(start, index), availableKeys) ?: return null
        val instruction = clause.substring(index).trim().trimStart(':', '：').trim()
        if (instruction.isEmpty()) return null
        return key to instruction
    }

    /**
     * 审核棒任务书：只写了「审核」两个字的（或只含关键词），替它补上目标复述与
     * 产出要求 —— 否则审核棒收到一句空话，不知道在为什么把关。
     * 人家自己写清楚了的，一字不动。
     */
    private fun reviewInstruction(goal: String, raw: String): String {
        val stripped = REVIEW_KEYWORD.replace(raw, " ").replace(Regex("""\s+"""), " ").trim()
        val bare = stripped.isEmpty() || stripped.length <= 4
        if (!bare) return raw
        return "复核上游产出是否达成目标「${goal.take(80)}」：逐条给结论、问题清单与是否放行；不要重写实现。"
    }
}

/**
 * 入口 A 的固定句式解析（编排规范 §16.6；§15 第 12 条裁决：拦截式）：
 * `以后本群默认组队：<worker> 开发，<reviewer> 审核`（可选前导 `@…` —— 判据是句式，
 * 不是呼叫谁）。确定性纯函数、零模型调用；住在 `:agentNode`，与 [DelegationParser]
 * 的触发词同模块 —— 委派语法的关键字只有一个家。
 *
 * **句式不匹配 → `null` = 不拦截、不消费**，消息照常落回唤醒判据（零行为变更）；
 * 匹配 ⇒ 调用方（宿主拦截桥）做归一 / 异厂商校验 / 落盘 / 可见回执。
 */
object DelegationTeamConfigParser {

    private val CONFIG_PATTERN = Regex(
        """^(?:@\S+\s+)?以后本群默认组队\s*[:：]?\s*(\S+?)\s*开发\s*[，,]\s*(\S+?)\s*审核\s*[。.]?\s*$""",
    )

    /** 返回 (worker 原文, reviewer 原文) —— 归一由调用方用 [AgentProfiles.normalizeKey] 做。 */
    fun parse(text: String): Pair<String, String>? {
        val match = CONFIG_PATTERN.find(text.trim()) ?: return null
        val worker = match.groupValues[1].trim()
        val reviewer = match.groupValues[2].trim()
        if (worker.isEmpty() || reviewer.isEmpty()) return null
        return worker to reviewer
    }
}
