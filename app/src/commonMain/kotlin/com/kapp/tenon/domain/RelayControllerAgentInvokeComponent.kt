// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.AgentChannelPayload
import com.kapp.tenon.core.protocol.AgentExecAuthorizer
import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.NoticeDetail
import com.kapp.tenon.domain.model.NoticeKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
internal class RelayControllerAgentInvokeComponent(private val host: AgentDomainHost) {

    // ---------------------------------------------------------------- 桥（壳层注入）

    /**
     * 执行器桥：谁装了它，谁就是能承接派单的宿主。null = 本机没有执行器
     * （手机、未接插件宿主的测试宿主）—— 入站派单如实回 `no-executor`。
     */
    var executor: AgentInvokeExecutor? = null

    /**
     * 本机能否承接一条带 `agentIntent` 的派单。
     *
     * 入站层用它做**执行者仲裁**：装得下就走意图这条路，装不下（手机 / 未接插件的宿主）
     * 才轮到文本路由的执行体。两条路都真跑过同一个 prompt 的现场见
     * `handleOnInbound` 里 `intentOwnsDispatch` 的注释。
     */
    fun intentExecutorPresent(): Boolean = executor != null

    /**
     * Channel 委派的**本机承接器**（M7-T2，D-017）：桌面壳把 agentNode 的编排器接到这里；
     * 手机与未装配的测试宿主保持 null ⇒ 入站 `agent-channel` 一律回诚实的
     * `no-executor`。返回值 = 「已应答」（true：受理 / denied / tag 兜底都算）；
     * false = 没人应答，调用方回 no-executor。
     *
     * 参数是**已解码**的 [AgentChannelPayload]：desktopApp 的编译类路径上没有
     * kotlinx-serialization（M4 执行桥同一条纪律），解析留在 :app 这一层。
     *
     * 为什么是「承接器」而不是执行器：决策包 §1.5 把互斥台账、租约与结果分派都
     * 裁给了**接收宿主侧编排器**（agentNode，`ChannelOrchestrator`）——域层不该
     * 再抄一份台账（两份台账迟早漂成「面板说在跑、宿主说在排队」）。域层做的是
     * **授权 + 分流**：过授权门交给编排器，没过/没装就如实拒绝。
     */
    var channelOrchestrator: ((payload: AgentChannelPayload, fromNode: String) -> Boolean)? = null

    /**
     * T-2c（D-029 前置）：标签路由候选桥 —— (tag) → 已选定的远端 nodeId；null = 没有
     * 节点挂着这个标签（no-tag-match）。由壳层装配（PeerTagFileStore + registry 在线态
     * 在壳可达）；null 桥 = 本机不做标签委派（executor 缺位走既有失败横幅）。
     */
    var peerTagRouter: ((tag: String) -> String?)? = null

    /**
     * T-2c：发起侧 agent-channel 帧出口 —— (targetNodeId, channelId, tag, task, deadlineMs)
     * → SendOutcome。由壳层装配（rt.sendAgentChannel 已含对端 agc1 门控的调用方职责）。
     */
    var channelDispatch: ((nodeId: String, channelId: String, tag: String, task: String, deadlineMs: Long) -> Unit)? = null

    /**
     * 入站 `agent-channel`（M7-T2，cap `agc1`）。
     *
     * 手机端永不执行任务：本机没有承接器 ⇒ 回一帧诚实的 `no-executor`（决策包
     * §1.6 的设计意图；声明 agc1 的语义是「解得动这族帧」，沉默到对端租约超时
     * 才是违背它）。桌面端由 desktopApp 装配时把 [channelOrchestrator] 接到
     * agentNode 编排器（授权门也在那一侧）—— 编排器自己负责幂等、互斥、租约与
     * 结果回执。
     *
     * **双应答防线**：桥返回 true = 已有人应答过（受理 / denied / tag 兜底），
     * 本函数不再回帧；false 或没装桥 = 回 no-executor。判据只有这一个，两处各判
     * 一遍迟早漂。
     */
    fun handleOnAgentChannel(envelope: Envelope) {
        val p = envelope.payload as? JsonObject ?: return
        val parsed = runCatching {
            com.kapp.tenon.core.protocol.Codec.wireJson.decodeFromJsonElement(
                AgentChannelPayload.serializer(), p,
            )
        }.getOrNull()
        val orchestrator = channelOrchestrator
        if (parsed != null && orchestrator != null && orchestrator(parsed, envelope.from)) return
        val channelId = parsed?.channelId
            ?: (p["channelId"] as? JsonPrimitive)?.contentOrNull
            ?: return
        val rt = host.runtime ?: return
        host.scope.launch {
            runCatching {
                rt.sendAgentChannelResult(
                    peerId = envelope.from,
                    channelId = channelId,
                    ok = false,
                    status = com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.NO_EXECUTOR,
                    error = "this node does not host a channel executor",
                )
            }
        }
    }

    /**
     * 入站 `agent-channel-result`（M7-T2）：result 帧的台账归宿。
     *
     * 发起方在域层（M4 同款）：result 落本机消息流（预览与未读照常），看板卡/会话
     * 时间线按 channelId 关联。编排器在**接收**侧；发起侧的展示跟随通消息走，
     * 这里只做「帧到了、被记下」的最小处理（payload 本身已过 Codec 校验）。
     */
    fun handleOnAgentChannelResult(envelope: Envelope) {
        val p = envelope.payload as? JsonObject ?: return
        val channelId = p.strField("channelId") ?: return
        val ok = (p["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return
        val status = p.strField("status") ?: return
        host.onChannelResultObserved(
            envelope, channelId, ok, status,
            summary = p.strField("summary"),
            error = p.strField("error"),
        )
    }

    /**
     * 目标插件在线状态（S4-4b）：`(agentId) → [PluginPresence]` 取值或 null。
     * null / 查不到 = 状态拿不到 ⇒ **一切照旧**（派单进租约兜底），绝不因查不到而拒派。
     * 数据源是 agentNode `/v1/plugins` 的 `status`（`PluginRegistry` 按 healthz 探活维护），
     * 由壳层装桥映射进来 —— :app 不依赖 :agentNode。
     */
    var pluginPresenceOf: ((agentId: String) -> String?)? = null

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

    // ---------------------------------------------------------------- 两个协作对象
    //
    // 本类只剩**装配与入站分发**：卡片动作域（rev / 记账 / 广播 / 翻面）与租约域
    // （登记 / 执行 / 巡检）各自成类；下面那批同名成员是域外唯一的接缝
    // （`:composeApp` 两个壳、`:orchestration`、`:app` 的测试宿主直连它们，
    // 签名与行为一字未变 —— ARP-7 的判据就是「既有判据零改动」）。

    /**
     * 卡片动作域（资格判定 / 原子记账 / rev 串行分配 / 全群广播 / 终态翻面 / 合流反查）。
     * 本类的同名成员只是接缝转发，见 [RelayControllerAgentCardActions]。
     */
    private val cards = RelayControllerAgentCardActions(host, CardActionPort())

    /**
     * 租约与执行（登记 / 跨端发帧 / 本机执行 / 结果帧落终态 / 超时巡检）。
     * 三个参数全是**现读**：执行器桥壳层在 `start` 前才装，翻卡出口就是上面的 [cards]。
     */
    private val leases = RelayControllerAgentInvokeLeases(
        host = host,
        executorOf = { executor },
        broadcastCard = { groupId, agentId, card -> cards.broadcastBoardCard(groupId, agentId, card) },
    )

    /** 审批防重台账（§7.3-2）：键 = `(cardId, actionId)`，首个 agent-ack 原子生效。 */
    internal val actionLedger: AgentCardActionLedger get() = cards.actionLedger

    /** 从落库的卡片行回填判定台账（重启后「已由 X 处理」不许凭空消失）。 */
    fun rebuildActionLedger() {
        actionLedger.rebuildFrom(host.state.value.messages.values.flatten().mapNotNull { it.card })
    }

    /** 派单（发起方）：登记租约 + 发帧 / 本机执行 —— 全在 [RelayControllerAgentInvokeLeases]。 */
    fun dispatchCrossHostInvoke(
        groupId: String,
        agentId: String,
        prompt: String,
        wsId: String?,
        deadlineMs: Long = DEFAULT_INVOKE_DEADLINE_MS,
    ): String? = leases.dispatchCrossHostInvoke(groupId, agentId, prompt, wsId, deadlineMs)

    /** 1:1 直派（无群容器）：跳过席位查找，显式目标 + agent + 工作区。见 [RelayControllerAgentInvokeLeases]。 */
    fun dispatchDirectInvoke(
        peerId: String?,
        agentId: String,
        prompt: String,
        wsId: String?,
        convId: String? = null,
        deadlineMs: Long = DEFAULT_INVOKE_DEADLINE_MS,
    ): String? = leases.dispatchDirectInvoke(peerId, agentId, prompt, wsId, convId, deadlineMs)
    /** 入站结果帧（B → A）：租约落终态 + Leader 拼看板卡上群 —— 见 [RelayControllerAgentInvokeLeases]。 */
    fun handleOnAgentInvokeResult(envelope: Envelope) = leases.handleOnAgentInvokeResult(envelope)

    /** 租约巡检协程；由 `RelayController.start` 挂进 jobs（stop 随之取消）。 */
    fun startLeaseTicker() = leases.startLeaseTicker()
    /** 入站派单帧（A → B）：授权门禁（§6.3）→ 本机执行 → 结果帧回传。 */
    fun handleOnAgentInvoke(envelope: Envelope) {
        val p = envelope.payload as? JsonObject ?: return
        val invokeId = p.strField("invokeId") ?: return
        val op = p.strField("op") ?: return
        when (op) {
            com.kapp.tenon.core.protocol.CodecInvoke.InvokeOps.TASK ->
                host.scope.launch { handleInvokeTask(envelope, p, invokeId) }
            com.kapp.tenon.core.protocol.CodecInvoke.InvokeOps.ACTION ->
                host.scope.launch { cards.handleInvokeAction(envelope, p, invokeId) }
        }
    }

    private suspend fun handleInvokeTask(envelope: Envelope, p: JsonObject, invokeId: String) {
        val agentId = p.strField("agentId") ?: return
        val prompt = p.strField("prompt").orEmpty()
        val requestedBy = p.strField("requestedBy") ?: return
        val groupId = p.strField("groupId")
        val wsId = p.strField("wsId")
        val self = host.state.value.selfNodeId
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
            reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.NO_EXECUTOR)
            return
        }
        val meta = groupId?.let { g -> host.state.value.settings.groups.firstOrNull { it.groupId == g } }
        if (meta == null) {
            // **1:1 直派**（dispatchDirectInvoke 发来，无群容器）：授权走节点级信任——
            // 发起方必须对本宿主持有执行授权（grantAllows 与群路径同一个桥，判据收窄为
            // 节点维度）；agentId 由发起方显式给定，本机不查席位——执行桥自行解析
            // （插件优先、CLI 驱动兜底）。群成员身份门槛在这里不适用：没有群可查。
            if (grantAllows?.invoke(agentId, requestedBy) != true) {
                recordGrantRequest?.invoke(requestedBy, agentId, "direct invoke")
                announceDispatchFailure(
                    convId = com.kapp.tenon.core.protocol.ConvId.single(envelope.from),
                    agentKey = agentId,
                    agentName = agentId,
                    dispatchId = invokeId,
                    title = prompt.take(60),
                    reason = "direct invoke not granted for this node",
                    unavailable = true,
                )
                reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DENIED, error = "direct invoke not granted for this node")
                return
            }
            val directExecutor = executor
            if (directExecutor == null) {
                announceDispatchFailure(
                    convId = com.kapp.tenon.core.protocol.ConvId.single(envelope.from),
                    agentKey = agentId,
                    agentName = agentId,
                    dispatchId = invokeId,
                    title = prompt.take(60),
                    reason = "no local executor",
                    unavailable = true,
                )
                reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.NO_EXECUTOR)
                return
            }
            // 1:1 反馈面（「只是发送消息」的修复）：受理与终局都落进与发起方的单聊
            // 会话流——群路径的看板卡在这条路上没有落点，租约镜像之外的可见面就是
            // 会话本身。受理与答案以 agent 名义发言（AgentSenderId 单一来源），
            // 失败走同源的 announceDispatchFailure（群/工单/1:1 三条路径一条实现）。
            val conv1to1 = com.kapp.tenon.core.protocol.ConvId.single(envelope.from)
            val wsTitle = wsId?.let { id ->
                host.state.value.agentWorkspaces.values.firstOrNull { it.wsId == id }?.wsTitle
            }
            val senderId = com.kapp.tenon.core.protocol.AgentSenderId.of(self, agentId, agentId)

            /**
             * 落本机 + **发到发起方**（两件事必须成对）。
             *
             * 「只落本机」是上一版的原样：`handleAppendMessage` 只写本端库，
             * 手机上一条都收不到 —— 用户发了工单、任务真跑完，界面一字不变
             * （真机 A22 现场：宿主库里躺着 `Ticket accepted…` 与
             * `改动已就绪…`，手机库同时段零入站行）。
             *
             * 信封 id 与本地行 id 同一个（既有纪律：补发/去重/原位替换按 id 对账）；
             * 发送失败只记日志、不改结论 —— 本端那条事实仍然成立。
             */
            suspend fun sayToPeer(text: String) {
                val msgId = newId()
                host.handleAppendMessage(
                    MessageUi(
                        id = msgId,
                        peerId = conv1to1,
                        mine = false,
                        senderId = senderId,
                        text = text,
                        at = nowMillis(),
                    ),
                )
                runCatching { rt.sendDirectText(peerId = envelope.from, text = text, envelopeId = msgId, senderId = senderId) }
                    .onFailure { t ->
                        println(
                            "[INVOKE] 1:1 feedback not delivered (invoke=" + invokeId + "): " +
                                (t.message ?: t::class.simpleName),
                        )
                    }
            }

            sayToPeer("Ticket accepted: executing in " + (wsTitle ?: "the workspace") + "…")
            val directOutcome = try {
                // **convId 用本端口径**，不是帧里那个：帧里的 `convId` 是发起方视角的
                // 会话 id（对端 = 本机 nodeId），本机拿它当自己的会话键会把卡片、产物
                // 全落进「和自己的会话」里 —— 真机现场就是这么丢的
                // （`[CARD] 1:1 direct card persisted locally (conv=<本机 nodeId>)`）。
                directExecutor.execute(
                    AgentInvokeTaskRequest(
                        agentId = agentId,
                        prompt = prompt,
                        wsId = wsId,
                        groupId = null,
                        convId = conv1to1,
                    ),
                )
            } catch (t: Throwable) {
                reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.ERROR, error = (t.message ?: "executor failed").take(Limits.AGENT_NOTE))
                return
            }
            if (directOutcome.status == com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DONE) {
                sayToPeer(directOutcome.summary?.takeIf { it.isNotBlank() } ?: "Task finished.")
            } else {
                announceDispatchFailure(
                    convId = conv1to1,
                    agentKey = agentId,
                    agentName = agentId,
                    dispatchId = invokeId,
                    title = prompt.take(60),
                    reason = directOutcome.error ?: directOutcome.summary,
                    unavailable = false,
                )
            }
            reply(
                ok = directOutcome.status == com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DONE,
                status = directOutcome.status,
                summary = directOutcome.summary,
                error = directOutcome.error,
                card = directOutcome.card,
            )
            return
        }
        if (requestedBy !in meta.members) {
            // 授权的前置门槛是成员身份（AgentExecAuthorizer 同一条）：不是群成员的请求一律拒。
            reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DENIED, error = "not a member of the group")
            return
        }
        val seat = meta.virtualAgents.orEmpty().firstOrNull { it.agentId == agentId }
        if (seat == null || seat.hostNodeId != self) {
            reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.NO_PLUGIN, error = "agent is not hosted here")
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
            reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DENIED, error = "execution not authorized")
            return
        }
        val ex = executor
        if (ex == null) {
            reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.NO_EXECUTOR)
            return
        }
        val outcome = try {
            ex.execute(AgentInvokeTaskRequest(agentId = agentId, prompt = prompt, wsId = wsId, groupId = groupId))
        } catch (t: Throwable) {
            reply(false, com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.ERROR, error = (t.message ?: "executor failed").take(Limits.AGENT_NOTE))
            return
        }
        reply(
            ok = outcome.status == com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DONE,
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
     * @param messageId 那条群文本的信封 id。只用于「同一次派单只公告一次失败」
     *   的去重（队列重放会把同一个信封再处理一遍）。
     */
    fun handleInboundAgentIntent(
        groupId: String,
        senderNode: String,
        intent: JsonObject?,
        messageText: String? = null,
        messageId: String? = null,
        isSeatSyncRetry: Boolean = false,
    ) {
        if (intent == null) return
        val agentId = intent.strField("agentId")?.takeIf { it.isNotBlank() } ?: return
        val wsId = intent.strField("wsId")?.takeIf { it.isNotBlank() }
        val st = host.state.value
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId }
        if (meta == null) {
            scheduleSeatSyncRetry(groupId, senderNode, intent, messageText, messageId, isSeatSyncRetry)
            return
        }
        val self = st.selfNodeId
        // 定向呼叫（T-G5）：显式 hostNodeId = 只有被点名的宿主处理这条意图
        // （群帧全员可见，执行只有一家 —— 否则每台有同名 agent 的机器都抢着跑）。
        val explicitHost = intent.strField("hostNodeId")?.takeIf { it.isNotBlank() }
        if (explicitHost != null && explicitHost != self) return
        val seat = meta.virtualAgents.orEmpty().firstOrNull { it.agentId == agentId }
        if (seat == null) {
            scheduleSeatSyncRetry(groupId, senderNode, intent, messageText, messageId, isSeatSyncRetry)
            return
        }
        val effectiveHost = explicitHost ?: seat.hostNodeId
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
            // 本地 notice 只有宿主自己看得到（notice 不进协议）。点 @ 的往往是手机，
            // 光落这一行等于「消息到了、零卡片零横幅」——真机 T5 的全静默就发生在这里。
            // 补一条 agentState=failed 群文本，让发起端也收到回执；授权判据本身不动。
            // 群帧全员可见：只有托管该席位的宿主回执，别的宿主不跟着刷屏。
            if (effectiveHost == self) {
                announceIntentDispatchDenied(groupId, agentId, seat.name, senderNode, messageId)
            }
            return
        }
        val ex = executor
        if (ex == null) {
            // T-2c（发起侧编排接线，D-029）：本机插件接不了活时，**按算力标签把任务委派出去**
            // （设计 §4.1 流程 A：托管宿主解析所需算力 → 按标签路由到算力节点）。
            // tag 来源 = intent.requiredTag（agentIntent 是 JsonObject 开放结构，内部键
            // 不动群文本白名单）；候选与选择走 PeerTagRouter（已交付 1e774b1a，桥注入）。
            // 没桥 / 没标签 / 无匹配节点 ⇒ 落回既有失败横幅（如实，不静默）。
            val requiredTag = intent.strField("requiredTag")?.takeIf { it.isNotBlank() }
            val routed = if (requiredTag != null) peerTagRouter?.invoke(requiredTag) else null
            if (requiredTag != null && routed == null) {
                // R3 兜底卡（T-G4）：指定了标签但无匹配节点 —— 用户要的是「有 gpu 的那台」，
                // 静默落回本机执行违背意图。挂起成卡，两个动作各自可达（重试路由 /
                // 降级本机），横幅文案照旧出一条（不点卡的人也有回声）。
                val tagTask = intent.strField("prompt")
                    ?: messageText?.let {
                        RelayController.extractAgentPrompt(it, setOfNotNull(seat.agentId.takeIf { t -> t.isNotBlank() }, seat.name))
                    }
                    ?: ""
                val cardId = "tagboard-" + newId().take(16)
                cards.rememberTagBoard(cardId, groupId, agentId, seat, tagTask, wsId, requiredTag)
                host.scope.launch {
                    val bannerTitle = runCatching { host.uiTexts.tagNoMatchTitle(requiredTag) }.getOrNull().orEmpty()
                    runCatching {
                        // 文案桥空（未装文案壳）= 卡不带 banner（空正文不发帧的既有纪律
                        // 同族：横幅缺失仍有点题 title 与动作，不算沉默）。
                        val card = buildJsonObject {
                            put("cardId", cardId)
                            put("rev", 1)
                            put("type", "team-task-board")
                            put("title", tagTask.take(80))
                            if (bannerTitle.isNotEmpty()) {
                                put("banner", buildJsonObject {
                                    put("text", bannerTitle)
                                    put("tone", "warn")
                                })
                            }
                                put("actions", kotlinx.serialization.json.buildJsonArray {
                                    add(buildJsonObject {
                                        put("actionId", TagBoardActions.RETRY)
                                        put("label", runCatching { host.uiTexts.invokeActionRetryLabel() }.getOrNull().orEmpty().ifEmpty { "retry" })
                                        put("style", "secondary")
                                    })
                                    add(buildJsonObject {
                                        put("actionId", TagBoardActions.FALLBACK_LOCAL)
                                        put("label", runCatching { host.uiTexts.invokeActionFallbackLabel() }.getOrNull().orEmpty().ifEmpty { "fallback" })
                                        put("style", "primary")
                                    })
                                })
                            }
                        broadcastBoardCard(groupId, agentId, card)
                    }
                    runCatching { announceAgentGaveUp(groupId, agentId, seat.name, messageId, unavailable = true) }
                }
                return
            }
            if (requiredTag != null && routed != null) {
                val channelId = "ch-" + newId().take(20)
                val task = intent.strField("prompt")
                    ?: messageText?.let {
                        RelayController.extractAgentPrompt(it, setOfNotNull(seat.agentId.takeIf { t -> t.isNotBlank() }, seat.name))
                    }
                    ?: ""
                channelDispatch?.invoke(routed, channelId, requiredTag, task, DEFAULT_INVOKE_DEADLINE_MS)
                host.handleAppendMessage(
                    MessageUi(
                        id = host.localMessageId(),
                        peerId = ConvId.group(groupId),
                        mine = false,
                        text = "",
                        at = nowMillis(),
                        notice = NoticeKind.AgentDelegatedByTag,
                        noticeDetail = NoticeDetail(actor = seat.name, detail = requiredTag + " -> " + routed.take(8)),
                    ),
                )
                return
            }
            // **插件接不了活**（被 kill / 从没注册 / 宿主壳没接桥）：此前这里 `?: return`，
            // 群流里一条都没有 —— 点 @ 的人看到的和「派单成功、任务在跑」一模一样。
            // 设计稿 v2 §5「失败如实」：宿主回一条 agentState=failed 的轻横幅。
            announceAgentGaveUp(groupId, agentId, seat.name, messageId, unavailable = true)
            return
        }
        // S4-4b：插件**判离线**还把活派进租约 = 点 @ 的人盯着群流干等 10+ 分钟（矩阵 §7.6 真机读数）。
        // 立刻回 agentState=failed 的轻横幅；online / 状态拿不到 ⇒ 照旧派单（查不到不拒派，租约兜底）。
        if (pluginPresenceOf?.invoke(agentId) == PluginPresence.OFFLINE) {
            announceAgentGaveUp(groupId, agentId, seat.name, messageId, unavailable = true)
            return
        }
        val tokens = setOfNotNull(seat.agentId.takeIf { it.isNotBlank() }, seat.name.takeIf { it.isNotBlank() })
        val prompt = intent.strField("prompt")
            ?: messageText?.let { RelayController.extractAgentPrompt(it, tokens) }
            ?: ""
        host.scope.launch {
            runCatching {
                ex.execute(AgentInvokeTaskRequest(agentId = agentId, prompt = prompt, wsId = wsId, groupId = groupId))
            }.onSuccess { outcome ->
                // 卡片是成功路径的回执；**没有卡片又没有 done** 才是沉默 —— 补一条反馈。
                if (outcome.card != null) {
                    broadcastBoardCard(groupId, agentId, outcome.card)
                } else if (outcome.status != com.kapp.tenon.core.protocol.CodecInvoke.InvokeResultStates.DONE) {
                    announceAgentGaveUp(groupId, agentId, seat.name, messageId, unavailable = false)
                }
            }.onFailure {
                // 执行器抛错同样是「点了没反应」：插件进程死在任务中间就长这样。
                announceAgentGaveUp(groupId, agentId, seat.name, messageId, unavailable = false)
            }
        }
    }

    // ---------------------------------------------------------------- 席位同步竞态（单次重投）

    /**
     * 席位同步竞态的**单次**重投（真机 E2E 判红过一次）。
     *
     * 手机端改群 meta（rev+1）与紧随其后的派单帧经常**同秒**到达宿主；派单帧先被处理时
     * 群 meta / 虚拟席位还没进 state，旧代码在 `meta == null` / `seat == null` 两道门上
     * **静默 return** —— 用户看到的是「派单发出去，什么都没发生」。修法：首次命中把
     * 同一意图延迟 [SEAT_SYNC_RETRY_DELAY_MS] 重投一次，meta 在途时自愈。
     *
     * 重投仍找不到席位就按旧语义沉默 —— 那是真·席位不存在：全群每台宿主都收得到群帧，
     * 「不是我的席位」若出声会变成 N 台机器各回一条错误横幅。
     */
    private fun scheduleSeatSyncRetry(
        groupId: String,
        senderNode: String,
        intent: JsonObject?,
        messageText: String?,
        messageId: String?,
        isSeatSyncRetry: Boolean,
    ) {
        if (isSeatSyncRetry) return
        host.scope.launch {
            delay(SEAT_SYNC_RETRY_DELAY_MS)
            if (!isActive) return@launch
            handleInboundAgentIntent(
                groupId = groupId,
                senderNode = senderNode,
                intent = intent,
                messageText = messageText,
                messageId = messageId,
                isSeatSyncRetry = true,
            )
        }
    }

    // ---------------------------------------------------------------- 失败反馈（S4-4）

    /**
     * 已经公告过的派单（键 = 那条群文本的 id）。**同一次派单只出 1 条横幅**：
     * 队列重放 / 去重表边界会让同一个信封被处理两次，而「一片红」比沉默更糟 ——
     * 用户会以为插件连续失败了三次。
     *
     * 用无界 set 是有意的：条目是一个 36 字符的 id，一次派单一条，会话生命周期内
     * 也就几百条；换成 LRU 反而会在重放迟到时漏判（那时才是它该起作用的时候）。
     */
    private val announcedDispatches = LockedSet<String>()

    /**
     * 群意图路径的旧入口：签名与调用点保持不变，判据转发到 [announceDispatchFailure]。
     *
     * 工单路径也从同一个入口落会话 —— 「派单失败要说话」这件事只有一条实现，
     * 下次再改不会出现「群聊说、1:1 不说」的分叉。
     */
    private fun announceAgentGaveUp(
        groupId: String,
        agentId: String,
        agentName: String,
        dispatchId: String?,
        unavailable: Boolean,
    ) {
        announceDispatchFailure(
            convId = ConvId.group(groupId),
            agentKey = agentId,
            agentName = agentName,
            dispatchId = dispatchId,
            title = null,
            reason = null,
            unavailable = unavailable,
        )
    }

    /**
     * 群意图派单被**授权门**拒时，给全群（尤其是发起端手机）的回执。
     *
     * 为什么不能只落 `AgentNotAuthorized` 本地 notice：`NoticeKind` 的 KDoc 与
     * [broadcastAgentGaveUp] 都写明了 notice 是纯本地数据、**不进协议** —— 宿主
     * 自己看得见，手机端一条都收不到。真机 T5 的「消息到了、dedup 增了、零卡片零横幅」
     * 就卡在这里：授权判据拒绝了，但这句拒绝从没离开过宿主。
     *
     * 复用 [announceCardActionDenied] 立下的范式（同一份文案、同一个
     * `agentState=failed` 通路、按一次尝试去重），不新增协议键、不新增文案；
     * 授权判据本体（`AgentExecAuthorizer` / 台账 `forExecution`）一字不动 ——
     * 这里改的是「拒绝说不说得出口」，不是「谁能执行」。
     */
    private fun announceIntentDispatchDenied(
        groupId: String,
        agentId: String,
        agentName: String,
        senderNode: String,
        messageId: String?,
    ) {
        val key = messageId ?: "$groupId|$agentId|$senderNode"
        if (!deniedIntentDispatches.add(key)) return
        host.scope.launch {
            val st = host.state.value
            val meta = st.settings.groups.firstOrNull { it.groupId == groupId }
            val actorName = decidedDisplayName(
                senderNode, meta?.memberNicks, st.selfNodeId, st.settings.nick,
            )
            val text = runCatching { host.uiTexts.agentActionDeniedNotice(actorName, agentName) }
                .getOrNull().orEmpty()
            broadcastAgentState(groupId, agentId, AGENT_STATE_FAILED, text)
        }
    }

    /** 已公告过的「未授权派单尝试」（键 = messageId），防止同一帧被重投后刷屏。 */
    private val deniedIntentDispatches = LockedSet<String>()

    /**
     * **派单终局失败的唯一发布入口**（群意图路径与工单路径共用）。
     *
     * - 群聊：保持既有 `agentState=failed` 群文本（全群可见，见 [broadcastAgentGaveUp]）。
     * - 1:1：落一条 [NoticeKind.AgentDispatchFailed] 系统提示行 —— 1:1 文本协议没有
     *   `agentState` 键，notice 是壳层既有的「系统解释」形状（与 `AgentNotAuthorized`
     *   同一条本地通路），不新造协议帧。
     *
     * @param title 工单标题 / 工单号（调用方截断）；null = 群意图路径没有工单
     * @param reason 一句话原因（调用方按 `Limits.AGENT_NOTE` 截断）
     */
    internal fun announceDispatchFailure(
        convId: String,
        agentKey: String,
        agentName: String,
        dispatchId: String?,
        title: String?,
        reason: String?,
        unavailable: Boolean,
    ) {
        val groupId = ConvId.groupIdOf(convId)
            ?: convId.takeIf { id -> host.state.value.settings.groups.any { it.groupId == id } }
        if (groupId != null) {
            broadcastAgentGaveUp(groupId, agentKey, agentName, dispatchId, unavailable, title, reason)
        } else {
            postDirectDispatchFailure(convId, agentKey, title, reason, dispatchId)
        }
    }

    /**
     * 把「这次没做成」播进群流：`agentState=failed` 的 agent 署名群文本。
     *
     * 为什么是群文本而不是本地 notice：notice 是**纯本地数据、不进协议**（见
     * `NoticeKind` 的 KDoc），只有宿主自己看得到；而「@ 了没反应」这件事是
     * **全群**都该知道的 —— 别的人接着 @ 同一个 agent 也会撞上同一堵墙。
     *
     * 为什么不发卡片：设计稿把它定为「轻横幅」—— 一次没做成的派单不值得占一张卡的位置，
     * 也不该进卡片的折叠/rev 体系（它没有 rev，也永远不会刷新）。
     *
     * @param unavailable true = 本机根本没有执行器；false = 有执行器但没做成
     */
    private fun broadcastAgentGaveUp(
        groupId: String,
        agentId: String,
        agentName: String,
        dispatchId: String?,
        unavailable: Boolean,
        title: String?,
        reason: String?,
    ) {
        // 没有 dispatchId 的调用点（老测试/内部直调）按内容去重，至少不会连发两条。
        val key = dispatchId
            ?: ("$groupId|$agentId|$agentName|$unavailable|${title.orEmpty()}|${reason.orEmpty()}")
        if (!announcedDispatches.add(key)) return
        host.scope.launch {
            // 工单路径带标题与原因 ⇒ 用一句能回答「哪一单、谁、为什么」的话；
            // 群意图路径没有工单（title/reason 都是 null）⇒ 保持原有两句措辞。
            val detailed = if (!title.isNullOrBlank() || !reason.isNullOrBlank()) {
                runCatching {
                    host.uiTexts.dispatchFailedNotice(
                        title.orEmpty().trim().take(60),
                        agentName,
                        reason.orEmpty().trim().take(Limits.AGENT_NOTE),
                    )
                }.getOrNull().orEmpty()
            } else {
                ""
            }
            val text = detailed.ifEmpty {
                runCatching {
                    if (unavailable) host.uiTexts.agentUnavailableNotice(agentName)
                    else host.uiTexts.agentNotCompletedNotice(agentName)
                }.getOrNull().orEmpty()
            }
            // 文案取不到也照落库：**帧本身**（agentState=failed）就是那条事实，
            // 界面层会在正文为空时补一句本地兜底（见 `AgentStateBannerRow`）。
            // 线上那一份则要求非空（协议对群文本 `text` 的判据），见 broadcastAgentState。
            broadcastAgentState(groupId, agentId, AGENT_STATE_FAILED, text)
        }
    }

    /**
     * 1:1 的失败落点：一条本地系统提示行。
     *
     * `NoticeDetail` 带齐「哪一单、谁失败、为什么」，文案由壳层按资源翻；领域层不拼中文
     * （`cjkLiteralGate` 与多语言对账同一条纪律）。
     */
    private fun postDirectDispatchFailure(
        convId: String,
        agentKey: String,
        title: String?,
        reason: String?,
        dispatchId: String?,
    ) {
        val safeTitle = title?.trim()?.take(60).orEmpty()
        val safeReason = reason?.trim()?.take(Limits.AGENT_NOTE).orEmpty()
        val key = dispatchId ?: ("$convId|$agentKey|$safeTitle|$safeReason")
        if (!announcedDispatches.add(key)) return
        host.handleAppendMessage(
            MessageUi(
                id = host.localMessageId(),
                peerId = convId,
                mine = false,
                text = "",
                at = nowMillis(),
                notice = NoticeKind.AgentDispatchFailed,
                noticeDetail = NoticeDetail(
                    actor = agentKey,
                    title = safeTitle,
                    reason = safeReason,
                ),
            ),
        )
        // **提示行是纯本地的（`NoticeKind` 不进协议）** —— 只落本机等于发起方手机上
        // 一个字都没有，「被拒」与「正在跑」在对面完全同形（真机：手机节点
        // 没被授 agent 级执行权，首次派单回 DENIED，界面零变化）。这里补一帧事实文本：
        // 哪一单、谁、为什么。文案走与群路径同一个 uiTexts 桥；桥没装（CLI/测试宿主）
        // 就退回到结构字段拼接，绝不拼中文常量（`cjkLiteralGate` 同一条纪律）。
        val peer = convId.takeIf { !it.startsWith("agent:") && !it.startsWith("ws-") }
        if (peer != null) {
            host.scope.launch {
                val text = runCatching {
                    host.uiTexts.dispatchFailedNotice(safeTitle, agentKey, safeReason)
                }.getOrNull().orEmpty().ifBlank {
                    listOf(safeTitle, agentKey, safeReason).filter { it.isNotBlank() }.joinToString(" — ")
                }
                if (text.isBlank()) return@launch
                runCatching { host.runtime?.sendDirectText(peerId = peer, text = text) }
                    .onFailure { t ->
                        println(
                            "[INVOKE] 1:1 failure notice not delivered (" + dispatchId + "): " +
                                (t.message ?: t::class.simpleName),
                        )
                    }
            }
        }
    }

    /**
     * 「空正文不发帧」在**文案桥未装**时的抑制读数（进程内只记第一次，只进不退）。
     *
     * 为什么存在：桥没装（壳忘了装配 / 组装时序把桥摘了）= 每句文案都空 = 群帧被
     * 静默抑制 —— 真机事故现场：桌面壳从不经 `RelayApp` 组装，桥从未装上，手机端
     * 0 条（composeApp `RelayUiTextsBridge` 的 KDoc 记着读数）。人可见的一面是
     * 日志里的一行 `[RELAY-TEXT]`；这个计数是它的**机器可读判据**：装了桥的产品壳
     * 恒为 0。只认 [NoUiTexts]：桥装了但某句取词失败返回空，不该被误报成「没装」。
     */
    var uninstalledTextBridgeSuppressions: Int = 0
        private set

    private fun noteUninstalledTextBridgeSuppression(site: String) {
        if (host.uiTexts !== NoUiTexts) return
        if (uninstalledTextBridgeSuppressions > 0) return
        uninstalledTextBridgeSuppressions = 1
        println("[RELAY-TEXT] uiTexts bridge not installed (NoUiTexts): empty copy suppressed a group frame at " + site)
    }

    /**
     * 以 agent 署名发一条只带执行状态的群文本（`agentState` 键 M1 已在白名单，
     * 不新增协议键）。本机落库与线上同帧同 id —— 与 [broadcastBoardCard] 同一纪律。
     */
    internal suspend fun broadcastAgentState(groupId: String, agentId: String, state: String, text: String) {
        val st = host.state.value
        val meta = st.settings.groups.firstOrNull { it.groupId == groupId } ?: return
        if (st.selfNodeId !in meta.members) return
        val sender = AgentSenderId.of(st.selfNodeId, agentId)
        val envelopeId = newId()
        // **先落库再发**：这条事实属于本机用户，不该因为运行时还没起来（或已经停了）
        // 就连自己都看不到。发送失败只影响别人看不看得到。
        host.handleAppendMessage(
            MessageUi(
                id = envelopeId,
                peerId = ConvId.group(groupId),
                mine = false,
                text = text,
                at = nowMillis(),
                delivery = null,
                senderId = sender,
                agentState = state,
            ),
            incoming = true,
        )
        val rt = host.runtime ?: return
        // **空正文不发帧**：协议侧 `validateMsg` 对群文本要求 `text` 非空（`text.isEmpty()`
        // 整帧拒收）。发一条注定被拒的帧比不发更糟 —— 本端显示「已发送」，对端一条没有。
        // 空正文只可能来自没装文案桥的壳（CLI / 测试宿主）；装了 `ResourceUiTexts` 的
        // 产品壳永远有措辞。桥没装这个事实必须在抑制发生时**被看见**（见下方读数），
        // 否则这就是「发送侧一切正常、对端什么都没有」的又一次静默现场。
        if (text.isEmpty()) {
            noteUninstalledTextBridgeSuppression("agentState:" + state)
            return
        }
        runCatching {
            rt.sendGroupText(
                text = text,
                groupId = groupId,
                groupRev = meta.rev,
                members = meta.members,
                senderId = sender,
                envelopeId = envelopeId,
                agentState = state,
            )
        }
    }


    // ---------------------------------------------------------------- 看板卡与卡片动作
    //
    // 整块（rev 串行分配 / 全群广播 / 资格判定 / 原子记账 / 终态翻面）在
    // [RelayControllerAgentCardActions]。下面三个同名成员是**域外唯一的接缝** ——
    // `:composeApp` 两个壳的点击、`:orchestration` 的翻面与投递、`:app` 的测试宿主
    // 连的都是它们，签名与行为一字未变（ARP-7 的判据就是「既有判据零改动」）。

    internal suspend fun broadcastBoardCard(groupId: String, agentId: String, card: JsonObject) =
        cards.broadcastBoardCard(groupId, agentId, card)

    fun handleCardAction(groupId: String, cardId: String, cardRev: Int, actionId: String) =
        cards.handleCardAction(groupId, cardId, cardRev, actionId)

    internal fun handleHostCardAction(
        groupId: String,
        agentId: String,
        cardId: String,
        actionId: String,
        decidedBy: String,
    ) = cards.handleHostCardAction(groupId, agentId, cardId, actionId, decidedBy)

    /**
     * 动作域的端口实现：十一个成员全是**现读转发**，没有一个是构造期快照
     * （见 [RelayControllerAgentCardActions.Port] 的 KDoc）—— 桥是壳层装配之后才注入的，
     * 拍成快照会得到一个永远为 null 的桥，且表现成「点了没反应」而不是异常。
     */
    private inner class CardActionPort : RelayControllerAgentCardActions.Port {
        override val leaseTable: AgentInvokeLeaseTable get() = leases.leaseTable

        override fun routeTag(tag: String): String? = peerTagRouter?.invoke(tag)

        override fun dispatchChannelToNode(nodeId: String, channelId: String, tag: String, task: String, deadlineMs: Long) {
            channelDispatch?.invoke(nodeId, channelId, tag, task, deadlineMs)
        }

        override fun grantAllowsNode(agentKey: String, nodeId: String): Boolean =
            grantAllows?.invoke(agentKey, nodeId) == true

        override fun execPolicyKey(agentKey: String): String? = execPolicyOf?.invoke(agentKey)

        override fun announceGaveUp(groupId: String, agentId: String, agentName: String, dispatchId: String?, unavailable: Boolean) {
            announceAgentGaveUp(groupId, agentId, agentName, dispatchId, unavailable)
        }

        override fun dispatchTaskToAgent(groupId: String, agentId: String, prompt: String, wsId: String?) {
            dispatchCrossHostInvoke(groupId, agentId, prompt, wsId)
        }

        override fun dispatchDirectInvoke(peerId: String?, agentId: String, prompt: String, wsId: String?) {
            leases.dispatchDirectInvoke(peerId, agentId, prompt, wsId)
        }

        override suspend fun runLocallyAndMark(
            invokeId: String,
            agentId: String,
            prompt: String,
            wsId: String?,
            cardGroupId: String?,
        ) {
            leases.executeLocallyAndMark(invokeId, agentId, prompt, wsId, cardGroupId)
        }

        override suspend fun publishAgentState(groupId: String, agentId: String, state: String, text: String) {
            broadcastAgentState(groupId, agentId, state, text)
        }

        override fun notifyHostCardAction(agentId: String, cardId: String, actionId: String, decidedBy: String) {
            onHostCardAction?.invoke(agentId, null, cardId, actionId, decidedBy)
        }
    }
    companion object {
        /** 派单租约的缺省期限。给足一个真实任务的工作窗口；调用方可按子任务改小。 */
        const val DEFAULT_INVOKE_DEADLINE_MS: Long = 10L * 60_000

        /**
         * 席位/meta 在途时的重投等待：同秒竞态在 1–2s 内自愈；太长会让派单的人
         * 白等（受理卡迟迟不出），太短盖不住慢的 meta 重传。
         */
        const val SEAT_SYNC_RETRY_DELAY_MS: Long = 2_000L

        /**
         * `agentState` 四态里的失败档（协议取值，见 `CodecMsg` 的白名单校验）。
         * 不写成裸串：这个值出现在帧里，写错一个字母就是整帧拒收。
         */
        const val AGENT_STATE_FAILED: String = "failed"
    }
}

// ---------------------------------------------------------------- 小工具

internal fun JsonObject.strField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull
