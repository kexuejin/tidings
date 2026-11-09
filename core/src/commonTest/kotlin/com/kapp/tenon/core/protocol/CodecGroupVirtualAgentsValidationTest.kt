// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `validateGroup` 对 `virtualAgents` 的形状/条数/预算校验（方案 §4.5）的行为钉子。
 *
 * 以前收端对 `virtualAgents` **完全不校验**：坏条目靠解码侧 `mapNotNull` 静默丢弃，
 * 一条超长的组员名让整条声明（连同宿主归属与团队）消失，发送侧毫无感知。现在
 * 越界拒绝**整帧**（bad-payload）—— 让发送侧能从失败里看到，而不是对着空列表猜。
 */
class CodecGroupVirtualAgentsValidationTest {

    private val base = GroupMeta(
        groupId = "g1",
        name = "项目研发群",
        members = listOf("node-a", "node-b"),
        rev = 3,
        updatedBy = "node-a",
        updatedTs = 1_000L,
        ownerId = "node-a",
        adminIds = emptyList(),
    )

    /** 经 `@Serializable` 编码（与生产广播同一条路径）再校验。 */
    private fun validOnWire(meta: GroupMeta): Boolean = CodecGroup.validateGroupMeta(meta)

    private fun member(
        agentId: String = "dsh",
        name: String = "dsh-teahouse-mobile",
        entityType: String = EntityTypes.SINGLE,
        teamMembers: List<TeamMemberInfo> = emptyList(),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = "node-a",
        name = name,
        entityType = entityType,
        teamMembers = teamMembers,
        defaultWsId = "ws-1",
    )

    // ------------------------------------------------------------ 合法形状放行

    @Test
    fun aWellFormedDeclarationWithATeamPasses() {
        val meta = base.copy(
            virtualAgents = listOf(
                member(
                    entityType = EntityTypes.TEAM,
                    teamMembers = listOf(
                        TeamMemberInfo("coder", "coder", "编码"),
                        TeamMemberInfo("reviewer", "reviewer", "审查"),
                    ),
                ),
            ),
        )
        assertTrue(validOnWire(meta), "完整团队声明必须过校验")
        // 且解码**不丢人**：发送侧校验通过的数据不会走到 mapNotNull 丢弃分支。
        val decoded = Codec.decodeGroupInfo(CodecGroup.groupInfoPayload(meta))
        assertEquals(2, decoded?.virtualAgents?.single()?.teamMembers?.size)
        assertEquals("编码", decoded?.virtualAgents?.single()?.teamMembers?.first()?.role)
    }

    @Test
    fun unknownEntityTypeValuesAreNotRejected() {
        val meta = base.copy(virtualAgents = listOf(member(entityType = "swarm-future")))
        assertTrue(validOnWire(meta), "entityType 未知取值不拒帧（展示字段，消费方归一）")
        assertEquals(
            EntityTypes.SINGLE,
            Codec.decodeGroupInfo(CodecGroup.groupInfoPayload(meta))?.virtualAgents?.single()?.entityType,
            "解码后消费方归一到单兵",
        )
    }

    @Test
    fun multiSeatDeclarationsOfTheSameAgentAreAllowedWhileSingle() {
        val meta = base.copy(
            virtualAgents = listOf(member(name = "dsh-ws-a"), member(name = "dsh-ws-b")),
        )
        assertTrue(validOnWire(meta), "同 agent 多席位（都单兵）合法 —— 席位声明单位本就允许")
    }

    // ------------------------------------------------------------ 越界拒绝整帧

    @Test
    fun anOverlongTeamMemberFieldRejectsTheWholeFrame() {
        val longRole = TeamMemberInfo("coder", "coder", "r".repeat(Limits.TEAM_ROLE + 1))
        val meta = base.copy(virtualAgents = listOf(member(teamMembers = listOf(longRole))))
        assertFalse(validOnWire(meta), "超长 role 拒整帧 —— 不再静默丢条目")
        // 解码侧的兜底仍在（防御老帧）：那条声明被丢弃而不是崩。
        val raw = Json.decodeFromString(
            kotlinx.serialization.json.JsonObject.serializer(),
            CodecGroup.groupInfoPayload(meta).toString(),
        )
        assertEquals(null, Codec.decodeGroupInfo(raw)?.virtualAgents?.firstOrNull()?.teamMembers?.firstOrNull())
    }

    @Test
    fun tooManyTeamMembersOnOneSeatRejectsTheFrame() {
        val members = (1..(Limits.TEAM_MEMBERS_MAX + 1)).map {
            TeamMemberInfo("m$it", "m$it", "r")
        }
        val meta = base.copy(virtualAgents = listOf(member(teamMembers = members)))
        assertFalse(validOnWire(meta), "单席位组员超 TEAM_MEMBERS_MAX 拒整帧")
    }

    @Test
    fun tooManyTeamMembersAcrossTheFrameRejectsTheFrame() {
        // 9 个团队席位 × 8 人 = 72 > 64：单席位都合法，总量越界。
        val seats = (2..10).map { i ->
            member(
                agentId = "agent-$i",
                entityType = EntityTypes.TEAM,
                teamMembers = (1..Limits.TEAM_MEMBERS_MAX).map { TeamMemberInfo("m$i-$it", "m$i-$it", "r") },
            )
        }.toMutableList().also { it[0] = member(
            agentId = "dsh",
            entityType = EntityTypes.TEAM,
            teamMembers = (1..Limits.TEAM_MEMBERS_MAX).map { TeamMemberInfo("a$it", "a$it", "r") },
        ) }
        val meta = base.copy(virtualAgents = seats)
        assertFalse(validOnWire(meta), "全帧组员总数超 GROUP_MAX_TEAM_MEMBERS_TOTAL 拒整帧")
    }

    @Test
    fun tooManyDeclarationsRejectTheFrame() {
        val seats = (1..(Limits.GROUP_MAX_VIRTUAL_AGENTS + 1)).map {
            member(agentId = "agent-$it", name = "agent-$it")
        }
        val meta = base.copy(virtualAgents = seats)
        assertFalse(validOnWire(meta), "声明条数超 GROUP_MAX_VIRTUAL_AGENTS 拒整帧")
    }

    @Test
    fun twoTeamsOnTheSameSeatRejectTheFrame() {
        val first = member(agentId = "dsh", entityType = EntityTypes.TEAM, teamMembers = listOf(TeamMemberInfo("c", "c", "r")))
        val second = member(agentId = "dsh", name = "dsh-ws-b", entityType = EntityTypes.TEAM, teamMembers = listOf(TeamMemberInfo("t", "t", "r")))
        val meta = base.copy(virtualAgents = listOf(first, second))
        assertFalse(validOnWire(meta), "同一 (host, agentId) 两条团队拒整帧（防手改帧/旧数据）")
    }

    @Test
    fun malformedShapesRejectTheFrame() {
        val groupJson = CodecGroup.groupInfoPayload(base).toString()
        fun wireWithSeat(seat: String): Boolean {
            val group = (Json.parseToJsonElement(groupJson) as kotlinx.serialization.json.JsonObject)["group"]!!
            val patched = kotlinx.serialization.json.buildJsonObject {
                (group as kotlinx.serialization.json.JsonObject).forEach { (k, v) -> put(k, v) }
                put("virtualAgents", Json.parseToJsonElement(seat))
            }
            return CodecGroup.validateGroup(
                Json.parseToJsonElement("""{"op":"info","group":$patched}""") as kotlinx.serialization.json.JsonObject,
            )
        }
        val full = """{"agentId":"dsh","hostNodeId":"node-a","name":"dsh","entityType":"team"}"""
        assertTrue(wireWithSeat("[$full]"), "最小合法声明放行（对照组）")
        // 缺 agentId
        assertFalse(
            wireWithSeat("""[{"hostNodeId":"node-a","name":"dsh"}]"""),
            "缺 agentId 拒整帧",
        )
        // name 超预算
        assertFalse(
            wireWithSeat("""[{"agentId":"dsh","hostNodeId":"node-a","name":"${"n".repeat(Limits.NICK + 1)}"}]"""),
            "超长 name 拒整帧",
        )
        // hostNodeId 不是串
        assertFalse(
            wireWithSeat("""[{"agentId":"dsh","hostNodeId":42,"name":"dsh"}]"""),
            "形状不对（hostNodeId 非串）拒整帧",
        )
        // allowedWorkspaces 条目缺 title
        assertFalse(
            wireWithSeat("""[{"agentId":"dsh","hostNodeId":"node-a","name":"dsh","allowedWorkspaces":[{"wsId":"ws-1"}]}]"""),
            "白名单条目缺 title 拒整帧",
        )
        // defaultWsId 给了但不是串
        assertFalse(
            wireWithSeat("""[{"agentId":"dsh","hostNodeId":"node-a","name":"dsh","defaultWsId":42}]"""),
            "defaultWsId 非串拒整帧",
        )
        // defaultWsId 显式 null 合法（与缺省同义）
        assertTrue(
            wireWithSeat("""[{"agentId":"dsh","hostNodeId":"node-a","name":"dsh","defaultWsId":null}]"""),
            "defaultWsId 显式 null 放行",
        )
    }

    // ------------------------------------------------------------ 发送侧同一判据

    @Test
    fun theSendSideRunsTheSameValidation() {
        val good = base.copy(virtualAgents = listOf(member()))
        assertTrue(CodecGroup.validateGroupMeta(good), "发送侧：合法元数据放行")
        val bad = base.copy(
            virtualAgents = listOf(
                member(teamMembers = listOf(TeamMemberInfo("c", "c", "r".repeat(Limits.TEAM_ROLE + 1)))),
            ),
        )
        assertFalse(CodecGroup.validateGroupMeta(bad), "发送侧：超预算元数据拦截 —— 不产出注定被拒的帧")
    }
}
