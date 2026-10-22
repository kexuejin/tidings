// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.generated.resources.agent_event_cursor
import app.relay.generated.resources.agent_session_announced_missing
import app.relay.generated.resources.agent_session_archived_section
import app.relay.generated.resources.agent_session_events_count
import app.relay.generated.resources.agent_session_events_shown
import app.relay.generated.resources.agent_session_load_more
import app.relay.generated.resources.agent_session_load_more_busy
import app.relay.generated.resources.agent_session_meta
import app.relay.generated.resources.agent_session_no_events
import app.relay.generated.resources.agent_session_started
import app.relay.generated.resources.agent_session_switch_title
import app.relay.generated.resources.agent_session_untitled
import app.relay.generated.resources.agent_session_bar_label
import app.relay.generated.resources.agent_session_pending_count
import app.relay.generated.resources.common_close
import app.relay.generated.resources.ic_check_double
import app.relay.generated.resources.ic_chevd
import app.relay.generated.resources.ic_file
import app.relay.generated.resources.ic_image
import app.relay.ui.components.AppButtonSize
import app.relay.ui.components.AppTextButton
import app.relay.ui.components.RelayDialog
import app.relay.ui.components.RelayDialogButton
import app.relay.ui.components.SessionStateLabel
import app.relay.ui.components.ThinkingBlock
import app.relay.ui.components.formatClock
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.RelayElevations
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import app.relay.app.model.AgentAttachmentUi
import app.relay.app.model.AgentEventUi
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.agentAttachmentCaption
import app.relay.app.model.agentAttachmentIsImage

@Composable
internal fun AgentSessionCard(
    session: AgentSessionUi,
    events: List<AgentEventUi>,
    now: Long,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = RelayElevations.Card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                Text(
                    // 主标题回落（alias → title）见 AgentSessionUi.displayTitle 的 KDoc；
                    // null = 这个会话从没有过名字，本地化成「未命名」，**不是**退回 sessionId。
                    text = session.displayTitle ?: stringResource(Res.string.agent_session_untitled),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val sState = session.state
                if (sState != null) {
                    SessionStateLabel(sState)
                }
            }

            val subtitle = session.title?.trim()?.takeIf {
                it.isNotBlank() &&
                !it.equals(session.displayTitle?.trim(), ignoreCase = true) &&
                !it.equals(session.alias?.trim(), ignoreCase = true)
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                )
            }

            Text(
                text = session.agentKind?.let { kind ->
                    stringResource(Res.string.agent_session_meta, kind, session.sessionId)
                } ?: session.sessionId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
            )
            session.startedAt?.let { startedAt ->
                Text(
                    text = stringResource(Res.string.agent_session_started, relativeTime(startedAt, now)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!session.announced) {
                Text(
                    text = stringResource(Res.string.agent_session_announced_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = LocalAppSpacing.current.sm))
            Text(
                text = stringResource(Res.string.agent_session_events_count, events.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (events.isEmpty()) {
                Text(
                    text = stringResource(Res.string.agent_session_no_events),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                )
                return@Column
            }

            val shown = events.takeLast(MAX_SHOWN_EVENTS)
            if (events.size > shown.size) {
                Text(
                    text = stringResource(Res.string.agent_session_events_shown, shown.size, events.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                )
            }
            shown.forEach { event -> AgentEventRow(event) }
        }
    }
}

@Composable
private fun AgentEventRow(event: AgentEventUi) {
    Column(Modifier.padding(top = LocalAppSpacing.current.sm)) {
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
                text = event.eventType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.agent_event_cursor, event.cursor),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val isThought = event.eventType.contains("thought", ignoreCase = true) ||
            event.eventType.contains("reasoning", ignoreCase = true)
        if (isThought) {
            val content = event.body?.takeIf { it.isNotBlank() }
                ?: event.summary?.takeIf { it.isNotBlank() }
                ?: ""
            ThinkingBlock(
                reasoning = content,
                isStreaming = false,
                modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
            )
        } else {
            event.summary?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = LocalAppSpacing.current.xxs))
            }
            event.body?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                )
            }
        }
        // task-45：附件信息卡（有元数据就画；没有就只留上面的文本占位）。
        event.attachment?.let { AgentAttachmentCard(it) }
    }
}

/**
 * 附件**信息卡**（task-45）：文件名 + 尺寸 + 大小，纯只读。
 *
 * 为什么不做"点开下载/预览"：附件元数据里只有 `attachmentId`，**没有**既有传输路径需要的
 * `fileId`/相对路径 —— 桥侧的字节通道还没定。先把"能看到附件本身是什么"做出来，
 * "点开"等字节通道定了再补（已记成已知限制）。
 *
 * 文案全部来自载荷（[agentAttachmentCaption]），所以这里**不需要新增任何字符串资源**；
 * 图标是纯装饰（`contentDescription = null`），信息已经在右边那行字里。
 */
@Composable
private fun AgentAttachmentCard(attachment: AgentAttachmentUi) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .padding(top = sp.xxs)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = sp.md10, vertical = sp.xxs),
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                if (agentAttachmentIsImage(attachment)) Res.drawable.ic_image else Res.drawable.ic_file,
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = agentAttachmentCaption(attachment),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MAX_SHOWN_EVENTS = 20

@Composable
internal fun AgentSessionPickerDialog(
    sessions: List<AgentSessionUi>,
    activeSessionId: String,
    state: RelayUiState,
    now: Long,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    archivedSessions: List<AgentSessionUi> = emptyList(),
    /**
     * 「加载更多」的落点（PPE-1 增补 D8）：null = 不画那一行。
     *
     * 为什么由调用方决定画不画：只有它知道「宿主声明了 agp1 吗」与「这个工作区还有没有
     * 下一页」。选择器自己猜会画出两种坏按钮 —— 对旧宿主点了没反应、或点出重复页。
     */
    onLoadMore: (() -> Unit)? = null,
    /** 正在拉取：按钮换成不可点的「加载中」，避免连点拉出重复页。 */
    loadingMore: Boolean = false,
) {
    var showArchived by remember { mutableStateOf(false) }

    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见 `ui/components/RelayDialog.kt`，
    // 严格遵循原型 `.dlg` 样式定义。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.agent_session_switch_title)) },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                sessions.forEach { session ->
                    val isActive = session.sessionId == activeSessionId
                    val pendingCount = state.agentApprovals.values.count {
                        it.sessionId == session.sessionId && it.status == "pending"
                    }
                    AgentSessionPickRow(
                        session = session,
                        isActive = isActive,
                        pendingCount = pendingCount,
                        onPick = onPick,
                    )
                }
                // 「加载更多」那一行（PPE-1 增补 D8）。
                //
                // 为什么放在列表**底部**而不是顶部：用户的动作顺序是"先看最近的，再往回翻"，
                // 而宿主按 updatedAt 降序给页，所以往下就是更旧的。放在顶上会让人以为那是
                // "刷新"，点下去反而把列表重置成第一页。
                //
                // 拉取中不画按钮而画一句「加载中」—— 连点两次会拉出重复页（宿主按
                // (nodeId, wsId) 只留最新一条请求，但两次点击的 offset 相同，回来的仍是同一页）。
                if (onLoadMore != null) {
                    Spacer(Modifier.size(LocalAppSpacing.current.sm))
                    if (loadingMore) {
                        Text(
                            text = stringResource(Res.string.agent_session_load_more_busy),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs),
                        )
                    } else {
                        // 列表末尾的「加载更多」：行内动作，且原代码显式用了 labelMedium
                        // —— 那正是小档的字阶，迁到按钮族后由 Small 档承担。
                        AppTextButton(
                            text = stringResource(Res.string.agent_session_load_more),
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth(),
                            size = AppButtonSize.Small,
                        )
                    }
                }
                // 已归档会话折叠区：默认折叠，点开显示历史归档的会话。
                if (archivedSessions.isNotEmpty()) {
                    Spacer(Modifier.size(LocalAppSpacing.current.sm))
                    HorizontalDivider(Modifier.padding(vertical = LocalAppSpacing.current.xs))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showArchived = !showArchived }
                            .padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.sm),
                    ) {
                        Text(
                            text = stringResource(Res.string.agent_session_archived_section, archivedSessions.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            painter = painterResource(Res.drawable.ic_chevd),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(if (showArchived) 180f else 0f),
                        )
                    }
                    if (showArchived) {
                        archivedSessions.forEach { session ->
                            val isActive = session.sessionId == activeSessionId
                            AgentSessionPickRow(
                                session = session,
                                isActive = isActive,
                                pendingCount = 0,
                                onPick = onPick,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            RelayDialogButton(text = stringResource(Res.string.common_close), onClick = onDismiss)
        },
    )
}

@Composable
private fun AgentSessionPickRow(
    session: AgentSessionUi,
    isActive: Boolean,
    pendingCount: Int,
    onPick: (String) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(session.sessionId) }
                .background(
                    color = if (isActive) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.sm),
        ) {
            // 勾选占位：始终占 18dp 宽，仅当前项显示勾（让名字首字母垂直对齐）
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isActive) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_check_double),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            // 会话名，弹性占满（无名时说「未命名」，理由见 displayTitle 的 KDoc）
            Text(
                text = session.displayTitle ?: stringResource(Res.string.agent_session_untitled),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 待审批角标（有才画）
            if (pendingCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.agent_session_pending_count, pendingCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm6, vertical = LocalAppSpacing.current.xxs),
                    )
                }
            }
            // 状态标签
            val sState = session.state
            if (sState != null) {
                SessionStateLabel(sState)
            }
        }
        // 坏状态提示：只收到事件、没收到会话宣告（红字，起点对齐名字）
        if (!session.announced) {
            Text(
                text = stringResource(Res.string.agent_session_announced_missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = (4 + 18 + 8).dp),
            )
        }
    }
}

/**
 * 会话切换条（原型 `.swbar`）：标题栏正下方一行，
 * 显示「会话」标签 + 当前会话名（加粗，省略溢出）+ 待审批角标 + 状态标签；
 * 多会话时右端显示向下箭头，整行可点击唤起切换对话框。
 *
 * 单会话时不可点击（样式静止），不显示向下箭头 —— 没有切换对象时箭头是谎言。
 */
@Composable
internal fun AgentSessionBar(
    sessions: List<AgentSessionUi>,
    activeSession: AgentSessionUi?,
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val canSwitch = sessions.size > 1
    // 同上：displayTitle 是那条回落规则的唯一实现。注意判空的是**会话**不是名字：
    // 没有活跃会话才整条不画；有会话但没名字时要画出来并写「未命名」，
    // 否则这一条会凭空消失（而它上面正高亮着那个会话）。
    val sessionName = activeSession?.let {
        it.displayTitle ?: stringResource(Res.string.agent_session_untitled)
    } ?: return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (canSwitch) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
    ) {
        // 「会话」 标签
        Text(
            text = stringResource(Res.string.agent_session_bar_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 会话名（弹性占满，超长省略）
        Text(
            text = sessionName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 待审批角标（有才画）
        if (pendingCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = stringResource(Res.string.agent_session_pending_count, pendingCount),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm6, vertical = 1.5.dp),
                )
            }
        }
        // 状态标签
        val sState = activeSession?.state
        if (sState != null) {
            SessionStateLabel(sState)
        }
        // 向下箭头（仅多会话时）
        if (canSwitch) {
            Icon(
                painter = painterResource(Res.drawable.ic_chevd),
                contentDescription = stringResource(Res.string.agent_session_switch_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
