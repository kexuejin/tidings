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
        text: String = "@coder fix it",
        agentKinds: Set<String> = setOf("coder", "dsh-lead"),
    ) = RelayController.agentPromptRoute(
        text = text,
        groupId = "g1",
        isWorkspaceGroup = true,
        repliedToIsAgent = false,
        localAgentPresent = localAgentPresent,
        remoteAgentPresent = remoteAgentPresent,
        agentKinds = agentKinds,
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

    // ------------------------------------------------------------ 整队（@团队名）

    private fun namedTeam(
        host: String = self,
        agentId: String = "dsh-lead",
        teamId: String = "team-dev",
        teamName: String = "开发团队",
    ) = teamSeat(host = host, agentId = agentId).copy(teamId = teamId, teamName = teamName)

    @Test
    fun teamNameJoinsTheMentionPool() {
        val rows = groupParticipantRows(ctx(namedTeam()))
        assertTrue("开发团队" in rows.mentionKeys, "团队名必须进池（候选=匹配=解析 同一份）：" + rows.mentionKeys)
    }

    @Test
    fun aTeamMentionResolvesToTheWholeTeam() {
        val g = group(namedTeam())
        val target = resolvePromptTarget("@开发团队 修登录", g, groupParticipantRows(ctx(namedTeam())).mentionKeys)
        val team = assertIs<PromptTarget.Team>(target)
        assertEquals("team-dev", team.teamId)
        assertEquals("开发团队", team.teamName)
        assertEquals(self, team.hostNodeId, "整队的执行主体是席位宿主")
    }

    /** 团队名与组员同帧：两个意图，不替人合并（与「一次只 @ 一个目标」同一条）。 */
    @Test
    fun aTeamAndAMemberInTheSameMessageAreAmbiguous() {
        val g = group(namedTeam())
        val target = resolvePromptTarget(
            "@开发团队 让 @coder 修登录",
            g, groupParticipantRows(ctx(namedTeam())).mentionKeys,
        )
        assertIs<PromptTarget.Ambiguous>(target)
    }

    /** 团队名与组员 token 撞名：`@` 永远先命中组员 ⇒ 歧义，提示改名而不是静默选一个。 */
    @Test
    fun aTeamNamedLikeAMemberIsAmbiguous() {
        val g = group(namedTeam(teamName = "coder"))
        val target = resolvePromptTarget("@coder 修登录", g, groupParticipantRows(ctx(namedTeam(teamName = "coder"))).mentionKeys)
        assertIs<PromptTarget.Ambiguous>(target)
    }

    /**
     * `@团队名` 必须真的**唤醒路由**（不只是解析出目标）。
     *
     * 这一条与 [teamNameJoinsTheMentionPool] 是两处依赖：池里没有团队名时
     * `shouldTriggerAgent` 判 NotAddressed ⇒ `agentPromptRoute` 返回 None ⇒
     * 「@ 了团队什么都没发生」。目标解析能过、唤醒判据不能过的分叉，
     * 症状正是本仓最贵的「@ 了没反应」。
     */
    @Test
    fun aTeamNameAloneWakesTheRoute() {
        val pool = groupParticipantRows(ctx(namedTeam())).mentionKeys
        assertEquals(
            AgentPromptRoute.Local,
            route(text = "@开发团队 修登录", agentKinds = pool, targetHostIsSelf = true, localAgentPresent = true, remoteAgentPresent = false),
        )
    }
}
