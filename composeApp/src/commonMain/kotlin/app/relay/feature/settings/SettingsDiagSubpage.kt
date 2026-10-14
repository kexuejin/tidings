// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Window
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.generated.resources.ic_info
import app.relay.generated.resources.set_diag_card_title
import app.relay.generated.resources.set_diag_crypto
import app.relay.generated.resources.set_diag_crypto_value
import app.relay.generated.resources.set_diag_disabled
import app.relay.generated.resources.set_diag_enabled
import app.relay.generated.resources.set_diag_group_text
import app.relay.generated.resources.set_diag_inset
import app.relay.generated.resources.set_diag_inset_value
import app.relay.generated.resources.set_diag_peers_value
import app.relay.generated.resources.set_diag_protocol
import app.relay.generated.resources.set_diag_protocol_value
import app.relay.generated.resources.set_diag_queued
import app.relay.generated.resources.set_diag_selfcheck
import app.relay.generated.resources.set_diag_selfcheck_unavailable
import app.relay.generated.resources.set_diag_subtitle
import app.relay.generated.resources.set_diag_title
import app.relay.generated.resources.set_node_id
import app.relay.generated.resources.set_peer_count
import app.relay.ui.SectionCard
import app.relay.ui.components.AdaptiveDiagDialog
import app.relay.ui.components.InfoRow
import app.relay.generated.resources.r2_set_diag_insets_sub
import app.relay.generated.resources.r2_set_diag_insets_title
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.RelayElevations
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 · 诊断（原型 `screenSetDiag()`，`prototype/relay.html:3948`）。
 *
 * 原型这一页：卡「实时状态」＋七条 kv（节点 ID / 协议 / 已知节点 / 排队消息 /
 * group-text 已启用 / 传输加密 / 状态栏 inset）＋禁用态「运行自检」＋一句 hint ＋
 * 一条 `.navrow`「适配对照」通向 `m-insets`。标题栏是它自己的「诊断 / 实时状态，非配置」。
 *
 * 「重新自检」灰着而不解释原因，用户只会以为程序卡了。**不能**传一个空 lambda
 * 把它"点亮" —— 那只是把"点不动"换成"点了没反应"，两种都在骗人。
 * 诚实的做法是说清当前真实状态：这一屏显示的都是实时派生量，
 * `RelayController` 至今没有自检/诊断入口（没有任何公开 API 能重跑它们）。
 */
@Composable
fun SettingsDiagSubpage(
    state: RelayUiState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    protocolSummary: String? = null,
    groupTextEnabled: Boolean = true,
    onRunSelfCheck: (() -> Unit)? = null,
) {
    var showAdaptiveDiag by rememberSaveable { mutableStateOf(false) }

    val onlineCount = state.onlineCount
    val peerCount = state.peers.size
    if (showAdaptiveDiag) {
        AdaptiveDiagDialog(onDismiss = { showAdaptiveDiag = false })
    }

    SettingsPageScaffold(
        title = stringResource(Res.string.set_diag_title),
        subtitle = stringResource(Res.string.set_diag_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        // 原型把这张卡的标题写成「实时状态」（`prototype/relay.html:3952`），页面标题才是「诊断」。
        // 两者不是同一个词，不要合并 —— 合并后卡头就成了页面标题的复读。
        SectionCard(
            title = stringResource(Res.string.set_diag_card_title),
            icon = Res.drawable.ic_info,
        ) {
            InfoRow(label = stringResource(Res.string.set_node_id), value = state.settings.nodeId)
            InfoRow(
                label = stringResource(Res.string.set_diag_protocol),
                // 兼容串是 UI 文案而不是数据：留在 Kotlin 里等于把它钉死成不可翻译的
                // 中文常量，而这里本来就是「没传就用当前协议说明」的默认值。
                value = protocolSummary ?: stringResource(Res.string.set_diag_protocol_value),
            )
            InfoRow(
                label = stringResource(Res.string.set_peer_count),
                value = stringResource(Res.string.set_diag_peers_value, peerCount, onlineCount, peerCount - onlineCount),
            )
            InfoRow(label = stringResource(Res.string.set_diag_queued), value = state.totalPending.toString())
            KvRow(
                label = stringResource(Res.string.set_diag_group_text),
                value = if (groupTextEnabled) stringResource(Res.string.set_diag_enabled) else stringResource(Res.string.set_diag_disabled),
                valueColor = if (groupTextEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                mono = true,
                emphasize = true,
            )
            // 原型顺序是「传输加密」在「状态栏 inset」之前（`prototype/relay.html:3959-3960`）。
            InfoRow(label = stringResource(Res.string.set_diag_crypto), value = stringResource(Res.string.set_diag_crypto_value))
            InfoRow(label = stringResource(Res.string.set_diag_inset), value = stringResource(Res.string.set_diag_inset_value))

            // 原型 `.cardfoot{display:flex;justify-content:flex-end;gap:8px;padding:10px 14px}`
            // （`prototype/relay.html:128`）：一枚**内容宽**的按钮右对齐。
            // 原来写的是 `weight(1f)` 撑满整行 —— 原型里没有任何一颗按钮是这样。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = { onRunSelfCheck?.invoke() },
                    enabled = onRunSelfCheck != null,
                ) {
                    // 原型这颗按钮的字面是「运行自检」（`prototype/relay.html:3962`），不是「重新自检」。
                    Text(stringResource(Res.string.set_diag_selfcheck))
                }
            }
            if (onRunSelfCheck == null) {
                Text(
                    text = stringResource(Res.string.set_diag_selfcheck_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                )
            }
        }

        // 原型的「适配对照」是**卡外**的一条 `.navrow`（`prototype/relay.html:3966-3971`）：
        // `.ni2` 图标块 + `.bv .t1`「适配对照」+ `.t2`「顶部空白 / 导航栏 / 状态栏：真机症状与现状对照」
        // + `.chev`。复用了设置首页那条 `SettingsNavRow`，只把它包进 `.navrow` 自己的
        // `background:var(--surface);border-radius:var(--r-card);box-shadow:var(--shadow)` 外壳里
        // （原来是一块 `surfaceVariant` + `radius 8` 的死灰块，`8` 在原型里根本没有 ——
        // 差集里 `radius 只代码{8}` 就是它）。
        // 已知行为差（登记在 §2.26）：原型 `go('m-insets')` 跳整页，代码开 `AdaptiveDiagDialog`。
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = RelayElevations.Card,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SettingsNavRow(
                icon = Icons.Outlined.Window,
                title = stringResource(Res.string.r2_set_diag_insets_title),
                sub = stringResource(Res.string.r2_set_diag_insets_sub),
                valText = null,
                onClick = { showAdaptiveDiag = true },
            )
        }
    }
}
