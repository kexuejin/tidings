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
    /**
     * [primaryWsTitle] 对应的目录键；null = 本群席位没声明执行点。
     *
     * 名录只画名字，但详情页要能点进工作区详情 —— 「哪个目录」这件事必须有 id，
     * 不能从名字反推（两个目录可以同名）。
     */
    val primaryWsId: String?,
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
                primaryWsId = seat.defaultWsId?.takeIf { it.isNotBlank() },
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
 * 团队详情里的一行「这支队在这个群」。
 *
 * 同一支队伍可以进多个群（[AgentTeamUi.teamId] 相同的多张卡），详情页要把它们**一次说清**：
 * 每群一行，各自带本群的执行点目录与待批数 —— 这两件事本来就是按群算的，混成一行就是编。
 */
public data class AgentTeamGroupRow(
    val groupId: String,
    val groupName: String,
    /** 席位在本群声明的执行点目录；null = 未声明（不拿别的群的目录顶替）。 */
    val wsId: String?,
    /** [wsId] 命中的宣告目录名；null = 未命名或宣告未到。 */
    val wsTitle: String?,
    /** 本群归因到本团队的待批数（与名录行同一把尺子）。 */
    val pendingCount: Int,
)

/**
 * 团队详情页的投影（团队视角的实体页）：给定团队键 `(groupId, leaderAgentId)`，
 * 输出该团队的整页数据。
 *
 * ## 为什么不是 `agentHubDetail`
 *
 * 员工详情回答的是「**这个人**是谁、服务哪些群、名下有哪些目录」，它的键是
 * `(hostNodeId, agentId)`。团队不是一个人：领队只是它的代表，组员在这个模型里
 * 连独立席位都没有（执行宿主恒为领队宿主）。点团队却落在领队的员工页，用户看到的
 * 标题是人名、段落是人的任职 —— 那不是「这支队」的页面，是「带这支队的那个人」的页面。
 *
 * ## 它复用同一份事实
 *
 * 输入就是 [agentTeams] 的输出（团队席位 → 团队行），不在这里再读一遍切片：
 * 名录行上写的名字、人数、待批、目录，与详情页里展开的是同一份，不存在第二个算法。
 *
 * ## 返回 null 的唯一情形
 *
 * 键定位不到任何团队行（席位撤销了，或导航层残留了一个已消失的键）—— 如实，
 * 由 UI 画空态；不落回任何"最接近的"团队（那会让返回键按一下画面不动）。
 */
public data class AgentTeamDetailUi(
    /** 定位用的群（用户点进来的那一行）；[groups] 的第一行就是它。 */
    val groupId: String,
    val leaderAgentId: String,
    /** 领队所在宿主：组员的执行宿主也是这里。 */
    val hostNodeId: String,
    val teamId: String?,
    /** 卡面标题（档案名 → 快照 → 群名，统一走 [teamDisplayName]）。 */
    val displayName: String,
    /** 定位群的群名（与标题不同时才有信息量，UI 自己决定画不画）。 */
    val groupName: String,
    val leaderName: String,
    /** 名册：领队在前，其余按声明序；名字是**本群**那一份（组内别名在这里生效）。 */
    val members: List<AgentTeamMemberUi>,
    val teamSize: Int,
    val online: Boolean,
    /** 各群待批之和（团队粒度；单群的团队等于那一群的数）。 */
    val pendingCount: Int,
    /** 成员最近活动（epoch ms；0 = 没有任何活动）。 */
    val lastActivityAt: Long,
    /** 这支队在哪些群：定位群在前，其余按群名钉住稳定序。 */
    val groups: List<AgentTeamGroupRow>,
)

/**
 * 团队详情投影：从 [agentTeams] 的行里定位一支队伍，并把它的**全部群**并成一份。
 *
 * 归并条件里必须带 `hostNodeId`：`teamId` 是领队宿主本机分配的档案键，两端各自
 * 建过一支同名档案时字符串可以撞上；把别的宿主那支队并进这一个，就会把两支人合成一张表。
 * 没档案的旧团队（`teamId == null`）只有它自己那一行 —— 它本来就没有跨群的第二个家。
 */
public fun agentTeamDetail(
    teams: List<AgentTeamUi>,
    groupId: String,
    leaderAgentId: String,
): AgentTeamDetailUi? {
    val anchor = teams.firstOrNull { it.groupId == groupId && it.leaderAgentId == leaderAgentId }
        ?: return null
    val sameTeam = if (anchor.teamId == null) {
        listOf(anchor)
    } else {
        teams.filter { it.hostNodeId == anchor.hostNodeId && it.teamId == anchor.teamId }
    }
    // 一个群只留一行：同 teamId 的两条同群席位不存在（档案写路径按群去重），
    // 真出现了也只说一次，不让待批被数两遍。
    val groups = sameTeam
        .distinctBy { it.groupId }
        .sortedWith(
            compareByDescending<AgentTeamUi> { it.groupId == anchor.groupId }
                .thenBy { it.groupName }
                .thenBy { it.leaderAgentId },
        )
        .map { row ->
            AgentTeamGroupRow(
                groupId = row.groupId,
                groupName = row.groupName,
                wsId = row.primaryWsId,
                wsTitle = row.primaryWsTitle,
                pendingCount = row.pendingCount,
            )
        }
    return AgentTeamDetailUi(
        groupId = anchor.groupId,
        leaderAgentId = anchor.leaderAgentId,
        hostNodeId = anchor.hostNodeId,
        teamId = anchor.teamId,
        displayName = teamDisplayName(anchor.teamName, anchor.groupName),
        groupName = anchor.groupName,
        leaderName = anchor.leaderName,
        // 名册取**定位群**那一份：组内别名按群生效，用户点的就是这一行，展开的也该是这一份。
        members = anchor.members,
        teamSize = anchor.teamSize,
        online = anchor.online,
        pendingCount = groups.sumOf { it.pendingCount },
        lastActivityAt = sameTeam.maxOfOrNull { it.lastActivityAt } ?: anchor.lastActivityAt,
        groups = groups,
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

/**
 * 一支**不在任何群里**的团队档案（孤儿）。
 *
 * 与 [AgentTeamUi] 的区别不是一个字段，而是**没有席位**：它没有群、没有在线态、
 * 没有待批、没有执行点目录 —— 那些事实都由席位声明承载，而它没有席位。
 */
public data class AgentTeamOrphanUi(
    /** 档案键（解散与「加回某群」都按它定位）。 */
    val teamId: String,
    /** 档案名（档案是唯一真相：没有席位就不存在第二份名字可回落）。 */
    val name: String,
    /** 人数 = 领队 1 + 组员数（与 [AgentTeamUi.teamSize] 同一算法）。 */
    val memberCount: Int,
)

/**
 * 未入驻任何群的团队档案。
 *
 * ## 为什么需要这个投影
 *
 * 团队名录按**席位**装配（[agentTeams]）：一支队伍被逐群「移除」、或它所在的群被解散
 * 之后，席位没了、名录里也就没有它那一行 —— 但档案还留在 `RelaySettings.teamProfiles`
 * 里，照样出现在群信息对话框的「添加团队」列表上。于是用户能看见它、却没有任何地方
 * 能删它，唯一的出路是先把它加回某个群、再从名录点进详情解散。
 *
 * 这不是「再加一个列表」，是**给一份仍然存在的数据一个落点**。
 *
 * ## 只在本机有档案时才非空
 *
 * `teamProfiles` 只住宿主本机（不进协议帧），手机端读到的恒为空列表 ⇒ 整段不画。
 * 与「团队档案只住宿主机」同一条纪律，不是特例。
 */
public fun orphanTeams(settingsSlice: SettingsSlice): List<AgentTeamOrphanUi> {
    val settings = settingsSlice.settings
    val profiles = settings.teamProfiles
    if (profiles.isEmpty()) return emptyList()
    val groups = settings.groups
    return profiles
        // 判据与写路径**同一份**（`groupsReferencingTeam`）：界面说它没入驻，就是写路径
        // 解散时会摘掉零个席位的那一支 —— 两边各写一遍 filter 迟早分叉，那时界面会把
        // 一支正在群里干活的队伍画成孤儿。
        .filter { groups.groupsReferencingTeam(it.teamId).isEmpty() }
        .map {
            AgentTeamOrphanUi(
                teamId = it.teamId,
                name = it.name,
                // 领队不计在 `members` 里（名单只列组员），与名录行同一算法。
                memberCount = 1 + it.members.size,
            )
        }
        // 没有活动数据可排：按名字钉稳定序（`sortedBy` 稳定，重名保持档案序）。
        .sortedBy { it.name }
}
