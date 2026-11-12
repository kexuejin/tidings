// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import com.kapp.tenon.domain.DesktopPaneActions
import com.kapp.tenon.core.transport.LocalFile

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

import com.kapp.tenon.domain.ChatSlice
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.AvatarSlice
import com.kapp.tenon.domain.groupOfConv
import com.kapp.tenon.domain.groupPeer
import com.kapp.tenon.domain.chatPeer
import com.kapp.tenon.domain.agentSessionsOf
import com.kapp.tenon.domain.memberOnlineCount
import com.kapp.tenon.domain.peer
import com.kapp.tenon.domain.senderContext
import com.kapp.tenon.domain.ChromeSlice
import com.kapp.tenon.ui.components.GroupInfoDialog
import com.kapp.tenon.generated.resources.chat_group_me
import com.kapp.tenon.generated.resources.chat_group_members
import com.kapp.tenon.generated.resources.chat_group_members_action
import com.kapp.tenon.generated.resources.chat_group_owner_label
import com.kapp.tenon.generated.resources.chat_group_rev
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.feature.chat.ChatHeaderContent
import com.kapp.tenon.feature.chat.ChatHeaderDensity
import com.kapp.tenon.feature.chat.ChatScreen
import com.kapp.tenon.feature.chat.ClearConversationDialog
import com.kapp.tenon.feature.chat.CloseAndClearConversationDialog
import com.kapp.tenon.feature.chat.ChatScreenActions
import com.kapp.tenon.feature.chat.ChatScreenChrome
import com.kapp.tenon.feature.chat.ChatScreenGroupActions
import com.kapp.tenon.feature.chat.ChatScreenState
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.EmptyState
import com.kapp.tenon.ui.components.KindBadge
import com.kapp.tenon.ui.components.ScreenRequestConfirmDialog
import com.kapp.tenon.ui.components.TopBarIconAction
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.chat_caps_section
import com.kapp.tenon.generated.resources.chat_group_agents_ready
import com.kapp.tenon.generated.resources.desktop_more_actions
import com.kapp.tenon.generated.resources.ic_screen_share
import com.kapp.tenon.generated.resources.screen_remote_view_action
import com.kapp.tenon.generated.resources.desktop_action_copy_node_id_short
import com.kapp.tenon.generated.resources.desktop_action_list
import com.kapp.tenon.generated.resources.desktop_action_open_in_new_window
import com.kapp.tenon.generated.resources.chat_clear_local_action
import com.kapp.tenon.generated.resources.chat_close_and_clear_action
import com.kapp.tenon.generated.resources.chat_group_online
import com.kapp.tenon.generated.resources.common_profile_unavailable
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.ic_copy
import com.kapp.tenon.generated.resources.ic_external
import com.kapp.tenon.generated.resources.ic_groups
import com.kapp.tenon.generated.resources.ic_list
import com.kapp.tenon.generated.resources.ic_trash
import org.jetbrains.compose.resources.stringResource

import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.core.protocol.ConvId


@Composable
public fun ChatPane(
    controller: DesktopPaneActions,
    selectedPeerId: String?,
    sizeClass: DesktopWindowSizeClass,
    now: Long,
    onSelect: (String) -> Unit,
    onGoToPairing: () -> Unit,
    onCopyPeerId: (String) -> Unit,
    onOpenInNewWindow: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /** 输入框预置草稿，只给截图门禁用（见 [ChatScreen.initialDraft]）。 */
    initialDraft: String = "",
    /**
     * 草稿的外部存放处 —— 与手机壳同一个槽（见 `ChatScreenChrome.onDraftChange`）。
     *
     * 桌面窗口常驻，没有这张表时"切走会话再回来"草稿就没了。
     * null = 不接管（截图门禁那条路不需要保留草稿）。
     */
    onDraftChange: ((convId: String, text: String) -> Unit)? = null,
    selectedWsId: String? = null,
    activeSessionId: String? = null,
    onSelectAgentSession: ((String) -> Unit)? = null,
    /**
     * 进入这个会话**那一刻**的未读条数（真机反馈 #5 的屏外未读指示）。
     *
     * 由壳在调 `controller.selectConversation` 清未读**之前**采下，再原样透传给
     * [ChatScreenState.unreadOnEntry] —— 采集为什么只能发生在壳里，见该字段的 KDoc。
     * 默认 0 = 没有未读或没采到：指示条会退化成不带数字的「回到最新」，不编条数。
     */
    unreadOnEntry: Int = 0,
    initialStagedFiles: List<LocalFile> = emptyList(),
    onStagedFilesChange: ((String, List<LocalFile>) -> Unit)? = null,
    /** Phase 4 订阅面切片：默认值由 [state] 投影，预览/旧路径行为不变。 */
    chatSlice: ChatSlice,
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    avatarSlice: AvatarSlice,
    chromeSlice: ChromeSlice,
) {
    // 「中断 agent run」走控制面（§6.6 agent-cancel），需要协程作用域；
    // 与手机壳（App.kt 里 chatScope.launch）同一条纪律。
    val cancelScope = rememberCoroutineScope()
    // 群没有 PeerUi（协议里群不是节点），所以群会话要用占位对端。
    // 与手机端 `ChatScreen` 同一条路径：`chatPeer` 靠历史兜底，`groupPeer` 靠成员表。
    val group = groupOfConv(settingsSlice, selectedPeerId)
    // **群优先**：`chatPeer` 对任何有历史的会话都回一个只有 nodeId 的占位，
    // 群会话键是 `group:<groupId>`，先问它就会把「group:g-」画到头上。
    // 手机端 `ChatScreen` 是同一条路径、同一个理由。
    val peer = groupPeer(group, nodeSlice, settingsSlice) ?: chatPeer(chatSlice, nodeSlice, settingsSlice, selectedPeerId)
    val chat: @Composable (Modifier) -> Unit = { chatModifier ->
        ChatScreen(
            data = ChatScreenState(
                chatSlice = chatSlice,
                agentSlice = agentSlice,
                nodeSlice = nodeSlice,
                settingsSlice = settingsSlice,
                chromeSlice = chromeSlice,
                peerId = selectedPeerId,
                wsId = selectedWsId,
                activeSessionId = activeSessionId,
                unreadOnEntry = unreadOnEntry,
                cacheDir = null,
                initialStagedFiles = initialStagedFiles,
                messageFlow = selectedPeerId?.let { id -> remember(id) { controller.messageFlowOf(id) } },
            ),
            actions = ChatScreenActions(
                onSend = controller::sendToConversation,
                // 胶囊选中的意图随正文一帧发出（方案 §4.2），与手机壳同一条域层入口。
                onSendWithIntent = { targetId, text, replyTo, intent ->
                    controller.sendToConversation(targetId, text, replyTo, intent)
                },
                onOpenAgentConsole = { sid ->
                    if (sid.isNotBlank()) onSelectAgentSession?.invoke(sid)
                },
                onSelectAgentSession = onSelectAgentSession,
                onDismissBanner = controller::dismissBanner,
                onAttachFile = controller::pickAndSendFiles,
                onPickFiles = controller::pickFiles,
                onSendFiles = controller::sendFiles,
                onAcceptTransfer = controller::acceptTransfer,
                onDeclineTransfer = controller::declineTransfer,
                // 与手机端同一个入口：按原 id 重放，不新发一条。
                onRetryMessage = controller::retryMessage,
                // 卡片动作真通路（M4 任务 5）：与手机壳同一个域层入口（两壳同源）。
                // 参数接原语；本机乐观置灰已在 ChatScreen 里先行记账。
                onCardAction = { convId, card, actionId ->
                    // **1:1 直派的卡没有群**：`groupIdOf` 对单聊会话返回 null，`?.let` 会把点击
                    // 整块吞掉 —— 按钮已被乐观置成「已提交，等待宿主确认」，宿主却零入站帧
                    // （真机 A22 现场：手机点「合并此补丁」，合并永远不发生且没有一行日志）。
                    // 域层两种会话都查，这里把会话 id 原样传下去即可（群会话仍是纯 groupId）。
                    controller.handleCardAction(ConvId.groupIdOf(convId) ?: convId, card.cardId, card.rev, actionId)
                },
                // 与手机端同一条路：从「@ 了但没执行」那一行直接申请授权。
                onRequestAgentGrant = { agentName ->
                    val conv = selectedPeerId
                    val groupId = conv?.let { ConvId.groupIdOf(it) }
                    if (groupId != null) controller.requestAgentGrantFromNotice(groupId, agentName)
                },
                // 向上翻页：滚到历史顶端时读更早的一页（与手机端同一条路径）。
                onLoadOlder = {
                    selectedPeerId?.let { controller.loadOlderMessages(it) }
                },
                // 撤回自己发出的、还在传的文件。以前这条没接：桌面上文件卡片根本没有取消按钮，
                // 点了「发送」就只能等它自己失败（审计 P1-13）。移动端那条路一直有。
                onCancelTransfer = controller::cancelTransfer,
                onResumeTransfer = controller::resumeTransfer,
                onSearchInConversation = { query, filter ->
                    selectedPeerId?.let { convId -> controller.searchInConversation(convId, query, filter) } ?: emptyList()
                },
                onSendPk = controller::sendPk,
                // 撤回与戳一碰：两条都是协议动作，手机与桌面共用同一个聊天页，所以一起接上。
                onRecallMessage = controller::recall,
                // 与手机壳同一个入口：只删本机，不动协议。
                onDeleteMessage = controller::deleteMessage,
                onForwardMessage = controller::forwardMessage,
                // 群与 Agent 的判据在 ChatScreen 里面，这里不必再判一次。
                onNudge = controller::nudge,
                onRequestScreenView = controller::requestScreenView,
                onRespondApproval = controller::respondApproval,
                // 群里那条「中断」：控制面动作，不发聊天文本（与手机端同一个控制器方法）。
                onCancelAgentRun = { peerId, sessionId, runId ->
                    cancelScope.launch {
                        controller.cancelAgentRun(peerId, sessionId, runId, reason = "user")
                    }
                },
                onOpenAgentHistory = { peerId, wsId ->
                    controller.requestAgentSessionPage(peerId, wsId, offset = 0)
                },
                onLoadMoreAgentSessions = { peerId, wsId ->
                    val known = agentSessionsOf(agentSlice, peerId).count { it.wsId == wsId }
                    controller.requestAgentSessionPage(peerId, wsId, offset = known.toLong())
                },
            ),
            groupActions = ChatScreenGroupActions(
                onAddGroupMembers = controller::addGroupMembers,
                onRemoveGroupMember = controller::removeGroupMember,
                onUpdateGroupMeta = controller::updateGroupMeta,
                // 团队档案（住本机设置）：「添加团队」把整支队一次加进本群。
                onAttachTeam = { groupId, teamId -> controller.attachTeamToGroup(teamId, groupId) },
                onLeaveGroup = { groupId ->
                    controller.leaveGroup(groupId)
                    onBack?.invoke()
                },
            ),
            chrome = ChatScreenChrome(
                receivedFileActions = controller.receivedFileActions,
                onBack = onBack,
                // 收到文件的开关/分享入口。null = 本平台没有这条出口（桌面、iOS），
                // 聊天页据此不画按钮；Android 传进来的是真实现。
                showHeader = false,
                // 桌面壳自己画右栏头（见 DesktopChatHeader）：手机端那个头的按钮是
                // 「返回 / 会话列表」，桌面上要的是「列表 / 复制节点 ID / 在新窗口打开」，
                // 语义不同，不能靠 showHeader = true 复用。
                //
                // 气泡比例按**外壳**定，不按窗口宽度算（推导见 feature/chat/BubbleWidth.kt）：
                // 这条路服务桌面壳，平板两栏也走同一条（App.kt 的 twoPane → DesktopShell →
                // 这里），而原型里 tabhost 那档同样是 form:'desktop' → 62%。所以一档到底，不必再分。
                bubbleFraction = ContentWidths.BUBBLE_FRACTION_WIDE,
                modifier = chatModifier,
                initialDraft = initialDraft,
                onDraftChange = onDraftChange,
                onStagedFilesChange = onStagedFilesChange,
                composerSlot = if (peer == null) {
                    null
                } else {
                    { args ->
                        // slot 的参数是一整包（ComposerSlotArgs）：回复条、@ 候选、
                        // 新任务 token、命中跳转与手机内置输入栏同源 —— 以前散参版本
                        // 漏接了 replyingTo，桌面点「回复」就没反应。
                        DesktopComposer(
                            peer = peer,
                            draft = args.draft,
                            onDraftChange = args.onDraftChange,
                            controller = controller,
                            isGroup = group != null,
                            mentionTargets = args.mentionTargets,
                            replyingTo = args.replyingTo,
                            replySenderName = args.replySenderName,
                            onCancelReply = args.onCancelReply,
                            newTaskToken = args.newTaskToken,
                            onJumpToMessage = args.onJumpToMessage,
                            // 发送走 ChatScreen 的出口（与手机内置输入栏同一个）：
                            // 「回复」的 replyId 才带得到发送侧，发完引用条才会消失。
                            onSend = args.onSend,
                            // 焦点意图：ChatScreen 那份 composerFocus 在桌面没有挂载点，
                            // 由本卡片自己的 requester 消费（回复 / @ 之后光标才回到输入框）。
                            focusRequest = args.focusRequest,
                            // 被移出群时与手机同一句话（历史只读）。
                            readOnlyReason = args.readOnlyReason,
                            stagedFiles = args.stagedFiles,
                            onRemoveStagedFile = args.onRemoveStagedFile,
                            onAttachFiles = args.onAttachFiles,
                        )
                    }
                },
            ),
        )
    }
    if (peer == null) {
        // 一个会话都没选中（原型 `screenDNoSel`，`relay.html:5145-5151`）：桌面自己画居中的那张
        // （见 [DesktopNoSelectionEmpty] 的 KDoc：共享 `EmptyState` 是顶对齐的，手机端继续用它）。
        // 选中了会话却查不到对端（只剩历史）时仍走 `ChatScreen` 那条路，行为不变。
        if (selectedPeerId == null) DesktopNoSelectionEmpty(modifier) else chat(modifier)
        return
    }
    var showGroupInfo by remember(selectedPeerId) { mutableStateOf(false) }
    // 「清空本会话记录」的确认框开合。桌面头部（DesktopChatHeader）画在这一层之下，
    // 所以触发与确认框都由本层持有；手机那条壳的同类确认框在 ChatScreen 里。
    var showClearConfirm by remember(selectedPeerId) { mutableStateOf(false) }
    var showCloseAndClearConfirm by remember(selectedPeerId) { mutableStateOf(false) }
    Column(modifier) {
        DesktopChatHeader(
            peer = peer,
            compact = sizeClass == DesktopWindowSizeClass.Compact,
            now = now,
            // 「列表」只有 Compact 用得上：宽窗下侧栏一直可见，原型也不给收起入口。
            onList = onBack,
            // 复制节点 ID 只对"是一个节点"的会话有意义：群不是节点，它的占位
            // `nodeId` 是会话键 `group:<groupId>`，复制出去对方拿去填地址只会失败。
            onCopyId = if (group == null) ({ onCopyPeerId(peer.nodeId) }) else null,
            onOpenInNewWindow = onOpenInNewWindow?.let { open -> { open(peer.nodeId) } },
            onRequestScreenView = controller::requestScreenView
                .takeIf { group == null && (!peer.isAgent || MsgTypes.CAP_REMOTE_SHARE in peer.caps) && peer.online }
                ?.let { req -> { req(peer.nodeId) } },
            avatarBytes = peer.avatarHash?.let { avatarSlice.avatarCache[it] },
            // 「戳一碰」不在这里传：按原型它落在工具行（`DesktopComposer` 的 `ToolIcon`），
            // 判据仍是「只给单聊、且不是 Agent」（判断写在 `DesktopComposer` 里，
            // 与这一行的 `onRequestScreenView` 同源）。
            group = group,
            memberOnlineCount = group?.let { memberOnlineCount(it, nodeSlice, settingsSlice) } ?: 0,
            // K 个智能体就绪 = 群成员 ∩ 已知 agent 对端 ∩ 在线（头部只画，判定在 state）。
            agentReadyCount = group?.members?.count { memberId ->
                memberId != settingsSlice.settings.nodeId &&
                    peer(memberId, nodeSlice, settingsSlice)?.isAgent == true &&
                    peer(memberId, nodeSlice, settingsSlice)?.online == true
            } ?: 0,
            // 群主名：**本机群主先比对 `selfNodeId`，不能只查节点表** ——
            // `nodeSlice.peers` 是远端节点表，本机不在里面，所以自己建的群在这里会静默丢掉
            // 整段「群主 X」（生产行为，不只是截图夹具）。回落链与手机端
            // `GroupInfoDialog` 对齐：本机用自己设的昵称（取不到再退回
            // 「我」），远端查不到就退 `nodeId` 前 8 字符，而不是整段消失。
            groupOwnerName = group?.ownerId?.let { owner ->
                when (owner) {
                    settingsSlice.settings.nodeId -> settingsSlice.settings.nick.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.chat_group_me)
                    else -> nodeSlice.peers.firstOrNull { it.nodeId == owner }?.displayName
                        // 空昵称要当"查不到"处理：否则会渲染出「群主 」这样半截的标签
                        // （远端刚上线、资料还没同步过来时 displayName 可能是空串）。
                        ?.takeIf { it.isNotBlank() }
                        ?: peer(owner, nodeSlice, settingsSlice)?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.common_profile_unavailable)
                }
            },
            onViewGroupInfo = if (group != null) ({ showGroupInfo = true }) else null,
            // 「清空本会话记录」：会话键直接用 selectedPeerId（私聊=对方 nodeId、
            // 群=group:<groupId>）—— 与 ChatScreenActions 里别的回调同一口径。
            // 与手机端共用同一个确认框（见 ClearConversationDialog）。
            onClearMessages = { showClearConfirm = true },
            onCloseAndClearConversation = { showCloseAndClearConfirm = true },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        chat(Modifier.weight(1f).fillMaxWidth())
        if (showGroupInfo && group != null) {
            GroupInfoDialog(
                group = group,
                selfNodeId = settingsSlice.settings.nodeId,
                selfNick = settingsSlice.settings.nick,
                peers = nodeSlice.peers,
                memberOnlineCount = memberOnlineCount(group, nodeSlice, settingsSlice),
                onDismiss = { showGroupInfo = false },
                onAddMembers = { members -> controller.addGroupMembers(group.groupId, members) },
                onRemoveMember = { member -> controller.removeGroupMember(group.groupId, member) },
                onUpdateMeta = controller::updateGroupMeta,
                onLeaveGroup = { controller.leaveGroup(group.groupId); onBack?.invoke() },
                onDissolveGroup = { controller.dissolveGroup(group.groupId); onBack?.invoke() },
                // **必须传身份上下文**：不传的话 `GroupInfoDialog` 会现建一份
                // `(selfNodeId, peers, group)` —— 里面没有 `agentKindsByHost`，于是
                // `groupParticipantRows` 找不到任何 agent，整个「入驻智能体」段落消失。
                // 真机反馈：同一个群，手机端能看到 agent、桌面端一个都看不到。
                identityContext = senderContext(ConvId.group(group.groupId), chatSlice, agentSlice, nodeSlice, settingsSlice),
                // 授权面板动作通路（入驻 / 去暴露 / 移除席位 / 设目录指针）。
                localWorkspaces = agentSlice.agentWorkspaces.values.filter { it.peerId == settingsSlice.settings.nodeId },
                onStationAgent = { agentId, duty -> controller.addGroupAgent(group.groupId, agentId, duty) },
                onReparseSeats = { controller.rebuildGroupSeatProjections(group.groupId) },
                onRemoveGroupAgent = { agentId -> controller.removeGroupAgent(group.groupId, agentId) },
                onSetProjectDirectory = { dirId -> controller.setGroupProjectDirectory(group.groupId, dirId) },
                hostDirectoryPort = controller.hostDirectoryPort,
                // 非宿主目录行的声明投影（路径不上屏；宿主行读本机指针，用不到它）。
                declaredDirectoryTitle = com.kapp.tenon.domain.workspaceOfGroup(
                    group.groupId, agentSlice, settingsSlice,
                )?.wsTitle,
                // 团队编制（§4.3）：对话框收 (agentId, roster)，这里补群 id。桌面不特殊化。
                onSetTeamRoster = { agentId, roster ->
                    controller.setGroupTeamDeclaration(group.groupId, agentId, roster)
                },
                teamExecutionPort = controller.teamExecutionPort,
                // 团队档案（住本机设置）：面板里「添加团队」段的候选与写通路。
                teamProfiles = settingsSlice.settings.teamProfiles,
                onAttachTeam = { teamId -> controller.attachTeamToGroup(teamId, group.groupId) },
            )
        }
        if (showClearConfirm) {
            ClearConversationDialog(
                conversationName = peer.displayName,
                onDismiss = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    // 只清本机：走既有 RelayController.clearMessages（不是 clearAllMessages）。
                    // selectedPeerId 可空，这里已在"有会话"的分支里（peer 非空），再 let 一次即可。
                    selectedPeerId?.let { controller.clearMessages(it) }
                },
            )
        }
        if (showCloseAndClearConfirm) {
            CloseAndClearConversationDialog(
                conversationName = peer.displayName,
                onDismiss = { showCloseAndClearConfirm = false },
                onConfirm = {
                    showCloseAndClearConfirm = false
                    selectedPeerId?.let {
                        controller.deleteConversation(it)
                        onBack?.invoke()
                    }
                },
            )
        }
    }
}

/**
 * 会话详情头（原型稿的 `.dhead`）。
 *
 * 这一行是原型里右栏最显眼的元素：头像 + 名字 + 平台/团队小标签 + 第二行的
 * 「地址 · 连接状态 · 最后在线」，右侧三个动作。此前桌面壳把手机端 `ChatScreen`
 * 以 `showHeader = false` 复用，等于整行都不存在。
 *
 * 两处**有意偏离原型稿**，都是为了不说假话：
 * - 原型按钮写「复制指纹」，这里写「复制节点 ID」。`:core` 没有公钥概念，
 *   `agentNode.fingerprintOf` 是资料指纹（推进 profileRev 用），不是加密身份指纹。
 * - 原型第二行写「已连接 2 小时 14 分」，这里不写连接时长：`PeerUi` 没有记录
 *   建连时刻，编一个数字就是假数据；能诚实给出的是 `lastSeenAt`。
 */
@Composable
public fun DesktopChatHeader(
    peer: PeerUi,
    compact: Boolean,
    now: Long,
    onList: (() -> Unit)?,
    /**
     * 复制节点 ID。null = 不画这个入口。
     *
     * 群会话传 null：群不是节点，它的占位 `nodeId` 是会话键 `group:<groupId>`，
     * 复制出去对方拿去「填地址」只会失败。与「戳一碰」同一个判据、同一种做法。
     */
    onCopyId: (() -> Unit)? = null,
    /**
     * 在新窗口打开当前会话。null = 本设备没有多窗口能力（手机/平板），整个入口不画；
     * 留一个永远灰着的按钮等于告诉用户「这里有个功能，只是你点不了」。
     */
    onOpenInNewWindow: (() -> Unit)? = null,
    onRequestScreenView: (() -> Unit)? = null,
    /**
     * 截图接缝：让「请求查看屏幕」确认表单在首帧就是**打开**的。
     *
     * 与 `AddNodeSheet.initialScanMode`（feature/nodes/NodeSheets.kt 的 `AddNodeSheet.initialScanMode`）同一类参数 ——
     * 确认表单只在用户点那颗图标按钮之后才出现，没有这个初值，那个分支就永远没有帧盯着，
     * 截图门禁对它完全变瞎。默认 false = 生产行为（关的）。
     */
    initialConfirmOpen: Boolean = false,
    avatarBytes: ByteArray? = null,
    /**
     * 群会话的成员表；null = 这是一个私聊。
     *
     * 有它的时候这一行头的画法与私聊**不同**：不画在线圆点、不画能力徽标、
     * 不画戳一碰（群没有"这一个对端在不在"这回事），第二行改成成员数。
     * 判据是"有没有成员表"，不是"peer.isAgent 是不是假" —— 群的对端是个占位。
     */
    group: GroupMeta? = null,
    /** 群成员里当前在线的人数（不含自己）。只在 [group] 非空时有意义。 */
    memberOnlineCount: Int = 0,
    /** 群主的显示名（`group.ownerId` 在节点表里的 `displayName`）。取不到时第二行只省掉「群主 X」那一段。 */
    groupOwnerName: String? = null,
    /**
     * 群成员里**在线的智能体**数（T-1c 头部信息价值：「K 个智能体就绪」）。
     * 只在 [group] 非空时有意义；0 时 badge 不带这一段 —— 不说「0 个就绪」的空话。
     * 由调用方从 state 算（agentWorkspaces × members × online），头部只画。
     */
    agentReadyCount: Int = 0,
    onViewGroupInfo: (() -> Unit)? = null,
    /**
     * 「清空本会话聊天记录」。null = 不画这个入口。
     *
     * 与手机端 ChatHeader 的同名动作同一语义：确认框由**调用方**持有
     * （这里触发、[ChatPane] 画 [ClearConversationDialog]），这样"头部负责表达意图、
     * 壳负责确认与执行"在两条壳上是同一条规矩。
     */
    onClearMessages: (() -> Unit)? = null,
    onCloseAndClearConversation: (() -> Unit)? = null,
) {
    // 「请求查看屏幕」的确认表单：以前点那颗图标按钮**直接发请求**，原型 d30-reqscreen
    // 在中间有一张确认卡（说明 + 取消 / 发送请求）。改成先开表单、确认后才发。
    var screenRequestConfirmOpen by remember { mutableStateOf(initialConfirmOpen) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            // 窄栏里（右栏只剩 Medium 的 ~456dp，或 Compact 单栏）平台/团队标签
            // 会把名字挤成一个「…」——非权重子节点先测量，先把宽度吃光。窄的时候
            // 整枚撤掉团队标签，地址行也允许折到第二行。
            // Compact 是单栏布局：560dp 窗口去掉左右留白后这一行仍能测到 ~560dp，
            // 只看 maxWidth 会漏判，所以把 compact 本身也算窄。
            val narrow = compact || maxWidth < 420.dp
            // 带文字的两个动作（「复制节点 ID」「在新窗口打开」）实测要吃掉 ~200dp：
            // Medium 右栏只剩 ~456dp、平板竖屏 ~496dp 时，它们会把名字和地址挤成
            // 「…」。低于 560dp 或单栏 Compact 就只留图标，Expanded 仍保持文字。
            val iconOnlyActions = compact || maxWidth < 560.dp
            Row(
                Modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md10),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
            ) {
                // 群头第二行。rev 与群主都是协议事实（GroupMeta.rev / ownerId），不是编出来的
                // 进度数字；群主名取不到时（对方不在节点表里）那一段整段省掉。
                val groupLine = group?.let { meta ->
                    val owner = groupOwnerName?.takeIf { it.isNotBlank() }
                    val ownerLabel = stringResource(Res.string.chat_group_owner_label)
                    listOfNotNull(
                        stringResource(Res.string.chat_group_members, meta.members.size),
                        stringResource(Res.string.chat_group_rev, meta.rev),
                        owner?.let { ownerLabel + " " + it },
                    ).joinToString(" · ")
                }
                // T-1c 头部信息价值（私聊）：在场行（在线 · 最后在线）之外，把能力 token
                //（peer.caps，如 ag1/tw1）接在行尾 —— 这台机器能干什么在头部可读，不用下钻详情。
                val privateLine = peerPresenceLine(peer, now).let { base ->
                    if (peer.caps.isNotEmpty()) {
                        base + " · " + stringResource(Res.string.chat_caps_section) + " " + peer.caps.joinToString(" ")
                    } else {
                        base
                    }
                }
                ChatHeaderContent(
                    title = peer.displayName,
                    isAgent = peer.isAgent && group == null,
                    isGroup = group != null,
                    avatarOnline = if (group == null) peer.online else null,
                    secondaryText = if (groupLine == null) privateLine else groupLine,
                    density = ChatHeaderDensity.Desktop,
                    narrow = narrow,
                    avatar = peer.avatar,
                    avatarBytes = avatarBytes,
                    modifier = Modifier.weight(1f),
                    titleTrailing = {
                        if (group == null) {
                            // 群的占位对端没有平台/团队，也不该带能力徽标。
                            if (peer.platform.isNotBlank()) HeadChip(peer.platform)
                            val teams = listOf(peer.company, peer.dept, peer.team)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                            if (!narrow && teams.isNotBlank()) HeadChip(teams)
                            KindBadge(peer.isAgent)
                        } else {
                            if (!narrow) {
                                val badgeText = buildString {
                                    append(stringResource(Res.string.chat_group_online, memberOnlineCount, group.members.size))
                                    if (agentReadyCount > 0) {
                                        append(" · ")
                                        append(stringResource(Res.string.chat_group_agents_ready, agentReadyCount))
                                    }
                                }
                                HeadChip(badgeText)
                            }
                        }
                    },
                )
                // T-1c 头部动作收拢（原型 `dhead` max=1，`prototype/relay.html:6255-6261`
                // 的注释逐字「直出最多保留 1 颗核心操作」）：群直出「成员」、私聊直出
                // 「复制节点 ID」，其余全部收进溢出菜单；破坏性动作（清空 / 关闭并删除）
                // 排菜单末尾。动作清单是**一份模型**（label + 图标 + onClick + danger），
                // 直出的文字按钮与菜单里的一行由它分别渲染 —— 挪动只改清单顺序，
                // 不会出现"两处各写一份"（与手机 ChatHeader 的动作模型同一条纪律）。
                data class HeaderAction(
                    val label: String,
                    val icon: org.jetbrains.compose.resources.DrawableResource,
                    val onClick: () -> Unit,
                    val danger: Boolean = false,
                )
                val directAction: HeaderAction? = when {
                    group != null && onViewGroupInfo != null -> HeaderAction(
                        label = stringResource(Res.string.chat_group_members_action),
                        icon = Res.drawable.ic_groups,
                        onClick = onViewGroupInfo,
                    )
                    group == null && onCopyId != null -> HeaderAction(
                        label = stringResource(Res.string.desktop_action_copy_node_id_short),
                        icon = Res.drawable.ic_copy,
                        onClick = onCopyId,
                    )
                    else -> null
                }
                val overflowActions = listOfNotNull(
                    onOpenInNewWindow?.let { open ->
                        HeaderAction(stringResource(Res.string.desktop_action_open_in_new_window), Res.drawable.ic_external, open)
                    },
                    onRequestScreenView?.let { reqScreen ->
                        HeaderAction(
                            label = stringResource(Res.string.screen_remote_view_action),
                            icon = Res.drawable.ic_screen_share,
                            // 确认表单仍由头部持有（d30-reqscreen 的「先开表单再发」）。
                            onClick = { screenRequestConfirmOpen = true },
                        )
                    },
                    onClearMessages?.let { clear ->
                        HeaderAction(stringResource(Res.string.chat_clear_local_action), Res.drawable.ic_trash, clear, danger = true)
                    },
                    onCloseAndClearConversation?.let { closeAndClear ->
                        HeaderAction(stringResource(Res.string.chat_close_and_clear_action), Res.drawable.ic_close, closeAndClear, danger = true)
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    // 「列表」只在 Compact 出现（原型 `.onlyc`），且**排最后** ——
                    // 原型 dhead 的注释：直出上限 1，列表排最后才不会占掉直出格。
                    if (compact && onList != null) {
                        if (iconOnlyActions) {
                            TopBarIconAction(Res.drawable.ic_list, stringResource(Res.string.desktop_action_list), onList)
                        } else {
                            ToolbarButton(stringResource(Res.string.desktop_action_list), painterResource(Res.drawable.ic_list), onList)
                        }
                    }
                    directAction?.let { action ->
                        ToolbarButton(action.label, painterResource(action.icon), action.onClick)
                    }
                    if (overflowActions.isNotEmpty()) {
                        com.kapp.tenon.ui.components.TopBarOverflowAction(
                            items = overflowActions.map { action ->
                                com.kapp.tenon.ui.components.TopBarOverflowItem(
                                    label = action.label,
                                    onClick = action.onClick,
                                )
                            },
                            contentDescription = stringResource(Res.string.desktop_more_actions),
                        )
                    }
                    // 「请求查看屏幕」的确认框贴着它的触发按钮（菜单项触发的开合也在这一层画）。
                    if (screenRequestConfirmOpen) {
                        ScreenRequestConfirmDialog(
                            onCancel = { screenRequestConfirmOpen = false },
                            onSend = {
                                screenRequestConfirmOpen = false
                                onRequestScreenView?.invoke()
                            },
                        )
                    }
                }
            }
        }
    }
}

// 窄栏头部动作已统一到共享件 com.kapp.tenon.ui.components.TopBarIconAction（原型 `.ibtn`）。
// 原来这里是两颗本地 HeadIconAction：34dp 的裸 IconButton、无底色、tint = onSurfaceVariant。
// 原型 `.dhead .acts` 经 actsHTML → actInline 渲染的是 `<button class="ibtn">` 方块
// （prototype/relay.html:5767 与 :2826），即 34×34、`--surface-2` 底、`--on` 图标。
