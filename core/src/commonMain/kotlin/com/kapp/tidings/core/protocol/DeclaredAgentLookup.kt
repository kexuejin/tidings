// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * 找出一份 `virtualAgents` 里**属于某个发送者身份**的那一条，返回它的 `wsId`。
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
 * 1. `id` 解析出的 host 与声明相同，且 agent 身份相同（走 [sameAgent] 容忍 kind/id 精度差异）；
 * 2. `id` 解析不出 host（老形态 `agent:<kind>`）时，只比 agent 身份。
 *
 * @param senderId `SenderIdentity.id`。
 * @param declaredAgents 群元数据里的 `virtualAgents`。
 * @param hostNodeId 只认这一台宿主的声明（撤回是机主主权）。
 * @return 该声明的 `wsId`；没匹配到或那条没绑工作区时为 null。
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
    return declaredAgents.orEmpty()
        .firstOrNull { declared ->
            if (declared.hostNodeId != host) return@firstOrNull false
            // 老形态 `agent:<kind>` 没有 host 段：只比 agent 身份。
            if (parsedHost == null) {
                sameAgent(parsedAgent, null, declared.agentId) || declared.name == parsedAgent
            } else {
                parsedHost == declared.hostNodeId &&
                    (sameAgent(parsedAgent, null, declared.agentId) || declared.name == parsedAgent)
            }
        }
        ?.wsId
}
