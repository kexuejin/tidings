// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.action_back
import com.kapp.tidings.generated.resources.nodes_group_add
import com.kapp.tidings.generated.resources.ic_add_node
import com.kapp.tidings.generated.resources.ic_arrow_back
import com.kapp.tidings.generated.resources.ic_more
import com.kapp.tidings.ui.centerCapped
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.SurfaceTiers

/**
 * 大标题顶栏 —— 上一轮「界面偏 demo 感」的直接解法。
 *
 * 改之前所有页面顶部是 Material 默认的 `TopAppBar`：一个 24sp 标题居中或靠左、
 * 没有副信息、没有分隔线，于是每页顶部都像"临时塞进去的工具条"。
 * 原型 `prototype/relay.html` 的 `.topbar`（CSS 在 `:320`，渲染函数 `topbar()` 在 `:1455`）
 * 给的是另一套形状：大号标题 + 一行灰色副标题，
 * 背景与状态栏同色，底部一条 hairline。这里就是它的 Compose 形状。
 *
 * **两条必须守住的约束**：
 *  1. 这里**不加**任何 `windowInsetsPadding` / `statusBarsPadding`。系统 inset 已在
 *     `App.kt` 的根 `BoxWithConstraints` 上消费过一次（见该文件注释），
 *     再加一次就会重演"顶部多一段空白"的老缺陷。顶栏只管自己的 padding。
 *  2. 背景色由本函数自己铺 `surface`（原型 `.topbar{background:var(--surface)}`，
 *     `prototype/relay.html:545`）。**不能假定上层已经铺过底色**：屏幕内容区的底色是
 *     `CompactScaffold` 里 `Scaffold` 的默认 `containerColor`（= `background`），
 *     它盖在根节点的 `surface` 之上；顶栏自己不铺色就会露出 `background`，
 *     浅色下是 #FAFAF8 而不是 #FFFFFF，于是顶栏和页面糊成一片、没有分界。
 *
 * @param contentMaxWidth 非 null 时把顶栏**内容**收进这么宽的一列并居中（底色仍满幅）。
 *   宽屏收列用，取 [com.kapp.tidings.ui.ContentWidths.Settings]。
 *   **不要传 `SettingsInner`(608)**：原型的 `.tbinner` 自身没有横向 padding
 *   （16px 在外层 `.topbar` 上），所以它写 608；而这里下面那个
 *   `padding(horizontal = 16.dp)` 是**本函数自带**的，按 640 收才得到同样的 608 内容宽。
 *   传 608 会让内容再被吃掉 32dp，反而与内容列对不齐（详见 `AdaptiveContent.kt` 的 KDoc）。
 */
@Composable
fun LargeTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    contentMaxWidth: Dp? = null,
) {
    val sp = LocalAppSpacing.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = sp.xs, bottom = sp.md10),
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (contentMaxWidth != null) {
                        Modifier.centerCapped(contentMaxWidth)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
                .padding(horizontal = sp.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                // 可点目标必须读得出来：给 null 的后果是读屏软件只会念"按钮"，
                // 用户不知道它做什么（装饰性图标才该用 null，见同文件下游那几颗）。
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_back),
                        contentDescription = stringResource(Res.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 原型 `.topbar h1{font-size:24px;font-weight:700;letter-spacing:-.02em}`。
                    // `headlineSmall` 自带的是 SemiBold(600)，字重比原型**轻一档**；
                    // 这一行改一次，26 屏的标题字重同时对齐（共享顶栏是并行的单位，不是屏）。
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.02).em,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 动作之间是原型的 `.topbar .acts{gap:6px}`（prototype/relay.html:580）：
            // 裸 IconButton 时代靠 40dp 按钮自带的内边距留缝，换成 34dp 的 `.ibtn`
            // 方块之后必须显式给间距，否则两颗方块会贴在一起（已实测到贴死）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                actions()
            }
        }
    }
}

/** `.ibtn` 的几何（原型 `prototype/relay.html:589-591`）：34×34 的方块、内部图标 18×18。 */
private val IbtnSize = 34.dp
private val IbtnIconSize = 18.dp

/**
 * 聊天页顶栏的 `.ibtn` 小一号：原型 `.topbar.chat .ibtn{width:30px;height:30px}` +
 * `.topbar.chat .ibtn svg{width:16px;height:16px}`（`prototype/relay.html:570-571`；
 * 理由在 `:566-569` —— 聊天页那一行要同时放下返回 + 头像 + 标题 + 标签 + 在线 + 动作）。
 */
val ChatIbtnSize = 30.dp
val ChatIbtnIconSize = 16.dp

/**
 * 顶栏动作按钮 —— 原型 `.ibtn`（`prototype/relay.html:589-591`）：
 * `width:34px;height:34px;border-radius:var(--r-ctl)`(12px)、
 * `background:var(--surface-2)`、`color:var(--on)`、`.ibtn svg{width:18px;height:18px}`。
 *
 * 原型的顶栏动作**不是裸图标**：`topbar()` 的 `acts` 全部经 `actsHTML → actInline`
 * 渲染成 `<button class="ibtn">`（`prototype/relay.html:2825-2828` 与 `:3052`），
 * 所以在原型截图里它们是**浅色圆角方块**（`--surface-2` = `#F0EFE9`，深色 `#2A2C2B`）。
 * 裸 `IconButton`（无底色、且常把 tint 写成 primary）是**形状 + 颜色双重偏离**：
 * 实测原型 `m01-home.png` 顶栏右侧有 7373px 的 `#F0EFE9` 方块，而代码侧同一区域 0px。
 *
 * **两处例外，别用本件**：
 *  - 返回键是 `.ibtn.plain{background:none}`（`:592`）—— 就是普通 `IconButton`；
 *  - 聊天页顶栏是 `.topbar.chat .ibtn{width:30px;height:30px}` + `svg 16px`（`:570-571`）。
 */
@Composable
fun TopBarIconAction(
    icon: DrawableResource,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = IbtnSize,
    iconSize: Dp = IbtnIconSize,
) {
    IbtnBox(size = size, enabled = enabled, onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 同上，但图标来自 `ImageVector`（聊天页那颗「切换会话」用的是 `Icons.Outlined.SwapHoriz`）。
 * 与 `DrawableResource` 版唯一的差别就是图标来源；几何、底色、tint 完全一致。
 */
@Composable
fun TopBarIconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = IbtnSize,
    iconSize: Dp = IbtnIconSize,
) {
    IbtnBox(size = size, enabled = enabled, onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 同上，但图标由调用方给 —— 一个动作集合里两种图标来源（`DrawableResource` 与
 * `ImageVector`）混在一起时用这个，免得为一个 `when` 把 `.ibtn` 的几何复制两遍。
 * 图标尺寸由调用方自己 `Modifier.size(...)`（用 [ChatIbtnIconSize] 或 [IbtnIconSize]）。
 */
@Composable
fun TopBarIconAction(
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = IbtnSize,
    icon: @Composable () -> Unit,
) {
    IbtnBox(size = size, enabled = enabled, onClick = onClick, modifier = modifier) {
        icon()
    }
}

/** `.ibtn` 的外壳：方块 + 圆角 + `--surface-2` 底 + 点击，图标由调用方给。 */
@Composable
private fun IbtnBox(
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            // 图标砖（固定尺寸方砖 + 单颗图标）→ --surface-3。
            .background(SurfaceTiers.Inset)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 顶栏右侧的「+」动作，节点页用它唤起添加节点弹层。 */
@Composable
fun AddNodeAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TopBarIconAction(
        icon = Res.drawable.ic_add_node,
        // 纯图标按钮：没有可见文字兜底，描述必须真的说出来它做什么。
        contentDescription = stringResource(Res.string.nodes_group_add),
        onClick = onClick,
        modifier = modifier,
    )
}

/** 顶栏溢出菜单的一项：原型 `actMenuItem`（`prototype/relay.html:2829-2832`）= 图标 + 文案。 */
class TopBarOverflowItem(
    val label: String,
    val onClick: () -> Unit,
    val leadingIcon: (@Composable () -> Unit)? = null,
)

/**
 * 顶栏「⋯」动作 + 它自己的菜单 —— 原型 `actsHTML(acts, max)` 的溢出分支
 * （`prototype/relay.html:2834-2845`）：直出的动作之外若还有剩余，就在末尾补一颗
 * `ic('more')` 的 `.ibtn`，把剩余项放进 `.menu`（外壳 `.hdmore` 见 `:694-697`，
 * 菜单本体 `.menu`/`.mi2` 见 `:1477-1490`）。
 *
 * 手机顶栏的直出上限是 2（`topbar()` 把 `max` 传 2，`:3052`；聊天页自己的动作
 * 只有两颗，所以那条上限在原型的聊天页从没触发过）—— 调用方只要把「第 3 颗起」
 * 的项交给本件即可。
 *
 * **菜单体是 Popup**：Layoutlib 只合成 Popup 容器、不合成内容，所以菜单在参考图里
 * 永远是空的。要把菜单体拍进参考帧，用 [TopBarOverflowItems] 直接喂给一个 `MenuPanel`
 * 壳（房内先例：`ScreenshotPreviewsR2Chat.kt` 用同一手法拍 `ConversationsOverflowItems`）。
 */
@Composable
fun TopBarOverflowAction(
    items: List<TopBarOverflowItem>,
    modifier: Modifier = Modifier,
    size: Dp = IbtnSize,
    iconSize: Dp = IbtnIconSize,
    contentDescription: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TopBarIconAction(
            icon = Res.drawable.ic_more,
            contentDescription = contentDescription,
            onClick = { expanded = true },
            size = size,
            iconSize = iconSize,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TopBarOverflowItems(items = items, onDismiss = { expanded = false })
        }
    }
}

/**
 * 顶栏溢出菜单的内容体（可单独放进 `MenuPanel` 拍照，见 [TopBarOverflowAction]）。
 *
 * 每一项用 `DropdownMenuItem`，也就是 M3 的 48dp 触摸目标；原型的
 * `.hdmore .menu .mi2` 更紧凑（12px 字、`padding:8px 10px`，`:697`），这里刻意取
 * M3 的可点尺寸，不为了 2px 的字号牺牲触摸目标（差异已记进 `design/fidelity-deviations.yaml`）。
 */
@Composable
fun TopBarOverflowItems(items: List<TopBarOverflowItem>, onDismiss: () -> Unit = {}) {
    items.forEach { item ->
        DropdownMenuItem(
            text = { Text(item.label) },
            leadingIcon = item.leadingIcon,
            onClick = {
                onDismiss()
                item.onClick()
            },
        )
    }
}

/** 顶栏底线。对应原型的 `border-bottom:1px solid var(--hair)`。 */
@Composable
fun TitleBarDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 同步状态条。
 *
 * 原型 `.syncbar`：11.5px、`surface-2` 底、hairline 下边，前面一个 7px 的状态点。
 * 它承载的是「现在到底是不是双向实时的」这个用户反复要确认的事实，
 * 所以文案必须说清是哪一层通道（mDNS / UDP / TCP）而不是笼统一句"已连接"。
 *
 * [action] 是原型同步条**右端那颗动作**（`prototype/relay.html:3312` 健康态的
 * `<span class="r">刷新</span>`、`:3399` 停用态的 `<span class="r">重新监听</span>`；
 * 样式 `.syncbar .r{margin-left:auto;color:var(--primary);cursor:pointer}` 见 `:579`）。
 * 原型两屏都画了它，不是可选装饰。默认 `null` = 不画，既有调用点行为不变。
 */
@Composable
fun SyncBar(
    text: String,
    modifier: Modifier = Modifier,
    healthy: Boolean = true,
    ledColor: Color? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    val dotColor = ledColor ?: if (healthy) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 通栏的次级表面条（不是图标砖）→ --surface-2。
            .background(SurfaceTiers.Raised)
            .padding(horizontal = sp.lg, vertical = sp.sm6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        StatusDot(online = healthy, size = 7.dp, color = dotColor)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            action()
        }
    }
}