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
 * D18：**`@` 候选给什么，匹配器就必须认什么** —— 两者同源于这一张成员表。
 *
 * 席位入驻后，候选 token 是 `virtualAgents[].name`（`dsh-<host>-<workspace>`），
 * 身份档的 kind 是席位 agentId（`dsh-<host>`），宿主自报的 kind 是裸 `dsh`。
 * 曾经只把其中一个交给匹配器，于是手打 `@dsh`、点候选全名各有一次「@ 了没反应」。
 */
class GroupMentionKeySourceTest {

    private val host = "11111111-1111-4111-8111-111111111111"

    private fun seatContext(): SenderIdentityContext = SenderIdentityContext(
        selfNodeId = host,
        group = GroupMeta(
            groupId = "g-seat",
            name = "协同群",
            members = listOf(host),
            rev = 2,
            updatedBy = host,
            updatedTs = 0L,
            virtualAgents = listOf(
                VirtualAgentMember(
                    agentId = "dsh-a030882e",
                    hostNodeId = host,
                    name = "dsh-a030882e-default-workspace",
                    defaultWsId = "ws-1",
                ),
            ),
        ),
        agentKindsByHost = mapOf(host to setOf("dsh")),
    )

    @Test
    fun everyCandidateTokenAndTheBareKindAreMatchable() {
        val rows = groupParticipantRows(seatContext())
        val agent = rows.agents.single()
        assertEquals("@dsh-a030882e-default-workspace", agent.mentionToken)
        assertEquals(
            setOf("dsh-a030882e", "dsh-a030882e-default-workspace", "dsh"),
            rows.mentionKeys,
        )
        val keys = agentKindsInGroup(seatContext())
        assertTrue(RelayController.isMentioningAgent("${agent.mentionToken} create probe.txt", keys))
        assertTrue(RelayController.isMentioningAgent("@dsh create probe.txt", keys))
        assertTrue(RelayController.isMentioningAgent("@DSH create probe.txt", keys))
        assertFalse(RelayController.isMentioningAgent("@dshx create probe.txt", keys), "词边界不许被前缀归位放宽")
    }

    @Test
    fun aGroupWithoutDeclaredAgentsHasNothingToMention() {
        val ctx = seatContext().copy(group = seatContext().group!!.copy(virtualAgents = emptyList()))
        val rows = groupParticipantRows(ctx)
        assertTrue(rows.agents.isEmpty())
        assertTrue(rows.mentionKeys.isEmpty(), "没有入驻声明就不编可点名对象")
    }

    @Test
    fun theAddressedKeySurvivesTheStrip() {
        val keys = agentKindsInGroup(seatContext())
        val raw = "@dsh-a030882e-default-workspace create probe.txt"
        val mentioned = RelayController.mentionedAgentKeys(raw, keys).firstOrNull()
        assertEquals("dsh", mentioned, "点名事实取最短的档案键：执行侧据此直接找得到驱动")
        assertEquals("", RelayController.extractAgentPrompt(raw, keys).substringBefore("create").trim())
    }

    /** 团队执行方案 P0-0.1：**组员名进 token 池** —— `@coder` 必须与候选同源可匹配。 */
    @Test
    fun teamRosterNamesJoinThePoolAndStayMatchable() {
        val teamSeat = VirtualAgentMember(
            agentId = "dsh-a030882e",
            hostNodeId = host,
            name = "dsh-a030882e-default-workspace",
            entityType = EntityTypes.TEAM,
            teamMembers = listOf(
                TeamMemberInfo("m-coder", "Coder", "编码"),
                TeamMemberInfo("m-reviewer", "reviewer", "审查"),
            ),
            defaultWsId = "ws-1",
        )
        val ctx = seatContext().copy(
            group = seatContext().group!!.copy(
                virtualAgents = listOf(teamSeat),
            ),
        )
        val rows = groupParticipantRows(ctx)
        // 池内归一小写（与其它 token 同一条纪律）；组员显示名是大写也照样进池。
        assertTrue(
            "coder" in rows.mentionKeys && "reviewer" in rows.mentionKeys,
            "组员名必须进可 @ 池，否则手打 @coder 永远不触发（「@ 了没反应」的回归形态）：" +
                rows.mentionKeys,
        )
        val keys = agentKindsInGroup(ctx)
        assertTrue(RelayController.isMentioningAgent("@coder 把登录修了", keys))
        assertTrue(RelayController.isMentioningAgent("@REVIEWER 审一下", keys), "大小写不敏感")
        // 组员不占席位：agent 候选行仍然只有席位本身，组员只在池里（对外单一窗口 §5.5）。
        assertEquals(1, rows.agents.size, "组员不是 agent 候选行 —— 它们不能被单独派单/开会话")
        assertEquals("@dsh-a030882e-default-workspace", rows.agents.single().mentionToken)
    }

    /** 单兵席位（无 roster）不给池里添组员 token：没有组员就没有团队 @ 语义。 */
    @Test
    fun aSingleSeatWithoutARosterAddsNoMemberTokens() {
        val existing = seatContext().group!!.virtualAgents.orEmpty().single()
        val ctx = seatContext().copy(
            group = seatContext().group!!.copy(
                virtualAgents = listOf(
                    existing.copy(
                        entityType = EntityTypes.SINGLE,
                        teamMembers = emptyList(),
                    ),
                ),
            ),
        )
        val rows = groupParticipantRows(ctx)
        assertTrue(
            rows.mentionKeys.none { it in setOf("coder", "reviewer") },
            "单兵席位不产生组员 token：${rows.mentionKeys}",
        )
    }
}
