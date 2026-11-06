// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.navigation.AppRoutes

/**
 * Phase 4 的**跨面派生投影**（纯函数，见蓝图 §4.4.3）。
 *
 * 有些派生天生跨面：会话行要显示对端昵称/在线态、页签徽标要把会话面与 agent 面的数
 * 拼在一起、agent 宿主条目要同时看节点表与工作区声明。它们**不能**塞进任何一个切片 ——
 * 那会让那个片退化成「改什么都会变」的聚合，切片语义当场失效。
 *
 * 处理方式：输入是切片，输出是渲染模型，函数是纯的（无副作用、不持有状态、不碰 Compose）。
 * 界面用 `remember(sliceA, sliceB, …) { projection(...) }` 组合调用。
 */

/**
 * 节点表里的 agent 条目 + 本机宿主（只有在它声明了工作区时才算 agent 宿主）。
 *
 * 与 [RelayUiState.agentPeers] 逐字同义：来源只有节点表的 `isAgent` 与工作区声明，
 * 先到先得、不合成。
 */
fun agentPeers(agent: AgentSlice, node: NodeSlice, settings: SettingsSlice): List<PeerUi> {
    val selfNodeId = settings.settings.nodeId
    val hostNode = if (agent.agentWorkspaces.keys.any { it.peerId == selfNodeId }) {
        PeerUi(
            nodeId = selfNodeId,
            nick = settings.settings.nick.ifBlank { selfNodeId.take(8) },
            platform = "",
            caps = listOf(MsgTypes.CAP_AGENT, MsgTypes.CAP_AGENT_WORKSPACE),
            online = true,
        )
    } else {
        null
    }
    return node.peers.filter { it.isAgent } + listOfNotNull(hostNode)
}

/**
 * 底栏徽标：会话用未读总数，Agent 用**属于 Agent 的**待处理数。
 *
 * 与 [navBadges] 的聚合版本逐字同义（两个数都为 0 时不放进 map，页签就不画空徽标）。
 */
fun navBadges(
    chat: ChatSlice,
    agent: AgentSlice,
    node: NodeSlice,
    settings: SettingsSlice,
): Map<String, Int> = buildMap {
    val unread = chat.unreadByPeer.values.sum()
    if (unread > 0) put(AppRoutes.CONVERSATIONS, unread)
    val agentPending = agentPeers(agent, node, settings).sumOf { chat.pendingByPeer[it.nodeId] ?: 0 }
    if (agentPending > 0) put(AppRoutes.AGENTS, agentPending)
}
