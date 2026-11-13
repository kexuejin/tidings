// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.ScreenRecordUi
import com.kapp.tenon.platform.AppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject

/**
 * 会话域对 **agent 域**的跨域操作面：只列会话域真正用到的那几条入站路由与派单入口。
 *
 * 为什么要有端口而不是直接拿 agent 分片：会话域是入站总分发处（一个信封进来先由
 * 会话分片按类型分发），它天然会碰其它三域的入口。直接持有 `agentCtl` 的话，
 * 「会话域到底依赖 agent 域什么」在源码上看不见，且下一次改动可以随手多调一个方法。
 * 端口把这份依赖列成一份清单：多用一条就要先改这里，评审时看得见。
 */
internal interface ConversationAgentPort {
    fun handleOnAgentSession(envelope: Envelope)
    fun handleOnAgentEvent(envelope: Envelope)
    fun handleOnAgentApproval(envelope: Envelope)
    fun handleOnAgentAck(envelope: Envelope)
    fun handleOnAgentQueue(envelope: Envelope)
    fun handleOnAgentRun(envelope: Envelope)
    fun handleOnAgentCancel(envelope: Envelope)
    fun handleOnAgentArtifact(envelope: Envelope)
    fun handleOnAgentWorkspace(envelope: Envelope)
    fun handleOnAgentGrant(envelope: Envelope)
    fun handleOnAgentGrantList(envelope: Envelope)
    fun handleOnAgentToolRulesList(envelope: Envelope)
    fun handleOnAgentInvoke(envelope: Envelope)
    fun handleOnAgentInvokeResult(envelope: Envelope)
    fun intentExecutorPresent(): Boolean
    fun handleInboundAgentIntent(
        groupId: String,
        senderNode: String,
        intent: JsonObject?,
        messageText: String? = null,
        messageId: String? = null,
        isSeatSyncRetry: Boolean = false,
    )
}

/** 会话域对**传输域**的跨域操作面：投屏与共享柜的入站信封路由。 */
internal interface ConversationTransferPort {
    fun handleOnScreenEnvelope(envelope: Envelope)
    fun handleOnShareEnvelope(envelope: Envelope)
}

/** 会话域对**节点域**的跨域操作面：网段扫描结果与头像信封的入站路由。 */
internal interface ConversationNodePort {
    fun handleOnScanRangesReceived(envelope: Envelope)
    fun handleOnAvatarEnvelope(envelope: Envelope)
}

/**
 * 会话域的**宿主缝**：消息本体、群、小游戏卡、投屏记录四个分片只允许碰这里列出的成员。
 *
 * 与 [AgentDomainHost] / [NodeDomainHost] / [TransferDomainHost] 同一形状与同一理由。
 * 额外三条跨域端口（[agent] / [transfer] / [node]）是本域作为**入站总分发处**必须的：
 * 信封按类型分发给各域，但分片看不到别的域的组件类型，只能看到端口上的方法。
 *
 * 本域**显式保留** [core] 与 [stateLock]：消息表、群元数据、传输表同住 [RelayCoreState]、
 * 共用同一把阻塞锁。那是 Phase 3 第三步「按点替换 StateLock」要清的耦合 —— 先证每处锁
 * 保护的不变量，再按域切表；在那之前这里是显式记账，不假装已经消失。
 *
 * 本域分片对 UI 状态是**只读**的（`state`）：写入一律先改 [core] 里的表，再 [publish]。
 * 这正是 [RelayCoreState] KDoc 定的纪律（「改表 → 同一临界区里 commit」），抽取前后一致。
 */
internal interface ConversationDomainHost {
    /** 当前 UI 状态的只读视图（跨域共享的单一事实源）。 */
    val state: StateFlow<RelayUiState>

    /** 表已改完、通知 UI 一次（本域没有第二条写 UI 状态的路）。 */
    fun publish()

    /** 跨域共享的可变表（见接口 KDoc 里「显式保留 core / stateLock」）。 */
    val core: RelayCoreState

    /** 跨域共享的阻塞锁（可重入；持锁期间不得挂起）。 */
    val stateLock: StateLock

    /** 控制器的协程作用域。 */
    val scope: CoroutineScope

    /** 本机的平台能力。 */
    val platform: AppPlatform

    /** 本机节点运行时；null = 节点没在跑。 */
    val runtime: NodeRuntime?

    /** 当前运行时（恢复路径问它「现在能不能发」）。 */
    fun activeRuntime(): NodeRuntime?

    /** 立刻按当前状态刷一遍节点表。 */
    fun refreshPeers()

    /** 消息存储。 */
    val messageStore: MessageStore

    /** 「聊天记录落盘」开关的本机值（本域只读）。 */
    val persistMessages: Boolean

    /** 落盘互斥（快照在锁内取、写盘在锁外做）。 */
    val persistMutex: Mutex

    /** 启动恢复是否已跑过（只跑一次）。 */
    var messagesRestored: Boolean

    /** 远程中继客户端（消息分片首个入站时懒建）。 */
    var remoteRelayClient: RemoteRelayClient?

    /** 领域层取一句话的桥（见 [RelayUiTexts]）。 */
    val uiTexts: RelayUiTexts

    /** 警告 / 信息横幅、系统通知。 */
    fun warn(message: BannerMessage)
    fun info(message: BannerMessage)
    fun showNotification(title: String, body: String, focusPeerId: String? = null)

    /** 投屏记录卡的正文（取不到文案时返回空串，不编中文）。 */
    suspend fun screenCardTextOf(record: ScreenRecordUi): String

    /** 横切：插一条消息（按 `at` 归位、可选计未读）。 */
    fun handleAppendMessage(message: MessageUi, incoming: Boolean = false)

    /** 横切：按新 id 整条替换（乐观插入的临时 id → 真实 envelope id）。 */
    fun handleReplaceMessage(peerId: String, message: MessageUi)

    /** 横切：按旧 id 找、整条换成新消息（可以换 id —— 乐观插入的临时 id 换成真实 id）。 */
    fun replaceMessage(peerId: String, previousId: String, message: MessageUi)

    /** 横切：把内存里的消息表快照落盘。 */
    fun handlePersistThreads()

    /** 横切：设置落盘 + 推状态。 */
    fun handlePersist(settings: RelaySettings)

    /** 生成本机乐观消息 id。 */
    fun localMessageId(): String

    /** 已完成的后台任务占位（恢复路径用它表达「这轮没有真正的工作」）。 */
    fun completedJob(): Job

    /** 请求界面把某个会话滚到可见（一次性的 UI 请求）。 */
    fun requestFocus(peerId: String)

    /** 这个群是否声明了可执行的 agent（群路由判据）。 */
    fun groupHasDeclaredAgents(groupId: String): Boolean

    /**
     * 兑现「别处建的群」对本机提的目录请求（UX 规范 §2.6 手机侧；判据本体在
     * `RelayController.adoptRequestedGroupDirectories`）。
     *
     * 为什么走这条缝：群元数据在**本域**落地（`handleApplyGroupMeta`），而目录台账
     * （`hostDirectoryPort`）在控制器本机 —— 两边都要碰。让群分片直接拿 `RelayController`
     * 会把整类依赖面重新开放；加一个方法只开放这一件事。
     *
     * @return 本次真的补上的群数（本机没有目录端口的壳恒 0）。
     */
    fun adoptRequestedGroupDirectories(): Int

    /** @ 了本机 agent 但没权限时的那句提示（null = 不需要提示）。 */
    fun unathorizedAgentMention(text: String, groupId: String, senderId: String?): String?

    /** 群元数据补昵称（发送侧唯一写入点，见实现处的真机事故注记）。 */
    fun groupMetaWithNicks(meta: GroupMeta): GroupMeta

    /** 群内 agent 路由事实（本地/远端 agent 是否在位）。 */
    fun agentRouteFactsOf(groupId: String): GroupAgentRouteFacts

    /** 壳层桥：agent 提示入口（null = 本机没有执行器；末参 = 原文点名的键，未点名则 null）。 */
    val onAgentPrompt: (suspend (
        convId: String,
        senderPeerId: String,
        prompt: String,
        replyToId: String?,
        mentionedAgentKey: String?,
    ) -> Unit)?

    /** 壳层桥：工单追问拦截（返回 true = 已消费）。 */
    val onAgentFollowUp: ((convId: String, senderPeerId: String, text: String) -> Boolean)?

    /** 壳层桥：打断拦截（返回 true = 已消费）。 */
    val onAgentAbort: ((convId: String, text: String) -> Boolean)?

    /** 壳层桥：群模板配置拦截（§16.6 入口 A；返回 true = 已消费，false = 句式不匹配）。 */
    val onDelegationTeamConfig: ((convId: String, text: String) -> Boolean)?

    /** 壳层桥：快捷指令拦截（§9.2；返回 true = 已消费，false = 不是快捷指令）。 */
    val onQuickAction: ((convId: String, text: String) -> Boolean)?

    /** 壳层桥：诊断日志（§7.2；null = 不记 —— 手机端。宿主接 RelayLog，`:cli` 接 stdout）。 */
    val diagnosticLogSink: ((tag: String, message: String) -> Unit)?

    /** 本域自己的群分片（消息分片的入站总分发会调它）。 */
    val groups: RelayControllerGroupsComponent

    /** 本域自己的小游戏卡分片。 */
    val pk: RelayControllerPkComponent

    /** 跨域：agent 域操作面。 */
    val agent: ConversationAgentPort

    /** 跨域：传输域操作面。 */
    val transfer: ConversationTransferPort

    /** 跨域：节点域操作面。 */
    val node: ConversationNodePort
}

/**
 * 会话域服务：消息本体（[messages]）、群（[groups]）、小游戏卡（[pk]）、投屏记录（[screenHistory]）
 * 的**唯一装配点**。
 *
 * 行为与拆分前逐字一致 —— 本类只做转发与归属，不新增判据、不改状态形状。
 * 主类 [RelayController] 仍是唯一公开门面，把四族继续以同名成员暴露，内部改为此处委托。
 *
 * ## 跨域只走端口
 *
 * 三个端口（[agent] / [transfer] / [node]）在本类里实现，实现只调用**其它域服务的方法**
 * （`AgentDomainService.handleOnAgentSession` 一类），不直接碰 `agentCtl` / `screenCtl` 等
 * 分片。这样「会话域依赖别的域什么」集中在一处可评审，分片也拿不到别人的组件类型。
 */
internal class ConversationDomainService(
    private val host: RelayController,
    agentDomain: AgentDomainService,
    transferDomain: TransferDomainService,
    nodeDomain: NodeDomainService,
) : ConversationDomainHost {

    /** 单聊消息本体：发送、撤回、补发、落盘、检索。 */
    val messages = RelayControllerMessagesComponent(this)

    /** 建群/退群、群资料、成员、群消息。 */
    override val groups = RelayControllerGroupsComponent(this)

    /** 会话内小游戏卡（比大小/猜拳）。 */
    override val pk = RelayControllerPkComponent(this)

    /** 投屏记录 upsert 成聊天消息。 */
    val screenHistory = RelayControllerScreenHistoryComponent(this)

    override val agent: ConversationAgentPort = object : ConversationAgentPort {
        override fun handleOnAgentSession(envelope: Envelope) = agentDomain.handleOnAgentSession(envelope)
        override fun handleOnAgentEvent(envelope: Envelope) = agentDomain.handleOnAgentEvent(envelope)
        override fun handleOnAgentApproval(envelope: Envelope) = agentDomain.handleOnAgentApproval(envelope)
        override fun handleOnAgentAck(envelope: Envelope) = agentDomain.handleOnAgentAck(envelope)
        override fun handleOnAgentQueue(envelope: Envelope) = agentDomain.handleOnAgentQueue(envelope)
        override fun handleOnAgentRun(envelope: Envelope) = agentDomain.handleOnAgentRun(envelope)
        override fun handleOnAgentCancel(envelope: Envelope) = agentDomain.handleOnAgentCancel(envelope)
        override fun handleOnAgentArtifact(envelope: Envelope) = agentDomain.handleOnAgentArtifact(envelope)
        override fun handleOnAgentWorkspace(envelope: Envelope) = agentDomain.handleOnAgentWorkspace(envelope)
        override fun handleOnAgentGrant(envelope: Envelope) = agentDomain.handleOnAgentGrant(envelope)
        override fun handleOnAgentGrantList(envelope: Envelope) = agentDomain.handleOnAgentGrantList(envelope)
        override fun handleOnAgentToolRulesList(envelope: Envelope) = agentDomain.handleOnAgentToolRulesList(envelope)
        override fun handleOnAgentInvoke(envelope: Envelope) = agentDomain.handleOnAgentInvoke(envelope)
        override fun handleOnAgentInvokeResult(envelope: Envelope) = agentDomain.handleOnAgentInvokeResult(envelope)
        override fun intentExecutorPresent(): Boolean = agentDomain.intentExecutorPresent()
        override fun handleInboundAgentIntent(
            groupId: String,
            senderNode: String,
            intent: JsonObject?,
            messageText: String?,
            messageId: String?,
            isSeatSyncRetry: Boolean,
        ) = agentDomain.handleInboundAgentIntent(
            groupId, senderNode, intent, messageText, messageId, isSeatSyncRetry,
        )
    }

    override val transfer: ConversationTransferPort = object : ConversationTransferPort {
        override fun handleOnScreenEnvelope(envelope: Envelope) = transferDomain.handleOnScreenEnvelope(envelope)
        override fun handleOnShareEnvelope(envelope: Envelope) = transferDomain.handleOnShareEnvelope(envelope)
    }

    override val node: ConversationNodePort = object : ConversationNodePort {
        override fun handleOnScanRangesReceived(envelope: Envelope) = nodeDomain.handleOnScanRangesReceived(envelope)
        override fun handleOnAvatarEnvelope(envelope: Envelope) = nodeDomain.handleOnAvatarEnvelope(envelope)
    }

    override val state: StateFlow<RelayUiState> get() = host.state

    override fun publish() = host.publish()

    override val core: RelayCoreState get() = host.core

    override val stateLock: StateLock get() = host.stateLock

    override val scope: CoroutineScope get() = host.scope

    override val platform: AppPlatform get() = host.platform

    override val runtime: NodeRuntime? get() = host.runtime

    override fun activeRuntime(): NodeRuntime? = host.activeRuntime()

    override fun refreshPeers() = host.refreshPeers()

    override val messageStore: MessageStore get() = host.messageStore

    override val persistMessages: Boolean get() = host.persistMessages

    override val persistMutex: Mutex get() = host.persistMutex

    override var messagesRestored: Boolean
        get() = host.messagesRestored
        set(value) { host.messagesRestored = value }

    override var remoteRelayClient: RemoteRelayClient?
        get() = host.remoteRelayClient
        set(value) { host.remoteRelayClient = value }

    override val uiTexts: RelayUiTexts get() = host.uiTexts

    override fun warn(message: BannerMessage) = host.warn(message)

    override fun info(message: BannerMessage) = host.info(message)

    override fun showNotification(title: String, body: String, focusPeerId: String?) =
        host.showNotification(title, body, focusPeerId)

    override suspend fun screenCardTextOf(record: ScreenRecordUi): String = host.screenCardTextOf(record)

    override fun handleAppendMessage(message: MessageUi, incoming: Boolean) =
        host.handleAppendMessage(message, incoming)

    override fun handleReplaceMessage(peerId: String, message: MessageUi) =
        host.handleReplaceMessage(peerId, message)

    override fun replaceMessage(peerId: String, previousId: String, message: MessageUi) =
        host.replaceMessage(peerId, previousId, message)

    override fun handlePersistThreads() = host.handlePersistThreads()

    override fun handlePersist(settings: RelaySettings) = host.handlePersist(settings)

    override fun localMessageId(): String = host.localMessageId()

    override fun completedJob(): Job = host.completedJob()

    override fun requestFocus(peerId: String) = host.requestFocus(peerId)

    override fun groupHasDeclaredAgents(groupId: String): Boolean = host.groupHasDeclaredAgents(groupId)

    override fun adoptRequestedGroupDirectories(): Int = host.adoptRequestedGroupDirectories()

    override fun unathorizedAgentMention(text: String, groupId: String, senderId: String?): String? =
        host.unathorizedAgentMention(text, groupId, senderId)

    override fun groupMetaWithNicks(meta: GroupMeta): GroupMeta = host.groupMetaWithNicks(meta)

    override fun agentRouteFactsOf(groupId: String): GroupAgentRouteFacts = host.agentRouteFactsOf(groupId)

    override val onAgentPrompt: (suspend (
        convId: String,
        senderPeerId: String,
        prompt: String,
        replyToId: String?,
        mentionedAgentKey: String?,
    ) -> Unit)?
        get() = host.onAgentPrompt

    override val onAgentFollowUp: ((convId: String, senderPeerId: String, text: String) -> Boolean)?
        get() = host.onAgentFollowUp

    override val onAgentAbort: ((convId: String, text: String) -> Boolean)?
        get() = host.onAgentAbort

    override val onDelegationTeamConfig: ((convId: String, text: String) -> Boolean)?
        get() = host.onDelegationTeamConfig

    override val onQuickAction: ((convId: String, text: String) -> Boolean)?
        get() = host.onQuickAction

    override val diagnosticLogSink: ((tag: String, message: String) -> Unit)?
        get() = host.diagnosticLogSink
}
