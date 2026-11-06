// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.canApproveAgentRequest
import com.kapp.tenon.core.protocol.mayDecideAgentApproval
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.AgentArtifactUi
import com.kapp.tenon.domain.model.AgentEventUi
import com.kapp.tenon.domain.model.AgentRunUi
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.GrantRequestStatus
import com.kapp.tenon.domain.model.GrantUi
import com.kapp.tenon.domain.model.grantStateToStatus
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.domain.model.visibleInDsh

/**
 * Agent 屏族的**切片投影**（Phase 4，蓝图 §4.4.3）。
 *
 * 全部是纯函数：只读入参、无副作用、不读时钟。涉及「许可/时效」的两条
 * （[mayDecideApproval] / [canApproveAgentWork]）显式接 `now` 参数 —— 投影内部读
 * `System.currentTimeMillis()` 会让结果不可判定，也会撞 env 门禁。
 *
 * 旧聚合 getter 全部保留 @Deprecated（过渡入口），实现委托到这里。
 */

/** 生效授权条目（切片版）：顺序 = 工作区级 → agent 级 → 整台设备。 */
fun grantFor(peerId: String, agentKey: String? = null, wsId: String? = null, agent: AgentSlice): GrantUi? {
    if (wsId != null) {
        agent.agentGrants[grantKeyOf(peerId, agentKey, wsId)]?.let { return it }
    }
    if (agentKey != null) {
        agent.agentGrants[grantKeyOf(peerId, agentKey, null)]?.let { return it }
    }
    return agent.agentGrants[grantKeyOf(peerId, null, null)]
}

/** 某个节点名下**未过滤**的会话，按「最新活动」倒序（切片版）。 */
fun agentSessionsOf(agent: AgentSlice, peerId: String?): List<AgentSessionUi> =
    peerId?.let { id -> agent.agentSessions.filterKeys { it.peerId == id }.values }
        .orEmpty()
        .sortedWith(
            compareByDescending<AgentSessionUi> { session ->
                val key = AgentSessionKey(session.peerId, session.sessionId)
                val lastEventAt = agent.agentEvents[key]?.maxOfOrNull { it.at } ?: 0L
                maxOf(session.sortActivityAt, lastEventAt)
            }.thenByDescending { it.startedAt ?: 0L }
                .thenByDescending { it.sessionId },
        )

/** 某个节点名下**该显示**的会话（DSH 可见性过滤），切片版。 */
fun visibleAgentSessionsOf(agent: AgentSlice, peerId: String?): List<AgentSessionUi> =
    agentSessionsOf(agent, peerId).visibleInDsh()

/** 某个会话的事件流（按 cursor 升序），切片版。 */
fun agentEventsOf(agent: AgentSlice, peerId: String?, sessionId: String?): List<AgentEventUi> =
    if (peerId != null && sessionId != null) {
        agent.agentEvents[AgentSessionKey(peerId, sessionId)].orEmpty()
    } else {
        emptyList()
    }

/** [agentEventsOf] 的重载：直接传会话对象（短的重载委托给长的，只有一份实现）。 */
fun agentEventsOf(agent: AgentSlice, session: AgentSessionUi?): List<AgentEventUi> =
    agentEventsOf(agent, session?.peerId, session?.sessionId)

/** 某个会话的执行状态，最近收到在前（切片版）。 */
fun agentRunsOf(agent: AgentSlice, peerId: String?, sessionId: String?): List<AgentRunUi> =
    if (peerId != null && sessionId != null) {
        agent.agentRuns.values
            .filter { it.peerId == peerId && it.sessionId == sessionId }
            .sortedByDescending { it.updatedAt }
    } else {
        emptyList()
    }

/** 某个会话的产物，最近收到在前（切片版）。 */
fun agentArtifactsOf(agent: AgentSlice, peerId: String?, sessionId: String?): List<AgentArtifactUi> =
    if (peerId != null && sessionId != null) {
        agent.agentArtifacts.values
            .filter { it.peerId == peerId && it.sessionId == sessionId }
            .sortedByDescending { it.at }
    } else {
        emptyList()
    }

/** 某个会话里的消息（切片版）：与 `RelayUiState.messagesWith` 逐字同义。 */
fun messagesWith(chat: ChatSlice, peerId: String?): List<MessageUi> = peerId?.let { chat.messages[it] }.orEmpty()

/** 我对某个节点的申请状态（切片版）：判序与旧实现一致（宿主结论优先于我的等待）。 */
fun grantStatusOf(
    peerId: String,
    agentKey: String? = null,
    wsId: String? = null,
    agent: AgentSlice,
): GrantRequestStatus {
    grantFor(peerId, agentKey, wsId, agent)?.let { return grantStateToStatus(it.state) }
    if (grantKeyOf(peerId, agentKey, wsId) in agent.pendingGrantRequests) return GrantRequestStatus.PENDING
    if (wsId != null && grantKeyOf(peerId, agentKey, null) in agent.pendingGrantRequests) return GrantRequestStatus.PENDING
    return GrantRequestStatus.NONE
}

/**
 * 这条审批能不能处置（切片版）。
 *
 * **判据本体只有一份**：与旧入口一样调用 `:core` 的 [mayDecideAgentApproval]；本函数只负责
 * 把切片里的输入取出来（peer / session / grant / selfNodeId），**不得**就地展开成
 * `isHost || isMyDevice || OWNER || (ADMIN && delegated)` —— 那正是之前修掉的四份手抄。
 */
fun mayDecideApproval(
    approval: AgentApprovalUi,
    agent: AgentSlice,
    node: NodeSlice,
    settingsSlice: SettingsSlice,
    now: Long,
): Boolean {
    val peer = peer(approval.peerId, node, settingsSlice)
    val session = agent.agentSessions[AgentSessionKey(approval.peerId, approval.sessionId)]
    val grant = grantFor(
        peerId = approval.peerId,
        agentKey = session?.agentId ?: session?.agentKind,
        wsId = session?.wsId,
        agent = agent,
    )
    return mayDecideAgentApproval(
        isLocalHost = isLocalHost(peer, settingsSlice),
        isMyDevice = grant?.isMyDevice == true,
        access = grant?.access,
        delegatedApproval = grant?.delegatedApproval == true,
        expiresAt = approval.expiresAt,
        now = now,
    )
}

/** 工作区/会话级的「我有审批权吗」（切片版）：与 [mayDecideApproval] 同一个判据、不带过期。 */
fun canApproveAgentWork(
    peerId: String,
    agentKey: String?,
    wsId: String?,
    agent: AgentSlice,
    node: NodeSlice,
    settingsSlice: SettingsSlice,
): Boolean {
    val peer = peer(peerId, node, settingsSlice)
    val grant = grantFor(peerId, agentKey, wsId, agent)
    return canApproveAgentRequest(
        isLocalHost = isLocalHost(peer, settingsSlice),
        isMyDevice = grant?.isMyDevice == true,
        access = grant?.access,
        delegatedApproval = grant?.delegatedApproval == true,
    )
}

/** 「本机宿主」判据（旧实现里内联在两条判据中，这里是唯一一份）。 */
private fun isLocalHost(peer: PeerUi?, settingsSlice: SettingsSlice): Boolean =
    peer != null && (
        peer.nodeId == settingsSlice.settings.nodeId ||
            peer.ip == "127.0.0.1" ||
            peer.host == "localhost" ||
            peer.host == "127.0.0.1"
        )
