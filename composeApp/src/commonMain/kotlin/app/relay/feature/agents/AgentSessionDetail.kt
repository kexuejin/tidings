// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.generated.resources.action_back
import app.relay.generated.resources.agent_event_dir_in
import app.relay.generated.resources.agent_event_dir_out
import app.relay.generated.resources.agent_events_note
import app.relay.generated.resources.agent_messages_title
import app.relay.generated.resources.agent_offline_message
import app.relay.generated.resources.agent_offline_title
import app.relay.generated.resources.agent_open_chat
import app.relay.generated.resources.cidr_scan_progress
import app.relay.generated.resources.agent_sessions_empty
import app.relay.generated.resources.agent_sessions_memory_note
import app.relay.generated.resources.agent_session_untitled
import app.relay.generated.resources.agent_timeline_empty
import app.relay.core.protocol.AgentGrantAccess
import app.relay.generated.resources.common_never
import app.relay.generated.resources.ic_arrow_back
import app.relay.generated.resources.ic_chat_bubble
import app.relay.ui.ContentWidths
import app.relay.ui.EmptyState
import app.relay.ui.centerCapped
import app.relay.ui.components.SectionHeader
import app.relay.ui.components.ThinkingBlock
import app.relay.ui.components.formatClock
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.LocalCardShape
import app.relay.ui.theme.RelayElevations
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.PeerUi
import app.relay.app.model.ScanProgressUi


/**
 * 会话详情工作台视图（**第三级**，下钻详情）。
 *
 * ## 三级 IA 里的位置（issue 6，用户裁决）
 *
 * ① 工作区看板 → ② 工作区操作中心（直通码 / 权限管理 / 最近会话入口）→ ③ **本屏**。
 * 事件流与审批队列归这一屏；直通码与权限管理**不在**这里（它们按工作区授权，已搬到二级，
 * 见 `WorkspaceDetailView` 里那段权限与宿主控制条）。
 */
@Composable
internal fun SessionDetailView(
    peer: PeerUi,
    sessions: List<AgentSessionUi>,
    activeSession: AgentSessionUi?,
    state: RelayUiState,
    now: Long,
    onBack: () -> Unit,
    onSelectSession: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onRespondApproval: ((approvalId: String, allow: Boolean) -> Unit)?,
    onCancelTask: ((taskId: String) -> Unit)?,
    modifier: Modifier = Modifier,
    // 只给截图门禁用：把「切换会话」浮层默认打开。生产侧一律走默认值（点击 `AgentSessionBar`
    // 才开），所以它不改变任何运行时行为，只是给回归帧一个能看见浮层的入口
    // —— 先例见 `ui/components/GroupCreateDialog.kt` 的 `initialName` / `initialChosen`。
    initialPicking: Boolean = false,
) {
    var picking by remember { mutableStateOf(initialPicking) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .centerCapped(ContentWidths.Pane),
    ) {
        // 常驻置顶顶栏：返回键 + 会话标题与所属工作区 + 快捷打开聊天
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                modifier = Modifier.weight(1f),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_back),
                        contentDescription = stringResource(Res.string.action_back),
                    )
                }
                Column {
                    val sTitle = activeSession?.displayTitle ?: stringResource(Res.string.agent_session_untitled)
                    Text(
                        text = sTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subWs = activeSession?.wsTitle?.takeIf { it.isNotBlank() } ?: peer.displayName
                    val subKind = activeSession?.agentKind ?: ""
                    Text(
                        text = if (subKind.isNotEmpty()) "$subWs · $subKind" else subWs,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { onOpenChat(peer.nodeId) }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_chat_bubble),
                    contentDescription = stringResource(Res.string.agent_open_chat),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 会话切换条（原型 `.swbar`）：仅在多会话时呈现，单会话时不画冗余条目
        if (sessions.size > 1) {
            val sessionPendingCount = remember(state.agentApprovals, activeSession?.sessionId) {
                activeSession?.sessionId?.let { sid ->
                    state.agentApprovals.values.count { it.sessionId == sid && it.status == "pending" }
                } ?: 0
            }
            AgentSessionBar(
                sessions = sessions,
                activeSession = activeSession,
                pendingCount = sessionPendingCount,
                onClick = { picking = true },
            )
            HorizontalDivider()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalAppSpacing.current.lg),
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            if (activeSession == null) {
                EmptyState(
                    title = stringResource(Res.string.agent_sessions_empty),
                    message = stringResource(Res.string.agent_sessions_memory_note),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val isHost = peer.nodeId == state.selfNodeId || peer.ip == "127.0.0.1" || peer.host == "localhost" || peer.host == "127.0.0.1"
                val sessionGrant = state.grantFor(peer.nodeId, activeSession.agentId ?: activeSession.agentKind, activeSession.wsId)
                val canApprove = isHost ||
                    (sessionGrant?.isMyDevice == true) ||
                    (sessionGrant?.access == AgentGrantAccess.OWNER) ||
                    (sessionGrant?.access == AgentGrantAccess.ADMIN && sessionGrant.delegatedApproval)
                val isReadOnly = !canApprove

                // 权限与宿主控制条已按 task-4 D 整体搬到**工作区详情**（WorkspaceDetailView）：
                // 授权是按工作区给的，不是按某一轮会话给的 —— 挂在会话详情上意味着
                // "换一条会话就换一套权限显示"，而且没有会话时那一栏整块消失。
                // 这一屏现在只留会话自己的东西：身份概览 / 审批 / 队列 / 执行 / 产物 / 消息流。

                // 会话身份概览卡片
                AgentSessionCard(
                    session = activeSession,
                    events = state.agentEventsOf(activeSession),
                    now = now,
                )

                // 专属待审批卡片
                val sessionApprovals = state.agentApprovals.values.filter {
                    it.peerId == peer.nodeId && it.sessionId == activeSession.sessionId
                }
                AgentApprovalsSection(
                    approvals = sessionApprovals,
                    onRespond = onRespondApproval,
                    isReadOnly = { isReadOnly },
                )

                // 专属队列卡片
                val sessionTasks = state.agentTasks.values.filter {
                    it.peerId == peer.nodeId && it.sessionId == activeSession.sessionId
                }
                AgentQueueSection(tasks = sessionTasks, onCancelTask = onCancelTask)

                // 专属执行状态卡片（PPE-1 §6.4 agent-run）
                AgentRunsSection(runs = state.agentRunsOf(peer.nodeId, activeSession.sessionId))

                // 专属产物卡片（PPE-1 §6.7 agent-artifact，只有元数据）
                AgentArtifactsSection(
                    artifacts = state.agentArtifactsOf(peer.nodeId, activeSession.sessionId),
                )

                if (!peer.online) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(LocalAppSpacing.current.lg)) {
                            Text(
                                text = stringResource(Res.string.agent_offline_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = stringResource(
                                    Res.string.agent_offline_message,
                                    if (peer.lastSeenAt > 0) relativeTime(peer.lastSeenAt, now) else stringResource(Res.string.common_never),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                // 真实聊天气泡流
                SectionHeader(stringResource(Res.string.agent_messages_title))
                val events = state.messagesWith(peer.nodeId)
                Text(
                    text = stringResource(Res.string.agent_events_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (events.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.agent_timeline_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    events.forEach { event ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                            shadowElevation = RelayElevations.Card,
                        ) {
                            Column(Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                                ) {
                                    Text(
                                        text = formatClock(event.at),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = if (event.mine) stringResource(Res.string.agent_event_dir_out) else stringResource(Res.string.agent_event_dir_in),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (!event.reasoning.isNullOrBlank()) {
                                    ThinkingBlock(
                                        reasoning = event.reasoning,
                                        elapsedMs = event.reasoningElapsedMs,
                                        isStreaming = false,
                                        modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                                    )
                                }
                                Text(
                                    text = event.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = { onOpenChat(peer.nodeId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = LocalAppSpacing.current.md),
                    shape = LocalCardShape.current,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_chat_bubble),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(LocalAppSpacing.current.xs))
                    Text(stringResource(Res.string.agent_open_chat))
                }
            }

            Column(Modifier.padding(bottom = LocalAppSpacing.current.xl)) {}
        }
    }

    if (picking && activeSession != null) {
        AgentSessionPickerDialog(
            sessions = sessions,
            activeSessionId = activeSession.sessionId,
            state = state,
            now = now,
            onPick = {
                onSelectSession(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
internal fun AgentLoadingState(progress: ScanProgressUi, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = RelayElevations.Card,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
            modifier = Modifier.padding(LocalAppSpacing.current.lg),
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(
                    Res.string.cidr_scan_progress,
                    progress.currentCidr,
                    progress.scannedHosts,
                    progress.totalHosts,
                    progress.discoveredCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
