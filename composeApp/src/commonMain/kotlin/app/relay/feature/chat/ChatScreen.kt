// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import app.relay.app.model.MessageUi
import app.relay.app.model.PeerUi
import app.relay.app.model.TransferState
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
import app.relay.generated.resources.ic_pk
import app.relay.generated.resources.ic_search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


/**
 * 单聊消息流。
 *
 * 投递状态与点对点协议标准语义一致：`已送达` = 对方 ACK 过；`已排队` = 只是进了补发队列，
 * **对方还没收到**。把这两个混成一个"已发送"是这类应用最常见的谎。
 */
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
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var draft by remember(peerId) { mutableStateOf(initialDraft) }
    var replyingToMessage by remember(peerId) { mutableStateOf<MessageUi?>(null) }
    var forwardingMessage by remember { mutableStateOf<MessageUi?>(null) }
    var showGroupInfo by remember(peerId) { mutableStateOf(false) }
    var showVoiceRecord by remember(peerId) { mutableStateOf(false) }
    var previewingImagePath by remember { mutableStateOf<String?>(null) }
    var showHistorySearch by remember(peerId) { mutableStateOf(initialHistorySearch) }
    var showPkPicker by remember { mutableStateOf(false) }
    // 会话切换浮层的开合跟着**工作区**走，不只跟 peerId：列表本身按 data.wsId 收窄
    // （见下面 agentSessions 的 remember），浮层却只认 peerId 的话，从工作区 A 点开浮层
    // 后切到同一节点的 B，浮层仍开着、内容却已换成 B 的会话 —— 用户会以为在看 A 的列表。
    var showSessionPicker by remember(peerId, data.wsId) { mutableStateOf(false) }
    // 空会话那条状态要把光标直接送进输入框（见下面 messages.isEmpty() 分支）。
    // 桌面壳自己提供的输入卡片有自己的焦点管理，所以只在用内置输入栏时才建这个请求器。
    val composerFocus = remember { FocusRequester() }
    // 会话切换条（`.swbar`）与切换对话框共用同一份列表，按当前对端 nodeId 收窄：
    // 会话列表按**工作区**收窄：`data.wsId` 为空 = 不按工作区收窄（直接在跟节点聊），
    // 非空则只要属于该工作区的会话。
    //
    // 为什么用 `filter` 而不是新增一个 `agentSessionsOf(peerId, wsId)`：这一屏是那个口径的
    // 唯一使用者，而筛选条件很短；把它做进 `RelayUiState` 会让「按节点」与「按工作区」
    // 两个口径并列存在、以后又有人选错。
    // 取数口径是 `visibleAgentSessionsOf`（已按 DSH 的可见性两键过滤掉子代理会话与空壳会话），
    // **不是** `agentSessionsOf` —— 后者是未过滤的原始表，分页游标要用它（见 RelayUiState 的 KDoc）。
    // 真机实测：111 行落库会话里 68 行是 DSH 自己一定不显示的（子代理 19 + 空壳 49），
    // 它们成批出现在这个浮层里，而用户的原话是「怎么都是 Route this bounded MEMORY…」。
    val agentSessions = remember(state.agentSessions, peer.nodeId, data.wsId) {
        state.visibleAgentSessionsOf(peer.nodeId).let { all ->
            if (data.wsId == null) all else all.filter { it.wsId == data.wsId }
        }
    }
    val archivedSessions = remember(state.agentSessions, peer.nodeId, data.wsId) {
        state.agentSessionsOf(peer.nodeId).filter {
            it.isArchived && !it.isSubagent && (data.wsId == null || it.wsId == data.wsId)
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

    // 打开这个 agent 会话时，向宿主要该工作区第一页历史会话（PPE-1 增补 D8）。
    //
    // 为什么挂在「打开」而不是启动时全量推：用户裁决原话 ——「正常其实用户只有在需要
    // 打开这个工作区的时候，同步下会话就好吧」。历史可能有上百条，启动时全推既慢又
    // 没必要；打开时才要，代价恰好付在想看的东西上。
    //
    // ## 要哪个工作区：直接用 `data.wsId`，**不再从会话列表里推**
    //
    // 直接从会话列表推会导致零会话工作区陷入死锁，因此必须显式传入 data.wsId。
    // 工作区过滤会话，所以一个本地还没有会话的工作区拿到的是空列表 ⇒ wsId 为 null ⇒
    // 请求根本不发 ⇒ 那个工作区**永远**拉不出它的历史会话（真机实测：点开只有 1 条会话的
    // 工作区能拉，点开零会话的没反应）。
    //
    // 而「用户点的是哪个工作区」在进入这一屏时就已经知道了（`data.wsId`，由工作区列表给出）——
    // 从会话反推它，既是绕路也是错路。`data.wsId == null`（直接跟节点聊）时本来就不该拉。
    val openHistory = actions.onOpenAgentHistory
    val requestWsId = data.wsId
    LaunchedEffect(peer.nodeId, requestWsId, openHistory) {
        if (openHistory != null && requestWsId != null) {
            openHistory(peer.nodeId, requestWsId)
        }
    }
    // 新消息到达时**只在已经贴底时**才自动滚。原来的写法无条件滚，于是用户往上翻旧消息
    // 时，任何一条新消息（包括屏幕协助记下来的那条）都会把视图拽回底部 —— 读旧消息这件事
    // 就没法做了。判据用 `canScrollForward`：它同时覆盖"内容还没占满一屏"（本来就不能滚，
    // 视为贴底，短会话照样自动滚到底）与"用户往上翻过了"（能往前滚 ⇒ 不打扰）。
    //
    // 用 derivedStateOf 而不是在 effect 里现读：effect 只在 messages.size 变化时重启，
    // 而"是否贴底"是随滚动持续变化的，必须在滚动时就更新，否则拿到的是上一次的快照。
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && atBottom) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        if (showHeader) {
            ChatHeader(
                peer = peer,
                state = state,
                // 工作区名从**宣告台账**取（`agentWorkspaces` 是 D3 帧的落点，带 wsTitle）。
                // 取不到就不传 —— 那时副标题只画会话名与在线态，不编一个名字（父稿 §0 的降级铁律）。
                activeSession = activeSession,
                workspaceName = data.wsId?.let { ws ->
                    state.agentWorkspaces.values
                        .firstOrNull { it.peerId == peer.nodeId && it.wsId == ws }
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
                onOpenSessionPicker = if (group == null && peer.isAgent) ({ showSessionPicker = true }) else null,
                // 「切换会话」的资格与选择器列表必须同集合：这里传的就是下面
                // `agentSessions` 那份（已 visible 过滤 + 已按 wsId 收窄）。
                // 头部自己按 peerId 再数一次时，节点有 2 条可见会话分属两个工作区
                // 会让按钮画得出来、点开只有 1 行。
                visibleSessionCount = agentSessions.size,
            )
            HorizontalDivider()
        }

        // Agent 会话切换条（原型 `.swbar`）：标题栏正下方显示当前会话名与状态，
        // 多会话时可点击唤起切换对话框。消息流本身尚无 sessionId 维度（D3 提案），
        // 但会话条是「当前在和哪个会话交互」的信息展示，是合理的只读信息，非虚假交互。
        if (showHeader && group == null && peer.isAgent && (agentSessions.isNotEmpty() || archivedSessions.isNotEmpty())) {
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
        val agentApprovals = remember(state.agentApprovals, peer.nodeId) {
            state.agentApprovals.values.filter { it.peerId == peer.nodeId && it.status == "pending" }
        }
        if (peer.isAgent && agentApprovals.isNotEmpty() && onRespondApproval != null) {
            ChatApprovalBanner(
                approvals = agentApprovals,
                onRespond = onRespondApproval,
                modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.xs),
            )
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 原型 `.msgs{...padding:12px 14px 6px;...gap:10px}`（桌面 `.win .msgs` 为
                    // `14px 16px 6px`/`gap:9px`，手机取 `:root` 那一条）。
                    contentPadding = PaddingValues(start = sp.lg14, top = sp.md, end = sp.lg14, bottom = sp.sm6),
                    verticalArrangement = Arrangement.spacedBy(sp.md10),
                ) {
                    items(messages, key = { it.id }) { message ->
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
                            )
                        }
                    }
                }
            }
        }

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

    if (showSessionPicker && peer.isAgent && (agentSessions.isNotEmpty() || archivedSessions.isNotEmpty())) {
        // 「加载更多」的落点：要的是**当前会话所属工作区**的下一页。
        // 用第一条会话的 wsId 而不是"用户选中的"：选择器列出的是同一个工作区里的会话
        // （会话就是按工作区归拢的），所以这一组共用一个 wsId。
        // 优先用「用户点进来的那个工作区」（`data.wsId`）：它是这一屏过滤会话的依据本身，
        // 拿它当分页的归属不会自相矛盾。会话列表里的 wsId 只是兜底 —— 直接跟节点聊
        // （`data.wsId == null`）时，列表才可能横跨多个工作区。
        val pickerWsId = data.wsId ?: (agentSessions + archivedSessions).firstOrNull { it.wsId != null }?.wsId
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
                pickerWsId != null && hasMoreSessionsLocal(state, peer.nodeId, pickerWsId)
            }?.let { load ->
                { load(peer.nodeId, pickerWsId!!) }
            },
            loadingMore = pickerWsId != null &&
                sessionPageKeyOf(peer.nodeId, pickerWsId) in state.sessionPageLoading,
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
