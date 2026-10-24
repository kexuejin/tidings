// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.accents
import com.kapp.tidings.navigation.AppNavItem
import com.kapp.tidings.ui.theme.RelayElevations
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 单栏外壳：**竖屏底部页签 / 横屏左侧竖导**。
 *
 * ## 它不管宽屏，宽屏由 `DesktopShell` 承接
 *
 * 这个函数只服务**单栏**两档，不画两栏。曾经它叫 `AdaptiveScaffold`、
 * 还带一条 `NavigationRail` 的「宽屏侧边导航栏」分支，但那条分支**从来没有被渲染过**：
 * 生产调用点只有 `App.kt` 的 `AdaptiveScaffold(...)` 一处，而它位于
 * `if (twoPane) { DesktopShell(...) } else { ... }` 的 **else 之内**，`twoPane` 的定义
 * 恰好就是 `layoutMode == MediumTwoPane || layoutMode == ExpandedListDetail`。
 * 于是「凡是会给两栏的档位」都走了 `DesktopShell`，这里永远只收到
 * `CompactSinglePane` / `CompactLandscape`，那条 `NavigationRail` 分支不可达。
 *
 * 这处死代码是有害的：文件头曾写着「窄屏底部页签，宽屏侧边导航栏」，读到这里的人
 * 会以为宽屏适配已经做了。真正的宽屏竖导在 [com.kapp.tidings.ui.desktop.DesktopNavigation]，
 * 由 `DesktopShell` 画。**所以本文件改名成 `CompactScaffold`**，让「宽屏不在这里」
 * 变成一眼可见的事实，而不是靠读调用点才能发现。
 *
 * ## 两种形态：竖屏底导 / 横屏竖导
 *
 * 本函数收 `CompactSinglePane` 与 `CompactLandscape` 两档，它们的**导航件不同**：
 * 竖屏用底部页签（[Scaffold] 的 `bottomBar`），横屏用左侧竖导
 * （[CompactNavigationRail]，由 [landscape] 选择）。这不是恢复上面那条死分支 ——
 * 那条分支收的是**宽屏两档**（`MediumTwoPane` / `ExpandedListDetail`，早已被
 * `DesktopShell` 截走），而横屏档的宽度通常只有 800–900 dp，给不了两栏。
 *
 * ## 参数
 *
 * [badges] 是「页签 id → 未读 / 待处理数」，缺省或 <= 0 不画徽标。
 * 用 0 当哨兵而不是 `null`：调用方是从 `unreadByPeer` 这类 map 里取值的，
 * `?: 0` 比 `containsKey` 顺手，也省掉一层装箱。
 *
 * [landscape] 为 true 时改画左侧竖导。调用方（`App.kt`）传的是
 * `layoutMode == AppLayoutMode.CompactLandscape`，即 `width > height`。
 * 缺省 false 是刻意的：截图门禁与 @Preview 里绝大多数格子是竖屏，
 * 让「竖屏」成为不写参数的那一种，省掉几十处噪音。
 *
 * ## inset 归属
 *
 * 这个外壳一律把 `contentWindowInsets` / `windowInsets` 置零。
 * 安全区由 [com.kapp.tidings.RelayApp] 根层的 `WindowInsets.safeDrawing` 消费一次，
 * 若这里保留默认值就会 pad 第二次 —— 那正是顶部 24 dp 空白 + 底栏上方白条的来源。
 * 新增页面时不要再往屏内加 `safeDrawingPadding` / `imePadding`。
 */
@Composable
fun CompactScaffold(
    selectedScreenId: String,
    navItems: List<AppNavItem>,
    onScreenSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<String, Int> = emptyMap(),
    landscape: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    if (landscape) {
        CompactNavigationRail(
            selectedScreenId = selectedScreenId,
            navItems = navItems,
            onScreenSelected = onScreenSelected,
            badges = badges,
            modifier = modifier,
            content = content,
        )
        return
    }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column {
                TitleBarDivider()
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = RelayElevations.Sheet,
                    windowInsets = WindowInsets(0),
                ) {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            selected = item.id == selectedScreenId,
                            onClick = { onScreenSelected(item.id) },
                            icon = { NavIcon(item, badges[item.id] ?: 0) },
                            label = {
                                Text(
                                    text = stringResource(item.label),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

/**
 * 横屏竖导栏宽度（dp），与 `ui.desktop.DesktopMetrics.MEDIUM_RAIL_PX` 同为 64。
 *
 * 刻意重复而不引用：`ui.desktop` 已依赖 `ui.components`，反向引用会成环。
 * 改这里时请一并改 `composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopLayout.kt:97`。
 */
private const val COMPACT_RAIL_WIDTH_DP = 64

/**
 * 手机 / 小平板**横屏**时的外壳：**左侧竖导**。
 *
 * ## 为什么横屏要换形态
 *
 * 横屏（如 852×393）的高只有 393 dp，底部页签连同安全区要吃掉约 60 dp，再扣掉顶栏，
 * 剩下的内容区不到 280 dp —— 而竖屏同样宽度的手机有 852 dp 高。底部页签在横屏是
 * **最贵的一种导航**：它吃的是最短的那条边。原型对此的立场是 `nav: 'rail'`
 * （`prototype/relay.html:2429` 的 `tabland` / `tablet` / `full` 三档全部 `nav:'rail'`，
 * `:1929 const useRail = s.nav === 'rail'`），即横屏一律改画竖导。本函数落实这条。
 *
 * 竖导宽度取 [COMPACT_RAIL_WIDTH_DP]（64 dp），与桌面平板档的竖导同宽
 * （`ui.desktop.DesktopMetrics.MEDIUM_RAIL_PX`），这样「横屏手机」和「平板」在导航件上
 * 观感一致，不是两套设计语言。**数字是刻意重复的**：`ui.desktop` 已经依赖
 * `ui.components`（`ui/desktop/DesktopNavigation.kt` 取 `badgeCountText`），这里反向 import
 * `DesktopMetrics` 会让两个包耦成环。共享的是数值口径，不是类型。
 *
 * ## 与 [com.kapp.tidings.ui.desktop.NavigationRail] 的关系
 *
 * 不复用那个函数：它在 `ui.desktop` 包，图标类型是 `ImageVector` + `DesktopView` 枚举，
 * 而这里收的是 `AppNavItem`（`DrawableResource` + 字符串 id）。两者共享的只有宽度与配色，
 * 重复的是十几行布局 —— 比拆一个跨包抽象便宜。
 *
 * ## inset 归属
 *
 * 与底导形态一致：`WindowInsets(0)`。安全区由 [com.kapp.tidings.RelayApp] 根层消费一次，
 * 这里**不得**再加 `safeDrawingPadding` —— 横屏时左右安全区（刘海侧）会变成白边。
 */
@Composable
private fun CompactNavigationRail(
    selectedScreenId: String,
    navItems: List<AppNavItem>,
    onScreenSelected: (String) -> Unit,
    badges: Map<String, Int>,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(COMPACT_RAIL_WIDTH_DP.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                // 一组互斥页签：读屏靠它报「第 2 个，共 5 个」。
                .selectableGroup()
                // 横屏 + 系统大字号时 5 个页签会高过可用高度（单格 47 + 16×fontScale），
                // 最底下的「设置」会被挤出屏幕且无处可滚。给它滚动，别让字号把入口吃掉。
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(LocalAppSpacing.current.md10))
            navItems.forEach { item ->
                NavRailButton(
                    item = item,
                    selected = item.id == selectedScreenId,
                    badgeCount = badges[item.id] ?: 0,
                    onClick = { onScreenSelected(item.id) },
                )
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.weight(1f).fillMaxHeight()) { content(Modifier.fillMaxSize()) }
    }
}

/**
 * 竖导按钮：图标在上、标签在下，选中时图标染 `primary`。
 *
 * 标签不设 [TextOverflow.Ellipsis] 之外的宽度处理：64 dp 的栏宽放得下
 * 「会话 / 文件柜 / 发现 / 终端 / 设置」这几个两三个字的标签，超长语言
 * （德语等）会省略号截断，这是可接受的降级 —— 图标本身仍可辨认。
 *
 * **读屏语义**：这颗按钮与底导的 [NavigationBarItem] 是同一物种，语义必须对齐 ——
 * 角色是 [Role.Tab]、选中态用 `semantics { selected }` 汇报，并且
 * `mergeDescendants = true` 把图标与下方标签并进同一个可点节点（不合并的话 TalkBack
 * 会先念「标签页/已选中」、再单独把标签念一遍）。缺了它们，读屏软件在横屏竖导上
 * 只会念「按钮」、不念「哪个被选中」，竖屏底导却什么都告诉用户 ——
 * 同一个导航件在两种朝向下讲两种话。图标的 `contentDescription` 是 null：
 * 标签文字就在下面可见着，图标再描述一遍「会话」会让 TalkBack 把同一个词播两次。
 *
 * 桌面/平板那份竖导（`ui.desktop.DesktopNavigation` 的 `RailButton`）是同一套语义的
 * 第二份实现，两份必须逐项一致 —— 由 `CompactScaffoldSemanticsGuardTest` 同时盯着。
 */
@Composable
private fun NavRailButton(
    item: AppNavItem,
    selected: Boolean,
    badgeCount: Int,
    onClick: () -> Unit,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .clickable(onClick = onClick, role = Role.Tab)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(vertical = LocalAppSpacing.current.md10)
            .width(COMPACT_RAIL_WIDTH_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(
            badge = {
                if (badgeCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.accents.badge,
                        contentColor = MaterialTheme.accents.onBadge,
                    ) { Text(badgeCountText(badgeCount)) }
                }
            },
        ) {
            Icon(
                painter = painterResource(item.icon),
                // 标签文字可见（下方 Text），图标描述是重复播报 → null。
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(item.label),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 页签图标，带未读徽标。
 *
 * 徽标画在图标右上角而不是标签旁：窄屏上 5 个页签的标签宽度本来就只有几毫米，
 * 再挤一个数字进来必然触发 [TextOverflow.Ellipsis]，那正是这个应用最早暴露的缺陷。
 *
 * 图标的 `contentDescription` 是 null：[NavigationBarItem] 的标签文字（下方可见的
 * 「会话」等）就是这颗页签的读屏名字，图标再描述一遍等于同一个词播报两次 ——
 * 与横屏竖导的 [NavRailButton] 同一条纪律。
 */
@Composable
private fun NavIcon(item: AppNavItem, badgeCount: Int) {
    BadgedBox(
        badge = {
            if (badgeCount > 0) {
                Badge(
                    containerColor = MaterialTheme.accents.badge,
                    contentColor = MaterialTheme.accents.onBadge,
                ) { Text(badgeCountText(badgeCount)) }
            }
        },
    ) {
        Icon(
            painter = painterResource(item.icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}
