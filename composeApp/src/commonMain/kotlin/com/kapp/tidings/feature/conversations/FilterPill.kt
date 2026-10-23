// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.conversations

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * 一颗筛选药丸（全部 / 未读 / 群聊 / Agent / 同事）。
 *
 * 原型 `prototype/relay.html:456-461` 逐字：
 * ```
 * .fchip{…font-size:12px;font-weight:500;padding:5px 11px;border-radius:var(--r-chip);
 *   background:var(--surface-2);color:var(--on-2);border:1px solid transparent}
 * .fchip[aria-selected="true"]{background:var(--primary-c);color:var(--on-primary-c);font-weight:600}
 * ```
 * ⇒ 选中态是**主色容器底 + onPrimaryContainer 字 + SemiBold**；
 * 未选中态是 `--surface-2` 底（`surfaceVariant` `#F0EFE9`）+ `--on-2` 字（`onSurfaceVariant`），
 * 且**没有可见描边**（原型那句 `border:1px solid transparent`）。
 *
 * 裸 `FilterChip(...)` 吃的是 M3 默认值：选中容器 = `secondaryContainer`
 * （本仓 `ui/theme/Color.kt` 里是浅橙 `#F5E6D3`）⇒ 画出来是**浅橙**，与原型那颗绿不符。
 * 颜色这一层没有量具（`tools/check-design-palette.py` 自陈"实测它没有分辨力"），
 * 所以只能靠这里显式指定；别为了省两行退回默认色。
 *
 * 从 `ConversationsScreen.kt` 原样搬到这个文件（那一屏当时已 597 行，
 * `prototype-to-compose` 的评审红线是 500 行：先拆再加）。搬迁不改行为、不改签名，
 * 只是把它与同样被搬走的 `ConversationsOverflowItems` / `ConversationRow` 分开。
 */
@Composable
internal fun FilterPill(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            // 未选中：`--surface-2` 底（= `surfaceVariant` `#F0EFE9`）+ `--on-2` 字。
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            // 选中：`--primary-c` 底（= `primaryContainer` `#B7E4C7`）+ `--on-primary-c` 字。
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        // 原型 `.fchip{…border:1px solid transparent}` ⇒ 没有可见描边。
        // M3 默认会给未选中态画一圈 `outline` 描边，画出来比原型多一道灰边。
        border = null,
    )
}
