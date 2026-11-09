// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain.model

/**
 * Team Folder 的域层模型（阶段 1 数据侧）。
 *
 * 为什么在 `:app` 而不是 `:composeApp`：团队清单要由**域层**从 `GroupMeta.virtualAgents`
 * 与会话状态算出来，而模块依赖方向是 `composeApp → app`。UI 层只拿结果渲染。
 *
 * 纯数据、零 Compose 依赖（`:domainComposeFreeGate` 守着这条边界）。
 */
data class TeamMemberUi(
    val agentId: String,
    val name: String,
    val role: String,
)

data class TeamFolderUi(
    val convId: String,
    /**
     * 组标识。
     *
     * `[事实]` 线格式里 `VirtualAgentMember` **没有** `pluginId` —— 只有 `agentId`
     * （leader 的档案键）、`hostNodeId`、`name`、`entityType`、`teamMembers`。
     * 所以组标识只能取 leader 的 `agentId`；一个群挂多个团队时，各团队 leader 不同即天然分开。
     */
    val pluginId: String,
    val title: String,
    val members: List<TeamMemberUi> = emptyList(),
    /**
     * 该团队此刻是否在干活。
     *
     * 由域层按 `agent-session.state == BUSY` 聚合（匹配 `(agentId ?: agentKind)`，大小写归一，
     * 且必须同宿主），UI 只画两态、不自己判活。
     */
    val isRunning: Boolean = false,
)

/** 折叠投影的组键：`(convId, pluginId)`，与聚合键同一口径。 */
fun teamGroupKey(convId: String, pluginId: String): String = convId + ":" + pluginId
