// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.resolveTeamMemberToken

/**
 * 团队执行档的**壳层端口**（团队执行方案 §6.3/§7.1）：UI 读/写宿主本地执行配置的唯一通路。
 *
 * ## 为什么是端口而不是直调
 *
 * `:app`（域层）不许 import 驱动/编排类型（`:domainComposeFreeGate` 同一条边界纪律）：
 * 驱动名册、执行档落盘、保存校验的实现都在 `:orchestration`/`desktopApp`，经壳注入。
 * 与 `onExecDevices` / `invokeExecutor` 同一形状 —— **手机端端口为 null ⇒ UI 只读**
 * （执行配置由席位宿主管理，手机只看声明投影）。
 */
interface TeamExecutionPort {

    /**
     * 宿主**在位**的驱动键集合（`AgentProfiles.knownKeys() ∩ resolveCliDriver`）。
     * 驱动下拉只画这里面的键 —— "装了没"是宿主当下的事实，不是保存时的事实。
     */
    fun driverCatalog(): Set<String>

    /** 读该席位的执行档；null = 没配过（正常）。 */
    fun profileOf(groupId: String, dirId: String, leaderAgentId: String): TeamExecutionProfileUi?

    /** 校验（保存前预检 + UI 就地指出原因）；与 save 共用同一份规则（§3.4 单源）。 */
    fun validate(profile: TeamExecutionProfileUi, declaredMemberIds: Set<String>): List<String>

    /** 保存（同主键覆盖）。Rejected 携带结构化原因，UI 直接展示 —— 不允许"先存着"。 */
    fun save(profile: TeamExecutionProfileUi, declaredMemberIds: Set<String>): TeamExecutionSaveResult

    /**
     * 群项目目录指针（D16：执行档主键的一半）。null = 群没设目录 / 本机不是宿主 ——
     * 编制对话框据此置灰保存（执行档挂在一个解析不出来的目录上没有意义）。
     */
    fun groupPointerDirId(groupId: String): String? = null
}

/** 端口侧的执行档成员（UI 编辑形状；与编排层 TeamExecutionMemberSpec 逐字段对齐）。 */
data class TeamExecutionMemberUi(
    val agentId: String,
    val kind: String,
    val driverKey: String,
    val models: List<String> = emptyList(),
    val customPrompt: String? = null,
) {
    val isWorker: Boolean get() = kind == TeamExecutionKindsUi.WORKER
}

/** 成员 kind 的取值域（UI 侧单一定义；编排层 TeamExecutionKinds 与它逐字同源）。 */
object TeamExecutionKindsUi {
    const val WORKER: String = "worker"
    const val REVIEWER: String = "reviewer"
}

data class TeamExecutionProfileUi(
    val groupId: String,
    val dirId: String,
    val leaderAgentId: String,
    val members: List<TeamExecutionMemberUi>,
)

sealed interface TeamExecutionSaveResult {
    data object Saved : TeamExecutionSaveResult
    data class Rejected(val reasons: List<String>) : TeamExecutionSaveResult
}

/**
 * 声明层席位 → 执行档**初值**（团队编制对话框打开时预填）：声明里的每个组员一条，
 * 既有执行档的同 agentId 条目**沿用其执行配置**（编辑保全 —— 改名/改角色不重算执行配置）；
 * 没配过的新组员默认第一名为 worker、其余 reviewer，驱动取目录第一个（UI 可改）。
 */
fun initialExecutionProfileUi(
    port: TeamExecutionPort,
    group: GroupMeta?,
    seat: VirtualAgentMember,
): TeamExecutionProfileUi? {
    if (group == null) return null
    // D16：键的一半换成群指针的 dirId —— 没设指针就取不到初值（对话框会置灰）。
    val dirId = port.groupPointerDirId(group.groupId) ?: return null
    val existing = port.profileOf(group.groupId, dirId, seat.agentId)
    val declared = seat.teamMembers
    // **单兵席位也要有执行层**（不变量 5）：没有 teamMembers 时从前直接 null ⇒ 单兵
    // 根本没有「底座/模型」这一层，换底座只能换席位本身（`agentId` 是身份）⇒ 编制与
    // 目录一起丢。这里给单兵生成一条 worker：`agentId` 仍是席位 slug（身份不随底座变），
    // `driverKey` 留空等机主显式指派 —— **不猜**（猜错比空着更贵，空着会被保存校验拦下）。
    if (declared.isEmpty()) {
        val prior = existing?.members?.firstOrNull {
            it.agentId.trim().lowercase() == seat.agentId.trim().lowercase()
        }
        return TeamExecutionProfileUi(
            groupId = group.groupId,
            dirId = dirId,
            leaderAgentId = seat.agentId,
            members = listOf(
                TeamExecutionMemberUi(
                    agentId = seat.agentId,
                    kind = TeamExecutionKindsUi.WORKER,
                    driverKey = prior?.driverKey.orEmpty(),
                    models = prior?.models ?: emptyList(),
                    customPrompt = prior?.customPrompt,
                ),
            ),
        )
    }
    val members = declared.mapIndexed { index, member ->
        val prior = existing?.members?.firstOrNull {
            it.agentId.trim().lowercase() == member.agentId.trim().lowercase()
        }
        TeamExecutionMemberUi(
            agentId = member.agentId,
            kind = prior?.kind ?: if (index == 0) TeamExecutionKindsUi.WORKER else TeamExecutionKindsUi.REVIEWER,
            driverKey = prior?.driverKey ?: "",
            models = prior?.models ?: emptyList(),
        )
    }
    return TeamExecutionProfileUi(group.groupId, dirId, seat.agentId, members)
}

/**
 * UI 侧保存校验（与编排层 save 共用同一份规则口径；端口的 validate 实现会再做一次
 * 完整校验 —— 这里只做**结构前置**，给出可就地显示的原因）。
 */
fun validateExecutionProfileUi(
    profile: TeamExecutionProfileUi,
    declaredMemberIds: Set<String>,
    driverCatalog: Set<String>,
): List<String> {
    val reasons = mutableListOf<String>()
    val workers = profile.members.filter { it.isWorker }
    if (workers.size != 1) reasons.add("exactly one worker required (found " + workers.size + ")")
    val ids = profile.members.map { it.agentId.trim().lowercase() }
    if (ids.toSet().size != ids.size) reasons.add("duplicate member agentId")
    if (profile.members.size > Limits.TEAM_MEMBERS_MAX) {
        reasons.add("team size exceeds limit (" + profile.members.size + ")")
    }
    profile.members.forEach { m ->
        if (m.driverKey !in driverCatalog) {
            reasons.add("member " + m.agentId + ": driver not installed (" + m.driverKey + ")")
        }
        // 结构前置：本期只消费 `models.first`（没有轮换的消费路径），多值存下来就是
        // 死数据。**驱动能力校验不在这里**——那份词表在 `DriverModelFlags`
        // （agent-contract），UI 层再抄一份必然两处漂移；宿主侧
        // `TeamExecutionValidation` 会在真正落盘前按它拒一次。
        if (m.models.size > 1) {
            reasons.add("member " + m.agentId + ": only one model per member is supported (" + m.models.size + ")")
        }
    }
    val declared = declaredMemberIds.map { it.trim().lowercase() }.toSet()
    profile.members.forEach { m ->
        if (m.agentId.trim().lowercase() !in declared) {
            reasons.add("member " + m.agentId + " is not in the declared roster")
        }
    }
    return reasons
}

/**
 * 本群全部组员的 `@` token → (席位, 组员) 的 UI 侧解析（@ 候选与插入用）；
 * 判据单源 = core 的 [resolveTeamMemberToken]。
 */
fun teamMentionCandidates(group: GroupMeta?): List<Pair<VirtualAgentMember, TeamMemberInfo>> {
    if (group == null) return emptyList()
    val out = mutableListOf<Pair<VirtualAgentMember, TeamMemberInfo>>()
    group.virtualAgents.orEmpty().filter { seat ->
        seat.entityType == com.kapp.tenon.core.protocol.EntityTypes.TEAM || seat.teamMembers.isNotEmpty()
    }.forEach { seat ->
        seat.teamMembers.forEach { member ->
            if (member.name.isNotBlank()) out.add(seat to member)
        }
    }
    return out
}
