// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.AppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 传输域的**宿主缝**：文件传输、共享柜、投屏三个分片只允许碰这里列出的成员。
 *
 * 与 [AgentDomainHost] / [NodeDomainHost] 同一形状与同一理由：依赖面写在类型上，
 * 越界成员编译期报错；状态写入只有 [updateTransferState] 一条路。
 *
 * ## 为什么这里还看得见 [core] 与 [stateLock]
 *
 * 传输的几张表（`transferMessages` / `transferConvs` / `groupTransferIds`）与消息列表
 * 同住在 `RelayCoreState`，同一把阻塞锁也同时保护「聊天线程」与「传输表」——
 * 两族在真机上会并发读写（见 [RelayCoreState] 的 KDoc：启动后 104ms 的
 * `ConcurrentModificationException`）。把它们从本接口里藏掉只会让两边各拿一份视图，
 * 真正要做的是 Phase 3 第三步「按点替换 StateLock」：先证明每处锁保护的不变量，
 * 再把表按域切走。在那之前，这里是**显式记账**的耦合，而不是偷偷绕过的耦合。
 */
internal interface TransferDomainHost {
    /** 当前 UI 状态的只读视图（跨域共享的单一事实源，见 [RelayUiState]）。 */
    val state: StateFlow<RelayUiState>

    /** 唯一的写入口：改表 → 同一临界区里提交（见 [RelayStateWriter]）。 */
    fun updateTransferState(transform: (RelayUiState) -> RelayUiState)

    /** 表已改完、只是通知 UI 一次。 */
    fun publish()

    /** 跨域共享的可变表（见接口 KDoc 里「为什么这里还看得见 core」）。 */
    val core: RelayCoreState

    /** 跨域共享的阻塞锁（可重入；持锁期间不得挂起）。 */
    val stateLock: StateLock

    /** 控制器的协程作用域。 */
    val scope: CoroutineScope

    /** 本机节点运行时；null = 节点没在跑（传输/投屏路径据此诚实降级）。 */
    val runtime: NodeRuntime?

    /** 本机的平台能力（文件选择、缓存目录、投屏入口）。 */
    val platform: AppPlatform

    /** 立刻按当前状态刷一遍节点表。 */
    fun refreshPeers()

    /** 警告 / 信息横幅。 */
    fun warn(message: BannerMessage)
    fun info(message: BannerMessage)

    /** 生成本机乐观消息 id。 */
    fun localMessageId(): String

    /** 横切：插一条消息（按 `at` 归位、可选计未读）。 */
    fun handleAppendMessage(message: MessageUi, incoming: Boolean = false)

    /** 横切：按新 id 整条替换（乐观插入的临时 id → 真实 envelope id）。 */
    fun handleReplaceMessage(peerId: String, message: MessageUi)

    /** 横切：把内存里的消息表快照落盘。 */
    fun handlePersistThreads()

    /** 横切：设置落盘 + 推状态。 */
    fun handlePersist(settings: RelaySettings)
}

/**
 * 传输域服务：文件 offer/accept/续传/残片/传输日志（[transfers]）、共享柜授权与上下行
 * （[cabinet]）、投屏会话协商与帧流（[screen]）的**唯一装配点**。
 *
 * 行为与拆分前逐字一致 —— 本类只做转发与归属，不新增判据、不改状态形状。
 * 主类 [RelayController] 仍是唯一公开门面，把三族继续以同名成员暴露，内部改为此处委托。
 */
internal class TransferDomainService(
    private val host: RelayController,
) : TransferDomainHost {

    /** 文件 offer/accept、续传、残片、传输日志。 */
    val transfers = RelayControllerTransfersComponent(this)

    /** 共享柜授权、浏览、上下行。 */
    val cabinet = RelayControllerCabinetComponent(this)

    /** 投屏会话协商与帧流（与文件直传共用数据平面）。 */
    val screen = RelayControllerScreenComponent(this)

    // ---- 跨域入站路由（供 ConversationDomainService 的端口转发）----
    //
    // 会话域是入站总分发处，这两个方法让「传输域对外提供什么」写在服务上，
    // 而不是让会话域直接拿 [screen] / [cabinet] 分片。
    fun handleOnScreenEnvelope(envelope: Envelope) = screen.handleOnScreenEnvelope(envelope)

    fun handleOnShareEnvelope(envelope: Envelope) = cabinet.handleOnShareEnvelope(envelope)

    override val state: StateFlow<RelayUiState> get() = host.state

    override fun updateTransferState(transform: (RelayUiState) -> RelayUiState) {
        host._state.update(transform)
    }

    override fun publish() = host.publish()

    override val core: RelayCoreState get() = host.core

    override val stateLock: StateLock get() = host.stateLock

    override val scope: CoroutineScope get() = host.scope

    override val runtime: NodeRuntime? get() = host.runtime

    override val platform: AppPlatform get() = host.platform

    override fun refreshPeers() = host.refreshPeers()

    override fun warn(message: BannerMessage) = host.warn(message)

    override fun info(message: BannerMessage) = host.info(message)

    override fun localMessageId(): String = host.localMessageId()

    override fun handleAppendMessage(message: MessageUi, incoming: Boolean) =
        host.handleAppendMessage(message, incoming)

    override fun handleReplaceMessage(peerId: String, message: MessageUi) =
        host.handleReplaceMessage(peerId, message)

    override fun handlePersistThreads() = host.handlePersistThreads()

    override fun handlePersist(settings: RelaySettings) = host.handlePersist(settings)
}
