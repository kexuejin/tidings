// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.AgentArtifactKey
import com.kapp.tenon.domain.model.AgentArtifactUi
import com.kapp.tenon.domain.model.AgentEventUi
import com.kapp.tenon.domain.model.AgentQueueTaskUi
import com.kapp.tenon.domain.model.AgentRunKey
import com.kapp.tenon.domain.model.AgentRunUi
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.CabinetUiState
import com.kapp.tenon.domain.model.GrantUi
import com.kapp.tenon.domain.model.ToolApprovalRuleUi
import com.kapp.tenon.domain.model.MdnsServiceUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.NetworkUi
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.domain.model.ScanProgressUi
import com.kapp.tenon.domain.model.ScreenSessionUiState
import com.kapp.tenon.domain.model.TransferRecordUi
import com.kapp.tenon.domain.model.WorkspaceKey
import com.kapp.tenon.domain.model.WorkspaceUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Phase 4：`RelayUiState` 的**订阅面切片**（设计与迁移计划见蓝图 §4.4）。
 *
 * ## 这里是什么，不是什么
 *
 * - 是**纯投影**：每个 slice 只是把 `RelayUiState` 的一组字段原样取出来，没有任何存储、
 *   没有写入方法、没有跨面派生值。唯一的可变状态仍是 `RelayCoreState` 里的表与
 *   [RelayController._state]；因此**结构上不存在第二个写入方**。
 * - 不是按 UI 用途切的「屏模型」：判断一片该不该发射只看**字段分区**。会话行要显示的
 *   昵称/在线态属于节点面，需要它的屏自己去组合节点切片（或按投影函数组合），
 *   不许把节点字段塞进 [ChatSlice] —— 那会让切片退化成「改什么都会变」的聚合。
 *
 * ## 为什么用 stateIn + distinctUntilChanged
 *
 * 切片本身不持有值：它从单一 `state` 派生，`StateFlow` 的等值合并保证「投影后相等的值
 * 不再发射」。`SharingStarted.WhileSubscribed(5_000)` 让上游在没有订阅者 5 秒后停下 ——
 * 界面离开、进程闲置时不为没人看的切片继续 map。
 *
 * **投影函数里不许 toList / toSet / copy 之类的容器重建**：容器重建会造出「内容相同但
 * 引用不同」的中间值，让等值判断变便宜、让重建时机变得不可预测。这里全部是直接字段读取。
 */
data class ChatSlice(
    val messages: Map<String, List<MessageUi>> = emptyMap(),
    val conversationSummaries: List<ConversationSummaryRow> = emptyList(),
    val unreadByPeer: Map<String, Int> = emptyMap(),
    val pendingByPeer: Map<String, Int> = emptyMap(),
)

data class AgentSlice(
    val agentSessions: Map<AgentSessionKey, AgentSessionUi> = emptyMap(),
    val agentEvents: Map<AgentSessionKey, List<AgentEventUi>> = emptyMap(),
    val agentApprovals: Map<String, AgentApprovalUi> = emptyMap(),
    val agentTasks: Map<String, AgentQueueTaskUi> = emptyMap(),
    val agentRuns: Map<AgentRunKey, AgentRunUi> = emptyMap(),
    val agentInvokes: Map<String, AgentInvokeLease> = emptyMap(),
    val agentArtifacts: Map<AgentArtifactKey, AgentArtifactUi> = emptyMap(),
    val agentWorkspaces: Map<WorkspaceKey, WorkspaceUi> = emptyMap(),
    val agentGrants: Map<String, GrantUi> = emptyMap(),
    /**
     * 宿主免批表的快照（cap `atr1`），按宿主 nodeId 分键。
     *
     * 只存**最近一次收到的快照**（每次全量覆盖，不增量合并）：宿主回了新快照，
     * 旧的那份就是过时的 —— 增量合并会复活宿主已经撤掉的行。
     */
    val toolApprovalRules: Map<String, List<ToolApprovalRuleUi>> = emptyMap(),
    val toolRulesReadFailed: Set<String> = emptySet(),
    val pendingGrantRequests: Set<String> = emptySet(),
    val sessionPageLoading: Set<String> = emptySet(),
    val sessionPageExhausted: Set<String> = emptySet(),
)

data class NodeSlice(
    val network: NetworkUi = NetworkUi(),
    /** 本机节点 id：身份恢复会换值，必须走切片保持响应（App.kt 的 QR/分享面消费它）。 */
    val selfNodeId: String = "",
    val peers: List<PeerUi> = emptyList(),
    val mdnsServices: List<MdnsServiceUi> = emptyList(),
    val scanProgress: ScanProgressUi? = null,
)

data class TransferSlice(
    val transferLogs: List<TransferRecordUi> = emptyList(),
    val cabinet: CabinetUiState = CabinetUiState(),
    val screenSession: ScreenSessionUiState = ScreenSessionUiState(),
)

data class SettingsSlice(
    val settings: RelaySettings = RelaySettings.firstRun(),
    val updateState: UpdateCheckState = UpdateCheckState(),
    val restarting: Boolean = false,
)

/**
 * 一次性的 UI 请求/横幅，与业务数据分开：它变化频繁但与任何业务表无关。
 *
 * `focusRequest` 仍保持「写入 → 消费后清空」的现有语义（不引入新事件通道）。
 */
data class ChromeSlice(
    val banner: BannerUi? = null,
    val settingsNote: BannerMessage? = null,
    val focusRequest: String? = null,
    val externalStoreTick: Long = 0L,
)

/**
 * 头像缓存与选图。
 *
 * 单独成片是因为它含 `ByteArray`：data class 的等值对 `ByteArray` 是**指针等值**。
 * 当前写路径只在内容变化时重建这些值（同内容不重建），所以默认等值可用；
 * 用用例钉住「同内容不重建 ⇒ 不发射」，将来若出现整表重建再引入内容 hash 包装。
 */
data class AvatarSlice(
    val avatarCache: Map<String, ByteArray> = emptyMap(),
    val pickedAvatarBytes: ByteArray? = null,
    val pickedAvatarHash: String? = null,
    val pickedAvatarName: String? = null,
)

// ---- 纯投影函数（无副作用、只读入参；单测见 RelayStateSlicesTest）----
//
// **public**：Phase 4 迁移期的屏适配需要它们 —— 未迁移/预览路径拿着 RelayUiState 调
// 「切片版 Composable」时，由这些函数把整状态投影成切片（见蓝图 §4.4.5 的默认参数约定）。

fun RelayUiState.toChatSlice(): ChatSlice = ChatSlice(
    messages = messages,
    conversationSummaries = conversationSummaries,
    unreadByPeer = unreadByPeer,
    pendingByPeer = pendingByPeer,
)

fun RelayUiState.toAgentSlice(): AgentSlice = AgentSlice(
    agentSessions = agentSessions,
    agentEvents = agentEvents,
    agentApprovals = agentApprovals,
    agentTasks = agentTasks,
    agentRuns = agentRuns,
    agentInvokes = agentInvokes,
    agentArtifacts = agentArtifacts,
    agentWorkspaces = agentWorkspaces,
    agentGrants = agentGrants,
    toolApprovalRules = toolApprovalRules,
    toolRulesReadFailed = toolRulesReadFailed,
    pendingGrantRequests = pendingGrantRequests,
    sessionPageLoading = sessionPageLoading,
    sessionPageExhausted = sessionPageExhausted,
)

fun RelayUiState.toNodeSlice(): NodeSlice = NodeSlice(
    network = network,
    selfNodeId = selfNodeId,
    peers = peers,
    mdnsServices = mdnsServices,
    scanProgress = scanProgress,
)

fun RelayUiState.toTransferSlice(): TransferSlice = TransferSlice(
    transferLogs = transferLogs,
    cabinet = cabinet,
    screenSession = screenSession,
)

fun RelayUiState.toSettingsSlice(): SettingsSlice = SettingsSlice(
    settings = settings,
    updateState = updateState,
    restarting = restarting,
)

fun RelayUiState.toChromeSlice(): ChromeSlice = ChromeSlice(
    banner = banner,
    settingsNote = settingsNote,
    focusRequest = focusRequest,
    externalStoreTick = externalStoreTick,
)

fun RelayUiState.toAvatarSlice(): AvatarSlice = AvatarSlice(
    avatarCache = avatarCache,
    pickedAvatarBytes = pickedAvatarBytes,
    pickedAvatarHash = pickedAvatarHash,
    pickedAvatarName = pickedAvatarName,
)

/**
 * 切片的持有者：每片一条派生 `StateFlow`，生命周期绑在控制器 scope 上。
 *
 * 由 [RelayController] **惰性**构造一次；界面通过控制器上的同名 `val` 访问，不要自己 map
 * （自己 map 会得到没有等值合并的冷流，且每次订阅都重算）。
 *
 * **每个 `stateIn` 都会在传入的 scope 上挂一个子协程**（等待订阅者，只在 scope 取消时结束）。
 * 所以不要用「业务调用方的临时 scope」构造它：`runBlocking { RelayController(scope = this, …) }`
 * 会在收尾时永远等这些子协程（实测全量 :app:jvmTest 挂死十分钟，栈落在 runBlocking 的
 * joinBlocking）。控制器因此惰性创建这个 hold；测试里订阅切片的 scope 用 `runTest` 的
 * `backgroundScope`。
 */
internal class RelayStateSlices(
    private val source: StateFlow<RelayUiState>,
    scope: CoroutineScope,
) {
    private val started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS)

    val chat: StateFlow<ChatSlice> =
        source.map { it.toChatSlice() }.distinctUntilChanged().stateIn(scope, started, source.value.toChatSlice())

    val agent: StateFlow<AgentSlice> =
        source.map { it.toAgentSlice() }.distinctUntilChanged().stateIn(scope, started, source.value.toAgentSlice())

    val node: StateFlow<NodeSlice> =
        source.map { it.toNodeSlice() }.distinctUntilChanged().stateIn(scope, started, source.value.toNodeSlice())

    val transfer: StateFlow<TransferSlice> =
        source.map { it.toTransferSlice() }.distinctUntilChanged().stateIn(scope, started, source.value.toTransferSlice())

    val settings: StateFlow<SettingsSlice> =
        source.map { it.toSettingsSlice() }.distinctUntilChanged().stateIn(scope, started, source.value.toSettingsSlice())

    val chrome: StateFlow<ChromeSlice> =
        source.map { it.toChromeSlice() }.distinctUntilChanged().stateIn(scope, started, source.value.toChromeSlice())

    val avatar: StateFlow<AvatarSlice> =
        source.map { it.toAvatarSlice() }.distinctUntilChanged().stateIn(scope, started, source.value.toAvatarSlice())

    /**
     * 单会话独立订阅流（Task 2.6 & §7.6 裁决）：
     * 仅当指定会话的消息发生增删或变化时发射，切断跨群更新导致的无谓重组。
     * 纯投影，与底层单一写入状态源完全共享。
     */
    fun messageFlowOf(convId: String): Flow<List<MessageUi>> =
        chat.map { it.messages[convId].orEmpty() }.distinctUntilChanged()

    private companion object {
        /** 订阅者全部离开后上游停多久。与 Compose 里常见的 5s 一致。 */
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}
