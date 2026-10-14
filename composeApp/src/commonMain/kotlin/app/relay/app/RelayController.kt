// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.relay.core.fs.pathJoin
import app.relay.core.ioDispatcher
import app.relay.core.nowMillis
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.DEFAULT_TIMINGS
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgKinds
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.Timings
import app.relay.core.session.NodeRuntime
import app.relay.core.persist.PersistStores
import app.relay.core.transport.DedupStore
import app.relay.core.transport.LocalFile
import app.relay.core.transport.ManualPeer
import app.relay.core.transport.PartFile
import app.relay.core.transport.PeerEvent
import app.relay.core.transport.PeerRecord
import app.relay.core.transport.SendOutcome
import app.relay.core.transport.TransferEvent
import app.relay.core.transport.dedupLogPath
import app.relay.platform.AppPlatform
import app.relay.platform.CameraUnavailableException
import app.relay.platform.NetworkSnapshot
import app.relay.platform.ReceivedFileActions
import app.relay.ui.desktop.DesktopPaneActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 版本号会作为协议 `Profile.ver` 发给对端（≤16 字符）。 */
import app.relay.feature.update.AppUpdateChecker
import app.relay.feature.update.DefaultAppUpdateChecker
import app.relay.feature.update.UpdateCheckState
import app.relay.feature.update.UpdateCheckStatus
import app.relay.app.model.DeliveryState
import app.relay.app.model.MdnsServiceUi
import app.relay.app.model.MessageUi
import app.relay.app.model.NetworkUi
import app.relay.app.model.NodePhase
import app.relay.app.model.PeerUi

const val APP_VERSION: String = "relay-0.1.0"

/**
 * 把 [NodeRuntime] 接到 Compose 上的唯一状态持有者与门面协调器（Facade Coordinator）。
 *
 * 核心领域逻辑已模块化解耦到专用领域组件：
 * - 群组协议与成员管理：[RelayControllerGroups.kt]
 * - 文件传输与分片管理：[RelayControllerTransfers.kt]
 * - 消息流、去重与撤回：[RelayControllerMessages.kt]
 * - 设置持久化与远程中继：[RelayControllerSettings.kt]
 * - Agent 智能体通信：[RelayControllerAgent.kt]
 */
class RelayController(
    internal val platform: AppPlatform,
    internal val store: SettingsStore,
    internal val scope: CoroutineScope,
    val version: String = APP_VERSION,
    internal val messageStore: MessageStore = InMemoryMessageStore(),
    internal val agentStore: AgentStore = InMemoryAgentStore(),
    private val timings: Timings = DEFAULT_TIMINGS,
    internal var updateChecker: AppUpdateChecker = DefaultAppUpdateChecker(platform),
    /**
     * 这个宿主是否**承载 agent 节点**（设计文档 §2 方案 A，2026-10-14 用户裁决）。
     *
     * true 只有桌面端：它有系统托盘、能开机自启、关窗只是隐藏 —— 是机器上最天然的
     * 常驻者，所以由它来当那个「让本机 AI 上线」的节点（固定回环 8788 + 广播 `ag1`）。
     *
     * false 是手机与该仓库的所有测试：手机不承载 AI 会话，声明 `ag1` 会骗对端
     * （对方把会话帧推过来，我们只 ACK、业务层丢弃）。
     *
     * 默认 false ⇒ 既有行为一字不变；桌面端在 `Main` 里显式传 true。
     */
    private val advertiseAgent: Boolean = false,
) : DesktopPaneActions {

    internal val _state: MutableStateFlow<RelayUiState> = MutableStateFlow(
        // 首次运行的默认设备名按**宿主**给（2026-10-14 修真机撞名）：桌面端与手机是同一份
        // 模型的两个宿主，默认名不同。`advertiseAgent` 就是区分它们的那个标志（见它的 KDoc）。
        RelayUiState(
            settings = (store.load() ?: RelaySettings.firstRun()).let { loaded ->
                // 两个宿主各自的默认名在这里补齐（2026-10-14 修真机撞名）。
                //
                // 为什么不能只在 `firstRun()` 里给：**已经建过 settings.json 的老库**不会走
                // 那条路，而它们的 nick 可能正是那个共享默认值（桌面端就是这样：文件里
                // nick 为 null，于是它顶着「我的手机」上局域网，与真手机撞名）。
                //
                // 只在**空**的时候补：用户改过的名字永远优先（这是显示名，不是身份）。
                //
                // 默认名从平台上取（见 `AppPlatform.deviceDefaultNick`）：那是一句要本地化
                // 的文案，属于平台层；写在这份共享代码里就只能所有宿主共用一个词，
                // 而那正是这次撞名的来源。
                val fallback = platform.deviceDefaultNick.ifBlank { platform.deviceInfo.hostName() }
                if (loaded.nick.isBlank()) loaded.copy(nick = fallback).also(store::save) else loaded
            },
        ),
    )
    val state: StateFlow<RelayUiState> = _state.asStateFlow()

    val dataDirectory: String = platform.dataDirectory.dataDirectory()
    val cacheDirectory: String = platform.dataDirectory.cacheDirectory()

    fun activeRuntime(): NodeRuntime? = runtime

    internal var runtime: NodeRuntime? = null
    internal var jobs = mutableListOf<Job>()

    /**
     * 控制器持有的可变会话表。搬出本类是为了让「这张表归谁」写在类型上，
     * 而不是一个 `internal` 字段 + 全靠约定的纪律。
     *
     * [RelayCoreState.lock] 是这里**唯一**的并发护栏（38 处引用，跨 6 个分片）；
     * 它同时保护 [stateLock] 这个名字的历史调用点。
     */
    internal val core = RelayCoreState()
    internal val writer = RelayStateWriter(core, _state)
    internal val stateLock: StateLock get() = core.lock

    internal val pkCtl = RelayControllerPkComponent(this)
    internal val avatarCtl = RelayControllerAvatarComponent(this)
    internal val screenHistoryCtl = RelayControllerScreenHistoryComponent(this)
    internal val cidrCtl = RelayControllerCidrComponent(this)
    internal val screenCtl = RelayControllerScreenComponent(this)
    internal val agentCtl = RelayControllerAgentComponent(this)
    internal val cabinetCtl = RelayControllerCabinetComponent(this)
    internal val groupsCtl = RelayControllerGroupsComponent(this)
    internal val settingsCtl = RelayControllerSettingsComponent(this)
    internal val transfersCtl = RelayControllerTransfersComponent(this)
    internal val messagesCtl = RelayControllerMessagesComponent(this)

    // ---- 横切操作（实现在 RelayControllerOps.kt，被 Groups / Transfers / Cidr / Cabinet 共用）----
    internal fun persist(settings: RelaySettings) = handlePersist(settings)
    internal fun appendMessage(message: MessageUi, incoming: Boolean = false) = handleAppendMessage(message, incoming)
    internal fun replaceMessage(peerId: String, message: MessageUi) = handleReplaceMessage(peerId, message)
    internal fun persistThreads() = handlePersistThreads()

    var persistMessages: Boolean = _state.value.settings.persistMessages
    internal val dedupStore: DedupStore = GatedDedupStore(dedupLogPath(dataDirectory)) { persistMessages }

    /**
     * core 三个存储契约的落盘实现，路径在 `<dataDir>/stores/` 下。
     *
     * 单独一个字段而不是就地 new 两个 store：这两个对象在构造时就会把日志读进内存，
     * 构造两次等于白读一遍文件，而「读两遍」在文件正在被写时读到的是两个不同的快照。
     */
    private val persist: PersistStores = PersistStores.under(dataDirectory)

    val themeMode: String get() = _state.value.settings.themeMode
    internal var messagesRestored: Boolean = false
    internal val persistMutex = Mutex()

    internal var remoteRelayClient: RemoteRelayClient? = null
    internal var remotePollingJob: Job? = null
    internal var lastRemoteSeq: Long = 0L
    internal var localSeq: Int = 0

    // ------------------------------------------------------------------ 委托动作：设置
    fun updatePersistMessages(enabled: Boolean) = settingsCtl.handleUpdatePersistMessages(enabled)
    fun updateThemeMode(wire: String) = settingsCtl.handleUpdateThemeMode(wire)
    fun updateWaitFrameCapability(enabled: Boolean) = settingsCtl.handleUpdateWaitFrameCapability(enabled)
    fun updateRecordMessageCapability(enabled: Boolean) = settingsCtl.handleUpdateRecordMessageCapability(enabled)
    fun updateNotifyAgentMessages(enabled: Boolean) = settingsCtl.handleUpdateNotifyAgentMessages(enabled)
    fun updateNotifyMentionOnly(enabled: Boolean) = settingsCtl.handleUpdateNotifyMentionOnly(enabled)
    fun updateNotifyQuietHours(enabled: Boolean) = settingsCtl.handleUpdateNotifyQuietHours(enabled)
    fun saveProfile(nick: String, company: String, dept: String, team: String, avatar: Int) =
        settingsCtl.handleSaveProfile(nick, company, dept, team, avatar)

    /**
     * 保存**本机**给某个节点起的备注（节点备注规范）。
     *
     * 与 [saveProfile] 的关键差别：备注**不进协议**、不升 `profileRev`、不重启节点。
     * 传空串 = 删掉这条备注。详见 `settingsCtl.handleSetPeerRemark` 的 KDoc。
     */
    override fun setPeerRemark(nodeId: String, remark: String) = settingsCtl.handleSetPeerRemark(nodeId, remark)

    /**
     * 「上传头像」页的选图 / 移除。**只动本机**，不宣告、不上网（见 `avatarCtl.handlePickAvatar` 的 KDoc）。
     *
     * 这两个是 `RelayControllerAvatar.kt` 里 `internal` 实现的公开门面：`:desktopApp` 是独立
     * Gradle 模块，`internal` 跨不过去，而桌面身份页的头像分段控件第三档要落到同一屏
     * （原型 `screenDSettings()` 的 `${avSeg()}`，`prototype/relay.html:5263`）。
     */
    fun pickAvatar() = avatarCtl.handlePickAvatar()

    /** 清掉刚选中但还没保存的那张图。 */
    fun removePickedAvatar() = avatarCtl.handleRemovePickedAvatar()

    fun savePorts(rawUdp: String, rawTcp: String) = settingsCtl.handleSavePorts(rawUdp, rawTcp)
    fun setRemoteRelay(url: String, token: String, nodeId: String? = null, nick: String? = null) =
        settingsCtl.handleSetRemoteRelay(url, token, nodeId, nick)
    fun clearRemoteRelay() = settingsCtl.handleClearRemoteRelay()
    fun clearSettingsNote() = _state.update { it.copy(settingsNote = null) }

    // ------------------------------------------------------------------ 委托动作：消息与聊天
    fun send(peerId: String, text: String) = messagesCtl.handleSend(peerId, text)
    override fun sendToConversation(convId: String, text: String, replyTo: String?) {
        val groupId = ConvId.groupIdOf(convId)
        if (groupId != null) {
            sendToGroup(groupId, text, replyTo = replyTo)
        } else {
            send(convId, text)
        }
    }
    fun sendToGroup(groupId: String, text: String, mentions: List<String>? = null, replyTo: String? = null) =
        groupsCtl.handleSendToGroup(groupId, text, mentions, replyTo)
    override fun sendPk(convId: String, game: String, value: String) = pkCtl.handleSendPk(convId, game, value)
    override fun forwardMessage(message: MessageUi, targetConvId: String) {
        val body = message.text.ifBlank { message.file?.name.orEmpty() }
        if (body.isBlank()) return
        sendToConversation(targetConvId, body)
        info(BannerMessage.ForwardSuccess)
    }
    override fun recall(message: MessageUi) = messagesCtl.handleRecall(message)
    override fun nudge(peerId: String) = messagesCtl.handleNudge(peerId)
    fun clearMessages(peerId: String) = messagesCtl.handleClearMessages(peerId)
    fun clearAllMessages() = messagesCtl.handleClearAllMessages()
    override fun searchHistoricalMessages(query: String, limit: Long): List<MessageSearchResult> =
        messageStore.search(query, limit)
    override fun searchGlobal(query: String): GlobalSearchResult = messagesCtl.handleSearchGlobalInternal(query)
    override fun searchInConversation(
        convId: String,
        query: String,
        filter: HistorySearchFilter,
        limit: Long,
    ): List<MessageSearchResult> = messagesCtl.handleSearchInConversationInternal(convId, query, filter, limit)
    internal fun onInbound(envelope: Envelope) = messagesCtl.handleOnInbound(envelope)

    // ------------------------------------------------------------------ 委托动作：群组
    override fun createGroup(name: String, memberIds: List<String>): String? = groupsCtl.handleCreateGroup(name, memberIds)
    override fun addGroupMembers(groupId: String, newMemberIds: List<String>) = groupsCtl.handleAddGroupMembers(groupId, newMemberIds)
    override fun removeGroupMember(groupId: String, memberId: String) = groupsCtl.handleRemoveGroupMember(groupId, memberId)
    override fun leaveGroup(groupId: String) = groupsCtl.handleLeaveGroup(groupId)
    override fun updateGroupMeta(groupId: String, description: String?, announce: String?) =
        groupsCtl.handleUpdateGroupMeta(groupId, description, announce)
    /**
     * 打开某条会话时把它标为已读。
     *
     * ⚠ 它**不记录"当前选中的是谁"** —— 那件事是 [setVisibleConversation] 管的。
     * 所以这个名字指的是调用点在做的事（用户点了某条会话），不是这个方法做的全部事。
     * 别把它当"标为已读"的通用入口用：批量标已读是 [markAllRead]。
     */
    fun selectConversation(convId: String) {
        stateLock.withLock { core.unread[convId] = 0 }
        publish()
    }

    /**
     * 「全部标为已读」（原型 `screenHomeMore` 的第 5 项，`prototype/relay.html:2861`）。
     *
     * 以前 `App.kt` 是 `conversations.forEach { selectConversation(it.convId) }` 凑出来的：
     * 效果上对（[selectConversation] 顺手清零 core.unread），但那是**借了别人的副作用** ——
     * 一个叫 `selectConversation` 的方法被当批量标已读用，后来人只会以为
     * `onMarkAllRead` 是把所有会话都选中了。这个仓库反复抓的就是这类
     * 「名字看着对、语义在别处」，所以这里给它一个说得出名字的入口。
     */
    fun markAllRead() {
        stateLock.withLock { core.unread.keys.toList().forEach { core.unread[it] = 0 } }
        publish()
    }

    override fun setVisibleConversation(convId: String?) {
        core.visibleConversationId = convId
    }

    // ------------------------------------------------------------------ 委托动作：文件传输
    override fun pickAndSendFiles(peerId: String) = transfersCtl.handlePickAndSendFiles(peerId)
    fun sendFiles(peerId: String, files: List<LocalFile>) = transfersCtl.handleSendFiles(peerId, files)
    override fun acceptTransfer(transferId: String) = transfersCtl.handleAcceptTransfer(transferId)
    override fun declineTransfer(transferId: String) = transfersCtl.handleDeclineTransfer(transferId)
    override fun cancelTransfer(transferId: String) = transfersCtl.handleCancelTransfer(transferId)
    override fun resumeTransfer(transferId: String) = transfersCtl.handleResumeTransfer(transferId)
    override fun clearTransferLogs() = transfersCtl.handleClearTransferLogs()
    fun discardPartial(path: String) = transfersCtl.handleDiscardPartial(path)
    fun leftoverParts(): List<PartFile> = transfersCtl.handleLeftoverParts()
    fun clearLeftoverParts(): Int = transfersCtl.handleClearLeftoverParts()
    internal fun onTransfer(event: TransferEvent) = transfersCtl.handleOnTransfer(event)

    // ------------------------------------------------------------------ 委托动作：共享文件柜 (Cabinet)
    override fun setShareRoot(path: String?) = cabinetCtl.handleSetShareRoot(path)
    override fun pickShareRoot() = cabinetCtl.handlePickShareRoot()
    override fun setShareMode(mode: String) = cabinetCtl.handleSetShareMode(mode)
    override fun setShareGrant(nodeId: String, mode: String) = cabinetCtl.handleSetShareGrant(nodeId, mode)
    override fun removeShareGrant(nodeId: String) = cabinetCtl.handleRemoveShareGrant(nodeId)
    override fun selectCabinetPeer(nodeId: String?) = cabinetCtl.handleSelectCabinetPeer(nodeId)
    override fun loadCabinetPath(path: String) = cabinetCtl.handleLoadCabinetPath(path)
    override fun toggleCabinetSelection(name: String) = cabinetCtl.handleToggleCabinetSelection(name)
    override fun clearCabinetSelection() = cabinetCtl.handleClearCabinetSelection()
    override fun downloadCabinetEntries(names: List<String>, saveAs: Boolean) =
        cabinetCtl.handleDownloadCabinetEntries(names, saveAs)
    override fun uploadToCabinet(peerId: String) = cabinetCtl.handleUploadToCabinet(peerId)
    override fun setCabinetViewMode(mode: String) = cabinetCtl.handleSetCabinetViewMode(mode)

    // ------------------------------------------------------------------ 委托动作：CIDR 网段探测与同步
    override fun addScanRange(cidr: String) = cidrCtl.handleAddScanRange(cidr)
    override fun removeScanRange(cidr: String) = cidrCtl.handleRemoveScanRange(cidr)
    override fun triggerCidrScan(cidr: String?) = cidrCtl.handleTriggerCidrScan(cidr)

    // ------------------------------------------------------------------ 委托动作：屏幕截取
    override fun triggerScreenCapture(targetConvId: String?) {
        val sc = platform.screenCapture
        if (sc == null || !sc.isSupported()) {
            warn(BannerMessage.CaptureUnavailable)
            return
        }
        val convId = targetConvId ?: core.visibleConversationId
        sc.startCapture { filePath ->
            if (filePath == null) {
                // 截图没有产出结果：覆盖窗里点了取消，或写盘失败 —— 服务两条路径都回 `null`，
                // 生产者分不出也不必分（文案只说"已取消"，见 `BannerMessage.CaptureCanceled`）。
                //
                // 以前这里什么都不做，于是用户点完截图、点掉覆盖窗，界面上零反馈：
                // 既没有成功消息也没有取消回执，看起来就像按钮根本没接上。
                //
                // `convId == null`（没有目标会话可发）**保持静默**：那是"截图成功但没处发"，
                // 是另一个决策（要不要提示、提示什么），本轮不动。
                info(BannerMessage.CaptureCanceled)
            } else if (convId != null) {
                val f = app.relay.core.transport.LocalFile(
                    path = filePath,
                    name = filePath.substringAfterLast(if (filePath.contains("/")) "/" else "\\"),
                    size = app.relay.core.fs.fileSize(filePath) ?: 0L,
                )
                sendFiles(convId, listOf(f))
            }
        }
    }

    override fun sendVoice(peerId: String, filePath: String) {
        val f = app.relay.core.transport.LocalFile(
            path = filePath,
            name = filePath.substringAfterLast(if (filePath.contains("/")) "/" else "\\"),
            size = app.relay.core.fs.fileSize(filePath) ?: 0L,
        )
        sendFiles(peerId, listOf(f))
    }

    // ------------------------------------------------------------------ 委托动作：屏幕协助 (Remote View)
    override fun requestScreenView(peerId: String) = screenCtl.handleRequestScreenView(peerId)
    override fun respondScreenView(accept: Boolean) = screenCtl.handleRespondScreenView(accept)
    override fun setScreenViewMode(mode: app.relay.core.protocol.ScreenMode) = screenCtl.handleSetScreenViewMode(mode)
    override fun stopScreenView() = screenCtl.handleStopScreenView()

    // ------------------------------------------------------------------ 委托动作：智能体 (Agent)
    @Suppress("LongParameterList")
    suspend fun sendAgentSession(
        peerId: String,
        sessionId: String,
        alias: String? = null,
        agentKind: String? = null,
        state: String? = null,
        title: String? = null,
        startedAt: Long? = null,
        agentId: String? = null,
        wsId: String? = null,
        wsTitle: String? = null,
        wsPath: String? = null,
    ): SendOutcome? = agentCtl.handleSendAgentSession(
        peerId, sessionId, alias, agentKind, state, title, startedAt,
        agentId, wsId, wsTitle, wsPath,
    )

    suspend fun sendAgentEvent(
        peerId: String,
        sessionId: String,
        cursor: Long,
        eventType: String,
        summary: String? = null,
        body: String? = null,
        ref: String? = null,
    ): SendOutcome? = agentCtl.handleSendAgentEvent(peerId, sessionId, cursor, eventType, summary, body, ref)

    /**
     * 向宿主申请"让我看你的 agent"（PPE-1 增补 D7）。
     *
     * `agentKey` = `agentId ?? agentKind`，null = 申请**整台设备**（父稿 §3.3 的 scope=all）。
     * 返回 null 有两种含义且必须分得开：没有运行时（节点根本没起来）、对端没声明 `ag1`；
     * 调用方据此决定文案（见 `AgentGrantCard` 的失败分支）。
     */
    suspend fun sendAgentGrantRequest(
        peerId: String,
        agentKey: String? = null,
        reason: String? = null,
    ): SendOutcome? = agentCtl.handleSendGrantRequest(peerId, agentKey, reason)

    /**
     * 向宿主要一页该工作区的历史会话（PPE-1 增补 D8）。
     *
     * 「打开工作区时同步」的入口（用户裁决）：`offset` 传本地已知条数即可，
     * `limit` 交给宿主自定（它才知道一帧装得下几条）。返回 false = 没发出去
     * （对端不声明 `agp1`、或节点没起来）—— 调用方据此决定画不画「加载更多」。
     */
    suspend fun requestAgentSessionPage(
        peerId: String,
        wsId: String,
        offset: Long,
        limit: Long? = null,
    ): Boolean = agentCtl.handleRequestSessionPage(peerId, wsId, offset, limit)


    override fun respondApproval(approvalId: String, allow: Boolean) = agentCtl.handleRespondApproval(approvalId, allow)
    override fun cancelAgentTask(taskId: String) = agentCtl.handleCancelAgentTask(taskId)

    // ------------------------------------------------------------------ 生命周期
    fun start() {
        if (runtime != null) {
            if (_state.value.restarting) _state.update { it.copy(restarting = false) }
            return
        }
        if (_state.value.network.phase == NodePhase.Starting) return
        val restored = messagesCtl.handleRestoreMessages()
        agentCtl.handleRestoreAgentData()
        val settings = _state.value.settings
        for (group in settings.groups) core.groupNeeds.noteKnown(group.groupId, group.rev)
        _state.update { it.copy(network = it.network.copy(phase = NodePhase.Starting, failure = null)) }

        val isShareSupported = platform.screenSharing?.isShareSupported() == true
        val profile = settings.toProfile(
            host = platform.deviceInfo.hostName(),
            ver = version,
            remoteView = true,
            remoteShare = isShareSupported,
            // 承载 agent 节点的宿主（桌面端）在此广播 `ag1`；手机端恒 false。
            advertiseAgent = advertiseAgent,
        )
        val rt = NodeRuntime(
            profile = profile,
            udpPort = settings.udpPort,
            manualPeers = settings.manualPeers.map { ManualPeer(it.host, it.port) },
            tcpPort = settings.tcpPort,
            downloadDir = { pathJoin(dataDirectory, "downloads") },
            // 三个存储契约都用落盘实现：离线待发队列与会话历史跨重启，
            // 去重表本来就落盘（见 dedupStore）。内存版只留给测试与预览。
            queueStore = persist.queue,
            dedupStore = dedupStore,
            messageStore = persist.messages,
            timings = timings,
            onOpenScreen = { socket, open, remoteAddress, read, write ->
                screenCtl.handleOnIncomingScreenSocket(socket, open, remoteAddress, read, write)
            },
        )

        scope.launch {
            try {
                rt.start(scope)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        network = it.network.copy(
                            phase = NodePhase.Failed,
                            failure = bindFailureMessage(settings.udpPort, t),
                        ),
                        restarting = false,
                    )
                }
                return@launch
            }
            runtime = rt
            val snapshot = platform.networkMonitor.snapshot()
            _state.update {
                it.copy(
                    network = NetworkUi(
                        phase = NodePhase.Running,
                        transport = snapshot.transport,
                        localAddresses = snapshot.localAddresses,
                        vpnActive = snapshot.vpnActive,
                        boundUdpPort = rt.boundPort,
                        failure = null,
                    ),
                    restarting = false,
                )
            }
            observe(rt)
            restored.join()
            // 屏幕协助历史卡：先做启动卫生（把上次没走完的会话标成 interrupted），
            // 再开始订阅相位变化。顺序不能反 —— 反了的话，卫生那一步写进去的
            // "已结束"会盖掉观察者刚写进去的"进行中"。
            screenHistoryCtl.handleInterruptUnfinishedScreenRecords()
            jobs += screenHistoryCtl.handleObserveScreenRecords()
            messagesCtl.handleRestoreQueuedFrom(rt)
            handshake()
            startMdns(rt.boundPort)
            requestNotificationPermission()
            core.networkCancel = platform.networkMonitor.observe { onNetworkChanged(it) }
            val remoteConfig = settings.remoteRelay
            if (remoteConfig != null && remoteConfig.enabled) {
                settingsCtl.handleStartRemoteRelay(remoteConfig)
            }
        }
    }

    suspend fun stop(leaving: Boolean = true) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        settingsCtl.handleStopRemoteRelay()
        stopMdns()
        core.networkCancel?.invoke()
        core.networkCancel = null
        if (leaving) runCatching { runtime?.announceExit() }
        runtime?.stop()
        runtime = null
        _state.update {
            it.copy(
                network = it.network.copy(phase = NodePhase.Stopped, boundUdpPort = 0),
                restarting = if (leaving) false else it.restarting,
            )
        }
    }

    suspend fun restart() {
        stop(leaving = false)
        start()
    }

    override fun requestRestart() {
        _state.update { it.copy(restarting = true) }
        scope.launch { restart() }
    }

    fun checkForUpdates() {
        scope.launch {
            _state.update {
                it.copy(updateState = it.updateState.copy(status = UpdateCheckStatus.Checking, errorMessage = null))
            }
            try {
                val info = updateChecker.check(version)
                val status = if (info.hasUpdate) {
                    UpdateCheckStatus.UpdateAvailable
                } else {
                    UpdateCheckStatus.UpToDate
                }
                _state.update {
                    it.copy(
                        updateState = UpdateCheckState(
                            status = status,
                            info = info,
                            errorMessage = null,
                        )
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        updateState = UpdateCheckState(
                            status = UpdateCheckStatus.Error,
                            info = null,
                            errorMessage = t.message ?: "error",
                        )
                    )
                }
            }
        }
    }

    fun openUpdateUrl() {
        val url = _state.value.updateState.info?.downloadUrl
        if (!url.isNullOrBlank()) {
            platform.urlOpener?.openUrl(url)
        }
    }

    private fun onNetworkChanged(snapshot: NetworkSnapshot) {
        _state.update {
            it.copy(
                network = it.network.copy(
                    transport = snapshot.transport,
                    localAddresses = snapshot.localAddresses,
                    vpnActive = snapshot.vpnActive,
                ),
            )
        }
        val rt = runtime ?: return
        scope.launch { runCatching { rt.announce() } }
    }

    private fun observe(rt: NodeRuntime) {
        jobs += scope.launch {
            rt.peers.collect { event ->
                if (event is PeerEvent.Updated) {
                    refreshPeers()
                    cidrCtl.handleShareScanRangesToPeer(event.record.profile.nodeId)
                    val hash = event.record.profile.avatarHash
                    if (!hash.isNullOrBlank()) {
                        avatarCtl.handleFetchAvatarIfNeeded(event.record.profile.nodeId, hash)
                    }
                }
            }
        }
        jobs += scope.launch {
            rt.inbound.collect { envelope -> onInbound(envelope) }
        }
        jobs += scope.launch {
            rt.status.collect { status ->
                val current = stateLock.withLock {
                    core.threads[status.peerId]?.firstOrNull { it.id == status.msgId }
                } ?: return@collect
                val delivery = if (status.status == "sent") DeliveryState.Sent else DeliveryState.Queued
                handleReplaceMessage(
                    status.peerId,
                    current.copy(delivery = delivery),
                )
                messagesCtl.handleSyncPending(rt)
                publish()
            }
        }
        jobs += scope.launch {
            rt.transfers.collect { event -> onTransfer(event) }
        }
        jobs += scope.launch {
            while (true) {
                refreshPeers()
                messagesCtl.handleSyncPending(rt)
                delay(PEER_POLL_MS)
            }
        }
        jobs += scope.launch {
            while (true) {
                val pruned = withContext(ioDispatcher) {
                    runCatching { rt.prune() }.getOrDefault(emptyList())
                }
                messagesCtl.handleOnPruned(pruned)
                agentCtl.handlePruneAgentSessions()
                delay(PRUNE_INTERVAL_MS)
            }
        }
    }

    fun handshake() {
        val rt = runtime ?: return
        val peers = _state.value.settings.manualPeers
        scope.launch {
            if (peers.isEmpty()) return@launch
            for (peer in peers) {
                runCatching { rt.announceTo(peer.host, peer.port) }
            }
        }
    }

    // ------------------------------------------------------------------ mDNS 与节点发现
    private suspend fun startMdns(udpPort: Int) {
        val settings = _state.value.settings
        runCatching { platform.localNetworkGate.ensureAccess() }
        val label = mdnsServiceLabel(settings.nick, settings.nodeId)
        core.selfMdnsLabel = label
        val advertised = runCatching { platform.mdnsAdvertiser.start(label, udpPort) }.getOrDefault(false)
        _state.update { it.copy(network = it.network.copy(mdnsAdvertised = advertised)) }
        startMdnsBrowsing()
    }

    private fun startMdnsBrowsing() {
        val browser = platform.mdnsBrowser
        browser.startBrowsing(
            onServiceFound = { serviceName, host, port ->
                if (serviceName == core.selfMdnsLabel) return@startBrowsing
                core.mdnsServices[serviceName] = MdnsServiceUi(serviceName, host, port)
                _state.update { it.copy(mdnsServices = core.mdnsServices.values.toList()) }
            },
            onServiceLost = { serviceName ->
                core.mdnsServices.remove(serviceName)
                _state.update { it.copy(mdnsServices = core.mdnsServices.values.toList()) }
            },
        )
    }

    private fun stopMdns() {
        runCatching { platform.mdnsAdvertiser.stop() }
        platform.mdnsBrowser.stopBrowsing()
        core.mdnsServices.clear()
        core.selfMdnsLabel = null
        _state.update {
            it.copy(mdnsServices = emptyList(), network = it.network.copy(mdnsAdvertised = null))
        }
    }

    suspend fun scanQrCode(): Boolean {
        val access = runCatching { platform.cameraGate.ensureAccess() }
        val granted = access.getOrNull() ?: run {
            warn(
                access.exceptionOrNull()?.messageOrNull()?.let(BannerMessage::ScanFailed)
                    ?: BannerMessage.UnknownError,
            )
            return false
        }
        if (!granted) {
            warn(BannerMessage.CameraPermissionDenied)
            return false
        }
        val result = try {
            platform.qrScanner.scan()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            warn(
                when (failure) {
                    is CameraUnavailableException -> BannerMessage.CameraUnavailable
                    else -> failure.messageOrNull()?.let(BannerMessage::ScanFailed)
                        ?: BannerMessage.UnknownError
                },
            )
            return false
        } ?: return false
        return handleQrCode(result)
    }

    suspend fun importQrImage(): Boolean {
        val picked = try {
            platform.filePicker.pick()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            warn(
                failure.messageOrNull()?.let(BannerMessage::PickFailed) ?: BannerMessage.UnknownError,
            )
            return false
        } ?: return false

        val content = try {
            platform.qrScanner.scanImage(picked.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            warn(
                failure.messageOrNull()?.let(BannerMessage::ScanFailed) ?: BannerMessage.UnknownError,
            )
            return false
        } ?: run {
            warn(BannerMessage.NoQrInImage)
            return false
        }

        return handleQrCode(content)
    }

    private suspend fun handleQrCode(raw: String): Boolean {
        when (val parsed = parseQr(raw)) {
            is ParsedQr.LanPeer -> {
                addManualPeer(parsed.host, parsed.port.toString())
                return true
            }
            is ParsedQr.RemoteRelay -> {
                if (!parsed.lan.isNullOrBlank()) {
                    val lanParsed = parseQrPayload(parsed.lan)
                    if (lanParsed != null) {
                        addManualPeer(lanParsed.first, lanParsed.second.toString())
                    }
                }
                setRemoteRelay(
                    url = parsed.url,
                    token = parsed.token,
                    nodeId = parsed.nodeId,
                    nick = parsed.nick,
                )
                return true
            }
            null -> {
                warn(BannerMessage.InvalidQrCode)
                return false
            }
        }
    }

    val qrScannerPreview: (@Composable (Modifier) -> Unit)?
        get() = platform.qrScannerPreview

    override val receivedFileActions: ReceivedFileActions? = platform.receivedFileActions

    internal fun requestNotificationPermission() {
        scope.launch {
            val granted = platform.pushNotificationHandler.requestPermission()
            if (!granted) warn(BannerMessage.NotificationPermissionDenied)
        }
    }

    internal fun showNotification(title: String, body: String, focusPeerId: String? = null) {
        platform.pushNotificationHandler.showNotification(title, body, focusPeerId)
    }

    override fun addManualPeer(rawHost: String, rawPort: String) =
        addNamedManualPeer(rawHost, rawPort, null)

    /**
     * 带名字的手动节点 —— `m-addnode-fill` 的「名字（可选）」框走这条。
     *
     * 名字**只存在本机**（落在 [ManualPeerEntry.name]），不进 wire：对端握手后显示的是它
     * 自己的昵称，而本机这一行显示的是"我给它起的名字"，用来区分同一台机器的多个地址
     * （`192.168.1.77:17878` 这种串没人记得住是哪台）。
     *
     * [rawName] 为空/全空白 = 不起名，行为与 [addManualPeer] 完全一致（行标题回落
     * `host:port`），旧存档也读得出来 —— 这个形参是后加的，语义必须是"可选"。
     */
    fun addNamedManualPeer(rawHost: String, rawPort: String, rawName: String?) {
        val split = splitHostPort(rawHost, rawPort)
        if (split == null) {
            warn(BannerMessage.InvalidHost)
            return
        }
        val host = split.first
        if (host.isEmpty()) {
            warn(BannerMessage.EmptyHost)
            return
        }
        if (!isPlausibleHost(host)) {
            warn(BannerMessage.InvalidHost)
            return
        }
        val port = split.second.ifEmpty { Protocol.DEFAULT_UDP_PORT.toString() }.toIntOrNull()
        if (port == null || port !in 1..65535) {
            warn(BannerMessage.InvalidPort)
            return
        }
        val settings = _state.value.settings
        if (settings.manualPeers.any { it.host == host && it.port == port }) {
            warn(BannerMessage.DuplicatePeer)
            return
        }
        val name = rawName?.trim()?.takeIf { it.isNotEmpty() }
        val next = settings.copy(manualPeers = settings.manualPeers + ManualPeerEntry(host, port, name))
        handlePersist(next)
        runtime?.let { rt -> scope.launch { runCatching { rt.announceTo(host, port) } } }
        _state.update { it.copy(banner = BannerUi(BannerMessage.Handshaking("$host:$port"), BannerTone.Info)) }
    }

    override fun removeManualPeer(entry: ManualPeerEntry) {
        val settings = _state.value.settings
        handlePersist(settings.copy(manualPeers = settings.manualPeers.filterNot { it == entry }))
    }

    override fun removeUnreachableManualPeers() {
        val settings = _state.value.settings
        val dead = manualPeerStatuses(settings.manualPeers, _state.value.peers)
            .filter { it.unreachable }
            .map { it.entry }
        if (dead.isEmpty()) {
            warn(BannerMessage.NoUnreachablePeers)
            return
        }
        handlePersist(settings.copy(manualPeers = settings.manualPeers.filterNot { it in dead }))
        _state.update { it.copy(banner = BannerUi(BannerMessage.UnreachablePeersRemoved(dead.size), BannerTone.Info)) }
    }

    fun selectPeer(nodeId: String) {
        stateLock.withLock { core.unread[nodeId] = 0 }
        publish()
    }

    override fun dismissBanner() = _state.update { it.copy(banner = null) }

    fun requestFocus(peerId: String) {
        _state.update { it.copy(focusRequest = peerId) }
    }

    fun consumeFocusRequest() {
        _state.update { it.copy(focusRequest = null) }
    }

    internal fun refreshPeers() {
        val rt = runtime
        val records: List<PeerRecord> = rt?.peerList() ?: emptyList()
        val peers = records.map { it.toUi() }.toMutableList()
        val remoteConfig = _state.value.settings.remoteRelay
        if (remoteConfig != null && remoteConfig.enabled && remoteConfig.nodeId != null) {
            val exists = peers.any { it.nodeId == remoteConfig.nodeId }
            if (!exists) {
                peers.add(
                    PeerUi(
                        nodeId = remoteConfig.nodeId,
                        nick = remoteConfig.nick ?: "Remote Tidings",
                        host = remoteConfig.url,
                        ip = remoteConfig.url,
                        online = true,
                        caps = listOf(MsgTypes.CAP_AGENT),
                    ),
                )
            }
        }
        // 本机备注在这一层贴上去，而不是让 `PeerRecord.toUi()` 去读设置：
        // 备注的权威源是 `RelaySettings.peerRemarks`，节点记录里根本没有这个字段
        // （它不进协议 —— 见 `PeerUi.remark` 的 KDoc）。贴在这一层还有一个好处：
        // 上面那条中继节点、以及以后任何新来源的 PeerUi 都自动带上备注。
        val remarks = _state.value.settings.peerRemarks
        if (remarks.isNotEmpty()) {
            for (i in peers.indices) {
                val r = remarks[peers[i].nodeId].orEmpty()
                if (r.isNotEmpty()) peers[i] = peers[i].copy(remark = r)
            }
        }
        // 快照必须在锁内取：轮询协程在这里遍历 core.threads，而 messagesCtl.handleAppendMessage/messagesCtl.handleRestoreMessages
        // 可能在别的 worker 上改它的结构（这就是 refreshPeers 崩溃的现场）。
        val snapshot = stateLock.withLock {
            Triple(
                core.threads.mapValues { (_, list) -> list.toList() },
                core.unread.toMap(),
                core.pending.toMap(),
            )
        }
        _state.update {
            it.copy(
                peers = peers,
                messages = snapshot.first,
                unreadByPeer = snapshot.second,
                pendingByPeer = snapshot.third,
            )
        }
    }

    /** 推一次状态。改表之后唯一的通知方式（见 [RelayStateWriter]）。 */
    internal fun publish() = writer.publish()

    internal fun warn(message: BannerMessage) = _state.update { it.copy(banner = BannerUi(message, BannerTone.Warn)) }
    internal fun info(message: BannerMessage) = _state.update { it.copy(banner = BannerUi(message, BannerTone.Info)) }

    internal fun localMessageId(): String {
        localSeq++
        return "local-$localSeq-${nowMillis()}"
    }

    internal fun completedJob(): Job = Job().apply { complete() }

    companion object {
        internal const val PEER_POLL_MS = 2_000L
        internal const val PRUNE_INTERVAL_MS = 10 * 60 * 1000L
        internal const val MAX_AGENT_EVENTS = 200

        internal val CHAT_INBOUND_TYPES: Set<String> = setOf(MsgTypes.MSG)

        internal fun textOf(envelope: Envelope): String? {
            val payload = envelope.payload as? JsonObject ?: return null
            val kind = (payload["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: MsgKinds.TEXT
            if (kind != MsgKinds.TEXT && kind != MsgKinds.GROUP_TEXT) return null
            return (payload["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        internal fun groupIdOf(envelope: Envelope): String? {
            if (!isGroupText(envelope)) return null
            val payload = envelope.payload as? JsonObject ?: return null
            return (payload["groupId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        internal fun replyToOf(envelope: Envelope): String? {
            if (!isGroupText(envelope)) return null
            val payload = envelope.payload as? JsonObject ?: return null
            return (payload["replyTo"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifBlank { null }
        }

        internal fun mentionsSelf(envelope: Envelope, selfNodeId: String): Boolean {
            if (!isGroupText(envelope)) return false
            val payload = envelope.payload as? JsonObject ?: return false
            val mentions = payload["mentions"] as? JsonArray ?: return false
            return mentions.any { item ->
                val id = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                id == selfNodeId || id == MENTION_ALL
            }
        }

        internal const val MENTION_ALL: String = "@all"

        internal fun isGroupText(envelope: Envelope): Boolean =
            envelope.type == MsgTypes.MSG && msgKindOf(envelope) == MsgKinds.GROUP_TEXT

        internal fun isPlausibleHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            if (host.any { it.isWhitespace() }) return false
            return host.any { it.isLetterOrDigit() }
        }

        internal fun splitHostPort(rawHost: String, rawPort: String): Pair<String, String>? {
            val host = rawHost.trim()
            val port = rawPort.trim()
            val colon = host.indexOf(':')
            if (colon < 0 || host.indexOf(':', colon + 1) >= 0) return host to port
            val suffix = host.substring(colon + 1)
            if (suffix.toIntOrNull()?.let { it in 1..65535 } != true) return null
            if (port.isNotEmpty() && port != suffix) return null
            return host.substring(0, colon) to suffix
        }

        internal fun bindFailureMessage(port: Int, error: Throwable): BannerMessage =
            BannerMessage.BindFailed(port, error.messageOrNull())
    }
}

private fun PeerRecord.toUi(): PeerUi = PeerUi(
    nodeId = profile.nodeId,
    nick = profile.nick,
    company = profile.company,
    dept = profile.dept,
    team = profile.team,
    avatar = profile.avatar,
    avatarHash = profile.avatarHash,
    host = profile.host,
    ip = ip,
    udpPort = udpPort,
    platform = profile.platform,
    tcpPort = profile.tcpPort,
    caps = profile.caps,
    online = online,
    lastSeenAt = lastSeen,
)

internal fun Throwable.messageOrNull(): String? = message?.takeIf { it.isNotBlank() }