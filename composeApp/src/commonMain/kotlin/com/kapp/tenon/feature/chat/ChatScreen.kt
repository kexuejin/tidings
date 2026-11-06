// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.SenderIdentity
import com.kapp.tenon.domain.directChatMentionTargets
import com.kapp.tenon.domain.groupParticipantRows
import com.kapp.tenon.domain.resolveNodeIdentity
import com.kapp.tenon.domain.resolveSenderIdentity
import com.kapp.tenon.domain.chatPeer
import com.kapp.tenon.domain.groupOfConv
import com.kapp.tenon.domain.groupPeer
import com.kapp.tenon.domain.memberOnlineCount
import com.kapp.tenon.domain.messagesWith
import com.kapp.tenon.domain.peer
import com.kapp.tenon.domain.senderContext
import com.kapp.tenon.domain.RelayUiState
import com.kapp.tenon.domain.isPendingApproval
import com.kapp.tenon.domain.mayDecideApproval
import com.kapp.tenon.domain.pendingApprovalCount
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.transport.LocalFile
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.feature.agents.AgentSessionBar
import com.kapp.tenon.ui.components.AppBanner
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.generated.resources.chat_qa_diff
import com.kapp.tenon.generated.resources.chat_qa_status
import com.kapp.tenon.generated.resources.chat_qa_stop
import com.kapp.tenon.generated.resources.chat_qa_test
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.ic_info
import com.kapp.tenon.generated.resources.ic_list
import com.kapp.tenon.generated.resources.ic_terminal
import com.kapp.tenon.ui.components.ComposerCapability
import com.kapp.tenon.ui.components.MentionTarget
import com.kapp.tenon.ui.components.MessageComposer
import com.kapp.tenon.ui.components.insertMention
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.EmptyState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.TransferState
import com.kapp.tenon.generated.resources.action_find_nodes
import com.kapp.tenon.generated.resources.chat_attach
import com.kapp.tenon.generated.resources.chat_folder_limit_hint
import com.kapp.tenon.generated.resources.chat_group_media_hint
import com.kapp.tenon.generated.resources.chat_image
import com.kapp.tenon.generated.resources.chat_no_selection_message
import com.kapp.tenon.generated.resources.chat_no_selection_title
import com.kapp.tenon.generated.resources.chat_nudge
import com.kapp.tenon.generated.resources.chat_peer_offline
import com.kapp.tenon.generated.resources.chat_pk
import com.kapp.tenon.generated.resources.common_agent
import com.kapp.tenon.generated.resources.common_profile_unavailable
import com.kapp.tenon.generated.resources.chat_voice_record
import com.kapp.tenon.generated.resources.hist_search_title
import com.kapp.tenon.generated.resources.ic_attachment
import com.kapp.tenon.generated.resources.ic_chat_bubble
import com.kapp.tenon.generated.resources.ic_image
import com.kapp.tenon.generated.resources.ic_mic
import com.kapp.tenon.generated.resources.ic_nudge
import com.kapp.tenon.generated.resources.agent_host_sleeping_banner
import com.kapp.tenon.generated.resources.ic_moon
import com.kapp.tenon.generated.resources.ic_pk
import com.kapp.tenon.generated.resources.ic_search
import com.kapp.tenon.generated.resources.chat_group_readonly_left
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import org.jetbrains.compose.resources.painterResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kapp.tenon.generated.resources.ic_plus
import com.kapp.tenon.generated.resources.chat_new_task_action


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
    /**
     * §9.3 快捷指令芯片开关：只由手机导航传 true（群会话才有 chips）；桌面
     * （[com.kapp.tenon.ui.desktop.DesktopChatPane]）用默认 false = 显式关 ——
     * 桌面有自己的键盘与命令行路径，不共享这一排。
     */
    showQuickActions: Boolean = false,
) {
    // ---- 四个 holder 展开成本地名 ------------------------------------------
    // 函数体一行未改：下面这些名字与旧签名的 35 个形参同名同义，差异只有可空性。
    // 旧签名里 onAcceptTransfer / onDeclineTransfer 是 `= {}` 的非空默认体，
    // 现在收窄成可空 —— 「点了没反应」与「不画这个入口」是两件事，后者才对
    // （见 [ChatScreenActions.onAttachFile] 的 KDoc）。收窄后三处调用点显式补 `?: {}`。
    val chatSlice = data.chatSlice
    val agentSlice = data.agentSlice
    val nodeSlice = data.nodeSlice
    val settingsSlice = data.settingsSlice
    val chromeSlice = data.chromeSlice
    val peerId = data.peerId
    val now = data.now
    val cacheDir = data.cacheDir
    val onSend = actions.onSend
    val onSendWithIntent = actions.onSendWithIntent
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
    val realPeer = peer(peerId, nodeSlice, settingsSlice)
    // 群会话：会话键是 `group:<groupId>`，所以 `peer()` 必然查不到人 —— 它的"对端"
    // 是那份成员表。群存在但一条消息都还没有时 `chatPeer` 也返回 null（它靠 messages
    // 兜底），所以这里再兜一层 [RelayUiState.groupPeer]，否则一个刚建好的群点进去
    // 看到的是"选一个会话"。兜完 `peer` 非空，下面所有既有的渲染路径照旧。
    val group = groupOfConv(settingsSlice, peerId)
    // **群优先**，不是 `chatPeer` 优先。`chatPeer` 对任何有历史的会话都会回一个
    // 只有 nodeId、昵称空着的占位，而群的会话键是 `group:<groupId>` —— 先问它的话，
    // 头部画出来的是「group:g-」这种用户看不懂的东西，头像退化成那个占位 id 的首字母，
    // 输入栏还会因为占位的 `online = false` 平白多一句「对方离线，消息会排队」，
    // 而群消息其实一条都不会排队（见 `RelayController.sendToGroup`）。
    val peer = groupPeer(group, nodeSlice, settingsSlice) ?: chatPeer(chatSlice, nodeSlice, settingsSlice, peerId)
    // 群成员里当前在线的人数（不含自己）。头部与输入栏提示都要用它，
    // 所以只算一次 —— 两处各算一遍迟早会有一处忘了跟成员表更新。
    val memberOnlineCount = group?.let { memberOnlineCount(it, nodeSlice, settingsSlice) } ?: 0
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
            icon = painterResource(Res.drawable.ic_chat_bubble),
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val messages = messagesWith(chatSlice, peerId)

    // 身份解析上下文（冒泡署名 / 头像 / Markdown / 群成员面板 / @ 候选 / agentInvolved
    // 共用这一份事实）。判据只在 `SenderIdentity` 里写一次，这里不再各自 `peer()` 一遍。
    val identityCtx = remember(chatSlice, agentSlice, nodeSlice, settingsSlice, peerId) { senderContext(peerId, chatSlice, agentSlice, nodeSlice, settingsSlice) }
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
        msg.mine -> resolveNodeIdentity(settingsSlice.settings.nodeId, bubbleCtx).displayName
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

    val draftState = remember(peerId) { mutableStateOf(initialDraft) }
    var draft by draftState
    val lastRecalledTextState = remember(peerId) { mutableStateOf<String?>(null) }
    var lastRecalledText by lastRecalledTextState
    val replyingToMessageState = remember(peerId) { mutableStateOf<MessageUi?>(null) }
    var replyingToMessage by replyingToMessageState
    // 声明式卡片的**本机乐观记账**（方案 §5.3）：点击审批按钮先置灰再发动作，
    // 键是 "cardId|actionId"。会话切换即重建（remember(peerId)）—— 换个会话看别的卡，
    // 这份 pending 没有意义；宿主判定（rev+1 新帧）到达后由三态判据接管。
    val pendingCardActionsState = remember(peerId) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingCardActions by pendingCardActionsState

    // ── 上下文唤起胶囊（方案 §5.2，交付 2）──────────────────────────────
    // 键是 @ token —— 与 @ 候选（MentionTarget.token）、气泡署名同一份事实
    // （`GroupMeta.virtualAgents[].name`），不另造名字。
    // 只在**群**里建：agentIntent 是群文本的键，单聊塞了会被白名单整帧拒收。
    val capsuleAgents = remember(group, agentSlice.agentWorkspaces) {
        if (group == null) {
            emptyMap()
        } else {
            val wsTitles = agentSlice.agentWorkspaces.values
                .associate { it.wsId to it.wsTitle.orEmpty() }
            group.virtualAgents.orEmpty().mapNotNull { member ->
                val name = member.name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val token = "@" + name
                // 白名单的标题优先用声明自带的（随 group.info 广播，非宿主也有）；
                // 声明没带时退回本机工作区表，再退回 wsId 本身 —— 三档都不编路径。
                val workspaces = member.allowedWorkspaces
                    .map { CapsuleWorkspaceOption(it.wsId, it.title.ifBlank { wsTitles[it.wsId] ?: it.wsId }) }
                    .ifEmpty {
                        member.defaultWsId?.let { wsId ->
                            listOf(CapsuleWorkspaceOption(wsId, wsTitles[wsId]?.takeIf { it.isNotBlank() } ?: wsId))
                        }
                    }
                    .orEmpty()
                token to CapsuleAgentInfo(
                    agentToken = token,
                    agentName = name,
                    agentId = member.agentId,
                    hostNodeId = member.hostNodeId,
                    isTeam = member.entityType == "team" || member.teamMembers.isNotEmpty(),
                    workspaces = workspaces,
                    defaultWsId = member.defaultWsId,
                )
            }.toMap()
        }
    }
    val capsuleStateState = remember(peerId) { mutableStateOf(CapsuleState.Empty) }
    var capsuleState by capsuleStateState
    // 草稿变更的**唯一入口**（手机内置输入栏与桌面输入卡片共用）：赋值之外，
    // 同一步评估胶囊的唤起判据（§5.2）—— 键入合法 @agent 唤起、粘帖不误触发。
    // 分散在两个输入形态里各评一遍，迟早有一处漏。
    fun updateDraftAndCapsule(next: String) {
        val previous = draft
        draft = next
        capsuleState = capsuleOnDraftEdit(capsuleState, previous, next, capsuleAgents)
        peerId?.let { onDraftChangeExternal?.invoke(it, next) }
    }

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
        // 胶囊选中的意图随正文**一帧**发出（方案 §4.2 单帧原子意图）：
        // 壳接了 onSendWithIntent 才装键，null = 这个壳没有意图通路，退回普通发送。
        // 发送即意图消费完毕：胶囊随之清空（草稿也没了，绑定依据不复存在）。
        val intentSelection = capsuleState.selection
        capsuleState = CapsuleState.Empty
        if (body.isNotBlank() || filesToSend.isEmpty()) {
            if (intentSelection != null && onSendWithIntent != null) {
                onSendWithIntent(peer.nodeId, body, replyId, intentSelection.toIntentJson())
            } else {
                onSend(peer.nodeId, body, replyId)
            }
        }
        if (filesToSend.isNotEmpty()) {
            onSendFiles?.invoke(peer.nodeId, filesToSend)
        }
    }
    val forwardingMessageState = remember { mutableStateOf<MessageUi?>(null) }
    var forwardingMessage by forwardingMessageState
    val showGroupInfoState = remember(peerId) { mutableStateOf(false) }
    var showGroupInfo by showGroupInfoState
    // 「清空本会话记录」的确认框开合。动作来自头部（⋮），确认框画在这一层 —— 桌面壳的
    // 头部不进 ChatScreen，它自己那份确认框在 DesktopChatPane 里。
    val showClearConfirmState = remember(peerId) { mutableStateOf(false) }
    var showClearConfirm by showClearConfirmState
    val showCloseAndClearConfirmState = remember(peerId) { mutableStateOf(false) }
    var showCloseAndClearConfirm by showCloseAndClearConfirmState
    val showVoiceRecordState = remember(peerId) { mutableStateOf(false) }
    var showVoiceRecord by showVoiceRecordState
    val previewingImagePathState = remember { mutableStateOf<String?>(null) }
    var previewingImagePath by previewingImagePathState
    val showHistorySearchState = remember(peerId) { mutableStateOf(initialHistorySearch) }
    var showHistorySearch by showHistorySearchState
    val showPkPickerState = remember { mutableStateOf(false) }
    var showPkPicker by showPkPickerState
    // 会话切换浮层的开合跟着**工作区**走，不只跟 peerId：列表本身按 effectiveWsId 收窄
    val matchedWs = remember(group, agentSlice.agentWorkspaces) {
        if (group == null) null
        else agentSlice.agentWorkspaces.values.firstOrNull { ws ->
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
    val showSessionPickerState = remember(peerId, effectiveWsId) { mutableStateOf(false) }
    var showSessionPicker by showSessionPickerState
    // 目标选择 Bottom Sheet（§5.2）的开合。跟会话走：切走即关（remember(peerId)）。
    val showTaskPickerState = remember(peerId) { mutableStateOf(false) }
    var showTaskPicker by showTaskPickerState

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
            showHistorySearch || showSessionPicker || showTaskPicker,
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
            showTaskPicker -> showTaskPicker = false
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

    // ARP-14 拆分：上面这段派生整体搬进 `ChatAgentSessionState.kt`
    // （`rememberChatAgentSessionState`，逐行搬移、remember 键原样）。
    // 这里保留同名只读别名 —— 主体对这 12 个名字的 ~50 处引用零改动；
    // `groupAgentHosts` / `activeSessionEvents` 是派生内部细节，不外露。
    val agentState = rememberChatAgentSessionState(
        agentSlice = agentSlice,
        peerNodeId = peer.nodeId,
        effectiveWsId = effectiveWsId,
        group = group,
        identityCtx = identityCtx,
        groupAgentIdentities = groupRows.agents,
        groupHasAgent = groupRows.hasAgent,
        peerIsAgent = peer.isAgent,
        selfNodeId = settingsSlice.settings.nodeId,
        activeSessionId = data.activeSessionId,
        now = data.now,
    )
    val isWorkspaceGroup = agentState.isWorkspaceGroup
    val leftGroup = agentState.leftGroup
    val newTaskToken = agentState.newTaskToken
    val agentSessions = agentState.agentSessions
    val archivedSessions = agentState.archivedSessions
    val agentInvolved = agentState.agentInvolved
    val activeSession = agentState.activeSession
    val sessionHasPendingApproval = agentState.hasPendingApproval
    val sessionQueuedCount = agentState.queuedCount
    val sessionActiveRun = agentState.activeRun
    val sessionLastActivityAt = agentState.lastActivityAt
    val agentStatusSnapshot = agentState.statusSnapshot
    // 是**同一个** —— 两处各拼一次 token，迟早有一处 @ 了却叫不醒。
    val mentionSender: (SenderIdentity) -> Unit = { identity ->
        val (next, _) = insertMention(
            text = draft,
            start = draft.length,
            end = draft.length,
            token = identity.mentionToken,
        )
        draft = next
        // @ 候选点选/长按头像是**手动唤起**（§5.2「手动键入 @ 选中」）；点的是人
        // （不在 capsuleAgents 里）就不唤起 —— 胶囊只绑执行主体。
        capsuleAgents[identity.mentionToken]?.let { picked ->
            capsuleState = capsuleOnAgentPick(capsuleState, picked, next, capsuleAgents)
        }
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
    val historyTargetNodeId = remember(peer.nodeId, requestWsId, agentSlice.agentSessions, agentSlice.agentWorkspaces) {
        if (requestWsId != null) {
            agentSlice.agentWorkspaces.values.firstOrNull { it.wsId == requestWsId }?.peerId
                ?: agentSlice.agentSessions.values.firstOrNull { it.wsId == requestWsId }?.peerId
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
    // 「回复 agent 发言」唤起胶囊（§5.2 的第二个时机）：挂在 replyingToMessage 上，
    // 回复谁、取消回复都在这一处生效 —— 不必在每条气泡的 onReply 里各写一遍。
    // 绑定用的 token 来自与气泡署名同一个解析器（capsuleAgents 的键 = @token）。
    LaunchedEffect(replyingToMessage, capsuleAgents) {
        val msg = replyingToMessage
        val agent = if (msg == null) {
            null
        } else {
            val token = if (group != null) {
                resolveSenderIdentity(msg.senderId, bubbleCtx)?.mentionToken
            } else {
                null // 单聊没有 agentIntent 键，胶囊只服务群（见 capsuleAgents 的建表条件）
            }
            token?.let(capsuleAgents::get)
        }
        capsuleState = capsuleOnReply(capsuleState, agent)
    }


    // ARP-14 拆分：贴底判据与未读记账整段搬进 `ChatScrollFollow.kt`
    // （`rememberChatScrollFollow`，逐行搬移、remember/LaunchedEffect 键原样）。
    val scrollFollow = rememberChatScrollFollow(
        listState = listState,
        peerId = peerId,
        messages = messages,
        initialUnreadOnEntry = data.unreadOnEntry,
    )
    val isNearBottom = scrollFollow.isNearBottom.value

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
                        showTaskPicker -> { showTaskPicker = false; true }
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
                identityContext = senderContext(peerId, chatSlice, agentSlice, nodeSlice, settingsSlice),
                // 工作区名从**宣告台账**取（`agentWorkspaces` 是 D3 帧的落点，带 wsTitle）。
                // 取不到就不传 —— 那时副标题只画会话名与在线态，不编一个名字（父稿 §0 的降级铁律）。
                activeSession = activeSession,
                workspaceName = effectiveWsId?.let { ws ->
                    agentSlice.agentWorkspaces.values
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
            val sessionPendingCount = remember(agentSlice.agentApprovals, agentSessions) {
                activeSession?.sessionId?.let { sid ->
                    pendingApprovalCount(agentSlice.agentApprovals.values, nowMillis(), sessionId = sid)
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

        chromeSlice.banner?.let {
            AppBanner(it, onDismissBanner ?: {}, Modifier.padding(horizontal = sp.lg, vertical = sp.sm))
        }

        // Workspace / Agent 待审批 Banner：作为 IM 插件的直接替代体验，在会话中直接展示待批复工具调用
        // 待批 = pending **且未过期**（领域层唯一判据）。修复前这里只按 status 过滤：
        // 一条早上就该过期的审批，下午在聊天里还能点「批准」，而看板/会话条早就不算它了。
        val approvalsNow = nowMillis()
        val agentApprovals = remember(
            agentSlice.agentApprovals, peer.nodeId, isWorkspaceGroup, agentSessions, archivedSessions, approvalsNow,
        ) {
            val inScope = if (isWorkspaceGroup) {
                val sessionIds = (agentSessions + archivedSessions).map { it.sessionId }.toSet()
                agentSlice.agentApprovals.values.filter { it.sessionId in sessionIds || it.peerId == peer.nodeId }
            } else {
                agentSlice.agentApprovals.values.filter { it.peerId == peer.nodeId }
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
                isReadOnly = { appr -> !mayDecideApproval(appr, agentSlice, nodeSlice, settingsSlice, approvalsNow) },
                modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.xs),
            )
        }

        // 宿主设备睡眠/离线感知 Banner（工作区协同规范 v3.1）
        val isHostSleeping = if (group != null) {
            val hostNodes = group.virtualAgents?.mapNotNull { it.hostNodeId } ?: emptyList()
            hostNodes.isNotEmpty() && hostNodes.all { hostId ->
                val p = peer(hostId, nodeSlice, settingsSlice)
                p != null && !p.online
            }
        } else if (peer.isAgent) {
            !peer.online
        } else false

        if (isHostSleeping) {
            androidx.compose.material3.Surface(
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant),
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
            val activeAgentRun = agentSlice.agentRuns.values.firstOrNull { run ->
                !run.isTerminal && group.virtualAgents?.any { it.hostNodeId == run.peerId } == true
            }
            if (activeAgentRun != null) {
                val agentName = group.virtualAgents?.firstOrNull { it.hostNodeId == activeAgentRun.peerId }?.name
                    ?: peer(activeAgentRun.peerId, nodeSlice, settingsSlice)?.displayName
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
            // 消息列表主体：空态 / LazyColumn / 回到最新胶囊整块（ARP-14 后半）。
            // 五个回写状态以 MutableState 下传（本侧 var 委托与宿主内 var 委托同源）。
            ChatMessageList(
                messages = messages,
                group = group,
                peer = peer,
                peerId = peerId,
                now = now,
                agentSlice = agentSlice,
                listState = listState,
                isNearBottom = isNearBottom,
                historyAtBeginning = historyAtBeginning,
                highlightedMessageId = highlightedMessageId,
                historyLoading = historyLoading,
                scrollFollow = scrollFollow,
                bubbleCtx = bubbleCtx,
                bubbleFraction = bubbleFraction,
                composerSlot = composerSlot,
                composerFocus = composerFocus,
                requestComposerFocus = ::requestComposerFocus,
                scrollToMessage = ::scrollToMessage,
                quotedSenderNameOf = ::quotedSenderNameOf,
                mentionSender = mentionSender,
                coroutineScope = coroutineScope,
                actions = actions,
                receivedFileActions = receivedFileActions,
                onDraftChangeExternal = onDraftChangeExternal,
                forwardingMessageState = forwardingMessageState,
                previewingImagePathState = previewingImagePathState,
                replyingToMessageState = replyingToMessageState,
                lastRecalledTextState = lastRecalledTextState,
                pendingCardActionsState = pendingCardActionsState,
                draftState = draftState,
            )
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

        // 目标胶囊（方案 §5.2）：只在唤起时出现（平时 100% 纯净 —— 常驻目标条已废除）。
        // 长在共享 ChatScreen 里，手机与桌面天然同源（AGENTS §2.6）。
        val boundCapsule = capsuleState
        if (group != null && boundCapsule.agent != null) {
            AgentContextCapsule(
                agent = boundCapsule.agent,
                selection = boundCapsule.selection,
                onExpand = { showTaskPicker = true },
                onDismiss = { capsuleState = capsuleOnDismiss(capsuleState) },
                modifier = Modifier.padding(horizontal = sp.lg14, vertical = sp.xs),
            )
        }

        if (composerSlot != null) {
            // 草稿仍由 ChatScreen 持有（remember(peerId)），所以切换会话时桌面输入卡片也不会串内容。
            // 回复条 / 新任务 token / 命中跳转同机同源：桌面输入卡片拿到的是与手机
            // 内置输入栏同一份事实（见 ComposerSlotArgs 的 KDoc）。
            composerSlot(
                ComposerSlotArgs(
                    draft = draft,
                    onDraftChange = ::updateDraftAndCapsule,
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
                onDraftChange = ::updateDraftAndCapsule,
                stagedFiles = stagedFiles,
                onRemoveStagedFile = ::removeStagedFile,
                // §9.3 快捷指令芯片：手机群会话常驻一行；桌面不传 = 显式关。
                // 点一下 = 命令字面量进草稿（过目再发）；到了宿主由前置拦截链消费（§9.2）。
                quickActions = if (showQuickActions && ConvId.isGroup(peerId)) {
                    listOf(
                        ComposerCapability("qa-status", Res.string.chat_qa_status, Res.drawable.ic_info) {
                            updateDraftAndCapsule("/status")
                        },
                        ComposerCapability("qa-diff", Res.string.chat_qa_diff, Res.drawable.ic_list) {
                            updateDraftAndCapsule("/diff")
                        },
                        ComposerCapability("qa-test", Res.string.chat_qa_test, Res.drawable.ic_terminal) {
                            updateDraftAndCapsule("/test")
                        },
                        ComposerCapability("qa-stop", Res.string.chat_qa_stop, Res.drawable.ic_close) {
                            updateDraftAndCapsule("/stop")
                        },
                    )
                } else {
                    emptyList()
                },
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

    // ---- 浮层区：对话框/浮层宿主 ------------------------------------------
    // 十一个浮层（群信息/语音/转发/Pk/看图/清空×2/历史搜索/任务胶囊/会话选择器）
    // 的公共入口。show* 全部以 MutableState 下传：这里的 var 委托与宿主内的本地委托
    // 读同一个状态对象，宿主里「关浮层」的回写在这里同样成立。
    ChatDialogsHost(
        peer = peer,
        group = group,
        now = now,
        cacheDir = cacheDir,
        memberOnlineCount = memberOnlineCount,
        effectiveWsId = effectiveWsId,
        historyTargetNodeId = historyTargetNodeId,
        agentSessions = agentSessions,
        archivedSessions = archivedSessions,
        activeSession = activeSession,
        chatSlice = chatSlice,
        agentSlice = agentSlice,
        nodeSlice = nodeSlice,
        settingsSlice = settingsSlice,
        identityCtx = identityCtx,
        actions = actions,
        groupActions = groupActions,
        receivedFileActions = receivedFileActions,
        scrollToMessage = ::scrollToMessage,
        onMentionSender = mentionSender,
        showGroupInfoState = showGroupInfoState,
        showVoiceRecordState = showVoiceRecordState,
        forwardingMessageState = forwardingMessageState,
        showPkPickerState = showPkPickerState,
        previewingImagePathState = previewingImagePathState,
        showClearConfirmState = showClearConfirmState,
        showCloseAndClearConfirmState = showCloseAndClearConfirmState,
        showHistorySearchState = showHistorySearchState,
        showTaskPickerState = showTaskPickerState,
        showSessionPickerState = showSessionPickerState,
        capsuleStateState = capsuleStateState,
    )
}
