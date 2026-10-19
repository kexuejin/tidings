// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import app.relay.app.MessageSearchResult
import app.relay.app.canRecall
import app.relay.app.sessionPageKeyOf
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.feature.agents.AgentSessionBar
import app.relay.feature.agents.AgentSessionPickerDialog
import app.relay.ui.components.AppBanner
import app.relay.ui.components.ComposerCapability
import app.relay.ui.components.PkPickerDialog
import app.relay.ui.components.VoiceRecordDialog
import app.relay.ui.components.ForwardTargetDialog
import app.relay.ui.components.GroupInfoDialog
import app.relay.ui.components.ImageViewerDialog
import app.relay.ui.components.MessageComposer
import app.relay.ui.components.ScreenHistoryCard
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.EmptyState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.MessageUi
import app.relay.app.model.PeerUi
import app.relay.app.model.TransferState
import app.relay.core.protocol.AgentGrantAccess
import app.relay.generated.resources.action_find_nodes
import app.relay.generated.resources.chat_attach
import app.relay.generated.resources.chat_empty_message
import app.relay.generated.resources.chat_empty_title
import app.relay.generated.resources.chat_first_message
import app.relay.generated.resources.chat_folder_limit_hint
import app.relay.generated.resources.chat_group_media_hint
import app.relay.generated.resources.chat_group_some_offline
import app.relay.generated.resources.chat_image
import app.relay.generated.resources.chat_no_selection_message
import app.relay.generated.resources.chat_no_selection_title
import app.relay.generated.resources.chat_nudge
import app.relay.generated.resources.chat_peer_offline
import app.relay.generated.resources.chat_pk
import app.relay.generated.resources.chat_voice_record
import app.relay.generated.resources.hist_search_title
import app.relay.generated.resources.ic_attachment
import app.relay.generated.resources.ic_image
import app.relay.generated.resources.ic_mic
import app.relay.generated.resources.ic_nudge
import app.relay.generated.resources.agent_host_sleeping_banner
import app.relay.generated.resources.ic_moon
import app.relay.generated.resources.ic_pk
import app.relay.generated.resources.ic_search
import app.relay.generated.resources.ic_down
import app.relay.generated.resources.chat_unread_jump
import app.relay.generated.resources.chat_unread_jump_latest
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.painterResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.relay.generated.resources.ic_terminal
import app.relay.generated.resources.ic_plus
import app.relay.generated.resources.chat_new_task_action
import app.relay.generated.resources.chat_task_divider_label
import app.relay.generated.resources.chat_task_divider_new


/**
 * 单聊消息流。
 *
 * 投递状态与点对点协议标准语义一致：`已送达` = 对方 ACK 过；`已排队` = 只是进了补发队列，
 * **对方还没收到**。把这两个混成一个"已发送"是这类应用最常见的谎。
 */
// `BackHandler`（`androidx.compose.ui.backhandler`）目前还是实验 API。
// 与 `App.kt` 里那两处同一写法：在**这个组合函数**上 OptIn，而不是散在每个调用点。
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(
    data: ChatScreenState,
    actions: ChatScreenActions = ChatScreenActions(onSend = { _, _, _ -> }),
    groupActions: ChatScreenGroupActions = ChatScreenGroupActions(),
    chrome: ChatScreenChrome = ChatScreenChrome(),
) {
    // ---- 四个 holder 展开成本地名 ------------------------------------------
    // 函数体一行未改：下面这些名字与旧签名的 35 个形参同名同义，差异只有可空性。
    // 旧签名里 onAcceptTransfer / onDeclineTransfer 是 `= {}` 的非空默认体，
    // 现在收窄成可空 —— 「点了没反应」与「不画这个入口」是两件事，后者才对
    // （见 [ChatScreenActions.onAttachFile] 的 KDoc）。收窄后三处调用点显式补 `?: {}`。
    val state = data.state
    val peerId = data.peerId
    val now = data.now
    val cacheDir = data.cacheDir
    val onSend = actions.onSend
    val onOpenAgentConsole = actions.onOpenAgentConsole
    val onDismissBanner = actions.onDismissBanner
    val onAttachFile = actions.onAttachFile
    val onAcceptTransfer = actions.onAcceptTransfer
    val onDeclineTransfer = actions.onDeclineTransfer
    val onCancelTransfer = actions.onCancelTransfer
    val onResumeTransfer = actions.onResumeTransfer
    val onRecallMessage = actions.onRecallMessage
    val onForwardMessage = actions.onForwardMessage
    val onNudge = actions.onNudge
    val onRequestScreenView = actions.onRequestScreenView
    val onSendPk = actions.onSendPk
    val onSendVoice = actions.onSendVoice
    val onRespondApproval = actions.onRespondApproval
    val onSearchInConversation = actions.onSearchInConversation
    val onClearMessages = actions.onClearMessages
    val onAddGroupMembers = groupActions.onAddGroupMembers
    val onRemoveGroupMember = groupActions.onRemoveGroupMember
    val onUpdateGroupMeta = groupActions.onUpdateGroupMeta
    val onLeaveGroup = groupActions.onLeaveGroup
    val receivedFileActions = chrome.receivedFileActions
    val onBack = chrome.onBack
    val onFindPeers = chrome.onFindPeers
    val modifier = chrome.modifier
    val showHeader = chrome.showHeader
    val bubbleFraction = chrome.bubbleFraction
    val initialDraft = chrome.initialDraft
    val initialEmojiOpen = chrome.initialEmojiOpen
    val initialHistorySearch = chrome.initialHistorySearch
    val initialScreenRequestConfirmOpen = chrome.initialScreenRequestConfirmOpen
    /** null = 用内置输入栏；非 null = 桌面壳自己提供的输入卡片。 */
    val composerSlot: (@Composable (String, (String) -> Unit) -> Unit)? = chrome.composerSlot
    val sp = LocalAppSpacing.current
    val realPeer = state.peer(peerId)
    // 群会话：会话键是 `group:<groupId>`，所以 `peer()` 必然查不到人 —— 它的"对端"
    // 是那份成员表。群存在但一条消息都还没有时 `chatPeer` 也返回 null（它靠 messages
    // 兜底），所以这里再兜一层 [RelayUiState.groupPeer]，否则一个刚建好的群点进去
    // 看到的是"选一个会话"。兜完 `peer` 非空，下面所有既有的渲染路径照旧。
    val group = state.groupOfConv(peerId)
    // **群优先**，不是 `chatPeer` 优先。`chatPeer` 对任何有历史的会话都会回一个
    // 只有 nodeId、昵称空着的占位，而群的会话键是 `group:<groupId>` —— 先问它的话，
    // 头部画出来的是「group:g-」这种用户看不懂的东西，头像退化成那个占位 id 的首字母，
    // 输入栏还会因为占位的 `online = false` 平白多一句「对方离线，消息会排队」，
    // 而群消息其实一条都不会排队（见 `RelayController.sendToGroup`）。
    val peer = state.groupPeer(group) ?: state.chatPeer(peerId)
    // 群成员里当前在线的人数（不含自己）。头部与输入栏提示都要用它，
    // 所以只算一次 —— 两处各算一遍迟早会有一处忘了跟成员表更新。
    val memberOnlineCount = group?.let { state.memberOnlineCount(it) } ?: 0
    // 对端记录还没出现、但本机确实存着历史（见 [RelayUiState.chatPeer]）：
    // 过去这里直接画"选一个会话"，用户看着像消息全丢了。群不走这一支：它的头部
    // 本来就不显示 IP/能力，那句"本机存下来的历史"对群是句废话。
    val restored = realPeer == null && peer != null && group == null
    if (peerId == null || peer == null) {
        EmptyState(
            title = stringResource(Res.string.chat_no_selection_title),
            message = stringResource(Res.string.chat_no_selection_message),
            // 没会话时下一步就是去配对，所以这条空状态要给出口而不是让用户自己找页签。
            actionLabel = onFindPeers?.let { stringResource(Res.string.action_find_nodes) },
            onAction = onFindPeers,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val messages = state.messagesWith(peerId)

    // 正在收发**文件夹**时，计数行右端那句要换掉（原型 `m-sendfolder`）：
    // 那一刻真正要提醒的上限不是"走 UDP 还是 TCP"，而是"一次能塞多少个文件"。
    // 判据只看这一条会话里有没有一个还没落地的文件夹 —— 不是"曾经发过文件夹"，
    // 那会让提示在传输结束后继续挂着。`Offered` 也算在内：它是"已发出、对方还没接"，
    // 同样在传输中（`TransferState` 只有 Offered/Active/Completed/Failed/Declined 五档）。
    val sendingFolder = messages.any { message ->
        message.file?.let {
            it.isFolder && (it.state == TransferState.Offered || it.state == TransferState.Active)
        } == true
    }
    // 按**会话**重建滚动状态：不加这层 key 时，从会话 A 切到 B 会继承 A 的偏移，
    // 初始锚定一旦没落到底，用户看到的就是"A 的位置"（真机反馈 #5 的另一种表现）。
    val listState = key(peerId) { rememberLazyListState() }
    val coroutineScope = rememberCoroutineScope()
    var draft by remember(peerId) { mutableStateOf(initialDraft) }
    var replyingToMessage by remember(peerId) { mutableStateOf<MessageUi?>(null) }
    var forwardingMessage by remember { mutableStateOf<MessageUi?>(null) }
    var showGroupInfo by remember(peerId) { mutableStateOf(false) }
    // 「清空本会话记录」的确认框开合。动作来自头部（⋮），确认框画在这一层 —— 桌面壳的
    // 头部不进 ChatScreen，它自己那份确认框在 DesktopChatPane 里。
    var showClearConfirm by remember(peerId) { mutableStateOf(false) }
    var showVoiceRecord by remember(peerId) { mutableStateOf(false) }
    var previewingImagePath by remember { mutableStateOf<String?>(null) }
    var showHistorySearch by remember(peerId) { mutableStateOf(initialHistorySearch) }
    var showPkPicker by remember { mutableStateOf(false) }
    // 会话切换浮层的开合跟着**工作区**走，不只跟 peerId：列表本身按 effectiveWsId 收窄
    val matchedWs = remember(group, state.agentWorkspaces) {
        if (group == null) null
        else state.agentWorkspaces.values.firstOrNull { ws ->
            val desc = group.description.orEmpty()
            val pathMatch = ws.wsPath?.isNotBlank() == true && (desc.contains(ws.wsPath) || desc == ws.wsPath)
            val idMatch = ws.wsId?.isNotBlank() == true && (desc.contains(ws.wsId) || desc == ws.wsId)
            val titleMatch = ws.wsTitle?.isNotBlank() == true && group.name.contains(ws.wsTitle)
            pathMatch || idMatch || titleMatch
        }
    }
    val effectiveWsId = data.wsId ?: matchedWs?.wsId
    var showSessionPicker by remember(peerId, effectiveWsId) { mutableStateOf(false) }

    // ## 浮层必须吃返回键（UX 审计 §5 的 P0）
    //
    // 这些浮层盖在聊天页**之上**，但此前没有一处接管返回键 —— 于是用户在任意一个浮层上
    // 按返回，事件直接穿到最外层，**整个聊天页被关掉**（在手机上等于退回列表、在桌面壳里
    // 等于丢掉当前选中）。用户的心智是"关掉眼前这个框"，不是"退出这个会话"。
    //
    // 顺序是**层叠深度倒序**：越晚打开、越靠上的先关。这里刻意排成一条 when，
    // 而不是每个浮层各挂一个 BackHandler —— 多个 enabled=true 的 BackHandler 同时存在时，
    // 谁先收到事件取决于组合顺序，那是一种"看运气"的行为。
    BackHandler(
        enabled = forwardingMessage != null || previewingImagePath != null ||
            showClearConfirm || showGroupInfo || showVoiceRecord || showPkPicker ||
            showHistorySearch || showSessionPicker,
    ) {
        when {
            forwardingMessage != null -> forwardingMessage = null
            previewingImagePath != null -> previewingImagePath = null
            showClearConfirm -> showClearConfirm = false
            showGroupInfo -> showGroupInfo = false
            showVoiceRecord -> showVoiceRecord = false
            showPkPicker -> showPkPicker = false
            showHistorySearch -> showHistorySearch = false
            showSessionPicker -> showSessionPicker = false
        }
    }
    // 空会话那条状态要把光标直接送进输入框（见下面 messages.isEmpty() 分支）。
    // 桌面壳自己提供的输入卡片有自己的焦点管理，所以只在用内置输入栏时才建这个请求器。
    val composerFocus = remember { FocusRequester() }
    // 会话切换条（`.swbar`）与切换对话框共用同一份列表，按当前对端 nodeId 收窄：
    // 会话列表按**工作区**收窄：`effectiveWsId` 为空 = 不按工作区收窄（直接在跟节点聊），
    // 非空则只要属于该工作区的会话。
    //
    // 为什么用 `filter` 而不是新增一个 `agentSessionsOf(peerId, wsId)`：这一屏是那个口径的
    // 唯一使用者，而筛选条件很短；把它做进 `RelayUiState` 会让「按节点」与「按工作区」
    // 两个口径并列存在、以后又有人选错。
    // 取数口径是 `visibleAgentSessionsOf`（已按 DSH 的可见性两键过滤掉子代理会话与空壳会话），
    // **不是** `agentSessionsOf` —— 后者是未过滤的原始表，分页游标要用它（见 RelayUiState 的 KDoc）。
    // 真机实测：111 行落库会话里 68 行是 DSH 自己一定不显示的（子代理 19 + 空壳 49），
    // 它们成批出现在这个浮层里，而用户的原话是「怎么都是 Route this bounded MEMORY…」。
    val isWorkspaceGroup = group != null && effectiveWsId != null
    val agentSessions = remember(state.agentSessions, peer.nodeId, effectiveWsId) {
        if (effectiveWsId != null) {
            state.agentSessions.values
                .filter { it.wsId == effectiveWsId && !it.isSubagent && !it.isArchived && it.title?.isNotBlank() == true }
                .distinctBy { it.sessionId }
        } else {
            state.visibleAgentSessionsOf(peer.nodeId)
        }
    }
    val archivedSessions = remember(state.agentSessions, peer.nodeId, effectiveWsId) {
        if (effectiveWsId != null) {
            state.agentSessions.values
                .filter { it.wsId == effectiveWsId && !it.isSubagent && it.isArchived }
                .distinctBy { it.sessionId }
        } else {
            state.agentSessionsOf(peer.nodeId).filter {
                it.isArchived && !it.isSubagent
            }
        }
    }
    // 当前会话：**用户选的那条优先**，只在没选或它不属于这一屏的集合时才退回第一条。
    //
    // 三处（头部副标题、切换条、选择器高亮）从此共用这一个值 —— 它们都在画「当前会话」，
    // 以前各自 `firstOrNull()` 一次，于是头部可以显示 A、高亮显示 B。
    val activeSession = remember(agentSessions, archivedSessions, data.activeSessionId) {
        (agentSessions + archivedSessions).firstOrNull { it.sessionId == data.activeSessionId }
            ?: agentSessions.firstOrNull()
            ?: archivedSessions.firstOrNull()
    }

    // agent 会话的「输入提示」数据（真机反馈 #3）。三样都是既有事实：
    // 会话状态、事件流的最近时刻、有没有在跑的 run / 待审批 —— 取不到就沉默。
    val activeSessionEvents = remember(state.agentEvents, activeSession) {
        state.agentEventsOf(activeSession)
    }
    val sessionHasPendingApproval = remember(state.agentApprovals, activeSession, peer.nodeId) {
        state.agentApprovals.values.any { approval ->
            approval.status == "pending" && when {
                activeSession != null ->
                    approval.peerId == activeSession.peerId && approval.sessionId == activeSession.sessionId
                else -> approval.peerId == peer.nodeId
            }
        }
    }
    val sessionActiveRun = remember(state.agentRuns, activeSession, peer.nodeId) {
        state.agentRuns.values.firstOrNull { run ->
            !run.isTerminal && when {
                activeSession != null ->
                    run.peerId == activeSession.peerId && run.sessionId == activeSession.sessionId
                else -> run.peerId == peer.nodeId
            }
        }
    }
    // 最近一次动过的时刻：会话活动时刻、事件流最近到达、run 的自报/收帧时刻取大。
    // 只回答「多久没动了」，不用于显示时间（与协议对 `at` 的既有纪律同一条）。
    val sessionLastActivityAt = remember(activeSession, activeSessionEvents, sessionActiveRun) {
        maxOf(
            activeSession?.sortActivityAt ?: 0L,
            activeSessionEvents.maxOfOrNull { it.at } ?: 0L,
            sessionActiveRun?.at ?: 0L,
            sessionActiveRun?.updatedAt ?: 0L,
        )
    }

    // 打开这个 agent 会话时，向宿主要该工作区第一页历史会话（PPE-1 增补 D8）。
    //
    // 为什么挂在「打开」而不是启动时全量推：用户裁决原话 ——「正常其实用户只有在需要
    // 打开这个工作区的时候，同步下会话就好吧」。历史可能有上百条，启动时全推既慢又
    // 没必要；打开时才要，代价恰好付在想看的东西上。
    val openHistory = actions.onOpenAgentHistory
    val requestWsId = effectiveWsId
    val historyTargetNodeId = remember(peer.nodeId, requestWsId, state.agentSessions, state.agentWorkspaces) {
        if (requestWsId != null) {
            state.agentWorkspaces.values.firstOrNull { it.wsId == requestWsId }?.peerId
                ?: state.agentSessions.values.firstOrNull { it.wsId == requestWsId }?.peerId
                ?: peer.nodeId
        } else {
            peer.nodeId
        }
    }
    LaunchedEffect(historyTargetNodeId, requestWsId, openHistory) {
        if (openHistory != null && requestWsId != null) {
            openHistory(historyTargetNodeId, requestWsId)
        }
    }
    // 新消息到达时**只在已经贴底时**才自动滚。原来的写法无条件滚，于是用户往上翻旧消息
    // 时，任何一条新消息（包括屏幕协助记下来的那条）都会把视图拽回底部 —— 读旧消息这件事
    // 就没法做了。判据提在 [isNearBottomAfterLayout]（纯函数、有单测）：它同时覆盖
    // "内容还没占满一屏"（本来就不能滚，视为贴底，短会话照样自动滚到底）、
    // "布局还没量过"（进入会话的头一两帧，此时也必须当成贴底，否则新到的消息永远不滚 ——
    // 真机反馈 #5 的根因就在旧判据那句 `?: 0` 兜底里）与"用户往上翻过了"（能往前滚 ⇒ 不打扰）。
    //
    // 用 derivedStateOf 而不是在 effect 里现读：effect 只在 messages.size 变化时重启，
    // 而"是否贴底"是随滚动持续变化的，必须在滚动时就更新，否则拿到的是上一次的快照。
    // 最后可见索引**不要用 `?: 0` 兜底**：那会把"还没量过"说成"可见的是第 0 条"。
    val lastVisibleIndex by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
    }
    val isNearBottom by remember(messages.size) {
        derivedStateOf {
            isNearBottomAfterLayout(
                itemCount = messages.size,
                totalItemCount = listState.layoutInfo.totalItemsCount,
                lastVisibleIndex = lastVisibleIndex,
                canScrollForward = listState.canScrollForward,
            )
        }
    }
    // 进入这一屏时屏外的未读。数字只来自壳在清未读**之前**采下的值
    // （ChatScreenState.unreadOnEntry），拿不到就是 0 —— 指示条会退化成不带数字的
    // 「回到最新」，绝不编一个条数。
    var unreadOnEntry by remember(peerId) { mutableStateOf(data.unreadOnEntry) }
    // 进入之后、用户不在底部时新到的条数。
    var unseenWhileAway by remember(peerId) { mutableStateOf(0) }
    var previousMessageCount by remember(peerId) { mutableStateOf(messages.size) }
    var hasInitialScrolled by remember(peerId) { mutableStateOf(false) }

    // 进入会话：**第一次拿到消息就滚到最新**。不能只在"首帧就非空"时滚 ——
    // 冷启动 MessageStore 是异步恢复的（RelayControllerMessagesComponent.handleRestoreMessages
    // 里 scope.launch + withContext(io) 之后才 publish），消息常常在第一帧之后才落进
    // core.threads；旧实现把闩锁设在第一个非空帧上，之后的增长只剩"贴底才滚"这一条路。
    LaunchedEffect(peerId, messages.isEmpty()) {
        if (messages.isEmpty()) {
            // 会话被清空（或本来就空）：把锚定状态复位，下一条消息到达时重新落到底。
            // 没有这一支的话，「清空本会话记录」之后 hasInitialScrolled 仍是 true，
            // 之后的新消息只会画出来、不滚 —— 用户会以为清空把会话弄坏了。
            hasInitialScrolled = false
            previousMessageCount = 0
            unseenWhileAway = 0
            return@LaunchedEffect
        }
        if (hasInitialScrolled) return@LaunchedEffect
        hasInitialScrolled = true
        previousMessageCount = messages.size
        listState.scrollToItem(messages.lastIndex)
    }

    LaunchedEffect(messages.size) {
        val newAdded = (messages.size - previousMessageCount).coerceAtLeast(0)
        previousMessageCount = messages.size
        if (newAdded <= 0) return@LaunchedEffect
        val lastMsgIsMine = messages.lastOrNull()?.mine == true
        unseenWhileAway = unseenAfterGrowth(unseenWhileAway, newAdded, isNearBottom, lastMsgIsMine)
        if (shouldFollowLatest(isNearBottom, lastMsgIsMine)) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(isNearBottom, hasInitialScrolled) {
        if (!hasInitialScrolled || !isNearBottom) return@LaunchedEffect
        // 锚定真的落到底 = 最新那条已经在眼前，进入时采下的 N 条未读**不再在屏幕外**，
        // 计数到此为止。放掉它而不是留着：否则用户之后往上翻旧消息时，胶囊会拿一个
        // 早就看过的旧数字来骗他。
        unseenWhileAway = 0
        unreadOnEntry = 0
    }

    Column(modifier.fillMaxSize()) {
        if (showHeader) {
            ChatHeader(
                peer = peer,
                state = state,
                // 工作区名从**宣告台账**取（`agentWorkspaces` 是 D3 帧的落点，带 wsTitle）。
                // 取不到就不传 —— 那时副标题只画会话名与在线态，不编一个名字（父稿 §0 的降级铁律）。
                activeSession = activeSession,
                workspaceName = effectiveWsId?.let { ws ->
                    state.agentWorkspaces.values
                        .firstOrNull { it.wsId == ws }
                        ?.wsTitle
                },
                onOpenAgentConsole = onOpenAgentConsole ?: {},
                onBack = onBack,
                restored = restored,
                // 戳一碰只给单聊，且只在壳真的能发的时候画（见 onNudge 的说明）。
                // 群必须挡掉：占位对端的 nodeId 是 `group:<groupId>`，戳过去对方根本不认。
                onNudge = onNudge?.takeIf { group == null && !peer.isAgent }
                    ?.let { nudge -> { nudge(peer.nodeId) } },
                onRequestScreenView = onRequestScreenView?.takeIf { group == null && !peer.isAgent && peer.online }
                    ?.let { req -> { req(peer.nodeId) } },
                initialConfirmOpen = initialScreenRequestConfirmOpen,
                group = group,
                memberOnlineCount = memberOnlineCount,
                onOpenGroupInfo = if (group != null) ({ showGroupInfo = true }) else null,
                onSearchHistory = null,
                // 「清空本会话记录」：只在壳真的接了这条动作时画（null = 不画），
                // 点了先开确认框，确认后才真的清（见 ClearConversationDialog）。
                onClearMessages = onClearMessages?.let { { showClearConfirm = true } },
                onOpenSessionPicker = if (group == null && peer.isAgent) ({ showSessionPicker = true }) else null,
                // 「切换会话」的资格与选择器列表必须同集合：这里传的就是下面
                // `agentSessions` 那份（已 visible 过滤 + 已按 wsId 收窄）。
                // 头部自己按 peerId 再数一次时，节点有 2 条可见会话分属两个工作区
                // 会让按钮画得出来、点开只有 1 行。
                visibleSessionCount = agentSessions.size,
            )
            HorizontalDivider()
        }

        // Agent 会话切换条（原型 `.swbar`）：仅在 1对1 Agent 私聊时显示；协同群聊中彻底移除
        if (group == null && peer.isAgent && (agentSessions.isNotEmpty() || archivedSessions.isNotEmpty())) {
            val sessionPendingCount = remember(state.agentApprovals, agentSessions) {
                activeSession?.sessionId?.let { sid ->
                    state.agentApprovals.values.count { it.sessionId == sid && it.status == "pending" }
                } ?: 0
            }
            AgentSessionBar(
                sessions = if (archivedSessions.isNotEmpty()) agentSessions + archivedSessions else agentSessions,
                // 与头部、选择器共用同一个「当前会话」（见 activeSession 的说明）。
                activeSession = activeSession,
                pendingCount = sessionPendingCount,
                onClick = { showSessionPicker = true },
            )
            HorizontalDivider()
        }

        state.banner?.let {
            AppBanner(it, onDismissBanner ?: {}, Modifier.padding(horizontal = sp.lg, vertical = sp.sm))
        }

        // Workspace / Agent 待审批 Banner：作为 IM 插件的直接替代体验，在会话中直接展示待批复工具调用
        val agentApprovals = remember(state.agentApprovals, peer.nodeId, isWorkspaceGroup, agentSessions, archivedSessions) {
            if (isWorkspaceGroup) {
                val sessionIds = (agentSessions + archivedSessions).map { it.sessionId }.toSet()
                state.agentApprovals.values.filter {
                    it.status == "pending" && (it.sessionId in sessionIds || it.peerId == peer.nodeId)
                }
            } else {
                state.agentApprovals.values.filter { it.peerId == peer.nodeId && it.status == "pending" }
            }
        }
        if ((peer.isAgent || isWorkspaceGroup) && agentApprovals.isNotEmpty() && onRespondApproval != null) {
            ChatApprovalBanner(
                approvals = agentApprovals,
                onRespond = onRespondApproval,
                isReadOnly = { appr ->
                    val session = state.agentSessions[AgentSessionKey(appr.peerId, appr.sessionId)]
                    val grant = state.grantFor(appr.peerId, session?.let { it.agentId ?: it.agentKind }, session?.wsId)
                    val canApprove = (appr.peerId == state.selfNodeId) ||
                        (grant?.isMyDevice == true) ||
                        (grant?.access == AgentGrantAccess.OWNER) ||
                        (grant?.access == AgentGrantAccess.ADMIN && grant.delegatedApproval)
                    !canApprove
                },
                modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.xs),
            )
        }

        // 宿主设备睡眠/离线感知 Banner（工作区协同规范 v3.1）
        val isHostSleeping = if (group != null) {
            val hostNodes = group.virtualAgents?.mapNotNull { it.hostNodeId } ?: emptyList()
            hostNodes.isNotEmpty() && hostNodes.all { hostId ->
                val p = state.peer(hostId)
                p != null && !p.online
            }
        } else if (peer.isAgent) {
            !peer.online
        } else false

        if (isHostSleeping) {
            androidx.compose.material3.Surface(
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.xs).fillMaxWidth(),
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = sp.md, vertical = sp.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    androidx.compose.material3.Icon(
                        painter = org.jetbrains.compose.resources.painterResource(Res.drawable.ic_moon),
                        contentDescription = null,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    androidx.compose.material3.Text(
                        text = org.jetbrains.compose.resources.stringResource(Res.string.agent_host_sleeping_banner),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 群内 Agent 工作状态广播（Goal 5 / GroupProgress）
        if (group != null) {
            val activeAgentRun = state.agentRuns.values.firstOrNull { run ->
                !run.isTerminal && group.virtualAgents?.any { it.hostNodeId == run.peerId } == true
            }
            if (activeAgentRun != null) {
                val agentName = group.virtualAgents?.firstOrNull { it.hostNodeId == activeAgentRun.peerId }?.name
                    ?: activeAgentRun.peerId.take(8)
                GroupProgressBar(
                    agentName = agentName,
                    activeRun = activeAgentRun,
                    onInterrupt = { onSend(peer.nodeId, "/cancel", null) },
                    modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.xs),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                // 空会话的下一步是打字：这个出口把光标直接送进下面的输入框，
                // 比"这里什么都没有"更清楚。桌面壳自带的输入卡片（composerSlot）
                // 有自己的焦点管理，那时候不给这个按钮，免得按下去什么都没发生。
                val focusComposer = composerSlot == null
                EmptyState(
                    title = stringResource(Res.string.chat_empty_title),
                    message = stringResource(Res.string.chat_empty_message),
                    actionLabel = if (focusComposer) stringResource(Res.string.chat_first_message) else null,
                    onAction = if (focusComposer) ({ composerFocus.requestFocus() }) else null,
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                )
            } else {
                val sessionTransitions = remember(messages) { sessionTransitionIds(messages) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 原型 `.msgs{...padding:12px 14px 6px;...gap:10px}`（桌面 `.win .msgs` 为
                    // `14px 16px 6px`/`gap:9px`，手机取 `:root` 那一条）。
                    contentPadding = PaddingValues(start = sp.lg14, top = sp.md, end = sp.lg14, bottom = sp.sm6),
                    verticalArrangement = Arrangement.spacedBy(sp.md10),
                ) {
                    items(messages, key = { it.id }) { message ->
                        if (message.id in sessionTransitions) {
                            val sid = extractSessionId(message.id)
                            if (sid != null) {
                                val s = state.agentSessions.values.firstOrNull { it.sessionId == sid && it.title?.isNotBlank() == true }
                                    ?: state.agentSessions.values.firstOrNull { it.sessionId == sid }
                                SessionDivider(sessionTitle = s?.title)
                            }
                        }
                        val notice = message.notice
                        if (notice != null) {
                            // 系统提示行不是消息：撤回与戳一碰都只留一行居中的灰字（协议 §8）。
                            NoticeLine(notice)
                        } else if (message.screen != null) {
                            // 屏幕协助历史卡：它是**独立的一条消息**，不是气泡的附件
                            // （交互规范中将其渲染成 `.message-stack` 的第一个
                            // 孩子）。左右归属与气泡同一条规矩 —— `mine` 在生产者那边就是
                            // "本侧是 viewer"，所以这里直接用。
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                ScreenHistoryCard(record = message.screen, now = now)
                            }
                        } else {
                            // 「重发」接的是已有的 onSend：以原文本再发一条，不是服务端重试 ——
                            // core 没有按 messageId 重投的接口，而让用户自己照着抄一遍是白给的摩擦。
                            // 所以这里只在有真文本时给出口：文件消息重发不了（本地路径早就没了）。
                            val quotedMsg = message.replyTo?.let { replyId ->
                                messages.firstOrNull { it.id == replyId }
                            }
                            MessageBubble(
                                message = message,
                                bubbleFraction = bubbleFraction,
                                onAcceptTransfer = onAcceptTransfer ?: {},
                                onDeclineTransfer = onDeclineTransfer ?: {},
                                onCancelTransfer = onCancelTransfer,
                                onResumeTransfer = onResumeTransfer,
                                receivedFileActions = receivedFileActions,
                                onRetry = if (message.file == null && message.text.isNotBlank()) {
                                    { text: String -> onSend(peer.nodeId, text, null) }
                                } else {
                                    null
                                },
                                // 撤回入口的判据在 canRecall 里（自己的、5 分钟内、媒体要有 msgId）。
                                onRecall = onRecallMessage
                                    ?.takeIf { canRecall(message, now) }
                                    ?.let { recall -> { recall(message) } },
                                onReply = {
                                    replyingToMessage = message
                                    composerFocus.requestFocus()
                                },
                                onForward = onForwardMessage?.let { { forwardingMessage = message } },
                                // 群里必须署名：会话键是 `group:<groupId>`，气泡左右只分得出
                                // "是不是我发的"，分不出"是哪个人发的"。私聊恒 null（会话键
                                // 就是发送者，再署一次名是同一件事的第二个说法）。
                                senderName = if (group != null && !message.mine) {
                                    state.senderName(message.senderId)
                                } else {
                                    null
                                },
                                // 原型 `.msg .mav` 只在**别人发的**消息上出现（演示数据里
                                // 自己发的几条一条 `ini` 都没有）。私聊那颗就是会话对端；
                                // 群里按发送者取 —— 取不到就退回署名用的那串（`nodeId` 前 8 位），
                                // 与 `senderName` 的兜底保持同一种叫法。
                                senderAvatar = if (message.mine) {
                                    null
                                } else {
                                    val senderPeer = if (group != null) state.peer(message.senderId) else peer
                                    ChatAvatar(
                                        name = senderPeer?.nick
                                            ?: state.senderName(message.senderId)
                                            ?: peer.nick,
                                        avatar = senderPeer?.avatar?.takeIf { it >= 0 },
                                        isAgent = senderPeer?.isAgent ?: false,
                                    )
                                },
                                quotedMessage = quotedMsg,
                                onScrollToQuoted = quotedMsg?.let { target ->
                                    {
                                        val idx = messages.indexOfFirst { it.id == target.id }
                                        if (idx >= 0) {
                                            coroutineScope.launch { listState.animateScrollToItem(idx) }
                                        }
                                    }
                                },
                                onImageClick = { previewingImagePath = it },
                                // agent 的回复本身就是 Markdown（标题/列表/代码块/表格/链接），
                                // 纯文本渲染会让用户看到 ** 与 - 的字面量。人发的消息不转，
                                // 否则用户打的星号会被当成强调。
                                renderMarkdown = shouldRenderMarkdown(
                                    mine = message.mine,
                                    senderIsAgent = if (group != null) {
                                        state.peer(message.senderId)?.isAgent == true
                                    } else {
                                        peer.isAgent
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            // **下方还有消息**时的浮起胶囊（真机反馈 #5）。判据就是「贴底」的反面：
            // 已经贴底 = 最新那条在眼前、下方没有东西，这时不画（画了会盖住最新那条气泡）。
            // - 进入时采下的未读（unreadOnEntry）> 0 = 初始锚定没落到底（旧 bug 或竞态），
            //   此时下面确实压着 N 条没看过 → 「N 条未读 · 回到最新」；
            // - 进入后、用户不在底部时新到的（unseenWhileAway）同理；
            // - 两个数字都没有、只是用户往上翻 → 只说「回到最新」，**不写条数**（不编）。
            // 点它或自己滚到底即清除。
            val unseenTotal = unreadOnEntry + unseenWhileAway
            if (messages.isNotEmpty() && !isNearBottom) {
                Surface(
                    onClick = {
                        unreadOnEntry = 0
                        unseenWhileAway = 0
                        coroutineScope.launch {
                            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = sp.md),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = sp.lg14, vertical = sp.sm6),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_down),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (unseenTotal > 0) {
                                stringResource(Res.string.chat_unread_jump, unseenTotal)
                            } else {
                                stringResource(Res.string.chat_unread_jump_latest)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // agent 会话的输入提示：放在输入区正上方。两条壳（手机内置输入栏 / 桌面 composerSlot）
        // 共用这一个调用点 —— 以后要挪位置只动这一行（见 ComposerStatusLine 的 KDoc）。
        ComposerStatusLine(
            agentInvolved = peer.isAgent || isWorkspaceGroup,
            sessionState = activeSession?.state,
            lastActivityAt = sessionLastActivityAt,
            hasPendingApproval = sessionHasPendingApproval,
            hasActiveRun = sessionActiveRun != null,
            now = data.now,
        )

        if (composerSlot != null) {
            // 草稿仍由 ChatScreen 持有（remember(peerId)），所以切换会话时桌面输入卡片也不会串内容。
            composerSlot(draft) { draft = it }
        } else {
            MessageComposer(
                draft = draft,
                onDraftChange = { draft = it },
                // 发文件夹时计数行右端换掉：见上面 `sendingFolder` 的注释。
                hintTrailing = if (sendingFolder) {
                    stringResource(Res.string.chat_folder_limit_hint)
                } else {
                    null
                },
                // 提示的说法在这里定：群与 1:1 的排队规则不同，只有本层知道是哪种。
                // 群里有人不在线是常态，说的不是"对方离线"而是"有人不在线"，
                // 而且群消息确实会为离线成员入队（逐成员 sendUserMessage）。
                //
                // 比的是**除自己以外**的人数：`memberOnlineCount` 的 KDoc 写明它不含自己，
                // 而 `group.members` 含自己（建群时自己恒在成员表里）。曾经两边都直接用
                // `group.members.size`，于是所有人都在线时也会说"有成员不在线" ——
                // 正好差自己那一个（2 人群、两人都在线，输入栏仍挂着这句）。
                offlineHint = when {
                    group == null && !peer.online -> stringResource(Res.string.chat_peer_offline)
                    group != null && memberOnlineCount < othersInGroup(group) ->
                        stringResource(Res.string.chat_group_some_offline)
                    else -> null
                },
                // 能力由「壳真的能不能做」决定，而不是由一个空 lambda 决定：
                // onAttachFile 为 null 的壳连这项能力都不会出现在输入栏里。
                //
                // 顺序照原型 `toolsRow(group, histTo)`（`prototype/relay.html:3094-3118`）
                // 的左段：表情（内置，排在第一位，见 MessageComposer）· PK · 窗口震动 ·
                // … · 文件。原型那 7 颗里的截图 / 图片 / 文件夹本端没有对应实现
                // （`platform.filePicker` 只有 `pick()` 与 `pickDirectory()`，没有
                // "挑图片"与"截屏"），所以这三颗**不画** —— 一个按下去没反应的图标
                // 比没有更糟，这条规矩和 onAttachFile 为 null 时整颗不画是同一条。
                capabilities = listOfNotNull(
                    onSendPk?.takeIf { !peer.isAgent }?.let {
                        ComposerCapability(
                            id = "pk",
                            label = Res.string.chat_pk,
                            icon = Res.drawable.ic_pk,
                            onPick = { showPkPicker = true },
                        )
                    },
                    // 窗口震动只给单聊，判据与头部那颗完全一致（群与 Agent 都不画）：
                    // 震动要落到一个具体窗口上，群里没有单一收件人。
                    onNudge?.takeIf { group == null && !peer.isAgent }?.let { nudge ->
                        ComposerCapability(
                            id = "nudge",
                            label = Res.string.chat_nudge,
                            icon = Res.drawable.ic_nudge,
                            onPick = { nudge(peer.nodeId) },
                        )
                    },
                    // 「图片」与「文件」走**同一条** `pick()` 链路：`AndroidFilePicker.pick()`
                    // 拉的是 `arrayOf("*/*")`（`AndroidFilePicker.kt:98`），图片本来就是文件，
                    // 所以这颗不需要新的平台 API、也不需要新的发送链路。两颗的差别只有图标与
                    // 标签 —— 这正是原型把「截图 / 图片 / 文件夹」画成三颗的样子里，本端**唯一
                    // 真能通**的那一颗。另外两颗为什么不画，见 `fidelity-deviations.yaml`。
                    // 位置照原型：图片在文件**左边**。
                    onAttachFile?.let { attach ->
                        ComposerCapability(
                            id = "image",
                            label = Res.string.chat_image,
                            icon = Res.drawable.ic_image,
                            onPick = { attach(peer.nodeId) },
                        )
                    },
                    onAttachFile?.let { attach ->
                        ComposerCapability(
                            id = "file",
                            label = Res.string.chat_attach,
                            icon = Res.drawable.ic_attachment,
                            onPick = { attach(peer.nodeId) },
                        )
                    },
                    onSendVoice?.takeIf { !peer.isAgent && cacheDir != null }?.let {
                        ComposerCapability(
                            id = "voice",
                            label = Res.string.chat_voice_record,
                            icon = Res.drawable.ic_mic,
                            onPick = { showVoiceRecord = true },
                        )
                    },
                    if (isWorkspaceGroup) {
                        ComposerCapability(
                            id = "new_task",
                            label = Res.string.chat_new_task_action,
                            icon = Res.drawable.ic_plus,
                            onPick = {
                                val prefix = "@dsh "
                                if (!draft.startsWith(prefix)) {
                                    draft = "$prefix$draft"
                                }
                                composerFocus.requestFocus()
                            },
                        )
                    } else null,
                ),
                // 右端那颗：会话内历史搜索。原型把它放在这一排的**右端**，并在注释里写明
                // 「源码手机端开在头部（`ChatHeader` 的 onSearchHistory），原型
                // 头部只留两颗动作 —— 393px 一行里塞不下更多，所以它落到了这一排的右端。
                // **同一个屏上只能有一颗**」。所以头部那颗已经摘掉（见上面的 ChatHeader 调用）。
                toolsTrailing = listOfNotNull(
                    onSearchInConversation?.let {
                        ComposerCapability(
                            id = "history",
                            label = Res.string.hist_search_title,
                            icon = Res.drawable.ic_search,
                            onPick = { showHistorySearch = true },
                        )
                    },
                ),
                // 群里那排比单聊少一项（没有窗口震动），腾出的位置给这句话。
                toolsHint = group?.let { stringResource(Res.string.chat_group_media_hint) },
                groupMembers = if (group != null) {
                    val peerMap = state.peers.associateBy { it.nodeId }
                    group.members.filter { it != state.selfNodeId }.map { id ->
                        peerMap[id] ?: PeerUi(nodeId = id, nick = id.take(8), online = false)
                    }
                } else {
                    emptyList()
                },
                replyingTo = replyingToMessage,
                onCancelReply = { replyingToMessage = null },
                focusRequester = composerFocus,
                initialEmojiOpen = initialEmojiOpen,
                onSend = {
                    val body = draft
                    val replyId = replyingToMessage?.id
                    draft = ""
                    replyingToMessage = null
                    onSend(peer.nodeId, body, replyId)
                },
            )
        }
    }

    if (showGroupInfo && group != null) {
        GroupInfoDialog(
            group = group,
            selfNodeId = state.selfNodeId,
            peers = state.peers,
            memberOnlineCount = memberOnlineCount,
            onDismiss = { showGroupInfo = false },
            onAddMembers = onAddGroupMembers?.let { add -> { members -> add(group.groupId, members) } },
            onRemoveMember = onRemoveGroupMember?.let { remove -> { member -> remove(group.groupId, member) } },
            onUpdateMeta = onUpdateGroupMeta,
            onLeaveGroup = onLeaveGroup?.let { leave -> { leave(group.groupId) } },
        )
    }

    if (showVoiceRecord && cacheDir != null) {
        VoiceRecordDialog(
            cacheDir = cacheDir,
            onDismiss = { showVoiceRecord = false },
            onSendVoice = { path, durationMs ->
                showVoiceRecord = false
                onSendVoice?.invoke(peer.nodeId, path, durationMs)
            },
        )
    }

    val forwardMsg = forwardingMessage
    if (forwardMsg != null && onForwardMessage != null) {
        ForwardTargetDialog(
            sessions = state.conversations,
            onSelectTarget = { targetId ->
                forwardingMessage = null
                onForwardMessage(forwardMsg, targetId)
            },
            onDismiss = { forwardingMessage = null },
        )
    }

    if (showPkPicker && onSendPk != null) {
        PkPickerDialog(
            onDismiss = { showPkPicker = false },
            onPick = { game, value -> onSendPk(peer.nodeId, game, value) },
        )
    }

    previewingImagePath?.let { path ->
        ImageViewerDialog(
            imagePath = path,
            onDismiss = { previewingImagePath = null },
            receivedFileActions = receivedFileActions,
        )
    }

    if (showClearConfirm && onClearMessages != null) {
        // 会话名取 peer.displayName：群是群名、单聊是对端名、agent 是 agent 名 ——
        // 与头部/会话列表同一个叫法（写进确认正文，让用户核对清的是哪一个）。
        ClearConversationDialog(
            conversationName = peer.displayName,
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                showClearConfirm = false
                onClearMessages()
            },
        )
    }

    if (showHistorySearch && onSearchInConversation != null) {
        HistorySearchOverlay(
            onDismiss = { showHistorySearch = false },
            onSearch = { q, f -> onSearchInConversation(q, f) },
            onSelectHit = { hit ->
                showHistorySearch = false
                val idx = messages.indexOfFirst { it.id == hit.id }
                if (idx >= 0) {
                    coroutineScope.launch { listState.animateScrollToItem(idx) }
                }
            },
            // 命中行副行要写「老周 · 昨天 18:02」，而 `MessageSearchResult.senderId`
            // 是 **nodeId** —— 不传名字就印出 `n-zhou0000000003`。
            peerNames = state.peers.associate { it.nodeId to it.displayName },
            now = now,
        )
    }

    if (showSessionPicker && group == null && peer.isAgent && (agentSessions.isNotEmpty() || archivedSessions.isNotEmpty())) {
        // 「加载更多」的落点：要的是**当前会话所属工作区**的下一页。
        // 用第一条会话的 wsId 而不是"用户选中的"：选择器列出的是同一个工作区里的会话
        // （会话就是按工作区归拢的），所以这一组共用一个 wsId。
        // 优先用「用户点进来的那个工作区」（`data.wsId`）：它是这一屏过滤会话的依据本身，
        // 拿它当分页的归属不会自相矛盾。会话列表里的 wsId 只是兜底 —— 直接跟节点聊
        // （`data.wsId == null`）时，列表才可能横跨多个工作区。
        val pickerWsId = effectiveWsId ?: (agentSessions + archivedSessions).firstOrNull { it.wsId != null }?.wsId
        AgentSessionPickerDialog(
            sessions = agentSessions,
            // 高亮**用户选的那条**，与头部显示的那条一致（原先各自 firstOrNull）。
            activeSessionId = activeSession?.sessionId.orEmpty(),
            state = state,
            now = now,
            archivedSessions = archivedSessions,
            // 切换目标会话：由 actions.onSelectAgentSession 推进，会话归属由导航状态持有。
            onPick = { sessionId ->
                actions.onSelectAgentSession?.invoke(sessionId)
                showSessionPicker = false
            },
            onDismiss = { showSessionPicker = false },
            // 三个条件一起才画那一行：有加载回调（壳支持）、有 wsId（这组会话确实归属一个
            // 工作区）、而且宿主自报的会话数确实比本地多（真的还有下一页）。
            // 缺任何一个都**不画** —— 三种坏按钮：点了没反应、点了重复拉同一页、点了永远空。
            onLoadMore = actions.onLoadMoreAgentSessions?.takeIf {
                pickerWsId != null && hasMoreSessionsLocal(state, historyTargetNodeId, pickerWsId)
            }?.let { load ->
                { load(historyTargetNodeId, pickerWsId!!) }
            },
            loadingMore = pickerWsId != null &&
                sessionPageKeyOf(historyTargetNodeId, pickerWsId) in state.sessionPageLoading,
        )
    }
}

/**
 * 某个工作区还有没有下一页（PPE-1 增补 D8）。
 *
 * 判据：本地已知的会话数 < 宿主在工作区帧里自报的 sessionCount。用自报数当上界而不是
 * 「上一页是否装满」，是因为后者在最后一页恰好装满时会多要一次空页（用户点了
 * 「加载更多」却什么都没发生）。
 *
 * 出口一：上面那个自报数是**宿主的话**，它只在收到 agent-workspace 帧时更新（而且
 * 键缺失时还保留旧值）—— 宿主那侧删了会话、或帧丢过一轮，local < declared 就会一直
 * 成立，按钮永远点得动、点下去却一页空转。`sessionPageExhausted` 就是为这件事记的账：
 * 发过一页、5 秒后一条新行都没来，本端自己判定这个工作区到底了，不再画这颗按钮。
 */
private fun hasMoreSessionsLocal(state: RelayUiState, peerId: String, wsId: String): Boolean {
    if (sessionPageKeyOf(peerId, wsId) in state.sessionPageExhausted) return false
    val declared = state.agentWorkspaces.values
        .firstOrNull { it.peerId == peerId && it.wsId == wsId }
        ?.sessionCount
        ?.toInt()
        ?: return false
    // 与分页 offset 同一把尺子：数**未过滤**原始表（见 `agentSessionCountOf` 的 KDoc）。
    return state.agentSessionCountOf(peerId, wsId) < declared
}

/**
 * 消息流里每段会话的**第一条**消息 id —— 也就是分隔线的落点（task-42 ④）。
 *
 * 判据是「这条消息的会话 id 与上一条不同」：会话 id 相同的一段算同一段，换段就是新一段。
 * 抽成纯函数是为了能被单测钉住：新会话是否真的会产生一条分隔线，不该只能靠真机肉眼确认。
 */
internal fun sessionTransitionIds(messages: List<MessageUi>): Set<String> {
    val transitions = mutableSetOf<String>()
    var lastSessionId: String? = null
    for (m in messages) {
        val sid = extractSessionId(m.id)
        if (sid != null && sid != lastSessionId) {
            transitions.add(m.id)
            lastSessionId = sid
        }
    }
    return transitions
}

/**
 * 分隔线上要写的会话标题；null = 标题还没到（调用方画中性文案「新会话」）。
 *
 * 空白串与 null 是同一档：它们都表示「这条会话现在没有可写的名字」，而不是一个真标题。
 * 这里**不**退回 sessionId —— 身份键不是标题（AgentSessionUi.displayTitle 的 KDoc）。
 */
internal fun sessionDividerTitle(sessionTitle: String?): String? =
    sessionTitle?.takeIf { it.isNotBlank() }

internal fun extractSessionId(msgId: String): String? {
    if (!msgId.startsWith("agent-ev-")) return null
    val rest = msgId.removePrefix("agent-ev-")
    val parts = rest.split("-")
    if (parts.size >= 3) {
        val last = parts.last().toLongOrNull()
        val secondLast = parts[parts.size - 2].toLongOrNull()
        // 格式为 agent-ev-${sessionId}-${cursor}-${at}，其中 at 是毫秒级时间戳（> 1e12）
        if (last != null && last > 1_000_000_000_000L && secondLast != null) {
            return parts.dropLast(2).joinToString("-")
        }
    }
    if (parts.size >= 2) {
        val last = parts.last().toLongOrNull()
        if (last != null) {
            return parts.dropLast(1).joinToString("-")
        }
    }
    return rest
}

@Composable
private fun SessionDivider(
    sessionTitle: String?,
    modifier: Modifier = Modifier,
) {
    // 没有标题也**必须画这条分隔线**（task-42 ④）。
    //
    // 早先这里是「标题为空就 return」：本意是不让「任务：868856e1」这种把身份键当标题的
    // 东西上屏（那条规矩仍然成立，见 AgentSessionUi.displayTitle 的 KDoc）。但它把
    // **新建会话**这一档也一起吞了 —— 新会话先到消息、后到（甚至一直没到）带标题的
    // agent-session 帧，于是用户看到的是「有新消息，却没有任何分段标记」，而分段标记
    // 恰恰是他要的那个东西。
    //
    // 现在的取舍：**线永远画**；标题没到时用一句中性文案「新会话」，标题到齐后同一帧
    // 自然升级成真标题（标题来自 state，会触发重组）。仍然**不**退回 sessionId 前 8 位。
    val displayTitle = sessionDividerTitle(sessionTitle)
    val titleText = if (displayTitle != null) {
        stringResource(Res.string.chat_task_divider_label, displayTitle)
    } else {
        stringResource(Res.string.chat_task_divider_new)
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_terminal),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )
    }
}
