// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.generated.resources.Res
import app.relay.generated.resources.agent_session_state_archived
import app.relay.generated.resources.agent_session_state_busy
import app.relay.generated.resources.agent_session_state_done
import app.relay.generated.resources.agent_session_state_error
import app.relay.generated.resources.agent_session_state_idle
import app.relay.generated.resources.ic_chat_bubble
import app.relay.generated.resources.ic_chev
import app.relay.generated.resources.workspace_access_readonly
import app.relay.generated.resources.workspace_access_readwrite
import app.relay.generated.resources.workspace_badge_host
import app.relay.generated.resources.workspace_board_artifacts_format
import app.relay.generated.resources.workspace_board_queue_format
import app.relay.generated.resources.workspace_board_runs_format
import app.relay.generated.resources.workspace_no_sessions
import app.relay.generated.resources.workspace_sessions_not_synced
import app.relay.generated.resources.workspace_latest_activity_format
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.LocalCardShape
import app.relay.app.model.PeerUi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.theme.relayCardShadow
import app.relay.ui.theme.SurfaceTiers

/**
 * 工作区卡片的全部文案与计数。
 *
 * ## 为什么文案在这里是**已经格式化好的字符串**
 *
 * 这张卡片被两屏共用，而两屏对同一个位置叫法不同：看板的待审批药丸是
 * `agent_subtitle_pending`（「N 项待处理」），消息页那一段是 `conv_inbox_ws_pending_pill`；
 * 副行更不是同一句话（看板写「在线 · TCP 11800」，消息页写「还没收到会话宣告」）。
 * 把这些字符串在调用点用 `stringResource` 取好再传进来，卡片就完全不需要知道
 * 自己是画在哪一屏上的 —— 换一屏不用改卡片，也不会把某一屏的用词泄漏到另一屏。
 *
 * ## 为什么不是把 `WorkspaceUnitUi` 直接传进来
 *
 * `ui/components` 不认识 `app.relay.app` 的数据模型（`WorkspaceUnitUi` / `PeerUi` 是两
 * 套不同的东西：一个是「(节点, Agent) 的一行」，一个是「一个声明了 ag1 的节点」）。
 * 让这一层依赖上面那一层，等于把一条本来干净的依赖边反过来。
 *
 * 这两份数据的所有差异都在 [session] 与几个计数上，不是形态上的差异 —— 这正是本轮要
 * 消掉的那处重复（此前 `AgentConsoleScreen.WorkspaceCard` 与
 * `InboxWorkspaceSection.InboxUnitRow` 是同一张卡写了两遍）。
 */
internal data class WorkspaceUnitCardUi(
    val online: Boolean,
    /** 标题。调用点各自按自己的降级阶梯算好（看板是节点名，消息页是 `wsTitle → agentKind → 未上报类型`）。 */
    val title: String,
    /** 标题右边挂不挂那枚「Agent」徽标。工作区行不挂（原型实测 394px 宽时会截断长工作区名）。 */
    val kindBadge: Boolean,
    /** 再往后挂一枚中性标签（「未上报工作区」）。null = 不挂。 */
    val mutedTag: String? = null,
    val subtitle: String,
    /** 副行下面那一行补充说明（未命名工作区为什么没名字）。null = 不画。 */
    val hint: String? = null,
    /** 行尾那颗聊天按钮上的字。 */
    val chatLabel: String,
    /** 已格式化好的待审批文案。null = 不画药丸。 */
    val pendingLabel: String? = null,
    val queueCount: Int = 0,
    val runCount: Int = 0,
    val artifactCount: Int = 0,
    /** 这一行当前最关键的活跃会话。null = 画「还没有会话」。 */
    val session: WorkspaceUnitSessionUi? = null,
    /**
     * 这个工作区一共有几个会话（宿主在宣告帧里自报的数，来自 DSH 权威台账）。
     *
     * ## 为什么要它（在"行上不再印会话数"之后仍然要）
     *
     * 本地一条会话都没拿到时，靠它区分「没有会话」（0，可以断言）与「还没同步」（>0，
     * 只能如实说）。**这不是一个计数徽标**：它只在错误态出现一次，是那句"还没同步到"
     * 的理由，所以不在 issue 6 「行/组头不再数量」的范围内。
     */
    val sessionCount: Int = 0,
    val isHost: Boolean = false,
    val access: String? = null,
)

/** 卡片里那一段活跃会话。 */
internal data class WorkspaceUnitSessionUi(
    val title: String,
    /** 对端自报的会话状态；原样保留字符串，不认识的取值照原样印出来。 */
    val state: String? = null,
    /** 最新一条活动的文案（事件 summary/body，退化到会话标题）。 */
    val summary: String? = null,
    /** 这一行的时刻：优先最新事件，其次会话的上报时间。 */
    val at: Long,
)

/**
 * 工作区卡片 —— 消息页「工作区」段与工作区看板**共用**的同一条 item。
 *
 * 形态：头部（头像 + 状态点 + 标题 + 徽标 + 聊天按钮）→ 分隔线 → 队列/执行/产物计数
 * → 活跃会话（名字 + 状态 + 待审批药丸 + 最新活动 + 底栏「共 N 个会话 · 时间」+ 下钻箭头）。
 *
 * [avatar] 是插槽而不是参数：两屏的头像不是同一个东西（看板是节点头像，消息页按行是
 * Agent 机器人或工作区终端图标）。插槽只让头像这一格分叉，卡片其余部分仍然是同一份。
 */
@Composable
internal fun WorkspaceUnitCard(
    ui: WorkspaceUnitCardUi,
    now: Long,
    onClick: () -> Unit,
    onOpenChat: () -> Unit,
    avatar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .relayCardShadow(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // 原型 `.card{box-shadow:var(--shadow)}`：海拔归零、投影自绘。
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = LocalCardShape.current,
    ) {
        Column(
            modifier = Modifier.padding(sp.lg),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                avatar()
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    ) {
                        StatusDot(ui.online)
                        Text(
                            text = ui.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (ui.kindBadge) KindBadge(true)
                        if (ui.isHost) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = stringResource(Res.string.workspace_badge_host),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        } else if (ui.access != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = SurfaceTiers.Raised,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (ui.access == "ro") {
                                            Res.string.workspace_access_readonly
                                        } else {
                                            Res.string.workspace_access_readwrite
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                        ui.mutedTag?.let { MutedTag(it) }
                    }
                    Text(
                        text = ui.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (ui.hint != null) {
                        Text(
                            text = ui.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                AppOutlinedButton(
                    text = ui.chatLabel,
                    onClick = onOpenChat,
                    size = AppButtonSize.Small,
                    leadingIcon = Res.drawable.ic_chat_bubble,
                )
            }

            HorizontalDivider()

            // 工作区级 at-a-glance：队列 / 执行 / 产物三类，与下钻详情页的六个 section 对齐
            // （PPE-1 §6.2/§6.4/§6.7）。三类都没有数据时整行不画 —— 没内容的分隔行只是噪音。
            if (ui.queueCount > 0 || ui.runCount > 0 || ui.artifactCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 与分隔线之间再垫一档：外层 Column 的 spacedBy 已经给了 md，
                        // 这一档 xs 是看板原来就有的（计数行紧贴分隔线会显得糊成一团）。
                        .padding(top = sp.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.md),
                ) {
                    if (ui.queueCount > 0) {
                        BoardCount(stringResource(Res.string.workspace_board_queue_format, ui.queueCount))
                    }
                    if (ui.runCount > 0) {
                        BoardCount(stringResource(Res.string.workspace_board_runs_format, ui.runCount))
                    }
                    if (ui.artifactCount > 0) {
                        BoardCount(stringResource(Res.string.workspace_board_artifacts_format, ui.artifactCount))
                    }
                }
            }

            val session = ui.session
            if (session != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                    ) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // `weight(1f)` 是看板原来的写法，必须保留：Compose 的 Row 先量
                            // 无权重子项（状态标签、药丸各取固有宽度），再把剩余宽度给标题。
                            // 少了它标题会按内容宽度排，长会话名会把状态标签与药丸整排推出屏幕外。
                            modifier = Modifier.weight(1f),
                        )
                        session.state?.let { SessionStateLabel(it) }
                        ui.pendingLabel?.let { PendingPill(it) }
                    }

                    if (session.summary != null && session.summary != session.title) {
                        Text(
                            text = session.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = sp.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            // `session.at` 的口径在派生层（`InboxWorkspaceUnits` 的
                            // `WorkspaceUnitActiveUi.at`）：有事件时 = 最新事件的本端到达时刻（实时，
                            // 那个"刚刚"是真的）；没有事件时 = 会话的**宿主动作时刻**
                            // （`AgentSessionUi.sortActivityAt`）—— 无事件的会话基本都是分页拉回来的
                            // 历史，用本端到达时刻会让"越久没碰的工作区越写刚刚"。
                            // 卡片这一层不参与口径决定，只是照着画。
                            //
                            // 不再前缀「共 N 个会话」（issue 6）：这一行要回答的是"最新一次动在
                            // 什么时候"，会话数在段/页层面已经有了。
                            text = stringResource(
                                Res.string.workspace_latest_activity_format,
                                relativeTime(session.at, now),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            painter = painterResource(Res.drawable.ic_chev),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            // 16dp 而不是 IconSizes.Inline(14dp)：这是"可下钻"的引导箭头，
                            // 看板原来就用 16dp。取大的一档 —— 它同时是触摸目标的一部分。
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else {
                // 没有会话时，待审批药丸仍然要露出来：它的落点本来是会话那一行，
                // 而"这台机器上有 2 项等你批"恰恰是最不该因为没会话就消失的一句。
                ui.pendingLabel?.let { PendingPill(it) }
                // ## 「没有」与「还没拿到」是两件事
                //
                // 宿主报了条目但本地为空时，提示未同步而非无数据。
                //
                // 宿主报了几条、本地却一条都没有 ⇒ 那是**还没同步**，不是没有。这句必须
                // 说出来并给出路（点上面的刷新能拉），否则用户只能以为数据错了。
                //
                // 判据：没有「本地为空」这种局面，只有「本地为空**且**宿主说它没有」（可以断言）
                // 与「本地为空但宿主说有 N 条」（只能如实说还没同步）两种。
                val declared = ui.sessionCount
                Text(
                    text = if (declared > 0) {
                        stringResource(Res.string.workspace_sessions_not_synced, declared)
                    } else {
                        stringResource(Res.string.workspace_no_sessions)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 看板计数行里的一格。 */
@Composable
private fun BoardCount(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 会话状态标签。原样保留对端给的字符串：协议只定义了四档，但对端将来多一档
 * 不该把整行变成空白。
 */
@Composable
internal fun SessionStateLabel(state: String) {
    val (label, color) = when (state) {
        "idle" -> stringResource(Res.string.agent_session_state_idle) to MaterialTheme.colorScheme.onSurfaceVariant
        "busy" -> stringResource(Res.string.agent_session_state_busy) to MaterialTheme.colorScheme.primary
        "done" -> stringResource(Res.string.agent_session_state_done) to MaterialTheme.colorScheme.primary
        "error" -> stringResource(Res.string.agent_session_state_error) to MaterialTheme.colorScheme.error
        "archived" -> stringResource(Res.string.agent_session_state_archived) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> state to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
}

/** 中性徽标（「未上报工作区」这类"还缺哪一层"的说明）。 */
@Composable
internal fun MutedTag(text: String) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
        )
    }
}

/**
 * 待审批药丸。
 *
 * 以前这是两处平行实现（`InboxWorkspaceSection.PendingPill` 与看板里那颗），
 * `InboxWorkspaceSection.kt` 的 KDoc 自己记着这笔债；现在只剩这一份。
 *
 * 内边距取**两屏里更宽的那一档**（`sm` 而非 `sm6`）：看板原本就是 `sm`，而消息页
 * 原来那版是 `sm6`。合成一份时只能保留一个值，取宽的一档 —— 药丸是这一行里唯一
 * 带彩色底的元素，窄 2dp 会让它从"可点的目标"缩成"贴上去的标签"。
 */
@Composable
internal fun PendingPill(text: String) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
        )
    }
}
