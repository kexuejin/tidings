// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.HistorySearchKind
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.hist_filter_all
import com.kapp.tenon.generated.resources.hist_filter_file
import com.kapp.tenon.generated.resources.hist_filter_image
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.accents
import org.jetbrains.compose.resources.stringResource

/**
 * 历史搜索的**类型筛选**：全部 / 图片 / 文件。
 *
 * ## 为什么是一个组件两种皮肤
 *
 * 手机浮层与桌面左栏各有一份原型：手机是 `.hskinds button`（描边胶囊，选中填主色），
 * 桌面是 `.win .seg button`（透明轨道上的分段条，选中那段白底 + 1dp 阴影）。**数据与
 * 标签完全同一份**，只有皮不同 —— 从前是两段各自复制的 Row/Box/Text，改一处（比如
 * 「文件」的措辞、或顺序调整）另一处就漂。
 *
 * 皮肤由 [HistoryKindFilterSkin] 选，判据落在「改一处两壳都变」：数据、顺序、选中语义、
 * 点击处理全在这里；皮肤只决定盒子的画法。
 *
 * 两种皮肤的 padding 分别对齐各自原型（手机走 spacing 档位，桌面是窗内 `.seg` 的
 * 3/4/5px 奇数像素），所以数值写成具名常量 —— `design-token-usage` 的棘轮只记裸字面量。
 */
@Composable
fun HistoryKindFilter(
    selected: HistorySearchKind,
    onSelect: (HistorySearchKind) -> Unit,
    skin: HistoryKindFilterSkin,
    modifier: Modifier = Modifier,
) {
    // 唯一的选项表：改这里，两壳同时变。
    val options = listOf(
        HistorySearchKind.All to Res.string.hist_filter_all,
        HistorySearchKind.Image to Res.string.hist_filter_image,
        HistorySearchKind.File to Res.string.hist_filter_file,
    )
    when (skin) {
        HistoryKindFilterSkin.Outline -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(OutlineChipGap),
        ) {
            options.forEach { (kind, labelRes) ->
                OutlineKindChip(
                    label = stringResource(labelRes),
                    selected = selected == kind,
                    onClick = { onSelect(kind) },
                )
            }
        }

        HistoryKindFilterSkin.Segment -> Surface(
            // `.hsside .seg{background:none}`（`prototype/relay.html:1031`）：这条分段条
            // 落在灰底左栏里时**轨道是透明的**，只有选中的那一段是白底。
            color = Color.Transparent,
            shape = MaterialTheme.shapes.medium,
            modifier = modifier,
        ) {
            Row(
                modifier = Modifier.padding(SegTrackPadding),
                horizontalArrangement = Arrangement.spacedBy(SegGap),
            ) {
                options.forEach { (kind, labelRes) ->
                    SegmentKindChip(
                        label = stringResource(labelRes),
                        selected = selected == kind,
                        onClick = { onSelect(kind) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 两壳的皮肤。数据一侧同源，只有画法不同（见 [HistoryKindFilter] 的 KDoc）。 */
enum class HistoryKindFilterSkin {
    /** 手机浮层：`.hskinds button`（描边 + 选中填主色，`:278-282`）。 */
    Outline,

    /** 桌面左栏：`.seg button`（透明轨道 + 选中白底带阴影，`:345-352`）。 */
    Segment,
}

/** `.hskinds{gap:5px}`。 */
private val OutlineChipGap = 5.dp

/** `.hskinds button{border:1px solid var(--outline)}`。 */
private val OutlineChipBorderWidth = 1.dp

/** `.seg{padding:3px;gap:3px}`（`prototype/relay.html:345`）。 */
private val SegTrackPadding = 3.dp
private val SegGap = 3.dp

/** `.win .seg button{padding:5px 4px}`（`:349`）。 */
private val SegChipPaddingVertical = 5.dp
private val SegChipPaddingHorizontal = 4.dp

/** 选中那一段的 `box-shadow:0 1px 2px rgba(0,0,0,.1)`（`:351`）。 */
private val SegSelectedElevation = 1.dp

/**
 * 描边胶囊一颗。`.hskinds button{flex:none;border:1px solid var(--outline);border-radius:8px;
 * background:var(--surface);color:var(--on-2);padding:6px 10px;font-size:12px}` +
 * `[aria-pressed=true]{background:var(--primary);border-color:var(--primary);
 * color:var(--on-primary);font-weight:600}`。
 */
@Composable
private fun OutlineKindChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                OutlineChipBorderWidth,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.accents.outlineStrong,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = sp.md10, vertical = sp.sm6),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 分段条的一段。`.seg button{flex:1;border:0;background:none;font-size:12px;padding:6px 4px;
 * border-radius:8px;color:var(--on-2)}` + `[aria-pressed="true"]{background:var(--surface);
 * color:var(--on);font-weight:600;box-shadow:0 1px 2px rgba(0,0,0,.1)}`（`:346-352`）。
 */
@Composable
private fun SegmentKindChip(
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
                horizontal = SegChipPaddingHorizontal,
                vertical = SegChipPaddingVertical,
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
