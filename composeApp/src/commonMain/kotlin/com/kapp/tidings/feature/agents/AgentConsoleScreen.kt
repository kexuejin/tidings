// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.pickActiveSession
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.navigation.resolveAgentSessionId
import com.kapp.tidings.ui.ContentWidths
import com.kapp.tidings.ui.centerCapped
import com.kapp.tidings.ui.theme.LocalAppSpacing

/**
 * 工作区（原 Agent）控制台，对齐 DSH 看板与会话详情台。
 *
 * 三级架构（issue 6，用户裁决）：
 * 1. 一级：工作区看板（WorkspaceBoardView）——按设备列工作区，每行展示活跃会话与待审批；
 * 2. 二级：工作区操作中心（WorkspaceDetailView）——直通码 / 权限管理 / 最近会话入口，
 *    **不再整段列会话**（会话列表与事件流都归三级）；
 * 3. 三级：会话详情台（SessionDetailView）——沉浸式全屏视图，常驻置顶栏（返回 + 会话名 +
 *    切换会话），下方滚动事件流与审批队列。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AgentConsoleScreen(
    state: RelayUiState,
    selectedAgentId: String?,
    onSelectAgent: (String) -> Unit,
    selectedAgentSessionId: String? = null,
    onSelectAgentSession: (String) -> Unit = {},
    inDetail: Boolean = false,
    detailWsId: String? = null,
    detailSessionId: String? = null,
    onOpenWorkspaceDetail: ((peerId: String, wsId: String) -> Unit)? = null,
    onLeaveWorkspaceDetail: (() -> Unit)? = null,
    onOpenSessionDetail: ((peerId: String, sessionId: String) -> Unit)? = null,
    onLeaveSessionDetail: (() -> Unit)? = null,
    onBackToBoard: (() -> Unit)? = null,
    /** 与 `WorkspaceBoardView` 同签名：wsId 非空 = 要收窄到那个工作区（见那里的 KDoc）。 */
    onOpenChat: (peerId: String, wsId: String?) -> Unit,
    onFindNodes: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    now: Long = nowMillis(),
    showHeader: Boolean = true,
    onRespondApproval: ((approvalId: String, allow: Boolean) -> Unit)? = null,
    onCancelTask: ((taskId: String) -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    /**
     * 算「谁能用这个 agent 干活」的逐设备勾选状态；null = 这个壳不提供配置入口
     * （手机连别人的机器时就没得配 —— 那是对方的主权）。
     */
    execDevicesOf: ((String?) -> List<ExecDeviceRow>)? = null,
    /** 勾选 / 取消某台设备：(agentKey, nodeId, checked) -> Unit。 */
    onToggleExecDevice: ((String, String, Boolean) -> Unit)? = null,
    /** 「添加设备」的出口。 */
    onAddExecDevice: (() -> Unit)? = null,
    onCreateWorkspaceGroup: (() -> Unit)? = null,
    // 只给截图门禁用，见 `SessionDetailView(initialPicking = …)`。
    initialSessionPickerOpen: Boolean = false,
    onLoadWorkspaceSessions: ((peerId: String, wsId: String) -> Unit)? = null,
    /**
     * 「申请授权」（task-10）：照 `RelayController.sendAgentGrantRequest(peerId, agentKey,
     * reason, wsId, access)` 的签名，返回 null = 没发出去（没有运行时 / 对端没声明 ag1 / agr1）。
     *
     * 组合根（手机 = `App.kt`）把它接到 `controller::sendAgentGrantRequest`。默认 null =
     * 本宿主没有这条能力 ⇒ 工作区详情的远端分支不画「申请授权」按钮。
     */
    onRequestGrant: (suspend (
        peerId: String,
        agentKey: String?,
        reason: String?,
        wsId: String?,
        access: String?,
    ) -> com.kapp.tidings.core.transport.SendOutcome?)? = null,
) {
    val agents = state.agentPeers
    val selected = agents.firstOrNull { it.nodeId == selectedAgentId } ?: agents.firstOrNull()

    // 内部备选下钻状态：桌面端壳或未接入外层导航时提供自驱下钻支持
    var localDetailSessionId by remember { mutableStateOf<String?>(null) }
    var localDetailWsId by remember { mutableStateOf<String?>(null) }

    val effectiveWsId = detailWsId ?: localDetailWsId
    val effectiveSessionId = detailSessionId ?: localDetailSessionId

    val backEnabled = (localDetailSessionId != null || localDetailWsId != null) &&
        (onLeaveWorkspaceDetail == null && onLeaveSessionDetail == null)
    BackHandler(enabled = backEnabled) {
        if (localDetailSessionId != null) {
            localDetailSessionId = null
            if (localDetailWsId == null) {
                onBackToBoard?.invoke()
            }
        } else if (localDetailWsId != null) {
            localDetailWsId = null
            onBackToBoard?.invoke()
        }
    }

    // 三级导航：工作区看板 -> 工作区详情 -> 会话详情
    if (selected != null && effectiveSessionId != null) {
        val allSessions = state.visibleAgentSessionsOf(selected.nodeId)
        val sessions = if (effectiveWsId != null) {
            allSessions.filter { it.wsId == effectiveWsId }
        } else {
            allSessions
        }
        val currentSessionId = resolveAgentSessionId(
            selectedAgentSessionId ?: effectiveSessionId,
            sessions.map { it.sessionId },
        )
        val activeSession = sessions.firstOrNull { it.sessionId == currentSessionId } ?: sessions.firstOrNull()

        SessionDetailView(
            peer = selected,
            sessions = sessions,
            activeSession = activeSession,
            state = state,
            now = now,
            onBack = {
                if (onLeaveSessionDetail != null) {
                    onLeaveSessionDetail()
                } else {
                    localDetailSessionId = null
                    if (localDetailWsId == null) {
                        onBackToBoard?.invoke()
                    }
                }
            },
            onSelectSession = { sessionId ->
                if (onOpenSessionDetail != null) {
                    onOpenSessionDetail(selected.nodeId, sessionId)
                } else {
                    localDetailSessionId = sessionId
                }
                onSelectAgentSession(sessionId)
            },
            onOpenChat = { sessionPeerId ->
                activeSession?.let { onSelectAgentSession(it.sessionId) }
                onOpenChat(sessionPeerId, effectiveWsId)
            },
            onRespondApproval = onRespondApproval,
            onCancelTask = onCancelTask,
            modifier = modifier,
            initialPicking = initialSessionPickerOpen,
        )
    } else if (selected != null && effectiveWsId != null) {
        val allSessions = state.visibleAgentSessionsOf(selected.nodeId)
        val sessions = allSessions.filter { it.wsId == effectiveWsId }
        val workspace = state.agentWorkspaces.values.firstOrNull { it.wsId == effectiveWsId }
        // 这个工作区属于哪个 agent —— 执行权限的键就是它（与 store 一致）。
        val agentKeyForView: String? = workspace?.agentKey
            ?: sessions.firstOrNull()?.agentId
            ?: sessions.firstOrNull()?.agentKind

        WorkspaceDetailView(
            wsId = effectiveWsId,
            workspace = workspace,
            peer = selected,
            sessions = sessions,
            state = state,
            now = now,
            onBack = {
                if (onLeaveWorkspaceDetail != null) {
                    onLeaveWorkspaceDetail()
                } else {
                    localDetailWsId = null
                    onBackToBoard?.invoke()
                }
            },
            onOpenSession = { sessionId ->
                if (onOpenSessionDetail != null) {
                    onOpenSessionDetail(selected.nodeId, sessionId)
                } else {
                    localDetailSessionId = sessionId
                }
                onSelectAgentSession(sessionId)
            },
            onOpenChat = {
                // 进聊天前把"要停在哪条会话"记下来。挑法与工作区卡片、会话详情**同源**
                // （pickActiveSession 是唯一一处规则）：此前这里是第三份手抄的
                // 「pending → busy → first」，没有 stale-busy 判定、first 也依赖调用方列表顺序，
                // 于是"从卡片点进来"与"卡片上显示的那条"可以是两条不同的会话。
                pickActiveSession(
                    sessions = sessions,
                    lastEventAtOf = { s -> state.agentEventsOf(s).maxOfOrNull { it.at } ?: 0L },
                    pendingSessionIds = state.agentApprovals.values
                        .filter { it.peerId == selected.nodeId && it.status == "pending" }
                        .map { it.sessionId }
                        .toSet(),
                    now = now,
                )?.let { onSelectAgentSession(it.sessionId) }
                onOpenChat(selected.nodeId, effectiveWsId)
            },
            onOpenSessionChat = { sessionId ->
                onSelectAgentSession(sessionId)
                onOpenChat(selected.nodeId, effectiveWsId)
            },
            onRespondApproval = onRespondApproval,
            onRefresh = onRefresh,
            // 谁能用这个 agent 干活：逐设备勾选。
            // 数据由调用方算好（`execDeviceRows`），这里只透传 —— 派生逻辑不该散在壳里。
            execDevices = execDevicesOf?.invoke(agentKeyForView),
            onToggleExecDevice = onToggleExecDevice?.let { cb ->
                { nodeId, checked -> cb(agentKeyForView.orEmpty(), nodeId, checked) }
            },
            onAddExecDevice = onAddExecDevice,
            // 把 (peerId, agentKey, wsId) 在这里绑死，屏内只留"点一下"这件事。
            // agentKey 取工作区宣告的身份键，拿不到再退到该工作区第一条会话的身份 ——
            // 与 `grantFor` 的键口径一致（`agentId ?? agentKind`）。
            onRequestGrant = onRequestGrant?.let { send ->
                suspend {
                    send(
                        selected.nodeId,
                        workspace?.agentKey
                            ?: sessions.firstOrNull()?.agentId
                            ?: sessions.firstOrNull()?.agentKind,
                        null,
                        effectiveWsId,
                        null,
                    )
                }
            },
            modifier = modifier,
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .centerCapped(ContentWidths.Pane)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalAppSpacing.current.lg),
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            WorkspaceBoardView(
                state = state,
                agents = agents,
                selectedAgent = selected,
                showHeader = showHeader,
                now = now,
                onSelectAgent = onSelectAgent,
                onOpenWorkspace = { peerId, wsId, sessionId ->
                    onSelectAgent(peerId)
                    if (wsId != null && onOpenWorkspaceDetail != null) {
                        onOpenWorkspaceDetail(peerId, wsId)
                    } else {
                        localDetailWsId = wsId
                    }
                    if (sessionId != null) {
                        if (onOpenSessionDetail != null) {
                            onOpenSessionDetail(peerId, sessionId)
                        } else {
                            localDetailSessionId = sessionId
                        }
                        onSelectAgentSession(sessionId)
                    }
                    if (wsId != null) {
                        onLoadWorkspaceSessions?.invoke(peerId, wsId)
                    }
                },
                onOpenChat = onOpenChat,
                onFindNodes = onFindNodes,
                onRefresh = onRefresh,
                onRespondApproval = onRespondApproval,
                onCreateWorkspaceGroup = onCreateWorkspaceGroup,
            )

            Column(Modifier.padding(bottom = LocalAppSpacing.current.xl)) {}
        }
    }
}
