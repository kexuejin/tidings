// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.WorkspaceKey
import com.kapp.tenon.domain.model.WorkspaceUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 团队视图投影的行为钉子（不变量 5 的「团队」维）。
 *
 * 它回答的是「这个团队有谁、各自在本团队里叫什么、本群还有几件待批」。
 *
 * ## 反向探针（每条判据都必须能区分对错）
 *
 * - 团队键去掉 `leaderAgentId`（只按 `groupId` 归并）⇒
 *   `twoTeamsInOneGroupStayTwoCards` 会合成一张、成员表混在一起；
 * - `pendingCountByGroup` 改成数「成员名下待批合计」⇒
 *   `pendingIsCountedPerGroupNotPerMember` 变红（另一群的待批被算进来）；
 * - 领队不进成员表 ⇒ `leaderComesFirstInTheMemberList` 变红；
 * - 单兵席位放行 ⇒ `soloSeatIsNotATeam` 变红。
 */
class AgentTeamProjectionTest {

    private val self = "self-node-0001"
    private val other = "host-other-0002"
    private val now = 1_700_000_000_000L

    private fun seat(
        agentId: String,
        name: String,
        hostNodeId: String = self,
        defaultWsId: String? = null,
        teamMembers: List<TeamMemberInfo> = emptyList(),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = hostNodeId,
        name = name,
        entityType = if (teamMembers.isEmpty()) EntityTypes.SINGLE else EntityTypes.TEAM,
        teamMembers = teamMembers,
        defaultWsId = defaultWsId,
    )

    private fun group(groupId: String, name: String, seats: List<VirtualAgentMember>) =
        GroupMeta.create(groupId, name, members = emptyList(), selfId = self, updatedTs = 1L)
            .copy(virtualAgents = seats)

    private fun session(peerId: String, sessionId: String, wsId: String?, at: Long) = AgentSessionUi(
        peerId = peerId,
        sessionId = sessionId,
        agentId = "dsh",
        wsId = wsId,
        updatedAt = at,
    )

    private fun approval(id: String, peerId: String, sessionId: String) =
        AgentApprovalUi(approvalId = id, sessionId = sessionId, peerId = peerId)

    private fun state(
        groups: List<GroupMeta>,
        sessions: List<AgentSessionUi> = emptyList(),
        approvals: List<AgentApprovalUi> = emptyList(),
        workspaces: List<WorkspaceUi> = emptyList(),
    ): RelayUiState {
        val settings = RelaySettings.firstRun().copy(nodeId = self, groups = groups)
        return RelayUiState(
            settings = settings,
            peers = emptyList(),
            agentSessions = sessions.associateBy { AgentSessionKey(it.peerId, it.sessionId) },
            agentApprovals = approvals.associateBy { it.approvalId },
            agentWorkspaces = workspaces.associate { WorkspaceKey(it.peerId, it.agentKey, it.wsId) to it },
        )
    }

    private fun teamsOf(state: RelayUiState) =
        agentTeams(state.toAgentSlice(), state.toNodeSlice(), state.toSettingsSlice(), now)

    @Test
    fun leaderComesFirstInTheMemberList() {
        val teams = teamsOf(
            state(
                groups = listOf(
                    group(
                        "g1", "项目协同群",
                        listOf(
                            seat(
                                "dsh-a", "墨查",
                                teamMembers = listOf(
                                    TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "架构领队"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, teams.size)
        val team = teams.single()
        assertEquals(2, team.teamSize)
        assertEquals("墨查", team.leaderName)
        assertTrue(team.members.first().isLeader, "领队必须在成员表第一行")
        assertEquals("小榫", team.members[1].name)
        assertEquals("架构领队", team.members[1].role)
        assertFalse(team.members[1].isLeader)
    }

    @Test
    fun soloSeatIsNotATeam() {
        val teams = teamsOf(
            state(
                groups = listOf(
                    group("g1", "项目协同群", listOf(seat("dsh-a", "独狼"))),
                ),
            ),
        )
        assertEquals(emptyList(), teams, "单兵席位没有组员，不是团队 —— 团队视图不该给它一张卡")
    }

    @Test
    fun twoTeamsInOneGroupStayTwoCards() {
        val teams = teamsOf(
            state(
                groups = listOf(
                    group(
                        "g1", "大群",
                        listOf(
                            seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "榫一", role = "开发"))),
                            seat("dsh-c", "旁白", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-d", name = "榫二", role = "审查"))),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, teams.size, "一个群两支队伍 = 两张卡；按 groupId 归并会把它们挤成一张")
        assertEquals(setOf("dsh-a", "dsh-c"), teams.map { it.leaderAgentId }.toSet())
    }

    @Test
    fun pendingIsCountedPerGroupNotPerMember() {
        // 同一个领队在两个群都有席位；A 群有一件待批，B 群没有。
        val a = seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")))
        val b = seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")))
        val st = state(
            groups = listOf(group("g1", "A 群", listOf(a)), group("g2", "B 群", listOf(b))),
            sessions = listOf(session(self, "s1", "ws-1", now - 1000)),
            approvals = listOf(approval("ap1", self, "s1")),
            workspaces = listOf(
                WorkspaceUi(
                    peerId = self, agentKey = "dsh", wsId = "ws-1", wsTitle = "仓库",
                    wsPath = null, agentKind = null, online = null, sessionCount = null, at = 0L,
                ),
            ),
        )
        // ws-1 绑给 A 群（席位声明的 defaultWsId）。
        val bound = st.copy(
            settings = st.settings.copy(
                groups = listOf(
                    a.copy(defaultWsId = "ws-1").let { group("g1", "A 群", listOf(it)) },
                    group("g2", "B 群", listOf(b)),
                ),
            ),
        )
        val teams = teamsOf(bound).associateBy { it.groupName }
        assertEquals(1, teams.getValue("A 群").pendingCount, "本群那一件要数进来")
        assertEquals(0, teams.getValue("B 群").pendingCount, "另一个群的待批不许算到本群头上")
    }

    @Test
    fun offlineLeaderHostIsReportedAsOffline() {
        val teams = teamsOf(
            state(
                groups = listOf(
                    group(
                        "g1", "远端群",
                        listOf(
                            seat(
                                "dsh-remote", "远程领队", hostNodeId = other,
                                teamMembers = listOf(TeamMemberInfo(agentId = "dsh-x", name = "组员", role = "开发")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val team = teams.single()
        assertFalse(team.online, "别的宿主不在节点表里 = 离线，不许当成「空闲」或「在线」")
    }

    @Test
    fun teamsWithMorePendingComeFirst() {
        val quiet = seat("dsh-a", "安静队", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "甲", role = "开发")))
        val noisy = seat("dsh-c", "忙碌队", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-d", name = "乙", role = "开发")))
        val st = state(
            groups = listOf(
                group("g1", "安静群", listOf(quiet.copy(defaultWsId = "ws-q"))),
                group("g2", "忙碌群", listOf(noisy.copy(defaultWsId = "ws-n"))),
            ),
            sessions = listOf(
                session(self, "s-q", "ws-q", now - 1000),
                session(self, "s-n1", "ws-n", now - 1000),
                session(self, "s-n2", "ws-n", now - 1000),
            ),
            approvals = listOf(approval("ap1", self, "s-n1"), approval("ap2", self, "s-n2")),
        )
        val teams = teamsOf(st)
        assertEquals("忙碌群", teams.first().groupName, "待批多的团队排前面（Urgent-First 换归因单位，不换口径）")
    }

    @Test
    fun unattributedApprovalIsNotChargedToAnyTeam() {
        // 会话没有 wsId（直通宿主私聊）：审批不属于任何团队。把它算给「第一个群」
        // 就是编造归属 —— 与事件路由 no-conv-bound 同一条纪律。
        val st = state(
            groups = listOf(
                group(
                    "g1", "唯一的群",
                    listOf(seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")))),
                ),
            ),
            sessions = listOf(session(self, "s-chat", null, now - 1000)),
            approvals = listOf(approval("ap1", self, "s-chat")),
        )
        assertEquals(0, teamsOf(st).single().pendingCount, "归因不上的审批不许落到任何团队卡上")
    }

    @Test
    fun lastActivityTakesTheNewestAmongMembers() {
        val st = state(
            groups = listOf(
                group(
                    "g1", "项目协同群",
                    listOf(
                        seat(
                            "dsh-a", "墨查",
                            teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")),
                        ),
                    ),
                ),
            ),
            sessions = listOf(
                session(self, "s-old", null, now - 90_000),
                session(self, "s-new", null, now - 5_000).copy(agentId = "dsh-b"),
            ),
        )
        assertEquals(now - 5_000, teamsOf(st).single().lastActivityAt, "成员里最新的那一份才是团队的活动时刻")
    }
}
