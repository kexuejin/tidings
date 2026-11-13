// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 执行配置矩阵投影（规范 §4.0）的行为钉子。
 *
 * 它回答的是「每名员工由哪个底座执行、用哪个模型、这一行到底生效的是哪份配置」。
 *
 * ## 反向探针
 *
 * - 去掉 `seat.hostNodeId != selfNodeId` 的过滤 ⇒ `anotherHostsSeatIsNotInMyMatrix` 变红；
 * - `installed` 改成「driverKey 非空即为在位」⇒ `unassignedIsNotInstalled` 变红；
 * - `overridden` 恒为 false ⇒ `savedProfileIsMarkedAsOverridden` 变红；
 * - 单兵席位的初值不再生成 worker ⇒ `soloSeatStillHasOneRow` 变红。
 */
class ExecutionMatrixProjectionTest {

    private val self = "self-node-0001"
    private val other = "host-other-0002"

    private fun seat(
        agentId: String,
        name: String,
        hostNodeId: String = self,
        teamMembers: List<TeamMemberInfo> = emptyList(),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = hostNodeId,
        name = name,
        entityType = if (teamMembers.isEmpty()) EntityTypes.SINGLE else EntityTypes.TEAM,
        teamMembers = teamMembers,
    )

    private fun group(id: String, name: String, seats: List<VirtualAgentMember>) =
        GroupMeta.create(id, name, members = emptyList(), selfId = self, updatedTs = 1L)
            .copy(virtualAgents = seats)

    /** 假端口：只提供本测试需要的事实，保存路径不参与（这里是**读**的投影）。 */
    private class FakePort(
        val catalog: Set<String>,
        val pointers: Map<String, String>,
        val saved: Map<Pair<String, String>, TeamExecutionProfileUi> = emptyMap(),
    ) : TeamExecutionPort {
        override fun driverCatalog(): Set<String> = catalog
        override fun profileOf(dirId: String, leaderAgentId: String) =
            saved[dirId to leaderAgentId]
        override fun validate(profile: TeamExecutionProfileUi, declaredMemberIds: Set<String>) = emptyList<String>()
        override fun save(profile: TeamExecutionProfileUi, declaredMemberIds: Set<String>) =
            TeamExecutionSaveResult.Saved
        override fun groupPointerDirId(groupId: String): String? = pointers[groupId]
    }

    private fun rows(
        groups: List<GroupMeta>,
        catalog: Set<String> = setOf("claude", "dsh"),
        pointers: Map<String, String> = groups.associate { it.groupId to ("dir-" + it.groupId) },
        saved: Map<Pair<String, String>, TeamExecutionProfileUi> = emptyMap(),
    ) = executionMatrixRows(groups, self, FakePort(catalog, pointers, saved))

    @Test
    fun oneEmployeeInTwoTeamsTakesOneRowPerTeam() {
        val teamSeat = seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")))
        val rows = rows(
            listOf(
                group("g1", "A 群", listOf(teamSeat)),
                group("g2", "B 群", listOf(teamSeat)),
            ),
        )
        // 行是「团队 × 员工」：同一个人在 A、B 两个团队各一行 —— 这正是「只在本团队换底座」
        // 能成为一次本地操作的原因。按员工去重会让 B 群那行消失。
        assertEquals(2, rows.size)
        assertEquals(setOf("A 群", "B 群"), rows.map { it.groupName }.toSet())
        assertEquals(listOf("小榫", "小榫"), rows.map { it.memberName })
    }

    @Test
    fun soloSeatStillHasOneRow() {
        val rows = rows(listOf(group("g1", "项目协同群", listOf(seat("dsh-a", "独狼")))))
        assertEquals(1, rows.size, "单兵也必须有执行层（不变量 5），否则换底座 = 换席位")
        assertEquals("独狼", rows.single().memberName)
        assertEquals("", rows.single().driverKey, "没配过就是空串（未指派），不许猜一个默认底座")
    }

    @Test
    fun anotherHostsSeatIsNotInMyMatrix() {
        val rows = rows(
            listOf(
                group(
                    "g1", "远端群",
                    listOf(seat("dsh-remote", "远程", hostNodeId = other)),
                ),
            ),
        )
        assertEquals(emptyList(), rows, "别的宿主上的席位本机读不到也写不了，画进来就是骗人")
    }

    @Test
    fun unassignedIsNotInstalled() {
        val rows = rows(listOf(group("g1", "群", listOf(seat("dsh-a", "独狼")))))
        assertFalse(rows.single().installed, "空 driverKey 是「未指派」，不是「在位」")
    }

    @Test
    fun unknownDriverIsNotInstalled() {
        val group = group("g1", "群", listOf(seat("dsh-a", "独狼")))
        val saved = mapOf(
            ("dir-g1" to "dsh-a") to TeamExecutionProfileUi(
                dirId = "dir-g1", leaderAgentId = "dsh-a",
                members = listOf(TeamExecutionMemberUi(agentId = "dsh-a", kind = TeamExecutionKindsUi.WORKER, driverKey = "codex")),
            ),
        )
        val rows = rows(listOf(group), catalog = setOf("claude"), saved = saved)
        assertFalse(rows.single().installed, "配过但当前不在位的底座要如实报「不在位」")
        assertEquals("codex", rows.single().driverKey)
    }

    @Test
    fun savedProfileIsMarkedAsOverridden() {
        val group = group("g1", "群", listOf(seat("dsh-a", "独狼")))
        val baseline = rows(listOf(group))
        assertFalse(baseline.single().overridden, "没落过盘的行是推导值，必须标成「继承」")

        val saved = mapOf(
            ("dir-g1" to "dsh-a") to TeamExecutionProfileUi(
                dirId = "dir-g1", leaderAgentId = "dsh-a",
                members = listOf(
                    TeamExecutionMemberUi(
                        agentId = "dsh-a",
                        kind = TeamExecutionKindsUi.WORKER,
                        driverKey = "claude",
                        models = listOf("opus-4-1"),
                    ),
                ),
            ),
        )
        val overridden = rows(listOf(group), saved = saved).single()
        assertTrue(overridden.overridden, "本团队落过盘就要标「本团队覆盖」")
        assertEquals("claude", overridden.driverKey)
        assertEquals("opus-4-1", overridden.model)
        assertTrue(overridden.installed)
    }

    @Test
    fun groupWithoutDirectoryPointerIsReadOnly() {
        val rows = rows(
            listOf(group("g1", "群", listOf(seat("dsh-a", "独狼")))),
            pointers = emptyMap(),
        )
        assertFalse(rows.single().editable, "执行档主键的一半是目录指针；解析不出来就只能只读")
    }

    @Test
    fun rowsAreGroupedByTeamWithoutReordering() {
        val rows = rows(
            listOf(
                group(
                    "g1", "A 群",
                    listOf(
                        seat(
                            "dsh-a", "甲",
                            teamMembers = listOf(
                                TeamMemberInfo(agentId = "dsh-b", name = "乙", role = "审查"),
                                TeamMemberInfo(agentId = "dsh-c2", name = "丙", role = "开发"),
                            ),
                        ),
                    ),
                ),
                group("g2", "B 群", listOf(seat("dsh-c", "丙"))),
            ),
        )
        val byGroup = executionMatrixByGroup(rows)
        assertEquals(listOf("g1", "g2"), byGroup.map { it.first })
        assertEquals(2, byGroup[0].second.size)
        assertEquals(1, byGroup[1].second.size)
    }
}
