// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.TeamMemberInfo
import kotlinx.serialization.Serializable

/**
 * 一份**团队档案**：团队自己的名字 + 名册，住在本机设置里。
 *
 * ## 它解决的问题
 *
 * 团队原先只是群声明里的一条席位，没有名字 —— 卡面只能显示群名，于是
 * 「改群名 = 改团队名」、并且「一个团队只能待在一个群」。档案把名字与名册从
 * 「某个群」提升成本机的一份对象，群里的席位只持有 [teamId] 引用：
 *
 * - 改名一次生效：改档案 → 所有引用它的群重写快照（[TeamProfileWritePath]）；
 * - 一份名册多群复用：同一支队进第二个群时不再需要重新填一遍组员；
 * - 群能把**一个团队**整体加进来，而不是逐个加人。
 *
 * ## 它不是什么
 *
 * - **不是派单目标**：派单只认 leader（由它内部协调）或某个组员的 token，
 *   不存在「把任务派给团队」这件事（[com.kapp.tenon.core.protocol.TeamMention] 的解析面不变）。
 * - **不是全局对象**：它住在领队宿主本机，不进任何协议帧；对端拿到的是席位上的快照
 *   （`VirtualAgentMember.teamName` / `teamMembers`）。
 * - **不是执行配置**：底座与模型仍住 `TeamExecutionPort`（团队粒度、宿主本地）。
 *
 * @property teamId 档案键。由创建方（领队宿主）分配，`tm-` 前缀。席位上的
 *   `VirtualAgentMember.teamId` 指向它；档案丢失时席位回落快照（名字还在、改名失效）。
 * @property name 团队显示名（人看的，可含中文，不用作 `@` token）。长度见 `Limits.TEAM`。
 * @property leaderAgentId 领队在本机的 agent 身份键（不是席位显示名）。
 * @property members 名册快照；组员没有 `hostNodeId`，执行宿主恒为领队宿主。
 * @property rev 档案自身版本，每次写入 +1（审计与「重建是否真的发生了」的判据）。
 */
@Serializable
public data class TeamProfile(
    val teamId: String,
    val name: String,
    val leaderAgentId: String,
    val members: List<TeamMemberInfo> = emptyList(),
    val rev: Int = 0,
    val updatedTs: Long = 0,
) {
    /** 名册 → 编辑期草稿（写路径与投影重建共用一份转换，别处不再手抄）。 */
    public fun rosterDrafts(): List<TeamMemberDraft> =
        members.map { TeamMemberDraft(agentId = it.agentId, name = it.name, role = it.role) }
}

/** 按 [teamId] 找档案；null = 没有这份档案（调用方如实区分「没有」与「空名册」）。 */
public fun List<TeamProfile>.byTeamId(teamId: String?): TeamProfile? {
    val key = teamId?.trim().orEmpty()
    if (key.isEmpty()) return null
    return firstOrNull { it.teamId == key }
}

/**
 * 哪些群正在引用这份档案（席位 `teamId` 相等）—— **全仓唯一一份判定**。
 *
 * 两处读它：写路径（改名 / 加群 / 解散要知道改哪些群）与界面（「这支队还在不在群里」）。
 * 各写一遍 `filter` 的后果不是重复代码，是**判据分叉**：某天一方加了条件、另一边没加，
 * 界面就会把一支正在群里干活的队伍画成「未入驻」，或者反过来，让用户解散一支他以为
 * 已经没了的队伍。
 *
 * 返回群对象而不只是 id：界面要判「空」，写路径要 id（`.map { it.groupId }`）。
 */
public fun List<GroupMeta>.groupsReferencingTeam(teamId: String): List<GroupMeta> =
    filter { group -> group.virtualAgents.orEmpty().any { it.teamId == teamId } }

/**
 * 席位上的团队展示名：**档案优先，快照兜底，最后才回落群名**。
 *
 * 三段兜底的理由：档案只有领队宿主有（对端只能看快照）；快照在老数据上可能没有；
 * 群名是最后的兜底 —— 显式写出来，避免调用方各自写一遍 `?:` 时顺序不一致
 * （那会让同一支队在两端显示两个名字）。
 */
public fun teamDisplayName(teamName: String?, groupName: String): String =
    teamName?.trim()?.takeIf { it.isNotEmpty() } ?: groupName
