// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.sameAgent
import com.kapp.tenon.domain.model.AgentSessionKey

/**
 * 团队视图的**一行成员**（团队视角，不变量 5 的「团队」维）。
 *
 * 名字与角色都是**这个团队里**的（协议里它们挂在席位上，员工换团队就换一份名字/角色），
 * 所以同一名员工在两个团队会出现两行、两段名字 —— 这是事实，不是重复。
 */
public data class AgentTeamMemberUi(
    /** 成员身份键（`TeamMemberInfo.agentId` / 领队的席位 `agentId`）；展开详情用。 */
    val agentId: String,
    /** 本团队内的 @ token。空 = 声明里没写（UI 翻译，不拿 agentId 顶替）。 */
    val name: String,
    /** 本团队内的角色；空 = 声明里没写。 */
    val role: String,
    /** 这一位是不是领队（`true` 时 [role] 恒空：领队席位没有 role 字段）。 */
    val isLeader: Boolean,
)

/**
 * 一张**团队卡**（团队视图的实体）：键 `(groupId, leaderAgentId)`。
 *
 * ## 为什么键必须是二元组
 *
 * `groupId` 单独不够用：一个群可以有多条团队席位（`entityType = "team"`），
 * `TeamMention.resolveTeamMemberToken` 专门有 `Ambiguous` 分支处理「token 命中多个席位」。
 * 拿 `groupId` 当团队键会让同一个群的两支队伍挤成一张卡，成员表互相污染。
 *
 * ## 为什么没有 teamId
 *
 * 团队不是独立对象：它只是群声明里的一条 `VirtualAgentMember`（带 `teamMembers`）。
 * 所以这里也没有 id 字段可给 —— [groupId] + [leaderAgentId] 就是它全部的身份。
 */
public data class AgentTeamUi(
    val groupId: String,
    /** 领队席位身份键（团队键的另一半）。 */
    val leaderAgentId: String,
    /** 领队所在宿主。组员没有 `hostNodeId` —— 它们的执行宿主就是这里（执行事实，非推断）。 */
    val hostNodeId: String,
    /**
     * 群名。**不再是团队名**：团队有自己的名字（[teamName]，来自本机团队档案）。
     *
     * 这个名字仍要保留：同一支队伍可以进多个群，卡面上「它在这几个群里的哪一个」
     * 只能靠群名回答；没有它，两张同名团队卡会变成无法区分的一对。
     */
    val groupName: String,
    /**
     * 团队档案键；null = 没有档案（群内直编的旧团队）。
     *
     * 有档案时改名/换名册走档案（一次改动对所有引用群生效）；没档案时只能走群内整表替换。
     */
    val teamId: String?,
    /**
     * 团队自己的名字（档案的广播快照）；null = 没有独立名，卡面回落 [groupName]。
     *
     * 消费方**不要自己写 `?:`**：统一走 [teamDisplayName]，否则「档案 → 快照 → 群名」的
     * 兜底顺序会在不同屏上不一致，同一支队显示成两个名字。
     */
    val teamName: String?,
    /** 领队的席位名；空 = 声明里没写。 */
    val leaderName: String,
    /** 成员表：领队在前，其余按声明序。 */
    val members: List<AgentTeamMemberUi>,
    /** 团队人数 = 1 + 组员数（与 [AgentHubUi.teamSize] 同一个算法）。 */
    val teamSize: Int,
    /** 领队宿主在不在线（席位在、宿主没被发现过 = 离线，如实，不当作「空闲」）。 */
    val online: Boolean,
    /**
     * **本群**待审批数。
     *
     * 不是「成员名下待审批合计」：那个数会把成员在**别的群**的待批算进来，团队卡上写
     * 出来就是一句假话。这里按「审批 → 会话 → 会话自报 wsId → 席位声明的绑定群」
     * 逐条归因（与 `AgentHubGroupRow.pendingCount` 同一把尺子）。
     */
    val pendingCount: Int,
    /** 成员最近一次活动（epoch ms；0 = 没有任何活动）。排序与「最近活动」文案读它。 */
    val lastActivityAt: Long,
    /** 本团队席位声明的执行点目录名；null = 未声明 / 宣告还没到（不拿 id 顶替）。 */
    val primaryWsTitle: String?,
)

/**
 * 团队视图的投影（团队视角）：每个 `(groupId, leaderAgentId)` 一张卡。
 *
 * ## 与员工卡同一份事实
 *
 * 成员匹配、在线态、最近活动全部复用 [hubAgents] 的同一套装配（同一份聚类结果），
 * 不在这里另写一套身份归一 —— 否则同一个人在员工视图和团队视图里会变成两个「人」。
 *
 * ## 排序
 *
 * 本群待批多者在前 → 成员最近活动新者在前 → 群名与领队钉住稳定序（与卡片流的
 * Urgent-First 同一条口径，只是归因单位从「员工」换成「团队」）。
 */
public fun agentTeams(
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    now: Long,
): List<AgentTeamUi> {
    val cards = hubAgents(agentSlice, nodeSlice, settingsSlice, now)
    val groups = settingsSlice.settings.groups
    val pendingByGroup = pendingCountByGroup(agentSlice, settingsSlice, now)

    val out = mutableListOf<AgentTeamUi>()
    for (group in groups) {
        for (seat in group.virtualAgents.orEmpty()) {
            // 「团队」的判据与 `isTeam` 同源（声明是团队，或组员清单非空）。
            val isTeamSeat = seat.entityType == com.kapp.tenon.core.protocol.EntityTypes.TEAM ||
                seat.teamMembers.isNotEmpty()
            if (!isTeamSeat) continue

            val leaderCard = cards.firstOrNull {
                it.hostNodeId == seat.hostNodeId && sameAgent(it.agentKey, null, seat.agentId)
            }
            val memberRows = buildList {
                add(AgentTeamMemberUi(agentId = seat.agentId, name = seat.name, role = "", isLeader = true))
                seat.teamMembers.forEach { member ->
                    add(
                        AgentTeamMemberUi(
                            agentId = member.agentId,
                            name = member.name,
                            role = member.role,
                            isLeader = false,
                        ),
                    )
                }
            }
            // 成员最近活动：领队 + 组员在**同一宿主**上的卡片时刻取最大。
            // 组员没有 hostNodeId，它们的执行宿主就是领队宿主（见 AgentTeamUi 的 KDoc）。
            val lastAt = memberRows
                .mapNotNull { row ->
                    cards.firstOrNull {
                        it.hostNodeId == seat.hostNodeId && sameAgent(it.agentKey, null, row.agentId)
                    }?.lastActivityAt
                }
                .maxOrNull() ?: 0L
            val primaryWs = seat.defaultWsId?.takeIf { it.isNotBlank() }
                ?.let { wsId -> agentSlice.agentWorkspaces.values.firstOrNull { it.wsId == wsId }?.wsTitle }

            out += AgentTeamUi(
                groupId = group.groupId,
                leaderAgentId = seat.agentId,
                hostNodeId = seat.hostNodeId,
                groupName = group.name,
                teamId = seat.teamId,
                teamName = seat.teamName?.takeIf { it.isNotBlank() },
                leaderName = seat.name,
                members = memberRows,
                teamSize = 1 + seat.teamMembers.size,
                online = leaderCard?.online ?: (seat.hostNodeId == settingsSlice.settings.nodeId),
                pendingCount = pendingByGroup[group.groupId] ?: 0,
                lastActivityAt = lastAt,
                primaryWsTitle = primaryWs,
            )
        }
    }
    return out.sortedWith(
        compareByDescending<AgentTeamUi> { it.pendingCount }
            .thenByDescending { it.lastActivityAt }
            .thenBy { it.groupName }
            .thenBy { it.leaderAgentId },
    )
}

/**
 * **本群**待审批数（`groupId → 条数`）：审批 → 会话 → 会话自报 `wsId` → 席位声明的绑定群。
 *
 * 归因不上的审批（直通宿主私聊、目录没绑群）**不进这张表** —— 它们不属于任何团队，
 * 塞给「碰巧同一个目录」的群就是编造归属（与 `no-conv-bound` 同一条纪律）。
 */
public fun pendingCountByGroup(
    agentSlice: AgentSlice,
    settingsSlice: SettingsSlice,
    now: Long,
): Map<String, Int> {
    val groups = settingsSlice.settings.groups
    val out = mutableMapOf<String, Int>()
    agentSlice.agentApprovals.values.forEach { approval ->
        if (!isPendingApproval(approval, now)) return@forEach
        val session = agentSlice.agentSessions[AgentSessionKey(approval.peerId, approval.sessionId)]
        val group = groupBoundToWorkspace(session?.wsId, groups) ?: return@forEach
        out[group.groupId] = (out[group.groupId] ?: 0) + 1
    }
    return out
}
