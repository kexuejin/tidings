// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kapp.tidings.domain.HistorySearchFilter
import com.kapp.tidings.domain.HistorySearchKind
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.feature.chat.CalendarGrid
import com.kapp.tidings.feature.chat.debouncedHistorySearch
import com.kapp.tidings.feature.chat.HistoryHitItem
import com.kapp.tidings.feature.chat.formatDateLabel
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_close
import com.kapp.tidings.generated.resources.common_offline
import com.kapp.tidings.generated.resources.common_online
import com.kapp.tidings.generated.resources.hist_date_clear
import com.kapp.tidings.generated.resources.hist_filter_all
import com.kapp.tidings.generated.resources.hist_filter_date_all
import com.kapp.tidings.generated.resources.hist_filter_date_range
import com.kapp.tidings.generated.resources.hist_filter_file
import com.kapp.tidings.generated.resources.hist_filter_image
import com.kapp.tidings.generated.resources.hist_search_clear_filter
import com.kapp.tidings.generated.resources.hist_search_empty
import com.kapp.tidings.generated.resources.hist_search_empty_hint
import com.kapp.tidings.generated.resources.hist_search_filter_date
import com.kapp.tidings.generated.resources.hist_search_filter_type
import com.kapp.tidings.generated.resources.hist_search_footer_hint
import com.kapp.tidings.generated.resources.hist_search_input_hint
import com.kapp.tidings.generated.resources.hist_search_results_count
import com.kapp.tidings.generated.resources.hist_search_results_section
import com.kapp.tidings.generated.resources.r2_shell_hist_keyword
import com.kapp.tidings.generated.resources.r2_shell_hist_title
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.ui.theme.SurfaceTiers

/**
 * 桌面端会话内历史搜索弹窗（原型 d-histsearch）。
 *
 * 居中 modal dialog：宽 680dp、高 min(560dp, 可用高)，外层 16% 黑遮罩；
 * 左栏 218dp 条件区（关键词、类型、月历、清空筛选），右栏结果区，页脚跨两栏。
 *
 * 版式出处逐条对 `prototype/relay.html`：
 * [`.hsovl`]`:1009` / [`.hsdlg`]`:1011`（680×560）/ [`.hsdh`]`:1015`（头部 13/15/12）/
 * [`.hsside`]`:1021`（218px、`--surface-2`、内部 gap 9px、padding 10px、`overflow-y:auto`）/
 * [`.seg`]`:345`（灰轨道 3px 内边距、选中段白底 8px 圆角 600 字重）/
 * [`.hsside>.btn`]`:1039`（虚线描边、整宽）与 [`.hsfoot`]`:1004`（`grid-column:1/-1`）。
 */
@Composable
public fun DesktopHistorySearchDialog(
    peer: PeerUi,
    onDismiss: () -> Unit,
    onSearch: (query: String, filter: HistorySearchFilter) -> List<MessageSearchResult>,
    onSelectHit: (MessageSearchResult) -> Unit,
    initialQuery: String = "",
    /**
     * 截图接缝：`d-histsearch` 那一屏的左栏画的是**已经选好的一段日期**
     * （原型 `screenDHistSearch` 写死「10月8日 至 10月10日」，`prototype/relay.html:5025`），
     * 而日期段原本只能在帧里逐格点出来。默认 null = 生产行为不变（进来就是「全部日期」）。
     */
    initialFromAt: Long? = null,
    initialToAt: Long? = null,
    /**
     * 命中行里「多久以前」用的当前时刻。理由同 [CalendarGrid] 的 `now`：
     * 取真实挂钟会让参考图随日子漂移。默认 = 生产行为不变。
     */
    now: Long = nowMillis(),
) {
    var query by remember { mutableStateOf(initialQuery) }
    var selectedKind by remember { mutableStateOf(HistorySearchKind.All) }
    var fromAt by remember { mutableStateOf(initialFromAt) }
    var toAt by remember { mutableStateOf(initialToAt) }
    var currentMonthOffset by remember { mutableStateOf(0) }

    var results by remember { mutableStateOf<List<MessageSearchResult>>(emptyList()) }

    // 击键不再逐字打一次检索：静默后才查一次，与手机浮层
    // （`HistorySearchOverlay` 的 `debouncedHistorySearch`）**同一个 helper、同一条静默期**。
    // 桌面从前是逐字直查（这里一行同步 `onSearch`），同一句话打三个字就是三次全库扫。
    LaunchedEffect(query, selectedKind, fromAt, toAt) {
        val filter = HistorySearchFilter(kind = selectedKind, fromAt = fromAt, toAt = toAt)
        results = debouncedHistorySearch(query, filter, search = onSearch)
    }

    // 打开即聚焦：点「历史搜索」就是要打字，不该再点一次输入框。
    // 与手机浮层（`HistorySearchOverlay` 的 `searchFocus`）**同一条纪律、同一个 key（Unit）**：
    // 这个弹窗的生命周期就是"打开"，所以「组合一次、请求一次」正是想要的语义。
    // 桌面从前没有这一步 —— 弹窗打开后按键进不去（焦点还在聊天页的输入卡片上）。
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.16f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                // `.hsdlg{width:min(680px,100%);height:min(560px,100%)}`（`:1011`）。
                // 原先写成 `widthIn(max = 680.dp)` **再** `fillMaxWidth(0.9f)`：widthIn 先把
                // 约束收成 680，fillMaxWidth 再取它的 90% ⇒ 实际永远 612dp，帧上量得
                // 1607/2.6256 = 612dp。改成 fillMaxWidth/fillMaxHeight 才是 min(680, 可用)。
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .heightIn(max = 560.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = false) {}, // 阻止穿透
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 弹窗头部
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = stringResource(Res.string.r2_shell_hist_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            val statusText = if (peer.online) {
                                stringResource(Res.string.common_online)
                            } else {
                                stringResource(Res.string.common_offline)
                            }
                            // `.hsdh .s` 逐字是「teahouse-mobile · 192.168.1.5:17879 · 在线」
                            // （`prototype/relay.html:5013`）= 名字 · 地址:端口 · 在线态；
                            // 原先用的 `peer.subtitle` 是「捎话 · 设计 · Tidings」那张名片，不是地址。
                            val host = peer.ip.ifBlank { peer.host }
                            val address = if (peer.udpPort > 0) "$host:${peer.udpPort}" else host
                            Text(
                                text = listOf(peer.displayName, address, statusText)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // `.ibtn{width:34px;height:34px;border-radius:var(--r-ctl);background:var(--surface-2)}`
                        // （`:583`）+ 16px 图标 —— 原型那颗关闭是 **34dp 灰底方块**，
                        // 不是裸 48dp 的 M3 IconButton（帧上原本只有一枚大 ✕）。
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    // 图标座（34dp 方砖 + 单颗图标）→ --surface-3。
                                    color = SurfaceTiers.Inset,
                                    shape = MaterialTheme.shapes.medium,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(Res.string.common_close),
                                modifier = Modifier.size(IconSizes.Label),
                            )
                        }
                    }
                    HorizontalDivider()

                    // 主体：左 218dp 条件栏 + 右结果栏
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        // 左栏：条件
                        // `.hsside{width:218px;background:var(--surface-2);border-right:1px solid var(--hair);
                        //   gap:9px;padding:10px;overflow-y:auto}`（`prototype/relay.html:1021-1024`）
                        // ⇒ gap 9 / padding 10，而且**可纵向滚**：原型那份内容本来就比 dialog
                        //   高（`清空筛选` 在静帧里也在折线以下），不可滚会被裁掉半颗按钮。
                        Column(
                            modifier = Modifier
                                .width(220.dp)
                                .fillMaxHeight()
                                // `.hsside{...background:var(--surface-2);border-right:1px solid var(--hair)}`
                                // （`:1021-1022`）—— 左栏是**灰底**的（右栏才是白底），
                                // 帧上原来整块都是 dialog 的白底、分不出两栏。
                                // 右侧那圈 hairline 由 `VerticalDivider()` 承担。
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .verticalScroll(rememberScrollState())
                                .padding(LocalAppSpacing.current.md),
                            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                        ) {
                            // 关键词（原型 `label.hsf > span「关键词」+ input`，`:5016-5018`）——
                            // 这一块 12px 的标签帧上原先没有。
                            Column(verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6)) {
                                Text(
                                    text = stringResource(Res.string.r2_shell_hist_keyword),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // `.inp{background:var(--surface);border:1px solid var(--outline);
                                //   border-radius:var(--r-ctl)}`（`:358`）+ `.hsside .hsf .inp{width:100%;height:34px}`（`:1036`）
                                // ⇒ 白底 + 1px 描边 + 12dp 圆角 + **34dp 高**，且没有前置放大镜。
                                // M3 的 OutlinedTextField 最短 56dp、聚焦时画 2px 主色描边，形状不对。
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.fillMaxWidth().height(HistSearchInputHeight),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = HistSearchInputPaddingH),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (query.isEmpty()) {
                                            Text(
                                                text = stringResource(Res.string.hist_search_input_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        BasicTextField(
                                            value = query,
                                            onValueChange = { query = it },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                        )
                                    }
                                }
                            }

                            // 类型：`.seg{display:flex;background:var(--surface-2);border-radius:12px;padding:3px;gap:3px}`
                            // + `.seg button{flex:1;border:0;background:none;font-size:12px;padding:6px 4px;border-radius:8px;color:var(--on-2)}`
                            // + `[aria-pressed="true"]{background:var(--surface);color:var(--on);font-weight:600;box-shadow:0 1px 2px rgba(0,0,0,.1)}`
                            // （`prototype/relay.html:345-352`，窗内 `.win .seg button{padding:5px 4px}` `:349`）
                            // M3 的 FilterChip 是「三颗各自独立的胶囊」（选中 peach 底），形状不是一条分段条。
                            Column(verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs)) {
                                Text(
                                    text = stringResource(Res.string.hist_search_filter_type),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Surface(
                                    // `.hsside .seg{background:none}`（`:1031`）：这条分段条
                                    // 落在灰底左栏里时**轨道是透明的**，只有选中的那一段是白底。
                                    color = Color.Transparent,
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(SegPadding),
                                        horizontalArrangement = Arrangement.spacedBy(SegGap),
                                    ) {
                                        HistoryKindSegment(
                                            label = stringResource(Res.string.hist_filter_all),
                                            selected = selectedKind == HistorySearchKind.All,
                                            onClick = { selectedKind = HistorySearchKind.All },
                                            modifier = Modifier.weight(1f),
                                        )
                                        HistoryKindSegment(
                                            label = stringResource(Res.string.hist_filter_image),
                                            selected = selectedKind == HistorySearchKind.Image,
                                            onClick = { selectedKind = HistorySearchKind.Image },
                                            modifier = Modifier.weight(1f),
                                        )
                                        HistoryKindSegment(
                                            label = stringResource(Res.string.hist_filter_file),
                                            selected = selectedKind == HistorySearchKind.File,
                                            onClick = { selectedKind = HistorySearchKind.File },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }

                            // 日期：`.hsside .hsfld{flex-direction:column;gap:8px}`（`:1037`）⇒
                            // 标签 → 值 → 清除 竖着摞；`.hsside .hsclear{align-self:flex-start}`（`:1030`）
                            // ⇒ 「清除」在**日期值下方左对齐**，不是与标签同一行的右端（帧上原来在右端）。
                            Column(verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                                Text(
                                    text = stringResource(Res.string.hist_search_filter_date),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val dateLabel = if (fromAt != null && toAt != null) {
                                    stringResource(
                                        Res.string.hist_filter_date_range,
                                        formatDateLabel(fromAt!!),
                                        formatDateLabel(toAt!!),
                                    )
                                } else {
                                    stringResource(Res.string.hist_filter_date_all)
                                }
                                // `.hsside .hsdate{text-align:left}` + `.hsdate b{font-weight:600}`（`:1029`/`:941`）
                                Text(
                                    text = dateLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (fromAt != null || toAt != null) {
                                    // `.hsclear{background:transparent;color:var(--on-2);border:1px solid var(--outline);
                                    //   border-radius:8px;padding:5px 9px;font-size:11px}`（`:938-939`）
                                    Surface(
                                        onClick = {
                                            fromAt = null
                                            toAt = null
                                        },
                                        color = Color.Transparent,
                                        shape = MaterialTheme.shapes.small,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.hist_date_clear),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(
                                                horizontal = ClearButtonPaddingH,
                                                vertical = ClearButtonPaddingV,
                                            ),
                                        )
                                    }
                                }
                            }

                            // 月历：`.hscal{background:var(--surface-2);border:1px solid var(--hair);
                            //   border-radius:12px;padding:9px 10px 10px}` + `.hsside .hscal{padding:7px 8px 8px}`
                            //   （`:975-976` / `:1028`）⇒ 卡片的底色与左栏同色（左栏也是 surface-2），
                            //   靠那圈 hairline 与圆角立住；[CalendarGrid] 自身没有卡片外壳。
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                CalendarGrid(
                                    monthOffset = currentMonthOffset,
                                    onPrevMonth = { currentMonthOffset-- },
                                    onNextMonth = { currentMonthOffset++ },
                                    startAt = fromAt,
                                    endAt = toAt,
                                    onDayClick = { dayMillis ->
                                        if (fromAt == null || (fromAt != null && toAt != null)) {
                                            fromAt = dayMillis
                                            toAt = null
                                        } else {
                                            if (dayMillis < fromAt!!) {
                                                toAt = fromAt
                                                fromAt = dayMillis
                                            } else {
                                                toAt = dayMillis + 86399999L
                                            }
                                        }
                                    },
                                    modifier = Modifier.padding(
                                        start = CalendarCardPaddingH,
                                        end = CalendarCardPaddingH,
                                        top = CalendarCardPaddingTop,
                                        bottom = CalendarCardPaddingH,
                                    ),
                                )
                            }

                            // 「清空筛选」：`.hsside>.btn{margin-top:auto;background:transparent;color:var(--on-2);
                            //   border:1px dashed var(--outline);width:100%}`（`:1039-1040`）
                            // ⇒ **虚线描边、透明底、整宽**，且被推到底部（`margin-top:auto`）。
                            // 原来的 M3 OutlinedButton 是实线 + 主色文字，与原型两种边框语言。
                            val dashedBorderColor = MaterialTheme.colorScheme.outline
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawBehind {
                                        drawRoundRect(
                                            color = dashedBorderColor,
                                            style = Stroke(
                                                width = DashedBorderWidth.toPx(),
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                                            ),
                                            cornerRadius = CornerRadius(DashedBorderRadius.toPx()),
                                        )
                                    }
                                    .clickable {
                                        query = ""
                                        selectedKind = HistorySearchKind.All
                                        fromAt = null
                                        toAt = null
                                    }
                                    .padding(vertical = ClearFilterPaddingV),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(Res.string.hist_search_clear_filter),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        VerticalDivider()

                        // 右栏：结果
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(LocalAppSpacing.current.md),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.sm),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(Res.string.hist_search_results_section),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(Res.string.hist_search_results_count, results.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            if (results.isEmpty()) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = stringResource(Res.string.hist_search_empty),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(LocalAppSpacing.current.xs))
                                        Text(
                                            text = stringResource(Res.string.hist_search_empty_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = LocalAppSpacing.current.xs),
                                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                                ) {
                                    items(results, key = { it.id }) { hit ->
                                        HistoryHitItem(
                                            hit = hit,
                                            onClick = {
                                                onSelectHit(hit)
                                                onDismiss()
                                            },
                                            // 不给名字就会印裸 nodeId（`n-zhou0000000003`）；
                                            // 原型那一行写的是「老周 · 昨天 18:02」。
                                            peerNames = mapOf(peer.nodeId to peer.displayName),
                                            now = now,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 页脚：`.hsfoot{flex:none;width:100%;grid-column:1/-1;text-align:center;
                    //   font-size:11px;color:var(--on-2);padding:12px 0 10px;border-top:1px solid var(--hair)}`
                    //   （`prototype/relay.html:1004-1005`）—— 它是 `hsdbd` 网格里 **跨两栏** 的一行
                    //   （`screenDHistSearch` `:5561` 里是 `.hsdbd` 的直接子节点），不是右栏内部的一行。
                    HorizontalDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LocalAppSpacing.current.md, bottom = LocalAppSpacing.current.md10),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.hist_search_footer_hint, results.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 原型逐值常量 + 分段条的一段
// ---------------------------------------------------------------------------
// 这些值全部抄自 `prototype/relay.html` 的 `.seg` / `.hsclear` / `.hsside>.btn` /
// `.hscal`。写成具名常量有两个理由：①`design-token-usage.py` 的棘轮把
// `padding(3.dp)` 这类**裸字面量**记成欠债（具名常量不计入）；②原型那些值是
// 3/5/8/9 这种奇数像素，用现成的 `AppSpacing` 档位会各差 1–2dp。
/** `.seg{padding:3px;gap:3px}`（`prototype/relay.html:345`）。 */
private val SegPadding = 3.dp
/** `.seg{gap:3px}` 同上。 */
private val SegGap = 3.dp
/** `.win .seg button{padding:5px 4px}`（`:349`）。 */
private val SegButtonPaddingV = 5.dp
private val SegButtonPaddingH = 4.dp
/** 选中那一段的 `box-shadow:0 1px 2px rgba(0,0,0,.1)`（`:351`）。 */
private val SegSelectedElevation = 1.dp
/** `.hsclear{padding:5px 9px}`（`:939`）。 */
private val ClearButtonPaddingV = 5.dp
private val ClearButtonPaddingH = 9.dp
/** `.hsside>.btn` 的虚线描边：`border:1px dashed var(--outline)`（`:1039`）。 */
private val DashedBorderWidth = 1.dp
private val DashedBorderRadius = 8.dp
/** 虚线那颗按钮自身的 `padding`（`.btn` 的 `--pad-btn`，窗内 `7px 12px`，`:620`）。 */
private val ClearFilterPaddingV = 7.dp
/** `.hsside .hscal{padding:7px 8px 8px}`（`:1028`）。 */
private val CalendarCardPaddingTop = 7.dp
private val CalendarCardPaddingH = 8.dp
/** `.hsside .hsf .inp{height:34px}`（`:1036`）+ `.win .inp{padding:8px 11px}`（`:361`）。 */
private val HistSearchInputHeight = 34.dp
private val HistSearchInputPaddingH = 11.dp

/** 分段条里的一段。`.seg button{flex:1;border:0;background:none;font-size:12px;padding:6px 4px;
 *  border-radius:8px;color:var(--on-2)}` + `[aria-pressed="true"]{background:var(--surface);
 *  color:var(--on);font-weight:600;box-shadow:0 1px 2px rgba(0,0,0,.1)}`
 *  （`prototype/relay.html:346-352`）。 */
@Composable
private fun HistoryKindSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        shadowElevation = if (selected) SegSelectedElevation else 0.dp,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = SegButtonPaddingH,
                vertical = SegButtonPaddingV,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
