// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.RosterDenial
import com.kapp.tenon.agent.RosterEmployeeSnapshot
import com.kapp.tenon.agent.RosterMember
import com.kapp.tenon.agent.RosterTeamDraft
import com.kapp.tenon.agent.RosterTeamSnapshot
import com.kapp.tenon.agent.RosterWritePort
import com.kapp.tenon.agent.RosterWriteResult
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.domain.DeclarationActor
import com.kapp.tenon.domain.DeclarationOrigin
import com.kapp.tenon.domain.DeclarationRejection
import com.kapp.tenon.domain.EmployeeProfileCommand
import com.kapp.tenon.domain.EmployeeProfileResult
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.TeamProfileCommand
import com.kapp.tenon.domain.TeamProfileResult
import com.kapp.tenon.domain.applyEmployeeProfile
import com.kapp.tenon.domain.applyTeamProfile
import com.kapp.tenon.domain.byTeamId
import com.kapp.tenon.domain.groupsReferencingTeam

/**
 * 档案面（员工 + 团队）的宿主实现（P3 工具面）：把控制面的工具调用接到域层的唯一写路径上。
 *
 * ## 它只做三件事
 *
 * 1. 读快照（`RelaySettings.employeeProfiles` / `teamProfiles`）；
 * 2. 把命令交给 `applyEmployeeProfile` / `applyTeamProfile`（**不自己改设置**：跨群别名重建、
 *    rev 推进、广播都在那两条写路径里，抄一遍就会漂）；
 * 3. 把域层的拒绝翻译成**分类码**（句子由控制面那一层拼，见 [denialOf]）。
 *
 * ## 为什么 actor 是 AGENT
 *
 * 这两条写路径的审计与回执按 actor 区分「人点出来的」与「agent 自己改的」。
 * `agentKey` 非空 ⇒ `DeclarationActor.Agent(本机, agentKey)`；为空（工具没带身份）
 * 就退回 Human —— 那不常见，但**不编一个 agentId 出来**（编出来的审计行是假的）。
 *
 * ## 团队快照里为什么要带 `groupIds`
 *
 * 「这支队在哪些群」决定它能不能被 `@` 到。只回名册而不回落点，等于给 agent 一份
 * 看不见脚的清单 —— 它会以为队伍已经能接活。
 */
class DesktopRosterWritePort(private val controller: RelayController) : RosterWritePort {

    override fun employees(): List<RosterEmployeeSnapshot> =
        controller.state.value.settings.employeeProfiles.map { profile ->
            RosterEmployeeSnapshot(
                employeeId = profile.employeeId,
                agentId = profile.agentId,
                name = profile.name,
                duty = AgentDuties.normalize(profile.duty),
            )
        }

    override fun updateEmployee(
        agentKey: String?,
        employeeId: String,
        name: String?,
        duty: String?,
    ): RosterWriteResult {
        val self = controller.state.value.selfNodeId
        val before = employees().firstOrNull { it.employeeId == employeeId }
            ?: return RosterWriteResult.Denied(RosterDenial.UNKNOWN_EMPLOYEE, employeeId)
        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = employeeId,
                name = name,
                duty = duty,
                actor = actorOf(self, agentKey),
                origin = DeclarationOrigin.AGENT_TOOL,
            ),
        )
        return when (result) {
            is EmployeeProfileResult.Applied -> {
                val after = employees().firstOrNull { it.employeeId == employeeId } ?: before
                RosterWriteResult.Applied(before = before, after = after)
            }
            is EmployeeProfileResult.Rejected ->
                RosterWriteResult.Denied(denialOf(result.reason), result.reason.name)
        }
    }

    override fun teams(): List<RosterTeamSnapshot> {
        val settings = controller.state.value.settings
        // 每份档案都在手里，直接由它建快照 —— 不再按 teamId 回查（回查一遍等于把
        // 「档案列表」与「按键查找」两套语义摆在一起，空指针只是时间问题）。
        return settings.teamProfiles.map { profile -> snapshotOf(profile, settings) }
    }

    override fun createTeam(agentKey: String?, draft: RosterTeamDraft): RosterWriteResult {
        val self = controller.state.value.selfNodeId
        val result = controller.applyTeamProfile(
            TeamProfileCommand.Create(
                name = draft.name,
                leaderAgentId = draft.leaderAgentId,
                members = draft.members.map { it.toDraft() },
                groupIds = draft.groupIds,
                actor = actorOf(self, agentKey),
                origin = DeclarationOrigin.AGENT_TOOL,
            ),
        )
        return when (result) {
            is TeamProfileResult.Applied -> {
                val after = snapshotById(result.teamId, controller.state.value.settings)
                    ?: return RosterWriteResult.Denied(RosterDenial.PERSIST_REJECTED, result.teamId)
                RosterWriteResult.TeamApplied(before = null, after = after)
            }
            is TeamProfileResult.Rejected ->
                RosterWriteResult.Denied(denialOf(result.reason), result.reason.name)
        }
    }

    override fun updateTeam(
        agentKey: String?,
        teamId: String,
        name: String?,
        members: List<RosterMember>?,
    ): RosterWriteResult {
        val self = controller.state.value.selfNodeId
        val before = snapshotById(teamId, controller.state.value.settings)
            ?: return RosterWriteResult.Denied(RosterDenial.UNKNOWN_TEAM, teamId)
        val result = controller.applyTeamProfile(
            TeamProfileCommand.Update(
                teamId = teamId,
                name = name,
                members = members?.map { it.toDraft() },
                actor = actorOf(self, agentKey),
                origin = DeclarationOrigin.AGENT_TOOL,
            ),
        )
        return when (result) {
            is TeamProfileResult.Applied -> {
                // 部分群写失败也要回事实（`applied` 只代表档案落了、席位未必都重建成功）。
                val after = snapshotById(teamId, controller.state.value.settings) ?: before
                RosterWriteResult.TeamApplied(before = before, after = after)
            }
            is TeamProfileResult.Rejected ->
                RosterWriteResult.Denied(denialOf(result.reason), result.reason.name)
        }
    }

    private fun actorOf(self: String, agentKey: String?): DeclarationActor =
        agentKey?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { DeclarationActor.Agent(self, it) }
            ?: DeclarationActor.Human(self)

    /** 读一份当前快照（写完再读 = 回执里的 after 就是落盘后的事实，不是拼出来的）。 */
    private fun snapshotOf(
        profile: com.kapp.tenon.domain.TeamProfile,
        settings: com.kapp.tenon.domain.RelaySettings,
    ): RosterTeamSnapshot = RosterTeamSnapshot(
        teamId = profile.teamId,
        name = profile.name,
        leaderAgentId = profile.leaderAgentId,
        members = profile.members.map { RosterMember(it.agentId, it.name, it.role) },
        // 判据与写路径同一份（`groupsReferencingTeam`）：这里数错了，agent 会以为
        // 队伍已经进了群而实际上群里看不见它。
        groupIds = settings.groups.groupsReferencingTeam(profile.teamId).map { it.groupId },
        rev = profile.rev,
    )

    private fun snapshotById(teamId: String, settings: com.kapp.tenon.domain.RelaySettings): RosterTeamSnapshot? =
        settings.teamProfiles.byTeamId(teamId)?.let { snapshotOf(it, settings) }

    private fun RosterMember.toDraft(): TeamMemberDraft =
        TeamMemberDraft(agentId = agentId, name = name, role = role)

    /**
     * 域层拒绝 → 工具面的分类码。**这里只做分类，不写句子**：文案住在呈现层
     * （HTTP 回执 / 审批卡），而本仓禁止 `:orchestration` 里出现硬编码中文。
     */
    private fun denialOf(reason: DeclarationRejection): RosterDenial = when (reason) {
        DeclarationRejection.NAME_INVALID -> RosterDenial.NAME_INVALID
        DeclarationRejection.EMPLOYEE_UNKNOWN -> RosterDenial.UNKNOWN_EMPLOYEE
        DeclarationRejection.TEAM_UNKNOWN -> RosterDenial.UNKNOWN_TEAM
        DeclarationRejection.ROSTER_INVALID -> RosterDenial.ROSTER_INVALID
        DeclarationRejection.EMPLOYEE_EXISTS -> RosterDenial.ARGUMENT_INVALID
        DeclarationRejection.ARGUMENT_INVALID -> RosterDenial.ARGUMENT_INVALID
        DeclarationRejection.NOT_MEMBER -> RosterDenial.NOT_MEMBER
        DeclarationRejection.PERSIST_REJECTED -> RosterDenial.PERSIST_REJECTED
        else -> RosterDenial.OTHER
    }
}
