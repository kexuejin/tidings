// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.ChatSlice
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.SenderIdentityContext
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.platform.ReceivedFileActions
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kapp.tenon.domain.MessageSearchResult
import com.kapp.tenon.domain.SenderIdentity
import com.kapp.tenon.domain.sessionPageKeyOf
import com.kapp.tenon.domain.conversations
import com.kapp.tenon.domain.memberOnlineCount
import com.kapp.tenon.domain.peer
import com.kapp.tenon.feature.agents.AgentSessionPickerDialog
import com.kapp.tenon.ui.components.PkPickerDialog
import com.kapp.tenon.ui.components.VoiceRecordDialog
import com.kapp.tenon.ui.components.ForwardTargetDialog
import com.kapp.tenon.ui.components.GroupInfoDialog
import com.kapp.tenon.ui.components.GroupInfoPage
import com.kapp.tenon.ui.components.ImageViewerDialog
import com.kapp.tenon.domain.model.MessageUi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 浮层宿主（ARP-14 后半 · 对话框群归位）。
 *
 * 这十一个浮层是 ChatScreen 尾部一整块「Column 之外的浮层区」——互相不嵌套、
 * 只靠 show* 布尔位与 forwardingMessage/previewingImagePath/capsuleState 三个
 * 值状态进出。整块搬出后 ChatScreen 主体回到「内容区」本身。
 *
 * 形态：show* 以 MutableState 下传、宿主内同名本地委托接住 —— 块体（含全部
 * 赋值语句）逐字未动；remember 键留在 ChatScreen 侧的声明处，逐字未动。
 *
 * 唯一行为差异（无）：`conversations(...)` 的取会话列表原来是浮层要渲染时才算，
 * 现在也是 —— 仍在 `ForwardTargetDialog` 的条件块内，没有提前求值。
 */
@Composable
internal fun ChatDialogsHost(
    peer: PeerUi,
    group: GroupMeta?,
    now: Long,
    cacheDir: String?,
    memberOnlineCount: Int,
    effectiveWsId: String?,
    historyTargetNodeId: String,
    agentSessions: List<AgentSessionUi>,
    archivedSessions: List<AgentSessionUi>,
    activeSession: AgentSessionUi?,
    chatSlice: ChatSlice,
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    identityCtx: SenderIdentityContext,
    actions: ChatScreenActions,
    groupActions: ChatScreenGroupActions,
    /** 团队执行档端口（手机端为 null ⇒ 只读投影；§6.3）。 */
    teamExecutionPort: com.kapp.tenon.domain.TeamExecutionPort?,
    receivedFileActions: ReceivedFileActions?,
    scrollToMessage: (String) -> Unit,
    onMentionSender: (SenderIdentity) -> Unit,
    showGroupInfoState: MutableState<Boolean>,
    showVoiceRecordState: MutableState<Boolean>,
    forwardingMessageState: MutableState<MessageUi?>,
    showPkPickerState: MutableState<Boolean>,
    previewingImagePathState: MutableState<String?>,
    showClearConfirmState: MutableState<Boolean>,
    showCloseAndClearConfirmState: MutableState<Boolean>,
    showHistorySearchState: MutableState<Boolean>,
    showTaskPickerState: MutableState<Boolean>,
    showSessionPickerState: MutableState<Boolean>,
    capsuleStateState: MutableState<CapsuleState>,
    /** 群信息用整屏页面（手机）还是弹窗（桌面）—— 外壳决定，见 `ChatScreenChrome` 同名参数。 */
    groupInfoAsPage: Boolean,
) {
    // ChatScreen 顶部的本地解包在早退（peer==null）之后才发生，宿主拿到的一律非空；
    // 这些同名别名让块体逐字保留。
    val mentionSender = onMentionSender
    val onSendVoice = actions.onSendVoice
    val onForwardMessage = actions.onForwardMessage
    val onSendPk = actions.onSendPk
    val onClearMessages = actions.onClearMessages
    val onCloseAndClearConversation = actions.onCloseAndClearConversation
    val onSearchInConversation = actions.onSearchInConversation
    var showGroupInfo by showGroupInfoState
    var showVoiceRecord by showVoiceRecordState
    var forwardingMessage by forwardingMessageState
    var showPkPicker by showPkPickerState
    var previewingImagePath by previewingImagePathState
    var showClearConfirm by showClearConfirmState
    var showCloseAndClearConfirm by showCloseAndClearConfirmState
    var showHistorySearch by showHistorySearchState
    var showTaskPicker by showTaskPickerState
    var showSessionPicker by showSessionPickerState
    var capsuleState by capsuleStateState
    if (showGroupInfo && group != null) {
        // 两种外壳、一份内容（GroupInfoBody 在组件文件内部）：手机整屏页 / 桌面居中框。
        // 实参先算成局部量，两壳只挑形态不各抄一遍。
        //
        // 成员面板用**和气泡 / @ 候选同一份**上下文：agent 显示 DSH、@ 候选给出 @DSH。
        val addMembers = groupActions.onAddGroupMembers?.let { add -> { members: List<String> -> add(group.groupId, members) } }
        val removeMember = groupActions.onRemoveGroupMember?.let { remove -> { member: String -> remove(group.groupId, member) } }
        val leaveGroup = groupActions.onLeaveGroup?.let { leave -> { leave(group.groupId) } }
        val dissolveGroup = groupActions.onDissolveGroup?.let { dissolve -> { dissolve(group.groupId) } }
        // 长按成员头像 = 直接 @：插入到编辑器后关掉面板（用户下一步是打字）。
        val mentionFromPanel: (SenderIdentity) -> Unit = { identity ->
            showGroupInfo = false
            mentionSender(identity)
        }
        // 授权管理面板的动作通路：入驻 / 去暴露 / 移除席位 / 设目录指针，全部补群 id。
        val stationAgent = groupActions.onStationAgent?.let { station ->
            { agentId: String, duty: String? -> station(group.groupId, agentId, duty) }
        }
        val reparseSeats = groupActions.onReparseSeats?.let { reparse ->
            { reparse(group.groupId) }
        }
        val removeGroupAgent = groupActions.onRemoveGroupAgent?.let { remove ->
            { agentId: String -> remove(group.groupId, agentId) }
        }
        val setProjectDirectory = groupActions.onSetProjectDirectory?.let { setDir ->
            { dirId: String? -> setDir(group.groupId, dirId) }
        }
        // 非宿主的目录行显示值：席位声明投影（workspaceOfGroup 的 wsTitle；路径不上屏）。
        val declaredDirectoryTitle = com.kapp.tenon.domain.workspaceOfGroup(
            group.groupId, agentSlice, settingsSlice,
        )?.wsTitle
        // 团队编制的保存通路（§4.3）：对话框收的是 (agentId, roster)，这里补群 id。
        // 手机壳画宿主注解（§2.2 双端形态契约）：执行驱动在桌面宿主上托管。
        val setTeamRoster = groupActions.onSetTeamRoster?.let { save ->
            { agentId: String, roster: List<TeamMemberDraft>? -> save(group.groupId, agentId, roster) }
        }
        val localWs = agentSlice.agentWorkspaces.values.filter { it.peerId == settingsSlice.settings.nodeId }
        if (groupInfoAsPage) {
            GroupInfoPage(
                group = group,
                selfNodeId = settingsSlice.settings.nodeId,
                selfNick = settingsSlice.settings.nick,
                peers = nodeSlice.peers,
                memberOnlineCount = memberOnlineCount,
                onDismiss = { showGroupInfo = false },
                onAddMembers = addMembers,
                onRemoveMember = removeMember,
                onUpdateMeta = groupActions.onUpdateGroupMeta,
                onLeaveGroup = leaveGroup,
                onDissolveGroup = dissolveGroup,
                identityContext = identityCtx,
                onMentionMember = mentionFromPanel,
                localWorkspaces = localWs,
                onStationAgent = stationAgent,
                onReparseSeats = reparseSeats,
                onRemoveGroupAgent = removeGroupAgent,
                onSetProjectDirectory = setProjectDirectory,
                hostDirectoryPort = groupActions.hostDirectoryPort,
                declaredDirectoryTitle = declaredDirectoryTitle,
                onSetTeamRoster = setTeamRoster,
                showTeamHostManagedNotice = true,
            // 手机壳默认无执行面（DesktopExtension 只在桌面装配端口）⇒ 只读投影。
            teamExecutionPort = groupActions.teamExecutionPort,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GroupInfoDialog(
                group = group,
                selfNodeId = settingsSlice.settings.nodeId,
                selfNick = settingsSlice.settings.nick,
                peers = nodeSlice.peers,
                memberOnlineCount = memberOnlineCount,
                onDismiss = { showGroupInfo = false },
                onAddMembers = addMembers,
                onRemoveMember = removeMember,
                onUpdateMeta = groupActions.onUpdateGroupMeta,
                onLeaveGroup = leaveGroup,
                onDissolveGroup = dissolveGroup,
                identityContext = identityCtx,
                onMentionMember = mentionFromPanel,
                localWorkspaces = localWs,
                onStationAgent = stationAgent,
                onReparseSeats = reparseSeats,
                onRemoveGroupAgent = removeGroupAgent,
                onSetProjectDirectory = setProjectDirectory,
                hostDirectoryPort = groupActions.hostDirectoryPort,
                declaredDirectoryTitle = declaredDirectoryTitle,
                onSetTeamRoster = setTeamRoster,
                showTeamHostManagedNotice = true,
                teamExecutionPort = groupActions.teamExecutionPort,
            )
        }
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
            sessions = conversations(chatSlice, agentSlice, nodeSlice, settingsSlice),
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
            peerNames = nodeSlice.peers.associate { it.nodeId to it.displayName },
            now = now,
        )
    }

    // 「最近任务」：胶囊绑定的那个 agent 宿主名下的可见会话（活动倒序，取前几条）。
    // 与会话切换条同一份来源（agentSlice.agentSessions）+ 同一套可见性两键（子代理/空壳剔除）。
    // 这一段在 Column 之外（浮层区），所以重新读一次 capsuleState —— 它在 Column 内
    // 取的别名（boundCapsule）到不了这里。
    val pickedCapsuleAgent = capsuleState.agent
    val capsuleTasks = remember(agentSlice.agentSessions, pickedCapsuleAgent) {
        val host = pickedCapsuleAgent?.hostNodeId
        if (host == null) {
            emptyList()
        } else {
            agentSlice.agentSessions.values
                .filter {
                    it.peerId == host && !it.isSubagent && !it.isArchived &&
                        it.title?.isNotBlank() == true
                }
                .sortedByDescending { it.sortActivityAt }
                .distinctBy { it.sessionId }
                .take(CAPSULE_RECENT_TASK_LIMIT)
                .map { CapsuleTaskOption(it.sessionId, it.title.orEmpty(), it.wsId) }
        }
    }
    if (showTaskPicker && pickedCapsuleAgent != null) {
        AgentTaskPickerSheet(
            agent = pickedCapsuleAgent,
            selection = capsuleState.selection,
            recentTasks = capsuleTasks,
            // 点选即刻生效（零锁死）：状态直接落，然后收 sheet —— 没有「确认中」。
            onPick = { selection ->
                capsuleState = capsuleOnSelect(capsuleState, selection)
                showTaskPicker = false
            },
            onDismiss = { showTaskPicker = false },
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
            agentSlice = agentSlice,
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
                pickerWsId != null && hasMoreSessionsLocal(agentSlice, historyTargetNodeId, pickerWsId)
            }?.let { load ->
                { load(historyTargetNodeId, pickerWsId!!) }
            },
            loadingMore = pickerWsId != null &&
                sessionPageKeyOf(historyTargetNodeId, pickerWsId) in agentSlice.sessionPageLoading,
        )
    }
}
