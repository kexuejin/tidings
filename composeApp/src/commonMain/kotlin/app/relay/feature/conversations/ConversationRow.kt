// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.ui.components.KindBadge
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.StatusDot
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.LocalCardShape
import app.relay.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.stringResource
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_group_online
import app.relay.generated.resources.conv_no_messages
import app.relay.generated.resources.conv_pending_count
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.painterResource
import app.relay.app.WorkspaceUnitGroupUi
import app.relay.app.WorkspaceUnitUi
import app.relay.generated.resources.conv_inbox_ws_badge_unknown_kind
import app.relay.generated.resources.conv_inbox_ws_pending_pill
import app.relay.generated.resources.conv_inbox_ws_row_agent_none
import app.relay.generated.resources.conv_inbox_ws_row_agent_sessions
import app.relay.generated.resources.conv_inbox_ws_row_unnamed
import app.relay.generated.resources.conv_inbox_ws_row_ws_sessions
import app.relay.generated.resources.ic_bot
import app.relay.generated.resources.ic_terminal
import app.relay.ui.theme.IconSizes
import app.relay.app.model.ConversationUi

// 会话行的行模板。
//
// 从 `ConversationsScreen.kt` 原样搬到这里（那一屏 597 行，先拆再加）：
// 搬迁不改行为、不改签名。桌面会话面板另有一份平行实现（`DesktopConversationsPane.kt`
// 的 public `ConversationRow`），合并它们不在这一轮的范围里。

@Composable
internal fun ConversationRow(row: ConversationUi, now: Long, onOpenChat: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        // 原型 `.card{background:var(--surface);border-radius:var(--r-card);box-shadow:var(--shadow)}`
        // （prototype/relay.html:119 + :58）—— 圆角是 `--r-card`：移动端 16px、桌面窗 12px。
        // 圆角通过 `LocalCardShape` 动态适配：移动端 16dp、桌面端 12dp。
        shape = LocalCardShape.current,
        modifier = Modifier
            .fillMaxWidth()
            // 投影走原型的 `--shadow`（宽而浅），不用 M3 海拔 —— 原因与实测见 `relayCardShadow`。
            .relayCardShadow()
            .clickable { onOpenChat(row.convId) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
        ) {
            // 在线圆点压在头像右下角，做成"在线指示"而不是另起一列。
            Box {
                PeerAvatar(
                    name = row.title,
                    isAgent = row.isAgent,
                    size = 48.dp,
                    isGroup = row.isGroup,
                    avatar = row.avatar,
                )
                // 群不画那个圆点：它表达的是"这一个对端在不在"，而群是一群人。
                // 硬画的结果是每个群都显示离线（`ConversationUi.online` 对群恒 false），
                // 用户会以为群废了。成员数在下面那行用字说清楚。
                if (!row.isGroup) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(14.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { StatusDot(row.online) }
                }
            }

            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    KindBadge(row.isAgent)
                }
                Text(
                    text = row.preview ?: stringResource(Res.string.conv_no_messages),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                )
                if (row.isGroup) {
                    // 「2/5 人在线」而不是一个绿点：群行上真正影响决策的是"现在发出去
                    // 有没有人立刻看到"，那是一个数字，不是一个布尔。
                    Text(
                        text = stringResource(
                            Res.string.chat_group_online,
                            row.memberOnlineCount,
                            row.memberCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                Text(
                    text = if (row.at > 0) relativeTime(row.at, now) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.unread > 0) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                        Text(
                            text = row.unread.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm, vertical = LocalAppSpacing.current.xxs),
                        )
                    }
                }
                if (row.pendingCount > 0) {
                    Text(
                        // 待发条数是用户可见文案，走资源而不是内联中文：
                        // 这一行曾是硬编码字面量，翻译时会被漏掉。
                        text = stringResource(Res.string.conv_pending_count, row.pendingCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 消息列表中工作区的混排单行卡片（不展开内部 session，对齐会话行高度与样式）。
 * 点击整行直接下钻到工作区详情。
 */
@Composable
internal fun WorkspaceFeedRow(
    unit: WorkspaceUnitUi,
    group: WorkspaceUnitGroupUi,
    now: Long,
    onOpenWorkspaceUnit: (WorkspaceUnitUi) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        // 与 `ConversationRow` 同一张卡、同一口径（原型 `.card` 的 `--r-card`，见上）。
        shape = LocalCardShape.current,
        modifier = Modifier
            .fillMaxWidth()
            // 与会话行同一张卡、同一投影口径（见 `relayCardShadow`）。
            .relayCardShadow()
            .clickable { onOpenWorkspaceUnit(unit) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.lg),
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (unit.isWorkspace) Res.drawable.ic_terminal else Res.drawable.ic_bot),
                        contentDescription = null,
                        modifier = Modifier.size(IconSizes.Title),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusDot(group.online)
                }
            }

            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.sm),
                ) {
                    val title = when {
                        unit.isWorkspace -> unit.wsTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.conv_inbox_ws_row_unnamed)
                        else -> unit.agentKind
                            ?: stringResource(Res.string.conv_inbox_ws_badge_unknown_kind)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    KindBadge(isAgent = true)
                }
                val subtitle = if (unit.isWorkspace) {
                    stringResource(
                        Res.string.conv_inbox_ws_row_ws_sessions,
                        unit.agentKind ?: group.nodeName,
                        unit.sessionCount,
                    )
                } else if (unit.sessionCount > 0) {
                    stringResource(Res.string.conv_inbox_ws_row_agent_sessions, unit.sessionCount)
                } else {
                    stringResource(Res.string.conv_inbox_ws_row_agent_none)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = sp.xs),
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = if (unit.lastActivityAt > 0) relativeTime(unit.lastActivityAt, now) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (unit.pendingCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = stringResource(Res.string.conv_inbox_ws_pending_pill, unit.pendingCount),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                        )
                    }
                }
            }
        }
    }
}

