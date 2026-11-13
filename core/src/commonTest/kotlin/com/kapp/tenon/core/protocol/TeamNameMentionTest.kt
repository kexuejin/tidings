// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `@团队名`（整队）解析的钉子。
 *
 * 团队名是**展示串**（可含中文），和组员 slug 不是一套词表 —— 这里钉住的是
 * 「什么算命中、什么必须 fail-closed」，因为两套语义抢一个 `@` 时的症状是
 * 「@ 了团队却派给了某个人」，而那种错误在界面上看不出来。
 */
class TeamNameMentionTest {

    private val host = "host-self"

    private fun seat(
        agentId: String = "dsh-lead",
        name: String = "lead",
        teamId: String? = "team-dev",
        teamName: String? = "开发团队",
        members: List<Pair<String, String>> = listOf("coder" to "编码", "reviewer" to "审查"),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = host,
        name = name,
        entityType = EntityTypes.TEAM,
        teamMembers = members.mapIndexed { i, (n, role) -> TeamMemberInfo("m-$i-$n", n, role) },
        defaultWsId = "ws-1",
        teamId = teamId,
        teamName = teamName,
    )

    private fun group(vararg seats: VirtualAgentMember) = GroupMeta(
        groupId = "g1", name = "n", members = listOf(host), rev = 1,
        updatedBy = host, updatedTs = 1, virtualAgents = seats.toList(),
    )

    @Test
    fun aTeamNameMentionResolvesToTheWholeTeamSeat() {
        val hit = assertIs<TeamNameMention.Hit>(group(seat()).resolveTeamNameMention("@开发团队 修个登录"))
        assertEquals("team-dev", hit.teamId)
        assertEquals("开发团队", hit.teamName)
        assertEquals("dsh-lead", hit.seat.agentId)
    }

    /** 中文是表意文字，不是 token 的延续：`@开发团队修一下` 也算点名。 */
    @Test
    fun chineseRightAfterTheNameStillMatches() {
        assertIs<TeamNameMention.Hit>(group(seat()).resolveTeamNameMention("@开发团队修一下登录"))
    }

    /** 长名优先：`@开发团队二组` 不该被 `@开发团队` 抢先命中。 */
    @Test
    fun longerTeamNameWins() {
        val g = group(
            seat(agentId = "dsh-a", teamId = "team-a", teamName = "开发团队"),
            seat(agentId = "dsh-b", teamId = "team-b", teamName = "开发团队二组"),
        )
        val hit = assertIs<TeamNameMention.Hit>(g.resolveTeamNameMention("@开发团队二组 上"))
        assertEquals("team-b", hit.teamId)
    }

    /** 两支不同的队起了同一个名字：不挑一支。 */
    @Test
    fun twoTeamsSharingANameAreAmbiguous() {
        val g = group(
            seat(agentId = "dsh-a", teamId = "team-a", teamName = "开发团队"),
            seat(agentId = "dsh-b", teamId = "team-b", teamName = "开发团队"),
        )
        assertEquals(TeamNameMention.Ambiguous, g.resolveTeamNameMention("@开发团队 上"))
    }

    /**
     * 团队名与组员 token 撞名 ⇒ 歧义。
     *
     * 这时 `@` 永远先命中组员，团队名等于**打不到** —— 静默选成员会让用户以为
     * 「整队被调起来了」，实际只跑了一个角色。
     */
    @Test
    fun aTeamNamedLikeAMemberIsAmbiguous() {
        val g = group(seat(teamName = "coder"))
        assertEquals(TeamNameMention.Ambiguous, g.resolveTeamNameMention("@coder 修"))
    }

    /** 团队名与席位 token 撞名同样歧义（`@` 先命中席位 ⇒ 单 agent 路径）。 */
    @Test
    fun aTeamNamedLikeASeatIsAmbiguous() {
        val g = group(seat(name = "开发团队", teamName = "开发团队"))
        assertEquals(TeamNameMention.Ambiguous, g.resolveTeamNameMention("@开发团队 修"))
    }

    /** 两支不同的队都被点名：`@` 一次只派一个执行主体，不替人挑一支。 */
    @Test
    fun twoDifferentTeamsAtOnceAreAmbiguous() {
        val g = group(
            seat(agentId = "dsh-a", teamId = "team-a", teamName = "开发团队"),
            seat(agentId = "dsh-b", teamId = "team-b", teamName = "测试团队"),
        )
        assertEquals(
            TeamNameMention.Ambiguous,
            g.resolveTeamNameMention("@开发团队 先跑一遍，@测试团队 随后验"),
        )
    }

    /** 同一支队被点名两次仍是**一个**目标 —— 数命中次数会把正常用法判成歧义。 */
    @Test
    fun theSameTeamMentionedTwiceIsStillOneTarget() {
        val hit = assertIs<TeamNameMention.Hit>(
            group(seat()).resolveTeamNameMention("@开发团队 先看登录，@开发团队 再看支付"),
        )
        assertEquals("team-dev", hit.teamId)
    }

    /** 前缀不算第二个目标：`@开发团队二组` 只点名了更长的那支。 */
    @Test
    fun aLongerNameDoesNotCountItsPrefixAsASecondTeam() {
        val g = group(
            seat(agentId = "dsh-a", teamId = "team-a", teamName = "开发团队"),
            seat(agentId = "dsh-b", teamId = "team-b", teamName = "开发团队二组"),
        )
        val hit = assertIs<TeamNameMention.Hit>(g.resolveTeamNameMention("@开发团队二组 上"))
        assertEquals("team-b", hit.teamId)
    }

    @Test
    fun noTeamNameMeansNoTeamTarget() {
        assertNull(group(seat(teamName = null)).resolveTeamNameMention("@开发团队 修"))
        assertNull(group(seat()).resolveTeamNameMention("@别的队 修"))
    }

    /** 带空白的团队名 `@` 打不出来（输入法在空格断开）⇒ 不参与解析，界面负责提示改名。 */
    @Test
    fun aNameWithWhitespaceIsNotMentionable() {
        assertNull(group(seat(teamName = "开发 团队")).resolveTeamNameMention("@开发 团队 修"))
    }

    /** 宿主侧按 token 反查：与文本解析同一份判据（大小写不敏感）。 */
    @Test
    fun tokenLookupAgreesWithTextParsing() {
        val g = group(seat(teamName = "DevSquad"))
        val byText = assertIs<TeamNameMention.Hit>(g.resolveTeamNameMention("@devsquad 上"))
        val byToken = assertIs<TeamNameMention.Hit>(g.teamSeatMatchingName("DEVSQUAD"))
        assertEquals(byText.seat.agentId, byToken.seat.agentId)
        assertNull(g.teamSeatMatchingName("coder"), "组员 token 不是团队名")
    }
}
