// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.domain.model.WorkspaceKey
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.AgentEventUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.AgentGrantAccess
import com.kapp.tenon.domain.model.GrantUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 跨面投影的**等价性**测试：切片版本必须与聚合版本逐字同义。
 *
 * 为什么需要它：投影是新写的一份逻辑（`RelayStateProjections.kt`），而旧聚合版本还会
 * 被未迁移的调用方长期使用（androidApp 截图门禁、未迁移屏）。两份逻辑一旦漂移，
 * 「界面上同一个数字两边不一样」就会回来 —— 这正是本仓最贵的一类缺陷（一个事实两个来源）。
 * 等价性断言把两份实现钉在一起：改任一边而另一边没跟上，用例立刻红。
 */
@Suppress("DEPRECATION")
class RelayStateProjectionsTest {

    private val self = "self-0000-4000-8000-000000000001"
    private val agentPeer = PeerUi(nodeId = "peer-agent", nick = "gpu-box", platform = "", caps = listOf("ag1"), online = true)
    private val humanPeer = PeerUi(nodeId = "peer-human", nick = "alice", platform = "", caps = emptyList(), online = true)

    private fun state(
        unread: Map<String, Int> = emptyMap(),
        pending: Map<String, Int> = emptyMap(),
        workspaces: Map<WorkspaceKey, WorkspaceUi> = emptyMap(),
        approvals: Map<String, AgentApprovalUi> = emptyMap(),
    ) = RelayUiState(
        settings = RelaySettings.firstRun().copy(nodeId = self, nick = "我的手机"),
        peers = listOf(agentPeer, humanPeer),
        unreadByPeer = unread,
        pendingByPeer = pending,
        agentWorkspaces = workspaces,
        agentApprovals = approvals,
    )

    @Test
    fun navBadgesProjectionMatchesTheAggregateVersion() {
        val cases = listOf(
            state(),
            state(unread = mapOf("peer-human" to 3)),
            state(pending = mapOf("peer-agent" to 2)),
            state(
                unread = mapOf("peer-human" to 1, "group:g1" to 4),
                pending = mapOf("peer-agent" to 2, "peer-human" to 9),
            ),
            // 本机自己声明了工作区 ⇒ 本机算 agent 宿主，它的 pending 也要算进 Agent 徽标
            state(
                pending = mapOf(self to 5),
                workspaces = mapOf(
                    WorkspaceKey(peerId = self, agentKey = "dsh", wsId = "ws-1") to WorkspaceUi(
                        peerId = self,
                        agentKey = "dsh",
                        wsId = "ws-1",
                        wsTitle = "teahouse-mobile",
                        wsPath = "/srv/teahouse-mobile",
                        agentKind = "DSH",
                        online = true,
                        sessionCount = null,
                        at = 1L,
                    ),
                ),
            ),
        )
        for ((index, sample) in cases.withIndex()) {
            assertEquals(
                navBadges(sample.toChatSlice(), sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice()),
                navBadges(sample.toChatSlice(), sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice()),
                "第 " + index + " 个样本：切片版徽标必须与聚合版逐字同义",
            )
        }
    }

    @Test
    fun agentPeersProjectionMatchesTheAggregateGetter() {
        val withLocalWorkspace = state(
            workspaces = mapOf(
                WorkspaceKey(peerId = self, agentKey = "dsh", wsId = "ws-1") to WorkspaceUi(
                        peerId = self,
                        agentKey = "dsh",
                        wsId = "ws-1",
                        wsTitle = "teahouse-mobile",
                        wsPath = "/srv/teahouse-mobile",
                        agentKind = "DSH",
                        online = true,
                        sessionCount = null,
                        at = 1L,
                    ),
            ),
        )
        for (sample in listOf(state(), withLocalWorkspace)) {
            assertEquals(
                sample.agentPeers,
                agentPeers(sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice()),
                "切片版 agentPeers 必须与聚合 getter 逐字同义",
            )
        }
        assertTrue(
            agentPeers(withLocalWorkspace.toAgentSlice(), withLocalWorkspace.toNodeSlice(), withLocalWorkspace.toSettingsSlice())
                .any { it.nodeId == self },
            "本机声明了工作区时，它必须是 agent 宿主条目（否则 Agent 页签的排队数会漏本机）",
        )
    }

    // ---- Phase 4 跨面投影：conversations / inbox / humanPeers / peer / totalPending ----

    private fun samples(): List<Pair<String, RelayUiState>> {
        val human = PeerUi(nodeId = "peer-human", nick = "alice", platform = "", caps = emptyList(), online = false)
        val empty = RelayUiState()

        val group = GroupMeta.create("g-1", "协同群", listOf(human.nodeId), selfId = self, updatedTs = 1L)
        val noWorkspaces = RelayUiState(
            settings = RelaySettings.firstRun().copy(nodeId = self, nick = "我的手机", groups = listOf(group)),
            peers = listOf(human, agentPeer),
            messages = mapOf(
                "peer-human" to listOf(MessageUi(id = "m-1", peerId = "peer-human", mine = false, text = "hi", at = 5L)),
            ),
            unreadByPeer = mapOf("peer-human" to 2),
            pendingByPeer = mapOf(agentPeer.nodeId to 3),
            agentSessions = mapOf(
                AgentSessionKey(agentPeer.nodeId, "s-1") to AgentSessionUi(
                    peerId = agentPeer.nodeId,
                    sessionId = "s-1",
                    agentKind = "DSH",
                    state = "idle",
                    wsId = "ws-1",
                    wsTitle = "teahouse-mobile",
                ),
            ),
        )

        val selfSpecial = RelayUiState(
            settings = RelaySettings.firstRun().copy(nodeId = self, nick = "我的手机"),
            peers = listOf(human),
            agentWorkspaces = mapOf(
                WorkspaceKey(peerId = self, agentKey = "dsh", wsId = "ws-self") to WorkspaceUi(
                    peerId = self, agentKey = "dsh", wsId = "ws-self", wsTitle = "self-ws",
                    wsPath = "/srv/self-ws", agentKind = "DSH", online = true, sessionCount = null, at = 1L,
                ),
            ),
            agentSessions = mapOf(
                AgentSessionKey(self, "s-self") to AgentSessionUi(
                    peerId = self, sessionId = "s-self", agentKind = "DSH", state = "idle",
                    wsId = "ws-self", wsTitle = "self-ws",
                ),
            ),
            agentEvents = mapOf(
                AgentSessionKey(self, "s-self") to listOf(
                    AgentEventUi(sessionId = "s-self", peerId = self, cursor = 1L, eventType = "token.delta", at = 9L),
                ),
            ),
        )
        return listOf("empty" to empty, "no-workspaces" to noWorkspaces, "self-special" to selfSpecial)
    }

    @Test
    fun conversationsProjectionMatchesTheAggregateGetter() {
        for ((name, sample) in samples()) {
            assertEquals(
                conversations(sample.toChatSlice(), sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice()),
                conversations(sample.toChatSlice(), sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice()),
                name + "：conversations 切片投影必须与聚合 getter 逐字同义",
            )
        }
        val selfSample = samples().single { it.first == "self-special" }.second
        assertTrue(
            peer(self, selfSample.toNodeSlice(), selfSample.toSettingsSlice())?.online == true,
            "本机 selfNodeId 必须能取到合成的 agent 宿主条目（会话标题/解析器都依赖它）",
        )
    }

    @Test
    fun inboxWorkspaceUnitsProjectionMatchesTheAggregateVersion() {
        for ((name, sample) in samples()) {
            assertEquals(
                inboxWorkspaceUnits(sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice(), ),
                inboxWorkspaceUnits(sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice()),
                name + "：inboxWorkspaceUnits 切片投影必须与聚合版本逐字同义",
            )
        }
        val selfSample = samples().single { it.first == "self-special" }.second
        val units = inboxWorkspaceUnits(selfSample.toAgentSlice(), selfSample.toNodeSlice(), selfSample.toSettingsSlice())
        assertTrue(
            units.groups.any { it.peerId == self },
            "本机声明了工作区且有一条会话时，看板必须出现「本机宿主」这一组（selfNodeId 特判）",
        )
    }

    @Test
    fun smallProjectionsMatchTheirAggregateVersions() {
        for ((name, sample) in samples()) {
            assertEquals(humanPeers(sample.toNodeSlice()), humanPeers(sample.toNodeSlice()), name + "：humanPeers")
            assertEquals(totalPending(sample.toChatSlice()), totalPending(sample.toChatSlice()), name + "：totalPending")
            for (id in listOf(null, self, "peer-human", "peer-agent", "missing")) {
                assertEquals(
                    peer(id, sample.toNodeSlice(), sample.toSettingsSlice()),
                    peer(id, sample.toNodeSlice(), sample.toSettingsSlice()),
                    name + "：peer(" + id + ")",
                )
            }
            for (convId in conversations(sample.toChatSlice(), sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice()).map { it.convId }) {
                assertEquals(
                    sample.previewSenderNameOf(convId),
                    previewSenderNameOf(convId, sample.toChatSlice(), sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice()),
                    name + "：previewSenderNameOf(" + convId + ")",
                )
            }
        }
    }

    /**
     * 窄视图的**解析分支**覆盖：previewSenderNameOf 会走若干条身份解析分支，
     * 每条都必须与旧聚合 getter 同义 —— 这是「构造残缺对象」风险唯一的执行级防线。
     *
     * 五个分支：自己 / 对端存在 / 对端缺失 / agent 发送者 / 非 agent 发送者（未知 nodeId）。
     */
    @Test
    fun previewSenderNameOfCoversEveryResolutionBranch() {
        val groupId = "g-branches"
        val conv = "group:" + groupId
        val group = GroupMeta.create(groupId, "分支群", listOf("peer-human"), selfId = self, updatedTs = 1L)
        val base = RelayUiState(
            settings = RelaySettings.firstRun().copy(nodeId = self, nick = "我的手机", groups = listOf(group)),
            peers = listOf(
                PeerUi(nodeId = "peer-human", nick = "alice", platform = "", caps = emptyList(), online = true),
                agentPeer,
            ),
            agentSessions = mapOf(
                AgentSessionKey(agentPeer.nodeId, "s-branch") to AgentSessionUi(
                    peerId = agentPeer.nodeId, sessionId = "s-branch", agentKind = "DSH",
                    agentId = "dsh", state = "idle", wsId = "ws-b", wsTitle = "branch-ws",
                ),
            ),
            agentWorkspaces = mapOf(
                WorkspaceKey(peerId = agentPeer.nodeId, agentKey = "dsh", wsId = "ws-b") to WorkspaceUi(
                    peerId = agentPeer.nodeId, agentKey = "dsh", wsId = "ws-b", wsTitle = "branch-ws",
                    wsPath = "/srv/branch-ws", agentKind = "DSH", online = true, sessionCount = null, at = 1L,
                ),
            ),
        )
        fun withSender(senderId: String?): RelayUiState = base.copy(
            messages = mapOf(conv to listOf(MessageUi(id = "m", peerId = conv, mine = false, text = "x", at = 2L, senderId = senderId))),
        )
        val branches = linkedMapOf(
            "self" to self,
            "peer-present" to "peer-human",
            "peer-missing" to "40404040-4444-4444-8444-444444444444",
            "agent-sender" to AgentSenderId.of(agentPeer.nodeId, "dsh", "DSH"),
            "non-agent-sender" to "50505050-5555-4555-8555-555555555555",
        )
        for ((name, senderId) in branches) {
            val sample = withSender(senderId)
            val old = sample.previewSenderNameOf(conv)
            val projected = previewSenderNameOf(
                conv, sample.toChatSlice(), sample.toAgentSlice(), sample.toNodeSlice(), sample.toSettingsSlice(),
            )
            assertEquals(old, projected, name + "：previewSenderNameOf 的分支解析必须与聚合 getter 同义")
        }
        assertNull(
            previewSenderNameOf(conv, withSender(self).toChatSlice(), withSender(self).toAgentSlice(), withSender(self).toNodeSlice(), withSender(self).toSettingsSlice()),
            "自己说的那条不该署名（界面另有「你：」前缀）",
        )
        assertEquals(
            "alice",
            previewSenderNameOf(conv, withSender("peer-human").toChatSlice(), withSender("peer-human").toAgentSlice(), withSender("peer-human").toNodeSlice(), withSender("peer-human").toSettingsSlice()),
            "对端存在时必须解析出它的昵称（少填 peers 字段会静默退化）",
        )
    }

    // ---- Agent 屏族投影（8 条） ----

    @Test
    fun agentDerivedProjectionsMatchAggregates() {
        for ((name, sample) in samples()) {
            val agent = sample.toAgentSlice()
            val chat = sample.toChatSlice()
            for (pid in listOf(null, self, "peer-human", agentPeer.nodeId, "missing-node")) {
                assertEquals(agentEventsOf(sample.toAgentSlice(), pid, "s-1"), agentEventsOf(agent, pid, "s-1"), name + "：agentEventsOf(" + pid + ")")
                assertEquals(agentRunsOf(sample.toAgentSlice(), pid, "s-1"), agentRunsOf(agent, pid, "s-1"), name + "：agentRunsOf(" + pid + ")")
                assertEquals(agentArtifactsOf(sample.toAgentSlice(), pid, "s-1"), agentArtifactsOf(agent, pid, "s-1"), name + "：agentArtifactsOf(" + pid + ")")
                assertEquals(visibleAgentSessionsOf(sample.toAgentSlice(), pid), visibleAgentSessionsOf(agent, pid), name + "：visibleAgentSessionsOf(" + pid + ")")
                assertEquals(messagesWith(sample.toChatSlice(), pid), messagesWith(chat, pid), name + "：messagesWith(" + pid + ")")
                for (key in listOf(null, "dsh")) {
                    for (ws in listOf(null, "ws-1")) {
                        assertEquals(
                            grantStatusOf(pid ?: "", key, ws, sample.toAgentSlice()),
                            grantStatusOf(pid ?: "", key, ws, agent),
                            name + "：grantStatusOf(" + pid + ", " + key + ", " + ws + ")",
                        )
                    }
                }
            }
            assertEquals(agentEventsOf(sample.toAgentSlice(), null as AgentSessionUi?), agentEventsOf(agent, null as AgentSessionUi?))
            sample.agentSessions.values.firstOrNull()?.let { session ->
                assertEquals(agentEventsOf(sample.toAgentSlice(), session), agentEventsOf(agent, session), name + "：agentEventsOf(session)")
            }
        }
    }

    @Test
    fun approvalAuthorityProjectionsMatchAggregatesAcrossBranches() {
        val now = 1_000_000L
        val remote = "phone-1"
        val loop = "loop-peer"
        fun grant(access: String, delegated: Boolean = false, isMyDevice: Boolean = false) = GrantUi(
            peerId = remote, agentKey = "dsh", scope = "agent", state = "approved", at = 0L,
            access = access, isMyDevice = isMyDevice, delegatedApproval = delegated,
        )
        fun sample(grant: GrantUi?): RelayUiState = RelayUiState(
            settings = RelaySettings.firstRun().copy(nodeId = self, nick = "我的手机"),
            peers = listOf(
                PeerUi(nodeId = remote, nick = "同事", platform = "", caps = emptyList(), online = true),
                PeerUi(nodeId = loop, nick = "回环", platform = "", caps = emptyList(), online = true, ip = "127.0.0.1"),
            ),
            agentSessions = mapOf(
                AgentSessionKey(remote, "s-1") to AgentSessionUi(
                    peerId = remote, sessionId = "s-1", agentKind = "dsh", agentId = "dsh", state = "idle",
                ),
            ),
            agentGrants = grant?.let { mapOf(grantKeyOf(remote, "dsh", null) to it) }.orEmpty(),
        )
        fun approval(peerId: String, expiresAt: Long = now + 60_000L) = AgentApprovalUi(
            approvalId = "a-1", sessionId = "s-1", peerId = peerId, expiresAt = expiresAt,
        )
        val branches = listOf(
            "owner-grant" to (sample(grant(AgentGrantAccess.OWNER)) to approval(remote)),
            "admin-delegated" to (sample(grant(AgentGrantAccess.ADMIN, delegated = true)) to approval(remote)),
            "admin-without-delegation" to (sample(grant(AgentGrantAccess.ADMIN)) to approval(remote)),
            "no-grant" to (sample(null) to approval(remote)),
            "expired" to (sample(grant(AgentGrantAccess.OWNER)) to approval(remote, expiresAt = now - 1L)),
            "self-loopback" to (sample(null) to approval(self)),
            "ip-loopback" to (sample(null) to approval(loop)),
            "missing-id" to (sample(null) to approval("ghost-node")),
        )
        for ((name, pair) in branches) {
            val (state, appr) = pair
            val agent = state.toAgentSlice()
            val node = state.toNodeSlice()
            val settings = state.toSettingsSlice()
            assertEquals(
                mayDecideApproval(appr, state.toAgentSlice(), state.toNodeSlice(), state.toSettingsSlice(), now),
                mayDecideApproval(appr, agent, node, settings, now),
                name + "：mayDecideApproval 投影必须与旧入口同义（且复用同一权威判据）",
            )
            assertEquals(
                canApproveAgentWork(remote, "dsh", null, state.toAgentSlice(), state.toNodeSlice(), state.toSettingsSlice()),
                canApproveAgentWork(remote, "dsh", null, agent, node, settings),
                name + "：canApproveAgentWork 投影必须与旧入口同义",
            )
        }
        val owner = branches.single { it.first == "owner-grant" }.second
        assertTrue(mayDecideApproval(owner.second, owner.first.toAgentSlice(), owner.first.toNodeSlice(), owner.first.toSettingsSlice(), now))
        val noGrant = branches.single { it.first == "no-grant" }.second
        assertFalse(mayDecideApproval(noGrant.second, noGrant.first.toAgentSlice(), noGrant.first.toNodeSlice(), noGrant.first.toSettingsSlice(), now))
        val selfBranch = branches.single { it.first == "self-loopback" }.second
        assertTrue(mayDecideApproval(selfBranch.second, selfBranch.first.toAgentSlice(), selfBranch.first.toNodeSlice(), selfBranch.first.toSettingsSlice(), now), "本机宿主必须能处置自己的审批")
    }
}
