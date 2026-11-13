// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.GroupWorkspaceInfo
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.declarationFor
import com.kapp.tenon.core.protocol.withPointerProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群项目目录 → 席位执行投影的钉子（P2/D5）。
 *
 * 反向验证：
 * - 把 `rebuildSelfSeatsForPointer` 的变更检测退回「永远重建」⇒ 无变化用例红
 *   （每次广播都会白涨一个 rev）；
 * - 把投影写入退回手写 `copy(defaultWsId = …)` 并顺手重建声明 ⇒ 团队字段用例红
 *   （那是广播抹掉团队字段的老病，declarationFor/withPointerProjection 的存在理由）。
 */
class GroupSeatPointerProjectionTest {

    private fun metaOf(vararg seats: com.kapp.tenon.core.protocol.VirtualAgentMember): GroupMeta =
        GroupMeta.create(
            groupId = "g1",
            name = "proj",
            members = emptyList(),
            selfId = "self",
            updatedTs = 1_000L,
        ).let { base ->
            if (seats.isEmpty()) base else base.copy(virtualAgents = seats.toList())
        }

    private fun seatOf(
        agentId: String,
        host: String = "self",
        wsId: String? = null,
        allowed: List<GroupWorkspaceInfo> = emptyList(),
        team: Boolean = false,
    ): com.kapp.tenon.core.protocol.VirtualAgentMember = declarationFor(
        agentId = agentId,
        hostNodeId = host,
        name = "$agentId-token",
        defaultWsId = wsId,
        previous = null,
    ).let {
        if (!team) it else it.copy(
            entityType = EntityTypes.TEAM,
            teamMembers = listOf(TeamMemberInfo("w1", "coder", "worker")),
        )
    }.withPointerProjection(wsId, allowed)

    @Test
    fun `resolved pointer rewrites projection with title`() {
        val meta = metaOf(seatOf("dsh"))
        val next = rebuildSelfSeatsForPointer(
            meta, "self",
            resolveWsId = { "ws-a" },
            titleOfWs = { "teahouse" },
        )!!
        val seat = next.single()
        assertEquals("ws-a", seat.defaultWsId)
        assertEquals(listOf(GroupWorkspaceInfo("ws-a", "teahouse")), seat.allowedWorkspaces)
    }

    @Test
    fun `unresolved pointer clears projection but keeps the seat`() {
        val meta = metaOf(seatOf("dsh", wsId = "ws-a", allowed = listOf(GroupWorkspaceInfo("ws-a", "t"))))
        val next = rebuildSelfSeatsForPointer(meta, "self", resolveWsId = { null }, titleOfWs = { "" })!!
        val seat = next.single()
        assertNull(seat.defaultWsId)
        assertTrue(seat.allowedWorkspaces.isEmpty())
        // 席位还在群里：能对话，只是不可执行（不猜、不撤）。
        assertEquals("dsh-token", seat.name)
    }

    @Test
    fun `foreign seats are untouched`() {
        val foreign = seatOf("gemini", host = "other", wsId = "ws-x")
        val meta = metaOf(foreign, seatOf("dsh"))
        val next = rebuildSelfSeatsForPointer(meta, "self", resolveWsId = { "ws-a" }, titleOfWs = { "t" })!!
        assertEquals(foreign, next.first { it.agentId == "gemini" })
        assertEquals("ws-a", next.first { it.agentId == "dsh" }.defaultWsId)
    }

    @Test
    fun `no change means no broadcast`() {
        val seat = seatOf("dsh", wsId = "ws-a", allowed = listOf(GroupWorkspaceInfo("ws-a", "t")))
        val meta = metaOf(seat)
        val next = rebuildSelfSeatsForPointer(meta, "self", resolveWsId = { "ws-a" }, titleOfWs = { "t" })
        assertNull(next)
    }

    @Test
    fun `missing title falls back to empty -- not to the path`() {
        val meta = metaOf(seatOf("dsh"))
        val next = rebuildSelfSeatsForPointer(meta, "self", resolveWsId = { "ws-a" }, titleOfWs = { null })!!
        assertEquals(listOf(GroupWorkspaceInfo("ws-a", "")), next.single().allowedWorkspaces)
    }

    @Test
    fun `projection rewrite never touches name or team fields`() {
        val teamSeat = seatOf("dsh", team = true, wsId = "ws-old", allowed = listOf(GroupWorkspaceInfo("ws-old", "old")))
        val next = rebuildSelfSeatsForPointer(
            metaOf(teamSeat), "self",
            resolveWsId = { "ws-a" }, titleOfWs = { "teahouse" },
        )!!
        val seat = next.single()
        assertEquals(EntityTypes.TEAM, seat.entityType)
        assertEquals(1, seat.teamMembers.size)
        assertEquals("dsh-token", seat.name)
        assertEquals("ws-a", seat.defaultWsId)
    }
}
