// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import com.kapp.tidings.ui.components.KindBadge
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.StatusDot
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.LocalCardShape
import com.kapp.tidings.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_group_online
import com.kapp.tidings.generated.resources.conv_action_delete
import com.kapp.tidings.generated.resources.conv_action_mute
import com.kapp.tidings.generated.resources.conv_action_pin
import com.kapp.tidings.generated.resources.conv_action_unmute
import com.kapp.tidings.generated.resources.conv_action_unpin
import com.kapp.tidings.generated.resources.conv_no_messages
import com.kapp.tidings.generated.resources.conv_preview_mine
import com.kapp.tidings.generated.resources.conv_pending_count
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.painterResource
import com.kapp.tidings.domain.WorkspaceUnitGroupUi
import com.kapp.tidings.domain.WorkspaceUnitUi
import com.kapp.tidings.generated.resources.conv_inbox_ws_badge_unknown_kind
import com.kapp.tidings.generated.resources.conv_inbox_ws_pending_pill
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_agent_none
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_agent_sessions
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_unnamed
import com.kapp.tidings.generated.resources.conv_inbox_ws_row_ws_sessions
import com.kapp.tidings.generated.resources.ic_bot
import com.kapp.tidings.generated.resources.ic_terminal
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.domain.model.ConversationUi
import com.kapp.tidings.ui.theme.SurfaceTiers

/**
 * 会话行之间的分隔线（**内缩**，不与行的圆角相撞）。
 *
 * ## 为什么加它 —— 这不是"原型有"，是产品判断
 *
 * 原型 \`.sidelist{gap:1px}\` + \`.li{border-radius:12px}\` 其实**没有分割线**，
 * 它靠 hover / 选中态分层。那是 Web 列表的写法：滚动区短、鼠标随手可见。
 * 而桌面端这一列窄、行多、要长时间扫读 —— 一条极淡的线能把"行的基线"立住。
 * 之前删除它时只看了原型，忽略了使用场景，是判断失误。
 *
 * ## 为什么是**内缩**而不是通栏
 *
 * 行已经是 12dp 圆角，通栏线会顶到圆角上，视觉上像把行切成两半。
 * 内缩到**文字左沿**（= 水平内边距 + 头像宽 + 图文间距）之后，线与文字对齐 ——
 * 这是 macOS / iOS 列表分隔的常规做法，也和"圆角行 + 间距"这种分组方式相容。
 *
 * 厚度不写字面量：\`HorizontalDivider\` 的默认 \`thickness\` 就是 1dp（\`DividerDefaults.Thickness\`），
 * 自己写 \`height(1.dp)\` 只会多一处会漂移的尺寸。
 */
@Composable
internal fun ConversationListDivider() {
    HorizontalDivider(
        // 内缩量用**一个** token（`xxxl`=48，约等于「水平内边距 9 + 头像 32 + 图文间距 10」的 51）。
        //
        // 不要写成 `osm9 + xxl + md10`：那会把**头像宽度**塞进一个间距表达式里，
        // 而 `:designScreenDiffGate` 扫的是「间距语境的实参」—— 它会在该屏的代码取值集合里
        // 记下一个间距 32，而原型里没有 `padding:…32…`，于是判成"代码写了原型没有的值"。
        // 那个报警是对的：**尺寸不是间距**，混在算术里就说不清是哪一个。
        modifier = Modifier.padding(start = LocalAppSpacing.current.xxxl),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}


/**
 * 长按会话行弹出的动作菜单：置顶 / 静音 / 删除。
 *
 * 独立成一个组件（而不是塞在 `ConversationRow` 里）的理由与 `ConversationsOverflowItems`
 * 同一条：`DropdownMenu` 内部是 `Popup`，Layoutlib 只合成 Popup 的容器、不合成内容。
 * 抽出来之后截图门禁能直接渲染菜单体。
 */
@Composable
private fun ConversationRowMenu(
    row: ConversationUi,
    onDismiss: () -> Unit,
    onTogglePin: (() -> Unit)?,
    onToggleMute: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (onTogglePin != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (row.isPinned) Res.string.conv_action_unpin else Res.string.conv_action_pin,
                        ),
                    )
                },
                onClick = onTogglePin,
            )
        }
        if (onToggleMute != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (row.isMuted) Res.string.conv_action_unmute else Res.string.conv_action_mute,
                        ),
                    )
                },
                onClick = onToggleMute,
            )
        }
        if (onDelete != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.conv_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = onDelete,
            )
        }
    }
}
@Composable
internal fun ConversationRow(
    row: ConversationUi,
    now: Long,
    onOpenChat: (String) -> Unit,
    /**
     * 桌面两栏的选中态。手机端没有这个状态（列表独占一屏），用默认值即可。
     *
     * 这个参数是**合并两份实现时唯一需要新增的东西** —— 它原来是桌面那份
     * `public fun ConversationRow(conv, selected, now, onSelect)` 的全部差异。
     */
    selected: Boolean = false,
    /**
     * 长按会话行弹出的动作。**三个都为 null 时整份菜单不出现**（不画空菜单）。
     *
     * 为什么挂在**长按**上：行本身只有一个目标（打开会话），再挂一个常驻 ⋯ 按钮
     * 会挤掉标题宽度（原型已经因为 394px 宽难看清把行内 ⋯ 撤过一次）。
     * 长按是移动端的标准手势，桌面也吃同一个实现（§2.6：行是内容，两端共用）。
     */
    onTogglePin: (() -> Unit)? = null,
    onToggleMute: (() -> Unit)? = null,
    onDeleteConversation: (() -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onTogglePin != null || onToggleMute != null || onDeleteConversation != null

    // 桌面有鼠标，所以要有 hover；手机的指针永远不在"悬停"状态，`hoverable` 无害。
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // 底色优先级：选中 > hover > 透明。
    //
    // **这是全仓库唯一一处定义"会话行长什么样"的地方。** 它此前有两份平行实现
    // （手机是 16dp 圆角白卡 + 投影，桌面是整幅宽平铺行 + 40% 透明度分割线），
    // 于是同一个应用里同一个列表长两个样 —— 桌面那条分割线正是"没做圆角行"的补偿。
    //
    // 现在按原型 `.li` 收口：
    //   `.li{padding:7px 9px;border-radius:12px;min-height:52px}`（prototype/relay.html:734）
    //   `.li:hover{background:var(--surface-2)}`
    //   `.li[aria-selected="true"]{background:var(--surface);box-shadow:0 0 0 1px var(--hair)}`
    // 圆角行 + 行间间距本身就承担了分组，**所以不要分割线**。
    //
    // 底色**刻意偏离原型一处**：原型选中态是 `background:var(--surface)` + 1px `--hair` 环，
    // 而本 App 的 `background`(#FAFAF8) 与 `surface`(#FFFFFF) 在亮色下只差 **1.02:1** ——
    // 照抄等于"亮色主题下选中态看不见"（独立验证者按真实色值算过）。
    // 所以：选中用 `surfaceVariant` 实底、hover 用它的一半，让两者有可见层次，
    // 同时**不用 primaryContainer**（那是主色实底，会与未读徽标抢注意力）。
    // 三档用**三个不同的表面层级**，而不是同一层加不同透明度 ——
    // 原型给的正是三个变量：`--surface` / `--surface-2` / `--surface-3`（:1083）。
    // 之前全用 `surfaceVariant` + alpha，是因为没注意到主题里 `surfaceContainerHigh` 早就定义好了
    // （Color.kt 亮 #F3F2EC / 暗 #262827），只是**没有任何界面用它**。
    val background = when {
        selected -> MaterialTheme.colorScheme.surfaceVariant
        hovered -> SurfaceTiers.Raised
        else -> Color.Transparent
    }
    val modifier = Modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .background(background)
        .then(
            // 选中态的 1px 内描边（原型那圈 `0 0 0 1px var(--hair)`）。
            // 用 drawBehind 而不是 border：border 会被外层的 clip 裁掉一半。
            //
            // 颜色必须在**这里**取出：drawBehind 的 lambda 不在组合上下文里，
            // 在里面读 MaterialTheme 会编译失败（@Composable invocations ...）。
            //
            // 环用 `outline` 而不是原型的 `--hair`（= outlineVariant）：
            // outlineVariant 对 surface 只有 1.17:1（亮）/ 1.4:1（暗），低于非文本对比下限。
            //
            // `cornerRadius` **必须显式传**：`DrawScope.drawRoundRect` 的默认是
            // `CornerRadius.Zero`，不传就画成方角，再被外层的 `clip(shapes.medium)` 裁掉四角 ——
            // 结果是"看起来没有描边"（这一条是独立验证者发现的代码级缺陷，不是取舍）。
            if (selected) {
                val ring = MaterialTheme.colorScheme.outline
                val radius = with(LocalDensity.current) { 12.dp.toPx() }
                Modifier.drawBehind {
                    drawRoundRect(
                        color = ring,
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            } else {
                Modifier
            },
        )
        // 长按开菜单；单击仍是打开会话。combinedClickable 必须给 onClick ——
        // 这里的 onClick 本来就有真动作（打开），不是死按钮。
        .combinedClickable(
            onClick = { onOpenChat(row.convId) },
            onLongClick = if (hasMenu) { { menuOpen = true } } else null,
        )

    if (menuOpen) {
        ConversationRowMenu(
            row = row,
            onDismiss = { menuOpen = false },
            onTogglePin = onTogglePin?.let { act -> { menuOpen = false; act() } },
            onToggleMute = onToggleMute?.let { act -> { menuOpen = false; act() } },
            onDelete = onDeleteConversation?.let { act -> { menuOpen = false; act() } },
        )
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = sp.xxxl)
            .hoverable(interaction)
            .padding(horizontal = sp.osm9, vertical = sp.osm7),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
    ) {
        // 头像 32dp + 圆角 12（原型 `.li .av{width:32px;height:32px;border-radius:12px}`）。
        // 会话行是原型里**唯一**用方形头像的地方。
        Box {
            PeerAvatar(
                name = row.title,
                isAgent = row.isAgent,
                size = sp.xxl,
                isGroup = row.isGroup,
                avatar = row.avatar,
                shape = MaterialTheme.shapes.medium,
            )
            // 群不画那个圆点：它表达的是"这一个对端在不在"，而群是一群人。
            // 硬画的结果是每个群都显示离线（`ConversationUi.online` 对群恒 false），
            // 用户会以为群废了。成员数在下面那行用字说清楚。
            if (!row.isGroup) {
                // 原型 `.li .av .p{width:10px;height:10px;border:2px solid var(--bg)}`
                // —— 点外面有一圈底色，把它从头像上"抠"出来。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(IconSizes.Badge)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                        .padding(sp.xxs),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusDot(row.online, size = IconSizes.Badge - sp.xxs - sp.xxs)
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 原型 `.li .t1{gap:5px}` —— 标题与 KindBadge 之间 5px（`spacing.oxs5`）。
                horizontalArrangement = Arrangement.spacedBy(sp.oxs5),
            ) {
                Text(
                    text = row.title,
                    // 原型 `.li .t1{font-size:14px;font-weight:600}`。
                    // 合并前手机用 bodyLarge(16sp)/Medium(500)、桌面用 bodyMedium(14sp)/Medium，
                    // 两边都不对：字号与字重都要按原型走。
                    // 字重没有 token 组（`tokens.weights` 为 null），而 `FontWeight.SemiBold`
                    // 在生产里已用 126 次 —— 用它就是本仓惯例。
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                KindBadge(row.isAgent)
            }
            // 「我说的」加前缀：否则用户在列表里看到一句熟悉的话，分不清是哪边说的。
            // 前缀只加在**真有消息**那一档；「还没聊过」那句不是消息，加前缀会说反。
            val previewText = row.preview?.let { text ->
                if (row.lastMessageIsMine) {
                    stringResource(Res.string.conv_preview_mine, text)
                } else {
                    text
                }
            } ?: stringResource(Res.string.conv_no_messages)
            Text(
                text = previewText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = sp.xxs),
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
                    modifier = Modifier.padding(top = sp.xxs),
                )
            }
        }

        // 原型 `.li .rt{display:grid;gap:3px}` —— 右列（时间/未读/待发）之间 3px。
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(sp.oxs3)) {
            Text(
                // 只用**最近一条消息**的时间：没聊过的会话（lastMessageAt == 0）整格留空。
                // 不能用 row.at —— 它在没消息时会退回对端 lastSeenAt，局域网发现一刷新
                // 就变成"刚刚"，正是用户反馈的"没聊过的会话一直顶在最上面"的一半。
                text = if (row.lastMessageAt > 0) relativeTime(row.lastMessageAt, now) else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.unread > 0) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                    Text(
                        text = row.unread.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                    )
                }
            }
            if (row.pendingCount > 0) {
                Text(
                    // 待发条数是用户可见文案，走资源而不是内联中文。
                    text = stringResource(Res.string.conv_pending_count, row.pendingCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

