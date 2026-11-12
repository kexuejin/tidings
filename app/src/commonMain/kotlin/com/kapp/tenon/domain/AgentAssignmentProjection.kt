// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.sameAgent
import com.kapp.tenon.core.protocol.isTeam

/**
 * 一个**员工**在某个团队里的任职（不变量 5 的「归属」维度，零协议）。
 *
 * ## 为什么不是新实体
 *
 * 「员工可以归属多个团队」在协议里本来就有事实来源：同一个 `(hostNodeId, agentId)`
 * 会以**领队席位**（`VirtualAgentMember`）出现在一些群、以**组员条目**
 * （`TeamMemberInfo`）出现在另一些群。所以归属是「导出」，不是「新增字段」——
 * 这里只是把两处声明摊平成一张表。
 *
 * ## 为什么不能只看 `AgentHubUi.groupCount`
 *
 * 那个计数只数**领队席位**：一个 agent 在 A 群当领队、在 B 群当组员时 `groupCount == 1`，
 * 于是员工详情会写成「服务 1 群」——而它在 B 群是实打实在干活的那个人。
 * [isLeader] 就是这条差别的载体，两行不能合并成一行。
 */
public data class AgentAssignmentUi(
    val groupId: String,
    val groupName: String,
    /**
     * 本实体在该群的**席位名**（就是用户 @ 的那个 token）。
     *
     * 它是**每团队一份**的：同一个人在 A 群叫「墨查」、在 B 群叫「审查员」是合法的，
     * 因此名字不能拿来当员工主键（那样会把一个人拆成两张卡）。
     */
    val seatName: String,
    /** 这个群里本实体是领队（`true`）还是组员（`false`）。 */
    val isLeader: Boolean,
    /** 该席位是否团队形态（领队侧 = 自己带队；组员侧恒 `true`，因为它属于某个团队）。 */
    val isTeam: Boolean,
    /** 所在团队人数（领队 1 + 组员数）；非团队席位为 0。 */
    val teamSize: Int,
    /** 组员角色（`TeamMemberInfo.role`，展示用）；领队席位没有这个字段 ⇒ null。 */
    val role: String?,
    /** 该团队声明的执行点（席位 `defaultWsId`）；null = 未声明。**路径不过网**，只有 id。 */
    val defaultWsId: String?,
    /** [defaultWsId] 命中的已宣告目录标题；null = 未命名或宣告未到。 */
    val defaultWsTitle: String?,
)

/**
 * 某个员工的**全部任职**（每团队一行，按群名与身份稳定排序）。
 *
 * 纯函数、只读现有声明：不查执行档、不碰协议。执行档（底座/模型）是**每团队**的
 * 宿主本地配置，手机端取不到 —— 所以这里刻意不掺它，免得 UI 以为「任职行上能显示驱动」。
 *
 * @param agentKey 归一后的身份键（`AgentHubUi.agentKey`）。空串返回空表：没有身份就
 *   谈不上归属，「未上报类型」的实体不该被匹配到任何群。
 */
public fun agentAssignments(
    agentSlice: AgentSlice,
    settingsSlice: SettingsSlice,
    hostNodeId: String,
    agentKey: String,
): List<AgentAssignmentUi> {
    val key = agentKey.trim()
    if (key.isEmpty()) return emptyList()
    val out = mutableListOf<AgentAssignmentUi>()
    for (group in settingsSlice.settings.groups) {
        val seats = group.virtualAgents.orEmpty()
        // ① 领队身份：席位自己就是这个 agent。
        for (seat in seats) {
            if (seat.hostNodeId != hostNodeId) continue
            if (!sameAgent(seat.agentId, null, key)) continue
            out += AgentAssignmentUi(
                groupId = group.groupId,
                groupName = group.name,
                seatName = seat.name,
                isLeader = true,
                isTeam = seat.isTeam,
                teamSize = if (seat.isTeam) 1 + seat.teamMembers.size else 0,
                role = null,
                defaultWsId = seat.defaultWsId?.takeIf { it.isNotBlank() },
                defaultWsTitle = wsTitleOf(agentSlice, seat.defaultWsId),
            )
        }
        // ② 组员身份：本 agent 是某位领队的 `teamMembers` 之一。
        //    组员条目**没有 hostNodeId**（协议如此），它的宿主就是领队的宿主 ——
        //    这不是推断，是执行事实：`TeamExecution.resolve` 要求 `seat.hostNodeId == selfNodeId`，
        //    组员只能由领队所在宿主执行。所以这里按 leader 的 hostNodeId 过滤。
        for (leader in seats) {
            if (leader.hostNodeId != hostNodeId) continue
            if (leader.teamMembers.isEmpty()) continue
            // 自己就是这位领队：领队行已经在 ① 记过，别把同一个人在同一群记两遍。
            if (sameAgent(leader.agentId, null, key)) continue
            for (member in leader.teamMembers) {
                if (!sameAgent(member.agentId, null, key)) continue
                out += AgentAssignmentUi(
                    groupId = group.groupId,
                    groupName = group.name,
                    seatName = member.name,
                    isLeader = false,
                    isTeam = true,
                    teamSize = 1 + leader.teamMembers.size,
                    role = member.role.takeIf { it.isNotBlank() },
                    defaultWsId = leader.defaultWsId?.takeIf { it.isNotBlank() },
                    defaultWsTitle = wsTitleOf(agentSlice, leader.defaultWsId),
                )
            }
        }
    }
    return out.sortedWith(
        compareByDescending<AgentAssignmentUi> { it.isLeader }
            .thenBy { it.groupName }
            .thenBy { it.seatName },
    )
}

/** 席位声明的 `defaultWsId` → 已宣告目录名（与 `workspaceOfGroup` 同一张表、同一次查找）。 */
private fun wsTitleOf(agentSlice: AgentSlice, wsId: String?): String? {
    val id = wsId?.takeIf { it.isNotBlank() } ?: return null
    return agentSlice.agentWorkspaces.values.firstOrNull { it.wsId == id }?.wsTitle
}
