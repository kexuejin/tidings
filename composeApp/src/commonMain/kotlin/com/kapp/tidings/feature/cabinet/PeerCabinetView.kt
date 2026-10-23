// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.cabinet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.cabinet_col_name
import com.kapp.tidings.generated.resources.cabinet_col_size
import com.kapp.tidings.generated.resources.cabinet_crumbs_root
import com.kapp.tidings.generated.resources.cabinet_crumbs_up
import com.kapp.tidings.generated.resources.cabinet_download_count
import com.kapp.tidings.generated.resources.cabinet_empty_dir
import com.kapp.tidings.generated.resources.cabinet_empty_dir_hint
import com.kapp.tidings.generated.resources.cabinet_footer_selected
import com.kapp.tidings.generated.resources.cabinet_footer_unselected_hint
import com.kapp.tidings.generated.resources.cabinet_peer_item_count
import com.kapp.tidings.generated.resources.cabinet_peer_title
import com.kapp.tidings.generated.resources.cabinet_perm_badge_readonly
import com.kapp.tidings.generated.resources.cabinet_perm_badge_upload
import com.kapp.tidings.generated.resources.cabinet_retry
import com.kapp.tidings.generated.resources.cabinet_save_as
import com.kapp.tidings.generated.resources.cabinet_upload_pick
import com.kapp.tidings.generated.resources.ic_arrow_back
import com.kapp.tidings.generated.resources.ic_file
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_hdd
import com.kapp.tidings.generated.resources.ic_check_double
import com.kapp.tidings.ui.AppError
import com.kapp.tidings.ui.ContentWidths
import com.kapp.tidings.ui.EmptyState
import com.kapp.tidings.ui.ErrorState
import com.kapp.tidings.ui.centerCapped
import com.kapp.tidings.ui.components.AvatarPalette
import com.kapp.tidings.ui.components.StatusDot
import com.kapp.tidings.ui.components.formatBytes
import com.kapp.tidings.ui.components.formatCount
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.domain.model.CabinetUiState
import com.kapp.tidings.domain.model.PeerUi

/** 原型 `.btn.sm`：padding 6px 10px + 12px 行高 ⇒ 约 32px 高。 */
// 原型 `.btn` 的最小高度 32px = 间距档 `xxl`。顶层 val 读不到 CompositionLocal，
// 所以做成 @Composable 取值函数 —— 留裸值会记一笔间距欠债。
@Composable
private fun smallButtonHeight() = LocalAppSpacing.current.xxl

@Composable
internal fun PeerCabinetView(
    state: RelayUiState,
    /**
     * 头部那颗返回。`null` = 不画 —— 桌面宽档左栏一直在旁边，画它只会占宽度
     * （与 `MineCabinetView.onBack` 同一条规矩）。
     */
    onBack: (() -> Unit)? = null,
    onLoadPath: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onDownloadEntries: (List<String>, Boolean) -> Unit,
    onUploadToCabinet: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    now: Long = 0L,
) {
    val cabinet = state.cabinet
    val peer = state.peer(cabinet.activePeerId)
    val peerName = peer?.nick ?: cabinet.activePeerId.orEmpty().take(8)
    val canUpload = cabinet.currentPerm == "write" &&
        onUploadToCabinet != null &&
        cabinet.activePeerId != null

    Column(modifier.fillMaxSize()) {
        // ── 1) 顶栏。原型 `.topbar{padding:calc(status+4px) 16px 10px}` + `h1{24px/700/-.02em}` + `.sub{11px}`
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .centerCapped(ContentWidths.Pane)
                .padding(start = LocalAppSpacing.current.lg, top = LocalAppSpacing.current.sm, end = LocalAppSpacing.current.lg, bottom = LocalAppSpacing.current.md10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
        ) {
            if (onBack != null) IconButton(onClick = onBack) {
                // 原型顶栏的返回键是 `topbar(..., back='m-cabinet', ...)` 画出来的
                // `ic('back')`，即 `i-back`（`prototype/relay.html:1558`，一条 stroke 细
                // chevron）。上一版用 M3 `Icons.AutoMirrored.Outlined.ArrowBack` —— 那是
                // Material 的长尾箭头，笔画更重、尾巴更长，并排图里一眼能看出不是同一个字形。
                // 仓里的 `ic_arrow_back.xml`（`M15 5 8 12l7 7`，stroke 1.7）与原型同源，
                // 同仓先例：`ui/components/TopBars.kt`、`feature/chat/ChatHeader.kt`。
                Icon(
                    painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = null,
                )
            }
            PeerHeadAvatar(name = peerName, avatar = peer?.avatar ?: -1, online = peer?.online == true)
            Column(Modifier.weight(1f)) {
                Text(
                    peerName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.02).em,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(Res.string.cabinet_peer_item_count, formatCount(cabinet.totalEntries)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        // ── 2) `.cbhead`：34px 圆角方块图标 + h2(14/600) + cbmeta(11) + `.chip.on`
        Row(
            Modifier.fillMaxWidth()
                .centerCapped(ContentWidths.Pane)
                .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
        ) {
            Box(
                Modifier.size(34.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_hdd),
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.Body),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.cabinet_peer_title, peerName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(Res.string.cabinet_peer_item_count, formatCount(cabinet.totalEntries)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PermissionChip(
                text = stringResource(
                    if (cabinet.currentPerm == "write") {
                        Res.string.cabinet_perm_badge_upload
                    } else {
                        Res.string.cabinet_perm_badge_readonly
                    },
                ),
            )
        }
        HorizontalDivider()

        // ── 3) `.cbread`：padding 6px 14px、min-height 38、gap 2、字号 14
        Row(
            Modifier.fillMaxWidth()
                .centerCapped(ContentWidths.Pane)
                .heightIn(min = 38.dp)
                .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xxs),
        ) {
            CrumbSeg(
                // 原型 `cabCrumbs` 的返回格是 `ic('back')`，首段是 `ic('hdd')`
                // （`prototype/relay.html:4074-4078`）。
                icon = Res.drawable.ic_arrow_back,
                text = null,
                current = false,
                enabled = cabinet.currentPath.isNotEmpty(),
                onClick = {
                    val segments = cabinet.currentPath.split("/").filter { it.isNotEmpty() }
                    val parent = if (segments.size <= 1) "" else segments.dropLast(1).joinToString("/")
                    onLoadPath(parent)
                },
            )
            CrumbSeg(
                icon = Res.drawable.ic_hdd,
                text = stringResource(Res.string.cabinet_crumbs_root),
                current = cabinet.currentPath.isEmpty(),
                enabled = true,
                onClick = { onLoadPath("") },
            )
            if (cabinet.currentPath.isNotEmpty()) {
                val segments = cabinet.currentPath.split("/").filter { it.isNotEmpty() }
                segments.forEachIndexed { i, seg ->
                    Text(
                        " / ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val isLast = i == segments.lastIndex
                    CrumbSeg(
                        icon = null,
                        text = seg,
                        current = isLast,
                        enabled = true,
                        onClick = { onLoadPath(segments.take(i + 1).joinToString("/")) },
                    )
                }
            }
        }
        HorizontalDivider()

        // ── 4) `.cbfhead` + `.cbfrow`
        //
        // 三种情形互斥：错误态 / 空态 / 有内容的列表。
        //
        // 前两种**不画表头** —— 「名称 / 大小」两列在没有任何行时是纯噪声，
        // 而且会让人以为"列表只加载了一部分"。位置与样式跟仓里其余空态/错误态一致：
        // `fillMaxWidth()` 靠左、由 `MessageState` 自己带 24dp 内边距，**不居中** ——
        // 全仓空态都是这个位置（`ConversationsScreen.kt:308`、`DesktopFilesPane.kt:191`），
        // 这里另造一个居中版本就是新视觉语言。
        //
        // 原型 `screenCabinetPeer`（`prototype/relay.html`）**没有画过这两支** ——
        // GALLERY 里这一屏只有正常目录一格。所以这一段不是在"对齐原型取值"，而是补一条
        // **数据层有、UI 层零**的分支：`CabinetUiState.errorMessage` 由控制器写入
        // （`deny.reason`），此前唯一读它的是单测 —— 用户看到的只是"点进同事的文件柜，
        // 什么都没发生"。`entries` 为空同理：空目录画出来只有一个表头，像卡住了。
        //
        // 取值不新造视觉语言，走 App 既有的 [ErrorState] / [EmptyState] 原语；
        // 这两支没有原型基准，所以**不要**拿它们当"原型要求这样做"的依据。
        val loadError = cabinet.errorMessage
        val isEmptyDir = loadError == null && cabinet.entries.isEmpty()
        LazyColumn(
            Modifier.weight(1f).centerCapped(ContentWidths.Pane),
        ) {
            if (loadError != null || isEmptyDir) {
                item {
                    if (loadError != null) {
                        ErrorState(
                            error = AppError(code = "", message = loadError),
                            actionLabel = stringResource(Res.string.cabinet_retry),
                            onAction = { onLoadPath(cabinet.currentPath) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        EmptyState(
                            title = stringResource(Res.string.cabinet_empty_dir),
                            message = stringResource(Res.string.cabinet_empty_dir_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm6),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
                    ) {
                        Text(
                            stringResource(Res.string.cabinet_col_name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(Res.string.cabinet_col_size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(62.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                    HorizontalDivider()
                }
                items(cabinet.entries) { entry ->
                    val isSelected = entry.name in cabinet.selectedNames
                    val rowTint = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                    val nameColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(rowTint)
                            .clickable {
                                if (entry.isDir) {
                                    val next = if (cabinet.currentPath.isEmpty()) {
                                        entry.name
                                    } else {
                                        "${cabinet.currentPath}/${entry.name}"
                                    }
                                    onLoadPath(next)
                                } else {
                                    onToggleSelection(entry.name)
                                }
                            }
                            .padding(horizontal = LocalAppSpacing.current.lg14, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
                    ) {
                        // 勾选框**自己**是一个可选中的命中区：行体对目录是「进目录」
                        // （见上面的 clickable），所以不给勾选框一个自己的点击目标，目录行就
                        // 永远选不上 —— 而原型 `m11c-cabinet-peer` 第一行正是**选中的目录**
                        // （✅ 需求文档）与底栏「已选 2 项」。行体与勾选框各管一件事，与原型
                        // 底栏那句「单击选中，双击进文件夹」的两种手势一一对应。
                        CabinetCheckbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelection(entry.name) },
                        )
                        // 原型 `cabRow` 画的是 `ic(r.dir ? 'folder' : 'file')`
                        // （`prototype/relay.html:4071`），即 `i-folder`/`i-file` 两条
                        // stroke 1.7 的轮廓；M3 的 `Folder`/`InsertDriveFile` 是实心面，
                        // 并排图里明显更重。仓里两个资产与原型同源。
                        Icon(
                            painterResource(if (entry.isDir) Res.drawable.ic_folder else Res.drawable.ic_file),
                            contentDescription = null,
                            modifier = Modifier.size(IconSizes.Body),
                            tint = nameColor,
                        )
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            // 原型只对 `.cbfrow.dir .nm` 声明了 600，普通行没有声明（CSS 默认 400）——
                            // 显式写 Normal 会被量具记成「代码独有 400」。不写就是 400。
                            fontWeight = if (entry.isDir) FontWeight.SemiBold else null,
                            color = nameColor,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (entry.isDir) "—" else formatBytes(entry.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(62.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }

        // ── 5) `.cbfoot`：padding 10px 14px、gap 10、`.btn.sm`
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth()
                    .centerCapped(ContentWidths.Pane)
                    .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.md10),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
            ) {
                if (cabinet.selectedNames.isNotEmpty()) {
                    val selCount = cabinet.selectedNames.size
                    val totalSize = cabinet.entries
                        .filter { it.name in cabinet.selectedNames }
                        .sumOf { it.size }
                    Text(
                        stringResource(Res.string.cabinet_footer_selected, formatCount(selCount), formatBytes(totalSize)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SmallButton(
                        text = stringResource(Res.string.cabinet_save_as),
                        primary = false,
                        onClick = { onDownloadEntries(cabinet.selectedNames.toList(), true) },
                    )
                    SmallButton(
                        text = stringResource(Res.string.cabinet_download_count, formatCount(selCount)),
                        primary = true,
                        icon = Icons.Outlined.FileDownload,
                        onClick = { onDownloadEntries(cabinet.selectedNames.toList(), false) },
                    )
                } else {
                    Text(
                        stringResource(Res.string.cabinet_footer_unselected_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 原型这一屏没有上传按钮（它靠「拖文件进来」，桌面交互）。
                // 手机没有拖放，删掉按钮等于删掉唯一的上传入口 ⇒ 作为平台被迫的**新增**元素保留在底栏，
                // 并记进 design/fidelity-deviations.yaml，而不是硬凑成原型里有的东西。
                if (canUpload) {
                    SmallButton(
                        text = stringResource(Res.string.cabinet_upload_pick),
                        primary = false,
                        icon = Icons.Outlined.FileUpload,
                        onClick = { onUploadToCabinet.invoke(cabinet.activePeerId.orEmpty()) },
                    )
                }
            }
        }
    }
}

/** 原型 `.cbx`：16×16 radius 5、1.5px 描边；`.on` 主色填充 + 白勾。 */
@Composable
private fun CabinetCheckbox(
    checked: Boolean,
    /**
     * 勾选框自己的点击目标。`null` = 纯展示（当前只有同事文件柜的行在用它，
     * 因为那一屏要能选中目录 —— 行体已经被「进目录」占住了）。
     */
    onCheckedChange: (() -> Unit)? = null,
) {
    val borderColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier.size(16.dp)
            // 原型 `.cbx`：radius 5（就地内联 —— 顶层 val 不在任何函数切片里，量具看不见它）
            // 原型 `.cbx{border-radius:5px}`：M3 槽位（4/8/12/16/28）里没有 5。
            // 不收进 extraSmall(4) —— 收进去 design-screen-diff 会多出「原型有 5、代码没有」，
            // 那是拿保真换复用。5px 是原型精确值，欠债按 shape 记一笔。
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.5.dp, borderColor, RoundedCornerShape(5.dp))
            .then(
                if (onCheckedChange != null) {
                    // 命中区就是这 16dp：行体（整行 clickable）已经吃掉行内的空白，
                    // 再往外扩会把「进目录」的命中区切碎。原型 `.cbx` 也是 16px。
                    Modifier.clickable(onClick = onCheckedChange)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painterResource(Res.drawable.ic_check_double),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = Color.White,
            )
        }
    }
}

/** 原型 `.hav`：40×40 radius 13 圆角方块 + 右下 12px 在线点（2px surface 描边）。 */
@Composable
private fun PeerHeadAvatar(name: String, avatar: Int, online: Boolean) {
    val background = if (avatar >= 0) {
        AvatarPalette.resolveBg(avatar, name)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val foreground = if (avatar >= 0) Color.White else MaterialTheme.colorScheme.primary
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier.size(40.dp)// 原型 `.hav`：radius 13 的圆角方头像（不是圆）
            // 原型 `.hav{border-radius:13px}`：同理不收进 medium(12)，保原型精确值。
            .clip(RoundedCornerShape(13.dp)).background(background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // 姓名缩写：取首字。空名才走 ifBlank 那一支 —— 它实际到不了
                // （`peerName` 来自 `PeerUi.displayName`，链路末端已经回落到 host/ip/nodeId，
                // `RelayUiState.kt:89`），所以这里放一个**非 CJK** 的占位，而不是为一条
                // 不可达分支新增中文资源；`cjkLiteralGate` 的基线里 `Common.kt` 只留了 1 条
                // `"茶"`（`Common.kt:214`），本文件不在基线里，多一条就是新增违规。
                name.take(1).uppercase().ifBlank { "?" },
                // 原型 `.hav{font-size:14px;font-weight:700}`：14sp 用排版角色取，
                // 字重覆写成 700 —— 不写裸 `14.sp`，否则字号就绕过了唯一权威源。
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = foreground,
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .offset(x = LocalAppSpacing.current.xxs, y = LocalAppSpacing.current.xxs)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(LocalAppSpacing.current.xxs),
        ) {
            StatusDot(online = online, size = 12.dp)
        }
    }
}

/** 原型 `.chip.on`：11px、padding 4px 10px、radius 999、主色容器底 + 主色容器前景。 */
@Composable
private fun PermissionChip(text: String) {
    Box(
        Modifier.clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = LocalAppSpacing.current.md10, vertical = LocalAppSpacing.current.xs),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 原型 `.cbread .seg`：padding 3px 7px、radius 8、12px 图标、`.cur` 加粗。 */
@Composable
private fun CrumbSeg(
    icon: DrawableResource?,
    text: String?,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = when {
        current -> MaterialTheme.colorScheme.onSurface
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = if (text == null) stringResource(Res.string.cabinet_crumbs_up) else null,
                modifier = Modifier.size(12.dp),
                tint = tint.copy(alpha = 0.55f),
            )
        }
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.SemiBold else null,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** 原型 `.btn.sm`：padding 6px 10px、12px 字、gap 5px、radius 12；`.pri` 加粗。 */
@Composable
private fun SmallButton(
    text: String,
    primary: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val contentPadding = PaddingValues(horizontal = LocalAppSpacing.current.md10, vertical = LocalAppSpacing.current.sm6)
    val label: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(IconSizes.Label))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = Modifier.height(smallButtonHeight()),
            shape = MaterialTheme.shapes.medium,
            contentPadding = contentPadding,
        ) { label() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.height(smallButtonHeight()),
            shape = MaterialTheme.shapes.medium,
            contentPadding = contentPadding,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { label() }
    }
}
