// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.PeerUi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 三层正交模型在**投影层**的集成判据（方案 §2② / 洞 A 的聚类根因）。
 *
 * ## 为什么这条链不需要跨端帧透传
 *
 * 卡片身份的根不在会话帧，而在聚类：`hostIndexOf` 现在按 `sameAgent`（agentId 前缀归一）
 * 把同底座的一切并进一个 `HubCluster`，`assembleEntity` 再用 `sameAgent` 反查档案 —— 于是
 * 同底座两个真人永远是一张卡、归到第一份档案（洞 A/E 的投影病根）。
 *
 * 而员工身份锚 `employeeId` 在**本机就有现成来源**：席位 `VirtualAgentMember.employeeId`
 * 与档案 `EmployeeProfile.employeeId` 都是本机数据。所以最小可验证的透传链是本机聚类：
 * 席位带 employeeId → 聚类按 employeeId 精确分开 → 档案按 employeeId 精确归档 → 卡面带
 * 各自的 employeeId。**不需要改 agent-invoke / agent-session 任何协议帧。**
 *
 * 会话帧（agent-session）是否要带 employeeId 是另一回事：它决定「远端宿主起的会话」
 * 能否归到本机员工；本测试只钉本机可验证的那段，避免拿一个跨端字段冒充已落地。
 */
class EmployeeIdentityClusteringTest {

    private val self = "host-local-0001"
    private val now = 1_700_000_000_000L

    private fun peer(nodeId: String) = PeerUi(
        nodeId = nodeId, nick = nodeId, platform = "",
        caps = listOf(MsgTypes.CAP_AGENT), online = true,
    )

    private fun seat(agentId: String, name: String, employeeId: String) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = self,
        name = name,
        entityType = EntityTypes.SINGLE,
        employeeId = employeeId,
    )

    private fun group(seats: List<VirtualAgentMember>) =
        GroupMeta.create("g-1", "研发群", members = emptyList(), selfId = self, updatedTs = 1L)
            .copy(virtualAgents = seats)

    private fun state(seats: List<VirtualAgentMember>, employees: List<EmployeeProfile>): RelayUiState {
        val settings = RelaySettings.firstRun().copy(
            nodeId = self, groups = listOf(group(seats)), employeeProfiles = employees,
        )
        return RelayUiState(settings = settings, peers = listOf(peer(self)))
    }

    private fun employee(employeeId: String, name: String) = EmployeeProfile(
        employeeId = employeeId,
        agentId = "dsh", // 同底座 —— 这正是要点：身份不看底座
        name = name,
        token = "tok-$employeeId",
    )

    private fun cardsOf(s: RelayUiState): List<AgentHubUi> =
        hubAgents(s.toAgentSlice(), s.toNodeSlice(), s.toSettingsSlice(), now)

    @Test
    fun twoSeatsOnTheSameDriverWithDifferentEmployeeIdsAreTwoCards() {
        // 同一底座 dsh 的两个席位，各是一个员工（三层模型：底座只是路由线索）。
        val s = state(
            seats = listOf(
                seat("dsh", "dsh-wang", "emp-wang"),
                seat("dsh", "dsh-li", "emp-li"),
            ),
            employees = listOf(
                employee("emp-wang", "前端小王"),
                employee("emp-li", "后端老李"),
            ),
        )

        val cards = cardsOf(s)
        assertEquals(2, cards.size, "同底座两个不同 employeeId 必须是两张卡，不是并成一张")
        assertEquals(
            setOf("emp-wang", "emp-li"),
            cards.map { it.employeeId }.toSet(),
            "每张卡带自己的身份锚，各归各档",
        )
        assertEquals(
            setOf("前端小王", "后端老李"),
            cards.map { it.displayName }.toSet(),
            "显示名按各自的档案，不串到第一份",
        )
        assertEquals(
            listOf(1, 1),
            cards.map { it.groupCount },
            "两人同在一个群，但每张卡只算自己的席位，不把对方的席位串进来",
        )
    }

    @Test
    fun sameDriverTwoSeatsMatchTheirOwnProfileByEmployeeId() {
        // 变体：席位 agentId 精度不同（dsh / dsh-029bbbf3），但 employeeId 明确 —— 身份只看 employeeId。
        val s = state(
            seats = listOf(
                seat("dsh", "dsh-wang", "emp-wang"),
                seat("dsh-029bbbf3", "dsh-li", "emp-li"),
            ),
            employees = listOf(
                employee("emp-wang", "前端小王"),
                employee("emp-li", "后端老李"),
            ),
        )

        val cards = cardsOf(s)
        assertEquals(2, cards.size, "agentId 精度不同不影响：employeeId 才是身份锚")
        val byEmployeeId: Map<String?, String?> = cards.associate { it.employeeId to it.displayName }
        assertEquals(
            mapOf<String?, String?>("emp-wang" to "前端小王", "emp-li" to "后端老李"),
            byEmployeeId,
            "各自的卡归各自的档案",
        )
    }

    @Test
    fun aSeatWithoutEmployeeIdStillFallsBackToAgentIdClustering() {
        // 兼容：老席位没有 employeeId（手机端建的 / 旧数据），仍按 agentId 聚成一张卡，不炸。
        val s = state(
            seats = listOf(seat("dsh", "dsh-coder", employeeId = "emp-unused").copy(employeeId = null)),
            employees = listOf(employee("emp-unused", "小码")),
        )
        val cards = cardsOf(s)
        assertEquals(1, cards.size, "无 employeeId 的老席位仍聚成一张卡")
        // 兼容：没有身份锚时回落 agentId 路由尺反查档案（老数据不至于全部丢掉档案）。
        // 这是降级路径，不是身份判定 —— 新数据一律有 employeeId，走精确匹配。
        assertEquals("emp-unused", cards.single().employeeId, "无身份锚的老席位靠 agentId 反查到档案，保持兼容")
    }
}
