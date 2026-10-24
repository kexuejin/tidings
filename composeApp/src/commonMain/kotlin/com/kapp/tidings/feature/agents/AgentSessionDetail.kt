// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.action_back
import com.kapp.tidings.generated.resources.agent_event_dir_in
import com.kapp.tidings.generated.resources.agent_event_dir_out
import com.kapp.tidings.generated.resources.agent_events_note
import com.kapp.tidings.generated.resources.agent_messages_title
import com.kapp.tidings.generated.resources.agent_offline_message
import com.kapp.tidings.generated.resources.agent_offline_title
import com.kapp.tidings.generated.resources.agent_open_chat
import com.kapp.tidings.generated.resources.cidr_scan_progress
import com.kapp.tidings.generated.resources.agent_approval_history_title
import com.kapp.tidings.generated.resources.agent_approval_revoke_session
import com.kapp.tidings.generated.resources.agent_sessions_empty
import com.kapp.tidings.generated.resources.agent_sessions_memory_note
import com.kapp.tidings.generated.resources.agent_session_untitled
import com.kapp.tidings.generated.resources.agent_timeline_empty
import com.kapp.tidings.generated.resources.common_never
import com.kapp.tidings.generated.resources.ic_arrow_back
import com.kapp.tidings.generated.resources.ic_chat_bubble
import com.kapp.tidings.ui.ContentWidths
import com.kapp.tidings.ui.EmptyState
import com.kapp.tidings.ui.centerCapped
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppIconButton
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.SectionHeader
import com.kapp.tidings.ui.components.ThinkingBlock
import com.kapp.tidings.ui.components.formatClock
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayElevations
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.mayDecideApproval
import com.kapp.tidings.domain.pendingApprovalCount
import com.kapp.tidings.domain.model.ScanProgressUi
import com.kapp.tidings.ui.components.AppButtonTone


/**
 * 跟随滚动的判定：视口停在**离底不超过 [threshold]** 的位置时才算「用户在底部」，
 * 新事件到达才自动滚到底；用户往上翻了就不打扰（等他回到底部附近才恢复跟随）。
 *
 * 抽成纯函数是因为「±阈值」这条纪律必须能被单测钉住（旧行为是根本不滚：新事件
 * 追加在屏外，用户以为没动静）。`max <= 0`（内容不足一屏，无滚动条）按跟随处理。
 */
internal fun shouldAutoFollow(lastVisible: Int, max: Int, threshold: Int): Boolean =
    max <= 0 || max - lastVisible <= threshold

/** 「近底」的容差（像素）：比一屏小、又容得下最后一两张卡的高度。 */
internal const val AUTO_FOLLOW_THRESHOLD_PX = 480

/**
 * 会话详情「有新内容」的信号：**任一路**条数变化都算。
 *
 * 为什么不是只数聊天消息：这一屏是「审批 + 队列 + 事件流 + 消息流」四种内容的组合，
 * 旧实现拿 `state.messagesWith(...).size` 单独当 key —— 事件流或审批在屏外追加时
 * 什么都不滚，用户看到的是「已经到底了」，而新内容就挂在他视线下面。
 *
 * 用四个分量而不是求和：求和的碰撞是真实的（来一条消息 + 走一条队列任务 = 和不变 ⇒
 * 该滚不滚）。`LaunchedEffect(List)` 按结构比较，四个分量各占一格。
 */
internal fun followScrollSignature(
    messageCount: Int,
    eventCount: Int,
    approvalCount: Int,
    taskCount: Int,
): List<Int> = listOf(messageCount, eventCount, approvalCount, taskCount)

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
    /** 三参处置（「本会话免审」）：null 时退回 [onRespondApproval]，不画免审按钮。 */
    onRespondChoice: ((approvalId: String, allow: Boolean, allowSession: Boolean) -> Unit)? = null,
    /**
     * 撤销「本会话免审」：null = 不提供入口。
     *
     * 免审是一次点击授出的**会话级空白放行**；没有撤销入口，它就是一个不可逆决定。
     * 组合根接 controller 的实现：按 approvalId 逐条发一条 agent-ack（kind = revoke_session）。
     */
    onRevokeSessionApproval: ((sessionId: String) -> Unit)? = null,
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
                AppIconButton(
                    icon = Res.drawable.ic_arrow_back,
                    contentDescription = stringResource(Res.string.action_back),
                    onClick = onBack,
                )
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
            AppIconButton(
                icon = Res.drawable.ic_chat_bubble,
                contentDescription = stringResource(Res.string.agent_open_chat),
                onClick = { onOpenChat(peer.nodeId) },
                tone = AppButtonTone.Primary,
            )
        }

        // 会话切换条（原型 `.swbar`）：仅在多会话时呈现，单会话时不画冗余条目
        if (sessions.size > 1) {
            // 与看板页头同一把尺子：过期的审批不算「待批」（领域层唯一入口）。
            val sessionPendingCount = remember(state.agentApprovals, activeSession?.sessionId, now) {
                activeSession?.sessionId?.let { sid ->
                    pendingApprovalCount(state.agentApprovals.values, now, sessionId = sid)
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

        // ===== 跟随滚动（issue「会话详情无跟随滚动」）=====
        // 整屏是 verticalScroll，新事件追加在屏外时用户毫无感知。规则：视口在底部附近
        // （shouldAutoFollow）才跟随；用户往上翻了就不打扰，回到底部附近自动恢复跟随。
        val scrollState = rememberScrollState()
        var autoFollow by remember { mutableStateOf(true) }
        LaunchedEffect(scrollState) {
            snapshotFlow { scrollState.value to scrollState.maxValue }
                .collect { (v, m) -> autoFollow = shouldAutoFollow(v, m, AUTO_FOLLOW_THRESHOLD_PX) }
        }
        // 新内容到达 = 这一屏**任一路**条数变化（消息 / 事件 / 审批 / 队列），
        // 见 followScrollSignature。等一帧让新内容先完成布局（maxValue 才是新的），
        // 仍在跟随态才滚到底 —— 离底状态下这个 effect 什么都不做。
        val feedSignature = if (activeSession != null) {
            followScrollSignature(
                messageCount = state.messagesWith(peer.nodeId).size,
                eventCount = state.agentEventsOf(activeSession).size,
                approvalCount = state.agentApprovals.values.count {
                    it.peerId == peer.nodeId && it.sessionId == activeSession.sessionId
                },
                taskCount = state.agentTasks.values.count {
                    it.peerId == peer.nodeId && it.sessionId == activeSession.sessionId
                },
            )
        } else {
            emptyList()
        }
        LaunchedEffect(feedSignature) {
            if (feedSignature.isNotEmpty() && autoFollow) {
                withFrameNanos { }
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
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

                // 离线必须紧跟身份卡：它是"这个会话现在能不能指望"的第一条事实，
                // 排在审批/队列/产物之后的写法会让用户先读"能点什么"、翻到底才知道它已经离线。
                if (!peer.online) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(LocalAppSpacing.current.lg)) {
                            Text(
                                text = stringResource(Res.string.agent_offline_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = stringResource(
                                    Res.string.agent_offline_message,
                                    if (peer.lastSeenAt > 0) relativeTime(peer.lastSeenAt, now) else stringResource(Res.string.common_never),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                // ===== 待办与历史分开 =====
                // 修复前这段把「已批准/已拒绝」也画在「待处理审批 N 项」下面，计数还数它们 ——
                // 用户看到 7 项、能点的只有 1 条。现在：pending（未过期）进待处理，
                // 已决 + 已过期的 pending 进「审批记录」（过期项以「已过期」徽标常驻，不带按钮）。
                val sessionApprovals = state.agentApprovals.values.filter {
                    it.peerId == peer.nodeId && it.sessionId == activeSession.sessionId
                }
                val pendingApprovals = sessionApprovals.filter {
                    it.status == "pending" && !isApprovalExpired(it, now)
                }
                val historyApprovals = sessionApprovals.filter {
                    it.status != "pending" || isApprovalExpired(it, now)
                }
                AgentApprovalsSection(
                    approvals = pendingApprovals,
                    onRespond = onRespondApproval,
                    onRespondChoice = onRespondChoice,
                    // 与看板/工作区详情/聊天横幅同一个判据（含过期）。修复前这里是第三份手抄。
                    isReadOnly = { appr -> !state.mayDecideApproval(appr, now) },
                )
                // 「本会话免审」的撤销入口：免审是一次点击授出的会话级空白放行，
                // 没有撤销入口就等于把「以后同类都不问」变成不可逆决定。
                if (onRevokeSessionApproval != null && sessionApprovals.any { it.status == "allow_session" }) {
                    AppTextButton(
                        text = stringResource(Res.string.agent_approval_revoke_session),
                        onClick = { onRevokeSessionApproval(activeSession.sessionId) },
                        size = AppButtonSize.Small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (historyApprovals.isNotEmpty()) {
                    AgentApprovalsSection(
                        approvals = historyApprovals,
                        title = Res.string.agent_approval_history_title,
                        includeExpiredPending = true,
                        isReadOnly = { true },
                    )
                }

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
                                // 跨模块 public 属性先落成局部量，否则 smart cast 不成立。
                                val reasoning = event.reasoning
                                if (!reasoning.isNullOrBlank()) {
                                    ThinkingBlock(
                                        reasoning = reasoning,
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
                // 原型这颗是 `.btn.block.pri`（标准档、通栏、`--r-ctl` 12dp 圆角）。
                // 原来用 `LocalCardShape`（16dp 卡圆角）+ 18dp 图标，两处都偏离原型；
                // 迁到按钮族后由 Medium 档统一给 12dp 圆角与 16dp 图标。
                AppButton(
                    text = stringResource(Res.string.agent_open_chat),
                    onClick = { onOpenChat(peer.nodeId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = LocalAppSpacing.current.md),
                    leadingIcon = Res.drawable.ic_chat_bubble,
                )
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
