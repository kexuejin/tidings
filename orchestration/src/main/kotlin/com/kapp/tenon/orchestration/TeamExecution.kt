// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.TeamMention
import com.kapp.tenon.core.protocol.TeamNameMention
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.resolveTeamMemberToken
import com.kapp.tenon.core.protocol.teamSeatMatchingName

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
        /** 完整命中：席位 + 组员 + 有效执行档。 */
        data class Hit(
            val seat: VirtualAgentMember,
            val member: TeamMemberInfo,
            val profile: TeamExecutionProfile,
        ) : Target

        /** 是组员 token，但没有可用的执行档 —— blocked 卡要写清 [reason]。 */
        data class NotConfigured(val seat: VirtualAgentMember?, val reason: String) : Target

        /** 不是组员 token —— 调用方回落既有路径。 */
        data object None : Target
    }

    fun resolve(
        groupId: String?,
        mentionedKey: String?,
        group: GroupMeta?,
        selfNodeId: String,
    ): Target {
        if (groupId == null || mentionedKey.isNullOrBlank() || group == null) return Target.None
        // **`@团队名`（整队）先判**：团队名是展示串，组员 token 是 ASCII slug，两套词表
        // 不能混在一个循环里（混了就是「团队叫 coder 时 @ 到组员」）。命中团队的语义是
        // 「这活整队接」—— 编到执行档唯一那个 worker，其余 reviewer 照既有责任链走。
        when (val teamMention = group.teamSeatMatchingName(mentionedKey)) {
            is TeamNameMention.Ambiguous ->
                return Target.NotConfigured(null, teamNameAmbiguous(mentionedKey))
            is TeamNameMention.Hit -> {
                val seat = teamMention.seat
                val (profile, denial) = profileForSeat(groupId, seat, selfNodeId)
                if (profile == null) return Target.NotConfigured(seat, denial.orEmpty())
                val worker = profile.worker
                    ?: return Target.NotConfigured(seat, "team execution profile has no worker")
                val member = seat.teamMembers.firstOrNull {
                    it.agentId.trim().lowercase() == worker.agentId.trim().lowercase()
                } ?: return Target.NotConfigured(
                    seat,
                    "execution profile is stale: worker '" + worker.agentId + "' is not in the roster",
                )
                return Target.Hit(seat, member, profile)
            }
            null -> Unit
        }
        when (val hit = group.resolveTeamMemberToken(mentionedKey)) {
            is TeamMention.Ambiguous ->
                return Target.NotConfigured(null, "member token matches multiple seats (hand-edited meta?)")
            is TeamMention.Hit -> {
                val seat = hit.seat
                val (profile, denial) = profileForSeat(groupId, seat, selfNodeId)
                if (profile == null) return Target.NotConfigured(seat, denial.orEmpty())
                val spec = profile.members.firstOrNull {
                    it.agentId.trim().lowercase() == hit.member.agentId.trim().lowercase()
                } ?: return Target.NotConfigured(
                    seat,
                    "execution profile is stale: member '" + hit.member.name + "' is not configured",
                )
                return Target.Hit(seat, hit.member, profile)
            }
            null -> return Target.None
        }
    }

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
    ): Pair<TeamExecutionProfile?, String?> {
        if (seat.hostNodeId != selfNodeId) {
            return null to "this seat is hosted on another device"
        }
        val dirId = dirIdOfGroup(groupId)?.trim().orEmpty()
        if (dirId.isEmpty()) {
            return null to "group has no project directory bound"
        }
        val profile = store.profileFor(dirId, seat.agentId) ?: return null to if (
            seat.teamMembers.isEmpty()
        ) {
            "team has no members declared"
        } else {
            "team execution is not configured for this team"
        }
        val declaredIds = seat.teamMembers.map { it.agentId.trim().lowercase() }.toSet()
        val dangling = profile.members.filter { it.agentId.trim().lowercase() !in declaredIds }
        if (dangling.isNotEmpty()) {
            return null to (
                "execution profile is stale: " + dangling.joinToString(",") { it.agentId } +
                    " no longer in the roster"
                )
        }
        return profile to null
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
 * - 0/多 worker：**读取期也拒**（保存期已拒；旧数据/手改文件走到这里同样拒）。
 */
object TeamPlanCompiler {

    sealed interface Compiled {
        /** 单棒（无 reviewer，或命中 reviewer 的纯审查任务）：调用方做在位检查后 dispatchSingleWorker。 */
        data class Single(
            val agentKey: String,
            val instruction: String,
            val reviewOnly: Boolean,
            /**
             * 本棒的模型（执行档 `models.first`）：null = 跟随驱动默认。
             * 调用方必须原样传到 `executeCliTask(model = …)` —— 只在这里携带、
             * 下游不消费，就是又造一份「存了不生效」的死数据。
             */
            val model: String? = null,
        ) : Compiled

        /** 多棒责任链：交给 `delegateByPlan`（gate → 链执行器）。 */
        data class Plan(val plan: DelegationPlan) : Compiled

        /** 编译期拒绝：blocked 卡写 [reason]。 */
        data class Rejected(val reason: String) : Compiled
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
    ): Compiled {
        val briefSuffix = capabilityBrief?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "\n\n" + it }.orEmpty()
        // 声明里的角色标签（编码/审查/测试…）按 agentId 对齐 —— role 属于群声明
        // （展示事实），执行档只存执行配置（§3.2），两边各管各的。
        val declaredRoleById = declaredMembers.associate {
            it.agentId.trim().lowercase() to it.role.trim()
        }
        val workers = profile.members.filter { it.isWorker }
        if (workers.size != 1) {
            return Compiled.Rejected(
                "execution profile must have exactly one worker (found " + workers.size + ")",
            )
        }
        val worker = workers.single()
        val hitSpec = profile.members.firstOrNull {
            it.agentId.trim().lowercase() == hitMember.agentId.trim().lowercase()
        } ?: return Compiled.Rejected("member '" + hitMember.name + "' is not in the execution profile")

        // **null = 控制面不可用**（availableAgentKeys() 的 null 语义）：与"驱动不在位"
        // 同一处置 —— 单棒路径不经门，编译时就拒，不交给 dispatchSingleWorker 撞通用错误。
        val available: Set<String> = availableDrivers ?: emptySet()
        // 命中 reviewer：纯审查单棒 —— 对当前工作区/分支做审计并给 VERDICT。
        if (!hitSpec.isWorker) {
            if (hitSpec.driverKey !in available) {
                return Compiled.Rejected(driverNotInPlace(hitSpec.driverKey, available))
            }
            return Compiled.Single(
                agentKey = hitSpec.driverKey,
                instruction = reviewInstruction(goal, hitMember.role, hitSpec.customPrompt) + briefSuffix,
                reviewOnly = true,
                model = hitSpec.models.firstOrNull(),
            )
        }

        // 命中 worker：完整责任链。
        val reviewers = profile.reviewers
        if (reviewers.isEmpty()) {
            // §5.3：单棒路径不经过 StageChainGate —— 编译时就做在位检查，
            // 不交给 dispatchSingleWorker 去撞通用错误。
            if (worker.driverKey !in available) {
                return Compiled.Rejected(driverNotInPlace(worker.driverKey, available))
            }
            return Compiled.Single(
                agentKey = worker.driverKey,
                instruction = workerInstruction(goal, worker.customPrompt) + briefSuffix,
                reviewOnly = false,
                model = worker.models.firstOrNull(),
            )
        }
        val stages = buildList {
            add(
                ChainStage(
                    role = workerStageRole(hitMember),
                    agentKey = worker.driverKey,
                    instruction = workerInstruction(goal, worker.customPrompt) + briefSuffix,
                    model = worker.models.firstOrNull(),
                ),
            )
            reviewers.forEach { reviewer ->
                val declaredRole = declaredRoleById[reviewer.agentId.trim().lowercase()]
                add(
                    ChainStage(
                        role = declaredRole?.ifBlank { null } ?: "reviewer",
                        agentKey = reviewer.driverKey,
                        instruction = reviewInstruction(goal, declaredRole, reviewer.customPrompt) + briefSuffix,
                        model = reviewer.models.firstOrNull(),
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
