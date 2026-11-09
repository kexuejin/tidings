// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 声明构造收口（`declarationFor`）与值域单一定义（`EntityTypes`）的行为钉子。
 *
 * 最要紧的一条是 F1 的修法本身：**重建席位必须把团队字段带回来**。
 * 广播前的重建丢掉 `entityType` / `teamMembers`，症状是「本机落库有团队、
 * 发出去的帧没有」—— group 帧不拒收、不报错，两端从此各说各话。
 */
class DeclarationForTest {

    private val self = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"

    @Test
    fun aRebuiltSeatKeepsTeamFieldsFromThePreviousDeclaration() {
        val previous = VirtualAgentMember(
            agentId = "dsh",
            hostNodeId = self,
            name = "dsh-teahouse-mobile",
            entityType = EntityTypes.TEAM,
            teamMembers = listOf(
                TeamMemberInfo(agentId = "coder", name = "coder", role = "编码"),
                TeamMemberInfo(agentId = "reviewer", name = "reviewer", role = "审查"),
            ),
            allowedWorkspaces = listOf(GroupWorkspaceInfo(wsId = "ws-1", title = "项目")),
            defaultWsId = "ws-1",
        )
        val rebuilt = declarationFor(
            agentId = "dsh",
            hostNodeId = self,
            name = "dsh-teahouse-mobile",
            defaultWsId = "ws-1",
            previous = previous,
        )
        assertEquals(EntityTypes.TEAM, rebuilt.entityType, "entityType 必须从 previous 原样带回")
        assertEquals(previous.teamMembers, rebuilt.teamMembers, "组员清单必须从 previous 原样带回")
        assertEquals(previous.allowedWorkspaces, rebuilt.allowedWorkspaces, "白名单也一并带回（同一类丢失）")
    }

    @Test
    fun aNewSeatStartsAsSingleWithEmptyRoster() {
        val seat = declarationFor(
            agentId = "dsh",
            hostNodeId = self,
            name = "dsh-teahouse-mobile",
            defaultWsId = "ws-1",
            previous = null,
        )
        assertEquals(EntityTypes.SINGLE, seat.entityType)
        assertTrue(seat.teamMembers.isEmpty())
        assertTrue(seat.allowedWorkspaces.isEmpty())
    }

    /** 身份字段是调用方给的：declarationFor 只补默认值，不替调用方算身份。 */
    @Test
    fun identityFieldsComeFromTheArguments() {
        val seat = declarationFor("dsh", self, "名字", "ws-9", null)
        assertEquals("dsh", seat.agentId)
        assertEquals(self, seat.hostNodeId)
        assertEquals("名字", seat.name)
        assertEquals("ws-9", seat.defaultWsId)
    }

    // ------------------------------------------------------------------ EntityTypes

    @Test
    fun normalizedKeepsKnownValuesAndCollapsesUnknownOnesToSingle() {
        assertEquals(EntityTypes.SINGLE, EntityTypes.normalized(EntityTypes.SINGLE))
        assertEquals(EntityTypes.TEAM, EntityTypes.normalized(EntityTypes.TEAM))
        // 未知值不拒帧（前向兼容优先），消费方归一到单兵。
        assertEquals(EntityTypes.SINGLE, EntityTypes.normalized("swarm"))
        assertEquals(EntityTypes.SINGLE, EntityTypes.normalized(""))
        assertEquals(EntityTypes.SINGLE, EntityTypes.normalized(null))
    }

    /** 值域只有两个值：发送侧不产出第三个 —— 这是钉住「本端产出」的那条。 */
    @Test
    fun theValueDomainHasExactlyTwoValues() {
        assertEquals(setOf("single", "team"), EntityTypes.ALL)
    }
}
