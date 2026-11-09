// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * （宿主, agent 身份）→ **代表席位**：声明表里第一条匹配的那条。
 *
 * ## 为什么要有「代表席位」这个单一判据
 *
 * 一个 agent 可以在多个工作区各有一条声明（真机数据：`dsh-029bbbf3` 有 15 个 ws），
 * 而界面上一个 agent 行只能挂一条「默认」—— 过去「默认」在两处各取各的：
 * `declaredWorkspaceOfSender`（撤回按钮的默认席位）与 `resolveAgentOfHost`（行显示名）
 * 都是 `firstOrNull`，匹配口径还一个容忍 kind/id 精度差异、一个只比精确值 ——
 * 多席位时两处可能落在**不同**条声明上（AGENTS §2.6 那类「同一事实两个说法」）。
 *
 * 现在行显示名、默认席位、团队编制对话框的「默认选中席位」都引用本函数；
 * 源码级守卫（`VirtualAgentDeclarationGuardTest`）钉住两处调用点。
 * 「第一条」不是用户意图 —— 多席位时 UI 必须显式给席位选择（[seatsOfAgent]），
 * 这里只是把「没选的时候看哪条」收敛成一个答案。
 *
 * ## 身份判据（与 `resolveSenderIdentity` 同一条）
 *
 * [sameAgent] 容忍 kind/id 精度差异（`dsh` ~ `dsh-029bbbf3`）+ 名字精确匹配兜底
 * （老数据只认 name）。agentId 的大小写差异由 [sameAgent] 内部归一。
 *
 * @param wantedAgent 行身份里的 agent 键（kind 或 agentId），如 `dsh`。
 */
fun representativeSeat(
    declaredAgents: List<VirtualAgentMember>?,
    hostNodeId: String,
    wantedAgent: String,
): VirtualAgentMember? =
    declaredAgents.orEmpty().firstOrNull { seat -> seatMatchesAgent(seat, hostNodeId, wantedAgent) }

/**
 * （宿主, agent 身份）→ 该 agent 的**全部**席位（按声明表顺序）。
 *
 * 团队编制对话框的席位选择用这份 —— 用户要在多席位间显式挑选，不能替他猜。
 */
fun seatsOfAgent(
    declaredAgents: List<VirtualAgentMember>?,
    hostNodeId: String,
    wantedAgent: String,
): List<VirtualAgentMember> =
    declaredAgents.orEmpty().filter { seat -> seatMatchesAgent(seat, hostNodeId, wantedAgent) }

/** 席位身份匹配的唯一判据：宿主相同，且 agent 身份按 [sameAgent] 或名字命中。 */
private fun seatMatchesAgent(seat: VirtualAgentMember, hostNodeId: String, wantedAgent: String): Boolean =
    seat.hostNodeId == hostNodeId &&
        (sameAgent(wantedAgent, null, seat.agentId) || seat.name == wantedAgent)

/**
 * 找出一份 `virtualAgents` 里**属于某个发送者身份**的那一条，返回它的默认工作区 id。
 *
 * ## 为什么需要它
 *
 * 界面上「撤回本机暴露的 agent」要回答一个问题：**这一行对应哪条声明？**
 * 两边用的身份形态不一样：
 *
 * | 来源 | 形态 |
 * |---|---|
 * | `SenderIdentity.id` | `agent:<host>:<kind>`（如 `agent:46c2…:dsh`） |
 * | `VirtualAgentMember` | `hostNodeId` + `agentId`（如 `46c2…` + `DSH`） |
 *
 * 直接拿 `name`（`dsh-teahouse-mobile`）去比 `id` 永远不等 —— 真机症状就是
 * 「旧 agent 没有移除入口」：按钮压根不画。
 *
 * ## 判定
 *
 * 命中任一即算同一条：
 * 1. `id` 解析出的 host 与声明相同，且 agent 身份相同（走 [representativeSeat] 同一条
 *    身份判据 —— 撤回按钮的「默认席位」与行显示名不再各取各的）；
 * 2. `id` 解析不出 host（老形态 `agent:<kind>`）时，只比 agent 身份。
 *
 * @param senderId `SenderIdentity.id`。
 * @param declaredAgents 群元数据里的 `virtualAgents`。
 * @param hostNodeId 只认这一台宿主的声明（撤回是机主主权）。
 * @return 该声明的 [VirtualAgentMember.defaultWsId]；没匹配到或那条没指定默认工作区时为 null。
 */
fun declaredWorkspaceOfSender(
    senderId: String?,
    declaredAgents: List<VirtualAgentMember>?,
    hostNodeId: String,
): String? {
    val parsed = AgentSenderId.parse(senderId) ?: return null
    val (parsedHost, parsedAgent) = parsed
    val host = hostNodeId.trim()
    if (host.isEmpty()) return null
    // 现代形态（`agent:<host>:<agentId>`）带宿主段：走代表席位这一条判据。
    if (parsedHost != null) {
        if (parsedHost != host) return null
        return representativeSeat(declaredAgents, host, parsedAgent.orEmpty())?.defaultWsId
    }
    // 老形态 `agent:<kind>` 没有 host 段：只比 agent 身份（判据与 [representativeSeat] 同一条）。
    return declaredAgents.orEmpty()
        .firstOrNull { seat ->
            seat.hostNodeId == host &&
                (sameAgent(parsedAgent, null, seat.agentId) || seat.name == parsedAgent)
        }
        ?.defaultWsId
}
