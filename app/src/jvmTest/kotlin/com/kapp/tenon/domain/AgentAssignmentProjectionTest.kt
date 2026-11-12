// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.WorkspaceKey
import com.kapp.tenon.domain.model.WorkspaceUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 员工**归属**投影（不变量 5 的第三个维度）的行为钉子。
 *
 * 它回答的是「这个员工在哪几个团队、以什么身份」—— `AgentHubUi.groupCount` 只数
 * **领队席位**，所以一个在 A 群当领队、B 群当组员的 agent 会被它算成 1 个群。
 * 这条差别就是本测试存在的理由。
 *
 * 反向探针：
 * - 把 ②（组员身份）那段删掉 ⇒ `oneAgentInTwoTeamsShowsBothIdentities` 只剩一行；
 * - 把「自己就是领队就别重复记」的判断删掉 ⇒ `leaderIsNotAlsoListedAsItsOwnMember` 变双行；
 * - 把 `hostNodeId` 过滤删掉 ⇒ `anotherHostsSeatIsNotMyAssignment` 变红。
 */
class AgentAssignmentProjectionTest {

    private val self = "self-node-0001"
    private val other = "host-other-0002"
    private val agent = "dsh-029bbbf3"

    private fun seat(
        agentId: String,
        hostNodeId: String = self,
        name: String,
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

    private fun declared(peerId: String, agentKey: String?, wsId: String, wsTitle: String?) =
        WorkspaceUi(
            peerId = peerId, agentKey = agentKey, wsId = wsId, wsTitle = wsTitle,
            wsPath = null, agentKind = null, online = null, sessionCount = null, at = 0L,
        )

    private fun state(
        groups: List<GroupMeta>,
        workspaces: List<WorkspaceUi> = emptyList(),
    ): RelayUiState {
        val settings = RelaySettings.firstRun().copy(nodeId = self, groups = groups)
        return RelayUiState(
            settings = settings,
            peers = emptyList(),
            agentSessions = emptyMap<AgentSessionKey, AgentSessionUi>(),
            agentWorkspaces = workspaces.associate { WorkspaceKey(it.peerId, it.agentKey, it.wsId) to it },
        )
    }

    private fun assignmentsOf(state: RelayUiState, key: String = agent) =
        agentAssignments(state.toAgentSlice(), state.toSettingsSlice(), self, key)

    @Test
    fun oneAgentInTwoTeamsShowsBothIdentities() {
        val st = state(
            groups = listOf(
                // A 群：它自己是领队。
                group(
                    "g-a", "项目协同群",
                    listOf(seat(agent, name = "墨查", teamMembers = listOf(TeamMemberInfo("m-x", "小榫", "架构")))),
                ),
                // B 群：它是别人的组员。
                group(
                    "g-b", "deepseek 群",
                    listOf(
                        seat(
                            "other-lead", name = "阿栞",
                            teamMembers = listOf(TeamMemberInfo(agent, "审查员", "审查")),
                        ),
                    ),
                ),
            ),
        )
        val rows = assignmentsOf(st)
        assertEquals(2, rows.size, "两处任职必须两行 —— groupCount 只能数出 1 个领队席位")
        val leader = rows.single { it.groupId == "g-a" }
        assertTrue(leader.isLeader, "A 群是领队身份")
        assertEquals("墨查", leader.seatName, "席位名是每团队一份的 @ token")
        val member = rows.single { it.groupId == "g-b" }
        assertFalse(member.isLeader, "B 群是组员身份")
        assertEquals("审查员", member.seatName, "组员名同样来自该群声明")
        assertEquals("审查", member.role, "组员角色来自 TeamMemberInfo")
        assertEquals(2, member.teamSize, "团队人数 = 领队 1 + 组员 1")
    }

    @Test
    fun leaderRowsCarryNoRoleBecauseTheProtocolHasNone() {
        val st = state(
            groups = listOf(
                group("g-a", "A", listOf(seat(agent, name = "墨查", teamMembers = listOf(TeamMemberInfo("m", "x", "y"))))),
            ),
        )
        val row = assignmentsOf(st).single()
        assertEquals(null, row.role, "领队席位没有 role 字段 —— 不编一个出来")
        assertTrue(row.isTeam)
        assertEquals(2, row.teamSize)
    }

    @Test
    fun leaderIsNotAlsoListedAsItsOwnMember() {
        val st = state(
            groups = listOf(
                group(
                    "g-a", "A",
                    listOf(seat(agent, name = "墨查", teamMembers = listOf(TeamMemberInfo("m-x", "小榫", "架构")))),
                ),
            ),
        )
        val rows = assignmentsOf(st)
        assertEquals(1, rows.size, "同一个人在同一群只能有一行 —— 领队不该被自己队伍再记一次")
        assertTrue(rows.single().isLeader)
    }

    @Test
    fun anotherHostsSeatIsNotMyAssignment() {
        val st = state(
            groups = listOf(group("g-a", "A", listOf(seat(agent, hostNodeId = other, name = "墨查")))),
        )
        assertTrue(
            assignmentsOf(st).isEmpty(),
            "同一个 agentId 在别的宿主上是另一台机器上的那个人 —— 不并进我的任职",
        )
    }

    @Test
    fun blankKeyHasNoAssignments() {
        val st = state(groups = listOf(group("g-a", "A", listOf(seat(agent, name = "墨查")))))
        assertTrue(assignmentsOf(st, key = "  ").isEmpty(), "没有身份键就谈不上归属（未上报类型）")
    }

    @Test
    fun declaredWorkspaceTitleIsResolvedThroughTheSameTable() {
        val st = state(
            groups = listOf(group("g-a", "A", listOf(seat(agent, name = "墨查", defaultWsId = "ws-1")))),
            workspaces = listOf(declared(self, agent, "ws-1", "teahouse-mobile")),
        )
        val row = assignmentsOf(st).single()
        assertEquals("ws-1", row.defaultWsId)
        assertEquals("teahouse-mobile", row.defaultWsTitle, "目录名走 agentWorkspaces 同一张表")
    }

    @Test
    fun undeclaredWorkspaceStaysNullInsteadOfGuessing() {
        val st = state(
            groups = listOf(group("g-a", "A", listOf(seat(agent, name = "墨查", defaultWsId = "ws-9")))),
        )
        val row = assignmentsOf(st).single()
        assertEquals("ws-9", row.defaultWsId)
        assertEquals(null, row.defaultWsTitle, "宣告没到就是 null —— 不拿 id 顶替目录名")
    }
}
