// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.MessageUi
import app.relay.app.model.PeerUi
import app.relay.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话列表里「每一条为什么在这里」（task-42 ③）。
 *
 * 规则：人类对端与群**一律**在列表里（刚配对完的电脑必须立刻出现）；
 * **agent 节点**只有在「有可见会话」或「有消息」时才算一条会话 ——
 * 两者都空的 agent 行点进去既没有会话也没有消息，正是用户问的那几个「没聊过的会话」。
 */
class ConversationSourceTest {

    private fun agentPeer(id: String) = PeerUi(
        nodeId = id,
        nick = id,
        online = true,
        caps = listOf(MsgTypes.CAP_AGENT),
    )

    private fun humanPeer(id: String) = PeerUi(nodeId = id, nick = id, online = true)

    private fun session(peerId: String, sid: String, origin: String? = null) = AgentSessionUi(
        peerId = peerId,
        sessionId = sid,
        state = "busy",
        title = "t",
        hostOrigin = origin,
    )

    private fun text(peerId: String, id: String) =
        MessageUi(id = id, peerId = peerId, mine = false, text = "x", at = 1L)

    @Test
    fun agentNodeWithNothingIsNotAConversation() {
        val state = RelayUiState(peers = listOf(agentPeer("agent-a")))
        assertEquals(emptyList(), state.conversations.map { it.convId })
    }

    @Test
    fun agentNodeWhoseSessionsAreAllHiddenIsNotAConversation() {
        // 子代理会话被 visibleInDsh 过滤掉；只剩它的 agent 行没有内容可看。
        val state = RelayUiState(
            peers = listOf(agentPeer("agent-a")),
            agentSessions = mapOf(
                AgentSessionKey("agent-a", "s-sub") to session("agent-a", "s-sub", origin = "subagent"),
            ),
        )
        assertEquals(emptyList(), state.conversations.map { it.convId })
    }

    @Test
    fun agentNodeWithAVisibleSessionIsAConversation() {
        val state = RelayUiState(
            peers = listOf(agentPeer("agent-a")),
            agentSessions = mapOf(
                AgentSessionKey("agent-a", "s-1") to session("agent-a", "s-1"),
            ),
        )
        assertEquals(listOf("agent-a"), state.conversations.map { it.convId })
    }

    @Test
    fun agentNodeWithMessagesIsAConversation() {
        val state = RelayUiState(
            peers = listOf(agentPeer("agent-a")),
            messages = mapOf("agent-a" to listOf(text("agent-a", "m-1"))),
        )
        assertEquals(listOf("agent-a"), state.conversations.map { it.convId })
    }

    @Test
    fun humanPeerWithNothingStillAppears() {
        // 回归护栏：这条收紧**只针对 agent**，不能把「刚配对完的电脑」一起藏掉。
        val state = RelayUiState(peers = listOf(humanPeer("peer-h")))
        assertEquals(listOf("peer-h"), state.conversations.map { it.convId })
    }
}
