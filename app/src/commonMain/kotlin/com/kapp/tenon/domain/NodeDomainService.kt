// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.AppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 节点域的**宿主缝**：网段探测、头像/资料交换、设置落盘与远程中继三个分片只允许碰这里列出的成员。
 *
 * 与 [AgentDomainHost] 同一形状与同一理由：分片过去构造在 `host: RelayController` 上，
 * 「只碰节点域状态」全靠人守；现在依赖面写在类型上，越界成员编译期就报错。
 *
 * 状态读写只有一条路：[state] 读、[updateNodeState] 写 —— 分片不再直接拿
 * `MutableStateFlow`。Phase 4 把节点状态切成独立流时，改的是 [NodeDomainService]
 * 里的一个实现，而不是散落在三个分片里的二十来个 `update` 调用点。
 *
 * 跨域动作走宿主方法而不是直接持有别的分片（[clearConversation] 即原先 Settings
 * 直接调消息分片的那一处）。
 */
internal interface NodeDomainHost {
    /** 当前 UI 状态的只读视图（跨域共享的单一事实源，见 [RelayUiState]）。 */
    val state: StateFlow<RelayUiState>

    /** 唯一的写入口：改表 → 同一临界区里提交（见 [RelayStateWriter]）。 */
    fun updateNodeState(transform: (RelayUiState) -> RelayUiState)

    /** 表已改完、只是通知 UI 一次（不产生新状态值）。 */
    fun publish()

    /** 控制器的协程作用域（生命周期与控制器一致）。 */
    val scope: CoroutineScope

    /** 本机的平台能力（Android 前台服务、HTTP 客户端、文件选择都从这里拿）。 */
    val platform: AppPlatform

    /** 本机节点运行时；null = 节点没在跑（扫描/中继路径据此诚实降级）。 */
    val runtime: NodeRuntime?

    /** 控制器持有的后台任务列表（停止时统一取消）。 */
    val jobs: MutableList<Job>

    /** 「聊天记录落盘」开关的本机值（设置分片读写它，与 `settings.persistMessages` 同步维护）。 */
    var persistMessages: Boolean

    /** 主题档位的当前值（读 `settings.themeMode` 的派生）。 */
    val themeMode: String

    /** 远程中继客户端与它的轮询任务/健康态/游标（设置分片是唯一写入方）。 */
    var remoteRelayClient: RemoteRelayClient?
    var remoteRelayHealthy: Boolean
    var remotePollingJob: Job?
    var lastRemoteSeq: Long

    /** 立刻按当前状态刷一遍节点表（轮询之外的一次主动刷新）。 */
    fun refreshPeers()

    /** 重启节点运行时（改昵称/端口/能力之后）。 */
    suspend fun restart()

    /** 信息横幅 / 警告横幅。 */
    fun info(message: BannerMessage)
    fun warn(message: BannerMessage)

    /** 设置落盘 + 推状态（`RelayControllerOps` 的横切事务）。 */
    fun handlePersist(settings: RelaySettings)

    /** 把内存里的消息表快照落盘（`RelayControllerOps` 的横切事务）。 */
    fun handlePersistThreads()

    /** 横切：插一条消息（按 `at` 归位、可选计未读）。 */
    fun handleAppendMessage(message: MessageUi, incoming: Boolean = false)

    /** 跨域动作：清空一个会话的消息（原先直接调消息分片，现在走宿主收口）。 */
    fun clearConversation(convId: String)
}

/**
 * 节点域服务：网段探测（[cidr]）、头像与资料交换（[avatar]）、设置落盘与远程中继（[settings]）
 * 的**唯一装配点**。
 *
 * 行为与拆分前逐字一致 —— 本类只做转发与归属，不新增判据、不改状态形状。
 * 主类 [RelayController] 仍是唯一公开门面，把三族继续以同名成员暴露，内部改为此处委托。
 *
 * 节点生命周期、发现与配对（主类那 2400 行装配面）暂不搬：它是门面本身，
 * 搬它等于换一次整类边界，留到 Phase 3 收尾单独评审。
 */
internal class NodeDomainService(
    private val host: RelayController,
) : NodeDomainHost {

    /** CIDR 网段探测与 Gossip 同步。 */
    val cidr = RelayControllerCidrComponent(this)

    /** 节点头像与资料交换。 */
    val avatar = RelayControllerAvatarComponent(this)

    /** 设置项写入、远程中继装配。 */
    val settings = RelayControllerSettingsComponent(this)

    // ---- 跨域入站路由（供 ConversationDomainService 的端口转发）----
    //
    // 会话域是入站总分发处，这两个方法让「节点域对外提供什么」写在服务上，
    // 而不是让会话域直接拿 [cidr] / [avatar] 分片。
    fun handleOnScanRangesReceived(envelope: Envelope) = cidr.handleOnScanRangesReceived(envelope)

    fun handleOnAvatarEnvelope(envelope: Envelope) = avatar.handleOnAvatarEnvelope(envelope)

    override val state: StateFlow<RelayUiState> get() = host.state

    override fun updateNodeState(transform: (RelayUiState) -> RelayUiState) {
        host._state.update(transform)
    }

    override fun publish() = host.publish()

    override val scope: CoroutineScope get() = host.scope

    override val platform: AppPlatform get() = host.platform

    override val runtime: NodeRuntime? get() = host.runtime

    override val jobs: MutableList<Job> get() = host.jobs

    override var persistMessages: Boolean
        get() = host.persistMessages
        set(value) { host.persistMessages = value }

    override val themeMode: String get() = host.themeMode

    override var remoteRelayClient: RemoteRelayClient?
        get() = host.remoteRelayClient
        set(value) { host.remoteRelayClient = value }

    override var remoteRelayHealthy: Boolean
        get() = host.remoteRelayHealthy
        set(value) { host.remoteRelayHealthy = value }

    override var remotePollingJob: Job?
        get() = host.remotePollingJob
        set(value) { host.remotePollingJob = value }

    override var lastRemoteSeq: Long
        get() = host.lastRemoteSeq
        set(value) { host.lastRemoteSeq = value }

    override fun refreshPeers() = host.refreshPeers()

    override suspend fun restart() = host.restart()

    override fun info(message: BannerMessage) = host.info(message)

    override fun warn(message: BannerMessage) = host.warn(message)

    override fun handlePersist(settings: RelaySettings) = host.handlePersist(settings)

    override fun handlePersistThreads() = host.handlePersistThreads()

    override fun handleAppendMessage(message: MessageUi, incoming: Boolean) =
        host.handleAppendMessage(message, incoming)

    override fun clearConversation(convId: String) = host.messagesCtl.handleClearMessages(convId)
}
