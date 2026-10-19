// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.ui.ContentWidths
import app.relay.ui.components.formatBytes
import app.relay.ui.components.formatCount
import app.relay.ui.components.TopBarIconAction
import app.relay.generated.resources.Res
import app.relay.generated.resources.action_back
import app.relay.generated.resources.cabinet_col_mtime
import app.relay.generated.resources.cabinet_col_name
import app.relay.generated.resources.cabinet_col_size
import app.relay.generated.resources.cabinet_crumbs_root
import app.relay.generated.resources.cabinet_crumbs_up
import app.relay.generated.resources.cabinet_dir_hint
import app.relay.generated.resources.cabinet_download_count
import app.relay.generated.resources.cabinet_footer_selected
import app.relay.generated.resources.cabinet_footer_unselected_hint
import app.relay.generated.resources.cabinet_grants_count
import app.relay.generated.resources.cabinet_grants_default_note
import app.relay.generated.resources.cabinet_grants_remove
import app.relay.generated.resources.cabinet_grants_title
import app.relay.generated.resources.cabinet_hint_off
import app.relay.generated.resources.cabinet_hint_read
import app.relay.generated.resources.cabinet_hint_write
import app.relay.generated.resources.cabinet_mine_subtitle_active
import app.relay.generated.resources.cabinet_mine_subtitle_inactive
import app.relay.generated.resources.cabinet_mine_title
import app.relay.generated.resources.cabinet_mode_off
import app.relay.generated.resources.cabinet_mode_read
import app.relay.generated.resources.cabinet_mode_write
import app.relay.generated.resources.cabinet_not_configured_desc
import app.relay.generated.resources.cabinet_not_configured_title
import app.relay.generated.resources.cabinet_open
import app.relay.generated.resources.cabinet_change
import app.relay.generated.resources.cabinet_peer_item_count
import app.relay.generated.resources.cabinet_peer_title
import app.relay.generated.resources.cabinet_peers_title
import app.relay.generated.resources.cabinet_perm_badge_readonly
import app.relay.generated.resources.cabinet_perm_badge_upload
import app.relay.generated.resources.cabinet_perm_default_title
import app.relay.generated.resources.cabinet_pick_dir
import app.relay.generated.resources.cabinet_recent_empty
import app.relay.generated.resources.cabinet_recent_hint
import app.relay.generated.resources.cabinet_recent_row_desc
import app.relay.generated.resources.cabinet_recent_title
import app.relay.generated.resources.cabinet_save_as
import app.relay.generated.resources.cabinet_share_dir_title
import app.relay.generated.resources.cabinet_side_title
import app.relay.generated.resources.cabinet_stop_share
import app.relay.generated.resources.cabinet_upload
import app.relay.generated.resources.cabinet_view_grid
import app.relay.generated.resources.cabinet_view_list
import app.relay.generated.resources.desktop_action_list
import app.relay.generated.resources.ic_arrow_back
import app.relay.generated.resources.ic_check_double
import app.relay.generated.resources.ic_close
import app.relay.generated.resources.ic_down
import app.relay.generated.resources.ic_file
import app.relay.generated.resources.ic_folder
import app.relay.generated.resources.ic_grid
import app.relay.generated.resources.ic_hdd
import app.relay.generated.resources.ic_list
import app.relay.generated.resources.ic_refresh
import app.relay.generated.resources.ic_up
import app.relay.ui.components.TitleBarDivider
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.IconSizes
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.relayCardShadow
import app.relay.ui.theme.RelayShapes

/**
 * 桌面端共享文件柜视图（原型 d-cabinet，对齐茶话间 CabinetList + CabinetPane）。
 *
 * 采用左右双栏结构：
 * - 左侧：我的文件柜摘要卡 + 同事文件柜列表；
 * - 右侧：我的配置面板 或 选定同事的文件浏览器。
 *
 * ## 三档形态（本轮新增，审计 §5.1 的 V3 包）
 *
 * | 档 | 左栏 | 右栏 |
 * | --- | --- | --- |
 * | Expanded | [ContentWidths.Sidebar]（300dp） | `weight(1f)` |
 * | Medium | [ContentWidths.SidebarNarrow]（240dp） | `weight(1f)` |
 * | Compact | 满宽（与右栏**互斥**） | 满宽（与左栏互斥） |
 *
 * 改动前的形态是 `Modifier.width(300.dp)` 写死 —— 560dp 的 Compact 窗里
 * 左栏吃掉 300，右栏只剩 260，两栏都被挤成缝。所以这里不再自己写数字：
 * Expanded 取 [ContentWidths.Sidebar]（与 [DesktopMetrics.SIDEBAR_PX] 同值，
 * 也就是改前那个 300 的出处），Medium 取 [ContentWidths.SidebarNarrow] ——
 * 与 `DesktopConversationsPane` 的 Medium 分支（`:182` 的 `width(240.dp)`）
 * 同一个口径，两屏在同一个窗口宽度下不会一个宽一个窄。
 *
 * ## 边界（必须诚实标注，不要照着原型的空气做工作）
 *
 * `d-cabinet` 在原型 `prototype/relay.html` 的 GALLERY（`:4519-4554`）里**只有
 * expanded 一格**，没有 compact / medium 档（审计 §8.1 逐格核实过）。所以
 * 「CabinetPane 原本没做 Compact 适配」是**代码欠账**，不是「原型画了、代码没有」。
 * 下面这三档是按代码事实（窗宽 + [desktopWindowSizeClass] 的阈值）推断的目标，
 * **不是**原型要求；将来若要按原型对账，这一屏没有可对照的基准。
 *
 * ## 为什么和 [FilesPane] 一样自己再判一次 sizeClass
 *
 * 理由与 `DesktopFilesPane.kt:132-141` 完全相同：两栏还是单栏取决于**这块面板**
 * 有多宽，不是整个窗口有多宽 —— Expanded 档里 `DesktopShell` 已经从左侧切掉
 * 导航竖导，剩下真正给文件柜的宽度才是判断依据。`sizeClass` 默认 `null`，
 * 于是 `ui/desktop/DesktopShell.kt` 的现有调用点一行不改也能得到正确答案。
 *
 * 已知不精确处（沿用 [FilesPane] 的既有口径，不在本包修）：`null` 分支把
 * **面板**高度交给 [desktopWindowSizeClass]，而那个函数会再扣一次 chrome 高度，
 * 高度方向上等于扣了两遍。桌面档位由宽度决定，这一条不会翻转档位。
 */
@Composable
public fun CabinetPane(
    state: RelayUiState,
    controller: DesktopPaneActions,
    modifier: Modifier = Modifier,
    now: Long = 0L,
    sizeClass: DesktopWindowSizeClass? = null,
) {
    val cabinet = state.cabinet

    BoxWithConstraints(modifier.fillMaxSize()) {
        val tier = sizeClass
            ?: desktopWindowSizeClass(maxWidth.value.toInt(), maxHeight.value.toInt())

        // Compact 档「左栏还是右栏」是**本屏**的导航状态，不是 cabinet 数据的一部分：
        // `activePeerId == null` 在双栏下表示「我的文件柜」这一项被选中，它不能同时
        // 兼职「详情是否打开」——否则窄档下「我的文件柜」一进去就退不回列表。
        // 初值取数据里是否已有选中项，这样将来若有冷启动预置的调用点也不会开在错的一栏。
        var compactDetailOpen by remember { mutableStateOf(cabinet.activePeerId != null) }

        val sidebar: @Composable (Modifier, (String?) -> Unit) -> Unit = { sidebarModifier, onSelect ->
            CabinetSidebar(state = state, onSelect = onSelect, modifier = sidebarModifier)
        }

        val detail: @Composable (Modifier, (() -> Unit)?) -> Unit = { detailModifier, onBack ->
            // 右栏里量一次**自己的**宽度：这是 [PeerCabinetBrowser] 头部要不要让位、
            // 网格排几列的依据。放在这一层而不是 [CabinetPane] 顶层，是因为顶层量到的
            // 是整窗宽度（含左栏与竖导），右栏实际剩多少要等 `weight(1f)` 分完才知道。
            BoxWithConstraints(detailModifier.background(MaterialTheme.colorScheme.background)) {
                val paneTier = desktopWindowSizeClass(maxWidth.value.toInt(), maxHeight.value.toInt())
                if (cabinet.activePeerId == null) {
                    // 我的文件柜设置面板
                    MineCabinetPanel(state = state, controller = controller, now = now, onBack = onBack)
                } else {
                    // 同事文件柜浏览面板
                    PeerCabinetBrowser(
                        state = state,
                        controller = controller,
                        now = now,
                        paneTier = paneTier,
                        onBack = onBack,
                    )
                }
            }
        }

        when (tier) {
            // Expanded：原型 d-cabinet 画的就是这一档，左栏 300 + 右栏吃掉剩余宽度。
            DesktopWindowSizeClass.Expanded -> Row(Modifier.fillMaxSize()) {
                sidebar(Modifier.width(ContentWidths.Sidebar).fillMaxHeight(), controller::selectCabinetPeer)
                VerticalDividerLine()
                detail(Modifier.weight(1f).fillMaxHeight(), null)
            }

            // Medium：窗口只有 664–999，竖导已经在壳里占掉 64dp。左栏让出 60dp 给右栏，
            // 与 DesktopConversationsPane 的 Medium 分支同一个数字（ContentWidths.SidebarNarrow）。
            DesktopWindowSizeClass.Medium -> Row(Modifier.fillMaxSize()) {
                sidebar(Modifier.width(ContentWidths.SidebarNarrow).fillMaxHeight(), controller::selectCabinetPeer)
                VerticalDividerLine()
                detail(Modifier.weight(1f).fillMaxHeight(), null)
            }

            // Compact：560–663。左栏 240 加上右栏也只剩两条缝，所以两栏**互斥**：
            // 一屏只画一个，回列表的入口在详情头部（同 DesktopConversationsPane /
            // DesktopFilesPane 的做法）。壳在底部给了三段导航，视图切换不靠这一屏。
            DesktopWindowSizeClass.Compact -> {
                if (compactDetailOpen) {
                    detail(Modifier.fillMaxSize(), { compactDetailOpen = false })
                } else {
                    sidebar(Modifier.fillMaxSize(), { peerId ->
                        controller.selectCabinetPeer(peerId)
                        compactDetailOpen = true
                    })
                }
            }
        }
    }
}

/**
 * 文件柜左栏：我的文件柜摘要卡 + 在线同事列表。
 *
 * 宽度**不在这里定**：调用点按档位给 [ContentWidths.Sidebar] / [ContentWidths.SidebarNarrow] /
 * 满宽。写在这里就又变成一处看不见的分档判断（改前那个 `width(300.dp)` 的教训）。
 *
 * @param onSelect 传 `null` 表示选中「我的文件柜」，传 nodeId 表示选中那位同事。
 *   Compact 档的调用点靠它顺手把右栏打开，宽档直接转给 controller。
 */
@Composable
private fun CabinetSidebar(
    state: RelayUiState,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cabinet = state.cabinet
    val peers = state.humanPeers.filter { it.online }

    Column(
        modifier.fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(LocalAppSpacing.current.md),
    ) {
        Text(
            stringResource(Res.string.cabinet_side_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(LocalAppSpacing.current.md))

        // 「我的文件柜」卡片
        val isMine = cabinet.activePeerId == null
        Card(
            onClick = { onSelect(null) },
            colors = CardDefaults.cardColors(
                containerColor = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(LocalAppSpacing.current.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.ic_hdd), contentDescription = null, Modifier.size(28.dp))
                Spacer(Modifier.width(LocalAppSpacing.current.md10))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.cabinet_mine_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        state.settings.shareRoot ?: stringResource(Res.string.cabinet_mode_off),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(LocalAppSpacing.current.lg))
        Text(
            // 同事列表的小标题，同样不该带名字（原来传 `peers.size` ⇒ 「4的文件柜」）。
            stringResource(Res.string.cabinet_peers_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(LocalAppSpacing.current.sm))

        // 同事列表。窄档（240dp）与常规档（300dp）都是**单列**：这里一行是一个人，
        // 不是卡片流，列数自适应对它没有意义（审计 §7.2 的「列表列数」只针对卡片流）。
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs)) {
            items(peers) { peer ->
                val isSelected = cabinet.activePeerId == peer.nodeId
                Row(
                    Modifier.fillMaxWidth().clip(RelayShapes.small)
                        .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { onSelect(peer.nodeId) }
                        .padding(horizontal = LocalAppSpacing.current.sm, vertical = LocalAppSpacing.current.sm6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PeerAvatar(name = peer.nick, isAgent = peer.isAgent, size = 32.dp)
                    Spacer(Modifier.width(LocalAppSpacing.current.sm))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.cabinet_peer_title, peer.nick),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        Text(
                            if (peer.online) stringResource(Res.string.cabinet_perm_badge_upload) else stringResource(Res.string.cabinet_mode_read),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 我的文件柜设置面板（右栏）。
 *
 * **右栏内容不另加宽度上限**（与 [PeerCabinetBrowser] 一致）：本包的宽度口径只改
 * 「左栏多宽」（见 [CabinetPane] 的档位表），右栏继续吃 `weight(1f)` 的全部剩余宽度。
 * 审计 §6 的宽屏收列（[ContentWidths.Pane]）落在移动/共享的 `CabinetScreen` 上，
 * 不在这里 —— 桌面双栏本身已经把内容切到半幅，再叠一层收列是另一件事，没被要求。
 *
 * @param onBack 非 `null` 表示当前是 Compact 单栏档：在头部画一个回列表的按钮。
 *   宽档传 `null`，按钮不画 —— 左栏一直在旁边，画它只会占宽度。
 */
@Composable
private fun MineCabinetPanel(
    state: RelayUiState,
    controller: DesktopPaneActions,
    now: Long,
    onBack: (() -> Unit)? = null,
) {
    val settings = state.settings
    val hasRoot = !settings.shareRoot.isNullOrBlank()

    LazyColumn(Modifier.fillMaxSize().padding(LocalAppSpacing.current.xl), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg)) {
        // 头部
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    ToolbarButton(stringResource(Res.string.desktop_action_list), painterResource(Res.drawable.ic_list), onBack)
                    Spacer(Modifier.width(LocalAppSpacing.current.md))
                }
                Icon(painterResource(Res.drawable.ic_hdd), contentDescription = null, Modifier.size(32.dp))
                Spacer(Modifier.width(LocalAppSpacing.current.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.cabinet_mine_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (hasRoot) stringResource(Res.string.cabinet_mine_subtitle_active) else stringResource(Res.string.cabinet_mine_subtitle_inactive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasRoot) {
                    OutlinedButton(onClick = { controller.setShareRoot(null) }) {
                        Text(stringResource(Res.string.cabinet_stop_share))
                    }
                }
            }
        }

        if (!hasRoot) {
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = LocalAppSpacing.current.lg)
                        .relayCardShadow(shape = MaterialTheme.shapes.medium),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(LocalAppSpacing.current.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(Res.drawable.ic_folder), contentDescription = null, Modifier.size(48.dp))
                        Spacer(Modifier.height(LocalAppSpacing.current.md))
                        Text(
                            stringResource(Res.string.cabinet_not_configured_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                        Text(
                            stringResource(Res.string.cabinet_not_configured_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.lg))
                        Button(onClick = { controller.pickShareRoot() }) {
                            Text(stringResource(Res.string.cabinet_pick_dir))
                        }
                    }
                }
            }
        } else {
            // 卡片 1: 共享目录
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(LocalAppSpacing.current.lg)) {
                        Text(stringResource(Res.string.cabinet_share_dir_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(LocalAppSpacing.current.sm))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(Res.drawable.ic_folder), contentDescription = null, Modifier.size(IconSizes.Title))
                            Spacer(Modifier.width(LocalAppSpacing.current.sm))
                            Text(settings.shareRoot.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { }, modifier = Modifier.padding(end = LocalAppSpacing.current.sm)) {
                                Text(stringResource(Res.string.cabinet_open))
                            }
                            Button(onClick = { controller.pickShareRoot() }) {
                                Text(stringResource(Res.string.cabinet_change))
                            }
                        }
                        Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                        Text(stringResource(Res.string.cabinet_dir_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 卡片 2: 默认权限
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(LocalAppSpacing.current.lg)) {
                        Text(stringResource(Res.string.cabinet_perm_default_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(LocalAppSpacing.current.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                            val modes = listOf("off" to Res.string.cabinet_mode_off, "read" to Res.string.cabinet_mode_read, "write" to Res.string.cabinet_mode_write)
                            modes.forEach { (m, labelRes) ->
                                val selected = settings.shareMode == m
                                Button(
                                    onClick = { controller.setShareMode(m) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                ) {
                                    Text(stringResource(labelRes))
                                }
                            }
                        }
                        Spacer(Modifier.height(LocalAppSpacing.current.sm))
                        val hintRes = when (settings.shareMode) {
                            "off" -> Res.string.cabinet_hint_off
                            "write" -> Res.string.cabinet_hint_write
                            else -> Res.string.cabinet_hint_read
                        }
                        Text(stringResource(hintRes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 卡片 3: 单独设过的同事
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(LocalAppSpacing.current.lg)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(Res.string.cabinet_grants_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(stringResource(Res.string.cabinet_grants_count, settings.shareGrants.size), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(LocalAppSpacing.current.md10))
                        if (settings.shareGrants.isEmpty()) {
                            Text(stringResource(Res.string.cabinet_grants_default_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            settings.shareGrants.forEach { grant ->
                                Row(Modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.xs), verticalAlignment = Alignment.CenterVertically) {
                                    Text(grant.nodeId.take(8), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(grant.mode, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = LocalAppSpacing.current.sm))
                                    IconButton(onClick = { controller.removeShareGrant(grant.nodeId) }) {
                                        Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.cabinet_grants_remove))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 卡片 4: 最近有人放进来
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(LocalAppSpacing.current.lg)) {
                        Text(stringResource(Res.string.cabinet_recent_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(LocalAppSpacing.current.md10))
                        if (settings.cabinetRecentUploads.isEmpty()) {
                            Text(stringResource(Res.string.cabinet_recent_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            settings.cabinetRecentUploads.forEach { item ->
                                Row(Modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.xs), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(Res.string.cabinet_recent_row_desc, item.uploaderNick, item.fileCount, formatBytes(item.totalSize)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(relativeTime(item.ts, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                        Text(stringResource(Res.string.cabinet_recent_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 同事文件柜浏览面板（右栏）。
 *
 * @param paneTier 由 [CabinetPane] 在右栏位置上量出来的分档（见那里的 `detail` 槽）。
 *   与 [CabinetPane] 自己用的那个 `tier` **不是同一个值**：外层判的是整个窗
 *   （含左栏与竖导），这里判的是右栏实际剩下多少 —— Expanded 窗（≥1000）减去
 *   竖导 64 与左栏 300 之后右栏约 636–1076，正好横跨 Medium 与 Expanded 两档。
 *   换句话说「窗口是 Expanded」不代表「这块浏览区够宽」，所以两处必须分开判。
 *
 * @param onBack 非 `null` 表示当前是 Compact 单栏档：在头部最左画一个回列表的按钮。
 *   宽档传 `null`，按钮不画 —— 左栏一直在旁边，画它只会占宽度。
 */
@Composable
private fun PeerCabinetBrowser(
    state: RelayUiState,
    controller: DesktopPaneActions,
    now: Long,
    paneTier: DesktopWindowSizeClass,
    onBack: (() -> Unit)? = null,
) {
    val cabinet = state.cabinet
    val peer = state.peer(cabinet.activePeerId)
    val peerName = peer?.nick ?: cabinet.activePeerId.orEmpty().take(8)

    Column(Modifier.fillMaxSize()) {
        // 头部工具栏
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = LocalAppSpacing.current.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = stringResource(Res.string.action_back))
                }
                Spacer(Modifier.width(LocalAppSpacing.current.xs))
            }
            Icon(painterResource(Res.drawable.ic_hdd), contentDescription = null, Modifier.size(24.dp))
            Spacer(Modifier.width(LocalAppSpacing.current.md10))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.cabinet_peer_title, peerName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.cabinet_peer_item_count, formatCount(cabinet.totalEntries)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 权限徽标在窄面板里是第一个该让位的：它只是一句状态说明，
            // 而右侧三个动作按钮是这一屏唯一的操作入口，挤掉了就没别的路。
            if (paneTier != DesktopWindowSizeClass.Compact) {
                Text(
                    if (cabinet.currentPerm == "write") stringResource(Res.string.cabinet_perm_badge_upload) else stringResource(Res.string.cabinet_perm_badge_readonly),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = LocalAppSpacing.current.lg),
                )
            }
            // 头部动作是原型的 `.ibtn` 方块：`.cbhead` 里 grid / refresh 就是
            // `<button class="ibtn">`（prototype/relay.html:5178-5179），不是裸图标；
            // 它们之间的 10dp 来自 `.cbhead{gap:10px}`（prototype/relay.html:285）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
            ) {
                if (cabinet.currentPerm == "write" && cabinet.activePeerId != null) {
                    TopBarIconAction(Res.drawable.ic_up, stringResource(Res.string.cabinet_upload), onClick = { controller.uploadToCabinet(cabinet.activePeerId) })
                }
                TopBarIconAction(Res.drawable.ic_list, stringResource(Res.string.cabinet_view_list), onClick = { controller.setCabinetViewMode("list") })
                TopBarIconAction(Res.drawable.ic_grid, stringResource(Res.string.cabinet_view_grid), onClick = { controller.setCabinetViewMode("grid") })
                TopBarIconAction(Res.drawable.ic_refresh, null, onClick = { controller.loadCabinetPath(cabinet.currentPath) })
            }
        }
        HorizontalDivider()

        // 面包屑
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    val segments = cabinet.currentPath.split("/").filter { it.isNotEmpty() }
                    val parent = if (segments.size <= 1) "" else segments.dropLast(1).joinToString("/")
                    controller.loadCabinetPath(parent)
                },
                enabled = cabinet.currentPath.isNotEmpty(),
            ) {
                Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = stringResource(Res.string.cabinet_crumbs_up))
            }
            Text(
                stringResource(Res.string.cabinet_crumbs_root),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (cabinet.currentPath.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { controller.loadCabinetPath("") },
            )
            if (cabinet.currentPath.isNotEmpty()) {
                val segments = cabinet.currentPath.split("/").filter { it.isNotEmpty() }
                segments.forEachIndexed { i, seg ->
                    Text(" / ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val isLast = i == segments.lastIndex
                    Text(
                        seg,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable {
                            val subPath = segments.take(i + 1).joinToString("/")
                            controller.loadCabinetPath(subPath)
                        },
                    )
                }
            }
        }
        HorizontalDivider()

        // 内容区
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (cabinet.viewMode == "grid") {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.md),
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    items(cabinet.entries) { entry ->
                        val isSelected = entry.name in cabinet.selectedNames
                        Column(
                            Modifier.clip(RelayShapes.small)
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable {
                                    if (entry.isDir) {
                                        val next = if (cabinet.currentPath.isEmpty()) entry.name else "${cabinet.currentPath}/${entry.name}"
                                        controller.loadCabinetPath(next)
                                    } else {
                                        controller.toggleCabinetSelection(entry.name)
                                    }
                                }
                                .padding(LocalAppSpacing.current.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                if (entry.isDir) painterResource(Res.drawable.ic_folder) else painterResource(Res.drawable.ic_file),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                            Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                // 表格列表
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(Res.string.cabinet_col_name), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            Text(stringResource(Res.string.cabinet_col_size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(90.dp))
                            Text(stringResource(Res.string.cabinet_col_mtime), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
                        }
                    }
                    items(cabinet.entries) { entry ->
                        val isSelected = entry.name in cabinet.selectedNames
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable {
                                    if (entry.isDir) {
                                        val next = if (cabinet.currentPath.isEmpty()) entry.name else "${cabinet.currentPath}/${entry.name}"
                                        controller.loadCabinetPath(next)
                                    } else {
                                        controller.toggleCabinetSelection(entry.name)
                                    }
                                }
                                .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md10),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CabinetSelectionBox(selected = isSelected)
                            Spacer(Modifier.width(LocalAppSpacing.current.sm))
                            Icon(if (entry.isDir) painterResource(Res.drawable.ic_folder) else painterResource(Res.drawable.ic_file), contentDescription = null, Modifier.size(IconSizes.Body))
                            Spacer(Modifier.width(LocalAppSpacing.current.sm))
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (entry.isDir) "—" else formatBytes(entry.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
                            Text(relativeTime(entry.mtime, now), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        // 底部操作栏
        Column(Modifier.fillMaxWidth()) {
            TitleBarDivider()
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = LocalAppSpacing.current.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cabinet.selectedNames.isNotEmpty()) {
                        val selCount = cabinet.selectedNames.size
                        val totalSize = cabinet.entries.filter { it.name in cabinet.selectedNames }.sumOf { it.size }
                        Text(
                            stringResource(Res.string.cabinet_footer_selected, formatCount(selCount), formatBytes(totalSize)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { controller.downloadCabinetEntries(cabinet.selectedNames.toList(), saveAs = true) }) {
                            Text(stringResource(Res.string.cabinet_save_as))
                        }
                        Spacer(Modifier.width(LocalAppSpacing.current.sm))
                        Button(onClick = { controller.downloadCabinetEntries(cabinet.selectedNames.toList(), saveAs = false) }) {
                            Icon(painterResource(Res.drawable.ic_down), contentDescription = null, Modifier.size(IconSizes.Body))
                            Spacer(Modifier.width(LocalAppSpacing.current.sm6))
                            Text(stringResource(Res.string.cabinet_download_count, formatCount(selCount)))
                        }
                    } else {
                        Text(
                            stringResource(Res.string.cabinet_footer_unselected_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 表格行首那个方框：原型 `prototype/relay.html:4068` 的 `cabRow` 第一列 `.cbx`
 * （选中时里面画一个 `check`），尺寸/圆角/描边逐字照 CSS —— `.cbx{width:16px;
 * height:16px;border-radius:5px;border:1.5px solid var(--outline)}`、
 * `.cbx.on{background:var(--primary);border-color:var(--primary)}`。
 *
 * 为什么要有它：整行选中高亮此前已经在（`primaryContainer` 底色），但**没有看得见的
 * 方框** —— 底色变化很容易被当成 hover，方框才是「这一行可以多选」的信号；原型
 * `d23-cabinet` 里那两行未选中项带着空方框，也是靠它才看出表格是可多选的。
 */
@Composable
private fun CabinetSelectionBox(selected: Boolean) {
    val shape = RoundedCornerShape(5.dp)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(LocalAppSpacing.current.lg)
            .clip(shape)
            .then(if (selected) Modifier.background(primary) else Modifier)
            .border(
                width = 1.5.dp,
                color = if (selected) primary else MaterialTheme.colorScheme.outline,
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painterResource(Res.drawable.ic_check_double),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(IconSizes.Badge),
            )
        }
    }
}
