// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.core.protocol.AgentSenderId
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.Limits
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NoticeDetail
import com.kapp.tidings.domain.model.NoticeKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 跨端派单网道（M4，规格 §6.2 / §6.3 / §7.3-1/2）。
 *
 * ## 它管什么
 *
 * - **派单**：Leader（本机）把子任务派到另一台宿主的插件 —— 走 `agent-invoke` 帧的
 *   P2P 点对点网道，**主群聊一条跨设备交接文本都不产生**；全群只看 Leader 发的
 *   一张汇总看板卡（§6.1「唯一对外发言人」）。
 * - **租约**：每条派单带 `deadlineMs`；目标节点失联 ⇒ 到期由租约表原子翻 Timeout，
 *   看板卡如实显示「子任务超时中断」并带 [重试]/[降级为本地执行] 两个动作（§7.3-1）。
 * - **执行门禁**（§6.3）：入站派单必须过 [AgentExecAuthorizer] —— 可信设备
 *   （RW/OWNER，即台账 `grantAllows`）自动放行；未授权 ⇒ 记 grant 申请并通知机主，
 *   复用既有 grants/approval 机制，不新造开关。
 * - **审批防重**（§7.3-2）：宿主用 [AgentCardActionLedger] 原子处理首个判定，
 *   晚到的并发点击幂等丢弃，结果以 rev+1 的卡片新帧广播全群（`card` 键已在白名单，
 *   不新增协议键）。
 *
 * ## 桥（与 `onAgentPrompt` / `RelayUiTexts` 同一条接缝纪律）
 *
 * 执行器与授权台账都在**壳层**（desktopApp 的 ControlPlane / AgentGrantStore），
 * 领域层拿不到 —— 全部以可空回调注入。没装桥时行为是**诚实的降级**：
 * 被指名派单会答一帧 `no-executor` 拒绝，绝不沉默。
 */
internal class RelayControllerAgentInvokeComponent(private val host: RelayController) {

    // ---------------------------------------------------------------- 桥（壳层注入）

    /**
     * 执行器桥：谁装了它，谁就是能承接派单的宿主。null = 本机没有执行器
     * （手机、未接插件宿主的测试宿主）—— 入站派单如实回 `no-executor`。
     */
    var executor: AgentInvokeExecutor? = null

    /**
     * 授权台账查询（§6.3 的「可信设备」判据）：`(agentKey, nodeId) → 是否已批准`。
     * null = 没有台账可用 ⇒ 只有 agent 宿主用户与群内成员判据兜底（fail-closed 方向）。
     */
    var grantAllows: ((agentKey: String, nodeId: String) -> Boolean)? = null

    /** 每个 agent 的执行范围（[AgentExecPolicies] 取值）；null = 缺省最严档。 */
    var execPolicyOf: ((agentKey: String) -> String?)? = null

    /**
     * 未授权派单的**机主裁决入口**（复用 grants）：记录申请 + 通知机主。
     * 由 desktopApp 接到 `AgentGrantStore.recordRequest` 与通知上；null = 只回拒绝帧。
     */
    var recordGrantRequest: ((nodeId: String, agentKey: String?, reason: String) -> Unit)? = null

    /**
     * 宿主侧卡片动作出口（任务 5 的真通路）：`(agentId, sessionId?, cardId, actionId,
     * decidedBy) → Unit`。desktopApp 接到 ControlPlane 的 `/v1/plugin/action`
     * （进插件 `handleAction`）；null = 本机不是插件宿主，动作只记账不上插件。
     */
    var onHostCardAction: ((agentId: String, sessionId: String?, cardId: String, actionId: String, decidedBy: String) -> Unit)? =
        null

    // ---------------------------------------------------------------- 租约表

    private val leases = AgentInvokeLeaseTable { nowMillis() }

    /** 审批防重台账（§7.3-2）：键 = `(cardId, actionId)`，首个 agent-ack 原子生效。 */
    internal val actionLedger = AgentCardActionLedger()

    /** 从落库的卡片行回填判定台账（重启后「已由 X 处理」不许凭空消失）。 */
    fun rebuildActionLedger() {
        actionLedger.rebuildFrom(host._state.value.messages.values.flatten().mapNotNull { it.card })
    }

    // ---------------------------------------------------------------- 派单（发起方）

    /**
     * 把一个子任务派给 [agentId] 的宿主（跨端或本机）。
     *
     * @return invokeId；null = 群里没有这个 agent 的声明（派单无从谈起）。
     * 目标不接派单（不声明 `agi1`）**不**在这里拦 —— 派单照发、租约照走：
     * 旧端会 ACK 后丢弃，租约到期给出如实的超时卡，比发送侧静默猜强。
     */
    fun dispatchCrossHostInvoke(
        groupId: String,
        agentId: String,
        prompt: String,
        wsId: String?,
        deadlineMs: Long = DEFAULT_INVOKE_DEADLINE_MS,
    ): String? {
        val st = host._state.value
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return null
        val seat = meta.virtualAgents.orEmpty().firstOrNull { it.agentId == agentId } ?: return null
        val self = st.selfNodeId
        val rt = host.runtime ?: return null
        val invokeId = newId()

        if (seat.hostNodeId == self) {
            // 目标就是本机：不过网道，直接本机执行（卡片照常进群 —— 这不是跨端交接）。
            leases.register(invokeId, groupId, agentId, seat.name, self, prompt, wsId, deadlineMs)
            mirrorLeases()
            host.scope.launch {
                executeLocallyAndBroadcast(invokeId, groupId, agentId, seat, prompt, wsId, requester = self)
            }
            return invokeId
        }

        // 跨端派单：登记租约（先记账再发帧 —— 发送失败也有一条可超时的租约，而不是无声消失）。
        leases.register(invokeId, groupId, agentId, seat.name, seat.hostNodeId, prompt, wsId, deadlineMs)
        mirrorLeases()
        host.scope.launch {
            runCatching {
                rt.sendAgentInvoke(
                    peerId = seat.hostNodeId,
                    invokeId = invokeId,
                    op = com.kapp.tidings.core.protocol.CodecInvoke.InvokeOps.TASK,
                    agentId = agentId,
                    requestedBy = self,
                    deadlineMs = deadlineMs,
                    prompt = prompt,
                    groupId = groupId,
                    groupRev = meta.rev,
                    wsId = wsId,
                )
            }
        }
        return invokeId
    }

    /** 入站结果帧（B → A）：租约落终态 + Leader 拼看板卡上群（§6.2 的唯一一张卡）。 */
    fun handleOnAgentInvokeResult(envelope: Envelope) {
        val p = envelope.payload as? JsonObject ?: return
        val invokeId = p.strField("invokeId") ?: return
        val ok = (p["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return
        val status = p.strField("status") ?: return
        val summary = p.strField("summary")
        val error = p.strField("error")
        val workerCard = p["card"] as? JsonObject
        val lease = when {
            ok -> leases.markDone(invokeId, summary, error)
            else -> leases.markFailed(invokeId, status, summary, error)
        } ?: return
        // 结果必须来自当初的承接节点：别的节点（或重放）冒名报结果，直接忽略。
        if (envelope.from != lease.targetNode) return
        mirrorLeases()
        host.scope.launch {
            broadcastBoardCard(
                groupId = lease.groupId,
                agentId = lease.agentId,
                card = buildInvokeBoardCard(
                    invokeId = lease.invokeId,
                    prompt = lease.prompt,
                    agentName = lease.agentName,
                    state = lease.state,
                    timeoutTitle = timeoutTitleOf(lease),
                    retryLabel = retryLabel(),
                    fallbackLabel = fallbackLabel(),
                    summary = summary,
                    error = error,
                    workerCard = workerCard,
                ),
            )
        }
    }

    /** 入站派单帧（A → B）：授权门禁（§6.3）→ 本机执行 → 结果帧回传。 */
    fun handleOnAgentInvoke(envelope: Envelope) {
        val p = envelope.payload as? JsonObject ?: return
        val invokeId = p.strField("invokeId") ?: return
        val op = p.strField("op") ?: return
        when (op) {
            com.kapp.tidings.core.protocol.CodecInvoke.InvokeOps.TASK ->
                host.scope.launch { handleInvokeTask(envelope, p, invokeId) }
            com.kapp.tidings.core.protocol.CodecInvoke.InvokeOps.ACTION ->
                host.scope.launch { handleInvokeAction(envelope, p, invokeId) }
        }
    }

    private suspend fun handleInvokeTask(envelope: Envelope, p: JsonObject, invokeId: String) {
        val agentId = p.strField("agentId") ?: return
        val prompt = p.strField("prompt").orEmpty()
        val requestedBy = p.strField("requestedBy") ?: return
        val groupId = p.strField("groupId")
        val wsId = p.strField("wsId")
        val self = host._state.value.selfNodeId
        val rt = host.runtime

        fun reply(ok: Boolean, status: String, summary: String? = null, error: String? = null, card: JsonObject? = null) {
            val peer = envelope.from
            if (peer.isEmpty()) return
            host.scope.launch {
                runCatching {
                    rt?.sendAgentInvokeResult(peer, invokeId, ok, status, summary, error, card)
                }
            }
        }

        if (rt == null) {
            reply(false, com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.NO_EXECUTOR)
            return
        }
        val meta = groupId?.let { g -> host._state.value.settings.groups.firstOrNull { it.groupId == g } }
        if (meta == null || requestedBy !in meta.members) {
            // 授权的前置门槛是成员身份（AgentExecAuthorizer 同一条）：不是群成员的请求一律拒。
            reply(false, com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.DENIED, error = "not a member of the group")
            return
        }
        val seat = meta.virtualAgents.orEmpty().firstOrNull { it.agentId == agentId }
        if (seat == null || seat.hostNodeId != self) {
            reply(false, com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.NO_PLUGIN, error = "agent is not hosted here")
            return
        }
        val verdict = AgentExecAuthorizer.authorize(
            senderId = requestedBy,
            group = meta,
            grantAllows = { node -> grantAllows?.invoke(agentId, node) == true },
            policy = execPolicyOf?.invoke(agentId),
            selfNodeId = self,
        )
        if (!with(AgentExecAuthorizer) { verdict.permitsExecution() }) {
            // 未授权 ⇒ 通知目标机主裁决（复用 grants）：记申请 + 通知，拒绝帧如实回发起方。
            recordGrantRequest?.invoke(requestedBy, agentId, "cross-host invoke")
            reply(false, com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.DENIED, error = "execution not authorized")
            return
        }
        val ex = executor
        if (ex == null) {
            reply(false, com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.NO_EXECUTOR)
            return
        }
        val outcome = try {
            ex.execute(AgentInvokeTaskRequest(agentId = agentId, prompt = prompt, wsId = wsId, groupId = groupId))
        } catch (t: Throwable) {
            reply(false, com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.ERROR, error = (t.message ?: "executor failed").take(Limits.AGENT_NOTE))
            return
        }
        reply(
            ok = outcome.status == com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.DONE,
            status = outcome.status,
            summary = outcome.summary,
            error = outcome.error,
            card = outcome.card,
        )
    }

    // ---------------------------------------------------------------- 本机 @agent 回路

    /**
     * 入站群文本带了 `agentIntent` 且目标 agent 由**本机**托管 ⇒ 本机执行、卡片进群。
     * 目标在别的宿主上时不做任何事：那条群消息按群协议已经送达目标设备，
     * 由它自己的这条路径接管（谁托管谁执行，与 onAgentPrompt 的路由判据同一条）。
     *
     * @param messageText 那条群文本的正文（调用方从信封取）——意图帧只带**目标**
     *   不带任务文本，任务话在消息里；剥掉 @ token 的剩余部分就是给执行器的 prompt。
     *   真机现场：不传它，插件收到的是空串 —— 执行了、卡片也进了群，但任务话丢了。
     */
    fun handleInboundAgentIntent(groupId: String, senderNode: String, intent: JsonObject?, messageText: String? = null) {
        if (intent == null) return
        val agentId = intent.strField("agentId")?.takeIf { it.isNotBlank() } ?: return
        val wsId = intent.strField("wsId")?.takeIf { it.isNotBlank() }
        val st = host._state.value
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return
        val self = st.selfNodeId
        val seat = meta.virtualAgents.orEmpty().firstOrNull { it.agentId == agentId } ?: return
        if (seat.hostNodeId != self) return
        val verdict = AgentExecAuthorizer.authorize(
            senderId = senderNode,
            group = meta,
            grantAllows = { node -> grantAllows?.invoke(agentId, node) == true },
            policy = execPolicyOf?.invoke(agentId),
            selfNodeId = self,
        )
        if (!with(AgentExecAuthorizer) { verdict.permitsExecution() }) {
            host.handleAppendMessage(
                MessageUi(
                    id = host.localMessageId(),
                    peerId = ConvId.group(groupId),
                    mine = false,
                    text = "",
                    at = nowMillis(),
                    notice = NoticeKind.AgentNotAuthorized,
                    noticeDetail = NoticeDetail(actor = seat.name),
                ),
            )
            return
        }
        val ex = executor ?: return
        val tokens = setOfNotNull(seat.agentId.takeIf { it.isNotBlank() }, seat.name.takeIf { it.isNotBlank() })
        val prompt = intent.strField("prompt")
            ?: messageText?.let { RelayController.extractAgentPrompt(it, tokens) }
            ?: ""
        host.scope.launch {
            runCatching {
                ex.execute(AgentInvokeTaskRequest(agentId = agentId, prompt = prompt, wsId = wsId, groupId = groupId))
            }.onSuccess { outcome ->
                outcome.card?.let { broadcastBoardCard(groupId, agentId, it) }
            }
        }
    }

    /** 本机执行（派单目标 = 自己）：终态翻租约、卡片进群。 */
    private suspend fun executeLocallyAndBroadcast(
        invokeId: String,
        groupId: String,
        agentId: String,
        seat: VirtualAgentMember,
        prompt: String,
        wsId: String?,
        requester: String,
    ) {
        val ex = executor
        if (ex == null) {
            leases.markFailed(invokeId, com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.NO_EXECUTOR, null, "no local executor")
            mirrorLeases()
            return
        }
        val outcome = try {
            ex.execute(AgentInvokeTaskRequest(agentId = agentId, prompt = prompt, wsId = wsId, groupId = groupId))
        } catch (t: Throwable) {
            leases.markFailed(
                invokeId, com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.ERROR, null,
                (t.message ?: "executor failed").take(Limits.AGENT_NOTE),
            )
            mirrorLeases()
            null
        } ?: return
        val done = outcome.status == com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.DONE
        if (done) leases.markDone(invokeId, outcome.summary, outcome.error)
        else leases.markFailed(invokeId, outcome.status, outcome.summary, outcome.error)
        mirrorLeases()
        outcome.card?.let { broadcastBoardCard(groupId, agentId, it) }
    }

    // ---------------------------------------------------------------- 租约 ticker（§7.3-1）

    /** 租约巡检协程；由 [RelayController.start] 挂进 jobs（stop 随之取消）。 */
    fun startLeaseTicker() {
        host.jobs += host.scope.launch {
            while (isActive) {
                delay(LEASE_TICK_MS)
                tickTimeouts()
            }
        }
    }

    private suspend fun tickTimeouts() {
        val flipped = leases.markTimeoutDue()
        if (flipped.isEmpty()) return
        mirrorLeases()
        for (lease in flipped) {
            broadcastBoardCard(
                groupId = lease.groupId,
                agentId = lease.agentId,
                card = buildInvokeBoardCard(
                    invokeId = lease.invokeId,
                    prompt = lease.prompt,
                    agentName = lease.agentName,
                    state = InvokeLeaseStates.TIMEOUT,
                    timeoutTitle = timeoutTitleOf(lease),
                    retryLabel = retryLabel(),
                    fallbackLabel = fallbackLabel(),
                    summary = null,
                    error = null,
                    workerCard = null,
                ),
            )
        }
        leases.pruneTerminalOlderThan(LEASE_TTL_MS)
    }

    private suspend fun timeoutTitleOf(lease: AgentInvokeLease): String =
        runCatching { host.uiTexts.invokeTimeoutTitle(lease.agentName) }.getOrNull().orEmpty()

    private suspend fun retryLabel(): String =
        runCatching { host.uiTexts.invokeActionRetryLabel() }.getOrNull().orEmpty()

    private suspend fun fallbackLabel(): String =
        runCatching { host.uiTexts.invokeActionFallbackLabel() }.getOrNull().orEmpty()

    private fun mirrorLeases() {
        val snap = leases.snapshot().associateBy { it.invokeId }
        host._state.update { it.copy(agentInvokes = snap) }
    }

    // ---------------------------------------------------------------- 看板卡广播

    /**
     * 把一张看板卡以 agent 署名的群文本发进群（`card` 键已在白名单，不新增协议键）。
     * 本机落库与线上同帧同 id —— 补发与去重都按信封 id 对账。
     */
    internal suspend fun broadcastBoardCard(groupId: String, agentId: String, card: JsonObject) {
        val st = host._state.value
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return
        if (st.selfNodeId !in meta.members) return
        val rt = host.runtime ?: return
        val title = card.strField("title").orEmpty().ifBlank { card.strField("cardId").orEmpty() }
        if (title.isBlank()) return
        val envelopeId = newId()
        host.handleAppendMessage(
            MessageUi(
                id = envelopeId,
                peerId = ConvId.group(groupId),
                mine = false,
                text = title,
                at = nowMillis(),
                delivery = null,
                senderId = AgentSenderId.of(st.selfNodeId, agentId),
                card = card,
            ),
            incoming = true,
        )
        runCatching {
            rt.sendGroupText(
                text = title,
                groupId = groupId,
                groupRev = meta.rev,
                members = meta.members,
                senderId = AgentSenderId.of(st.selfNodeId, agentId),
                envelopeId = envelopeId,
                card = card,
            )
        }
    }

    // ---------------------------------------------------------------- 卡片动作（任务 4/5）

    /**
     * 卡片动作的领域入口（两壳同源，参数接原语）：
     * 本机是卡片宿主 → 原子记账 + 插件 + 广播；宿主在别处 → `op=action` 的 invoke 帧直达。
     * 控制意图**绝不**拼成群文本（§8 的零中间文本判据同样管动作）。
     */
    fun handleCardAction(groupId: String, cardId: String, cardRev: Int, actionId: String) {
        val st = host._state.value
        val self = st.selfNodeId
        val convId = ConvId.group(groupId)
        val cardRow = st.messages[convId].orEmpty()
            .lastOrNull { row ->
                (row.card?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId
            } ?: return
        val (hostNode, agentId) = AgentSenderId.parse(cardRow.senderId) ?: return
        if (hostNode == null || agentId == null) return
        if (hostNode == self) {
            handleHostCardAction(groupId, agentId, cardId, actionId, decidedBy = self)
        } else {
            val rt = host.runtime ?: return
            host.scope.launch {
                runCatching {
                    rt.sendAgentInvoke(
                        peerId = hostNode,
                        invokeId = newId(),
                        op = com.kapp.tidings.core.protocol.CodecInvoke.InvokeOps.ACTION,
                        agentId = agentId,
                        requestedBy = self,
                        deadlineMs = ACTION_INVOKE_DEADLINE_MS,
                        actionId = actionId,
                        cardId = cardId,
                        cardRev = cardRev,
                        decidedBy = self,
                        groupId = groupId,
                    )
                }
            }
        }
    }

    /** 入站 `op=action`：决定人必须与发送节点同源，然后走与本地点击同一条宿主处理。 */
    private fun handleInvokeAction(envelope: Envelope, p: JsonObject, invokeId: String) {
        val agentId = p.strField("agentId") ?: return
        val cardId = p.strField("cardId") ?: return
        val actionId = p.strField("actionId") ?: return
        val decidedBy = p.strField("decidedBy") ?: return
        val groupId = p.strField("groupId") ?: return
        // 冒名判定：说「X 批了」的帧必须由 X 自己的节点发出 —— 否则任何人都能替全群批准。
        if (decidedBy != envelope.from) return
        handleHostCardAction(groupId, agentId, cardId, actionId, decidedBy)
        // 动作帧不需要逐帧回执：全群广播的那张 rev+1 卡就是结果（§7.3-2）。
        run { invokeId }
    }

    /**
     * 宿主侧动作处理：**原子**吃下首个判定（§7.3-2），转发插件，rev+1 新帧广播全群。
     * 晚到的并发点击在这里被幂等丢弃 —— 各端按钮的最终一致由广播保证。
     */
    internal fun handleHostCardAction(groupId: String, agentId: String, cardId: String, actionId: String, decidedBy: String) {
        val won = actionLedger.firstWin(cardId, actionId, decidedBy)
        if (!won) return
        // 看板卡的网格级动作（重试 / 降级）不走插件：它们是 Leader 自己的命令。
        when (actionId) {
            InvokeBoardActions.RETRY -> {
                val lease = leases.snapshot().firstOrNull { boardCardIdOf(it.invokeId) == cardId }
                if (lease != null) {
                    broadcastDecision(cardId, actionId, groupId, agentId, decidedBy)
                    dispatchCrossHostInvoke(lease.groupId, lease.agentId, lease.prompt, lease.wsId)
                    return
                }
            }
            InvokeBoardActions.FALLBACK_LOCAL -> {
                val lease = leases.snapshot().firstOrNull { boardCardIdOf(it.invokeId) == cardId }
                if (lease != null) {
                    broadcastDecision(cardId, actionId, groupId, agentId, decidedBy)
                    host.scope.launch {
                        executeLocallyAndBroadcast(
                            invokeId = lease.invokeId + "-local",
                            groupId = lease.groupId,
                            agentId = lease.agentId,
                            seat = VirtualAgentMember(agentId = lease.agentId, hostNodeId = host._state.value.selfNodeId, name = lease.agentName),
                            prompt = lease.prompt,
                            wsId = lease.wsId,
                            requester = decidedBy,
                        )
                    }
                    return
                }
            }
        }
        onHostCardAction?.invoke(agentId, null, cardId, actionId, decidedBy)
        broadcastDecision(cardId, actionId, groupId, agentId, decidedBy)
    }

    /**
     * 判定结果的广播：**同 cardId、rev+1 的新帧**（原位刷新的存储语义，M1 定死：
     * 追加式新行，不重写旧行）。决定人显示名用群成员昵称 —— 群里的人都认得昵称，
     * nodeId 只在昵称缺失时兜底。
     */
    private fun broadcastDecision(cardId: String, actionId: String, groupId: String, agentId: String, decidedBy: String) {
        val st = host._state.value
        val convId = ConvId.group(groupId)
        val latest = st.messages[convId].orEmpty()
            .lastOrNull { row -> (row.card?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId }
            ?.card ?: return
        val currentRev = (latest["rev"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId }
        val displayName = meta?.memberNicks?.get(decidedBy)?.takeIf { it.isNotBlank() } ?: decidedBy
        val next = JsonObject(
            latest.toMap() +
                ("rev" to JsonPrimitive(currentRev + 1)) +
                ("decidedBy" to JsonPrimitive(displayName)) +
                ("decidedActionId" to JsonPrimitive(actionId)),
        )
        host.scope.launch { broadcastBoardCard(groupId, agentId, next) }
    }

    private fun boardCardIdOf(invokeId: String): String = "invoke-" + invokeId

    companion object {
        /** 派单租约的缺省期限。给足一个真实任务的工作窗口；调用方可按子任务改小。 */
        const val DEFAULT_INVOKE_DEADLINE_MS: Long = 10L * 60_000

        /** 卡片动作请求的期限：动作必须立刻被处理，等不了任务那个量级。 */
        const val ACTION_INVOKE_DEADLINE_MS: Long = 15_000

        /** 租约巡检间隔。1s 的粒度让超时卡的迟到上限可预期（deadline + 1s）。 */
        const val LEASE_TICK_MS: Long = 1_000L

        /** 终态租约的保留时长：超时卡随时可能被点重试，太早清掉就没法重派。 */
        const val LEASE_TTL_MS: Long = 60L * 60_000
    }
}

// ---------------------------------------------------------------- 纯件（可独立测）

/** 租约状态。终态：done / timeout / denied / no-executor / no-plugin / error。 */
object InvokeLeaseStates {
    const val RUNNING = "running"
    const val DONE = "done"
    const val TIMEOUT = "timeout"
    const val DENIED = "denied"
    const val NO_EXECUTOR = "no-executor"
    const val NO_PLUGIN = "no-plugin"
    const val ERROR = "error"
    val TERMINAL: Set<String> = setOf(DONE, TIMEOUT, DENIED, NO_EXECUTOR, NO_PLUGIN, ERROR)
}

/** 看板卡上 Leader 自有动作的 actionId（超时卡的两个按钮）。 */
object InvokeBoardActions {
    const val RETRY = "invoke-retry"
    const val FALLBACK_LOCAL = "invoke-fallback-local"
}

/** 一条跨端派单的租约（发起方视角）。落进 [RelayUiState.agentInvokes]，界面与测试都能读。 */
data class AgentInvokeLease(
    val invokeId: String,
    val groupId: String,
    val agentId: String,
    val agentName: String,
    val targetNode: String,
    val prompt: String,
    val wsId: String?,
    val state: String,
    val createdAt: Long,
    val deadlineAt: Long,
    val finishedAt: Long? = null,
    val summary: String? = null,
    val error: String? = null,
)

/**
 * 租约台账：**终态翻转是原子的**。结果帧与超时巡检并发竞争同一条租约时，
 * 只有一方赢（`compute` 按键加锁）—— 「已完成的任务被改判超时」与
 * 「超时后又报成功」都是谎，两者都进不了台账。
 *
 * 状态的唯一事实来源是这张表；[RelayUiState.agentInvokes] 是它的镜像（UI 读）。
 *
 * @param now 可注入时钟（测试毫秒级验证期限；生产用墙钟）。
 */
internal class AgentInvokeLeaseTable(private val now: () -> Long) {

    private val rows = ConcurrentHashMap<String, AgentInvokeLease>()

    fun register(
        invokeId: String,
        groupId: String,
        agentId: String,
        agentName: String,
        targetNode: String,
        prompt: String,
        wsId: String?,
        deadlineMs: Long,
    ) {
        val start = now()
        rows[invokeId] = AgentInvokeLease(
            invokeId = invokeId, groupId = groupId, agentId = agentId, agentName = agentName,
            targetNode = targetNode, prompt = prompt, wsId = wsId,
            state = InvokeLeaseStates.RUNNING, createdAt = start, deadlineAt = start + deadlineMs,
        )
    }

    /** 结果帧落终态（成功）。租约不存在或已终态 ⇒ null（幂等，迟到的结果被丢弃）。 */
    fun markDone(invokeId: String, summary: String?, error: String?): AgentInvokeLease? =
        transition(invokeId, InvokeLeaseStates.DONE, summary, error)

    /** 结果帧落终态（失败档，status 是对端如实报的取值）。 */
    fun markFailed(invokeId: String, status: String, summary: String?, error: String?): AgentInvokeLease? {
        val target = if (status in InvokeLeaseStates.TERMINAL) status else InvokeLeaseStates.ERROR
        return transition(invokeId, target, summary, error)
    }

    /**
     * 到期巡检：把所有过期的 RUNNING 租约原子翻 TIMEOUT。
     * @return 本轮翻掉的租约（调用方据此各发一张超时卡）。
     */
    fun markTimeoutDue(): List<AgentInvokeLease> {
        val t = now()
        val flipped = mutableListOf<AgentInvokeLease>()
        for ((id, lease) in rows) {
            if (lease.state != InvokeLeaseStates.RUNNING || t < lease.deadlineAt) continue
            var won: AgentInvokeLease? = null
            rows.compute(id) { _, current ->
                if (current == null || current.state != InvokeLeaseStates.RUNNING) return@compute current
                won = current.copy(state = InvokeLeaseStates.TIMEOUT, finishedAt = t)
                won
            }
            won?.let { flipped += it }
        }
        return flipped
    }

    fun snapshot(): Collection<AgentInvokeLease> = rows.values.toList()

    /** 只清**终态且过老**的租约；RUNNING 的不许动（清了就没有超时判定）。 */
    fun pruneTerminalOlderThan(maxAgeMs: Long) {
        val t = now()
        rows.entries.removeIf { (_, lease) ->
            lease.state in InvokeLeaseStates.TERMINAL &&
                (lease.finishedAt ?: lease.createdAt) + maxAgeMs < t
        }
    }

    private fun transition(invokeId: String, toState: String, summary: String?, error: String?): AgentInvokeLease? {
        var won: AgentInvokeLease? = null
        rows.compute(invokeId) { _, current ->
            if (current == null || current.state != InvokeLeaseStates.RUNNING) return@compute current
            won = current.copy(state = toState, finishedAt = now(), summary = summary, error = error)
            won
        }
        return won
    }
}

/**
 * 宿主侧的审批防重台账（§7.3-2 Atomic Action Lock）。
 *
 * 判定的生效是 [ConcurrentHashMap.putIfAbsent] 的原子性：跨设备的多个并发
 * `agent-ack` 里**只有第一个**返回 true，其余被幂等丢弃。台账在**宿主**这一侧 ——
 * 全群只认宿主广播的那张 rev+1 卡，本地乐观置灰只是界面礼貌，不是一致性来源。
 */
internal class AgentCardActionLedger {

    private val decided = ConcurrentHashMap<String, String>()

    /** 首个判定生效返回 true；同键的后续判定（无论谁发）幂等丢弃。 */
    fun firstWin(cardId: String, actionId: String, decidedBy: String): Boolean =
        decided.putIfAbsent(key(cardId, actionId), decidedBy) == null

    fun decidedBy(cardId: String, actionId: String): String? = decided[key(cardId, actionId)]

    /**
     * 从落库卡片行回填（重启后台账不丢）：带 `decidedBy` + `decidedActionId` 的行
     * 就是宿主当年判过的证据；没有这两个键的行不许凭空多出判定。
     */
    fun rebuildFrom(cardRows: List<JsonObject>) {
        for (card in cardRows) {
            val cardId = card.strField("cardId") ?: continue
            val decidedActionId = card.strField("decidedActionId") ?: continue
            val by = card.strField("decidedBy") ?: continue
            decided.putIfAbsent(key(cardId, decidedActionId), by)
        }
    }

    private fun key(cardId: String, actionId: String): String = cardId + "|" + actionId
}

/** 执行桥的任务请求（字段接原语；wsId 是目标工作区，路径永不出宿主）。 */
data class AgentInvokeTaskRequest(
    val agentId: String,
    val prompt: String,
    val wsId: String?,
    val groupId: String?,
)

/** 执行桥的结果：status 用协议的封闭取值域（[com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates]）。 */
data class AgentInvokeExecution(
    val status: String,
    val summary: String? = null,
    val error: String? = null,
    /** 执行产出的卡片（组员的卡；Leader 上群时会把它收进自己的 cardId 域）。 */
    val card: JsonObject? = null,
) {
    companion object {
        /**
         * 成功结果，卡片来自事件流的 **JSON 文本**。壳层（desktopApp）的编译类路径上
         * 没有 kotlinx-serialization（既有纪律），执行桥拿到的卡片只能是一段 String ——
         * 解析在这层完成，壳层永远不点名 JsonObject；解析不出的卡片如实丢弃
         * （结果仍算 done，看板卡少一段内容，不谎报失败）。
         */
        fun done(summary: String?, cardJsonText: String?): AgentInvokeExecution = AgentInvokeExecution(
            status = com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates.DONE,
            summary = summary,
            card = cardJsonText?.let { text ->
                runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            },
        )
    }
}

/**
 * 执行器桥：desktopApp 接到 ControlPlane 的插件派单（`/v1/plugin/task` 语义）；
 * 测试接假实现。**不许**在这个桥里拼发送者 —— 身份只有 AgentSenderId 一个来源。
 */
interface AgentInvokeExecutor {
    suspend fun execute(request: AgentInvokeTaskRequest): AgentInvokeExecution
}

/** 看板卡的构造键（发卡片那侧的纯函数，宿主是谁、任务是什么都从参数进）。 */
object InvokeBoardCardBuilder {
    fun build(
        invokeId: String,
        prompt: String,
        agentName: String,
        state: String,
        timeoutTitle: String,
        retryLabel: String,
        fallbackLabel: String,
        summary: String?,
        error: String?,
        workerCard: JsonObject?,
    ): JsonObject = buildInvokeBoardCard(
        invokeId, prompt, agentName, state, timeoutTitle, retryLabel, fallbackLabel, summary, error, workerCard,
    )
}

/**
 * Leader 的看板卡（§6.1 / §6.2）：
 * - `cardId = invoke-<invokeId>`：cardId 的命名域归 Leader —— 组员插件的原始 cardId
 *   直接进群会跟下一次派单的原位折叠撞车；
 * - 超时态带 [重试]/[降级为本地执行] 两个动作（§7.3-1）；其它终态不带按钮
 *   （重试一个必然再被拒的派单是给人挫败感，不是给出口）；
 * - 组员卡片的内容（title/progress/items/banner）原样收编 —— 全群看一张汇总卡，
 *   汇总的是事实，不是 Leader 的转述。
 */
internal fun buildInvokeBoardCard(
    invokeId: String,
    prompt: String,
    agentName: String,
    state: String,
    timeoutTitle: String,
    retryLabel: String,
    fallbackLabel: String,
    summary: String?,
    error: String?,
    workerCard: JsonObject?,
): JsonObject {
    val base = buildJsonObject {
        put("cardId", "invoke-" + invokeId)
        put("rev", 1)
        put("type", "team-task-board")
        put("title", prompt.take(80))
    }
    val withWorker = workerCard?.let { worker ->
        JsonObject(base.toMap() + worker.toMap().filterKeys { it != "cardId" && it != "rev" })
    } ?: base
    val builder = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
    builder.putAll(withWorker.toMap())
    when (state) {
        InvokeLeaseStates.TIMEOUT -> {
            builder["banner"] = buildJsonObject {
                put("text", timeoutTitle)
                put("tone", "warn")
            }
            builder["actions"] = kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("actionId", InvokeBoardActions.RETRY); put("label", retryLabel); put("style", "secondary") })
                add(buildJsonObject { put("actionId", InvokeBoardActions.FALLBACK_LOCAL); put("label", fallbackLabel); put("style", "primary") })
            }
        }
        InvokeLeaseStates.DONE -> {
            builder["progress"] = JsonPrimitive(100)
            builder["banner"] = buildJsonObject {
                put("text", summary ?: workerCard?.strField("title") ?: "")
                put("tone", "success")
            }
        }
        else -> {
            builder["banner"] = buildJsonObject {
                put("text", summary ?: agentName)
                put("tone", "error")
            }
            if (error != null) builder["error"] = JsonPrimitive(error)
        }
    }
    return JsonObject(builder)
}

// ---------------------------------------------------------------- 小工具

internal fun JsonObject.strField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull
