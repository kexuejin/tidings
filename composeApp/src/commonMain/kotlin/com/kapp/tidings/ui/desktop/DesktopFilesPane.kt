// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.domain.transferIdDetailLabel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.ui.SectionCard
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.AppBanner
import com.kapp.tidings.ui.components.SectionHeader
import com.kapp.tidings.ui.components.formatBytes
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.action_clear_search
import com.kapp.tidings.generated.resources.desktop_action_list
import com.kapp.tidings.generated.resources.desktop_action_refresh
import com.kapp.tidings.generated.resources.desktop_files_dir_in
import com.kapp.tidings.generated.resources.desktop_files_dir_out
import com.kapp.tidings.generated.resources.desktop_files_empty
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.desktop_files_empty_hint
import com.kapp.tidings.generated.resources.desktop_files_from_peer
import com.kapp.tidings.generated.resources.desktop_files_info_card
import com.kapp.tidings.generated.resources.desktop_files_jump
import com.kapp.tidings.generated.resources.desktop_files_meta_single
import com.kapp.tidings.generated.resources.desktop_files_no_match_hint
import com.kapp.tidings.generated.resources.desktop_files_open_dir
import com.kapp.tidings.generated.resources.desktop_files_progress
import com.kapp.tidings.generated.resources.desktop_files_row_direction
import com.kapp.tidings.generated.resources.desktop_files_row_progress
import com.kapp.tidings.generated.resources.desktop_files_row_reason
import com.kapp.tidings.generated.resources.desktop_files_row_source
import com.kapp.tidings.generated.resources.desktop_files_row_transfer_id
import com.kapp.tidings.generated.resources.desktop_files_saved_card
import com.kapp.tidings.generated.resources.desktop_files_saved_empty
import com.kapp.tidings.generated.resources.desktop_files_select_hint
import com.kapp.tidings.generated.resources.desktop_files_state_active
import com.kapp.tidings.generated.resources.desktop_files_state_done
import com.kapp.tidings.generated.resources.desktop_files_state_failed
import com.kapp.tidings.generated.resources.desktop_files_state_waiting_me
import com.kapp.tidings.generated.resources.desktop_files_state_waiting_peer
import com.kapp.tidings.generated.resources.desktop_files_to_peer
import com.kapp.tidings.generated.resources.transfer_action_resume
import com.kapp.tidings.generated.resources.desktop_no_match
import com.kapp.tidings.generated.resources.desktop_view_files
import com.kapp.tidings.generated.resources.file_state_declined
import com.kapp.tidings.generated.resources.ic_down
import com.kapp.tidings.generated.resources.ic_up
import com.kapp.tidings.generated.resources.r2_shell_files_all
import com.kapp.tidings.generated.resources.ic_external
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_list
import com.kapp.tidings.generated.resources.ic_refresh
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.TransferState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.ShapeRoles
import com.kapp.tidings.ui.components.AppOutlinedButton

// ------------------------------------------------------------------ 文件视图

private data class FileRow(val message: MessageUi, val title: String, val peerId: String)

@Composable
public fun FilesPane(
    state: RelayUiState,
    query: String,
    onClearQuery: () -> Unit,
    /**
     * 清掉全局 banner。
     *
     * 这里原本传空实现，banner 上的关闭按钮点了没反应。banner 是
     * `RelayUiState.banner` 这一个**单例**字段（见 `RelayController.dismissBanner`），
     * 所以关闭必然是全局的：文件页关掉，会话/节点页同一时刻也不再显示同一条。
     * 想做「每屏各自 dismiss」得把 banner 改成列表，那是 `composeApp` 的改动，
     * 不在本次桌面端的写入范围内。
     */
    onDismissBanner: () -> Unit,
    /**
     * 打开落盘目录。传 `null` 表示本设备没有「打开系统文件管理器」这个能力
     * （手机/平板），此时按钮与空态里的入口都不画 —— 留着只会是点了没反应的控件。
     */
    onOpenFolder: (() -> Unit)?,
    onJumpToConversation: (String) -> Unit,
    onRefresh: () -> Unit,
    now: Long,
    /**
     * 壳传进来的窗口分档；`null` 表示没传，此时按**本面板自己的可用宽度**用
     * [desktopWindowSizeClass] 重新判一次（见下面的 `BoxWithConstraints`）。
     *
     * 两栏还是单栏取决于这块面板有多宽，不是整个窗口有多宽：Expanded 档里
     * `DesktopShell` 已经从左侧切掉导航栏/竖导，剩下真正给文件的宽度才是判断依据。
     * 保留 `null` 是为了让 `DesktopShell.kt` 的现有调用点一行不改也能编译 ——
     * 传不传都得到正确答案。
     */
    sizeClass: DesktopWindowSizeClass? = null,
    /**
     * 冷启动时预置选中的传输（右侧详情）。
     *
     * 与 `DesktopShell.initialDraft` / `initialQuery` 同一条理由：选中项是这一屏的
     * 内部状态，静态帧点不了行，不预置就录不到详情面板 —— 「已完成但 `savedPaths`
     * 为空」那一支的「还没落盘」文案（`desktop_files_saved_empty`）因此一直没有图
     * 证明它渲染得对。生产调用方一律用默认值（不选中）。
     */
    initialTransferId: String? = null,
    onResumeTransfer: ((String) -> Unit)? = null,
    onCancelTransfer: ((String) -> Unit)? = null,
) {
    val rows = remember(state.messages) {
        state.messages.entries
            .flatMap { (peerId, list) -> list.mapNotNull { msg -> msg.file?.let { FileRow(msg, "", peerId) } } }
            .map { it.copy(title = state.convTitle(it.peerId)) }
            .sortedByDescending { it.message.at }
    }
    val shown = remember(rows, query) {
        if (query.isBlank()) rows else rows.filter { it.message.file?.name?.contains(query, ignoreCase = true) == true }
    }
    // 选中项只记 transferId，不记整个 FileRow：传输中的卡片会被 RelayController
    // 反复替换（见 MessageUi 的追加/替换机制），存快照会让详情停在旧状态。
    // 每帧从 rows 现查，所以搜索过滤掉了选中项，选择也不会丢。
    var selectedTransferId by remember { mutableStateOf(initialTransferId) }
    val selected = selectedTransferId?.let { id -> rows.firstOrNull { it.message.file?.transferId == id } }
    val onSelect: (FileRow) -> Unit = { row ->
        val id = row.message.file?.transferId
        if (id != null) selectedTransferId = if (id == selectedTransferId) null else id
    }

    Column(Modifier.fillMaxSize().padding(LocalAppSpacing.current.lg)) {
        state.banner?.let { AppBanner(it, onDismiss = onDismissBanner) }
        // 「文件」+ 打开落盘目录 + 刷新这一行**只在空态画**。
        // 原型把两件事分给了两个屏：
        //   · 有文件时（`screenDFiles()`，`prototype/relay.html:4952-5013`）左栏头部是
        //     `sideFiles(sel)` 的 `.sidehead` —— 「全部文件」+ 计数（`:4723`，CSS `:680-682`），
        //     这一屏全屏**没有**「打开落盘目录 / 刷新」按钮：打开落盘目录在详情工具栏
        //     `.fbar`（`:4960`）里，刷新只属于空态那一屏（`toolbar(view, query, o)`
        //     `:2611-2626` 也没有这两颗）。
        //   · 空态时（`screenDFilesEmpty()`，`:5069-5080`，`side:false`）才是
        //     `.panehead` ＝「文件 + 打开落盘目录 + 刷新」＋整块两轴居中空态。
        // R1 的实现把这一行当常驻头部，于是有文件时既没有「全部文件」计数、又多出一排
        // 原型没有的按钮。
        if (shown.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SectionHeader(stringResource(Res.string.desktop_view_files))
                Spacer(Modifier.weight(1f))
                // onOpenFolder 为 null = 本设备没有「打开系统文件管理器」这个能力：
                // 不画按钮，而不是画一个点了没反应的。刷新是所有平台都有的。
                if (onOpenFolder != null) {
                    ToolbarButton(stringResource(Res.string.desktop_files_open_dir), painterResource(Res.drawable.ic_folder), onOpenFolder)
                    Spacer(Modifier.width(LocalAppSpacing.current.sm))
                }
                ToolbarButton(stringResource(Res.string.desktop_action_refresh), painterResource(Res.drawable.ic_refresh), onRefresh)
            }
            Spacer(Modifier.height(LocalAppSpacing.current.sm))
            // 空态必须给出下一步动作：告诉用户文件从哪来、怎么产生第一份；
            // 搜过但没命中时给的是「清除搜索」，别再让人去翻落盘目录。
            val filtering = query.isNotBlank()
            // 空态在**剩余空间里两轴居中**：原型 `.emptyd{flex:1;min-height:0;display:flex;
            // flex-direction:column;align-items:center;justify-content:center;padding:34px 40px;
            // text-align:center}`（`prototype/relay.html:772-775`），`d09-filesempty` 截图也是居中。
            // 不沿用共用 `EmptyState`（`app/relay/ui/AppStates.kt:23` → `MessageState:87`）：
            // 它按 `fillMaxWidth()` 顶对齐排、没有 textAlign 口子，传 `fillMaxSize()` 只占高度、
            // 内容仍贴左上；而它是全 app 共用的，动它会把手机端多屏空态一起挪走。
            // 写法与 d-search-empty 那支（`DesktopGlobalSearch.kt:171-194`）一致。
            Box(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // `.emptyd .big{font-size:16px;font-weight:700}`（`:774`）
                    Text(
                        text = if (filtering) {
                            stringResource(Res.string.desktop_no_match, query)
                        } else {
                            stringResource(Res.string.desktop_files_empty)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                    // `.emptyd .lede{font-size:12px;color:var(--on-2);margin-top:7px;…;max-width:460px}`（`:775`）
                    Text(
                        text = if (filtering) {
                            stringResource(Res.string.desktop_files_no_match_hint)
                        } else {
                            stringResource(Res.string.desktop_files_empty_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = FilesEmptyLedeMaxWidth),
                    )
                    val action: (() -> Unit)? = if (filtering) onClearQuery else onOpenFolder
                    if (action != null) {
                        Spacer(Modifier.height(LocalAppSpacing.current.lg14))
                        AppOutlinedButton(
                            text = if (filtering) {
                                stringResource(Res.string.action_clear_search)
                            } else {
                                stringResource(Res.string.desktop_files_open_dir)
                            },
                            onClick = action,
                            // 过滤态没有图标（原型那颗是"清除"），非过滤态是"打开目录"
                            leadingIcon = if (filtering) null else Res.drawable.ic_folder,
                        )
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val tier = sizeClass
                    ?: desktopWindowSizeClass(maxWidth.value.toInt(), maxHeight.value.toInt())
                val list: @Composable (Modifier) -> Unit = { listModifier ->
                    // 左栏 = 「全部文件 N」头 + 扁平行列表，对齐原型 `sideFiles(sel)`
                    // （`prototype/relay.html:4722-4735`）与 CSS `.sidehead`/`.sidelist`/`.li`
                    // （`:680-706`）：行里没有卡片外壳、没有行尾按钮、没有进度条。
                    Column(listModifier) {
                        FilesSideHead(count = shown.size)
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(
                                horizontal = LocalAppSpacing.current.sm,
                                vertical = LocalAppSpacing.current.xxs,
                            ),
                            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xxs),
                        ) {
                            items(shown) { row ->
                                // 这一屏的行只从带 file 的消息里聚合出来，`file` 理论上非空；
                                // 取成局部变量既省掉两处 `!!`，也让"理论上"变成显式的一行。
                                val file = row.message.file ?: return@items
                                FileListRow(
                                    file = file,
                                    peerTitle = row.title,
                                    at = row.message.at,
                                    now = now,
                                    selected = file.transferId == selectedTransferId,
                                    onClick = { onSelect(row) },
                                )
                            }
                        }
                    }
                }
                val detail: @Composable (Modifier, Boolean) -> Unit = { detailModifier, compact ->
                    val row = selected
                    val file = row?.message?.file
                    if (file == null) {
                        // 没选中时宽栏留一句提示，窄栏直接不画这一块 ——
                        // 详情区留白会让列表凭空少掉一半高度。
                        if (tier.twoPane) {
                            Box(detailModifier, contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(Res.string.desktop_files_select_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        FileDetailPane(
                            file = file,
                            peerTitle = row.title,
                            peerId = row.peerId,
                            at = row.message.at,
                            now = now,
                            onOpenFolder = onOpenFolder,
                            onJump = { onJumpToConversation(row.peerId) },
                            compact = compact,
                            modifier = detailModifier,
                            onBack = { selectedTransferId = null },
                            // 入站失败的续传出口。生产由 DesktopShell 传
                            // `controller::resumeTransfer` / `controller::cancelTransfer`
                            // （`DesktopShell` 里接 `controller::resumeTransfer` / `controller::cancelTransfer`
                            // 的那两处实参）；帧里传 `{}` 也走同一支。
                            // 注：原来这里写的是行号，DesktopShell 一改行就失效 —— 换成符号名。
                            onResumeTransfer = onResumeTransfer,
                            onCancelTransfer = onCancelTransfer,
                        )
                    }
                }
                when {
                    // Expanded：原型 .split 的左右两栏，列表与详情等宽平分。
                    tier.twoPane -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { list(Modifier.fillMaxSize()) }
                        VerticalDividerLine()
                        Box(Modifier.weight(1f).fillMaxHeight()) { detail(Modifier.fillMaxSize(), false) }
                    }
                    // Medium：竖导已经在壳里占掉 64px，再横着切两栏谁都放不下。
                    // 降级成「列表在上、详情在下」，详情封顶 320dp，剩下的高度全给列表。
                    tier == DesktopWindowSizeClass.Medium -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { list(Modifier.fillMaxSize()) }
                        if (selected != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            detail(Modifier.fillMaxWidth().heightIn(max = 320.dp), true)
                        }
                    }
                    // Compact：单栏，列表与详情互斥（同 DesktopConversationsPane 的做法），
                    // 回列表的入口是详情头里的「列表」按钮。
                    else -> {
                        if (selected == null) list(Modifier.fillMaxSize()) else detail(Modifier.fillMaxSize(), true)
                    }
                }
            }
        }
    }
}

/** 原型 `.emptyd .lede{…max-width:460px}`（`prototype/relay.html:775`）。 */
private val FilesEmptyLedeMaxWidth = 460.dp

/**
 * 文件左栏的头部：「全部文件」＋计数。
 *
 * 原型 `sideFiles(sel)` 的 `.sidehead`（markup `prototype/relay.html:4723`、CSS `:680-682`）：
 * `padding:11px 13px 6px`、`11px/600`、色 `--on-2`、计数靠右且字重 500。
 * 间距/字号照会话侧栏那条同族头部对齐（`DesktopConversationsPane.kt` 的会话侧栏头部），
 * 那一条是 R1/R2 已接受过的写法；**不画分隔线** —— 原型 `.sidehead` 只有 padding，
 * 分隔感来自 `.li` 自己的选中态阴影。
 */
@Composable
private fun FilesSideHead(count: Int) {
    val spacing = LocalAppSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = spacing.lg14,
            vertical = spacing.sm6,
        ),
    ) {
        Text(
            text = stringResource(Res.string.r2_shell_files_all),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 原型的 `.li .fi2`（`:696-697`）是 32×32、圆角 9px。 */
private val FileListIconSize = 32.dp

/** 原型的 `.li{min-height:52px}`（`:688`）。 */
private val FileListRowMinHeight = 52.dp

/**
 * 文件左栏的一行 —— 原型 `sideFiles(sel)` 的 `.li`（markup `prototype/relay.html:4722-4735`，
 * CSS `.li` `:688-706`）。扁平行，**没有卡片外壳、没有行尾按钮、没有进度条**：
 * 进度/落盘路径/项数都在右栏详情里（`.fdh` + `.fbar` + 两张卡，`:4952-5013`）。
 *
 * @param peerTitle 行第二段「收自 X / 发给 X」里的对端名（原型 `.t2` 的 `${f.who}`）。
 * @param mine `FileCardUi.mine` —— 原型按 `f.dir === 'in' ? 'down' : 'up'` 取箭头图标。
 */
@Composable
private fun FileListRow(
    file: FileCardUi,
    peerTitle: String,
    at: Long,
    now: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalAppSpacing.current
    Surface(
        // 选中态与原型 `.li[aria-selected="true"]{background:var(--surface);
        // box-shadow:0 0 0 1px var(--hair)}`（`:690`）同构：白底 + 1px hairline。
        // `MaterialTheme.shapes.medium` = 12dp，正是 `.li{border-radius:12px}`（`:688`）。
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = FileListRowMinHeight).clickable(onClick = onClick),
    ) {
        // 已落盘的文件行本身就是拖拽源：按住这一行拖到访达/资源管理器/编辑器就能带走，
        // 语义是复制（startDrag 用 ACTION_COPY）。路径没落地或被删过的时候
        // desktopFileDragSource 会原样返回，不挂任何手势。挂在内容 Row 上（与 R1 一致），
        // 免得和整行的 click 抢手势。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md10),
            modifier = Modifier
                .padding(horizontal = spacing.sm, vertical = spacing.sm6)
                .desktopFileDragSource(file.savedPaths),
        ) {
            Box(
                modifier = Modifier
                    .size(FileListIconSize)
                    .background(
                        // `.li .fi2{…background:var(--surface-3);color:var(--on-2)}`（`:715-716`）。
                        // 注意：`.fi2.act`（`:718`）虽然存在，但 `sideFiles()` 的行模板
                        // `<div class="fi2">`（`:4848`）**从不加 `act`** —— `act` 只用在另一份列表
                        // （工作区/`AGENTS` 那族的 `<div class="fi2${e.id===sel?' act':''}">`，`:5301`）。
                        // 所以文件行永远是灰底，正在传的那一行也不例外；早先按 CSS 猜成主色底是错的。
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        // 圆角 9px 是原型逐字值（`:696`，同族 `.filecard .fi` `:1120`），
                        // 不是档位值 —— 用量具第五十二洞修好后的读数对齐。
                        shape = ShapeRoles.Avatar,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (file.mine) Res.drawable.ic_up else Res.drawable.ic_down),
                    contentDescription = null,
                    // `.li .fi2 svg{width:17px;height:17px}`（`:717`）⇒ 落 `IconSizes.Label`(16dp)
                    // 这一档（原型 17px 与 16dp 差 1，属就近 snap）。
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizes.Label),
                )
            }
            Column(Modifier.weight(1f)) {
                // `.t1{font-size:14px;font-weight:600}`（`:701`）
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // `.t2{font-size:12px;color:var(--on-2)}`（`:702`）
                Text(
                    text = stringResource(
                        if (file.mine) Res.string.desktop_files_to_peer else Res.string.desktop_files_from_peer,
                        peerTitle,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // `.t3{font-size:11px;color:var(--on-2);opacity:.85}`（`:703`）＝ `${f.sz} · ${f.t} · 状态`。
                // 状态那一格照原型 `FSTATE_TXT`（`:4841-4843`）是**纯状态名**（`'active':'传输中'`）；
                // 带百分数的那条进度读数在详情卡的「进度」行（`desktop_files_row_progress`）。
                // 列表行**不印「N 个文件」**：项数只出现在右栏的传输信息卡（`transfer_folder_count`）。
                Text(
                    text = stringResource(
                        Res.string.desktop_files_meta_single,
                        formatBytes(file.size),
                        relativeTime(at, now),
                        stringResource(file.stateLabel()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 单个文件的详情面板（原型稿 `filesPane()` 的右栏，`.dhead` + `.dbody` 两段）。
 *
 * 桌面侧文案，后续如需多语言再迁到资源。
 *
 * 只用 [FileCardUi] 里**真实存在**的字段渲染，不编造原型上有、数据层没有的值：
 * - 原型的「校验」卡片（SHA-256 摘要、校验时机、逐字节一致结论）——
 *   `FileCardUi` 没有摘要字段，`:core` 也没有把校验结果带回 UI，本次整块不画。
 * - 原型的「传输轨迹」（通道 UDP→TCP 兜底、按 seq+包内哈希去重）——
 *   这些是协议内部轨迹，`RelayUiState` 没有暴露，本次用可给的替代项（方向 / 传输 ID /
 *   进度 / 失败原因）填一张等价的表。
 * - 原型的「另存为…」动作——桌面端只有「打开落盘目录」这一条既有出口，
 *   没有另存为的实现，不放一个点了没反应的按钮。
 * - 缩略图 / 预览图——`FileCardUi` 没有图片字段，不画。
 */
@Composable
public fun FileDetailPane(
    file: FileCardUi,
    peerTitle: String,
    peerId: String,
    at: Long,
    now: Long,
    onOpenFolder: (() -> Unit)?,
    onJump: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    // 入站失败的续传出口（d-resume）：必传才画「继续 / 取消」那一行。生产由 FilesPane 从
    // `DesktopShell` 接过来（`controller::resumeTransfer` / `::cancelTransfer`）。
    onResumeTransfer: ((String) -> Unit)? = null,
    onCancelTransfer: ((String) -> Unit)? = null,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.md),
        ) {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_folder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(IconSizes.Body),
                )
            }
            Spacer(Modifier.width(LocalAppSpacing.current.md))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${if (file.mine) stringResource(Res.string.desktop_files_to_peer, peerTitle) else stringResource(Res.string.desktop_files_from_peer, peerTitle)} · ${formatBytes(file.size)} · ${relativeTime(at, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(LocalAppSpacing.current.sm))
            // 纯状态名，不带百分数：原型 `screenDFiles`（`prototype/relay.html:5076-5081`）
            // 的详情头那颗 chip 逐字 `<span class="chip on">已完成</span>`；百分数只出现在
            // 「进度」那一行（`desktop_files_row_progress`）。
            HeadChip(stringResource(file.stateLabel()))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.md),
        ) {
            // 「列表」只有单栏档位用得上：双栏时列表一直在旁边，这个按钮只会占掉宽度。
            if (compact && onBack != null) {
                ToolbarButton(stringResource(Res.string.desktop_action_list), painterResource(Res.drawable.ic_list), onBack)
                Spacer(Modifier.width(LocalAppSpacing.current.sm))
            }
            // onOpenFolder 为 null = 本设备没有「打开系统文件管理器」的能力，整块不画。
            if (onOpenFolder != null) {
                ToolbarButton(
                    label = stringResource(Res.string.desktop_files_open_dir),
                    painter = painterResource(Res.drawable.ic_folder),
                    onClick = onOpenFolder,
                    // 保留灰按钮而不是隐藏：动作存在，只是这份文件还没落到磁盘上。
                    enabled = file.savedPaths.isNotEmpty(),
                )
                Spacer(Modifier.width(LocalAppSpacing.current.sm))
            }
            ToolbarButton(stringResource(Res.string.desktop_files_jump), painterResource(Res.drawable.ic_external), onJump)
        }
        // 原型这里是三张卡（校验 / 落盘 / 传输轨迹）。「校验」整块没有数据（见上），
        // 而头部已经把文件名、大小、时间、状态说过一遍：头部说「是什么」，
        // 下面两张卡只说「落到哪儿」和「怎么传的」。
        SectionCard(stringResource(Res.string.desktop_files_saved_card)) {
            if (file.savedPaths.isEmpty()) {
                Text(
                    stringResource(Res.string.desktop_files_saved_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                file.savedPaths.forEach { path ->
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(LocalAppSpacing.current.xs))
                }
            }
        }
        Spacer(Modifier.height(LocalAppSpacing.current.sm))
        SectionCard(stringResource(Res.string.desktop_files_info_card)) {
            FileDetailRow(stringResource(Res.string.desktop_files_row_source), peerId.take(8), monospace = true)
            FileDetailRow(stringResource(Res.string.desktop_files_row_direction), if (file.mine) stringResource(Res.string.desktop_files_dir_out) else stringResource(Res.string.desktop_files_dir_in))
            // 内部临时键（乐观插入的 pending-…）不上屏：它不是用户认得出的传输编号。
            transferIdDetailLabel(file.transferId)?.let { shown ->
                FileDetailRow(stringResource(Res.string.desktop_files_row_transfer_id), shown, monospace = true)
            }
            FileDetailRow(stringResource(Res.string.desktop_files_row_progress), stringResource(Res.string.desktop_files_progress, "${(file.fraction * 100).toInt()}%", formatBytes(file.done), formatBytes(file.size)))
            // FileCardUi 来自 composeApp 模块，reason 是跨模块的公开属性，
            // 这里不能靠 if (reason != null) 让编译器 smart cast，直接取一次可空值。
            file.reason?.let { FileDetailRow(stringResource(Res.string.desktop_files_row_reason), it) }
            if (file.state == TransferState.Active || file.state == TransferState.Offered) {
                Spacer(Modifier.height(LocalAppSpacing.current.sm))
                LinearProgressIndicator(progress = { file.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
            // 入站失败的续传出口（d-resume）。原型 `screenDResume()`
            // （`prototype/relay.html:4426-4444`）里这张失败卡的操作行是
            // `<div class="frow"><button class="btn sm pri">继续</button>
            //  <button class="btn sm">重新下载</button></div>`，并在同屏 annot 里逐字写明
            // 「**UI 上没有任何「可续传」提示**，只有这两个按钮」；
            // 出站那条没有「继续」（`handleResumeTransfer` 对 `card.mine` 直接 return，
            // 对齐 `files.ts:836-840` 的 `retryable 强判 direction === 'in'`），所以这里只画入站。
            // 「重新下载」（丢掉 .part 从头拉）在本端没有对应 API ⇒ 不画；
            // 那一步的替代是「取消」（`cancelTransfer`，会清掉这条传输与会话里的卡）。
            if (file.state == TransferState.Failed && !file.mine && onResumeTransfer != null) {
                Spacer(Modifier.height(LocalAppSpacing.current.sm))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppButton(
                        text = stringResource(Res.string.transfer_action_resume),
                        onClick = { onResumeTransfer(file.transferId) },
                    )
                    if (onCancelTransfer != null) {
                        AppTextButton(
                            text = stringResource(Res.string.common_cancel),
                            onClick = { onCancelTransfer(file.transferId) },
                        )
                    }
                }
            }
        }
    }
}

/** 详情里的「标签 / 值」一行。值用等宽字体读路径和 ID 更好认。 */
@Composable
private fun FileDetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 状态那一句话对应哪条文案 —— **一律是纯状态名，不带百分数**。
 *
 * 原型 `FSTATE_TXT`（`prototype/relay.html:4841-4843`）就是 `{... 'active':'传输中', ...}`；
 * `%` 只在进度那一行（`desktop_files_row_progress`）与消息气泡里那张卡的 meta 上出现。
 * 失败原因（运行时数据，装不进 [StringResource]）画在详情卡的「说明」行里，不并进这颗 chip。
 */
public fun FileCardUi.stateLabel(): StringResource = when (state) {
    TransferState.Offered -> if (mine) Res.string.desktop_files_state_waiting_peer else Res.string.desktop_files_state_waiting_me
    TransferState.Active -> Res.string.desktop_files_state_active
    TransferState.Completed -> Res.string.desktop_files_state_done
    TransferState.Failed -> Res.string.desktop_files_state_failed
    TransferState.Declined -> Res.string.file_state_declined
}

