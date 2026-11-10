// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.TeamMention
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.resolveTeamMemberToken

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
class TeamExecutionResolver(private val store: TeamExecutionStore) {

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
        when (val hit = group.resolveTeamMemberToken(mentionedKey)) {
            is TeamMention.Ambiguous ->
                return Target.NotConfigured(null, "member token matches multiple seats (hand-edited meta?)")
            is TeamMention.Hit -> {
                val seat = hit.seat
                if (seat.hostNodeId != selfNodeId) {
                    return Target.NotConfigured(seat, "this seat is hosted on another device")
                }
                val wsId = seat.defaultWsId?.trim().orEmpty()
                if (wsId.isEmpty()) {
                    return Target.NotConfigured(seat, "seat has no default workspace bound")
                }
                val profile = store.profileFor(groupId, wsId, seat.agentId) ?: run {
                    val declared = seat.teamMembers.map { it.agentId }
                    if (declared.isEmpty()) {
                        return Target.NotConfigured(seat, "team has no members declared")
                    }
                    return Target.NotConfigured(seat, "team execution is not configured for this seat")
                }
                val declaredIds = seat.teamMembers.map { it.agentId.trim().lowercase() }.toSet()
                val dangling = profile.members.filter { it.agentId.trim().lowercase() !in declaredIds }
                if (dangling.isNotEmpty()) {
                    return Target.NotConfigured(
                        seat,
                        "execution profile is stale: " + dangling.joinToString(",") { it.agentId } +
                            " no longer in the roster",
                    )
                }
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
        data class Single(val agentKey: String, val instruction: String, val reviewOnly: Boolean) : Compiled

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
    ): Compiled {
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
                instruction = reviewInstruction(goal, hitMember.role),
                reviewOnly = true,
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
                instruction = goal,
                reviewOnly = false,
            )
        }
        val stages = buildList {
            add(ChainStage(role = workerStageRole(hitMember), agentKey = worker.driverKey, instruction = goal))
            reviewers.forEach { reviewer ->
                val declaredRole = declaredRoleById[reviewer.agentId.trim().lowercase()]
                add(
                    ChainStage(
                        role = declaredRole?.ifBlank { null } ?: "reviewer",
                        agentKey = reviewer.driverKey,
                        instruction = reviewInstruction(goal, declaredRole),
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
     */
    internal fun reviewInstruction(goal: String, roleLabel: String?): String {
        val role = roleLabel?.trim().orEmpty()
        val roleLine = if (role.isEmpty()) "" else "[role: $role] "
        return roleLine + "Review the current workspace against the goal: " + goal.take(80) +
            ". Give findings item by item, a problem list, and an explicit final verdict line; do not rewrite the implementation."
    }
}
