// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **`group.info` 的编码与解码必须对 `virtualAgents` 一致。**
 *
 * ## 真机事故（2026-10-22）
 *
 * 编码走 `@Serializable`（`Codec.groupInfoPayload`），解码是**手写逐字段解析**
 * （`Codec.decodeGroupInfo`）。加 `virtualAgents` 时只加进了 data class，解码那一侧漏了。
 *
 * 于是：
 *
 *   · 发送端编码出完整的 `virtualAgents`（`messages.log` 里看得见那个数组）
 *   · 接收端解出来是 `null`
 *   · `GroupMerge` 里「本地缺、对方有」那个补全例外永远看到 `incomingHasAgents = false`
 *   · 同 rev 的补全被当成 `stale-rev` 丢弃
 *   · 手机上群里永远 0 个 agent、输入 `@` 找不到、气泡署名停在 `dsh`
 *
 * 排查了很久才定位，因为**线上数据是对的、落库结果是空的** —— 中间那一步静默丢了。
 * 这条测试做一次往返：编出来的必须能解回去。
 */
class GroupInfoVirtualAgentsRoundTripTest {

    private fun meta(agents: List<VirtualAgentMember>?) = GroupMeta(
        groupId = "grp-1",
        name = "协同群",
        members = listOf("self", "host-a"),
        rev = 7,
        updatedBy = "self",
        updatedTs = 1_000L,
        virtualAgents = agents,
    )

    /** **核心判据：编码再解码，`virtualAgents` 必须一条不少地回来。** */
    @Test
    fun virtualAgentsSurviveTheEncodeDecodeRoundTrip() {
        val original = meta(
            listOf(
                VirtualAgentMember(
                    agentId = "DSH",
                    hostNodeId = "host-a",
                    name = "dsh-teahouse-mobile",
                    defaultWsId = "ws-e9b18063d183",
                ),
            ),
        )
        val payload = Codec.groupInfoPayload(original)
        val decoded = assertNotNull(Codec.decodeGroupInfo(payload), "info 载荷必须能解回来")
        assertEquals(
            1,
            decoded.virtualAgents?.size,
            "virtualAgents 在解码时丢了 —— 编码用 @Serializable、解码是手写，加字段只加了一边",
        )
        val agent = decoded.virtualAgents!!.single()
        assertEquals("DSH", agent.agentId)
        assertEquals("host-a", agent.hostNodeId)
        assertEquals("dsh-teahouse-mobile", agent.name)
        assertEquals("ws-e9b18063d183", agent.defaultWsId)
    }

    /**
     * **团队形态与工作区白名单必须原样解回来**（方案 §3）。
     *
     * 解码是手写逐字段，编码是 `@Serializable` —— 新字段只加 data class 不加解码，
     * 症状与当年 `virtualAgents` 整个丢失同型：线上数据完整、落库全空、
     * 授权面板（非宿主侧只见 id 与标题）与 @ 候选一起失效。所以这条走**解码**断言，
     * 不只断言 JSON 里有键。
     */
    @Test
    fun teamShapeAndWorkspaceGrantsSurviveTheRoundTrip() {
        val original = meta(
            listOf(
                VirtualAgentMember(
                    agentId = "dev-lead",
                    hostNodeId = "host-a",
                    name = "dev-lead",
                    entityType = "team",
                    teamMembers = listOf(
                        TeamMemberInfo(agentId = "coder", name = "coder", role = "FullStack Engineer"),
                        TeamMemberInfo(agentId = "tester", name = "tester", role = "QA Engineer"),
                    ),
                    allowedWorkspaces = listOf(
                        GroupWorkspaceInfo(wsId = "ws-mobile", title = "Teahouse Mobile"),
                        GroupWorkspaceInfo(wsId = "ws-backend", title = "Teahouse Backend", description = "后端服务"),
                    ),
                    defaultWsId = "ws-mobile",
                ),
                VirtualAgentMember(
                    agentId = "solo",
                    hostNodeId = "host-a",
                    name = "solo",
                ),
            ),
        )
        val payload = Codec.groupInfoPayload(original)
        val decoded = assertNotNull(Codec.decodeGroupInfo(payload))
        val team = assertNotNull(
            decoded.virtualAgents?.firstOrNull { it.agentId == "dev-lead" },
            "团队声明必须解回来",
        )
        assertEquals("team", team.entityType, "entityType 在解码时丢了")
        assertEquals(2, team.teamMembers.size, "teamMembers 在解码时丢了")
        assertEquals(TeamMemberInfo(agentId = "coder", name = "coder", role = "FullStack Engineer"), team.teamMembers.first())
        assertEquals(2, team.allowedWorkspaces.size, "allowedWorkspaces 在解码时丢了 —— 非宿主设备的授权面板会全空")
        assertEquals(GroupWorkspaceInfo(wsId = "ws-backend", title = "Teahouse Backend", description = "后端服务"), team.allowedWorkspaces.last())
        assertEquals("ws-mobile", team.defaultWsId, "defaultWsId 在解码时丢了")

        val solo = assertNotNull(decoded.virtualAgents?.firstOrNull { it.agentId == "solo" })
        assertEquals("single", solo.entityType, "缺省 entityType 解码为单兵")
        assertEquals(emptyList(), solo.teamMembers)
        assertEquals(emptyList(), solo.allowedWorkspaces)
        assertNull(solo.defaultWsId)
    }

    /**
     * 旧形状（带 `role` / `wsId` 键的声明）**照样解码**：`group` 帧没有字段白名单，
     * 旧键只是被忽略 —— 不该让旧设备广播的元数据整帧失效。
     */
    @Test
    fun anOldShapeDeclarationStillDecodesWithoutTheRemovedKeys() {
        val original = meta(
            listOf(VirtualAgentMember(agentId = "DSH", hostNodeId = "host-a", name = "dsh-teahouse-mobile")),
        )
        val payload = Codec.groupInfoPayload(original).toString()
        // 在编码产物上手工加回旧键（编码器已不再产出它们），模拟旧构建的帧。
        // virtualAgents 是 group 对象的最后一个键，数组之后紧跟两层收括号。
        val patched = payload.replace(
            """}]}}""",
            ""","role":"Senior Engineer","wsId":"ws-legacy"}]}}""",
        )
        assertTrue(patched != payload, "前提：补丁必须真的插进去了")
        val decoded = assertNotNull(
            Codec.decodeGroupInfo(Codec.wireJson.parseToJsonElement(patched)),
            "旧形状的 info 帧必须能解回来",
        )
        val agent = decoded.virtualAgents?.single()
        assertNotNull(agent, "旧形状的声明不能被丢掉")
        assertNull(agent.defaultWsId, "旧键被忽略：不产出默认工作区")
    }

    /**
     * **同 rev 的补全例外要靠解码后的数据才生效。**
     *
     * 这条把「解码」与「合并判据」串起来钉：只修其中一处都不够 —— 解码丢了字段，
     * 例外就永远看到 `incomingHasAgents = false`。
     */
    @Test
    fun aDecodedSameRevisionFillsTheMissingAgents() {
        val incoming = meta(
            listOf(
                VirtualAgentMember(agentId = "DSH", hostNodeId = "host-a", name = "dsh-teahouse-mobile"),
            ),
        )
        val decoded = assertNotNull(Codec.decodeGroupInfo(Codec.groupInfoPayload(incoming)))
        // 手机本地：同一个 rev、但 virtualAgents 是空的（那个字段还不存在时落库的）。
        val local = meta(agents = null)
        val decision = GroupMerge.decide(local, decoded, "self")
        assertTrue(
            decision is GroupMergeDecision.Apply,
            "解码后应带上 agent 声明，从而触发同 rev 的补全；实得 " + decision,
        )
    }

    /** 没有声明时解码结果也该是 null（与「旧端不发」同义），不能变成空列表。 */
    @Test
    fun anAbsentFieldDecodesToNull() {
        val decoded = assertNotNull(Codec.decodeGroupInfo(Codec.groupInfoPayload(meta(null))))
        assertEquals(null, decoded.virtualAgents)
    }
}
