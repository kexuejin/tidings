// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.core.protocol

/**
 * 「谁能处置（批准/拒绝）一条 agent 审批」—— **全仓唯一判据**。
 *
 * ## 为什么它必须只有一份
 *
 * 这条规则同时决定两件事：客户端**画不画**批准按钮，宿主**认不认**那条回执。
 * 两边各写一份的结果不是编译错误，而是「手机上显示只读、看板上却能点」「客户端点了
 * 批准、宿主当场丢掉」这种静默分裂 —— 前者让人以为自己有权，后者让人以为已经批准。
 * 所以它落在 `:core`（客户端与宿主都依赖它），并且**只有这一个实现**：
 * `:app` 的 UI 判据与 `:agentNode` 的 `AgentGrantStore.allows(requireApproval = true)`
 * 都调它，谁都不许再抄一遍。
 *
 * ## fail-closed：缺一项授权信息就是拒绝
 *
 * 四项参数都是「**已确认的事实**」，任何一项拿不到就必须传它最保守的取值
 * （`null` / `false`），而这里对未知一律返回 false：
 *
 * | 参数 | 来源（客户端） | 来源（宿主） |
 * |---|---|---|
 * | [isLocalHost] | 这条审批的对端是不是本机节点（`peer.nodeId == selfNodeId`，或回环地址 `127.0.0.1`/`localhost`）—— 本机宿主对自己的 agent 天然有全部权限 | 宿主进程里恒为 `false`：宿主自己不会给自己发审批，走到 ack 的一定是远端 |
 * | [isMyDevice] | 对端下发的授权台账里这一条（`AgentGrantUi.isMyDevice`）：授权把它标成「我的设备」 | 台账 `AgentGrantStore.Grant.isMyDevice`（同一字段，经 `agent-grant` 帧同步） |
 * | [access] | `AgentGrantUi.access`（`ro`/`rw`/`admin`/`owner`）；**没有授权记录时传 `null`** | `Grant.access`；没有命中任何一条授权时不会走到这里 |
 * | [delegatedApproval] | `AgentGrantUi.delegatedApproval`：ADMIN 是否被委托人代为审批 | `Grant.delegatedApproval`（同一字段） |
 *
 * 规则（与 PPE-1 增补 D7 的分工表一致）：
 * - 本机宿主 ⇒ 允许（它就是在自己机器上）；
 * - `isMyDevice` ⇒ 允许；
 * - `owner` ⇒ 允许；
 * - `admin` **且**开了委托审批 ⇒ 允许；
 * - 其余（含 `ro`/`rw`/未知/没有授权）⇒ **拒绝**。
 *
 * 注意 `rw` 不在这里：能对话/能派活不等于能替宿主批准高危操作 —— 这正是本判据存在的理由。
 */
public fun canApproveAgentRequest(
    isLocalHost: Boolean,
    isMyDevice: Boolean,
    access: String?,
    delegatedApproval: Boolean,
): Boolean = when {
    isLocalHost -> true
    isMyDevice -> true
    access == AgentGrantAccess.OWNER -> true
    access == AgentGrantAccess.ADMIN && delegatedApproval -> true
    // 显式列出，避免"以后加了新 access 档就自动放行"：
    access == null -> false
    access == AgentGrantAccess.RO -> false
    access == AgentGrantAccess.RW -> false
    access == AgentGrantAccess.ADMIN -> false
    else -> false
}

/**
 * 审批的有效期判据 —— 与 [canApproveAgentRequest] 分开，因为它们是两件独立的事
 * （「你有权」与「它还来得及」），但**处置时必须同时成立**，见 [mayDecideAgentApproval]。
 *
 * `expiresAt == 0` 是「对端没声明有效期」，按永不过期处理；这是协议侧的既有约定
 * （`agent-approval` 的 `expiresAt` 允许为 0）。
 */
public fun isAgentApprovalExpired(expiresAt: Long, now: Long): Boolean =
    expiresAt > 0L && now > expiresAt

/**
 * 「这条审批现在还能被处置吗」—— 客户端画按钮、宿主应用回执，都用这一个合成判据。
 *
 * 把两件事合成一个函数，是为了让"过期仍可批准"这类错误**没有落点**：调用方要么问
 * [mayDecideAgentApproval]，要么就没有判据可用（不允许各自 `&&` 一遍）。
 */
public fun mayDecideAgentApproval(
    isLocalHost: Boolean,
    isMyDevice: Boolean,
    access: String?,
    delegatedApproval: Boolean,
    expiresAt: Long,
    now: Long,
): Boolean = !isAgentApprovalExpired(expiresAt, now) &&
    canApproveAgentRequest(isLocalHost, isMyDevice, access, delegatedApproval)
