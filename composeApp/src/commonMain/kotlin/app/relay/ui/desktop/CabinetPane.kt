// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.feature.cabinet.MineCabinetView
import app.relay.feature.cabinet.PeerCabinetView
import app.relay.core.nowMillis
import app.relay.ui.ContentWidths
import app.relay.generated.resources.Res
import app.relay.generated.resources.cabinet_mine_title
import app.relay.generated.resources.cabinet_mode_off
import app.relay.generated.resources.cabinet_mode_read
import app.relay.generated.resources.cabinet_peer_title
import app.relay.generated.resources.cabinet_peers_title
import app.relay.generated.resources.cabinet_perm_badge_upload
import app.relay.generated.resources.cabinet_side_title
import app.relay.generated.resources.ic_hdd
import app.relay.ui.components.PeerAvatar
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
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
    now: Long = nowMillis(),
    sizeClass: DesktopWindowSizeClass? = null,
    /**
     * 「在文件管理器里打开共享目录」的真实动作；null = 宿主给不出这条出口，
     * 那颗按钮**整颗不画**（与移动端 `CabinetScreen` 同一个判据：能力缺失 = 不画，
     * 不是"点了没反应"）。
     */
    onOpenShareRoot: (() -> Unit)? = null,
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
                    // 我的文件柜设置面板 —— **手机与桌面共用同一个实现**
                    // （`MineCabinetView`）。桌面这两栏里它只多一个"回列表"入口，
                    // 由 `onBack` 参数化；其余四张卡、共享目录、权限三档、单独授权、
                    // 最近有人放进来，全部是同一份代码。
                    MineCabinetView(
                        state = state,
                        onSetShareRoot = { controller.setShareRoot(it) },
                        onPickShareRoot = { controller.pickShareRoot() },
                        onSetShareMode = { controller.setShareMode(it) },
                        onRemoveShareGrant = { controller.removeShareGrant(it) },
                        onSelectPeer = { controller.selectCabinetPeer(it) },
                        onOpenShareRoot = onOpenShareRoot,
                        onBack = onBack,
                        now = now,
                    )
                } else {
                    // 同事文件柜浏览面板 —— **手机与桌面共用同一个实现**（`PeerCabinetView`）。
                    // 桌面这两栏里它只多一个"回列表"入口（`onBack`）；面包屑、选中框、
                    // 上传/下载/权限、空态全部是同一份代码。
                    PeerCabinetView(
                        state = state,
                        onBack = onBack,
                        onLoadPath = { controller.loadCabinetPath(it) },
                        onToggleSelection = { controller.toggleCabinetSelection(it) },
                        onDownloadEntries = { names, saveAs -> controller.downloadCabinetEntries(names, saveAs) },
                        onUploadToCabinet = { controller.uploadToCabinet(it) },
                        now = now,
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
