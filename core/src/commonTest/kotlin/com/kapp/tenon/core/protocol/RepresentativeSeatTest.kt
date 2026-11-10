// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「代表席位」单一判据的行为钉子。
 *
 * 行显示名（`resolveAgentOfHost`）、撤回按钮的默认席位（`declaredWorkspaceOfSender`）、
 * 团队编制对话框的默认选中 —— 三处过去各取各的 `firstOrNull`（判据还不同），
 * 多席位时可能落在**不同**条声明上。现在它们共用 [representativeSeat]。
 */
class RepresentativeSeatTest {

    private val self = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
    private val other = "11111111-2222-4333-8444-555555555555"

    private fun seat(
        host: String = self,
        agentId: String = "dsh",
        name: String = "dsh-ws-a",
        wsId: String? = "ws-a",
    ) = VirtualAgentMember(agentId = agentId, hostNodeId = host, name = name, defaultWsId = wsId)

    @Test
    fun theFirstMatchingDeclarationIsTheRepresentative() {
        val seats = listOf(seat(name = "dsh-ws-a"), seat(name = "dsh-ws-b", wsId = "ws-b"))
        assertEquals("dsh-ws-a", representativeSeat(seats, self, "dsh")?.name)
    }

    /** agentId 的大小写差异不算另一个 agent（声明常写大写 kind，行身份是小写）。 */
    @Test
    fun agentIdCaseDoesNotSplitAnAgent() {
        val seats = listOf(seat(agentId = "DSH", name = "dsh-teahouse-mobile"))
        assertEquals("dsh-teahouse-mobile", representativeSeat(seats, self, "dsh")?.name)
    }

    /** kind 与 id 的精度差异容忍（`dsh` ~ `dsh-029bbbf3`）—— 与 resolveSenderIdentity 同一条判据。 */
    @Test
    fun kindMatchesFullIdDeclarations() {
        val seats = listOf(seat(agentId = "dsh-029bbbf3", name = "dsh-teahouse-mobile"))
        assertEquals("dsh-teahouse-mobile", representativeSeat(seats, self, "dsh")?.name)
    }

    /** 名字也能命中（老数据只认 name）。 */
    @Test
    fun theMentionNameAlsoIdentifiesTheSeat() {
        val seats = listOf(seat(agentId = "dsh", name = "dsh-teahouse-mobile"))
        assertEquals("dsh-teahouse-mobile", representativeSeat(seats, self, "dsh-teahouse-mobile")?.name)
    }

    @Test
    fun anotherHostsSeatsAreInvisible() {
        val seats = listOf(seat(host = other, name = "dsh-ws-b", wsId = "ws-b"))
        assertNull(representativeSeat(seats, self, "dsh"))
    }

    @Test
    fun aDifferentAgentDoesNotMatch() {
        val seats = listOf(seat(agentId = "codex", name = "codex-ws-a"))
        assertNull(representativeSeat(seats, self, "dsh"))
    }

    @Test
    fun noDeclarationsYieldNull() {
        assertNull(representativeSeat(null, self, "dsh"))
        assertNull(representativeSeat(emptyList(), self, "dsh"))
    }

    /** 多席位时 [seatsOfAgent] 给**全部**席位（对话框的席位选择靠它，不能只给一条）。 */
    @Test
    fun seatsOfAgentListsEverySeatOfTheAgent() {
        val seats = listOf(
            seat(name = "dsh-ws-a", wsId = "ws-a"),
            seat(name = "dsh-ws-b", wsId = "ws-b"),
            seat(host = other, agentId = "dsh", name = "dsh-ws-remote", wsId = "ws-remote"),
            seat(agentId = "codex", name = "codex-ws-a", wsId = "ws-a"),
        )
        val mine = seatsOfAgent(seats, self, "dsh")
        assertEquals(listOf("dsh-ws-a", "dsh-ws-b"), mine.map { it.name }, "只收本宿主同 agent 的席位，按声明顺序")
    }

    /** declaredWorkspaceOfSender 的「默认席位」与 representativeSeat 落在同一条（§3.5 的收口）。 */
    @Test
    fun theRevokeButtonsDefaultSeatAgreesWithTheRepresentativeSeat() {
        val seats = listOf(
            seat(name = "dsh-ws-a", wsId = "ws-a"),
            seat(name = "dsh-ws-b", wsId = "ws-b"),
        )
        val viaSender = declaredWorkspaceOfSender(
            senderId = "agent:$self:dsh",
            declaredAgents = seats,
            hostNodeId = self,
        )
        assertEquals(representativeSeat(seats, self, "dsh")?.defaultWsId, viaSender)
    }
}
