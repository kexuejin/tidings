// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import androidx.compose.foundation.background
import com.kapp.tenon.domain.pendingApprovalCount
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.kapp.tenon.ui.components.PressableSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.kapp.tenon.ui.components.NavCountBadge
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.ChatSlice
import com.kapp.tenon.ui.components.TitleBarDivider
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.accents
import com.kapp.tenon.domain.model.FileCardUi
import com.kapp.tenon.domain.model.TransferState
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.ic_chat_bubble
import com.kapp.tenon.generated.resources.ic_folder
import com.kapp.tenon.generated.resources.ic_hdd
import com.kapp.tenon.generated.resources.ic_lan_search
import com.kapp.tenon.generated.resources.ic_settings
import com.kapp.tenon.generated.resources.ic_terminal
import com.kapp.tenon.generated.resources.screen_settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// ------------------------------------------------------------------ 导航条

/**
 * 竖导 / 底导的徽标：会话用未读总数，文件用**等你处置**的入向文件邀约数。
 *
 * 与移动端 [com.kapp.tenon.domain.navBadges] 同形状（缺失即 0，不画空徽标），但键是
 * [DesktopView] 而不是路由 id —— 桌面端的视图切换住在导航栏里。
 *
 * 为什么只给这三个视图画：这三个数都指得出「去哪儿看」。
 * - 未读：只有会话列表会把 `unreadByPeer` 清零，角标点进去就是能清它的那一屏。
 * - 待处置的入向邀约：文件屏把这一行标成「等我确认」并给「跳到会话」，
 *   但**接受/拒绝是在会话里按的**（`FileCardUi` 的按钮住在聊天气泡上，
 *   文件屏不重复一套）。所以这个角标是「有件事在等你」的指示，
 *   不是「在这一屏就能清掉」的待办 —— 与移动端 Agent 页的排队角标同类。
 * - 待审批：工作区屏每条审批卡上都有「批准/拒绝」，点进去就是能清它的那一屏
 *   （与移动端 Agent 页同一个数、同一条口径）。原型 `DVIEW_BADGE.agents` 也画了它。
 * 节点屏连这样的去处都没有（节点表本身已带在线状态点），
 * 硬凑一个数字出来就是画一个点不动的红点，所以它不画。
 */


/**
 * 切片版本（Phase 4）：输入 chat / agent 两片，输出同一份徽标。
 *
 * 与聚合版本逐字同义（`messages` 与 `agentApprovals` 都只在各自片里；`unreadByPeer` 属 chat 片）。
 * 壳根拿切片调用，不再让「改节点表/设置」也走到徽标重算。
 */
public fun desktopNavBadges(chat: ChatSlice, agent: AgentSlice, now: Long = 0L): Map<DesktopView, Int> = buildMap {
    val unread = chat.unreadByPeer.values.sum()
    if (unread > 0) put(DesktopView.Conversations, unread)
    val awaiting = chat.messages.values.sumOf { thread ->
        thread.count { message ->
            val card = message.file
            card != null && !card.mine && card.state == TransferState.Offered
        }
    }
    if (awaiting > 0) put(DesktopView.Files, awaiting)
    // O3：与移动端同一把尺子（pending 且未过期）。now=0 的缺省保持旧读数行为
    // （expiresAt>0 && now>expiresAt 恒 false ⇒ 不剔），需要剔过期的调用方显式传 now。
    val pendingApprovals = pendingApprovalCount(agent.agentApprovals.values, now)
    if (pendingApprovals > 0) put(DesktopView.Agents, pendingApprovals)
}

/**
 * Medium（600–999px）的 64px 竖排导航。
 *
 * 桌面原型把三个视图当平级：导航条**属于壳**，不属于会话面板 —— 它改的是外层的
 * `view`，而会话面板看不到那个状态。所以这里必须由壳把 [onViewChange] 递下来，
 * 让「文件/节点」两个按钮自己就能切，而不是依赖顶上工具栏的视图切换器。
 */
@Composable
public fun NavigationRail(
    view: DesktopView,
    onViewChange: (DesktopView) -> Unit,
    badges: Map<DesktopView, Int> = emptyMap(),
    onOpenSettings: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .width(DesktopMetrics.MEDIUM_RAIL_PX.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            // 竖导是一组互斥页签：缺 selectableGroup 时读屏不报「第 2 个，共 5 个」。
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(LocalAppSpacing.current.md10))
        DesktopView.entries.forEach { item ->
            RailButton(
                label = stringResource(desktopViewLabel(item)),
                icon = desktopViewIcon(item),
                selected = item == view,
                onClick = { onViewChange(item) },
                badge = badges[item] ?: 0,
            )
        }
        if (onOpenSettings != null) {
            Spacer(Modifier.weight(1f))
            RailButton(
                label = stringResource(Res.string.screen_settings),
                icon = painterResource(Res.drawable.ic_settings),
                selected = false,
                onClick = onOpenSettings,
            )
            Spacer(Modifier.height(LocalAppSpacing.current.md10))
        }
    }
}

/**
 * 竖导按钮：图标在上、标签在下，选中时图标染 `primary`。
 *
 * **读屏语义**：这颗按钮与移动端横屏竖导
 * （[com.kapp.tenon.ui.components.CompactScaffold] 里的 `NavRailButton`）是同一物种，
 * 语义必须一项不差 —— 角色 [Role.Tab]、选中态 `semantics { selected }`，
 * 并且 `mergeDescendants = true`：标签 `Text` 与图标在同一个可点节点里，
 * 否则 TalkBack 会先念「标签页/已选中」再单独把标签念一遍，分两次播报。
 * 缺了这一套，读屏在平板上只会念「按钮」、不念「哪个被选中」，
 * 手机竖屏底导（M3 `NavigationBarItem`）却什么都告诉用户。
 * 图标的 `contentDescription` 是 null（见 [NavIconWithBadge]）：标签文字就在下面可见着。
 */
@Composable
public fun RailButton(
    label: String,
    icon: androidx.compose.ui.graphics.painter.Painter,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int = 0,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = LocalHapticFeedback.current
    PressableSurface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        role = Role.Tab,
        color = Color.Transparent,
        modifier = Modifier
            .semantics(mergeDescendants = true) { this.selected = selected }
            .width(DesktopMetrics.MEDIUM_RAIL_PX.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = LocalAppSpacing.current.md10),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NavIconWithBadge(icon = icon, label = label, tint = tint, badge = badge)
            Spacer(Modifier.height(LocalAppSpacing.current.oxs3))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

/** Compact（<600px）的底部三段导航，与竖导同一组视图。 */
@Composable
public fun CompactNavigationBar(
    view: DesktopView,
    onViewChange: (DesktopView) -> Unit,
    badges: Map<DesktopView, Int> = emptyMap(),
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        TitleBarDivider()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DesktopMetrics.COMPACT_NAV_PX.dp)
                    // 一组互斥页签：读屏靠它报「第 2 个，共 5 个」。
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopView.entries.forEach { item ->
                    val selected = item == view
                    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    val label = stringResource(desktopViewLabel(item))
                    PressableSurface(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onViewChange(item)
                        },
                        role = Role.Tab,
                        color = Color.Transparent,
                        modifier = Modifier.semantics(mergeDescendants = true) { this.selected = selected },
                    ) {
                        Column(
                            Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.xs),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            NavIconWithBadge(icon = desktopViewIcon(item), label = label, tint = tint, badge = badges[item] ?: 0)
                            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 带角标的导航图标，与移动端底栏 [com.kapp.tenon.ui.components.CompactScaffold] 里
 * 的 `NavIcon` 同一形状：`badge <= 0` 时连 `Badge` 都不进组合，不会画出一个空圈。
 *
 * 徽标底色显式取 `accents.badge` / `onBadge`：M3 `Badge` 默认的 `error` 配白字只有 4.17:1。
 *
 * **`contentDescription` 是 null**：两个调用点（[RailButton] / [CompactNavigationBar]）
 * 都把标签文字画在图标旁边可见着，图标再描述一遍会让 TalkBack 把同一个词播两次 ——
 * 与移动端 `NavRailButton` / `NavIcon` 同一条纪律。`label` 形参仍保留：调用点按
 * 「图标 + 可见标签」成对传参，删掉它反而让两个调用点各拼一次文案。
 */
@Composable
private fun NavIconWithBadge(
    icon: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    badge: Int,
) {
    BadgedBox(
        badge = { if (badge > 0) { NavCountBadge(badge) } },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(IconSizes.Body), tint = tint)
    }
}

public fun desktopViewLabel(view: DesktopView): StringResource = view.label

@Composable
public fun desktopViewIcon(view: DesktopView): androidx.compose.ui.graphics.painter.Painter = when (view) {
    // 五视图图标 = 原型 DVIEWS 的 sprite symbol（chat/folder/hdd/lan/terminal，relay.html:3161-3167）
    // 经 design/icon-mapping.json 对账的 drawable（vector:ic_chat_bubble / ic_folder /
    // ic_hdd / ic_lan_search / ic_terminal）。V4 收敛：material-icons 字形在导航层退场，
    // 图标从「两个来源」回到「sprite 一张表」—— 改原型 sprite 这里才会跟着变。
    DesktopView.Conversations -> painterResource(Res.drawable.ic_chat_bubble)
    DesktopView.Files -> painterResource(Res.drawable.ic_folder)
    DesktopView.Cabinet -> painterResource(Res.drawable.ic_hdd)
    DesktopView.Nodes -> painterResource(Res.drawable.ic_lan_search)
    DesktopView.Agents -> painterResource(Res.drawable.ic_terminal)
}

@Composable
public fun VerticalDividerLine() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
}
