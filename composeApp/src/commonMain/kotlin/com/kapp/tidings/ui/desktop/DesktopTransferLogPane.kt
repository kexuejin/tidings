// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.file_open
import com.kapp.tidings.generated.resources.transfer_log_empty
import com.kapp.tidings.generated.resources.transfer_log_group_head
import com.kapp.tidings.generated.resources.transfer_log_hint
import com.kapp.tidings.generated.resources.transfer_log_status_failed
import com.kapp.tidings.generated.resources.transfer_log_status_success
import com.kapp.tidings.generated.resources.transfer_log_sub
import com.kapp.tidings.generated.resources.transfer_log_title
import com.kapp.tidings.generated.resources.ic_file
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_up
import com.kapp.tidings.ui.components.formatBytes
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.domain.model.TransferRecordUi
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppButtonSize

/**
 * 桌面端传输记录面板 (d-transferlog)。
 *
 * 展示最近 30 条传输记录，支持查看文件名、状态、大小、时间以及打开已落盘文件。
 *
 * **本屏没有「清除记录」按钮**：原型 `screenDTransferLog` 的 `.hint` 明写
 * 「这一页没有列头、没有筛选、没有「清除记录」。行只有三样：名称、状态 · 大小 · 时间、
 * 以及一个「打开」按钮（没有 savedPath 时置灰）」。`onClear` 因此只保留在签名上
 * （宿主 `DesktopHosting.kt` 仍在传），当前不渲染任何入口——要恢复，加回一颗按钮即可。
 *
 * 三条**登记**（本端数据层缺值，不是「还没画」，见 R2 报告 §1.11）：
 * 1. 原型文件夹行多一格「N 项」（`完成 · 18 项 · 12.4 MB · 今天 14:02`），而 `TransferRecordUi`
 *    （`app/model/TransferUi.kt`）只有 `name/size/isIncoming/at/success/path/isFolder` ⇒ 没有项数字段。
 * 2. 原型状态词表 `TSTAT`（`prototype/relay.html:4330-4336`）有 `expiredOut`「发送已到期」/`expiredIn`「文件已过期」，
 *    而行文案「分方向」正是靠这两个词（`:4491` 的 annot 逐字「到期这一条的文案是**分方向**的」）；
 *    本端只有 `success: Boolean` ⇒ 到期态不可表达，那一行拍不到。
 * 3. 原型第 2 行的名字是「release **等 9 个文件**」（多选命名规则 `files.ts:598`），本端直接用 `record.name`，
 *    命名规则在传输层、不在本面板。
 */
@Composable
fun DesktopTransferLogPane(
    records: List<TransferRecordUi>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenFile: ((String) -> Unit)? = null,
    /**
     * 相对时间的基准时钟。默认取真实时钟；截图门禁必须传 `PREVIEW_NOW`，
     * 否则夹具里「3 分钟前」的记录会渲染成「30 天前」，而且**明天再跑就变了**——
     * 参考图会自己过期。同 `CabinetScreen(now=…)` 的先例。
     */
    now: Long = nowMillis(),
) {
    val sp = LocalAppSpacing.current

    Column(
        // 原型 `.setbody{padding:16px}`：整页左右上下各 16px 内边距。
        // 少了它，进度记录卡会贴着窗口左边缘（参考图 `desk-transferlog-filled` 里 x=0），
        // 与原型（x≈16dp）对不上 —— `design-screen-diff.py --screen d-transferlog` 报的
        // `spacing 只原型{16}` 就是这一处，是**真缺口**，不是 snap。
        modifier = modifier.fillMaxSize().padding(sp.lg),
        // 原型 `.setbody>*{flex:none}`：两张卡之间**没有间距**，靠 `--shadow` 分缝。
        // （`Arrangement.spacedBy(0.dp)` 与默认的 `Arrangement.Top` 逐像素等价，是句空话，删掉。）
    ) {
        // `.grphead`（`.win` 档 `padding:12px 3px 5px`、11px / 600 / `letter-spacing:.06em`）
        Text(
            text = stringResource(Res.string.transfer_log_group_head),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.06.em),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = LocalAppSpacing.current.oxs3, top = sp.md, end = LocalAppSpacing.current.oxs3, bottom = LocalAppSpacing.current.oxs5),
        )

        // 卡 1：`.card > .padc`（`.win` 档 `padding:8px 13px 13px`，原型内联再把底边压到 10px）
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = RelayElevations.Card,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(start = LocalAppSpacing.current.olg13, top = sp.sm, end = LocalAppSpacing.current.olg13, bottom = sp.md10),
            ) {
                // `.tt b`：12px / 600
                Text(
                    text = stringResource(Res.string.transfer_log_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.transfer_log_sub),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
                // `.hint`（`.win` 档 `margin-top:8px` / `line-height:1.55`），原型内联压到 6px
                Text(
                    text = stringResource(Res.string.transfer_log_hint),
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 1.55.em),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = sp.sm6),
                )
            }
        }

        // 卡 2：`.card > ul.tlist`（行 `padding:9px 11px` / `min-height:52px` / 行间 `border-top`）
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = RelayElevations.Card,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (records.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.transfer_log_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    records.forEachIndexed { index, item ->
                        TransferRecordRow(record = item, now = now, onOpenFile = onOpenFile)
                        // `.tlist li+li{border-top:1px solid var(--hair)}` —— 末行没有线
                        if (index != records.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRecordRow(
    record: TransferRecordUi,
    now: Long,
    onOpenFile: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val isFolder = record.isFolder || record.name.endsWith("/")
    // 状态文案：原型 `TSTAT`（`prototype/relay.html:4330-4336`）—— `done:'完成'` / `failed:'失败'` /
    // `canceled:'已取消'`；`d14b-transferlog.png` 里那一行逐字「完成 · 18 项 · 12.4 MB · 今天 14:02」。
    // 注意：文件卡（`folderCard`，`:4351`）对 done 用的是「已完成 / 发送成功」两套词，**本页不用那一套**。
    val statusText = if (record.success) {
        stringResource(Res.string.transfer_log_status_success)
    } else {
        stringResource(Res.string.transfer_log_status_failed)
    }
    // meta = 「状态 · 大小 · 时间」（原型同名行逐字如此）。**没有方向前缀** —— 原型 `.t2` 从来只有
    // 三格（文件夹那几条多一格「N 项」，本端 `TransferRecordUi` 没有项数字段，见 KDoc 登记）；
    // 方向由左边那颗图标承担（原型第 4 行是出站的 `relay-debug.log`，图标用 `ic('up')`）。
    val metaText = "$statusText · ${formatBytes(record.size)} · ${relativeTime(record.at, now)}"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = LocalAppSpacing.current.olg11, vertical = LocalAppSpacing.current.osm9),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
    ) {
        // `.tlist .fi2`：30×30 / radius 8 / `--surface-3` 底 / 16px 图标
        Box(
            modifier = Modifier
                .size(IconSizes.Hero)
                .background(MaterialTheme.colorScheme.surfaceVariant, RelayShapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // 原型那 4 行的图标规则（`prototype/relay.html:4483-4489`）：
                // 文件夹 → `ic('folder')`（第 1、2 行，出站入站都算）；非文件夹出站 → `ic('up')`（第 4 行
                // `relay-debug.log`，`dir:'out'`）；非文件夹入站 → `ic('file')`（第 3 行 `insets-复核-10-07.png`）。
                // 原型 4 行里「出站」与「已到期」两个属性同时只出现在第 4 行，无法从夹具区分；
                // 本端模型只有方向（`TransferRecordUi.isIncoming`）、没有到期态，故按**方向**实现并登记。
                painter = when {
                    isFolder -> painterResource(Res.drawable.ic_folder)
                    !record.isIncoming -> painterResource(Res.drawable.ic_up)
                    else -> painterResource(Res.drawable.ic_file)
                },
                contentDescription = null,
                modifier = Modifier.size(IconSizes.Label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            // `.tlist .t1`：14px / 600
            Text(
                text = record.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // `.tlist .t2`：11px / `--on-2` / `margin-top:1px`
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (onOpenFile != null) {
            // `.win .btn.sm`：`padding:5px 9px` / 11px / `border-radius:9px`（描边，非实心）
            AppOutlinedButton(
                text = stringResource(Res.string.file_open),
                onClick = { record.path?.let { onOpenFile(it) } },
                enabled = record.path != null,
                size = AppButtonSize.Compact,
            )
        }
    }
}