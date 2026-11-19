// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.TeamMention
import com.kapp.tenon.core.protocol.TeamNameMention
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.isTeam
import com.kapp.tenon.core.protocol.resolveTeamMemberToken
import com.kapp.tenon.core.protocol.seatsMatchingToken
import com.kapp.tenon.core.protocol.teamSeatMatchingName
import com.kapp.tenon.domain.DispatchBlockedCode
import com.kapp.tenon.domain.TeamProfile
import com.kapp.tenon.domain.TeamProfileMention

/**
 * 派单入口：`@` 命中的是**哪一类**目标。
 *
 * 必须由解析层显式带下来 —— `@团队名`、`@主理人席位`、`@某个组员` 三种命中在
 * [TeamExecutionResolver.Target.Hit] 里长得几乎一样（同一席位、同一份执行档、
 * 甚至可能是同一个组员），下游只看 Hit 是推不出来的，而它们的语义完全不同：
 *
 * - [TEAM] / [LEADER] = 这活**整队接**，链形状由主理人裁决（点将 + 审查面）；
 * - [MEMBER] = 只让这个人做这一件事，不拉审查棒、不问主理人。
 *
 * 把三种语义压成一条路径的后果是「点名某人」变成「惊动全队」——用户以为它在派活，
 * 实际触发的是一次团队编排。
 */
enum class DispatchEntry {
    /** `@团队名`：整队接活。 */
    TEAM,

    /** `@主理人`（团队席位的名字 / agentId）：语义同 [TEAM]，只是换个称呼点它。 */
    LEADER,

    /** `@某个组员`：单兵。它做完就结束，后续流转由人再说一句 `@` 决定。 */
    MEMBER,
}

/**
 * `@组员` → (席位, 组员, 执行档) 的宿主侧解析（团队执行方案 §4.3）。
 *
 * 只在**席位宿主**上跑（路由层 §4.5 已按目标宿主判 Local）；三类非命中都要可区分：
 * - `null`（[Target.None]）：token 不是任何 isTeam 席位的组员 —— 可能是领队 token
 *   （走既有单 agent 路径）。**绝不**因为「不是组员」而拦截既有路径；
 * - [Target.NotConfigured]：是组员 token、但本机没有可用执行档 / 档过期 / 席位没绑
 *   默认工作区 —— **必须**出 blocked 卡，绝不回落到"默认 codex 单棒"（§2 的洞）。
 *
 * 悬空校验（§3.5）在这里：执行档的成员必须仍在当前声明里（按 agentId）；
 * 目标组员不在档里 ⇒ 过期。
 */
class TeamExecutionResolver(
    private val store: TeamExecutionStore,
    /**
     * 群 → 项目目录 dirId（D16）。执行档主键里的 wsId 换成了 dirId：wsId 是 agent
     * 视角的句柄（重宣告会变），dirId 是目录档案的稳定 id。null/空 = 群没设指针
     * ⇒ NotConfigured（blocked 卡写清原因），**不猜**。
     */
    private val dirIdOfGroup: (groupId: String) -> String? = { null },
) {

    sealed interface Target {
        /**
         * 完整命中：席位 + 组员 + 有效执行档。
         *
         * @param member 本次的**执行主体**（不是「用户打的那个词」）：`@团队名` / `@主理人`
         *   给的是编制里的 worker（主理人只做决策，不自己开进程），`@组员` 就是那个组员。
         * @param entry 这次是从哪个入口进来的 —— 决定整队接活还是单兵，见 [DispatchEntry]。
         */
        data class Hit(
            val seat: VirtualAgentMember,
            val member: TeamMemberInfo,
            val profile: TeamExecutionProfile,
            val entry: DispatchEntry,
        ) : Target

        /**
         * 是组员 token / 团队名，但没有可用的执行档 —— blocked 卡要写清 [reason]，
         * 并按 [code] 选文案与主行动（reason 只进折叠区和日志，不上卡面，§3.5）。
         */
        data class NotConfigured(
            val seat: VirtualAgentMember?,
            val code: DispatchBlockedCode,
            val reason: String,
        ) : Target

        /**
         * **队伍还没进这个群**（方案 §3.5 `TEAM_NOT_IN_GROUP`，§4-8b）：token 命中
         * **本机团队档案** ∧ 该群无该队席位。与 [TARGET_MISSING][DispatchBlockedCode.TARGET_MISSING]
         * 的互斥判据写死在这里：先看团队档案再看席位 —— 未入驻时 `@队名` 得到这一档
         * 而不是 `TARGET_MISSING`。主行动是「把队加进这个群」（[team] 定位档案）。
         */
        data class NotInGroup(val team: TeamProfile, val reason: String) : Target {
            val code: DispatchBlockedCode get() = DispatchBlockedCode.TEAM_NOT_IN_GROUP
        }

        /** 不是组员 token —— 调用方回落既有路径。 */
        data object None : Target
    }

    fun resolve(
        groupId: String?,
        mentionedKey: String?,
        group: GroupMeta?,
        selfNodeId: String,
        teamProfiles: List<TeamProfile> = emptyList(),
    ): Target {
        if (groupId == null || mentionedKey.isNullOrBlank() || group == null) return Target.None
        // **`@团队名`（整队）先判**：团队名是展示串，组员 token 是 ASCII slug，两套词表
        // 不能混在一个循环里（混了就是「团队叫 coder 时 @ 到组员」）。命中团队的语义是
        // 「这活整队接」—— 编到执行档唯一那个 worker，其余 reviewer 照既有责任链走。
        when (val teamMention = group.teamSeatMatchingName(mentionedKey)) {
            is TeamNameMention.Ambiguous ->
                return Target.NotConfigured(null, DispatchBlockedCode.TARGET_MISSING, teamNameAmbiguous(mentionedKey))
            is TeamNameMention.Hit -> {
                val seat = teamMention.seat
                return teamAnchor(groupId, seat, selfNodeId, DispatchEntry.TEAM)
            }
            null -> Unit
        }
        when (val hit = group.resolveTeamMemberToken(mentionedKey)) {
            is TeamMention.Ambiguous ->
                return Target.NotConfigured(null, DispatchBlockedCode.TARGET_MISSING, "member token matches multiple seats (hand-edited meta?)")
            is TeamMention.Hit -> {
                val seat = hit.seat
                val (profile, code, denial) = profileForSeat(groupId, seat, selfNodeId)
                if (profile == null) {
                    return Target.NotConfigured(seat, code ?: DispatchBlockedCode.TARGET_MISSING, denial.orEmpty())
                }
                val spec = profile.members.firstOrNull {
                    it.agentId.trim().lowercase() == hit.member.agentId.trim().lowercase()
                } ?: return Target.NotConfigured(
                    seat,
                    DispatchBlockedCode.TARGET_MISSING,
                    "execution profile is stale: member '" + hit.member.name + "' is not configured",
                )
                // **点名主理人 = 整队接活**：主理人语义优先于「某个组员」——他要的是
                // 「你们队看着办」，不是自己下场写代码。主理人不是执行者，所以落点换成
                // 编制里的 worker；他要是不在编制里（只配了名字），走下面的席位 token 分支。
                if (hit.member.agentId.trim().lowercase() ==
                    profile.leaderAgentId.trim().lowercase()
                ) {
                    return teamAnchor(groupId, seat, selfNodeId, DispatchEntry.LEADER)
                }
                return Target.Hit(seat, hit.member, profile, DispatchEntry.MEMBER)
            }
            null -> Unit
        }
        // **`@主理人`（席位 token）= 整队接活**：席位名 / agentId 命中的是本机团队席位。
        // 与 `@团队名` 读同一份档、走同一套裁决 —— 主理人不是执行者，执行主体仍是
        // 编制里那个 worker。组员 token 已在上面的分支返回，不会走到这里。
        val seatHit = group.seatsMatchingToken(mentionedKey)
        if (seatHit.size == 1) {
            val seat = seatHit.single()
            // 只有**真团队席位且有声明**才认这个语义：没有组员的席位是普通单 agent，
            // 它的 `@` 必须留给既有 kind 路径（这里多拦一步就是把能用的路径拦死）。
            if (seat.isTeam && seat.teamMembers.orEmpty().isNotEmpty()) {
                val (profile, code, denial) = profileForSeat(groupId, seat, selfNodeId)
                if (profile == null) {
                    // 与 `@团队名` 报同一句：同一支队，两个词不能一个说 A、一个说 B。
                    return Target.NotConfigured(seat, code ?: DispatchBlockedCode.TARGET_MISSING, denial.orEmpty())
                }
                return teamAnchor(groupId, seat, selfNodeId, DispatchEntry.LEADER)
            }
        }
        // **未入驻档**（§3.5 / §4-8b）：token 命中**本机团队档案**（队名或名册行 token）
        // ∧ 该群无该队席位 ⇒ `TEAM_NOT_IN_GROUP`，而不是流到 kind 路径被误报成
        // `TARGET_MISSING`（「互斥判据写死：先看团队档案再看席位」）。
        // 与路由层共用同一份档案匹配（TeamProfileMention）：各写一份就是
        // 「@ 了没反应 / 执行了别人」那类分叉的种子。
        TeamProfileMention.byNameOrMemberToken(teamProfiles, mentionedKey)?.let { team ->
            val attachedHere = group.virtualAgents.orEmpty().any { it.teamId == team.teamId }
            if (!attachedHere) {
                return Target.NotInGroup(
                    team,
                    "team '" + team.name + "' (" + team.teamId + ") is not attached to group " + groupId,
                )
            }
            // 档案在、席位也在 —— token 却没对上席位名/组员（改名差异）：交回 None，
            // 上面的席位级匹配已经覆盖过了；这里不再猜。
        }
        return Target.None
    }

    /**
     * 整队入口（`@团队名` / `@主理人`）的落点：执行主体 = 编制里的 worker。
     *
     * 两个入口共用一份判据与一套拒因，只差 [entry] 那一格 —— 各写一遍的结果是
     * 「同一支队，@队名时说 A、@主理人时说 B」。
     */
    private fun teamAnchor(
        groupId: String,
        seat: VirtualAgentMember,
        selfNodeId: String,
        entry: DispatchEntry,
    ): Target {
        val (profile, code, denial) = profileForSeat(groupId, seat, selfNodeId)
        if (profile == null) {
            return Target.NotConfigured(seat, code ?: DispatchBlockedCode.TARGET_MISSING, denial.orEmpty())
        }
        val worker = profile.worker
            ?: return Target.NotConfigured(seat, DispatchBlockedCode.NO_DRIVER, "team execution profile has no worker")
        val member = seat.teamMembers.firstOrNull {
            it.agentId.trim().lowercase() == worker.agentId.trim().lowercase()
        } ?: return Target.NotConfigured(
            seat,
            DispatchBlockedCode.TARGET_MISSING,
            "execution profile is stale: worker '" + worker.agentId + "' is not in the roster",
        )
        return Target.Hit(seat, member, profile, entry)
    }

    /** token → 本机团队档案：先队名（大小写不敏感），后名册行 token（组员 token 池的一部分）。 */
    private fun teamNameAmbiguous(key: String): String =
        "team name '" + key + "' matches more than one seat, or collides with a member token; rename the team"

    /**
     * 席位 → 执行档（含**宿主 / 工作区 / 悬空**三道校验）。第二项非空 = 拒绝原因。
     *
     * 抽出来是因为两个入口（`@组员` 与 `@团队名`）必须读同一份档、报同一句原因 ——
     * 各写一遍的结果是「同一支队，@人时说 A，@队时说 B」。
     */
    private fun profileForSeat(
        groupId: String,
        seat: VirtualAgentMember,
        selfNodeId: String,
    ): Triple<TeamExecutionProfile?, DispatchBlockedCode?, String?> {
        if (seat.hostNodeId != selfNodeId) {
            return Triple(null, DispatchBlockedCode.TARGET_MISSING, "this seat is hosted on another device")
        }
        val dirId = dirIdOfGroup(groupId)?.trim().orEmpty()
        if (dirId.isEmpty()) {
            return Triple(null, DispatchBlockedCode.NO_DIRECTORY, "group has no project directory bound")
        }
        val profile = store.profileFor(dirId, seat.agentId) ?: return Triple(
            null,
            if (seat.teamMembers.isEmpty()) DispatchBlockedCode.TARGET_MISSING else DispatchBlockedCode.NO_DRIVER,
            if (seat.teamMembers.isEmpty()) "team has no members declared"
            else "team execution is not configured for this team",
        )
        val declaredIds = seat.teamMembers.map { it.agentId.trim().lowercase() }.toSet()
        val dangling = profile.members.filter { it.agentId.trim().lowercase() !in declaredIds }
        if (dangling.isNotEmpty()) {
            return Triple(
                null,
                DispatchBlockedCode.TARGET_MISSING,
                "execution profile is stale: " + dangling.joinToString(",") { it.agentId } +
                    " no longer in the roster",
            )
        }
        return Triple(profile, null, null)
    }
}

/**
 * 编制 → 责任链的**编译层**（§5.1）：把执行档编译成既有 `DelegationPlan` 的形状，
 * 走既有的 `StageChainGate`。不新造执行器、不重写审查流水线。
 *
 * - 命中 **worker**：完整责任链 `[worker] + [reviewers…]`（先编码后审查）；
 * - 命中 **reviewer**：用户意图是对当前工作区直接审查 —— 编译为该 reviewer **单棒**
 *   任务，不调度 worker 产生冗余代码；
 * - 0 reviewer：不建链，单棒 worker（§5.3 —— 调用方负责在位检查，见 [Compiled.Single]）；
 * - [`DispatchEntry.MEMBER`]：**不走上面任何一条团队形状** —— 单兵只出单棒，编制里的
 *   审查棒一律不展开（用户点的是人，不是队）；
 * - 0 worker：**读取期也拒**（保存期已拒；旧数据/手改文件走到这里同样拒）。**N worker
 *   合法**：`kind` 声明「谁能干」，这一单谁下场的点将权在主理人（[PlannerChainGate]），
 *   没有可用建议时回落**锚点本人**（`@团队名` → 编制首位 worker）。
 */
object TeamPlanCompiler {

    sealed interface Compiled {
        /**
         * 这次派单**真正会被调起**的组员 `agentId`（按链序，原样大小写）。
         *
         * 授权必须按它逐个判，**不能只判 `@` 锚点**：主理人一旦有换人权，锚点与执行人
         * 就是两件事（拿 A 的授权跑 B）。原样大小写不是洁癖 —— `AgentExecPolicyStore`
         * 与授权台账都按字符串精确命中，归一成小写会让「配过策略的人」变成「没配过」。
         */
        val participants: List<String>

        /** 单棒（无 reviewer，或命中 reviewer 的纯审查任务）：调用方做在位检查后 dispatchSingleWorker。 */
        data class Single(
            val agentKey: String,
            /**
             * 这一棒的组员身份（授权对象）。[agentKey] 是驱动、这里是**人**：同一个人可以
             * 换底座，同一个底座也可以被两个组员共用 —— 拿驱动键去做授权判据，会把
             * 「允许小张用 codex」错算成「允许全组用 codex」。
             */
            val agentId: String,
            val instruction: String,
            val reviewOnly: Boolean,
            /**
             * 本棒的模型（执行档 `models.first`）：null = 跟随驱动默认。
             * 调用方必须原样传到 `executeCliTask(model = …)` —— 只在这里携带、
             * 下游不消费，就是又造一份「存了不生效」的死数据。
             */
            val model: String? = null,
            /** true = 形状来自 Planner 的裁决（0 审查棒也是它的裁决），不是编制静态展开。 */
            val plannedByLlm: Boolean = false,
        ) : Compiled {
            override val participants: List<String> get() = listOf(agentId)
        }

        /** 多棒责任链：交给 `delegateByPlan`（gate → 链执行器）。 */
        data class Plan(
            val plan: DelegationPlan,
            /** 与 `plan.stages` **同序同长**：授权按人判，链执行按驱动跑，两者一一对应。 */
            override val participants: List<String>,
            /**
             * true = 采纳了 Planner 的建议：`delegateByPlan` 必须跳过 [TeamPlanRefiner]
             * —— 同一批 instruction 被改写两遍不只是白付第二次 20s，第二遍还会把
             * Planner 裁掉的审查棒语义说回来（它只知道"原计划有什么"）。
             */
            val plannedByLlm: Boolean = false,
        ) : Compiled

        /**
         * 编译期拒绝：blocked 卡写 [reason]。
         *
         * [code] 只在「装个东西就能好」的那几档给值（目前只有 `DRIVER_MISSING`）；
         * 其余拒绝（执行档形状不对、成员不在档里）**不给码** —— 默认值不是
         * `DRIVER_MISSING`：那会把「编制配错了」误报成「运行时不在这台电脑上」，
         * 卡面的 chip 与那句人话都会指错方向。
         */
        data class Rejected(
            val reason: String,
            val code: DispatchBlockedCode? = null,
        ) : Compiled {
            /** 拒了就没有人会被调起 —— 空集不是「跳过授权」，是「没有授权对象」。 */
            override val participants: List<String> get() = emptyList()
        }
    }

    fun compile(
        profile: TeamExecutionProfile,
        declaredMembers: List<TeamMemberInfo>,
        hitMember: TeamMemberInfo,
        goal: String,
        availableDrivers: Set<String>?,
        /**
         * 追加到**每一棒**指令尾部的本机能力简报（P3 工具面）。null/空白 = 不追加。
         *
         * 每一棒都要带：审查棒不干活，但它要判断「这一版有没有按规矩用工具改编制」
         * （例如绕过工具直接改 settings.json 就是问题）。只给干活棒等于让审查棒瞎判。
         */
        capabilityBrief: String? = null,
        /**
         * Planner 的编排建议（审核面裁决）。null / 门不过 / 选中的棒不在位 ⇒ 走下面的
         * 编译固定链，逐字不变 —— 派单永不因为 LLM 卡住。
         */
        suggestion: OrchestrationSuggestion? = null,
        /**
         * 派单入口（见 [DispatchEntry]）。缺省 [DispatchEntry.TEAM] = 整队语义，即**旧行为**：
         * 漏传只会让「点名某人」多拉一条链，不会把整队悄悄降级成单兵。
         * [DispatchEntry.MEMBER] 必须显式传 —— 它是新语义，不该由默认值替人做决定。
         */
        entry: DispatchEntry = DispatchEntry.TEAM,
    ): Compiled {
        val briefSuffix = capabilityBrief?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "\n\n" + it }.orEmpty()
        // 声明里的角色标签（编码/审查/测试…）按 agentId 对齐 —— role 属于群声明
        // （展示事实），执行档只存执行配置（§3.2），两边各管各的。
        val declaredRoleById = declaredMembers.associate {
            it.agentId.trim().lowercase() to it.role.trim()
        }
        val workers = profile.workers
        if (workers.isEmpty()) {
            // 0 worker 是「谁都不能干活」——编制错误，不是 LLM 的一次坏选择。多 worker
            // **不再拒**：编制只声明"谁能干"，这一单谁下场的点将权在主理人。
            return Compiled.Rejected("execution profile must have at least one worker (found 0)")
        }
        val hitSpec = profile.members.firstOrNull {
            it.agentId.trim().lowercase() == hitMember.agentId.trim().lowercase()
        } ?: return Compiled.Rejected("member '" + hitMember.name + "' is not in the execution profile")

        // **null = 控制面不可用**（availableAgentKeys() 的 null 语义）：与"驱动不在位"
        // 同一处置 —— 单棒路径不经门，编译时就拒，不交给 dispatchSingleWorker 撞通用错误。
        val available: Set<String> = availableDrivers ?: emptySet()
        // 命中 reviewer：纯审查单棒 —— 对当前工作区/分支做审计并给 VERDICT。
        if (!hitSpec.isWorker) {
            if (hitSpec.driverKey !in available) {
                return Compiled.Rejected(
                    driverNotInPlace(hitSpec.driverKey, available),
                    DispatchBlockedCode.DRIVER_MISSING,
                )
            }
            return Compiled.Single(
                agentKey = hitSpec.driverKey,
                agentId = hitSpec.agentId,
                instruction = reviewInstruction(goal, hitMember.role, hitSpec.customPrompt) + briefSuffix,
                reviewOnly = true,
                model = hitSpec.models.firstOrNull(),
            )
        }

        // **`@组员` = 单兵**：只让它做这一件事 —— 不展开审查棒、不问主理人。
        // 团队上下文仍然带下去（`briefSuffix` 里有人名册），所以它自己知道「这活做完
        // 该找谁审」，只是不会替用户把整条链拉起来。
        if (entry == DispatchEntry.MEMBER) {
            if (hitSpec.driverKey !in available) {
                return Compiled.Rejected(
                    driverNotInPlace(hitSpec.driverKey, available),
                    DispatchBlockedCode.DRIVER_MISSING,
                )
            }
            return Compiled.Single(
                agentKey = hitSpec.driverKey,
                agentId = hitSpec.agentId,
                instruction = workerInstruction(goal, hitSpec.customPrompt) + briefSuffix,
                reviewOnly = false,
                model = hitSpec.models.firstOrNull(),
            )
        }

        // 命中 worker：**Planner 的点将优先**（谁干、审不审、审几棒、谁审由它定）。
        // 门不过 / 选中的棒不在位 ⇒ 落到下面的编译固定链，逐字不变。
        if (suggestion != null) {
            val accepted = PlannerChainGate.accept(suggestion, profile.members)
            val planned = accepted?.let {
                compilePlanned(it, profile, declaredMembers, goal, available, briefSuffix)
            }
            if (planned != null) return planned
        }

        // **落到这里 = 主理人没有（或没有可用的）点将建议**：编排放回编制静态形状，
        // 干活棒就是**锚点本人**（`@团队名` → 编制首位 worker，`@组员` → 那个人）。
        // 这条是旧行为逐字保留的那一支：点将只是**允许**换人，不是默认换人。
        val worker = hitSpec
        val reviewers = profile.reviewers
        if (reviewers.isEmpty()) {
            // §5.3：单棒路径不经过 StageChainGate —— 编译时就做在位检查，
            // 不交给 dispatchSingleWorker 去撞通用错误。
            if (worker.driverKey !in available) {
                return Compiled.Rejected(
                    driverNotInPlace(worker.driverKey, available),
                    DispatchBlockedCode.DRIVER_MISSING,
                )
            }
            return Compiled.Single(
                agentKey = worker.driverKey,
                agentId = worker.agentId,
                instruction = workerInstruction(goal, worker.customPrompt) + briefSuffix,
                reviewOnly = false,
                model = worker.models.firstOrNull(),
            )
        }
        val stages = buildList {
            val workerName = hitMember.name.ifBlank { hitMember.agentId }
            add(
                ChainStage(
                    role = workerStageRole(hitMember),
                    agentKey = worker.driverKey,
                    instruction = workerInstruction(goal, worker.customPrompt) + briefSuffix,
                    model = worker.models.firstOrNull(),
                    assignee = workerName,
                ),
            )
            reviewers.forEach { reviewer ->
                val declaredRole = declaredRoleById[reviewer.agentId.trim().lowercase()]
                val reviewerName = declaredMembers.firstOrNull {
                    it.agentId.trim().lowercase() == reviewer.agentId.trim().lowercase()
                }?.let { it.name.ifBlank { it.agentId } } ?: reviewer.agentId
                add(
                    ChainStage(
                        role = declaredRole?.ifBlank { null } ?: "reviewer",
                        agentKey = reviewer.driverKey,
                        instruction = reviewInstruction(goal, declaredRole, reviewer.customPrompt) + briefSuffix,
                        model = reviewer.models.firstOrNull(),
                        assignee = reviewerName,
                    ),
                )
            }
        }
        return Compiled.Plan(
            DelegationPlan(
                goal = goal,
                stages = stages,
                // §5.2：fromTemplate = true（人已显式选定团队，跳过 LLM planner）；
                // Source.AUTO 保证单棒时走 Downgrade 而不是 Reject。
                source = DelegationPlan.Source.AUTO,
                fromTemplate = true,
            ),
            // 与上面 stages 同序：[worker] + [reviewers…]。
            participants = listOf(worker.agentId) + reviewers.map { it.agentId },
        )
    }

    /**
     * Planner 建议 → 编译产物。返回 null = 这份建议用不了，调用方回落编译固定链。
     *
     * **1 棒必须直接出 [Compiled.Single]**，不得走 `Compiled.Plan` → `StageChainGate`：
     * 那条路会把它判成 `Downgrade` 并改用 `plan.goal` 派单，Planner 写的 worker 指令
     * 被静默丢弃、退回原始目标 —— 结果看似一样，用户的意图与卡面说明却是错的。
     *
     * 选中棒的驱动不在位同样整份作废：`StageChainGate` 的 `Reject` 是给**用户配置**
     * 用的可见失败，拿来报 Planner 的一次坏选择，就是把增强做成了故障源。
     */
    private fun compilePlanned(
        suggestion: OrchestrationSuggestion,
        profile: TeamExecutionProfile,
        declaredMembers: List<TeamMemberInfo>,
        goal: String,
        available: Set<String>,
        briefSuffix: String,
    ): Compiled? {
        val specsById = profile.members.associateBy { it.agentId.trim().lowercase() }
        val declaredById = declaredMembers.associateBy { it.agentId.trim().lowercase() }
        val stages = mutableListOf<ChainStage>()
        // 与 stages 同步长的人（授权对象）：一步步加，绝不在下游按 agentKey 反推
        // —— 驱动键不是身份，两个组员可以共用同一个底座。
        val participants = mutableListOf<String>()
        suggestion.stages.forEachIndexed { index, intent ->
            val spec = specsById[intent.agentId] ?: return null
            if (spec.driverKey !in available) return null
            val declared = declaredById[intent.agentId]
            participants.add(spec.agentId)
            stages.add(
                ChainStage(
                    // 角色标签必须取自**被点中的这个人**，不是 `@` 锚点：点将之后
                    // 「谁在干」与「用户打的那个词」已经是两个人，抄锚点的 role
                    // 会让卡面把 B 的活写成 A 的岗位。
                    role = declared?.role?.ifBlank { null }
                        ?: if (index == 0) "worker" else "reviewer",
                    agentKey = spec.driverKey,
                    instruction = intent.instruction + briefSuffix,
                    model = spec.models.firstOrNull(),
                    // 卡面括号里的承办人：身份名取群声明，取不到才回落 agentId。
                    // 只填 agentKey 会让卡面把"谁在干"显示成"什么在干"。
                    assignee = declared?.name?.ifBlank { null } ?: spec.agentId,
                ),
            )
        }
        if (stages.size == 1) {
            return Compiled.Single(
                agentKey = stages.single().agentKey,
                agentId = participants.single(),
                instruction = stages.single().instruction,
                reviewOnly = false,
                model = stages.single().model,
                plannedByLlm = true,
            )
        }
        return Compiled.Plan(
            DelegationPlan(
                goal = goal,
                stages = stages,
                source = DelegationPlan.Source.AUTO,
                fromTemplate = true,
            ),
            participants = participants,
            plannedByLlm = true,
        )
    }

    private fun driverNotInPlace(key: String, available: Set<String>): String =
        "driver '" + key + "' is not installed (available: " +
            (available.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "none") + ")"

    /** worker 棒的角色名：命中组员的角色标签（进卡面），空白回退 worker。 */
    private fun workerStageRole(hit: TeamMemberInfo): String = hit.role.ifBlank { "worker" }

    /**
     * 审查棒指令：聚焦"对当前工作区/分支的产出做审计"，中文角色标签进提示
     * （"你是审查/测试角色"）；结论行（`VERDICT: PASS|REJECT`）由
     * `DesktopStageChain` 对 i>0 的棒自动追加 —— 编译层**不重复**。
     *
     * 优先采纳组员人设自定义提示词 [customPrompt]；未定义时分两档回落：
     * 角色标签是**测试**棒 → 执行验证模板（跑命令、以证据说话）；其余 → 系统默认
     * 审查模版。kind 表达不了这个区分（tester 与 reviewer 同为 REVIEWER ——
     * 执行档只分干活/审查两档），能区分它们的只有声明里的角色标签。
     *
     * **目标必须全文进审查指令，绝不截断**。任务书不是交接摘要：逐条 AC 那种长需求
     * 一旦截到前几十字，末尾几条对审查棒就根本不存在，它只能据「无法核验」判
     * REJECT；回炉后 worker 认为活已干完、零补丁，整条链停摆。交接摘要另走
     * `MAX_SUMMARY` 预算（那是上一棒说过的话），不能拿来当任务书的替身。
     * [workerInstruction] 一直用全文，这里必须与它对称。
     */
    internal fun reviewInstruction(goal: String, roleLabel: String?, customPrompt: String? = null): String {
        val role = roleLabel?.trim().orEmpty()
        val roleLine = if (role.isEmpty()) "" else "[role: $role] "
        val custom = customPrompt?.trim().orEmpty()
        val ask =
            ". Give findings item by item, a problem list, and an explicit final verdict line; do not rewrite the implementation."
        val body = if (custom.isNotEmpty()) {
            custom + "\nGoal: " + goal + ask
        } else if (isTestingRole(role)) {
            "Verify the goal by actually running the available build/test commands against " +
                "the workspace: " + goal + ask
        } else {
            "Review the current workspace against the goal: " + goal + ask
        }
        return roleLine + body
    }

    /**
     * 声明里的自由文本角色标签是不是「测试」棒。子串匹配是有意的宽口径：标签是
     * 用户可改的自由文本（测试员 / Testing / QA…），词表窄了分流就不发生 ——
     * 误伤面（role 恰好含 "test" 的非测试角色）远小于漏配面。
     */
    private fun isTestingRole(label: String): Boolean {
        val lower = label.lowercase()
        if (TESTING_ROLE_WORDS.any { lower.contains(it) }) return true
        return label.contains(TESTING_ROLE_ZH) || label.contains(TESTING_ROLE_ZH_QC)
    }

    /** ASCII 侧的测试角色词（"test" 已覆盖 tester/testing/test-…）。 */
    private val TESTING_ROLE_WORDS = listOf("test", "qa")

    /** 中文侧的测试角色词 —— 非 UI 文案，进 cjkLiteralGate 基线（理由见基线注）。 */
    private const val TESTING_ROLE_ZH = "测试"

    /** 同上：质检（QC）也是测试棒 —— 词表窄了分流就不发生，误伤面远小于漏配面。 */
    private const val TESTING_ROLE_ZH_QC = "质检"

    /**
     * 干活棒指令：优先采纳组员自定义人设提示词 [customPrompt] 并拼接目标。
     */
    internal fun workerInstruction(goal: String, customPrompt: String? = null): String {
        val custom = customPrompt?.trim().orEmpty()
        return if (custom.isNotEmpty()) {
            custom + "\nGoal: " + goal
        } else {
            goal
        }
    }
}
