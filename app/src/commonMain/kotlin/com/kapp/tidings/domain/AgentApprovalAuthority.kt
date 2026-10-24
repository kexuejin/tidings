// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.canApproveAgentRequest
import com.kapp.tidings.core.protocol.mayDecideAgentApproval
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentSessionKey

/**
 * 「这条审批现在能不能处置」—— **客户端唯一入口**。
 *
 * ## 为什么要收成一个函数
 *
 * 这条规则此前在四个地方各写了一遍（看板、工作区详情、会话详情、聊天横幅），而其中
 * **只有看板那一处写错了**（只挡 `RO`）：于是同一个 ADMIN（未开委托审批）在看板上看到
 * 可点的「批准」，打开会话详情却写「只读」。更糟的是点下去真的会发出一条 `agent-ack`——
 * 修复前宿主也不复核，于是越权批准会生效。现在：
 *
 * - 客户端四处只调这个函数；
 * - 宿主（`:agentNode`）的 `AgentGrantStore.allowsApproval` 调 `:core` 的同一个判据；
 * - 两边都 fail-closed：缺授权信息 = 拒绝。
 *
 * ## 输入从哪儿来（与 `:core` 判据的参数一一对应）
 *
 * | 判据参数 | 这里取什么 |
 * |---|---|
 * | `isLocalHost` | 审批的 `peerId` 是不是本机节点（`selfNodeId`）或回环地址 |
 * | `isMyDevice` | `grantFor(peerId, agentKey, wsId).isMyDevice` —— 对端下发的授权台账 |
 * | `access` | 同一条授权的 `access`；**没有任何授权记录时是 null**（⇒ 拒绝，不猜） |
 * | `delegatedApproval` | 同一条授权上的委托审批位（只有 ADMIN 有意义） |
 *
 * `expiresAt` 与 `now` 一并参与判定：**过期与无权是同一件事的两个原因**，都表现为
 * 「这条现在不能点」。拆成两处判，就会出现「无权的不画、过期的还画」这种半对。
 */
public fun RelayUiState.mayDecideApproval(approval: AgentApprovalUi, now: Long): Boolean {
    val peer = peer(approval.peerId)
    val session = agentSessions[AgentSessionKey(approval.peerId, approval.sessionId)]
    val grant = grantFor(
        peerId = approval.peerId,
        agentKey = session?.agentId ?: session?.agentKind,
        wsId = session?.wsId,
    )
    // 本机宿主：审批就是我自己 agent 的事，权限由本机自己说了算（与旧实现同一判据）。
    val isLocalHost = peer != null && (
        peer.nodeId == selfNodeId ||
            peer.ip == "127.0.0.1" ||
            peer.host == "localhost" ||
            peer.host == "127.0.0.1"
        )
    return mayDecideAgentApproval(
        isLocalHost = isLocalHost,
        isMyDevice = grant?.isMyDevice == true,
        access = grant?.access,
        delegatedApproval = grant?.delegatedApproval == true,
        expiresAt = approval.expiresAt,
        now = now,
    )
}

/**
 * 工作区 / 会话级别的「我有审批权吗」—— 与 [mayDecideApproval] 同一个判据，只是不带过期。
 *
 * 为什么另给一个：工作区详情用它决定**权限卡**与「申请授权」的措辞（那是关于"我在这台
 * 设备上是什么角色"），与某一条审批的时限无关。**不许**再就地展开成
 * `isHost || isMyDevice || OWNER || (ADMIN && delegated)` —— 那正是这次修掉的四份拷贝。
 */
public fun RelayUiState.canApproveAgentWork(
    peerId: String,
    agentKey: String?,
    wsId: String?,
): Boolean {
    val peer = peer(peerId)
    val grant = grantFor(peerId, agentKey, wsId)
    val isLocalHost = peer != null && (
        peer.nodeId == selfNodeId ||
            peer.ip == "127.0.0.1" ||
            peer.host == "localhost" ||
            peer.host == "127.0.0.1"
        )
    return canApproveAgentRequest(
        isLocalHost = isLocalHost,
        isMyDevice = grant?.isMyDevice == true,
        access = grant?.access,
        delegatedApproval = grant?.delegatedApproval == true,
    )
}
