// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.relay.generated.resources.Res
import app.relay.generated.resources.common_close
import app.relay.generated.resources.diag_adaptive_title
import app.relay.generated.resources.r2_set_insets_device_hint
import app.relay.generated.resources.r2_set_insets_device_title
import app.relay.generated.resources.r2_set_insets_gate
import app.relay.generated.resources.r2_set_insets_goal
import app.relay.generated.resources.r2_set_insets_iphone
import app.relay.generated.resources.r2_set_insets_k_bottom
import app.relay.generated.resources.r2_set_insets_k_bottom_nav
import app.relay.generated.resources.r2_set_insets_k_gap
import app.relay.generated.resources.r2_set_insets_k_gate
import app.relay.generated.resources.r2_set_insets_k_icon_brightness
import app.relay.generated.resources.r2_set_insets_k_ios
import app.relay.generated.resources.r2_set_insets_k_iphone
import app.relay.generated.resources.r2_set_insets_k_pixel6
import app.relay.generated.resources.r2_set_insets_k_status_bar
import app.relay.generated.resources.r2_set_insets_new_bottom
import app.relay.generated.resources.r2_set_insets_new_brightness
import app.relay.generated.resources.r2_set_insets_new_hint
import app.relay.generated.resources.r2_set_insets_new_ios
import app.relay.generated.resources.r2_set_insets_new_status
import app.relay.generated.resources.r2_set_insets_new_title
import app.relay.generated.resources.r2_set_insets_old_bottom
import app.relay.generated.resources.r2_set_insets_old_gap
import app.relay.generated.resources.r2_set_insets_old_hint
import app.relay.generated.resources.r2_set_insets_old_status
import app.relay.generated.resources.r2_set_insets_old_title
import app.relay.generated.resources.r2_set_insets_pixel6
import app.relay.generated.resources.ic_check_double
import app.relay.ui.SectionCard
import app.relay.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 适配对照与诊断弹窗 (m-insets)。
 *
 * 原型 `prototype/relay.html:3975-4004 screenInsets()` 是一整页：`topbar` + 一条
 * `.banner.ok`（目标）+ 三张 `box()` 卡（旧版（真机症状）/ 新版（现状）/ 真机验收，
 * 每张 = `.card>h3` 标题 + `.hint` 副题 + 若干 `.kv` 行，行内 `padding:4px 0`）。
 * 本端把它装进弹窗（容器形态差已登记），**卡与行的文字逐字照抄**：用户点「适配对照」
 * 看到的必须是同一份信息，不能只剩一颗标题 + 一行 kv。
 *
 * 唯一不画的是原型自己标注的 `.grphead 本原型可切换` 那一块（`:3997-4002`）：它的
 * `toggleInsets()` 是**原型自测开关**（把整个原型切回旧版表现），产品里没有对应行为，
 * 画出来就是一颗点了没反应的假按钮。
 */
@Composable
fun AdaptiveDiagDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.diag_adaptive_title)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.md10),
            ) {
                // 原型 `.banner.ok`（`:3982-3983`；CSS `:354-360`）：primary-c 底 + 16px 勾 +
                // 正文，padding `10px 12px`、gap 9px、圆角 `--r-ctl`。
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = sp.md10, vertical = sp.md),
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_check_double),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(Res.string.r2_set_insets_goal),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                DiagSection(
                    title = stringResource(Res.string.r2_set_insets_old_title),
                    hint = stringResource(Res.string.r2_set_insets_old_hint),
                    rows = listOf(
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_status_bar),
                            stringResource(Res.string.r2_set_insets_old_status),
                            true,
                        ),
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_bottom),
                            stringResource(Res.string.r2_set_insets_old_bottom),
                            true,
                        ),
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_gap),
                            stringResource(Res.string.r2_set_insets_old_gap),
                            false,
                        ),
                    ),
                )

                DiagSection(
                    title = stringResource(Res.string.r2_set_insets_new_title),
                    hint = stringResource(Res.string.r2_set_insets_new_hint),
                    rows = listOf(
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_status_bar),
                            stringResource(Res.string.r2_set_insets_new_status),
                            false,
                        ),
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_bottom_nav),
                            stringResource(Res.string.r2_set_insets_new_bottom),
                            false,
                        ),
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_icon_brightness),
                            stringResource(Res.string.r2_set_insets_new_brightness),
                            false,
                        ),
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_ios),
                            stringResource(Res.string.r2_set_insets_new_ios),
                            false,
                        ),
                    ),
                )

                DiagSection(
                    title = stringResource(Res.string.r2_set_insets_device_title),
                    hint = stringResource(Res.string.r2_set_insets_device_hint),
                    rows = listOf(
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_pixel6),
                            stringResource(Res.string.r2_set_insets_pixel6),
                            true,
                        ),
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_iphone),
                            stringResource(Res.string.r2_set_insets_iphone),
                            true,
                        ),
                        Triple(
                            stringResource(Res.string.r2_set_insets_k_gate),
                            stringResource(Res.string.r2_set_insets_gate),
                            false,
                        ),
                    ),
                )
            }
        },
        confirmButton = {
            RelayDialogButton(text = stringResource(Res.string.common_close), onClick = onDismiss)
        },
        modifier = modifier,
    )
}

/**
 * 原型 `box(title, sub, rows)`（`:3976-3977`）= 一张 `.card`：`.card>h3` 标题 +
 * `.padc` 里的 `.hint` 副题 + 若干 `.kv` 行。这里用 [SectionCard]（它就是 `.card` 的
 * 代码对应物）承载同一结构。
 */
@Composable
private fun DiagSection(
    title: String,
    hint: String,
    rows: List<Triple<String, String, Boolean>>,
) {
    val sp = LocalAppSpacing.current
    SectionCard(title = title) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = sp.sm),
        )
        rows.forEach { (label, value, error) ->
            DiagRow(label = label, value = value, error = error)
        }
    }
}

/**
 * 原型 `row(k,v,c)`（`:3978-3979`）：`.kv` 行内 `padding:4px 0`，标签列
 * `.kv .k{color:var(--on-2);width:var(--kv-k)}`（`--kv-k:118px`，`:59`）是**定宽**、
 * 值列 `.kv .v{flex:1}` 吃掉剩下的；`c` 为真时值走 `--on-error-c`（红）——
 * 「真机症状」那两行与真机参数那两行在原型里就是红的。
 *
 * 两列都用 `weight`（1 : 2）而不是给标签钉 118dp：原型 118 : 231 ≈ 1 : 1.96，
 * 而 `weight` 没有字面量、窄容器里也不会把「图标亮度」挤成竖排（首版给标签
 * `weight(1f)` + 值不设权重就是这个下场：值的固有宽度把标签挤到一列一字）。
 *
 * 不复用 [InfoRow]/`KvRow`：它们没有「值走错误色」这一档。
 */
@Composable
private fun DiagRow(label: String, value: String, error: Boolean) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = sp.xs),
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(2f),
        )
    }
}
