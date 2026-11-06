// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.AgentSessionRegistry
import com.kapp.tenon.domain.model.hasVisibleText
import com.kapp.tenon.core.protocol.AgentSenderId

/**
 * **会话事件 → 聊天气泡**的文案构造（实时与补发共用一份）。
 *
 * 搬迁理由同 `AgentEventForwarder`：原本在 `DesktopExtension.kt` 尾部。这里只有
 * 「一句事件该显示成什么」——纯字符串判定，没有网络、没有台账、没有协程。
 *
 * 两条路共用一份是硬要求（task-59）：此前实时与补发各写一份，补发不传会话标题、
 * 发送者硬编码，同一条事件在两条路下文案与发送者都不一样，用户能看见。
 */

/**
 * 从任务 prompt 派生**会话标题**（divider 上显示的那个）的消毒规则。
 *
 * prompt 不是给人读的标题：首行可能是编排器注入的系统前导（真机 divider 出现过
 * 「You are working strictly inside…」整句英文），也可能带着 @ 提派 token
 * （「@codex-e2e create …」—— 那是路由地址，不是任务内容）。规则：
 * 取首个非空行 → 剥掉行首的 @ token → 超长截断。空 prompt 返回 null，
 * 让 ControlPlane 退回自己的缺省。
 */
fun sessionTitleFor(prompt: String): String? {
    val line = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
    val bare = line.replace(Regex("^(@\\S+\\s+)+"), "").trim()
    val title = bare.ifBlank { line }
    return title.take(80)
}

/** 一条 agent 事件在聊天通道里的最终形态（实时与补发共用的构造产物）。 */
data class AgentChatMessage(
    val text: String,
    val reasoning: String?,
    val isUserPrompt: Boolean,
    val senderId: String,
    /**
     * 这条消息属于哪个 agent 会话（schema 见 `rec`）。
     *
     * 加在**共用构造**里而不是两个调用点各传一次：实时与补发两条路各写一份的话，
     * 迟早有一处漏掉 —— 而漏掉的表现是「实时有分段线、补发没有」，只在断网重连后才看得见。
     */
    val agentSessionId: String? = null,
    /**
     * 该会话的标题 —— 分段线在**对端**显示「任务：xxx」的唯一来源。
     *
     * 标题本来是本机私有数据（`agentSessions` 从没同步过对端），真机上手机侧那张表
     * 是空的 ⇒ 它那边所有分段线只能显示「新会话」。跟着消息走之后单帧自包含。
     */
    val agentSessionTitle: String? = null,
    /** 会话内游标（与 [agentSessionId] 配对）。 */
    val agentCursor: Long? = null,
)

/**
 * 实时与补发**共用的消息构造**（task-59）：文案、思考块、发送者只有这一份实现。
 *
 * 此前两条路各写一份 —— 补发不传会话标题、发送者硬编码 agent:dsh，于是同一条事件在
 * 「实时」与「补发」下**文案与发送者都不一样**（用户可见）。共用之后不可能再漂。
 */
fun agentChatMessageFor(
    rec: AgentSessionRegistry.EventRecord,
    sessionTitle: String?,
    selfNodeId: String,
    agentKind: String?,
    format: (AgentSessionRegistry.EventRecord, String?) -> String?,
    /**
     * 只有思考、没有正文时的替代正文（空串 = 维持旧行为，直接丢弃这条）。
     *
     * 为什么放在**共用构造**里而不是两个调用点各传一次：实时与补发各写一份的话，
     * 迟早有一处漏掉 —— 而漏掉的表现正是「只是那一路没有下文」，只在断网重连后才看得见
     * （与 task-59 b 同一条纪律）。
     */
    thinkingOnlyFallback: String = "",
): AgentChatMessage? {
    // 债#6：没有 kind（且没有 agentId）就没有可署名的身份 —— 不产出发送者，
    // 事件不进群。身份必须来自声明；本地台账照记（recordLocalEvent 在调用方）。
    if (agentKind.isNullOrBlank()) return null
    val eventText = format(rec, sessionTitle) ?: return null
    if (!hasVisibleText(eventText)) return null
    val isUserPrompt = rec.eventType.lowercase() == "user.message"
    val (parsedReasoning, cleanAnswer) = com.kapp.tenon.domain.model.parseThinkingContent(eventText)
    // 只有思考、没有正文：**不许静默丢弃**（真机上这类回合整轮消失过，用户只说「没有下文」）。
    // 思考块留在 reasoning 里（界面上是折叠的「思考」），正文用一句如实的话顶上；
    // 顶替文本取不到（空串）时才退回旧行为。
    val thinkOnly = !hasVisibleText(cleanAnswer)
    val finalAnswer = when {
        !thinkOnly -> cleanAnswer
        parsedReasoning != null && hasVisibleText(thinkingOnlyFallback) -> thinkingOnlyFallback
        else -> return null
    }
    return AgentChatMessage(
        text = finalAnswer,
        reasoning = parsedReasoning,
        isUserPrompt = isUserPrompt,
        // agent 的标签**必须**走 `agentSenderIdFor`（它会把 kind 统一成小写）。
        // 这里原来写的是 `"agent:" + agentKind`，而线上那条路用的是 `agentSenderIdFor` ——
        // 同一个说话者在桌面端落库成 `agent:DSH`、在手机端落库成 `agent:dsh`，
        // 一个 agent 在群里显示成两个人（真机库里两种拼写各 801 / 113 条）。
        senderId = if (isUserPrompt) selfNodeId else agentSenderIdFor(selfNodeId, agentKind),
        // 会话归属：`rec` 就是这条路唯一的事实来源，两条路（实时/补发）都从这里取，
        // 所以不会出现「实时有分段线、补发没有」。
        agentSessionId = rec.sessionId,
        agentSessionTitle = sessionTitle,
        agentCursor = rec.cursor,
    )
}

/**
 * 群消息里表示"这是 agent 说的"的发送者键，形如 `agent:dsh`。
 *
 * 与**桌面端本地落库**（`RelayControllerGroupsComponent` 的 `senderId = "agent:${kind}"`）
 * 同一形态 —— 两端显示同一条气泡时必须得到同一个发送者，否则同一个群在手机上写"桌面端"、
 * 在桌面上写 "DSH"，用户会以为多了一个人。
 *
 * kind 为空时调用链在 `agentChatMessageFor` 已先行 return null，本函数不会以空 kind 到达；
 * core 的 `AgentSenderId.of` 兜底行为只是防御，不代表真实路径。
 */
fun agentSenderIdFor(selfNodeId: String, agentKind: String?, agentId: String? = null): String =
    // 交给 core 的**唯一来源**：它产出 `agent:<hostNodeId>:<agentId>`，并处理长度预算
    // （Limits.ID = 64，留给 agentId 只有 21 个字符）。各写一份已经漂过一次 ——
    // 真机库里 `agent:DSH` 与 `agent:dsh` 并存。
    AgentSenderId.of(selfNodeId, agentId, agentKind)

/**
 * 会话事件 → 聊天气泡的**白名单**（实时与补发共用，策略只有这一份）。
 *
 * 全新显示方案（替代旧的逐类黑名单）：群聊只承载**对话** ——
 * 用户说的话、Agent 的真实文本输出。其余一切（终态、错误、工具、重试……）
 * 都不是消息：
 *
 * - 终态（成功/待合并/受阻/已停止）由**任务卡**原位表达，卡片横幅是唯一的
 *   人话终态面 —— 旧的「✅ …」庆祝气泡与「⚠️ Agent 异常: …」刷屏从构造上消失。
 * - 瞬态自愈（CLI 断线重连 N/M、tls handshake）在成功时**就该无事发生**，
 *   失败时受挫卡一次说清 —— 中间过程一条都不该出声。
 * - 未知事件类型**默认沉默**：黑名单治不了「明天新增的 plumbing 类型」，
 *   白名单才能保证未来的执行细节不会再漏进对话流（会话轨迹仍在台账里可查）。
 */
fun formatAgentEventForChat(rec: AgentSessionRegistry.EventRecord, sessionTitle: String?): String? {
    val evtType = rec.eventType.lowercase()
    return when {
        evtType in listOf("assistant.text", "assistant.message", "text", "message") -> {
            rec.body?.trim()?.takeIf { hasVisibleText(it) }
        }
        // turn 收尾若带正文，就是 Agent 这一轮的收束语 —— 属于对话。
        evtType == "turn.end" -> rec.body?.trim()?.takeIf { hasVisibleText(it) }
        evtType == "user.message" -> {
            rec.body?.trim()?.takeIf { hasVisibleText(it) } ?: rec.summary?.trim()?.takeIf { hasVisibleText(it) }
        }
        else -> null
    }
}
