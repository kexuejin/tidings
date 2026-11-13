// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.domain.model.MessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/**
 * 智能体域的**宿主缝**：两个 agent 分片（会话/事件/审批 + 跨端派单网道）只允许碰这里列出的成员。
 *
 * ## 为什么要这层接口
 *
 * 十三个分片过去一律构造在 `host: RelayController` 上。"逐个 `host.` 前缀"让读方法体的人
 * 看得出碰了什么，但**编译器看不出** —— `internal` 在同一个模块里对任何文件都可见，
 * 于是「agent 域只碰 agent 域的状态」这条纪律全靠人守。真出现的形态是：一个 agent 帧的
 * 处理顺手读了消息表、或者让一个分片直接驱动另一个分片，跨域耦合每一轮都在长。
 *
 * 现在依赖面写在类型上：本接口之外的成员在 agent 分片里**编译不过**。新增一条依赖
 * 必须先改这里，评审时就能看见。
 *
 * ## 状态读写只有一条路
 *
 * 读走 [state]（只读视图），写走 [updateAgentState] —— 分片不再直接拿 `MutableStateFlow`。
 * 这是 Phase 4 状态切片的前置：将来 agent 状态从单一 [RelayUiState] 里切成独立流时，
 * 改的是 [AgentDomainService] 里的这一个实现，而不是散落在两个分片里的几十个 `update` 调用点。
 *
 * 接口是 `internal`：它只服务 `:app` 内部的服务化拆分，不构成对外 API。
 */
internal interface AgentDomainHost {
    /** 当前 UI 状态的只读视图（跨域共享的单一事实源，见 [RelayUiState]）。 */
    val state: StateFlow<RelayUiState>

    /** 唯一的写入口：改表 → 同一临界区里提交（见 [RelayStateWriter]）。 */
    fun updateAgentState(transform: (RelayUiState) -> RelayUiState)

    /** agent 会话/工作区/授权台账的持久化。 */
    val agentStore: AgentStore

    /** 控制器的协程作用域（生命周期与控制器一致）。 */
    val scope: CoroutineScope

    /** 本机节点运行时；null = 节点没在跑（发帧路径据此诚实降级）。 */
    val runtime: NodeRuntime?

    /** 消息存储（派单结果与卡片走消息表）。 */
    val messageStore: MessageStore

    /** 领域层取一句话的桥（见 [RelayUiTexts]）。 */
    val uiTexts: RelayUiTexts

    /** 控制器持有的后台任务列表（停止时统一取消）。 */
    val jobs: MutableList<Job>

    /** 会话分页请求桥：界面接它来向宿主再要一页（null = 没装桥）。 */
    val onLocalSessionPageRequest: ((peerId: String, wsId: String, offset: Long, limit: Long?) -> Boolean)?

    /** 生成本机乐观消息 id。 */
    fun localMessageId(): String

    /** 工作区建群后同步群资料（跨域动作，走宿主收口，不直接持有群组件）。 */
    fun syncWorkspaceGroups()

    /** 警告横幅。 */
    fun warn(message: BannerMessage)

    /** 系统通知。 */
    fun showNotification(title: String, body: String, focusPeerId: String? = null)

    /** channel 回执落成一条群流消息（去重后在库里仍只有一条）。 */
    fun onChannelResultObserved(
        envelope: Envelope,
        channelId: String,
        ok: Boolean,
        status: String,
        summary: String?,
        error: String?,
    )

    /** 横切：插一条消息（按 `at` 归位、可选计未读）。 */
    fun handleAppendMessage(message: MessageUi, incoming: Boolean = false)

    /** 横切：按新 id 整条替换（乐观插入的临时 id → 真实 envelope id）。 */
    fun handleReplaceMessage(peerId: String, message: MessageUi)
}

/**
 * 智能体域服务：agent 会话、事件、队列、审批、取消、回执、产物与跨端派单网道的**唯一装配点**。
 *
 * 行为与拆分前逐字一致 —— 本类只做转发与组件归属，不新增判据、不改状态形状：
 * 会话/事件族在 [agent]，跨端派单族在 [invoke]。
 *
 * 主类 [RelayController] 仍然是唯一的公开门面（`:orchestration` 与 UI 直接连它），
 * 它把两族的字段与方法继续以同名成员暴露，内部改为委托到这里。
 */
internal class AgentDomainService(
    private val host: RelayController,
) : AgentDomainHost {

    /** agent 协议帧的入站处理：会话、事件、队列、执行、审批、取消、回执、产物。 */
    val agent = RelayControllerAgentComponent(this)

    /** 跨端派单网道：租约、执行门禁、看板卡广播与审批防重。 */
    val invoke = RelayControllerAgentInvokeComponent(this)

    // ---- 跨域入站路由（供 ConversationDomainService 的端口转发）----
    //
    // 会话域是入站总分发处：一个信封进来先由会话分片按类型分发。这些方法让它走
    // **服务**而不是直接拿 [agent] / [invoke] 分片 —— 域间依赖因此是一份可评审的清单，
    // 分片类型也不会漏到别的域的源码里。

    fun handleOnAgentSession(envelope: Envelope) = agent.handleOnAgentSession(envelope)
    fun handleOnAgentEvent(envelope: Envelope) = agent.handleOnAgentEvent(envelope)
    fun handleOnAgentApproval(envelope: Envelope) = agent.handleOnAgentApproval(envelope)
    fun handleOnAgentAck(envelope: Envelope) = agent.handleOnAgentAck(envelope)
    fun handleOnAgentQueue(envelope: Envelope) = agent.handleOnAgentQueue(envelope)
    fun handleOnAgentRun(envelope: Envelope) = agent.handleOnAgentRun(envelope)
    fun handleOnAgentCancel(envelope: Envelope) = agent.handleOnAgentCancel(envelope)
    fun handleOnAgentArtifact(envelope: Envelope) = agent.handleOnAgentArtifact(envelope)
    fun handleOnAgentWorkspace(envelope: Envelope) = agent.handleOnAgentWorkspace(envelope)
    fun handleOnAgentGrant(envelope: Envelope) = agent.handleOnAgentGrant(envelope)
    fun handleOnAgentGrantList(envelope: Envelope) = agent.handleOnAgentGrantList(envelope)
    fun handleOnAgentToolRulesList(envelope: Envelope) = agent.handleOnAgentToolRulesList(envelope)
    fun handleOnAgentInvoke(envelope: Envelope) = invoke.handleOnAgentInvoke(envelope)
    fun handleOnAgentInvokeResult(envelope: Envelope) = invoke.handleOnAgentInvokeResult(envelope)
    fun intentExecutorPresent(): Boolean = invoke.intentExecutorPresent()
    fun handleInboundAgentIntent(
        groupId: String,
        senderNode: String,
        intent: JsonObject?,
        messageText: String? = null,
        messageId: String? = null,
        isSeatSyncRetry: Boolean = false,
    ) = invoke.handleInboundAgentIntent(groupId, senderNode, intent, messageText, messageId, isSeatSyncRetry)

    override val state: StateFlow<RelayUiState> get() = host.state

    override fun updateAgentState(transform: (RelayUiState) -> RelayUiState) {
        host._state.update(transform)
    }

    override val agentStore: AgentStore get() = host.agentStore

    override val scope: CoroutineScope get() = host.scope

    override val runtime: NodeRuntime? get() = host.runtime

    override val messageStore: MessageStore get() = host.messageStore

    override val uiTexts: RelayUiTexts get() = host.uiTexts

    override val jobs: MutableList<Job> get() = host.jobs

    override val onLocalSessionPageRequest:
        ((peerId: String, wsId: String, offset: Long, limit: Long?) -> Boolean)?
        get() = host.onLocalSessionPageRequest

    override fun localMessageId(): String = host.localMessageId()

    override fun syncWorkspaceGroups() = host.groupsCtl.handleSyncWorkspaceGroups()

    override fun warn(message: BannerMessage) = host.warn(message)

    override fun showNotification(title: String, body: String, focusPeerId: String?) =
        host.showNotification(title, body, focusPeerId)

    override fun onChannelResultObserved(
        envelope: Envelope,
        channelId: String,
        ok: Boolean,
        status: String,
        summary: String?,
        error: String?,
    ) = host.onChannelResultObserved(envelope, channelId, ok, status, summary, error)

    override fun handleAppendMessage(message: MessageUi, incoming: Boolean) =
        host.handleAppendMessage(message, incoming)

    override fun handleReplaceMessage(peerId: String, message: MessageUi) =
        host.handleReplaceMessage(peerId, message)
}
