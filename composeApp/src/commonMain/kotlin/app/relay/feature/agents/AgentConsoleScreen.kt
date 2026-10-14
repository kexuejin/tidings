// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.agents

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
import app.relay.app.RelayUiState
import app.relay.core.nowMillis
import app.relay.navigation.resolveAgentSessionId
import app.relay.ui.ContentWidths
import app.relay.ui.centerCapped
import app.relay.ui.theme.LocalAppSpacing

/**
 * 工作区（原 Agent）控制台，对齐 DSH 看板与会话详情台。
 *
 * 两级架构：
 * 1. 一级：工作区看板（WorkspaceBoardView）——轻量监控雷达，每个工作区一张卡，展示活跃会话与全局审批 Banner；
 * 2. 二级：会话详情台（SessionDetailView）——沉浸式全屏视图，常驻置顶栏（返回 + 会话名 + 切换会话），下方滚动事件流与审批队列。
 */
@Composable
fun AgentConsoleScreen(
    state: RelayUiState,
    selectedAgentId: String?,
    onSelectAgent: (String) -> Unit,
    selectedAgentSessionId: String? = null,
    onSelectAgentSession: (String) -> Unit = {},
    inDetail: Boolean = false,
    onOpenSessionDetail: ((peerId: String, sessionId: String) -> Unit)? = null,
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
    // 只给截图门禁用，见 `SessionDetailView(initialPicking = …)`。
    initialSessionPickerOpen: Boolean = false,
) {
    val agents = state.agentPeers
    val selected = agents.firstOrNull { it.nodeId == selectedAgentId } ?: agents.firstOrNull()

    // 内部备选下钻状态：桌面端壳或未接入外层导航时提供自驱下钻支持
    var localDetailSessionId by remember { mutableStateOf<String?>(null) }
    val isViewingDetail = inDetail || (onOpenSessionDetail == null && localDetailSessionId != null)

    if (isViewingDetail && selected != null) {
        // 与手机其余屏同口径：子代理会话与空壳会话不显示（见 AgentUi.visibleInDsh）。
        val sessions = state.visibleAgentSessionsOf(selected.nodeId)
        val currentSessionId = resolveAgentSessionId(
            selectedAgentSessionId ?: localDetailSessionId,
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
                localDetailSessionId = null
                onBackToBoard?.invoke()
            },
            onSelectSession = { sessionId ->
                localDetailSessionId = sessionId
                onSelectAgentSession(sessionId)
            },
            // 详情页是**会话级**视图（`resolveAgentSessionId` 挑一条会话），它不知道母工作区，
            // 所以收窄参数传 null —— 那里点开会话就是直接跟这个节点聊。
            // 要带上 wsId 的是看板那一层（它按工作区画行）。
            onOpenChat = { sessionPeerId -> onOpenChat(sessionPeerId, null) },
            onRespondApproval = onRespondApproval,
            onCancelTask = onCancelTask,
            modifier = modifier,
            initialPicking = initialSessionPickerOpen,
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
                onOpenSession = { peerId, sessionId ->
                    onSelectAgent(peerId)
                    if (onOpenSessionDetail != null) {
                        onOpenSessionDetail(peerId, sessionId)
                    } else {
                        localDetailSessionId = sessionId
                        onSelectAgentSession(sessionId)
                    }
                },
                onOpenChat = onOpenChat,
                onFindNodes = onFindNodes,
                onRefresh = onRefresh,
                onRespondApproval = onRespondApproval,
            )

            Column(Modifier.padding(bottom = LocalAppSpacing.current.xl)) {}
        }
    }
}
