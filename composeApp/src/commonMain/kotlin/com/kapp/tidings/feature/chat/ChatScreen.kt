// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.domain.SenderIdentity
import com.kapp.tidings.domain.agentHostsInGroup
import com.kapp.tidings.domain.canRecall
import com.kapp.tidings.domain.directChatMentionTargets
import com.kapp.tidings.domain.groupParticipantRows
import com.kapp.tidings.domain.resolveNodeIdentity
import com.kapp.tidings.domain.resolveSenderIdentity
import com.kapp.tidings.domain.formatRecallRemaining
import com.kapp.tidings.domain.isRecallUrgent
import com.kapp.tidings.domain.recallBlockedReason
import com.kapp.tidings.domain.recallRemainingMs
import com.kapp.tidings.domain.sessionPageKeyOf
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.isPendingApproval
import com.kapp.tidings.domain.mayDecideApproval
import com.kapp.tidings.domain.pendingApprovalCount
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.transport.LocalFile
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.feature.agents.AgentSessionBar
import com.kapp.tidings.feature.agents.AgentSessionPickerDialog
import com.kapp.tidings.ui.components.AppBanner
import com.kapp.tidings.ui.components.DayDivider
import com.kapp.tidings.ui.components.TimelineSeparator
import com.kapp.tidings.ui.components.ComposerCapability
import com.kapp.tidings.ui.components.PkPickerDialog
import com.kapp.tidings.ui.components.VoiceRecordDialog
import com.kapp.tidings.ui.components.ForwardTargetDialog
import com.kapp.tidings.ui.components.GroupInfoDialog
import com.kapp.tidings.ui.components.ImageViewerDialog
import com.kapp.tidings.ui.components.MentionTarget
import com.kapp.tidings.ui.components.MessageComposer
import com.kapp.tidings.ui.components.ScreenHistoryCard
import com.kapp.tidings.ui.components.insertMention
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.EmptyState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NoticeKind
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.generated.resources.action_find_nodes
import com.kapp.tidings.generated.resources.chat_attach
import com.kapp.tidings.generated.resources.chat_recall_expired
import com.kapp.tidings.generated.resources.chat_empty_message
import com.kapp.tidings.generated.resources.chat_empty_title
import com.kapp.tidings.generated.resources.chat_first_message
import com.kapp.tidings.generated.resources.chat_folder_limit_hint
import com.kapp.tidings.generated.resources.chat_group_media_hint
import com.kapp.tidings.generated.resources.chat_image
import com.kapp.tidings.generated.resources.chat_no_selection_message
import com.kapp.tidings.generated.resources.chat_no_selection_title
import com.kapp.tidings.generated.resources.chat_nudge
import com.kapp.tidings.generated.resources.chat_peer_offline
import com.kapp.tidings.generated.resources.chat_pk
import com.kapp.tidings.generated.resources.common_agent
import com.kapp.tidings.generated.resources.common_profile_unavailable
import com.kapp.tidings.generated.resources.chat_voice_record
import com.kapp.tidings.generated.resources.hist_search_title
import com.kapp.tidings.generated.resources.ic_attachment
import com.kapp.tidings.generated.resources.ic_image
import com.kapp.tidings.generated.resources.ic_mic
import com.kapp.tidings.generated.resources.ic_nudge
import com.kapp.tidings.generated.resources.agent_host_sleeping_banner
import com.kapp.tidings.generated.resources.ic_moon
import com.kapp.tidings.generated.resources.ic_pk
import com.kapp.tidings.generated.resources.ic_search
import com.kapp.tidings.generated.resources.ic_down
import com.kapp.tidings.generated.resources.chat_group_readonly_left
import com.kapp.tidings.generated.resources.chat_history_begin
import com.kapp.tidings.generated.resources.chat_history_loading
import com.kapp.tidings.generated.resources.chat_unread_jump
import com.kapp.tidings.generated.resources.chat_unread_jump_latest
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
import com.kapp.tidings.generated.resources.ic_terminal
import com.kapp.tidings.generated.resources.ic_plus
import com.kapp.tidings.generated.resources.chat_new_task_action
import com.kapp.tidings.generated.resources.chat_task_divider_label
import com.kapp.tidings.generated.resources.chat_task_divider_new


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
    val onPickFiles = actions.onPickFiles
    val onSendFiles = actions.onSendFiles
    val onAcceptTransfer = actions.onAcceptTransfer
    val onDeclineTransfer = actions.onDeclineTransfer
    val onCancelTransfer = actions.onCancelTransfer
    val onResumeTransfer = actions.onResumeTransfer
    val onRecallMessage = actions.onRecallMessage
    val onDeleteMessage = actions.onDeleteMessage
    val onRetryMessage = actions.onRetryMessage
    val onForwardMessage = actions.onForwardMessage
    val onNudge = actions.onNudge
    val onRequestScreenView = actions.onRequestScreenView
    val onSendPk = actions.onSendPk
    val onSendVoice = actions.onSendVoice
    val onRespondApproval = actions.onRespondApproval
    val onRespondChoice = actions.onRespondChoice
    val onSearchInConversation = actions.onSearchInConversation
    val onClearMessages = actions.onClearMessages
    val onCloseAndClearConversation = actions.onCloseAndClearConversation
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
    // 草稿的外部存放处（可空槽）。壳接住它，切会话时再作为 initialDraft 喂回来，
    // 于是"写到一半切走再回来"不会丢字。传 null = 这个壳不关心，行为不变。
    val onDraftChangeExternal = chrome.onDraftChange
    val onStagedFilesChangeExternal = chrome.onStagedFilesChange
    val initialEmojiOpen = chrome.initialEmojiOpen
    val initialHistorySearch = chrome.initialHistorySearch
    val initialScreenRequestConfirmOpen = chrome.initialScreenRequestConfirmOpen
    /** null = 用内置输入栏；非 null = 桌面壳自己提供的输入卡片。 */
    val composerSlot: (@Composable (ComposerSlotArgs) -> Unit)? = chrome.composerSlot
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

    // 身份解析上下文（冒泡署名 / 头像 / Markdown / 群成员面板 / @ 候选 / agentInvolved
    // 共用这一份事实）。判据只在 `SenderIdentity` 里写一次，这里不再各自 `peer()` 一遍。
    val identityCtx = remember(state, peerId) { state.senderContext(peerId) }
    // **消息署名单独一份 ctx：裸 nodeId 是「人」，不是「那台机器上的 agent」。**
    //
    // 与 `identityCtx` 分开而不是直接改它：那一份还服务群成员列表（`groupParticipantRows`），
    // 而成员列表里一个 agent 宿主的裸 nodeId **应该**显示 agent 名（那一行代表的就是
    // 那台机器上的 agent）。两个场景的正确答案相反，详见 `bareNodeIdIsHuman` 的 KDoc。
    //
    // 真身：用户在自己的 DSH 里打字，桌面端把 prompt 转发进群时 `senderId` 就是**用户
    // 的 nodeId**；手机上它的 agentKindsByHost 里那个 nodeId 有 `dsh`（它确实能跑 agent），
    // 于是被渲染成 `dsh` —— 用户看到自己说的话署名成了 agent。
    val bubbleCtx = remember(identityCtx) { identityCtx.copy(bareNodeIdIsHuman = true) }
    val groupRows = remember(identityCtx) { groupParticipantRows(identityCtx) }
    // `@` 候选：**外壳与内置输入栏共用这一份**。桌面壳的输入卡片也要它 —— 否则
    // 桌面上输 `@` 什么都不弹（真机反馈）。
    // 群与单聊**共用同一条**「谁能被 @」的派生规则，只是来源不同：
    // 群来自成员表（`groupParticipantRows`），单聊来自对端那台机器上的 agent
    // （`directChatMentionTargets`）。此前这里是 `else -> emptyList()`，
    // 于是**单聊里输 @ 什么都不弹** —— 而单聊正是跟 agent 说话的主场景。
    val mentionIdentities = if (group != null) {
        (groupRows.agents + groupRows.humans).filter { it.canMention }
    } else {
        peerId?.let { directChatMentionTargets(it, identityCtx) }.orEmpty()
            .filter { it.canMention }
    }
    val profileNote = stringResource(Res.string.common_profile_unavailable)
    val mentionTargets = mentionIdentities.map { identity ->
        MentionTarget(
            token = identity.mentionToken,
            label = identity.displayName,
            note = if (identity.needsProfileNote) profileNote else null,
            isAgent = identity.isAgent,
        )
    }
    // 群里有没有 agent 成员 —— 问题 1 的口径是**成员级事实**，不是"这是不是工作区群"。
    val groupAgentInvolved = groupRows.hasAgent

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
    // 向上翻页：滚到历史顶端时读更早的一页。
    //
    // 位置判据：reverseLayout=true ⇒ 索引 0 是最新一条，**索引最大**那头才是历史顶端。
    // 所以判的是 max(visibleItems.index) 靠近 total-1，而不是 firstVisibleItemIndex。
    //
    // 防抖：一次只申请一页。请求置位后要等 messages.size 真的变多（effect 重启）
    // 才允许再申请；读到尽头时 size 不变 ⇒ 自然停下，不会每帧打一次数据库。
    var olderPageRequested by remember(peerId) { mutableStateOf(false) }
    // 向上翻页的**可见反馈**：设计规格为这一屏声明了 loading_history 槽，
    // 而此前滚到顶只会静默发一次请求 —— 屏幕一动不动，用户以为"历史只有这么多"或"卡住了"。
    //
    // 两个事实：
    //  · 到达最早：messages 里最老那条的 seq == 1（seq 是会话内自增游标，第一条恒为 1），
    //    这是**真事实**，不是猜的；没有 seq（还没落库）就不宣称"到最早了"。
    //  · 正在加载：请求发出后置位，页到达（size 变了）就清；另有一个"放弃等待"的上界，
    //    因为域层的 loadOlder 在不返回新页时不发任何信号（那需要一个生产侧的游标状态，
    //    不在本轮范围）。上界只是"别让转圈永远转"，不是判据。
    var historyLoading by remember(peerId) { mutableStateOf(false) }
    var historyRequestSize by remember(peerId) { mutableStateOf(-1) }
    val historyAtBeginning = isHistoryBeginning(messages.firstOrNull()?.seq)
    LaunchedEffect(listState, messages.size, actions.onLoadOlder) {
        val loadOlder = actions.onLoadOlder ?: return@LaunchedEffect
        olderPageRequested = false
        if (messages.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            // 距末尾还有 3 条就预取：等真滚到底再加载会看到明显空窗。
            if (!olderPageRequested && total > 0 && lastVisible >= total - 3) {
                olderPageRequested = true
                historyRequestSize = messages.size
                historyLoading = !historyAtBeginning
                loadOlder()
            }
        }
    }
    // 页到达（size 变多）就收掉 loading；另设一个上界，避免域层"没有更早的了"时永远转圈。
    LaunchedEffect(messages.size) {
        if (historyLoading && messages.size != historyRequestSize) historyLoading = false
    }
    LaunchedEffect(historyLoading) {
        if (historyLoading) {
            kotlinx.coroutines.delay(HISTORY_LOADING_GIVE_UP_MS)
            historyLoading = false
        }
    }
    val coroutineScope = rememberCoroutineScope()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    // 引用卡 / 历史搜索命中「跳到那条消息」的唯一出口。
    //
    // 列表是「数据倒序 + reverseLayout」双翻结构：`messages` 里的正序下标不能直接喂给
    // `scrollToItem`，必须先做镜像换算（见 [visualIndexOf] 的 KDoc）。此前两处调用点
    // 各自把正序下标直接塞进去，滚到的是**镜像位置** —— 消息总数对称的另一头。
    fun scrollToMessage(messageId: String) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            highlightedMessageId = messageId
            coroutineScope.launch {
                listState.animateScrollToItem(visualIndexOf(idx, messages.size))
                kotlinx.coroutines.delay(1200)
                if (highlightedMessageId == messageId) {
                    highlightedMessageId = null
                }
            }
        }
    }
    // 引用卡 / 回复预览上「谁说的」的显示名。走**与气泡署名同一个解析器**（一个事实
    // 一个来源）；资料没到（hasProfile = false）就如实返回 null，由调用点画
    // 「未知成员」，不把 8 位 hex 的裸 id 当人名印。
    fun quotedSenderNameOf(msg: MessageUi): String? = when {
        msg.mine -> resolveNodeIdentity(state.selfNodeId, bubbleCtx).displayName
        group != null -> resolveSenderIdentity(msg.senderId, bubbleCtx)
            ?.takeIf { it.hasProfile }?.displayName
        else -> resolveNodeIdentity(peer.nodeId, bubbleCtx)
            .takeIf { it.hasProfile }?.displayName
    }
    var stagedFiles by remember(peerId, data.initialStagedFiles) { mutableStateOf(data.initialStagedFiles) }

    fun stageFiles(files: List<LocalFile>) {
        if (files.isEmpty()) return
        val merged = mergeStagedFiles(stagedFiles, files)
        stagedFiles = merged
        peerId?.let { onStagedFilesChangeExternal?.invoke(it, merged) }
    }

    fun removeStagedFile(file: LocalFile) {
        val updated = stagedFiles.filterNot { it.path == file.path }
        stagedFiles = updated
        peerId?.let { onStagedFilesChangeExternal?.invoke(it, updated) }
    }

    var draft by remember(peerId) { mutableStateOf(initialDraft) }
    var lastRecalledText by remember(peerId) { mutableStateOf<String?>(null) }
    var replyingToMessage by remember(peerId) { mutableStateOf<MessageUi?>(null) }
    // **发送的唯一出口**：手机内置输入栏与桌面输入卡片（composerSlot）共用这一个函数。
    //
    // 四件事必须一起发生，漏一件都是一条独立的真机缺陷：
    // ① 复位本地草稿；② 清外部草稿表（否则已发出的正文会在切回来时「复活」进输入框，
    // 见 [notifyDraftCleared]）；③ 清引用态（否则发完引用条还挂着，下一条又变成"回复"）；
    // ④ 把 replyId 交给发送侧（否则「回复」发出去只是一条普通消息）。
    //
    // 抽成一个函数（而不是在两份输入形态里各写一遍）是这条修复的**一半**：桌面输入卡片
    // 从前直接调 `controller.sendToConversation`，于是 ②③④ 全在它那条路上缺失 ——
    // 补一个出口比补三处调用更难漏。接线由 `ComposerSlotSendWiringTest` 盯住。
    fun submitMessage(body: String, replyId: String?) {
        val filesToSend = stagedFiles
        stagedFiles = emptyList()
        peerId?.let { onStagedFilesChangeExternal?.invoke(it, emptyList()) }
        draft = ""
        replyingToMessage = null
        notifyDraftCleared(peerId, onDraftChangeExternal)
        if (body.isNotBlank() || filesToSend.isEmpty()) {
            onSend(peer.nodeId, body, replyId)
        }
        if (filesToSend.isNotEmpty()) {
            onSendFiles?.invoke(peer.nodeId, filesToSend)
        }
    }
    var forwardingMessage by remember { mutableStateOf<MessageUi?>(null) }
    var showGroupInfo by remember(peerId) { mutableStateOf(false) }
    // 「清空本会话记录」的确认框开合。动作来自头部（⋮），确认框画在这一层 —— 桌面壳的
    // 头部不进 ChatScreen，它自己那份确认框在 DesktopChatPane 里。
    var showClearConfirm by remember(peerId) { mutableStateOf(false) }
    var showCloseAndClearConfirm by remember(peerId) { mutableStateOf(false) }
    var showVoiceRecord by remember(peerId) { mutableStateOf(false) }
    var previewingImagePath by remember { mutableStateOf<String?>(null) }
    var showHistorySearch by remember(peerId) { mutableStateOf(initialHistorySearch) }
    var showPkPicker by remember { mutableStateOf(false) }
    // 会话切换浮层的开合跟着**工作区**走，不只跟 peerId：列表本身按 effectiveWsId 收窄
    val matchedWs = remember(group, state.agentWorkspaces) {
        if (group == null) null
        else state.agentWorkspaces.values.firstOrNull { ws ->
            val desc = group.description.orEmpty()
            // 跨模块 public 属性先落成局部量，否则 smart cast 不成立。
            val wsPath = ws.wsPath
            val wsIdValue = ws.wsId
            val wsTitle = ws.wsTitle
            val pathMatch = !wsPath.isNullOrBlank() && (desc.contains(wsPath) || desc == wsPath)
            val idMatch = !wsIdValue.isNullOrBlank() && (desc.contains(wsIdValue) || desc == wsIdValue)
            val titleMatch = !wsTitle.isNullOrBlank() && group.name.contains(wsTitle)
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
            showClearConfirm || showCloseAndClearConfirm || showGroupInfo || showVoiceRecord || showPkPicker ||
            showHistorySearch || showSessionPicker,
    ) {
        when {
            forwardingMessage != null -> forwardingMessage = null
            previewingImagePath != null -> previewingImagePath = null
            showClearConfirm -> showClearConfirm = false
            showCloseAndClearConfirm -> showCloseAndClearConfirm = false
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
    // 「把焦点落到输入框」的**唯一入口**（回复 / 长按头像直接 @ / 新任务 chip 共用）。
    //
    // 两条壳的落地方式不同，这不是选择而是事实：桌面（composerSlot != null）下
    // [composerFocus] **没有挂载点** —— MessageComposer 不组合，桌面输入卡片用的是它
    // 自己的 requester。对未挂载的 requester 调 requestFocus() 会打印
    // "FocusRequester is not initialized" 并返回 false：不崩，但焦点留在消息列表上，
    // 用户看到"点了回复，光标没回来，还得再点一下输入框"。
    // 所以桌面把意图变成一个**递增计数**交给 [ComposerSlotArgs.focusRequest]，
    // 由输入卡片消费（见 DesktopComposer 的同名参数）。
    var composerFocusRequest by remember(peerId) { mutableStateOf(COMPOSER_FOCUS_REQUEST_IDLE) }
    fun requestComposerFocus() {
        if (composerSlot == null) {
            composerFocus.requestFocus()
        } else {
            composerFocusRequest = nextComposerFocusRequest(composerFocusRequest)
        }
    }
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
    // 被移出群：群元数据里已经没有自己。
    //
    // 真身：被踢出群后输入栏仍完全可用，用户打完字点发送，只得到一条红色「发送失败」——
    // 反复试，因为界面从没说过原因。`GroupMeta.members` 是权威（协议同步的成员表），
    // 不是从消息里猜的。
    val leftGroup = group != null && state.selfNodeId !in group.members
    // 「新任务」chip 点名的 agent token：**手机内置输入栏与桌面输入卡片共用这一个值**
    // （§2.6：同一事实一个来源 —— 桌面 chip 从前在这里之外自己写死 "@dsh "，
    // 在别的 agent 群里叫不醒）。判据与手机 onPick 原有的一条一致。
    // null = 不是工作区群（chip 在两条壳里都不画）。
    val newTaskToken = if (isWorkspaceGroup) {
        groupRows.agents
            .firstOrNull { !it.agentKind.isNullOrBlank() }
            ?.mentionToken
            ?: "@dsh"
    } else {
        null
    }
    // 群里 agent 宿主是谁（本机或远端都算）。这是群上下文里"该看谁的会话"的唯一来源：
    // 群会话键是 `group:<id>`，拿它去 `agentSessionsOf` 一条都查不到 —— 于是「正在思考」
    // 永远不显示，只剩输入区那句「有成员不在线」。
    val groupAgentHosts = remember(identityCtx) {
        if (group != null) agentHostsInGroup(identityCtx) else emptySet()
    }
    val agentSessions = remember(state.agentSessions, peer.nodeId, effectiveWsId, groupAgentHosts) {
        if (effectiveWsId != null) {
            state.agentSessions.values
                .filter { it.wsId == effectiveWsId && !it.isSubagent && !it.isArchived && it.title?.isNotBlank() == true }
                .distinctBy { it.sessionId }
        } else if (group != null) {
            state.agentSessions.values
                .filter {
                    it.peerId in groupAgentHosts && !it.isSubagent && !it.isArchived &&
                        it.title?.isNotBlank() == true
                }
                .distinctBy { it.sessionId }
        } else {
            state.visibleAgentSessionsOf(peer.nodeId)
        }
    }
    val archivedSessions = remember(state.agentSessions, peer.nodeId, effectiveWsId, groupAgentHosts) {
        if (effectiveWsId != null) {
            state.agentSessions.values
                .filter { it.wsId == effectiveWsId && !it.isSubagent && it.isArchived }
                .distinctBy { it.sessionId }
        } else if (group != null) {
            state.agentSessions.values
                .filter { it.peerId in groupAgentHosts && !it.isSubagent && it.isArchived }
                .distinctBy { it.sessionId }
        } else {
            state.agentSessionsOf(peer.nodeId).filter {
                it.isArchived && !it.isSubagent
            }
        }
    }
    // 这个会话算不算"有 agent 参与"：群里按**成员表**判（远端 agent 也是成员），
    // 不是按"是不是工作区群" —— 普通群里入住了一个 agent，状态线同样该出现。
    val agentInvolved = if (group != null) groupAgentInvolved else peer.isAgent
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
    // 这个会话里有几条指令**排在队列里还没执行**（PPE-1 §6.3 的 agent-queue）。
    //
    // 为什么统计到**会话**而不是设备：用户关心的是「我刚发的那条排上了没」，
    // 而一台设备上可能并行好几个会话。工作区看板上那个 queueCount 是按设备的,
    // 两者口径不同、用途也不同，不要合并。
    val sessionQueuedCount = remember(state.agentTasks, activeSession, peer.nodeId) {
        state.agentTasks.values.count { task ->
            when {
                activeSession != null ->
                    task.peerId == activeSession.peerId && task.sessionId == activeSession.sessionId
                // 没有选中会话时退回按设备数 —— 与 pendingApproval 同一条降级口径。
                else -> task.peerId == peer.nodeId
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

    // 状态线的**快照**：与 [ComposerStatusLine] 内部调用的是同一个纯函数（只是 now 不同），
    // 用来回答"状态线已经说明了情况，输入区那句「有成员不在线」还要不要说"。
    // 不在这里另写一套 busy/run 判定 —— 两处判据一旦分叉，就会出现"上面说正在思考、
    // 下面说有人在离线"的自相矛盾。
    val agentStatusSnapshot = agentComposerStatusOf(
        agentInvolved = agentInvolved,
        sessionState = activeSession?.state,
        lastActivityAt = sessionLastActivityAt,
        hasPendingApproval = sessionHasPendingApproval,
        hasActiveRun = sessionActiveRun != null,
        now = data.now,
    )

    // 长按头像 / 成员行「直接 @」：token 来自身份解析器，插入函数与输入栏的候选列表
    // 是**同一个** —— 两处各拼一次 token，迟早有一处 @ 了却叫不醒。
    val mentionSender: (SenderIdentity) -> Unit = { identity ->
        val (next, _) = insertMention(
            text = draft,
            start = draft.length,
            end = draft.length,
            token = identity.mentionToken,
        )
        draft = next
        // 桌面上这条 @ 会经 ComposerSlotArgs.draft 流进输入卡片，但**焦点不会**自己过去：
        // 走统一入口，让卡片的 requester 把光标落进来（否则用户 @ 完还得再点输入框）。
        requestComposerFocus()
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
    // **reverseLayout = true**：索引 0 是最新一条（画在最下面）。所以「最靠底的那条」
    // 是 visibleItemsInfo.firstOrNull()，不是 lastOrNull() —— 后者在倒序下是最早的一条。
    val bottomVisibleIndex by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index }
    }
    // key 必须带 peerId：只按条数记时，两个**条数相同**的会话互切不会重建，
    // `derivedStateOf` 里捕获的还是上一个会话的 itemCount ⇒ 贴底判据算错。
    val isNearBottom by remember(peerId, messages.size) {
        derivedStateOf {
            isNearBottomAfterLayout(
                itemCount = messages.size,
                totalItemCount = listState.layoutInfo.totalItemsCount,
                firstVisibleIndex = bottomVisibleIndex,
                // 倒序下「继续往最新方向滚」是 canScrollBackward。
                canScrollBackward = listState.canScrollBackward,
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
    // 「为**哪个会话**做过首次锚定」。用 peerId 而不是布尔量，是因为布尔量的复位时机
    // 依赖 `remember(peerId)` 的重建与 `LaunchedEffect` 重启谁先发生 —— 两个都有消息的
    // 会话互切时，`messages.isEmpty()` 不变（false→false），复位分支不触发，而布尔量
    // 还没随 remember 刷新 ⇒ 读到的仍是上一个会话的 true ⇒ **一次都不滚**。
    // 判据换成「这个 peerId 滚过没有」后，就不再依赖那两次重建的顺序。
    //
    // 进入会话：**倒序渲染后不需要锚定**。
    //
    // 正序版本要在这里反复 `scrollToItem(lastIndex)`，而 LazyColumn 在容器高度未稳定时
    // 会提前把 `canScrollForward` 报成 false —— 真机实测落点 firstVisibleItemIndex=74
    // （应为 83），于是「进会话停在中间」。三轮修补（布尔量 / peerId 记账 / 进入令牌）
    // 都是在修「何时该滚」，没解决「滚的时候还没量完」，所以一直没修好。
    //
    // `reverseLayout = true` 把问题从根上取消：索引 0 就是最新一条，进入即在底部，
    // 没有「要滚到哪」这件事。这里只保留「空会话时复位未读计数」那一支。
    LaunchedEffect(peerId, messages.size) {
        if (messages.isEmpty()) {
            // 会话被清空（或本来就空）：复位未读计数。
            previousMessageCount = 0
            unseenWhileAway = 0
        }
    }

    LaunchedEffect(messages.size) {
        val newAdded = (messages.size - previousMessageCount).coerceAtLeast(0)
        previousMessageCount = messages.size
        if (newAdded <= 0) return@LaunchedEffect
        val lastMsgIsMine = messages.lastOrNull()?.mine == true
        unseenWhileAway = unseenAfterGrowth(unseenWhileAway, newAdded, isNearBottom, lastMsgIsMine)
        if (shouldFollowLatest(isNearBottom, lastMsgIsMine)) {
            // reverseLayout：最新一条在索引 0，不是 lastIndex。
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(isNearBottom) {
        if (!isNearBottom) return@LaunchedEffect
        // 贴底 = 最新那条已经在眼前，进入时采下的 N 条未读**不再在屏幕外**，
        // 计数到此为止。放掉它而不是留着：否则用户之后往上翻旧消息时，胶囊会拿一个
        // 早就看过的旧数字来骗他。
        //
        // 倒序渲染后「进入会话」本身就在底部，所以这条不再需要「是否锚定过」那个前置。
        unseenWhileAway = 0
        unreadOnEntry = 0
    }

    Column(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    when {
                        forwardingMessage != null -> { forwardingMessage = null; true }
                        previewingImagePath != null -> { previewingImagePath = null; true }
                        showClearConfirm -> { showClearConfirm = false; true }
                        showCloseAndClearConfirm -> { showCloseAndClearConfirm = false; true }
                        showGroupInfo -> { showGroupInfo = false; true }
                        showVoiceRecord -> { showVoiceRecord = false; true }
                        showPkPicker -> { showPkPicker = false; true }
                        showHistorySearch -> { showHistorySearch = false; true }
                        showSessionPicker -> { showSessionPicker = false; true }
                        else -> false
                    }
                } else {
                    false
                }
            },
    ) {
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
                // 入口**不再因为对方离线而整颗消失**（那会让人以为功能被删了）。
                // 保留入口 → 点开确认表单 → 发送时由控制器给出既有的「当前无法查看屏幕」横幅，
                // 失败有话说。真正在群里/对 agent 时仍然不给入口（那两档没有语义）。
                onRequestScreenView = onRequestScreenView?.takeIf { group == null && !peer.isAgent }
                    ?.let { req -> { req(peer.nodeId) } },
                initialConfirmOpen = initialScreenRequestConfirmOpen,
                group = group,
                memberOnlineCount = memberOnlineCount,
                onOpenGroupInfo = if (group != null) ({ showGroupInfo = true }) else null,
                onSearchHistory = null,
                // 「清空本会话记录」：只在壳真的接了这条动作时画（null = 不画），
                // 点了先开确认框，确认后才真的清（见 ClearConversationDialog）。
                onClearMessages = onClearMessages?.let { { showClearConfirm = true } },
                onCloseAndClearConversation = onCloseAndClearConversation?.let { { showCloseAndClearConfirm = true } },
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
            // 与看板/选择器同一把尺子：过期的不算待批（领域层唯一入口）。
            val sessionPendingCount = remember(state.agentApprovals, agentSessions) {
                activeSession?.sessionId?.let { sid ->
                    pendingApprovalCount(state.agentApprovals.values, nowMillis(), sessionId = sid)
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
        // 待批 = pending **且未过期**（领域层唯一判据）。修复前这里只按 status 过滤：
        // 一条早上就该过期的审批，下午在聊天里还能点「批准」，而看板/会话条早就不算它了。
        val approvalsNow = nowMillis()
        val agentApprovals = remember(
            state.agentApprovals, peer.nodeId, isWorkspaceGroup, agentSessions, archivedSessions, approvalsNow,
        ) {
            val inScope = if (isWorkspaceGroup) {
                val sessionIds = (agentSessions + archivedSessions).map { it.sessionId }.toSet()
                state.agentApprovals.values.filter { it.sessionId in sessionIds || it.peerId == peer.nodeId }
            } else {
                state.agentApprovals.values.filter { it.peerId == peer.nodeId }
            }
            inScope.filter { isPendingApproval(it, approvalsNow) }
        }
        if ((peer.isAgent || isWorkspaceGroup) && agentApprovals.isNotEmpty() &&
            (onRespondApproval != null || onRespondChoice != null)
        ) {
            ChatApprovalBanner(
                approvals = agentApprovals,
                now = approvalsNow,
                onRespond = onRespondApproval,
                onRespondChoice = onRespondChoice,
                // 唯一判据（:core）：修复前这里是第四份手抄 —— 与看板那份（只挡 RO）对不上，
                // 于是同一个用户在两个入口看到两种权限。
                isReadOnly = { appr -> !state.mayDecideApproval(appr, approvalsNow) },
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
                    ?: state.peer(activeAgentRun.peerId)?.displayName
                    ?: stringResource(Res.string.common_agent)
                GroupProgressBar(
                    agentName = agentName,
                    activeRun = activeAgentRun,
                    // 「中断」是**控制意图**，不是聊天内容。
                    //
                    // 真身：这里从前是 `onSend(peer.nodeId, "/cancel", null)` —— 点一下就把
                    // 四个字母当普通群文本发给所有成员（他们都会看到一条 "/cancel" 气泡），
                    // 而对端若不把斜杠命令当命令，agent 根本没停：按钮在骗人。
                    // 协议本来就有 §6.6 的 agent-cancel 帧，走那条控制面。
                    // 槽为 null（宿主没接线）时**不给按钮**（GroupProgressBar 会置灰），
                    // 因为"发一条假消息"比"没有这个按钮"糟得多。
                    onInterrupt = actions.onCancelAgentRun?.let { cancel ->
                        { cancel(activeAgentRun.peerId, activeAgentRun.sessionId, activeAgentRun.runId) }
                    },
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
                // 时间/日期分隔线：列表首条、自然日切换或相邻消息间隔 >= 5 分钟。
                val timelineSeparators = remember(messages) { timelineSeparatorIds(messages, ::localDayKey) }
                // 引用气泡按 id 找源消息：倒一次表 O(n) 变成建一次 map O(n) + 每次查 O(1)。
                // 此前每条带引用的气泡在**每次重组**时 `firstOrNull` 线性扫全表，
                // 长会话里一屏几十条引用就是每帧几十次全扫。
                val messagesById = remember(messages) { messages.associateBy { it.id } }
                val messagePositions = remember(messages) {
                    messages.withIndex().associate { (idx, msg) -> msg.id to idx }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 原型 `.msgs{...padding:12px 14px 6px;...gap:10px}`（桌面 `.win .msgs` 为
                    // `14px 16px 6px`/`gap:9px`，手机取 `:root` 那一条）。
                    contentPadding = PaddingValues(start = sp.lg14, top = sp.md, end = sp.lg14, bottom = sp.sm6),
                    // **内容不足一屏时要贴底**（气泡贴着输入区，不是贴着顶）。
                    //
                    // reverseLayout 只决定「从哪头开始排」，不决定「排不满时贴哪边」：
                    // 少了 `Alignment.Bottom` 这一档，短会话（只有两三条）的气泡会停在顶部、
                    // 下方留出一大片空白，而输入框贴在屏幕底部 —— 真机现场就是这样。
                    // 正序时这个现象不明显（内容和输入区都在上方），倒序把它放大了。
                    verticalArrangement = Arrangement.spacedBy(sp.md10, Alignment.Bottom),
                    // **倒序渲染**（聊天流的标准结构，WeChat/Slack/Telegram 都是这条）。
                    //
                    // 为什么：正序时「最新在末尾」，进入会话必须先量完整个列表才滚得准，
                    // 而 LazyColumn 在容器高度未稳定时 `canScrollForward` 会提前变 false ——
                    // 真机实测停在 firstVisibleItemIndex=74（应为 83）。
                    // 倒序后索引 0 就是最新一条，**进入即在底部**，根本不需要锚定；
                    // 新消息到达时它天然出现在可见区顶部（视觉上是最下方）。
                    reverseLayout = true,
                ) {
                    // **数据也要倒过来**：`reverseLayout` 只翻视觉，若数据仍是最旧在前，
                    // 屏幕上就变成「最新在上、最旧在下」—— 真机截图正是这个样子。
                    // 两者必须同时翻：数据倒序 + reverseLayout，才等价于原来的「最旧在上」。
                    items(messages.asReversed(), key = { it.id }) { message ->
                        val notice = message.notice
                        if (notice != null) {
                            // 系统提示行不是消息：撤回与戳一碰都只留一行居中的灰字（协议 §8）。
                            NoticeLine(
                                kind = notice,
                                // 细节（谁把谁加/移了）。老库记录没有它 ⇒ 组件退回中性文案。
                                detail = message.noticeDetail,
                                // 名字在**这一层**解析：身份规则全在 SenderIdentityContext，
                                // 组件自己不该知道。查不到就退回 8 位短 id（成员面板同一取舍）。
                                displayNameOf = { id -> resolveNodeIdentity(id, bubbleCtx).displayName },
                                // **入口只在「确实是这条消息让你没权」时给**：
                                // 只有 agent 名拿得到、且调用方愿意接这个动作，才画那颗按钮。
                                // 否则会出现一颗点了没反应的按钮 —— 比没有更糟。
                                onRequestGrant = actions.onRequestAgentGrant
                                    ?.let { req ->
                                        {
                                            message.noticeDetail?.actor
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let(req)
                                        }
                                    },
                                onReEdit = if (notice == NoticeKind.RecalledBySelf && !lastRecalledText.isNullOrBlank()) {
                                    {
                                        val textToRestore = lastRecalledText.orEmpty()
                                        draft = textToRestore
                                        peerId?.let { onDraftChangeExternal?.invoke(it, textToRestore) }
                                        requestComposerFocus()
                                    }
                                } else null,
                            )
                        } else if (message.screen != null) {
                            // 跨模块 public 属性先落成局部量，否则 smart cast 不成立。
                            // 上面的 `else if` 已经判过非空；`!!` 只在这里把那个判据固化下来 ——
                            // 换成 ?.let 会把这一整块包进一层 lambda，diff 比收益大。
                            val screenRecord = message.screen!!
                            // 屏幕协助历史卡：它是**独立的一条消息**，不是气泡的附件
                            // （交互规范中将其渲染成 `.message-stack` 的第一个
                            // 孩子）。左右归属与气泡同一条规矩 —— `mine` 在生产者那边就是
                            // "本侧是 viewer"，所以这里直接用。
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                ScreenHistoryCard(record = screenRecord, now = now)
                            }
                        } else {
                            val originalIndex = messagePositions[message.id] ?: -1
                            val prevMsg = if (originalIndex > 0) messages.getOrNull(originalIndex - 1) else null
                            val nextMsg = if (originalIndex in 0 until messages.size - 1) messages.getOrNull(originalIndex + 1) else null

                            val isSameSenderWith = { a: MessageUi, b: MessageUi ->
                                a.notice == null && b.notice == null &&
                                    a.screen == null && b.screen == null &&
                                    a.mine == b.mine &&
                                    (a.mine || a.senderId == b.senderId) &&
                                    kotlin.math.abs(a.at - b.at) < 90_000L
                            }

                            val hasPrevSender = prevMsg != null &&
                                isSameSenderWith(prevMsg, message) &&
                                message.id !in timelineSeparators &&
                                message.id !in sessionTransitions

                            val hasNextSender = nextMsg != null &&
                                isSameSenderWith(message, nextMsg) &&
                                nextMsg.id !in timelineSeparators &&
                                nextMsg.id !in sessionTransitions

                            // 「重发」接的是已有的 onSend：以原文本再发一条，不是服务端重试 ——
                            // core 没有按 messageId 重投的接口，而让用户自己照着抄一遍是白给的摩擦。
                            // 所以这里只在有真文本时给出口：文件消息重发不了（本地路径早就没了）。
                            val quotedMsg = message.replyTo?.let { replyId ->
                                messagesById[replyId]
                            }
                            // 这条消息的发送者身份：署名 / 头像 / Markdown / 长按 @ **共用一份**。
                            // 群里按 senderId 解析（`agent:<kind>` 才认得出 agent）；
                            // 私聊的发送者就是会话对端，仍走同一个 `resolveNodeIdentity`。
                            val senderIdentity = when {
                                message.mine -> null
                                group != null -> resolveSenderIdentity(message.senderId, bubbleCtx)
                                else -> resolveNodeIdentity(peer.nodeId, bubbleCtx)
                            }
                            MessageBubble(
                                message = message,
                                bubbleFraction = bubbleFraction,
                                onAcceptTransfer = onAcceptTransfer ?: {},
                                onDeclineTransfer = onDeclineTransfer ?: {},
                                onCancelTransfer = onCancelTransfer,
                                onResumeTransfer = onResumeTransfer,
                                receivedFileActions = receivedFileActions,
                                // 「重发」必须按**原消息 id** 重放：换新 id 重发在收端就是
                                // 另一条消息（真机现场：同一句话在桌面库里两个 id）。
                                // 没有这条能力的壳（预览）退回旧行为，红字仍然可点。
                                onRetry = if (message.file == null && message.text.isNotBlank()) {
                                    onRetryMessage?.let { retry -> { _: String -> retry(message) } }
                                        ?: { text: String -> onSend(peer.nodeId, text, null) }
                                } else {
                                    null
                                },
                                // 撤回入口的判据在 canRecall 里（自己的、5 分钟内、媒体要有 msgId）。
                                // 超时后**入口置 null（点不动）但这一项仍然显示** ——
                                // 只剩"不能撤回"这一个事实时，把它藏起来等于不解释（见 recallBlockedReason）。
                                onRecall = onRecallMessage
                                    ?.takeIf { canRecall(message, now) }
                                    ?.let { recall ->
                                        {
                                            if (message.text.isNotBlank()) {
                                                lastRecalledText = message.text
                                            }
                                            recall(message)
                                        }
                                    },
                                recallVisible = onRecallMessage != null &&
                                    recallBlockedReason(message) == null,
                                recallMeta = formatRecallRemaining(recallRemainingMs(message, now))
                                    ?: stringResource(Res.string.chat_recall_expired),
                                recallUrgent = isRecallUrgent(recallRemainingMs(message, now)),
                                onReply = {
                                    replyingToMessage = message
                                    // 回复是"立刻要打字"的动作：焦点必须跟着进输入框。
                                    // 桌面走计数意图（本层 requester 在桌面没有挂载点）。
                                    requestComposerFocus()
                                },
                                onForward = onForwardMessage?.let { { forwardingMessage = message } },
                                // 只删本机这一条：不动协议、不通知对方。没有这一项时，
                                // 用户想清掉一条只有「撤回」（有时限）或「清空整个会话」两条路。
                                onDelete = onDeleteMessage?.let { del ->
                                    { peerId?.let { pid -> del(pid, message.id) } }
                                },
                                // 群里必须署名：会话键是 `group:<groupId>`，气泡左右只分得出
                                // "是不是我发的"，分不出"是哪个人发的"。私聊恒 null（会话键
                                // 就是发送者，再署一次名是同一件事的第二个说法）。
                                senderName = if (group != null && !message.mine) {
                                    senderIdentity?.displayName
                                } else {
                                    null
                                },
                                // 原型 `.msg .mav` 只在**别人发的**消息上出现（演示数据里
                                // 自己发的几条一条 `ini` 都没有）。头像与署名**同源**：
                                // agent 的 `agent:<kind>` 走解析器后 isAgent 为真，于是它画的是
                                // agent 头像（以前 peer() 查不到人就退化成会话对端的样子）。
                                senderAvatar = senderIdentity?.let { identity ->
                                    ChatAvatar(
                                        name = identity.displayName,
                                        avatar = identity.avatar,
                                        isAgent = identity.isAgent,
                                    )
                                },
                                // 长按发送者头像 = 直接 @ 这个人 / 这个 agent（自己除外）。
                                // **不再限定群**：单聊里对方若是 agent，长按它的头像同样该能 @ ——
                                // 此前这里是 `group != null && ...`，单聊恒为 null，于是
                                // `ChatBubbles` 连手势都没注册（它的 `if (onMentionSender != null)`）。
                                // 判据只留「这条消息不是自己发的」+ `canMention`（它已排除自己）。
                                onMentionSender = if (!message.mine) {
                                    senderIdentity
                                        ?.takeIf { it.canMention }
                                        ?.let { identity -> { mentionSender(identity) } }
                                } else {
                                    null
                                },
                                quotedMessage = quotedMsg,
                                // 引用卡上的发送者名：与气泡署名同一个解析器，不在组件里
                                // 拿 senderId 前 8 位凑数（那是协议标识，不是人名）。
                                quotedSenderName = quotedMsg?.let { quotedSenderNameOf(it) },
                                onScrollToQuoted = quotedMsg?.let { target ->
                                    {
                                        // 走镜像换算后的视觉下标（见 scrollToMessage 的 KDoc）。
                                        scrollToMessage(target.id)
                                    }
                                },
                                onImageClick = { previewingImagePath = it },
                                // agent 的回复本身就是 Markdown（标题/列表/代码块/表格/链接），
                                // 纯文本渲染会让用户看到 ** 与 - 的字面量。人发的消息不转，
                                // 否则用户打的星号会被当成强调。
                                renderMarkdown = shouldRenderMarkdown(
                                    mine = message.mine,
                                    senderIsAgent = senderIdentity?.isAgent == true,
                                    senderId = message.senderId,
                                ),
                                isHighlighted = message.id == highlightedMessageId,
                                hasPrevSender = hasPrevSender,
                                hasNextSender = hasNextSender,
                            )
                        }
                        // **两条分隔线必须画在气泡之后，不能画在之前。**
                        //
                        // 这个列表是 `reverseLayout = true` + 数据倒序（见上面那段 KDoc）。
                        // 正序下「画在消息之前」= 视觉上方，是对的；一旦翻了向，同一个写法
                        // 就变成**视觉下方** —— 真机现场：`任务：…` 那条线落在它所标记的消息
                        // 的下面，读起来像「这条消息结束后换了新会话」。
                        //
                        // 判据：在 reverseLayout 里，**后画的元素在视觉上方**。所以日期线与
                        // 会话线都要写在 MessageBubble 之后。改顺序时请连这条注释一起想。
                        if (message.id in timelineSeparators) {
                            TimelineSeparator(at = message.at, now = now)
                        }
                        if (message.id in sessionTransitions) {
                            // **必须与 `sessionTransitionIds` 用同一个判据取 sid。**
                            //
                            // 这里原来写的是 `extractSessionId(message.id)` —— 只认 id 前缀。
                            // 而判定那边用的是 `sessionIdOf`（优先协议字段 `agentSessionId`，
                            // 再退回前缀）。两者对**手机端**给出的答案不同：
                            //
                            //   桌面：id = `agent-ev-session-xxx-12` ⇒ 前缀能取到 ⇒ 有分割线
                            //   手机：id = 信封 UUID（转发时换掉了）⇒ 前缀取不到 ⇒ **整条不画**
                            //
                            // 于是「判定说这里是切换点、渲染说自己取不到 sid 就跳过」—— 手机端
                            // 永远画不出那条线，而数据是对的。这正是用户报的「桌面有、移动端没有」。
                            val sid = sessionIdOf(message)
                            if (sid != null) {
                                // **标题优先用消息自带的那一份。**
                                //
                                // `state.agentSessions` 是**本机私有**数据：它只由本机 agent 宿主
                                // 写入（`saveSession`），从来没有同步到对端 —— 真机读数：手机侧那张
                                // 表是**空的**（count=0），所以它那边每条线都只能显示「新会话」。
                                //
                                // 转发时把标题一并带上之后，对端也拿得到真标题。查本地表那一步
                                // 保留着：桌面上 agent 会话就是本机的，表里有更权威（会随标题更新
                                // 而刷新）。两者都没有才退回「新会话」那句中性说明。
                                val s = state.agentSessions.values.firstOrNull { it.sessionId == sid && it.title?.isNotBlank() == true }
                                    ?: state.agentSessions.values.firstOrNull { it.sessionId == sid }
                                SessionDivider(sessionTitle = message.agentSessionTitle ?: s?.title)
                            }
                        }
                    }
                    // 历史这一端的两条边界提示（设计规格的 loading_history 槽 + 到达最早的终点）。
                    //
                    // **必须声明在 items(...) 之后**：reverseLayout 里"后声明的条目在视觉上方"
                    // （与 DayDivider 画在气泡之后是同一条规矩），而这两条属于消息流的最顶端。
                    if (historyLoading) {
                        item(key = "history-edge-loading") {
                            HistoryEdgeMarker(text = stringResource(Res.string.chat_history_loading))
                        }
                    } else if (historyAtBeginning) {
                        item(key = "history-edge-begin") {
                            HistoryEdgeMarker(text = stringResource(Res.string.chat_history_begin))
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
                // 触达 48dp、视觉仍是那颗矮胶囊：外层透明 Box 吃命中区，内层 Surface 只画外观。
                // 此前整颗就是可点区域（约 30dp 高），拇指要试两次；而它是"回到最新"的唯一切入口。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = sp.md)
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable {
                            unreadOnEntry = 0
                            unseenWhileAway = 0
                            coroutineScope.launch {
                                // reverseLayout：最新一条在索引 0。
                                if (messages.isNotEmpty()) listState.animateScrollToItem(0)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 4.dp,
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
        }

        // agent 会话的输入提示：放在输入区正上方。两条壳（手机内置输入栏 / 桌面 composerSlot）
        // 共用这一个调用点 —— 以后要挪位置只动这一行（见 ComposerStatusLine 的 KDoc）。
        ComposerStatusLine(
            // 成员级事实：群里入住了一个 agent 就算 agentInvolved（不再要求"工作区群"）。
            agentInvolved = agentInvolved,
            sessionState = activeSession?.state,
            lastActivityAt = sessionLastActivityAt,
            hasPendingApproval = sessionHasPendingApproval,
            hasActiveRun = sessionActiveRun != null,
            queuedCount = sessionQueuedCount,
            now = data.now,
        )

        if (composerSlot != null) {
            // 草稿仍由 ChatScreen 持有（remember(peerId)），所以切换会话时桌面输入卡片也不会串内容。
            // 回复条 / 新任务 token / 命中跳转同机同源：桌面输入卡片拿到的是与手机
            // 内置输入栏同一份事实（见 ComposerSlotArgs 的 KDoc）。
            composerSlot(
                ComposerSlotArgs(
                    draft = draft,
                    onDraftChange = { next ->
                        draft = next
                        peerId?.let { onDraftChangeExternal?.invoke(it, next) }
                    },
                    stagedFiles = stagedFiles,
                    onRemoveStagedFile = ::removeStagedFile,
                    onAttachFiles = {
                        onPickFiles?.let { pick ->
                            coroutineScope.launch {
                                val picked = pick()
                                if (picked.isNotEmpty()) {
                                    stageFiles(picked)
                                }
                            }
                        }
                    },
                    onStageFiles = ::stageFiles,
                    // 「回复」必须真的是回复：把手机内置输入栏用的**同一个**出口交出去，
                    // 桌面发出去的消息才会带上 replyId，发完引用条才会一起消失。
                    onSend = ::submitMessage,
                    // 焦点意图：本层没有挂载点的那个 requester 换成计数交给卡片消费
                    // （见 [requestComposerFocus] 与 DesktopComposer 的同名参数）。
                    focusRequest = composerFocusRequest,
                    mentionTargets = mentionTargets,
                    replyingTo = replyingToMessage,
                    replySenderName = replyingToMessage?.let { quotedSenderNameOf(it) },
                    onCancelReply = { replyingToMessage = null },
                    newTaskToken = newTaskToken,
                    onJumpToMessage = ::scrollToMessage,
                    readOnlyReason = if (leftGroup) stringResource(Res.string.chat_group_readonly_left) else null,
                ),
            )
        } else {
            MessageComposer(
                draft = draft,
                onDraftChange = { next ->
                    draft = next
                    peerId?.let { onDraftChangeExternal?.invoke(it, next) }
                },
                stagedFiles = stagedFiles,
                onRemoveStagedFile = ::removeStagedFile,
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
                // **群里不再显示「有成员不在线，他们上线后会收到」。**
                //
                // 用户两次说过这条没用（第一次我把它降级成"状态线出现时让位"，那只是
                // 让它少出现，没解决它本身没有信息量这件事）。理由是：
                // - 群消息**本来就**为离线成员入队并补发，这是协议保证的行为，不是异常；
                // - 把它当提示说，等于每次有人不在线都提醒一遍「你的消息会送达」——
                //   用户不需要为一件必然会发生的事被通知；
                // - 头部已经有「1/4 人在线」这个更精确、可随时看的读数，重复表达同一件事；
                // - 占着输入区上方那一行，挤掉真正有用的东西（agent 正在思考 / 等审批）。
                //
                // **单聊的「对方离线」保留**：那一条说的是"这条现在发不出去，会排队"，
                // 是用户需要知道的状态差异（群是补发、单聊是排队等对方上线），
                // 不能因为群那条被删就一起删掉。
                offlineHint = when {
                    // 状态线已经在说明情况时，离线提示让位（判据用同一个快照，不另写一套）。
                    agentStatusSnapshot != null -> null
                    group == null && !peer.online -> stringResource(Res.string.chat_peer_offline)
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
                    // 拉的是 `arrayOf("*/*")`（见 `AndroidFilePicker.pick`），图片本来就是文件，
                    // 所以这颗不需要新的平台 API、也不需要新的发送链路。两颗的差别只有图标与
                    // 标签 —— 这正是原型把「截图 / 图片 / 文件夹」画成三颗的样子里，本端**唯一
                    // 真能通**的那一颗。另外两颗为什么不画，见 `fidelity-deviations.yaml`。
                    // 位置照原型：图片在文件**左边**。
                    onAttachFile?.let { attach ->
                        ComposerCapability(
                            id = "image",
                            label = Res.string.chat_image,
                            icon = Res.drawable.ic_image,
                            onPick = {
                                if (onPickFiles != null) {
                                    coroutineScope.launch {
                                        val picked = onPickFiles()
                                        if (picked.isNotEmpty()) {
                                            stageFiles(picked)
                                        }
                                    }
                                } else {
                                    attach(peer.nodeId)
                                }
                            },
                        )
                    },
                    onAttachFile?.let { attach ->
                        ComposerCapability(
                            id = "file",
                            label = Res.string.chat_attach,
                            icon = Res.drawable.ic_attachment,
                            onPick = {
                                if (onPickFiles != null) {
                                    coroutineScope.launch {
                                        val picked = onPickFiles()
                                        if (picked.isNotEmpty()) {
                                            stageFiles(picked)
                                        }
                                    }
                                } else {
                                    attach(peer.nodeId)
                                }
                            },
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
                                // 用**这个群里的 agent kind** 点名（默认 @dsh），而且与 @ 候选
                                // 走同一个插入函数：写死 "@dsh" 在别的 agent 群里叫不醒。
                                // token 在上面统一解析（桌面输入卡片共用同一个值）。
                                val token = newTaskToken ?: "@dsh"
                                if (!draft.startsWith(token, ignoreCase = true)) {
                                    val (next, _) = insertMention(draft, 0, 0, token)
                                    draft = next
                                }
                                requestComposerFocus()
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
                // @ 候选与成员面板、agentInvolved **同一份**解析结果：agent 以 `@DSH`
                // 出现（不再是宿主昵称），只有 id 的成员如实标「未获取到资料」，
                // 不再把 nodeId 裸串直接当候选名。
                mentionTargets = mentionTargets,
                replyingTo = replyingToMessage,
                // 回复预览的发送者名走身份解析（同气泡署名一份事实），不在组件里
                // 印 senderId 前 8 位。
                replySenderName = replyingToMessage?.let { quotedSenderNameOf(it) },
                onCancelReply = { replyingToMessage = null },
                readOnlyReason = if (leftGroup) stringResource(Res.string.chat_group_readonly_left) else null,
                focusRequester = composerFocus,
                initialEmojiOpen = initialEmojiOpen,
                // 与桌面输入卡片（composerSlot）共用同一个出口：草稿复位、外部草稿表
                // 清空、引用态清空、replyId 传递四件事都在 submitMessage 里一起发生。
                onSend = { submitMessage(draft, replyingToMessage?.id) },
            )
        }
    }

    if (showGroupInfo && group != null) {
        GroupInfoDialog(
            group = group,
            selfNodeId = state.selfNodeId,
            selfNick = state.settings.nick,
            peers = state.peers,
            memberOnlineCount = memberOnlineCount,
            onDismiss = { showGroupInfo = false },
            onAddMembers = groupActions.onAddGroupMembers?.let { add -> { members -> add(group.groupId, members) } },
            onRemoveMember = groupActions.onRemoveGroupMember?.let { remove -> { member -> remove(group.groupId, member) } },
            onUpdateMeta = groupActions.onUpdateGroupMeta,
            onLeaveGroup = groupActions.onLeaveGroup?.let { leave -> { leave(group.groupId) } },
            onDissolveGroup = groupActions.onDissolveGroup?.let { dissolve -> { dissolve(group.groupId) } },
            // 成员面板用**和气泡 / @ 候选同一份**上下文：agent 显示 DSH、@ 候选给出 @DSH。
            identityContext = identityCtx,
            // 长按成员头像 = 直接 @：插入到编辑器后关掉面板（用户下一步是打字）。
            onMentionMember = { identity ->
                showGroupInfo = false
                mentionSender(identity)
            },
            // 「添加智能体」= 暴露**本机项目**（不是加人）。候选取本机 workspace 表。
            localWorkspaces = state.agentWorkspaces.values.filter { it.peerId == state.selfNodeId },
            onExposeWorkspaces = groupActions.onExposeWorkspaces?.let { expose ->
                { chosen ->
                    // 一次成像：chosen 就是**最终集合**，收起与暴露在一次调用里完成
                    // （旧实现先撤再增、逐条调用，每条各发一份元数据，中间态会广播给全群）。
                    expose(group.groupId, chosen)
                }
            },
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

    if (showCloseAndClearConfirm && onCloseAndClearConversation != null) {
        CloseAndClearConversationDialog(
            conversationName = peer.displayName,
            onDismiss = { showCloseAndClearConfirm = false },
            onConfirm = {
                showCloseAndClearConfirm = false
                onCloseAndClearConversation()
            },
        )
    }

    if (showHistorySearch && onSearchInConversation != null) {
        HistorySearchOverlay(
            onDismiss = { showHistorySearch = false },
            onSearch = { q, f -> onSearchInConversation(q, f) },
            onSelectHit = { hit ->
                showHistorySearch = false
                // 走镜像换算后的视觉下标（见 scrollToMessage 的 KDoc）：列表倒序渲染，
                // 正序下标直接 scrollToItem 会滚到镜像位置。
                scrollToMessage(hit.id)
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
/**
 * 一条消息属于哪个 agent 会话：**优先读协议字段**，id 前缀只作为旧数据的回退。
 *
 * ## 为什么要这个顺序
 *
 * 字段（`agentSessionId`）是**事实**，由协议携带，两端一致；
 * id 前缀（`agent-ev-`）是桌面端本地构造消息时的副产物，转发进群时并不会带过去 ——
 * 手机端落库用的是信封 UUID，前缀判据在那里恒为 null，于是桌面有分段线、手机一条都没有。
 *
 * 保留回退是为了**已经落库的旧数据**（升级前收到的 agent 消息 id 里仍带前缀）：
 * 不然老会话的分段线会在升级后消失。新消息一律走字段那条路。
 */
internal fun sessionIdOf(message: MessageUi): String? =
    message.agentSessionId ?: extractSessionId(message.id)

internal fun sessionTransitionIds(messages: List<MessageUi>): Set<String> {
    val transitions = mutableSetOf<String>()
    var lastSessionId: String? = null
    for (m in messages) {
        val sid = sessionIdOf(m)
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

/**
 * 消息流两端的边界提示（"正在加载更早的消息" / "已经到最早一条了"）。
 *
 * 用居中弱化小字而不是转圈：它要回答的是"还有没有更早的"，不是"后台在忙"。
 * 高度固定不下来 —— 文案在两档语言里长度不同，靠 padding + 居中，不给死高度。
 */
@Composable
private fun HistoryEdgeMarker(text: String) {
    val sp = LocalAppSpacing.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = sp.md10),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「正在加载更早的消息」最多显示多久（域层在"没有更早的了"时不发信号，这里只防永远转圈）。 */
private const val HISTORY_LOADING_GIVE_UP_MS = 6_000L

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
