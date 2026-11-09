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
     * 组标识 = **`"<hostNodeId>:<agentId>"`（宿主限定）**。
     *
     * `[事实]` 线格式里 `VirtualAgentMember` **没有** `pluginId` —— 只有 `agentId`
     * （leader 的档案键）、`hostNodeId`、`name`、`entityType`、`teamMembers`。
     * 曾经只取 `agentId`，而折叠投影拿它拼行 key：两台宿主各挂一个同名 agent
     * （或同一 agent 的两个团队席位）时 key 相撞，LazyColumn 直接崩
     * （`Key was already used`）。校验层保证同一 (host, agentId) 至多一条团队，
     * 所以宿主限定后逐条唯一。它**只**做行 key：显示名走 [title]（leader 的 @ token），
     * 活跃匹配走域层的 agentKey —— 三个用途三个字段，不互相借用。
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
