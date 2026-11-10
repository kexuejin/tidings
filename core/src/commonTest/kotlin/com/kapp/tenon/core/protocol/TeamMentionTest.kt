// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 团队执行（v2）成员 mention 解析的**单源**钉子（方案 §4.1）。
 *
 * 三个消费方（app 路由 / 编排层 resolver / @ 候选）共用 [GroupMeta.resolveTeamMention]
 * 与 [GroupMeta.resolveTeamMemberToken] —— 这里钉住判据本体：长 token 优先、大小写
 * 不敏感、ASCII 词边界、非 team 席位不命中、跨席位重名 fail-closed。
 */
class TeamMentionTest {

    private val hostSelf = "host-self"
    private val hostOther = "host-other"

    private fun teamSeat(
        host: String = hostSelf,
        agentId: String = "dsh-lead",
        members: List<Triple<String, String, String>> = listOf(
            Triple("m-coder", "coder", "编码"),
            Triple("m-reviewer", "reviewer", "审查"),
        ),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = host,
        name = "lead-$host",
        entityType = EntityTypes.TEAM,
        teamMembers = members.map { (id, name, role) -> TeamMemberInfo(id, name, role) },
        defaultWsId = "ws-1",
    )

    private fun group(vararg seats: VirtualAgentMember) = GroupMeta(
        groupId = "g1", name = "n", members = listOf(hostSelf), rev = 1,
        updatedBy = hostSelf, updatedTs = 1, virtualAgents = seats.toList(),
    )

    @Test
    fun aMemberMentionResolvesToItsSeatAndMember() {
        val hit = assertIs<TeamMention.Hit>(group(teamSeat()).resolveTeamMention("@coder 把登录修了"))
        assertEquals("m-coder", hit.member.agentId)
        assertEquals("dsh-lead", hit.seat.agentId)
    }

    /** 长 token 优先：`@coder-review` 不该被 `@coder` 抢先命中。 */
    @Test
    fun longerTokenWinsOverItsPrefix() {
        val seats = teamSeat(
            members = listOf(
                Triple("m-coder", "coder", "编码"),
                Triple("m-reviewer", "coder-review", "审查"),
            ),
        )
        val hit = assertIs<TeamMention.Hit>(group(seats).resolveTeamMention("@coder-review 逐条审"))
        assertEquals("m-reviewer", hit.member.agentId)
    }

    @Test
    fun matchingIsCaseInsensitiveAndWordBounded() {
        val g = group(teamSeat())
        val hit = assertIs<TeamMention.Hit>(g.resolveTeamMention("@CODER fix it"))
        assertEquals("m-coder", hit.member.agentId)
        // ASCII 词边界：`@coder你好` 命中 `@coder`（中文不是 token 的延续）。
        assertEquals("m-coder", assertIs<TeamMention.Hit>(g.resolveTeamMention("@coder你好")).member.agentId)
        // 词边界：`@coders` 不命中 `@coder`（标识符字符延续 = 另一个 token）。
        assertNull(g.resolveTeamMention("@coders fix it"))
    }

    /**
     * isTeam 的口径（上游 v1 钉子）：`entityType == team` **或** 组员清单非空 ——
     * 组员清单是事实，entityType 只是声明。所以「entityType=single 但组员非空」
     * 仍然是团队目标（事实赢）；真正不参与的是**没有组员清单**的单兵席位（§3.1）。
     */
    @Test
    fun onlySeatsWithoutARosterAreNeverTeamTargets() {
        val declaredSingle = VirtualAgentMember(
            agentId = "solo", hostNodeId = hostSelf, name = "solo-seat",
            entityType = EntityTypes.SINGLE,
            teamMembers = listOf(TeamMemberInfo("m-solo", "soloist", "独奏")),
        )
        // 声明是单兵、组员清单是事实 ⇒ 事实赢：仍是团队目标。
        assertIs<TeamMention.Hit>(group(declaredSingle).resolveTeamMention("@soloist 干活"))
        // 真正的单兵：没有组员清单 ⇒ @ 解析不出团队目标。
        val bare = VirtualAgentMember(agentId = "bare", hostNodeId = hostSelf, name = "bare-seat")
        assertNull(group(bare).resolveTeamMention("@bare-seat 干活"))
    }

    /** 跨席位重名（手改帧）⇒ Ambiguous —— 绝不挑一个（挑错席位 = 在别人的仓里跑命令）。 */
    @Test
    fun duplicatedTokensAcrossSeatsFailClosed() {
        val g = group(
            teamSeat(host = hostSelf, agentId = "lead-a"),
            teamSeat(host = hostOther, agentId = "lead-b"),
        )
        assertIs<TeamMention.Ambiguous>(g.resolveTeamMention("@coder fix it"))
    }

    /** 按 token 精确解析（路由层逐 token 分类用）：不命中 = null，重名 = Ambiguous。 */
    @Test
    fun tokenBasedLookupAgreesWithTextLookup() {
        val g = group(teamSeat())
        assertEquals(
            (g.resolveTeamMention("@reviewer 审") as TeamMention.Hit).member.agentId,
            assertIs<TeamMention.Hit>(g.resolveTeamMemberToken("REVIEWER")).member.agentId,
        )
        assertNull(g.resolveTeamMemberToken("nobody"))
        assertNull(g.resolveTeamMemberToken("lead-self"), "席位名不是组员 token")
        val dup = group(
            teamSeat(host = hostSelf, agentId = "lead-a"),
            teamSeat(host = hostOther, agentId = "lead-b"),
        )
        assertIs<TeamMention.Ambiguous>(dup.resolveTeamMemberToken("coder"))
    }

    /** 席位级 token（name / agentId）解析 —— 路由层区分「席位目标」用。 */
    @Test
    fun seatTokensResolveByExactNameOrAgentId() {
        val g = group(teamSeat())
        assertEquals(1, g.seatsMatchingToken("lead-host-self").size)
        assertEquals(1, g.seatsMatchingToken("DSH-LEAD").size, "agentId 大小写不敏感")
        assertTrue(g.seatsMatchingToken("coder").isEmpty(), "组员名不是席位 token")
    }
}
