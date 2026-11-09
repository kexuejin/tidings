// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentSessionStates
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.AgentSessionUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 团队清单的域层钉子（阶段 1 数据侧）。
 *
 * 锁四件事：
 * 1. 团队判定用 `isTeam`（`teamMembers` 非空也是团队，`entityType` 只是声明）；
 * 2. 会话键只能是 `group:<groupId>` —— 造 `team-*` 会被判私聊、静默无帧；
 * 3. 活跃态按「同宿主 + 归一大小的 agentKey」匹配 —— 跨宿主同名 agent 不许互相点亮；
 * 4. 没有会话 = Idle（不是"未知"，也不保留上一次的 Running）。
 */
class TeamFoldersTest {

    private fun group(
        groupId: String = "g1",
        agents: List<VirtualAgentMember> = emptyList(),
    ) = GroupMeta(
        groupId = groupId,
        name = "群 " + groupId,
        members = emptyList(),
        rev = 1,
        updatedBy = "me",
        updatedTs = 0L,
        virtualAgents = agents,
    )

    private fun team(
        agentId: String = "dsh",
        hostNodeId: String = "host-a",
        name: String = "devops 团队",
        entityType: String = "team",
        members: List<TeamMemberInfo> = listOf(TeamMemberInfo("coder", "coder", "Engineer")),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = hostNodeId,
        name = name,
        entityType = entityType,
        teamMembers = members,
    )

    private fun session(
        peerId: String = "host-a",
        agentId: String? = null,
        agentKind: String? = "dsh",
        state: String = AgentSessionStates.BUSY,
    ) = AgentSessionUi(
        peerId = peerId,
        sessionId = "s-" + peerId + "-" + (agentId ?: agentKind),
        agentId = agentId,
        agentKind = agentKind,
        state = state,
    )

    @Test
    fun emptyGroupsProduceEmptyMap() {
        assertTrue(teamFoldersByConv(emptyList(), listOf(session())).isEmpty())
    }

    @Test
    fun singleAgentsAreNotTeams() {
        val groups = listOf(group(agents = listOf(team(entityType = "single", members = emptyList()))))
        assertTrue(teamFoldersByConv(groups, emptyList()).isEmpty())
    }

    @Test
    fun membersListAloneMakesItATeam() {
        // entityType 只是声明；组员清单是事实（旧包可能只填后者）——与 ChatScreen 同口径。
        val groups = listOf(group(agents = listOf(team(entityType = "single"))))
        val folders = teamFoldersByConv(groups, emptyList())[ConvId.group("g1")]
        assertEquals(1, folders?.size)
        assertEquals("host-a:dsh", folders?.first()?.pluginId, "组标识宿主限定（host:agentId）")
        assertEquals(1, folders?.first()?.members?.size)
    }

    @Test
    fun convKeyIsTheGroupKeyNotATeamKey() {
        val groups = listOf(group(agents = listOf(team())))
        val map = teamFoldersByConv(groups, emptyList())
        assertEquals(setOf("group:g1"), map.keys)
        // 反向钉子：team-* 会被 ConvId 判成私聊，发送侧找不到对端。
        assertFalse(ConvId.isGroup("team-dsh"))
        assertNull(ConvId.groupIdOf("team-dsh"))
    }

    @Test
    fun busySessionOnTheSameHostTurnsItRunning() {
        val groups = listOf(group(agents = listOf(team())))
        val folders = teamFoldersByConv(groups, listOf(session(agentKind = "dsh")))
        assertTrue(folders.getValue(ConvId.group("g1")).first().isRunning)
    }

    @Test
    fun noSessionMeansIdle() {
        val groups = listOf(group(agents = listOf(team())))
        assertFalse(teamFoldersByConv(groups, emptyList()).getValue(ConvId.group("g1")).first().isRunning)
    }

    @Test
    fun idleSessionMeansIdle() {
        val groups = listOf(group(agents = listOf(team())))
        val folders = teamFoldersByConv(
            groups,
            listOf(session(state = AgentSessionStates.IDLE)),
        )
        assertFalse(folders.getValue(ConvId.group("g1")).first().isRunning)
    }

    @Test
    fun agentKindFallbackMatchesWhenAgentIdIsMissing() {
        val groups = listOf(group(agents = listOf(team(agentId = "dsh"))))
        // 只报 agentKind 的对端也要点亮 —— agentKey 口径是 agentId ?: agentKind。
        val folders = teamFoldersByConv(groups, listOf(session(agentId = null, agentKind = "dsh")))
        assertTrue(folders.getValue(ConvId.group("g1")).first().isRunning)
    }

    @Test
    fun agentKeyMatchIsCaseInsensitive() {
        val groups = listOf(group(agents = listOf(team(agentId = "DSH"))))
        val folders = teamFoldersByConv(groups, listOf(session(agentId = "dsh", agentKind = null)))
        assertTrue(folders.getValue(ConvId.group("g1")).first().isRunning)
    }

    @Test
    fun sameAgentNameOnAnotherHostDoesNotTurnItRunning() {
        val groups = listOf(group(agents = listOf(team(hostNodeId = "host-a"))))
        // 同一 agentKey、不同宿主：不能互相点亮（否则 A 的忙碌显示在 B 上）。
        val folders = teamFoldersByConv(groups, listOf(session(peerId = "host-b", agentKind = "dsh")))
        assertFalse(folders.getValue(ConvId.group("g1")).first().isRunning)
    }

    @Test
    fun multipleTeamsInOneGroupKeepDeclarationOrder() {
        val groups = listOf(
            group(
                agents = listOf(
                    team(agentId = "alpha", name = "A 团队"),
                    team(agentId = "beta", name = "B 团队"),
                ),
            ),
        )
        val folders = teamFoldersByConv(groups, emptyList()).getValue(ConvId.group("g1"))
        assertEquals(listOf("host-a:alpha", "host-a:beta"), folders.map { it.pluginId }, "组标识宿主限定（host:agentId）")
        assertEquals(listOf("A 团队", "B 团队"), folders.map { it.title })
    }

    /**
     * P0-0.4：两台宿主各挂一个**同名** agent（或同 agent 的两个团队席位）时，
     * 裸 agentId 的组标识相撞 → 折叠投影行 key 相撞 → LazyColumn 直接崩。
     * 宿主限定后逐条唯一。
     */
    @Test
    fun sameAgentIdOnDifferentHostsProduceDistinctPluginIds() {
        val groups = listOf(
            group(
                agents = listOf(
                    team(agentId = "dsh", hostNodeId = "host-a", name = "A 的团队"),
                    team(agentId = "dsh", hostNodeId = "host-b", name = "B 的团队"),
                ),
            ),
        )
        val folders = teamFoldersByConv(groups, emptyList()).getValue(ConvId.group("g1"))
        assertEquals(
            listOf("host-a:dsh", "host-b:dsh"),
            folders.map { it.pluginId },
            "跨宿主同 agentId 不得共用组标识 —— 折叠投影拿它拼行 key",
        )
    }
}
