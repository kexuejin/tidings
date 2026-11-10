// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.resolveTeamMention
import com.kapp.tenon.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 团队执行（v2）路由层判据钉子（方案 §4.2/§4.5）：
 *
 * 1. **token 单源**：组员名进 mentionKeys 后，候选/匹配/解析三者同源；
 * 2. **目标解析**：`resolvePromptTarget` 的 Seat/Member/Ambiguous/None 四档矩阵；
 * 3. **目标宿主路由**（§4.5）：`agentPromptRoute` 按 `targetHostIsSelf` 判 Local/Remote
 *    —— 这是行为变更（多 agent 宿主群里文本 @ 从"互相让路"变成"目标宿主执行"）。
 */
class TeamPromptRoutingTest {

    private val self = "host-self"
    private val other = "host-other"

    private fun teamSeat(
        host: String = self,
        agentId: String = "dsh-lead",
        name: String = "lead-$host",
        members: List<TeamMemberInfo> = listOf(
            TeamMemberInfo("m-coder", "coder", "编码"),
            TeamMemberInfo("m-reviewer", "reviewer", "审查"),
        ),
    ) = VirtualAgentMember(
        agentId = agentId, hostNodeId = host, name = name,
        entityType = EntityTypes.TEAM, teamMembers = members, defaultWsId = "ws-1",
    )

    private fun group(vararg seats: VirtualAgentMember) = GroupMeta(
        groupId = "g1", name = "n", members = listOf(self, other), rev = 1,
        updatedBy = self, updatedTs = 1, virtualAgents = seats.toList(),
    )

    private fun ctx(vararg seats: VirtualAgentMember): SenderIdentityContext =
        SenderIdentityContext(selfNodeId = self, group = group(*seats))

    // ------------------------------------------------------------ token 单源

    @Test
    fun memberNamesJoinTheMentionPool() {
        val rows = groupParticipantRows(ctx(teamSeat()))
        assertTrue(
            "coder" in rows.mentionKeys && "reviewer" in rows.mentionKeys,
            "组员名必须进 token 池（候选=匹配=解析 同一份）：" + rows.mentionKeys,
        )
        assertTrue("dsh-lead" in rows.mentionKeys, "席位 agentId 仍在池里")
    }

    // ------------------------------------------------------------ 目标解析四档

    @Test
    fun memberMentionResolvesToTheMemberTarget() {
        val target = resolvePromptTarget("@coder 把登录修了", group(teamSeat()), groupParticipantRows(ctx(teamSeat())).mentionKeys)
        val member = assertIs<PromptTarget.Member>(target)
        assertEquals("m-coder", member.member.agentId)
        assertEquals(self, member.hostNodeId)
    }

    @Test
    fun seatMentionResolvesToTheSeatTarget() {
        val target = resolvePromptTarget("@dsh-lead 顺手看下", group(teamSeat()), groupParticipantRows(ctx(teamSeat())).mentionKeys)
        assertIs<PromptTarget.Seat>(target)
    }

    @Test
    fun twoMemberMentionsAreAmbiguous() {
        val target = resolvePromptTarget(
            "@coder 把登录修了，@reviewer 审查",
            group(teamSeat()), groupParticipantRows(ctx(teamSeat())).mentionKeys,
        )
        assertIs<PromptTarget.Ambiguous>(target, "两个组员同帧 = 歧义，不执行（§4.2）")
    }

    @Test
    fun seatAndMemberInTheSameMessageAreAmbiguous() {
        val target = resolvePromptTarget(
            "@dsh-lead 修登录 @coder 审查",
            group(teamSeat()), groupParticipantRows(ctx(teamSeat())).mentionKeys,
        )
        assertIs<PromptTarget.Ambiguous>(target, "席位+组员同帧 = 歧义，不执行")
    }

    @Test
    fun crossSeatDuplicateTokensAreAmbiguous() {
        val seats = arrayOf(teamSeat(agentId = "lead-a"), teamSeat(host = other, agentId = "lead-b"))
        val g = group(*seats)
        val target = resolvePromptTarget("@coder fix", g, groupParticipantRows(ctx(*seats)).mentionKeys)
        assertIs<PromptTarget.Ambiguous>(target, "跨席位重名 fail-closed")
    }

    @Test
    fun noMemberAndNoSeatIsNone() {
        val g = group(teamSeat())
        val target = resolvePromptTarget("把登录修了", g, groupParticipantRows(ctx(teamSeat())).mentionKeys)
        assertIs<PromptTarget.None>(target, "没点名 = None")
        assertEquals(PromptTarget.None, resolvePromptTarget("@coder", null, setOf("coder")), "1:1 没有团队语义")
        // 无组员清单的席位（真单兵）：组员解析不出目标（core 钉子同一条判据）。
        val bare = VirtualAgentMember(agentId = "bare", hostNodeId = self, name = "bare-seat")
        assertNull(group(bare).resolveTeamMention("@bare-seat 干活"))
    }

    // ------------------------------------------------------------ 目标宿主路由（§4.5 五档）

    private fun route(
        targetHostIsSelf: Boolean?,
        localAgentPresent: Boolean,
        remoteAgentPresent: Boolean,
    ) = RelayController.agentPromptRoute(
        text = "@coder fix it",
        groupId = "g1",
        isWorkspaceGroup = true,
        repliedToIsAgent = false,
        localAgentPresent = localAgentPresent,
        remoteAgentPresent = remoteAgentPresent,
        agentKinds = setOf("coder", "dsh-lead"),
        targetHostIsSelf = targetHostIsSelf,
    )

    /** 单宿主：目标=自己 ⇒ Local（解析不出目标时回落既有判据）。 */
    @Test
    fun singleHostLegacyBehaviorIsPreserved() {
        assertEquals(AgentPromptRoute.Local, route(targetHostIsSelf = null, localAgentPresent = true, remoteAgentPresent = false))
        assertEquals(AgentPromptRoute.Remote, route(targetHostIsSelf = null, localAgentPresent = true, remoteAgentPresent = true), "无目标时远端在场仍让路（旧判据）")
    }

    /** 目标=自己 ⇒ Local，**即使别的宿主也有 agent**（§4.5 的行为变更本体）。 */
    @Test
    fun targetSelfWinsEvenWithOtherAgentHosts() {
        assertEquals(AgentPromptRoute.Local, route(targetHostIsSelf = true, localAgentPresent = false, remoteAgentPresent = true))
        assertEquals(AgentPromptRoute.Local, route(targetHostIsSelf = true, localAgentPresent = true, remoteAgentPresent = true))
    }

    /** 目标=别的宿主 ⇒ Remote（消息已按协议送达那台设备）。 */
    @Test
    fun targetElseIsRemoteEvenIfWeHaveLocalAgents() {
        assertEquals(AgentPromptRoute.Remote, route(targetHostIsSelf = false, localAgentPresent = true, remoteAgentPresent = false))
    }
}
