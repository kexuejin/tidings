// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentSessionStates
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.isTeam
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.TeamFolderUi
import com.kapp.tenon.domain.model.TeamMemberUi

/**
 * 团队清单：从群元数据 + 会话状态算出「每个群有哪些团队、各自在不在跑」。
 *
 * ## 数据源（唯一的两个）
 *
 * - 团队本身：`GroupMeta.virtualAgents[].isTeam` —— 组员清单是事实，`entityType` 只是声明。
 * - 活跃态：`AgentSessionUi.state == AgentSessionStates.BUSY`。**不自造判活层**
 *   （`Process.isAlive` 单判会给出假 Idle：根进程退出后后代过继给 init）。
 *
 * ## 三条容易写错的判据
 *
 * 1. **会话键只能是 `group:<groupId>`**：`team-*` 不带前缀会被 `ConvId.isGroup` 判成私聊，
 *    发送侧拿它当 nodeId 找对端 ⇒ 无目标 ⇒ 静默无帧。
 * 2. **状态匹配必须同宿主**：`peerId == hostNodeId`（大小写归一）。两台宿主各有一个 `coder`
 *    时，按 agentKey 直接匹配会让 A 的忙碌点亮 B 的呼吸点。
 * 3. **agentKey 取 `agentId ?: agentKind` 并 lowercase**：`AgentSenderId.of` 写 senderId 时
 *    会转小写，而台账/声明可能保留原样；不归一就是 fail-closed 的"永远 Idle"。
 *
 * ## 聚合口径
 *
 * 「该 leader 名下**任一**会话 BUSY 即 Running」—— 用 `any` 而不是"取最近一条"：
 * 一条刚结束的旧会话会把正在跑的那条盖掉。没有会话 = Idle（不是"未知"）。
 */
fun teamFoldersByConv(
    groups: List<GroupMeta>,
    sessions: List<AgentSessionUi>,
): Map<String, List<TeamFolderUi>> {
    if (groups.isEmpty()) return emptyMap()

    // (宿主 nodeId → 正在忙的 agentKey 集合)；只收 BUSY，且两边都归一到小写。
    val busyByHost = HashMap<String, MutableSet<String>>()
    for (session in sessions) {
        if (session.state != AgentSessionStates.BUSY) continue
        val key = normalizeAgentKey(session.agentId ?: session.agentKind) ?: continue
        busyByHost.getOrPut(session.peerId.lowercase()) { HashSet() }.add(key)
    }

    val out = LinkedHashMap<String, List<TeamFolderUi>>()
    for (meta in groups) {
        val teams = meta.virtualAgents.orEmpty().filter { it.isTeam }
        if (teams.isEmpty()) continue
        val convId = ConvId.group(meta.groupId)
        out[convId] = teams.map { member ->
            val agentKey = normalizeAgentKey(member.agentId)
            val running = agentKey != null &&
                busyByHost[member.hostNodeId.lowercase()]?.contains(agentKey) == true
            TeamFolderUi(
                convId = convId,
                // 线格式没有 pluginId，组标识只能取 leader 的 agentId（见 TeamFolderUi 的 KDoc）。
                pluginId = member.agentId,
                title = member.name,
                members = member.teamMembers.map {
                    TeamMemberUi(agentId = it.agentId, name = it.name, role = it.role)
                },
                isRunning = running,
            )
        }
    }
    return out
}

private fun normalizeAgentKey(raw: String?): String? =
    raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
