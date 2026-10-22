// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.relay.core.fs.deleteFile
import app.relay.core.fs.makeDirectories
import app.relay.core.fs.openRandomAccess
import app.relay.core.fs.pathJoin
import app.relay.core.fs.use
import app.relay.core.ioDispatcher
import app.relay.core.nowMillis
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.DEFAULT_TIMINGS
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgKinds
import app.relay.core.protocol.AGENT_SENDER_PREFIX
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.Timings
import app.relay.core.session.NodeRuntime
import app.relay.core.persist.PersistStores
import app.relay.core.transport.DedupStore
import app.relay.core.transport.LocalFile
import app.relay.core.transport.ManualPeer
import app.relay.core.transport.PartFile
import app.relay.core.crypto.TlsPin
import app.relay.core.transport.PeerEndpoint
import app.relay.core.transport.SYNTHETIC_NICK_PREFIX
import app.relay.core.transport.PeerEvent
import app.relay.core.transport.PeerRecord
import app.relay.core.transport.SendOutcome
import app.relay.core.transport.TransferEvent
import app.relay.core.transport.dedupLogPath
import app.relay.core.transport.linkDiagnosticsJson
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
import kotlinx.coroutines.isActive
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
import app.relay.feature.agents.ExecDeviceRow
import app.relay.app.model.DeliveryState
import app.relay.app.model.MdnsServiceUi
import app.relay.app.model.MessageUi
import app.relay.app.model.NetworkUi
import app.relay.app.model.NodePhase
import app.relay.app.model.PeerUi

const val APP_VERSION: String = "relay-0.1.0"

/**
 * 一条**外部来源**配对载荷的只读预览（task-54）。
 *
 * 字段就是确认框要如实说出来的事实：
 * - [nodeId] / [nick]：这条内容会让本机与**谁**配对（解析得出才有值）；
 * - [hasPin]：载荷里**真的**带了一枚 TLS pin 吗 —— 只有 true 时确认框才允许说
 *   「并固定它的密钥」。带不带是两件事，文案不许比事实更确定；
 * - [address]：这条内容指向的地址（`host:port` 或中继 url）—— 载荷里没有昵称/ID 时，
 *   确认框至少能说出「要与哪一条地址配对」，而不是一句含糊的"某个节点"；
 * - [kind]：`lan-peer` / `remote-relay`；null = 解析不出（确认框如实说解析不出）。
 */
data class ExternalPairingPreview(
    val nodeId: String?,
    val nick: String?,
    val address: String?,
    val hasPin: Boolean,
    val kind: String?,
)

/**
 * 只读解析：把一条外部配对载荷翻译成确认框要展示的事实。
 *
 * ## 硬纪律：只读、无副作用、**不得落盘**
 *
 * 它只调 [parseQr]（纯函数），**不建节点、不写候选池、不存 pin、不改设置**。
 * 唯一有副作用的那条路是 [RelayController.handleQrCodeAsync]（新增/改写手动节点、写候选池、
 * 落对端 pin），只有用户在确认框上点「允许」之后才允许走它。
 *
 * 抽成顶层纯函数（而不是只做控制器方法）是为了能被单测直接钉住 —— 外部来源的信任建立
 * 是安全边界，判据要有一处可测的实现。
 */
// public 而不是 internal：宿主 `androidApp` 的单测（另一个 Gradle 模块）要直接钉它 ——
// 外部来源的信任建立是安全边界，判据必须有一处**跨模块可测**的实现。
public fun parseExternalPairingPreview(
    raw: String,
    myAddresses: List<String>,
): ExternalPairingPreview? = when (val parsed = parseQr(raw, myAddresses = myAddresses)) {
    is ParsedQr.LanPeer -> ExternalPairingPreview(
        nodeId = parsed.nodeId,
        nick = parsed.nick,
        address = parsed.host + ":" + parsed.port,
        hasPin = !parsed.tlsPin.isNullOrBlank(),
        kind = "lan-peer",
    )

    is ParsedQr.RemoteRelay -> ExternalPairingPreview(
        nodeId = parsed.nodeId,
        nick = parsed.nick,
        address = parsed.url,
        // 中继载荷不带对端 pin：这里**必须**是 false，否则确认框会凭空承诺固定密钥。
        hasPin = false,
        kind = "remote-relay",
    )

    null -> null
}

/**
 * 一条「在跟 agent 说」的消息该由谁执行（见 `RelayController.agentPromptRoute`）。
 *
 * 顶层类型而不是 companion 里的嵌套类：它就是三个调用点与 UI 共同判定的结论，
 * 不该只能写成 `RelayController.xxx`；而且嵌套在 companion 里时外部引用不到。
 */
enum class AgentPromptRoute { None, Local, Remote }

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
            settings = (store.load() ?: RelaySettings.firstRun()).let { raw ->
                // 一次性迁移（真机反馈 #2）：老库没有 persistMessagesDecided 这一位，
                // 而旧文件里"从没设过"与"显式关掉"是同一串字节 —— 按"从没设过"补成开启。
                // 只认这一位，所以跑过一次就再也不会重来（详见 migrateRelaySettings 的 KDoc）。
                val loaded = migrateRelaySettings(raw)
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
                val next = if (platform.isDefaultNick(loaded.nick)) loaded.copy(nick = fallback) else loaded
                // 迁移与补默认名都必须落盘：不写的话下次启动读到还是同一份老文件，
                // "一次性"就变成每次启动都重来一遍。
                if (next != raw) store.save(next)
                next
            },
        ),
    )
    val state: StateFlow<RelayUiState> = _state.asStateFlow()

    val dataDirectory: String = platform.dataDirectory.dataDirectory()

    /**
     * 手机侧长连接诊断文件的路径（task-34）：`<dataDir>/diag/link.json`。
     *
     * 为什么需要它：**拨号发生在手机**，而 `/v1/peers` 是宿主侧的控制面 —— 手机为什么没拨、
     * 拨了为什么连不上，在宿主那边永远看不到（宿主只会显示 skipReason=peer-has-no-lnk1，
     * 因为按设计只有宿主声明 lnk1）。这个文件只读、只在本机应用私有目录、不含任何凭据，
     * 供 `adb shell run-as app.relay cat files/relay/diag/link.json` 读取。
     */
    val linkDiagnosticsPath: String get() = pathJoin(dataDirectory, "diag", "link.json")
    val cacheDirectory: String = platform.dataDirectory.cacheDirectory()

    fun activeRuntime(): NodeRuntime? = runtime

    internal var runtime: NodeRuntime? = null
    internal var jobs = mutableListOf<Job>()
    var onLocalSessionPageRequest: ((peerId: String, wsId: String, offset: Long, limit: Long?) -> Boolean)? = null
    var onAgentPrompt: (suspend (convId: String, senderPeerId: String, prompt: String, replyToId: String?) -> Unit)? = null
    /**
     * **执行名单的宿主侧桥**：读写「谁能用这个 agent 干活」。
     *
     * 为什么需要桥：台账（`AgentGrantStore`）在 desktopApp，控制器在 composeApp ——
     * 后者拿不到前者。手机壳不接这个桥（`null`），因为它连的是别人的机器，
     * 那里的台账是对方的主权，我配不了。
     *
     * 三个回调都是**同步**的：台账读写是内存操作 + 一次文件落盘，不需要协程，
     * 而界面要在点击的这一帧就看到结果（异步会让勾选框闪一下再回来）。
     */
    var onExecDevices: ((agentKey: String?) -> List<ExecDeviceRow>?)? = null
    var onSetExecDeviceAllowed: ((agentKey: String, nodeId: String, allowed: Boolean) -> Unit)? = null

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
    fun appendMessage(message: MessageUi, incoming: Boolean = false) = handleAppendMessage(message, incoming)
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

    /**
     * 中继是不是**真的活着**（[RemoteRelayClient.checkHealth] 的结果）。
     *
     * 为什么必须有它：原来 [refreshPeers] 只要配置了中继就无条件塞一条 online = true
     * 的 PeerUi，而唯一的判活函数 checkHealth() 全仓无人调用 —— 中继挂了界面上照样
     * 显示「在线」，用户以为消息能到。真机验证（2026-10-18）把这条记成「假在线」。
     * 写入方是中继轮询协程，读取方是 [refreshPeers]，所以与 [lastRemoteSeq] 同款裸 var。
     */
    internal var remoteRelayHealthy: Boolean = false

    // ------------------------------------------------------------------ 委托动作：设置
    fun updatePersistMessages(enabled: Boolean) = settingsCtl.handleUpdatePersistMessages(enabled)
    fun updateThemeMode(wire: String) = settingsCtl.handleUpdateThemeMode(wire)
    fun updateWaitFrameCapability(enabled: Boolean) = settingsCtl.handleUpdateWaitFrameCapability(enabled)
    fun updateRecordMessageCapability(enabled: Boolean) = settingsCtl.handleUpdateRecordMessageCapability(enabled)
    fun updateNotifyAgentMessages(enabled: Boolean) = settingsCtl.handleUpdateNotifyAgentMessages(enabled)
    fun updateNotifyMentionOnly(enabled: Boolean) = settingsCtl.handleUpdateNotifyMentionOnly(enabled)
    fun updateNotifyQuietHours(enabled: Boolean) = settingsCtl.handleUpdateNotifyQuietHours(enabled)
    fun updateKeepConnectionAlive(enabled: Boolean) = settingsCtl.handleUpdateKeepConnectionAlive(enabled)

    /** 置顶 / 取消置顶一个会话（纯本机偏好）。 */
    override fun togglePinnedConv(convId: String) = settingsCtl.handleTogglePinnedConv(convId)

    /** 静音 / 取消静音一个会话（消息照收，只是不再通知）。 */
    override fun toggleMutedConv(convId: String) = settingsCtl.handleToggleMutedConv(convId)

    /** 删掉一个会话（只删本机：消息、未读、置顶/静音标记）。不动协议、不通知对方。 */
    override fun deleteConversation(convId: String) = settingsCtl.handleDeleteConversation(convId)

    /**
     * 本平台有没有「后台保持连接」这条能力（设置页据此决定画不画那个开关）。
     *
     * 桌面端与 iOS 的进程模型不需要它 → false，设置页不画 —— 与"能力不存在就不画入口"
     * 同一条规矩（一个按下去没反应的开关比没有更糟）。
     */
    val backgroundConnectionSupported: Boolean
        get() = platform.backgroundConnection?.isSupported == true

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

    /**
     * 失败气泡上的「重发」：按**原消息 id** 重放（core 的 `replayOutgoing`），不是再发一条。
     * 换 id 重发在收端就是另一条消息 —— 真机现场同一句话在桌面库里两个 id。
     */
    override fun retryMessage(message: MessageUi) = messagesCtl.handleRetryMessage(message)
    override fun nudge(peerId: String) = messagesCtl.handleNudge(peerId)
    override fun clearMessages(convId: String) = messagesCtl.handleClearMessages(convId)

    /** 只删本机这一条（不动协议、不通知对方）；见 `handleDeleteMessage` 的 KDoc。 */
    override fun deleteMessage(convId: String, messageId: String) =
        messagesCtl.handleDeleteMessage(convId, messageId)
    fun clearAllMessages() = messagesCtl.handleClearAllMessages()
    override fun searchHistoricalMessages(query: String, limit: Long): List<MessageSearchResult> =
        messageStore.search(query, limit)
    override fun searchGlobal(query: String): GlobalSearchResult = messagesCtl.handleSearchGlobalInternal(query)

    /** 带筛选范围的全局检索（见 `GlobalSearchScope`）：不要的段直接不查。 */
    fun searchGlobal(query: String, scope: GlobalSearchScope): GlobalSearchResult =
        messagesCtl.handleSearchGlobalInternal(query, scope)
    override fun searchInConversation(
        convId: String,
        query: String,
        filter: HistorySearchFilter,
        limit: Long,
    ): List<MessageSearchResult> = messagesCtl.handleSearchInConversationInternal(convId, query, filter, limit)
    internal fun onInbound(envelope: Envelope) = messagesCtl.handleOnInbound(envelope)

    // ------------------------------------------------------------------ 委托动作：群组
    override fun createGroup(name: String, memberIds: List<String>, description: String): String? = groupsCtl.handleCreateGroup(name, memberIds, description)
    override fun addGroupMembers(groupId: String, newMemberIds: List<String>) = groupsCtl.handleAddGroupMembers(groupId, newMemberIds)
    override fun removeGroupMember(groupId: String, memberId: String) = groupsCtl.handleRemoveGroupMember(groupId, memberId)
    override fun leaveGroup(groupId: String) = groupsCtl.handleLeaveGroup(groupId)
    override fun updateGroupMeta(groupId: String, description: String?, announce: String?) =
        groupsCtl.handleUpdateGroupMeta(groupId, description, announce)
    /** 执行名单：**转给宿主侧桥**；没接（手机壳）就返回 null，界面不画配置区。 */
    override fun execDevicesOf(agentKey: String?): List<ExecDeviceRow>? = onExecDevices?.invoke(agentKey)

    override fun setExecDeviceAllowed(agentKey: String, nodeId: String, allowed: Boolean) {
        onSetExecDeviceAllowed?.invoke(agentKey, nodeId, allowed)
    }

    override fun exposeWorkspace(groupId: String, wsId: String?, agentId: String?) =
        handleExposeWorkspace(groupId, wsId, agentId)
    /**
     * 打开某条会话时把它标为已读。
     *
     * ⚠ 它**不记录"当前选中的是谁"** —— 那件事是 [setVisibleConversation] 管的。
     * 所以这个名字指的是调用点在做的事（用户点了某条会话），不是这个方法做的全部事。
     * 别把它当"标为已读"的通用入口用：批量标已读是 [markAllRead]。
     */
    override fun selectConversation(convId: String) {
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
        if (convId != null && (core.unread[convId] ?: 0) > 0) {
            stateLock.withLock { core.unread[convId] = 0 }
            publish()
        }
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
        wsId: String? = null,
        access: String? = null,
    ): SendOutcome? = agentCtl.handleSendGrantRequest(peerId, agentKey, reason, wsId, access)

    /**
     * 向宿主要一页该工作区的历史会话（PPE-1 增补 D8）。
     *
     * 「打开工作区时同步」的入口（用户裁决）：`offset` 传本地已知条数即可，
     * `limit` 交给宿主自定（它才知道一帧装得下几条）。返回 false = 没发出去
     * （对端不声明 `agp1`、或节点没起来）—— 调用方据此决定画不画「加载更多」。
     */
    override fun requestAgentSessionPage(
        peerId: String,
        wsId: String,
        offset: Long,
        limit: Long?,
    ) {
        scope.launch {
            agentCtl.handleRequestSessionPage(peerId, wsId, offset, limit)
        }
    }

    suspend fun fetchAgentSessionPage(
        peerId: String,
        wsId: String,
        offset: Long,
        limit: Long? = null,
    ): Boolean = agentCtl.handleRequestSessionPage(peerId, wsId, offset, limit)


    override fun respondApproval(approvalId: String, allow: Boolean) = agentCtl.handleRespondApproval(approvalId, allow)
    override fun respondApproval(approvalId: String, allow: Boolean, allowSession: Boolean) =
        agentCtl.handleRespondApproval(approvalId, allow, allowSession)
    override fun cancelAgentTask(taskId: String) = agentCtl.handleCancelAgentTask(taskId)

    fun recordLocalWorkspace(
        wsId: String,
        wsTitle: String?,
        wsPath: String?,
        agentKey: String?,
        agentKind: String?,
        online: Boolean? = null,
        sessionCount: Long? = null,
        order: Long? = null,
        at: Long = app.relay.core.nowMillis(),
    ) = agentCtl.handleRecordLocalWorkspace(wsId, wsTitle, wsPath, agentKey, agentKind, online, sessionCount, order, at)

    fun recordLocalSession(
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
        updatedAt: Long? = null,
    ) = agentCtl.handleRecordLocalSession(
        sessionId, alias, agentKind, state, title, startedAt,
        agentId, wsId, wsTitle, wsPath, updatedAt,
    )

    fun recordLocalEvent(
        nodeId: String,
        sessionId: String,
        cursor: Long,
        eventType: String,
        summary: String? = null,
        body: String? = null,
        ref: String? = null,
        at: Long = app.relay.core.nowMillis(),
    ) = agentCtl.handleRecordLocalEvent(nodeId, sessionId, cursor, eventType, summary, body, ref, at)

    fun isWorkspaceGroup(groupId: String): Boolean = groupsCtl.isWorkspaceGroup(groupId)

    fun workspaceOfGroup(groupId: String): app.relay.app.model.WorkspaceUi? = groupsCtl.workspaceOfGroup(groupId)

    // ------------------------------------------------------------------ 生命周期
    fun start() {
        if (runtime != null) {
            if (_state.value.restarting) _state.update { it.copy(restarting = false) }
            return
        }
        if (_state.value.network.phase == NodePhase.Starting) return
        val restored = messagesCtl.handleRestoreMessages()
        agentCtl.handleRestoreAgentData()
        groupsCtl.handleSyncWorkspaceGroups()
        val settings = _state.value.settings
        for (group in settings.groups) core.groupNeeds.noteKnown(group.groupId, group.rev)
        _state.update { it.copy(network = it.network.copy(phase = NodePhase.Starting, failure = null)) }

        // 「后台保持连接」在每次节点启动时按设置**重新对齐**一次：
        // 这是"应用启动后重新把保活拉起来"的唯一入口（进程被杀过、服务跟着没了，
        // 下一次启动必须自己回来）。关掉时 setEnabled(false) 只是 stopService，
        // 不会把没在跑的服务拉起来（见 ConnectionKeepAliveGate.stop 的注释）。
        // 这里只在真的启动了节点的那条路上执行 —— start() 提前 return 的两支不碰它。
        scope.launch { platform.backgroundConnection?.setEnabled(settings.keepConnectionAlive) }

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
            // K2：自签身份的落盘目录 + 本机当前地址（进证书 SAN）。
            linkIdentityDir = { pathJoin(dataDirectory, "link-identity") },
            localAddresses = { _state.value.network.localAddresses },
            // 宣告地址与上面那份**不是同一个列表**（见 NodeRuntime 的 KDoc）：
            // 上面那份刻意过滤隧道网卡（广播/同网段判断用），这份含它们（单播告知用）。
            advertisedAddresses = { _state.value.network.advertisedAddresses },
            // 子网广播：受限广播在 TUN 环境下会被代理的 `/1` 路由接走，子网广播抢不走。
            // 平台没提供（Android/iOS 尚未接）时为空 —— 行为与改动前一字不差。
            subnetBroadcasts = { platform.subnetBroadcastAddresses() },
            // 三个存储契约都用落盘实现：离线待发队列与会话历史跨重启，
            // 去重表本来就落盘（见 dedupStore）。内存版只留给测试与预览。
            queueStore = persist.queue,
            dedupStore = dedupStore,
            messageStore = persist.messages,
            // 配对码里那一池候选地址（按 nodeId 存）：换网之后发送/心跳的兜底。
            peerEndpointStore = persist.peerEndpoints,
            // 配对码里那枚 tlsPin：K2 公网长连接的信任根（没它就不建公网连接）。
            peerPinStore = persist.peerPins,
            // 「已知对端」名录（task-80 ①）：重启后**身份**还在（以离线态），
            // 否则节点表里那台 Mac 只剩 node-a1b2c3d4 这种合成名（真机缺陷）。
            knownPeerStore = persist.knownPeers,
            timings = timings,
            onOpenScreen = { socket, open, remoteAddress, read, write ->
                screenCtl.handleOnIncomingScreenSocket(socket, open, remoteAddress, read, write)
            },
        )
        // K1：长连接**恒开**（不再是用户设置）。见 RelaySettings.linkDownlink 的 KDoc：
        // 关掉它不是"回滚到可用状态"，而是"不拨号" —— 移动网 / NAT 下就是永远连不上。
        // 长连接**不再是用户开关**（见 RelaySettings.linkDownlink 的 KDoc）：
        // 关掉它等于"只等对端连入"，在移动网 / NAT 下就是"永远连不上"。
        // 安全边界在 LinkClient.linkTargets（公网必须 TLS + pin），与这里无关。
        rt.linkEnabled = true

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
                        // 宣告地址与上面那份不是同一个列表（见 NetworkSnapshot 的 KDoc）：
                        // 上面刻意过滤隧道网卡（广播/同网段判断用），这份含它们（单播告知用）。
                        advertisedAddresses = snapshot.advertisedAddresses,
                        vpnActive = snapshot.vpnActive,
                        boundUdpPort = rt.boundPort,
                        // 印码要用（task-86）：主机必须把本机 pin 交给手机，否则公网目标建不出来。
                        selfTlsPin = rt.localTlsPin(),
                        // 诊断页要看的读数（与 pin 同一档：本机身份的只读事实）。
                        selfCertNotAfterMillis = rt.localCertNotAfterMillis(),
                        failure = null,
                    ),
                    restarting = false,
                )
            }
            observe(rt)
            // 手机侧诊断出口（task-34）：每 5 秒把同一批 link* 字段落成 JSON 文件。
            // 只读、本机、无凭据；不监听端口、不进协议（约束：不要开放到局域网）。
            jobs += scope.launch {
                while (isActive) {
                    writeLinkDiagnostics()
                    delay(5_000)
                }
            }
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

    /**
     * 把长连接诊断写成本机 JSON（task-34）。任何异常都吞掉 —— 诊断绝不能影响连接本身。
     */
    private fun writeLinkDiagnostics() {
        val rt = runtime ?: return
        runCatching {
            val text = linkDiagnosticsJson(
                selfNodeId = rt.nodeId,
                linkEnabled = rt.linkEnabled,
                snapshot = rt.linkSnapshot(),
                peers = rt.peerList(),
                now = nowMillis(),
            )
            val directory = pathJoin(dataDirectory, "diag")
            makeDirectories(directory)
            // 整份重写：先删再建（append 句柄 + unlink 的坑见 FileStores.rewriteLog 的 KDoc）。
            deleteFile(linkDiagnosticsPath)
            openRandomAccess(linkDiagnosticsPath, forWrite = true).use { file ->
                val bytes = text.encodeToByteArray()
                file.append(bytes, bytes.size)
            }
        }
    }

    private fun onNetworkChanged(snapshot: NetworkSnapshot) {
        _state.update {
            it.copy(
                network = it.network.copy(
                    transport = snapshot.transport,
                    localAddresses = snapshot.localAddresses,
                    advertisedAddresses = snapshot.advertisedAddresses,
                    vpnActive = snapshot.vpnActive,
                ),
            )
        }
        val rt = runtime ?: return
        // K3 ①：网络一变就**立刻**重拨长连接（清掉 1s→60s 的退避）+ 排空一次离线队列；
        // UDP 那条重播照旧。两件事都必须做：前者铺路、后者把积压的消息真的送出去。
        rt.requestLinkReconnectNow()
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
        // 先对一遍账：节点表里已经有真名的，就把便条上的合成名换掉。
        // 这一趟必须**不依赖用户再扫一次码** —— 改名原来只发生在「重新配对且地址变了」
        // 那条路上，于是配对当天写下的 `node-a1b2c3d4` 会一直留着（真机红米就是）。
        val renamed = manualPeersWithRealNames(peers, rt.peerList())
        if (renamed != peers) handlePersist(_state.value.settings.copy(manualPeers = renamed))
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

    fun handleQrCodeAsync(raw: String) {
        scope.launch {
            handleQrCode(raw)
        }
    }

    /**
     * 确认框用的只读预览（task-54）：见顶层 [parseExternalPairingPreview] 的纪律。
     *
     * 这里只多给一样东西 —— 本机当前地址表（多候选码要按它决定先打哪个候选）。
     * 除此之外与那个纯函数逐字同源，**没有任何副作用**。
     */
    fun previewExternalPairing(raw: String): ExternalPairingPreview? =
        parseExternalPairingPreview(raw, _state.value.network.localAddresses)

    internal suspend fun handleQrCode(raw: String): Boolean {
        // 本机地址表只用来决定"多候选码里先打哪个"（同网段优先 lan、跨网选 public_v6）；
        // 单地址码与手填那条路不看它。
        when (val parsed = parseQr(raw, myAddresses = _state.value.network.localAddresses)) {
            is ParsedQr.LanPeer -> {
                val peerName = parsed.nick ?: parsed.nodeId?.let { SYNTHETIC_NICK_PREFIX + it.take(8) }
                // 同一个 nodeId 重新配对：**更新它那一条的地址**，而不是新增一行 / 只报
                // 「已经在列表里」。nodeId 才是身份 —— peerId、群成员、会话与消息都挂在它
                // 上面；manualPeers 只是「这个身份上次是从哪个地址找到的」的通讯录，
                // 地址变了（比如从局域网 IPv6 换成公网 IPv6）就该原地改。
                val known = parsed.nodeId?.let { id ->
                    runtime?.peerList()?.firstOrNull { it.profile.nodeId == id }
                }
                val rewritten = known?.let { record ->
                    manualPeersWithUpdatedAddress(
                        entries = _state.value.settings.manualPeers,
                        oldHost = record.ip,
                        oldPort = record.udpPort,
                        newHost = parsed.host,
                        newPort = parsed.port,
                        // 顺手把合成名换成对端声明的真名（见该函数的 KDoc）。
                        newName = record.profile.nick,
                    )
                }
                if (rewritten == null) {
                    addNamedManualPeer(parsed.host, parsed.port.toString(), peerName)
                } else if (rewritten != _state.value.settings.manualPeers) {
                    handlePersist(_state.value.settings.copy(manualPeers = rewritten))
                }
                // 候选池 + TLS pin 存进 core（都按 nodeId）：换网之后发送/心跳按序兜底，
                // 公网长连接则靠这枚 pin 做证书固定（K2）。
                parsed.nodeId?.let { nodeId ->
                    runtime?.setPeerEndpoints(nodeId, parsed.candidates.map { PeerEndpoint(it.host, it.port) })
                    // 只存**能解析**的 pin：乱格式直接丢弃（少一枚安全声明，不让配对失败）。
                    val parsedPin = TlsPin.parse(parsed.tlsPin)?.wire
                    parsedPin?.let { pin -> runtime?.setPeerPin(nodeId, pin) }
                    // task-86（真机）：给这个 nodeId 一条**可拨的最小档案**。
                    // 二维码是主机→手机单向的 ⇒ 手机只知道地址与 pin；而长连接的拨号目标
                    // 要求「有 profile 且声明 lnk1」⇒ 不补这一条，手机 linkTargets 恒为空、
                    // 一个目标都拨不出去（真机 `linkEnabled=true` 但 `linkTotal=0`），
                    // 而主机因 peer-has-no-lnk1 也不拨 ⇒ 双方都不拨、永远「没有回应」。
                    runtime?.rememberPairedPeer(
                        nodeId = nodeId,
                        nick = parsed.nick,
                        host = parsed.host,
                        udpPort = parsed.port,
                        tlsPin = parsedPin,
                    )
                }
                for (candidate in parsed.candidates) {
                    if (candidate.host != parsed.host || candidate.port != parsed.port) {
                        runtime?.let { rt -> scope.launch { runCatching { rt.announceTo(candidate.host, candidate.port) } } }
                    }
                }
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

    /**
     * K1 长连接开关（设置页「数据与存储」）。
     *
     * 关掉 = 立即断开现有连接、回到今天的 UDP+TCP+队列 —— 这就是回滚开关，不需要重启。
     */
    /**
     * **已废弃**：长连接不再是用户设置（见 [app.relay.app.RelaySettings.linkDownlink] 的 KDoc）。
     *
     * 保留空实现只为二进制兼容（旧界面/测试若还调它，也不该让应用崩）。新代码不要调它 ——
     * `NodeRuntime.linkEnabled` 恒为 true。
     */
    @Deprecated("Link is always enabled; this toggle is not user-facing")
    fun updateLinkDownlink(enabled: Boolean) {
        runtime?.linkEnabled = true
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

    override fun refreshPeers() {
        val rt = runtime
        val records: List<PeerRecord> = rt?.peerList() ?: emptyList()
        val peers = records.map { it.toUi() }.toMutableList()
        // 中继节点：**在线与否取健康检查的结果**，不是"配置了就在线"（见 remoteRelayHealthy）。
        // 判据抽成纯函数 remoteRelayPeer 是为了能被单测钉住。
        remoteRelayPeer(_state.value.settings.remoteRelay, remoteRelayHealthy)?.let { relayPeer ->
            if (peers.none { it.nodeId == relayPeer.nodeId }) peers.add(relayPeer)
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

    fun localMessageId(): String {
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

        /**
         * 群消息**真正的说话者**（可选键 `senderId`）；没有就返回 null，调用方退回 [Envelope.from]。
         *
         * 为什么需要：`from` 永远是**发送这台设备**，而设备可能在代别人说话 —— DSH 的 agent
         * 事件由桌面端转发到群里。不读这个键，手机端就会把 agent 说的话显示成「桌面端发的」，
         * 而桌面端自己按 `agent:<kind>` 落库 ⇒ 同一个群两端发送者不一致（真机缺陷）。
         *
         * 宽松解析：非字符串/空串一律当没带（旧端与坏帧都不该让发送者变成空）。
         */
        internal fun groupSenderIdOf(envelope: Envelope): String? {
            if (!isGroupText(envelope)) return null
            val payload = envelope.payload as? JsonObject ?: return null
            return (payload["senderId"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.ifBlank { null }
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

        /**
         * 默认要认的 agent kind（兼容老调用点与历史测试）。
         *
         * 真正的口径是**这个会话里有哪些 agent**（解析器的 `GroupParticipantRows.agentKinds`），
         * 由调用方传进来；默认值只是让「只有一个 dsh agent」的旧场景保持原样。
         */
        val DEFAULT_AGENT_KINDS: Set<String> = setOf("dsh")

        /**
         * 点名正则：`@<kind>`，case-insensitive，词边界。
         *
         * kind 来自**解析器**（`SenderIdentity.mentionToken` 与它同源），所以「@ 候选里
         * 插的名字」与「触发判据认的名字」不可能分叉 —— 这里每多写一个别名，就等于多一种
         * 「@ 了却叫不醒」的形态。
         */
        private fun agentMentionRegex(agentKinds: Set<String>): Regex? {
            // 每个 kind 都要**同时**认两种写法：
            //
            // 1. 裸 kind（`@dsh`）—— 老形态、以及 agent 没有绑定 workspace 的情况；
            // 2. `kind-workspace`（`@dsh-teahouse`）—— `SenderIdentity.mentionToken` 现在
            //    产出的形态（它走 `AgentMemberName.token(agentKind, workspaceName)`）。
            //
            // 只认第 1 种会让「@ 插入的名字」与「正则认的名字」分叉 —— 那就是本仓反复
            // 出现过的「@ 了却叫不醒」。
            val kinds = agentKinds
                .mapNotNull { it.trim().lowercase().takeIf { k -> k.isNotEmpty() } }
                .distinct()
            if (kinds.isEmpty()) return null
            val alternatives = kinds.flatMap { k ->
                val escaped = Regex.escape(k)
                // `-` 必须紧跟：`@dsh-teahouse` 算，`@dshx` **不算**。
                // 用 `(?:-[a-z0-9-]+)?` 而不是 `[a-z0-9-]*` —— 后者会把 `@dshx`
                // 的 `x` 也吃进去，词边界就破了（既有测试钉着这条）。
                listOf(escaped + "(?:-[a-z0-9-]+)?", escaped)
            }.distinct()
            // 尾部不用 `\b`：`-` 是非单词字符，`\b` 在它后面不成立，会让
            // `@dsh-teahouse` 被 `@dsh` 那一支提前匹配。用否定前瞻锁定 token 边界。
            // 尾部否定前瞻要同时排掉 `_`：它虽是单词字符，但不在我们的 token 字符集里，
            // 漏掉它会让 `@dsh_extra` 被当成 `@dsh`（旧行为是**不**匹配，有测试钉着）。
            return Regex("(?i)@(?:" + alternatives.joinToString("|") + ")(?![a-z0-9_-])")
        }

        /** 这条文本有没有点名这个会话里的某个 agent（默认只认 `@dsh`）。 */
        fun isMentioningAgent(
            text: String,
            agentKinds: Set<String> = DEFAULT_AGENT_KINDS,
        ): Boolean {
            val regex = agentMentionRegex(agentKinds) ?: return false
            return text.contains(regex)
        }

        /** 兼容旧调用点的名字（只认 dsh）。 */
        fun isMentioningDsh(text: String): Boolean =
            isMentioningAgent(text, DEFAULT_AGENT_KINDS)

        /** 这条消息是不是 agent 说的（`senderId` 形如 `agent:<kind>`）。 */
        fun isAgentAuthored(message: MessageUi?): Boolean =
            message?.senderId?.startsWith(AGENT_SENDER_PREFIX) == true

        /**
         * 这条消息要不要触发**宿主侧**的 agent 执行？
         *
         * 判据是「**这条消息是不是在跟 agent 说话**」，不是「会话是什么类型」：
         *
         * - **1:1**（`groupId == null`）：**每条都触发**。点进这个会话本身就是在指名 agent，
         *   而且没有第三方旁观 —— 误触发的代价最低，再要求一个 `@dsh` 只是摩擦。
         * - **群**：`@dsh` 点名；**工作区群**里「回复 agent 那条」也算 —— 接话不必再 @ 一遍。
         * - **普通群**：只有 `@dsh`。
         *
         * ## 为什么不再「工作区群里每条都触发」
         *
         * 触发之后跑的是**宿主本地的执行能力**（默认驱动 `NativeRelayDriver`：`bash` /
         * 文件读写 / 代码搜索，受 ACL 约束）。工作区群恰恰是**人最多**的那个频道 ——
         * 让任何人的任意一句话都拉起它，等于把最宽的能力面挂在最公开的地方。
         * 真机实测：一条不带 @ 的群消息，1 秒后 `agent:native` 就回了一条。
         *
         * **判据只有这一份**：三个触发点（收方 / 本机发方 / 群发方）都调它，
         * 任何一处都不许自己重写一遍条件。
         */
        fun shouldTriggerAgent(
            text: String,
            groupId: String?,
            isWorkspaceGroup: Boolean,
            repliedToIsAgent: Boolean,
            agentKinds: Set<String> = DEFAULT_AGENT_KINDS,
        ): Boolean {
            if (groupId == null) return true
            if (isMentioningAgent(text, agentKinds)) return true
            return isWorkspaceGroup && repliedToIsAgent
        }

        /**
         * 这条在跟 agent 说的消息，**该由谁执行**。
         *
         * - [AgentPromptRoute.Local]：这个会话里的 agent 就在本机，调用本机的执行器；
         * - [AgentPromptRoute.Remote]：agent 在**别的宿主**上。群消息已经按协议送达那台
         *   设备，本机**不做任何执行、也不代替它回话**。注意这里**不**承诺「远端已经
         *   开始执行」：真正唤醒宿主上的 DSH 需要宿主侧的 prompt 注入桥，那不在本协议里
         *   （本轮已单列为产品级缺口），所以 UI 文案只能说「消息已送达」，不能说「已交给
         *   agent 执行」；
         * - [AgentPromptRoute.None]：没有该执行的对象（本机没执行器、会话里也没有 agent）。
         *
         * 为什么不引入「两边都跑」这一档：同一个群里两个同名 agent 同时回话，用户分不清
         * 哪条算数；而本机驱动是**降级执行能力**（bash / 文件读写），让它和真 agent 抢答
         * 恰恰就是「@dsh 却收到本地 mock」的那次事故。所以远端 agent 在场时本机让路。
         *
         * **判据本身仍只有一份**（[shouldTriggerAgent]）；这里多回答的是「谁执行」，
         * 不是「要不要叫醒」。
         */
        fun agentPromptRoute(
            text: String,
            groupId: String?,
            isWorkspaceGroup: Boolean,
            repliedToIsAgent: Boolean,
            localAgentPresent: Boolean,
            remoteAgentPresent: Boolean,
            agentKinds: Set<String> = DEFAULT_AGENT_KINDS,
        ): AgentPromptRoute {
            if (!shouldTriggerAgent(text, groupId, isWorkspaceGroup, repliedToIsAgent, agentKinds)) {
                return AgentPromptRoute.None
            }
            return when {
                // 远端 agent 在场 ⇒ 本机让路（哪怕本机也有 agent）。
                remoteAgentPresent -> AgentPromptRoute.Remote
                localAgentPresent -> AgentPromptRoute.Local
                else -> AgentPromptRoute.None
            }
        }

        /**
         * 去掉 @dsh mention，**其余文本原样保留**，并归一化空白。
         *
         * 归一化只收**空格与制表符**（不收换行）：删掉 mention 会在原位置留下双空格
         * （"Hey @dsh what…" 变成 "Hey  what…"），那是这个函数该收拾的，不该由调用方或测试替它擦。
         * 也**不**剥 mention 之外的前缀 —— "Hey" 可能是用户有意写的。
         */
        fun extractAgentPrompt(
            text: String,
            agentKinds: Set<String> = DEFAULT_AGENT_KINDS,
        ): String {
            val regex = agentMentionRegex(agentKinds)
                ?: return text.trim().ifBlank { text }
            return text.replace(regex, "")
                .replace(Regex("[ \t]+"), " ")
                .trim()
                .ifBlank { text }
        }

        /** 兼容旧调用点的名字（只剥 `@dsh`）。 */
        fun extractDshPrompt(text: String): String =
            extractAgentPrompt(text, DEFAULT_AGENT_KINDS)

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

            // 1. 方括号 IPv6: [240e:...]:17878 或 [240e:...]
            if (host.startsWith("[")) {
                val closeBracket = host.indexOf(']')
                if (closeBracket <= 1) return null
                val cleanHost = host.substring(1, closeBracket).trim()
                val rest = host.substring(closeBracket + 1).trim()
                if (rest.startsWith(":")) {
                    val suffix = rest.removePrefix(":").trim()
                    if (suffix.toIntOrNull()?.let { it in 1..65535 } != true) return null
                    if (port.isNotEmpty() && port != suffix) return null
                    return cleanHost to suffix
                } else if (rest.isEmpty()) {
                    return cleanHost to port
                } else {
                    return null
                }
            }

            // 2. IPv4 或 主机名: host:port 或 裸 host
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

/**
 * 中继节点该长什么样 —— 抽成纯函数，好让「在线与否」这条判据被单测钉住。
 *
 * online 取**健康检查的结果**，不是「配置了就在线」：中继是另一台机器上的服务，它挂了
 * 的时候这里必须如实说离线（真机反馈的「假在线」）。没配 / 没 nodeId ⇒ null（不注入）。
 */
internal fun remoteRelayPeer(config: RemoteRelayConfig?, healthy: Boolean): PeerUi? {
    if (config == null || !config.enabled) return null
    val nodeId = config.nodeId ?: return null
    return PeerUi(
        nodeId = nodeId,
        nick = config.nick ?: "Remote Tidings",
        host = config.url,
        ip = config.url,
        online = healthy,
        caps = listOf(MsgTypes.CAP_AGENT),
    )
}
/**
 * 重新配对同一个 nodeId 时，把它在通讯录里的**那一条地址**原地改掉。
 *
 * 为什么不新增一条：nodeId 才是身份，[ManualPeerEntry] 只是「这个身份上次是从哪个地址
 * 找到的」。地址变了就更新那一行，否则同一个设备会在节点列表里留下 `192.168.x.x:17878`、
 * `[2408:...]:17878` 两行 —— 而群成员表、会话、消息都只认 nodeId 一份，用户却以为有两台机器。
 *
 * @param oldHost/oldPort 这个身份**当前**被采纳的地址（来自 registry，不是通讯录自己猜的）。
 * @return 改过之后的新列表；没有与 (oldHost, oldPort) 对应的条目时返回 null（调用方走新增）。
 *   地址本来就一致时返回**原列表**（调用方据此跳过落盘）。
 */
internal fun manualPeersWithUpdatedAddress(
    entries: List<ManualPeerEntry>,
    oldHost: String,
    oldPort: Int,
    newHost: String,
    newPort: Int,
    /**
     * 对端**自己声明的**昵称（可选）。
     *
     * 为什么要在改地址时顺手换名字：便条上的名字是「我给这条地址起的」——
     * 早先配对时对端还没在二维码里带昵称，于是写进去的是合成名 `node-a1b2c3d4`。
     * 那个名字只在「还没握上手」时顶替 `host:port` 显示，可一旦对端换了地址、
     * 这条便条被改写，合成名就跟着一直留着 —— 真机红米的 `manualPeers[0].name`
     * 就是 `node-a1b2c3d4`，而它明明已经认识对方叫 `demo-mac-97`。
     *
     * 只换**空的或合成名**：用户自己起的便条（原型里的「老周的旧笔记本」）不许被覆盖。
     */
    newName: String? = null,
): List<ManualPeerEntry>? {
    val index = entries.indexOfFirst { it.host == oldHost && it.port == oldPort }
    if (index < 0) return null
    val current = entries[index]
    val renamed = if (newName != null && shouldReplacePeerLabel(current.name, newName)) {
        current.copy(name = newName)
    } else {
        current
    }
    if (oldHost == newHost && oldPort == newPort) {
        // 地址没变也要能改名：地址换了才改，合成名就永远留在没换过地址的那些便条上。
        return if (renamed === current) entries else entries.toMutableList().also { it[index] = renamed }
    }
    val updated = entries.toMutableList()
    updated[index] = renamed.copy(host = newHost, port = newPort)
    return updated
}

/**
 * 用**对端声明的昵称**顶掉便条上的合成名（`node-<8 位>`）。
 *
 * 与 [manualPeersWithUpdatedAddress] 的分工：那个管「地址变了，那一行原地改写」，
 * 这个管「地址没变，但我知道它叫什么了」。两个都要有 —— 只有前者时，用户不重新配对
 * 就永远看不到真名。
 */
internal fun manualPeersWithRealNames(
    entries: List<ManualPeerEntry>,
    records: List<app.relay.core.transport.PeerRecord>,
): List<ManualPeerEntry> {
    var out = entries
    for (r in records) {
        val next = manualPeersWithUpdatedAddress(
            entries = out,
            oldHost = r.ip,
            oldPort = r.udpPort,
            newHost = r.ip,
            newPort = r.udpPort,
            newName = r.profile.nick,
        ) ?: continue
        out = next
    }
    return out
}

/**
 * 便条上的名字该不该换成对端声明的昵称。
 *
 * 只有**没名字**或**合成名**（`node-<8 位>`）才换 —— 用户自己起的便条不动，
 * 那是他的东西，不是对端的身份。
 */
internal fun shouldReplacePeerLabel(stored: String?, real: String): Boolean {
    val target = real.trim()
    if (target.isEmpty()) return false
    if (target.startsWith(SYNTHETIC_NICK_PREFIX)) return false
    val current = stored?.trim().orEmpty()
    return current.isEmpty() || current.startsWith(SYNTHETIC_NICK_PREFIX)
}