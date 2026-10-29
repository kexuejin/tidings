// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.AgentSenderId
import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.core.protocol.AgentExecAuthorizer.permitsExecution
import com.kapp.tidings.core.protocol.AgentSessionPick
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.mentionedVirtualAgent

/**
 * `/v1/inbox` 的**注入判定附加信息**。
 *
 * ## 职责划分（这个接口只负责第一段）
 *
 * | 层 | 做什么 |
 * |---|---|
 * | 桌面端（实现本接口）| 只提供**事实**：这个群的元数据长什么样 |
 * | [ControlPlane]（消费方）| 用 core 的判据算「@ 到谁」「谁有权执行」 |
 * | `dsh-tidings` | 只消费结论，不重算 |
 *
 * ## 为什么授权判定不在这里做
 *
 * 授权的**权威数据**在宿主侧（`AgentGrantStore`，见它 KDoc 里「投递前的门控（执行点）」），
 * 不在桌面端的 `RelayUiState.grantFor` —— 那是**「我对别人有什么权」**，方向相反。
 * 用反方向的授权表做执行判定，等于「我能看别人」证明「别人能命令我」。
 *
 * 所以本接口**只回群元数据**，判定留给持有权威授权表的那一层。
 */
interface AgentInboxEnricher {

    /**
     * 查这个群（或私聊）的元数据；null = 不知道（不是群 / 还没同步到）。
     *
     * @param peerId 这条消息的会话键（群里是 `group:xxx`）。
     */
    fun groupMetaOf(payload: JsonObjectLike): GroupMeta?
}

/**
 * 载荷的最小视图 —— 只暴露判定需要的东西，避免让 agentNode 依赖整个协议模型。
 */
class JsonObjectLike(
    val text: String?,
    val groupId: String?,
    val agentSessionId: String?,
    val senderId: String?,
)

/**
 * `/v1/inbox` 每条消息的注入判定结果。
 *
 * 判定在这里完成（[ControlPlane] 持有权威授权表），桥接侧只消费。
 */
data class AgentInboxHint(
    /** 这条消息是不是在 @ 本机某个 agent。 */
    val addressedToAgent: Boolean,
    /** @ 到的那个 agent 在本机绑定的工作区；null = 未绑定（一等状态）。 */
    val boundWsId: String? = null,
    /** 用户在文本里显式指定的会话 id（可选）。null = 走默认。 */
    val requestedSessionId: String? = null,
    /** 判定所依据的 agent 名字（仅供诊断）。 */
    val agentName: String? = null,
    /**
     * **投给哪个会话**（Tidings 侧算好，桥接侧直接用）。
     *
     * ## 为什么这个判定在 Tidings 侧而不是桥接侧
     *
     * 「@ 到哪个 agent → 它在哪个工作区 → 该投给哪个会话」是一条**策略**，
     * 与「谁可以执行」同源（都在 core 的 `AgentSessionPick` / `AgentExecAuthorizer`）。
     * 如果让每个 agent 桥接插件自己算，就变成「每接一个新 agent 就重写一遍策略」——
     * 那正是这条重构要消除的东西。
     *
     * 桥接侧的责任因此退化成：拿到 `targetSessionId` → 调自己宿主的 followup。
     *
     * null = 没算出可投的会话（`denyReason` / 空候选 / 有歧义），此时**不投**。
     */
    val targetSessionId: String? = null,
    /**
     * **发送者有没有执行权**。
     *
     * false 时桥接侧应当**在群里回一句为什么**，而不是静默不动：
     * 「没反应」是最难排查的失败态。
     */
    val senderMayExecute: Boolean = false,
    /** 拒绝原因（`MemberOnly` / `NotAMember` 等），仅在 senderMayExecute=false 时有值。 */
    val denyReason: String? = null,
    /**
     * **这条消息是不是 agent 自己说的**（发送者是 `agent:*` 身份）。
     *
     * ## 为什么桥接侧需要它（而不是自己去看 `direction`）
     *
     * 「回声」这件事的判据是**发送者是不是 agent**，不是方向。真机上踩过：
     *
     * | 场景 | direction | 该不该注入 |
     * |---|---|---|
     * | 用户 @ 自己的 agent | `out` | **该** |
     * | agent 自己的回复 | `out` | 不该（回声） |
     * | 别人 @ 你的 agent | `in` | **该** |
     *
     * 桥接侧原先只看 `direction !== 'in'` 就跳过 —— 于是**本机用户 @ 自己的 agent
     * 第一条就被跳过**，症状是「消息发出去了、红米也收到了、但 agent 从来不执行」。
     * 判据放这里（core/Tidings 侧）而不是桥接侧：与 [targetSessionId] 同一条纪律 ——
     * 接新 agent 时不该重写一遍策略。
     */
    val senderIsAgent: Boolean = false,
)

/**
 * 判一条群消息要不要注入、投给哪个 agent、发送者有没有执行权。
 *
 * **三个判据都在 core，这里只是把它们串起来**：
 * - `GroupMeta.mentionedVirtualAgent` —— @ 到谁；
 * - `AgentExecAuthorizer` —— 谁可以执行；
 * - `AgentGrantStore` —— 显式授权那一档（**宿主自己的权威表**）。
 *
 * @param meta 群元数据；null = 不是群。
 * @param senderId **真正的说话者**（`envelope.from`，不是会话键）。
 * @param text 消息正文。
 * @param grantAllows 显式授权查询；null = 没有授权体系（结构身份仍生效）。
 */
fun resolveAgentInboxHint(
    meta: GroupMeta?,
    senderId: String?,
    text: String,
    grantAllows: ((String) -> Boolean)? = null,
    /** 宿主配的执行范围（[com.kapp.tidings.core.protocol.AgentExecPolicies]）；null ⇒ 最严一档。 */
    execPolicy: String? = null,
    /**
     * 这个 agent 的候选会话，**已按活跃度倒序**；每项带自己的 `wsId`（null = 未绑工作区）。
     *
     * 传空列表 = 没有可投的会话（判定会如实返回 null 的 targetSessionId）。
     */
    candidates: List<AgentSessionPick.Candidate> = emptyList(),
    /**
     * 用户**显式点选**的会话 id（载荷的 `agentSessionId`：点着某条 agent 会话消息回复/续聊时带上）。
     * null = 没点选，走默认。
     */
    requestedSessionId: String? = null,
    /** 本机 nodeId —— 判据据此认「我在自己机器上」。null = 不知道，此时不放行本机用户。 */
    selfNodeId: String? = null,
): AgentInboxHint? {
    if (meta == null) return null
    val agents = meta.virtualAgents.orEmpty()
    if (agents.isEmpty()) return null
    // **候选 token 不止 `name`，还要加上 `agentId`。**
    //
    // `name` 是入驻时自动生成的 `agent-workspace`（`dsh-teahouse-mobile`），
    // 而用户直觉打的是 **`@dsh`**。只认 `name` 时 `@dsh` 匹配不到任何条目 ⇒
    // `addressedToAgent=false` ⇒ 不产生 agentHint ⇒ 桥接侧永远看不到这条。
    // 真机现场：`@dsh错误的转发` 的 agentHint 是 null，而 `@dsh-teahouse-mobile 1` 是好的。
    //
    // `agentId` 在入驻时写的是 `DSH`（agentKind 兜底），所以 `@dsh` / `@DSH` 都能对上；
    // `name` 仍然保留（长 token 精确指定某个工作区的 agent）。
    // 匹配函数本身按「从长到短」挑，所以长 token 仍然优先，不会被 `dsh` 抢先。
    val tokens = agents.flatMap { agent ->
        listOfNotNull(agent.name, agent.agentId).filter { it.isNotBlank() }
    }
    val hit = meta.mentionedVirtualAgent(text, tokens) ?: return null
    val verdict = AgentExecAuthorizer.authorize(senderId, meta, grantAllows, execPolicy, selfNodeId)
    // **点选先归一化，判定与回带给桥接侧用同一个值** —— 否则 pick 判的是一份、
    // 桥接收到的是另一份，排查时两个答案对不上。
    val requested = requestedSessionId?.trim()?.takeIf { it.isNotEmpty() }
    // **投给哪个会话也在这里算**（判据在 core，桥接侧只消费）。
    // 只有「@ 到了 agent」且「有会话」时才有值；其余为 null（不投）。
    // 点选过就用点选判：在候选里 ⇒ 精确投它（赢了「最近活跃」）；
    // 不在 ⇒ `RequestedSessionGone`，targetSessionId 为 null（不投）——
    // **绝不静默换投**：用户以为命令进了 A、实际进了 B，比不投更糟。
    val picked = AgentSessionPick.pick(
        boundWsId = hit.wsId,
        candidates = candidates,
        requestedSessionId = requested,
    )
    val targetSessionId = (picked as? AgentSessionPick.Result.Pick)?.sessionId
    // **必须走 `permitsExecution()`，不要在这里另写一遍判据。**
    //
    // 原来写的是「不等于 MemberOnly 且不等于 NotAMember」—— 那是**黑名单**式判断：
    // 除了明确列出的两档，将来新加的档一律被当成「可以执行」。而 core 那边是白名单：
    // `PERMITTING` 只列了三档，它的 KDoc 明说「集中一处，防止将来顺手再多放行一档」。
    //
    // 两者当前答案相同（`Verdict` 只有六个值），但**语义相反**：明天加一个拒绝档，
    // 白名单立刻拒、黑名单静默放行。执行授权这条路上没有「碰巧对了」这种安全。
    val allowed = verdict.permitsExecution()
    return AgentInboxHint(
        addressedToAgent = true,
        boundWsId = hit.wsId,
        // 点选值随行：桥接侧在 RequestedSessionGone（不投）时也拿得到它，
        // 才能说清「你点的会话没了」，而不是一句「没反应」。
        requestedSessionId = requested,
        agentName = hit.name,
        senderMayExecute = allowed,
        senderIsAgent = senderId != null && AgentSenderId.parse(senderId) != null,
        denyReason = if (allowed) null else verdict.name,
        targetSessionId = targetSessionId,
    )
}
