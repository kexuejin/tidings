// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.relay.app.ExternalPairingPreview
import app.relay.generated.resources.Res
import app.relay.generated.resources.common_cancel
import app.relay.generated.resources.external_pairing_allow
import app.relay.generated.resources.external_pairing_body_node
import app.relay.generated.resources.external_pairing_body_pins_key
import app.relay.generated.resources.external_pairing_body_relay
import app.relay.generated.resources.external_pairing_body_unknown
import app.relay.generated.resources.external_pairing_title
import app.relay.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 外部来源配对（deep link / 调试注入）的二次确认（task-54）。
 *
 * ## 为什么要有它
 *
 * 这两条入口都在**应用自身的 UI 之外**：一条是 `relay://` / `relay-pair://` 深链
 * （`exported=true` + BROWSABLE ⇒ 任意应用或一条被诱导点击的链接都能触发），另一条是
 * debug 包的 `app.relay.TEST_INJECT_QR` 导出广播。它们最终都走 `handleQrCode` ——
 * 那条路会**新增/改写手动节点、写候选池、并落下对端的 TLS pin**，而公网 TLS 的
 * fail-closed 判定正建在这枚 pin 上。**用户点了一条链接 ≠ 同意固定一枚密钥**。
 *
 * ## 文案纪律：别比事实更确定
 *
 * - 只有载荷里**真的带 pin**（[ExternalPairingPreview.hasPin]）才说「并固定它的密钥」；
 * - 载荷解析不出节点时如实说解析不出，而不是编一个"某个节点"；
 * - 中继载荷说的是它真的会做的事（写 url + token），不套用"配对并固定密钥"。
 *
 * 拒绝 = 什么都不做：这个 composable 只把两个意图（[onAllow] / [onDeny]）交出去，
 * 调用方在 [onAllow] 里才允许调用有副作用的 `handleQrCodeAsync`。
 */
// 跨模块调用点：宿主 `androidApp` 的 MainActivity 要画它（那是外部入口所在的模块），
// internal 在那边看不见 —— 与 `WorkspaceGroupPromptDialog` 同一个理由。
@Composable
public fun ExternalPairingConfirmDialog(
    preview: ExternalPairingPreview?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    val body = when (preview?.kind) {
        "lan-peer" -> {
            // 身份回落链：昵称 → nodeId → 地址。三者都拿不到是不可能的（地址必非空），
            // 但真拿不到时走下面那个"解析不出"的诚实分支，而不是印一个空 %1$s。
            val label = preview.nick?.takeIf { it.isNotBlank() }
                ?: preview.nodeId?.takeIf { it.isNotBlank() }
                ?: preview.address?.takeIf { it.isNotBlank() }
            if (label == null) {
                stringResource(Res.string.external_pairing_body_unknown)
            } else {
                buildString {
                    append(stringResource(Res.string.external_pairing_body_node, label))
                    if (preview.hasPin) {
                        append(" ")
                        append(stringResource(Res.string.external_pairing_body_pins_key))
                    }
                }
            }
        }

        "remote-relay" -> stringResource(Res.string.external_pairing_body_relay)
        else -> stringResource(Res.string.external_pairing_body_unknown)
    }
    RelayDialog(
        onDismissRequest = onDeny,
        title = { Text(stringResource(Res.string.external_pairing_title)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.external_pairing_allow),
                primary = true,
                onClick = onAllow,
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDeny,
            )
        },
    )
}
