// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.ui.components.TitleBarDivider
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.action_clear_search
import com.kapp.tidings.generated.resources.common_offline
import com.kapp.tidings.generated.resources.desktop_app_title
import com.kapp.tidings.generated.resources.desktop_menu_new_conversation
import com.kapp.tidings.generated.resources.desktop_peer_connected
import com.kapp.tidings.generated.resources.desktop_peer_header
import com.kapp.tidings.generated.resources.desktop_peer_last_seen
import com.kapp.tidings.generated.resources.desktop_title_agents
import com.kapp.tidings.generated.resources.desktop_title_files
import com.kapp.tidings.generated.resources.desktop_title_nodes
import com.kapp.tidings.generated.resources.desktop_title_tagline
import com.kapp.tidings.generated.resources.desktop_toolbar_import
import com.kapp.tidings.generated.resources.desktop_toolbar_more
import com.kapp.tidings.generated.resources.desktop_toolbar_open_new_window
import com.kapp.tidings.generated.resources.desktop_toolbar_search
import com.kapp.tidings.generated.resources.desktop_toolbar_search_placeholder
import com.kapp.tidings.generated.resources.desktop_view_cabinet
import com.kapp.tidings.generated.resources.ic_add_node
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_external
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_more
import com.kapp.tidings.generated.resources.ic_search
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.ShapeRoles
import com.kapp.tidings.ui.components.AppIconButton

// ------------------------------------------------------------------ 标题栏

@Composable
public fun DesktopTitleBar(state: RelayUiState, view: DesktopView, selectedPeerId: String?, now: Long) {
    val peer = state.peer(selectedPeerId)
    val subtitle = when {
        view == DesktopView.Files -> stringResource(Res.string.desktop_title_files)
        view == DesktopView.Cabinet -> stringResource(Res.string.desktop_view_cabinet)
        // 桌面副标题与手机**不是**同一个 key：原型 `tsub(v)`（`prototype/relay.html:4853`）逐字
        // 「4 个节点在线 · 本机 a3f9c21e」；手机那条（`prototype/relay.html:3357`）是
        // 「${nodes.length} 个节点 · ${online} 在线」= `nodes_subtitle_short`。
        // 收敛时这一行被误换成手机 key（`desktop_title_nodes` 一度全仓 0 消费点），
        // 现按 Lead 裁决（D-N1）改回；`nodes_subtitle_short` 仍由手机 `NodesScreen` 与
        // 无标题栏宿主的兜底 `NodeCountLine`（`DesktopNodesPane.kt`）消费。
        view == DesktopView.Nodes -> stringResource(
            Res.string.desktop_title_nodes,
            state.onlineCount,
            state.selfNodeId.take(8),
        )
        // 工作区副标题：这一屏没有「选中项」可描述（选中一个 Agent 也不改标题栏，
        // 选择态住在屏内），所以给一句说明它是什么，与文件柜那条同形。
        view == DesktopView.Agents -> stringResource(Res.string.desktop_title_agents)
        peer != null -> stringResource(
            Res.string.desktop_peer_header,
            "${peer.ip.ifBlank { peer.host }}:${peer.udpPort}",
            if (peer.online) stringResource(Res.string.desktop_peer_connected) else stringResource(Res.string.common_offline),
            stringResource(Res.string.desktop_peer_last_seen, relativeTime(peer.lastSeenAt, now)),
        )
        else -> stringResource(Res.string.desktop_title_tagline)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(DesktopMetrics.TITLEBAR_PX.dp).padding(horizontal = LocalAppSpacing.current.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.desktop_app_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(LocalAppSpacing.current.md))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(view.label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ------------------------------------------------------------------ 工具栏

/**
 * 窗口工具栏：视图切换 + 搜索 + 一排动作。
 *
 * **为什么搜索面板在窄档位必须有个入口。** 工具栏历史上只在 Expanded 渲染
 * （`DesktopShell` 里那道 `if (sizeClass == Expanded)`），而会话搜索的 `query` 状态是壳层
 * 持有的、不随档位重置。于是在 Medium/Compact：用户可能先在宽窗里展开搜索、留下一个非空
 * `query`，再把窗口收窄 —— 侧栏立刻变成「没有匹配…的结果 / 清除搜索」，而屏幕上根本没有
 * 搜索框：用户能点「清除搜索」，却不知道自己正在筛选什么，看起来像列表坏了。
 * 所以窄档位下这里补一个可切换的搜索入口（[onToggleSearch] / [searching]）：按钮既是入口，
 * 也是「正在筛选」的状态指示 —— 点开后面板出现，按钮保持高亮；再点一次收起面板，
 * 但 `query` 不动（收起 ≠ 丢弃用户的输入）。
 *
 * 为什么不干脆在窄档位把搜索框也常驻渲染：那要多占一行高度，而窄档位的侧栏还要留给列表，
 * 而且它没有把「筛选状态不可见」这个问题变成新的问题（常驻输入框在 300px 宽度下会挤成一条
 * 缝）。切换按钮的展开/收起是完全可预测的两态，不依赖窗口尺寸的隐式行为。
 *
 * 注意：`DesktopShell` 目前在 Expanded 与 Medium 档位渲染本组件（`sizeClass != Compact`）。
 * 窄档位的搜索切换分支已接入，参数全部带默认值，保持调用点简洁。
 *
 * 这里曾经写过 `@param unreadByView`（「每个视图的未读数，画在对应 chip 上」），那是个孤儿参数：
 * 它既不在签名里，也不打算实现 —— 桌面端的视图切换住在导航栏（`DesktopNavigation.kt` 的
 * `NavigationRail` / `CompactNavigationBar`），工具栏里再放一排视图 chip 是重复入口。
 * 徽标现在画在导航栏上，见 `desktopNavBadges`。
 *
 * @param onToggleSearch 展开/收起搜索面板。传 `null` 表示「这一档没有可用的搜索入口」，
 *   窄档位下就既不画按钮也不画面板。
 * @param searching 当前是否处于筛选状态，决定按钮是否高亮，也决定窄档位下面板是否可见。
 *
 * ## 溢出菜单为什么没有截图门禁（审计 §9 缺口③，已证不可补）
 *
 * 这里曾经加过一个 `initialOverflowOpen` 参数，想让静态帧把菜单预置成展开，
 * 好把「平板宿主唯一的功能出口」录下来。**试过了，行不通，已回退。**
 *
 * 实测（探针文件已删，结论留在下）：`DropdownMenu` 走 `Popup`，而 **Layoutlib 不组合
 * `Popup` 的子内容** —— 弹层的底板与阴影会画出来，里面每一项都是空白。对照实验：
 * 同一段 `Box(background).padding{ Text }`，直接放在页面里能画出字，放进 `Popup`
 * 就是一个空框。所以门禁最多只能录到「一个空白的白框」，而把它当成参考图提交，
 * 等于给下一个人看「菜单就长这样，是空的」——比没有图更坏。
 *
 * 这一条必须写在这里，因为「加个 initial 参数就能录」是这里最自然的错觉，
 * 而 §4 那个假阳性恰恰是因为这张图缺席才得以藏身。缺口③的真正出路是
 * 交互测试（能真的点开菜单），不是截图门禁 —— 本轮没有引入 compose ui-test 依赖，
 * 所以它是**记录在案的缺口**，不是被悄悄补上的缺口。
 */
@Composable
public fun DesktopToolbar(
    sizeClass: DesktopWindowSizeClass,
    query: String,
    onQueryChange: (String) -> Unit,
    onImportFiles: () -> Unit,
    onNewConversation: () -> Unit,
    /**
     * 「导入文件」这一颗能不能按（缺陷③）。
     *
     * 没有选中会话时它是灰的：onImportFiles 的实现是「把文件发给当前会话」，
     * 没会话时按下去是静默无效 —— 一个点下去什么都不发生的按钮比一个灰按钮更糟
     * （同排的「新窗口」早就有正确的 enabled 判据，这一颗漏了）。
     * 判据由 canImportFiles() 统一给，与系统菜单栏那一项同源。
     */
    canImportFiles: Boolean = true,
    canOpenInNewWindow: Boolean,
    // 传 null 表示本设备没有多窗口能力（手机/平板），按钮直接不画：留一个永远灰着的
    // 按钮等于告诉用户「这里有个功能，只是你点不了」。
    onOpenInNewWindow: (() -> Unit)?,
    onToggleSearch: (() -> Unit)? = null,
    searching: Boolean = false,
    overflowItems: List<DesktopToolbarOverflowItem> = emptyList(),
    searchFocusRequester: FocusRequester? = null,
) {
    // Expanded 的搜索框是常驻的（原型稿那一行就是这么画的），窄档位则按 `searching` 现开现关。
    val searchPanelVisible = sizeClass == DesktopWindowSizeClass.Expanded || searching
    Column(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                // 下限而不是固定高度：系统字号放大时按钮文字要能把这一行撑高 ——
                // 固定 46dp 会在 fontScale 约 1.7 起把文字下半截裁掉（平板走的就是这条壳）。
                Modifier.fillMaxWidth().heightIn(min = DesktopMetrics.TOOLBAR_PX.dp).padding(horizontal = LocalAppSpacing.current.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
            ) {
                // 窄档位下这个按钮常驻：它同时承担「进入搜索」和「当前正在筛选」两个信号。
                if (sizeClass != DesktopWindowSizeClass.Expanded && onToggleSearch != null) {
                    ToolbarButton(
                        label = stringResource(Res.string.desktop_toolbar_search),
                        painter = painterResource(Res.drawable.ic_search),
                        onClick = onToggleSearch,
                        selected = searching,
                    )
                }
                if (searchPanelVisible) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        // 原型 `.search input{font-size:12px;padding:6px 10px 6px 29px;border-radius:9px}`
                        // （`prototype/relay.html:638-640`）—— 桌面搜索框本来就是 12px，不是 14px。
                        // 之前用 `bodyMedium`（14sp/lineHeight 20sp，`AppTheme.kt:95-101`）时，
                        // `singleLine` 会把行盒钉死在 20sp，而 14sp 的 CJK 字形需要 ≈23sp ⇒
                        // 上端被裁掉：实测占位符「文件」墨盒高 26px，而同一 14sp 在工具条按钮里
                        // （普通 `Text`，不受 TextField 行盒约束）是 34px。换成 `bodySmall`
                        // （12sp/16sp）后字形不再溢出，也与原型逐字一致。
                        textStyle = MaterialTheme.typography.bodySmall,
                        leadingIcon = { Icon(painterResource(Res.drawable.ic_search), contentDescription = null, modifier = Modifier.size(IconSizes.Label)) },
                        // 搜索框里的清除 ✕：原型 `prototype/relay.html:2602` 的 `.clr`，
                        // 只有输入非空时才画。全局搜索结果面板的空态因此不再重复放一个
                        // （`d27-search-empty` 的空态只有两行居中文字）。
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                AppIconButton(
                                    icon = Res.drawable.ic_close,
                                    contentDescription = stringResource(Res.string.action_clear_search),
                                    onClick = { onQueryChange("") },
                                )
                            }
                        } else {
                            null
                        },
                        placeholder = { Text(stringResource(Res.string.desktop_toolbar_search_placeholder), style = MaterialTheme.typography.bodySmall) },
                        // 原型 `.search input{border-radius:9px}`（`prototype/relay.html:640`）。
                        shape = ShapeRoles.Avatar,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        // 窄档位里输入框要和按钮抢一行：给它弹性但设上限，收起时也不至于把
                        // 右侧动作按钮挤出可视区。
                        modifier = (if (sizeClass == DesktopWindowSizeClass.Expanded) {
                            Modifier.width(300.dp)
                        } else {
                            Modifier.weight(1f, fill = false).widthIn(min = 140.dp, max = 300.dp)
                        }).let { if (searchFocusRequester != null) it.focusRequester(searchFocusRequester) else it },
                    )
                }
                Spacer(Modifier.weight(1f))
                ToolbarButton(
                    stringResource(Res.string.desktop_toolbar_import),
                    painterResource(Res.drawable.ic_folder),
                    onImportFiles,
                    enabled = canImportFiles,
                )
                if (onOpenInNewWindow != null) {
                    ToolbarButton(
                        label = stringResource(Res.string.desktop_toolbar_open_new_window),
                        painter = painterResource(Res.drawable.ic_external),
                        onClick = onOpenInNewWindow,
                        enabled = canOpenInNewWindow,
                    )
                }
                ToolbarButton(stringResource(Res.string.desktop_menu_new_conversation), painterResource(Res.drawable.ic_add_node), onNewConversation, primary = true)
                if (overflowItems.isNotEmpty()) {
                    ToolbarOverflowMenu(overflowItems)
                }
            }
        }
        TitleBarDivider()
    }
}

/**
 * 工具栏溢出菜单项。
 *
 * 平板等不渲染桌面菜单栏的外壳，把原本只有菜单栏才有的动作收进这里，
 * 保证隐藏菜单栏不等于丢失功能。
 */
public data class DesktopToolbarOverflowItem(
    val label: String,
    val onClick: () -> Unit,
    /**
     * 在这一项**之前**画一条分隔线。
     *
     * 原型工具栏溢出面板用 `.thr` 把八条分成三段（`prototype/relay.html:2625-2635`）：
     * `设置 / 切换到深色 / 工作区` ｜ `复制本机 ID / 诊断包 / 刷新节点 / 打开下载目录` ｜ `退出`。
     * 参考图 `prototype/screenshots/relay/d01b-tabhost-more.png` 里两条分隔线都看得见。
     */
    val dividerBefore: Boolean = false,
    /**
     * 行首图标。
     *
     * 原型 `.menu .mi2 svg{width:16px;height:16px;color:var(--on-2)}`（`prototype/relay.html:1461`）
     * —— 八条**每条都有**图标，参考图 `d01b-tabhost-more.png` 里逐条可见。
     * 传 `null` 就不画图标。
     */
    val icon: androidx.compose.ui.graphics.painter.Painter? = null,
    /**
     * 危险动作（目前只有「退出」）：文字与图标用错误色。
     *
     * 原型 `.menu .mi2.danger{color:var(--on-error-c)}`（`prototype/relay.html:1462`）+
     * `.menu .mi2.danger svg{color:var(--on-error-c)}`（`:1466`）。
     */
    val danger: Boolean = false,
)

/**
 * 溢出菜单的一行：**菜单与截图接缝面板共用**，避免两者的版式漂移。
 *
 * 版式照原型 `.menu .mi2{padding:9px 11px; font-size:14px}`（`prototype/relay.html:1458-1459`）——
 * M3 `DropdownMenuItem` 的默认字号是 `bodyLarge`（16sp），比原型大一档，
 * 所以这里显式传 `contentPadding` 与 `bodyMedium`（14sp），把生产的菜单也对齐到原型。
 */
@Composable
private fun ToolbarOverflowItemRow(
    item: DesktopToolbarOverflowItem,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        onClick = onClick,
        leadingIcon = item.icon?.let { icon ->
            {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.Label),
                    tint = if (item.danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        contentPadding = PaddingValues(horizontal = LocalAppSpacing.current.olg11, vertical = LocalAppSpacing.current.osm9),
    )
}

/** 原型 `.menu .thr{height:1px;background:var(--hair);margin:4px 8px}`（`prototype/relay.html:1469`）。 */
@Composable
private fun ToolbarOverflowItemDivider() {
    HorizontalDivider(
        Modifier.padding(
            horizontal = LocalAppSpacing.current.sm,
            vertical = LocalAppSpacing.current.xs,
        ),
    )
}


/**
 * 窄窗（Compact，< 664dp）的搜索入口。
 *
 * 这一档此前**整条工具栏都不渲染**（它的四个动作按钮在 560dp 宽度下会换行），于是
 * 会话搜索在这一档完全没有入口：从宽窗把窗口拖窄，`query` 仍是壳层状态、列表继续被它
 * 过滤，而屏幕上没有任何输入框 —— 用户只能把窗口再拖宽才能改词。
 *
 * 这里只放一颗搜索框（真机上窄窗最常缺的就是它），动作按钮仍不进来。
 */
@Composable
internal fun CompactSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            leadingIcon = {
                Icon(
                    painterResource(Res.drawable.ic_search),
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.Label),
                )
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    AppIconButton(
                        icon = Res.drawable.ic_close,
                        contentDescription = stringResource(Res.string.action_clear_search),
                        onClick = { onQueryChange("") },
                    )
                }
            } else {
                null
            },
            placeholder = { Text(stringResource(Res.string.desktop_toolbar_search_placeholder), style = MaterialTheme.typography.bodySmall) },
            shape = ShapeRoles.Avatar,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.xs),
        )
    }
}

/**
 * 工具栏末尾的溢出菜单按钮，仅在 [items] 非空时由 [DesktopToolbar] 渲染。
 *
 * 每一项在执行动作前先关闭菜单，避免设置窗口或对话框被菜单盖住。
 *
 * 这个菜单**没有截图门禁**：它走 `Popup`，而 Layoutlib 不组合 `Popup` 的子内容
 * （只画得出空白底板）。详细实测见 [DesktopToolbar] 的 KDoc。
 * `d-home-more` 那一屏画的**就是**这个面板，所以另给一个 [ToolbarOverflowPanel] 接缝
 * （同一份 `items`、画在普通 `Surface` 里）让那一屏有图可看。
 */
@Composable
private fun ToolbarOverflowMenu(items: List<DesktopToolbarOverflowItem>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppIconButton(
            icon = Res.drawable.ic_more,
            contentDescription = stringResource(Res.string.desktop_toolbar_more),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // 与 [ToolbarOverflowPanel] 同一套容器参数：白底 + 浮层阴影 + 1px hairline，
            // 出处同上（`prototype/relay.html:1456`）。
            containerColor = MaterialTheme.colorScheme.surface,
            shadowElevation = RelayElevations.Floating,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            items.forEach { item ->
                if (item.dividerBefore) {
                    ToolbarOverflowItemDivider()
                }
                ToolbarOverflowItemRow(item) {
                    expanded = false
                    item.onClick()
                }
            }
        }
    }
}

/**
 * 溢出面板的**截图接缝**版本：与 [ToolbarOverflowMenu] 用同一份 [items]，
 * 但画在普通 `Surface` 里而不是 `DropdownMenu`（后者走 `Popup`，Layoutlib 不组合）。
 *
 * 只有 [DesktopShell] 的 `overflowPanelPreview` 会渲染它（生产恒不画），
 * `d-home-more` 这一屏的**全部内容**
 * 就是这块面板（原型 `dMore(true)`，`prototype/relay.html:2622-2637`），
 * 面板不进图这一屏就没有可比的东西。
 *
 * 面板宽照原型 `min-width:206px`；条目顺序与分隔线由调用方给的 `items` 决定，
 * 所以**不会**与生产那份漂移。
 */
@Composable
internal fun ToolbarOverflowPanel(
    items: List<DesktopToolbarOverflowItem>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(OverflowPanelWidth),
        shape = MaterialTheme.shapes.medium,
        // 原型 `.menu{background:var(--surface)` 是纯白（`prototype/relay.html:1456`），
        // 不是 M3 的 `surfaceContainer`（本主题 #F6F5F0）；
        // 阴影与 1px hairline 同样照抄那一条（`--shadow-pop` 见 `:53`、`--hair` 见 `:51`）。
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = RelayElevations.Floating,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(LocalAppSpacing.current.xs)) {
            items.forEach { item ->
                if (item.dividerBefore) {
                    ToolbarOverflowItemDivider()
                }
                ToolbarOverflowItemRow(item, onClick = item.onClick)
            }
        }
    }
}

/** 原型 `.tbmore .menu{min-width:206px}`（`prototype/relay.html:2624`）。 */
private val OverflowPanelWidth = 206.dp

@Composable
public fun ToolbarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
) = ToolbarButton(
    label = label,
    painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon),
    onClick = onClick,
    primary = primary,
    enabled = enabled,
    selected = selected,
)

@Composable
public fun ToolbarButton(
    label: String,
    painter: androidx.compose.ui.graphics.painter.Painter,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
        primary -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        primary -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    // **两层**（与顶栏 IbtnBox 同一条裁决）：外层是 48dp 无障碍触控下限的命中区，
    // 内层才是原型的视觉砖（11px 字 / 9dp 圆角 / 7dp 竖内边距）。此前能点的就是那块
    // ~34dp 的砖 —— 平板（Medium/Expanded 走的就是这条壳）上手指点不中，而「导入文件」
    // 与「新窗口」还挨在一起，误触就是新建会话。
    Box(
        modifier = Modifier
            .heightIn(min = LocalAppSpacing.current.xxxl)
            .clip(ShapeRoles.Avatar)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                // 圆角 9dp：这三颗工具条按钮的 class 是 btn sm，而 .win 外壳里有档位覆盖
                // （原型 .win .btn.sm：padding 5px 9px / font-size 11px / border-radius 9px），
                // 它盖掉全局 .btn 的 12px。
                .background(bg, ShapeRoles.Avatar)
                .padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.osm7),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
        ) {
            Icon(painter, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}
