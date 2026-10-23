// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * 把一个工作区解析成「可入驻的 agent 身份键」。
 *
 * ## 为什么这是一条独立规则
 *
 * 群元数据里的 `virtualAgents` 每条都要一个 `agentId`，而**真实数据里它常常是空的**：
 * 协议把 `agentId` 当可选身份上报，多数宿主只报 `agentKind`（`DSH`）。
 *
 * 原先「入驻」在 `agentId` 为空时直接放弃 —— 界面上点得动、台账里不落任何东西、
 * **没有任何提示**，用户只会觉得「这个按钮没用」。
 *
 * 所以兜底顺序固定成：显式传入 > 工作区自报的 agentId > agentKind。
 * 三条都是同一件事的不同精度（全仓的归行口径一直是 `agentId ?? agentKind`）。
 *
 * @return null = 三条都空，这个工作区没有可用身份（不该入驻）。
 */
fun resolveVirtualAgentKey(
    explicitAgentId: String?,
    workspaceAgentId: String?,
    workspaceAgentKind: String?,
): String? {
    fun clean(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() }
    return clean(explicitAgentId) ?: clean(workspaceAgentId) ?: clean(workspaceAgentKind)
}
