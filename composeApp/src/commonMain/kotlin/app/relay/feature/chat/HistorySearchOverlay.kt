// SPDX-License-Identifier: GPL-3.0-only
package app.relay.feature.chat

import org.jetbrains.compose.resources.painterResource
import app.relay.core.nowMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.relay.app.HistorySearchFilter
import app.relay.app.HistorySearchKind
import app.relay.app.MessageSearchResult
import app.relay.generated.resources.Res
import app.relay.generated.resources.common_cancel
import app.relay.generated.resources.common_close
import app.relay.generated.resources.hist_date_clear
import app.relay.generated.resources.hist_date_confirm
import app.relay.generated.resources.hist_date_end
import app.relay.generated.resources.hist_date_month_day
import app.relay.generated.resources.hist_date_month_title
import app.relay.generated.resources.hist_date_range_title
import app.relay.generated.resources.hist_date_start
import app.relay.generated.resources.hist_filter_all
import app.relay.generated.resources.hist_filter_date_all
import app.relay.generated.resources.hist_filter_date_range
import app.relay.generated.resources.hist_filter_file
import app.relay.generated.resources.hist_filter_image
import app.relay.generated.resources.hist_quick_30days
import app.relay.generated.resources.hist_quick_7days
import app.relay.generated.resources.hist_quick_today
import app.relay.generated.resources.hist_search_empty
import app.relay.generated.resources.hist_search_filter_date
import app.relay.generated.resources.hist_search_filter_type
import app.relay.generated.resources.hist_search_footer_hint
import app.relay.generated.resources.hist_search_input_hint
import app.relay.generated.resources.hist_search_results_count
import app.relay.generated.resources.hist_search_results_section
import app.relay.generated.resources.hist_week_fri
import app.relay.generated.resources.hist_week_mon
import app.relay.generated.resources.hist_week_sat
import app.relay.generated.resources.hist_week_sun
import app.relay.generated.resources.hist_week_thu
import app.relay.generated.resources.hist_week_tue
import app.relay.generated.resources.hist_week_wed
import app.relay.generated.resources.r2_chat_time_yesterday
import app.relay.generated.resources.ic_search
import app.relay.generated.resources.ic_image
import app.relay.generated.resources.ic_file
import app.relay.generated.resources.ic_chat_bubble
import org.jetbrains.compose.resources.painterResource
import app.relay.ui.components.formatClock
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.accents
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.theme.ShapeRoles

/**
 * 移动端会话内历史搜索浮层（原型 m-histsearch 与 m-histdate）。
 *
 * 覆盖当前会话，包含搜索框、类型 chip、日期选择入口（贴底 sheet 月历）以及命中列表。
 */
@Composable
fun HistorySearchOverlay(
    onDismiss: () -> Unit,
    onSearch: (query: String, filter: HistorySearchFilter) -> List<MessageSearchResult>,
    onSelectHit: (MessageSearchResult) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    /**
     * 首屏就摆好的命中列表。**只给截图门禁用**：结果平时由
     * `LaunchedEffect(query, selectedKind, fromAt, toAt)` 填，而渲染器不执行 effect，
     * 于是「有结果」那一版没有任何帧能钉住。生产调用方一律用默认值（空）。
     */
    initialResults: List<MessageSearchResult> = emptyList(),
    /**
     * 只给截图门禁用：日期范围与"日期面板是否已经打开"的初值。
     *
     * 原型把这一屏画成**两种状态**：`m-histsearch`（筛选条件与命中列表）与
     * `m-histdate`（同一个浮层上再叠一层日期面板）。三处状态（`fromAt` / `toAt` /
     * `showDateSheet`）都是屏内 `remember`，静态帧点不了 —— 没有这个接缝，
     * `m-histdate` 那一屏一张图都拍不到，`m-histsearch` 的日期按钮也只能永远显示
     * 「全部日期」。生产调用方一律用默认值。
     */
    initialFromAt: Long? = null,
    initialToAt: Long? = null,
    initialDateSheetOpen: Boolean = false,
    /**
     * nodeId → 昵称，用于命中行副行（原型写「老周 · 昨天 18:02」而不是 nodeId）。
     * 生产调用方从 `state.peers` 组表传入；不传就退回 `peerId.take(8)`。
     */
    peerNames: Map<String, String> = emptyMap(),
    now: Long = nowMillis(),
) {
    var query by remember { mutableStateOf(initialQuery) }
    var selectedKind by remember { mutableStateOf(HistorySearchKind.All) }
    var fromAt by remember { mutableStateOf(initialFromAt) }
    var toAt by remember { mutableStateOf(initialToAt) }
    var showDateSheet by remember { mutableStateOf(initialDateSheetOpen) }

    var results by remember { mutableStateOf(initialResults) }

    LaunchedEffect(query, selectedKind, fromAt, toAt) {
        val filter = HistorySearchFilter(kind = selectedKind, fromAt = fromAt, toAt = toAt)
        results = onSearch(query, filter)
    }

    // 原型 `.hsbd{position:absolute;inset:0;z-index:24;background:var(--surface)}` —— 整屏浮层。
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        val sp = LocalAppSpacing.current
        Column(Modifier.fillMaxSize()) {
            // 原型 `.hs-f{display:flex;align-items:center;gap:9px;padding:9px 12px;
            //   border-bottom:1px solid var(--hair)}`。
            //
            // 这一层是**整屏浮层**：`.hsbd` 从 inset:0 铺满，连顶栏一起盖住 —— 原型帧里
            // 没有返回键 / 头像 / 会话名（见 prototype/screenshots/relay/m11d-histsearch.png，
            // 最上面一行就是搜索框）。原先代码自己又画了一条顶栏，等于把「被盖住的那条」
            // 重画了一遍，所以这里删掉；返回出口是右侧那颗 `.btn.sm`（原型装的是 `ic('close')`）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sp.md, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // `svg{width:1em;height:1em}`，而 `.hs-f` 没有自己的 font-size ⇒ 1em = 14px。
                Icon(
                    painter = painterResource(Res.drawable.ic_search),
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.Inline),
                )
                // `.inp{width:100%;border:1px solid var(--outline);background:var(--surface);
                //   color:var(--on);font-size:var(--fs-body);padding:10px 12px;
                //   border-radius:var(--r-ctl)}`，`.hs-f .inp{flex:1;min-width:0}`。
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        .padding(horizontal = sp.md, vertical = sp.md10),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.hist_search_input_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                // `.hs-f .btn.sm{padding:6px 9px}` + `.btn{border-radius:var(--r-ctl)=12px;
                //   border:1px solid var(--outline);background:var(--surface)}` + `.btn svg{16px}`。
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 9.dp, vertical = sp.sm6),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.common_close),
                        modifier = Modifier.size(IconSizes.Label),
                    )
                }
            }
            HorizontalDivider()

            // `.hsbody{flex:1;min-height:0;overflow-y:auto;padding:12px 14px 14px;
            //   display:flex;flex-direction:column;gap:11px}` —— 条件行和结果在同一个滚动容器里
            // （原型就是这么排的），不是「条件钉住 + 结果单独滚」。
            val dateLabel = if (fromAt != null && toAt != null) {
                stringResource(
                    Res.string.hist_filter_date_range,
                    formatDateLabel(fromAt!!),
                    formatDateLabel(toAt!!),
                )
            } else {
                stringResource(Res.string.hist_filter_date_all)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = sp.lg14, top = sp.md, end = sp.lg14, bottom = sp.lg14),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                // `.hsrow{display:flex;align-items:center;gap:9px}` +
                // `.hsrow>.hsl{flex:none;width:34px;font-size:12px;color:var(--on-2)}`
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.hist_search_filter_type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp),
                    )
                    // `.hskinds{display:flex;gap:5px;flex-wrap:wrap}` +
                    // `.hskinds button{flex:none;border:1px solid var(--outline);border-radius:8px;
                    //   background:var(--surface);color:var(--on-2);padding:6px 10px;font-size:12px}`
                    // + `[aria-pressed=true]{background:var(--primary);border-color:var(--primary);
                    //   color:var(--on-primary);font-weight:600}`
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(
                            HistorySearchKind.All to Res.string.hist_filter_all,
                            HistorySearchKind.Image to Res.string.hist_filter_image,
                            HistorySearchKind.File to Res.string.hist_filter_file,
                        ).forEach { (kind, labelRes) ->
                            val on = selectedKind == kind
                            Box(
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surface,
                                    )
                                    .border(
                                        1.dp,
                                        if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        MaterialTheme.shapes.small,
                                    )
                                    .clickable { selectedKind = kind }
                                    .padding(horizontal = sp.md10, vertical = sp.sm6),
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (on) FontWeight.SemiBold else null,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // `.hsdatebtn{flex:1;min-width:0;display:flex;align-items:center;
                //   justify-content:flex-end;gap:5px;background:var(--surface-2);
                //   border:1px solid var(--hair);border-radius:9px;padding:6px 9px;font-size:12px;
                //   color:var(--on-2)}`，里面的 `b{color:var(--on);font-weight:600;font-size:12px}`，
                // `.hsdatebtn .chev{width:15px;height:15px;opacity:.5}`。
                // `.hsclear{background:transparent;color:var(--on-2);border:1px solid var(--outline);
                //   border-radius:8px;padding:5px 9px;font-size:11px}`。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.hist_search_filter_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp),
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ShapeRoles.Avatar)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                ShapeRoles.Avatar,
                            )
                            .clickable { showDateSheet = true }
                            .padding(horizontal = 9.dp, vertical = sp.sm6),
                        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = dateLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    // 原型 `prototype/relay.html:4530` 那颗 `.hsclear` 是**无条件**渲染的：
                    // `<button class="hsclear" type="button">清除</button>` 紧跟在 `.hsdatebtn` 后面，
                    // 没有 `? :`、没有 `if`。原先代码只在有日期筛选时才画它，于是空态帧里少一颗按钮 ——
                    // 原型画了几件，就画几件。没有筛选时点它是空操作。
                    Box(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                MaterialTheme.shapes.small,
                            )
                            .clickable {
                                fromAt = null
                                toAt = null
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.hist_date_clear),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // `.hsrh{padding:6px 2px 3px;font-size:12px;color:var(--on-2);
                //   display:flex;justify-content:space-between;gap:8px}`，
                // `b{color:var(--on);font-weight:600}`。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = sp.xxs, top = sp.sm6, end = sp.xxs, bottom = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.hist_search_results_section),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.hist_search_results_count, results.size),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // `.hsres{flex:none;display:flex;flex-direction:column;gap:2px}` —— 结果列表是
                // `.hsbody` 这个滚动容器里的一段，原型没有第二层滚动区（所以不再开 LazyColumn）。
                if (results.isEmpty()) {
                    // 原型 `.annot` 的第四态文案：「没有找到相关记录」。这一层没有出错态
                    // （`runHistorySearch` 没写 catch），所以只有空态一种兜底。
                    //
                    // 原型没有画空态帧，所以这里**不给任何自创的间距值** —— 一给，差集里就会
                    // 多出一个「代码有、原型没有」的数字，而那个数字是我编的。
                    Text(
                        text = stringResource(Res.string.hist_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
                        results.forEach { hit ->
                            HistoryHitItem(
                                hit = hit,
                                onClick = {
                                    onSelectHit(hit)
                                    onDismiss()
                                },
                                peerNames = peerNames,
                                now = now,
                            )
                        }
                    }
                }

                // `.hsfoot{flex:none;width:100%;text-align:center;font-size:11px;
                //   color:var(--on-2);padding:12px 0 10px;border-top:1px solid var(--hair);
                //   margin-top:6px}`
                Column(modifier = Modifier.padding(top = sp.sm6)) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(Res.string.hist_search_footer_hint, results.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = sp.md, bottom = sp.md10),
                    )
                }
            }
        }

        // 日期选择 Sheet (m-histdate)
        if (showDateSheet) {
            HistoryDateSheet(
                fromAt = fromAt,
                toAt = toAt,
                onConfirm = { start, end ->
                    fromAt = start
                    toAt = end
                    showDateSheet = false
                },
                onDismiss = { showDateSheet = false },
                now = now,
            )
        }
    }
}

@Composable
internal fun HistoryHitItem(
    hit: MessageSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * nodeId → 昵称。**不给默认空表就印出裸 nodeId**（`n-zhou0000000003`），
     * 而原型那一行写的是「老周 · 昨天 18:02」。桌面端（`DesktopHistorySearch.kt`）
     * 复用本组件且不传，缺名时退回 `peerId.take(8)`，不会崩。
     */
    peerNames: Map<String, String> = emptyMap(),
    now: Long = nowMillis(),
) {
    val isImage = hit.fileName?.let { name ->
        val ext = name.substringAfterLast('.', "").lowercase()
        ext in listOf("png", "jpg", "jpeg", "webp", "gif")
    } ?: false
    val isFile = hit.fileName != null && !isImage
    val sp = LocalAppSpacing.current

    // `.hshit{display:flex;gap:10px;align-items:flex-start;padding:9px 10px;border-radius:12px}`
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = sp.md10, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
    ) {
        val iconRes = when {
            isImage -> Res.drawable.ic_image
            isFile -> Res.drawable.ic_file
            else -> Res.drawable.ic_chat_bubble
        }
        // `.hst{width:34px;height:34px;border-radius:12px;flex:none;background:var(--surface-3);
        //   color:var(--on-2);display:grid;place-items:center}`（`--surface-3` 是**填充** token，
        //   取 `MaterialTheme.accents.surface3`；`outlineVariant` 现在专做描边 `--hair`）
        // + `.hst svg{width:17px;height:17px}`。
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.accents.surface3,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Column(Modifier.weight(1f)) {
            val titleText = hit.fileName ?: hit.snippetText
            // `.hshit .t1{font-size:14px;line-height:1.45}` —— 没有 font-weight，就是 Normal。
            Text(
                text = titleText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 原型 `HITS` 那一行逐字是「老周 · 昨天 18:02」（人名 · 相对日 时刻），不是
            // `n-zhou0000000003 · 07:26`（nodeId · 绝对时刻）—— 后者是拿协议标识当人看的字，
            // 真人名在 `peerNames` 里。相对日只做今天/昨天两档，更早写月日，与协议规范
            // `utils/time.ts` 的 `listTime` 同口径（原型那句「周三 10:41」是稿子的美化字面量）。
            val who = hit.senderId?.let { peerNames[it] }
                ?: peerNames[hit.peerId]
                ?: (hit.senderId ?: hit.peerId).take(8)
            val subText = "$who · ${historyDayLabel(hit.timestamp, now)}"
            // `.hshit .t2{font-size:11px;color:var(--on-2);margin-top:2px}` —— 同样没有
            // font-weight，而 `labelSmall` 是 Medium，所以这里显式压回 Normal。
            Text(
                text = subText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = sp.xxs),
            )
        }
    }
}

/**
 * m-histdate 贴底对话框。
 */
@Composable
internal fun HistoryDateSheet(
    fromAt: Long?,
    toAt: Long?,
    onConfirm: (start: Long?, end: Long?) -> Unit,
    onDismiss: () -> Unit,
    /**
     * 渲染「今天」「近 7 天」「近 30 天」三档与月历所用的当前时刻。
     *
     * 为什么是参数而不是在函数里直接读挂钟：这三档的 `selected` 判据是
     * 「`tempStart`/`tempEnd` 是不是正好等于由**今天**推出来的那个区间」，
     * 而「今天」只要取自真实时间，同一帧在两次运行里就会落在不同的日界上
     * （跨日、跨月、跨年都会），参考图跟着变而没人知道为什么 ——
     * 截图门禁要的是确定性。与 `ConversationsScreen(now = …)` 同一条理由。
     */
    now: Long = nowMillis(),
) {
    var tempStart by remember { mutableStateOf(fromAt) }
    var tempEnd by remember { mutableStateOf(toAt) }
    var currentMonthOffset by remember { mutableStateOf(0) }
    val sp = LocalAppSpacing.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(8, 12, 10, (0.42f * 255).toInt()))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                // 原型 `.sheet{border-radius:var(--r-sheet) var(--r-sheet) 0 0}`，
                // `--r-sheet:28px`（`prototype/relay.html:56/1347`）。28 正好在 M3 圆角槽位里
                // （4/8/12/16/28），所以这里写死不算绕开 token；原 16dp 是 `.drpz` 那层没吃到
                // `--r-sheet` 的近似值。
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}, // 阻止点击事件穿透到背景
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sp.lg, vertical = sp.md),
                ) {
                    // 抓手。原型 `.sheet .grab{background:var(--outline)}` —— `outline`
                    // （#D4D4D0）比 `--hair` 深一档（后者的真值是 7% 黑，白底合成为 #EDEDED）。
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.height(sp.sm))

                    // 标题与关闭
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.hist_date_range_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_close))
                        }
                    }

                    // 开始 / 结束字段展示
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = sp.sm),
                        horizontalArrangement = Arrangement.spacedBy(sp.md),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.padding(horizontal = sp.md, vertical = sp.sm)) {
                                Text(
                                    text = stringResource(Res.string.hist_date_start),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = tempStart?.let { formatDateLabel(it) } ?: stringResource(Res.string.hist_filter_date_all),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.padding(horizontal = sp.md, vertical = sp.sm)) {
                                Text(
                                    text = stringResource(Res.string.hist_date_end),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = tempEnd?.let { formatDateLabel(it) } ?: stringResource(Res.string.hist_filter_date_all),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    // 快捷按钮 (今天 / 近 7 天 / 近 30 天 / 全部日期)
                    //
                    // `dayStart` 必须与月历格（`:832` 的 `epochDay * 86400000L - 28800000L`）
                    // 用**同一条「当地日」换算**：先 +8h 取整日，再 -8h 还原成当地零点。
                    // 原写法 `(now / 86400000L) * 86400000L` 取的是 **UTC** 零点
                    // （= 当地 08:00），于是「今天」比真正的当地一天晚 8 小时起步，
                    // 而且与月历格产出的当地零点**永远不相等** —— 后果是四颗 chip
                    // 即使把硬编码的 `selected = false` 改掉，也一颗都亮不起来。
                    val dayStart = ((now + 28800000L) / 86400000L) * 86400000L - 28800000L
                    val dayEnd = dayStart + 86399999L
                    // 四档范围与原型 `.drpq` 一一对应。`selected` 由「当前范围是不是正好这一档」
                    // 推出，**不能写死 false** —— 原型 `q` 里「近 30 天」是 `aria-pressed="true"`
                    // （`prototype/relay.html:4569`），而四处硬编码 false 的后果是：点「今天」
                    // 真的改了范围，chip 却一颗都不亮，看不出当前筛的是哪一档（真缺陷）。
                    val quickToday = dayStart to dayEnd
                    val quick7Days = (dayEnd - 7 * 86400000L) to dayEnd
                    val quick30Days = (dayEnd - 30 * 86400000L) to dayEnd
                    // 选中/未选中配色照原型 `.drpq button`：
                    //   `background:var(--surface)` + `color:var(--on-2)` + `border:1px solid var(--outline)`
                    //   `[aria-pressed="true"]{background:var(--primary-c);border-color:var(--primary);
                    //                        color:var(--primary);font-weight:600}`
                    // 不这么写就是 M3 默认选中态（浅橙 `secondaryContainer`）——
                    // 与 `ConversationsScreen.FilterPill` 当初踩的是同一个坑（那一处已修）。
                    // 注意原型选中字色是 `--primary`（深绿），不是 `--on-primary-c`。
                    val quickChipColors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = sp.sm6),
                        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    ) {
                        FilterChip(
                            selected = tempStart == quickToday.first && tempEnd == quickToday.second,
                            onClick = {
                                tempStart = quickToday.first
                                tempEnd = quickToday.second
                            },
                            label = {
                                Text(
                                    text = stringResource(Res.string.hist_quick_today),
                                    fontWeight = if (tempStart == quickToday.first && tempEnd == quickToday.second) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                            },
                            colors = quickChipColors,
                        )
                        FilterChip(
                            selected = tempStart == quick7Days.first && tempEnd == quick7Days.second,
                            onClick = {
                                tempStart = quick7Days.first
                                tempEnd = quick7Days.second
                            },
                            label = {
                                Text(
                                    text = stringResource(Res.string.hist_quick_7days),
                                    fontWeight = if (tempStart == quick7Days.first && tempEnd == quick7Days.second) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                            },
                            colors = quickChipColors,
                        )
                        FilterChip(
                            selected = tempStart == quick30Days.first && tempEnd == quick30Days.second,
                            onClick = {
                                tempStart = quick30Days.first
                                tempEnd = quick30Days.second
                            },
                            label = {
                                Text(
                                    text = stringResource(Res.string.hist_quick_30days),
                                    fontWeight = if (tempStart == quick30Days.first && tempEnd == quick30Days.second) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                            },
                            colors = quickChipColors,
                        )
                        FilterChip(
                            selected = tempStart == null && tempEnd == null,
                            onClick = {
                                tempStart = null
                                tempEnd = null
                            },
                            label = {
                                Text(
                                    text = stringResource(Res.string.hist_filter_date_all),
                                    fontWeight = if (tempStart == null && tempEnd == null) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                            },
                            colors = quickChipColors,
                        )
                    }

                    // 简易月历控件
                    CalendarGrid(
                        monthOffset = currentMonthOffset,
                        onPrevMonth = { currentMonthOffset-- },
                        onNextMonth = { currentMonthOffset++ },
                        startAt = tempStart,
                        endAt = tempEnd,
                        now = now,
                        onDayClick = { dayMillis ->
                            if (tempStart == null || (tempStart != null && tempEnd != null)) {
                                tempStart = dayMillis
                                tempEnd = null
                            } else {
                                if (dayMillis < tempStart!!) {
                                    tempEnd = tempStart
                                    tempStart = dayMillis
                                } else {
                                    tempEnd = dayMillis + 86399999L
                                }
                            }
                        },
                    )

                    // 底部按钮
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = sp.md, bottom = sp.xs),
                        horizontalArrangement = Arrangement.spacedBy(sp.md10),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(Res.string.common_cancel))
                        }
                        Button(
                            onClick = { onConfirm(tempStart, tempEnd) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text(stringResource(Res.string.hist_date_confirm))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CalendarGrid(
    monthOffset: Int,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    startAt: Long?,
    endAt: Long?,
    onDayClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 渲染「今天是哪一天」所用的当前时刻。理由同 [HistoryDateSheet] 的 `now`：
     * 月历标题（`10月`）、今天所在的那一格、以及跨月时那 5 行的排布都由它推出，
     * 取真实挂钟就会让参考图随月份漂移。
     */
    now: Long = nowMillis(),
) {
    val todayEpochDay = (now + 28800000L) / 86400000L
    val (curYear, curMonth) = epochDayToYearMonth(todayEpochDay + monthOffset * 30L)
    val sp = LocalAppSpacing.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = sp.xs),
    ) {
        // 月历头部: < 10月 >
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevMonth) {
                Text("<", fontWeight = FontWeight.Bold)
            }
            Text(
                text = stringResource(Res.string.hist_date_month_title, curMonth),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onNextMonth) {
                Text(">", fontWeight = FontWeight.Bold)
            }
        }

        // 星期标头
        val weekDays = listOf(
            stringResource(Res.string.hist_week_mon),
            stringResource(Res.string.hist_week_tue),
            stringResource(Res.string.hist_week_wed),
            stringResource(Res.string.hist_week_thu),
            stringResource(Res.string.hist_week_fri),
            stringResource(Res.string.hist_week_sat),
            stringResource(Res.string.hist_week_sun),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            for (w in weekDays) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = w, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(Modifier.height(sp.xs))

        // 日历网格 (按 35 格渲染)
        val firstDayEpoch = yearMonthToEpochDay(curYear, curMonth, 1)
        // 1970-01-01 是周四 (dayOfWeek = 4, 1=Mon, 7=Sun)
        val firstDayOfWeek = (((firstDayEpoch + 3) % 7) + 1).toInt() // 1=Mon...7=Sun
        val startEpochDay = firstDayEpoch - (firstDayOfWeek - 1)

        for (row in 0 until 5) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = sp.xxs)) {
                for (col in 0 until 7) {
                    val epochDay = startEpochDay + row * 7 + col
                    val (_, m, d) = epochDayToYearMonthDay(epochDay)
                    val isCurrentMonth = m == curMonth
                    val dayMillis = epochDay * 86400000L - 28800000L

                    // 区间与端点一律按**当地日**（UTC+8）比较。
                    //
                    // 原写法 `dayMillis / 86400000L == (startAt + 28800000L) / 86400000L`
                    // 是拿「格子的 **UTC** 日」比「区间的**当地**日」：`dayMillis` 已经
                    // -8h 还原成当地零点（`:832`），再整除 86400000 得到的是**前一天**
                    // 的 UTC 日号。后果是端点的深绿高亮**整体晚一天** —— 帧上写「9月13日
                    // 至 9月15日」，月历却把 14/15/16 点亮（见并排图）。同理
                    // `dayMillis in startAt..endAt` 也漏掉起始日：起始日当地零点
                    // 早于 `startAt` 那个时刻本身（用户选的是某天，不是 08:26）。
                    // 统一成「当地日号」比较，两个毛病一起消失。
                    val startDay = startAt?.let { (it + 28800000L) / 86400000L }
                    val endDay = endAt?.let { (it + 28800000L) / 86400000L }
                    val inRange = startDay != null && endDay != null && epochDay in startDay..endDay
                    val isEdge = epochDay == startDay || epochDay == endDay

                    // 原型 `.hsdays button.in-range{background:var(--primary-c);color:var(--on-primary-c)}`
                    // —— **不透明**。代码原来写 `primaryContainer.copy(alpha = 0.5f)`，压在白底上比
                    // 原型浅一档，区间那几天看着像"没选中"（真缺陷：用了原型没有的值）。
                    val bg = when {
                        isEdge -> MaterialTheme.colorScheme.primary
                        inRange -> MaterialTheme.colorScheme.primaryContainer
                        else -> Color.Transparent
                    }
                    // 原型 `.hsdays button.out{color:var(--on-2);opacity:.4}` —— 非本月那几天是
                    // **深灰降到四成**，不是换成浅灰（`--outline` 是边框色，比 `--on-2` 浅得多）。
                    val textColor = when {
                        isEdge -> MaterialTheme.colorScheme.onPrimary
                        inRange -> MaterialTheme.colorScheme.onPrimaryContainer
                        isCurrentMonth -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg)
                            .clickable { onDayClick(dayMillis) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = d.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isEdge) FontWeight.Bold else FontWeight.Normal,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun formatDateLabel(millis: Long): String {
    val epochDay = (millis + 28800000L) / 86400000L
    val (_, m, d) = epochDayToYearMonthDay(epochDay)
    return stringResource(Res.string.hist_date_month_day, m, d)
}

/**
 * 命中行副行的日期半段：今天 / 昨天写词，更早写月日 + 时刻。
 *
 * 口径取自相对时间格式化规范 `listTime(ts)`（今天 → `HH:mm`，
 * 昨天 → 「昨天」，更早 → `MM-DD`）—— 但那支**不带时刻**，而原型 `HITS` 那一行
 * 「老周 · 昨天 18:02」两样都要，所以这里是「相对日 + 时刻」的组合：
 * 今天 → `18:02`、昨天 → `昨天 18:02`、更早 → `10月8日 18:02`。
 * 原型里的「周三 10:41」是稿子的美化字面量（`listTime` 不产「周X」），不照抄。
 */
@Composable
internal fun historyDayLabel(millis: Long, now: Long): String {
    val day = (millis + 28800000L) / 86400000L
    val today = (now + 28800000L) / 86400000L
    val clock = formatClock(millis)
    return when (day) {
        today -> clock
        today - 1L -> "${stringResource(Res.string.r2_chat_time_yesterday)} $clock"
        else -> "${formatDateLabel(millis)} $clock"
    }
}

internal fun epochDayToYearMonthDay(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719468L
    val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y = (yoe + era * 400L).toInt()
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
    val m = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
    val finalY = if (m <= 2) y + 1 else y
    return Triple(finalY, m, d)
}

internal fun epochDayToYearMonth(epochDay: Long): Pair<Int, Int> {
    val (y, m, _) = epochDayToYearMonthDay(epochDay)
    return Pair(y, m)
}

internal fun yearMonthToEpochDay(y: Int, m: Int, d: Int): Long {
    val adjY = if (m <= 2) y - 1 else y
    val era = if (adjY >= 0) adjY / 400 else (adjY - 399) / 400
    val yoe = adjY - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}
