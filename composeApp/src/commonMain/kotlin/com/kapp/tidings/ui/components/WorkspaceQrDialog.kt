// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.PeerCandidateTypes
import com.kapp.tidings.domain.pairingPayload
import com.kapp.tidings.domain.qrCandidatesFor
import com.kapp.tidings.domain.qrPairingPayloadForAddresses
import com.kapp.tidings.core.qr.QrEncoder
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_close
import com.kapp.tidings.generated.resources.workspace_qr_btn
import com.kapp.tidings.generated.resources.workspace_qr_dialog_desc
import com.kapp.tidings.generated.resources.pairing_public_ipv6_hint
import com.kapp.tidings.generated.resources.workspace_qr_no_address
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 工作区直通码（Direct QR）对话框。
 *
 * 展示真实二维码载荷：扫描后可自动完成节点配对与工作区授权。
 */
@Composable
public fun WorkspaceQrDialog(
    wsTitle: String,
    wsId: String?,
    wsPath: String?,
    host: String?,
    port: Int,
    onDismiss: () -> Unit,
    /**
     * 本机全部配对候选（`state.network.localAddresses`），可选；默认空 = 沿用旧的单地址形态。
     *
     * 给了（且 [nodeId] 非空）就走**多候选** JSON 载荷：手机跨网络时才可能选到公网 v6。
     * 旧调用点（不给这两个参数）行为一字不变。
     */
    addresses: List<String> = emptyList(),
    /** 多候选载荷里的 nodeId（手机侧用它显示节点名）。 */
    nodeId: String = "",
    /**
     * 本机 TLS pin（`state.network.selfTlsPin`）。**必须传**（task-86 真机）：
     *
     * 公网长连接的拨号目标要求 `pin != null`（fail-closed），而二维码是主机→手机单向的
     * 信任通道 —— 不印 pin，手机就建不出公网 TLS 目标，只剩局域网明文那条，
     * 在蜂窝上一直 `connect-timeout`（诊断里的 `dialLastTarget` 会停在局域网明文端口上）。
     */
    tlsPin: String? = null,
    /**
     * 本机昵称。带进码里，对方节点表第一眼就是真名（不是 `node-a1b2c3d4`）。
     * 预算同样要算 —— 与 [tlsPin] 同一条纪律，否则裁剪与生成会用两套参数。
     */
    nick: String? = null,
) {
    val sp = LocalAppSpacing.current
    // 多候选载荷装不下 `?wsId=` 这个查询后缀（JSON 后面挂 query 会直接解析失败），而那个后缀
    // `parseQr` 从来没读过（只读 pin/tlsPin）。所以：有候选就发多候选 JSON、丢掉这个死后缀；
    // 没候选就沿用旧形态。若"扫码即授权工作区"以后要真做，它应该是一个 JSON 键 —— 见
    // `multiCandidatePairingPayload` 的可选键 seam。
    // 预算必须**带上 pin**：pin 是 43 个字节，不预留就会出现"裁剪时说装得下、加完 pin 超预算"
    // （那样 hasPublicV6 会宣称码里有公网 v6，而码里其实已经被裁掉了 —— 界面对用户说了假话）。
    val candidates =
        if (nodeId.isNotBlank()) {
            qrCandidatesFor(addresses, port, nodeId, tlsPin = tlsPin, nick = nick)
        } else {
            emptyList()
        }
    val payload = if (candidates.isNotEmpty()) {
        qrPairingPayloadForAddresses(
            nodeId = nodeId,
            addresses = addresses,
            port = port,
            tlsPin = tlsPin,
            nick = nick,
        )
    } else {
        host?.takeIf { it.isNotBlank() }?.let { h ->
            pairingPayload(h, port)?.let { base ->
                val w = wsId?.trim()
                if (!w.isNullOrBlank()) "$base?wsId=$w" else base
            }
        }
    }
    val hasPublicV6 = candidates.any { it.type == PeerCandidateTypes.PUBLIC_V6 }
    val qrCode = payload?.let { runCatching { QrEncoder.encode(it) }.getOrNull() }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.workspace_qr_btn)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(sp.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = wsTitle.ifBlank { wsId.orEmpty() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!wsPath.isNullOrBlank()) {
                    Text(
                        text = wsPath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (qrCode != null) {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = 2.dp,
                        modifier = Modifier.padding(vertical = sp.xs),
                    ) {
                        QrCodeView(
                            code = qrCode,
                            contentDescription = stringResource(Res.string.workspace_qr_btn),
                            modifier = Modifier.padding(sp.sm).size(180.dp),
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = sp.sm),
                    ) {
                        Text(
                            text = stringResource(Res.string.workspace_qr_no_address),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(sp.md),
                        )
                    }
                }
                Text(
                    text = stringResource(Res.string.workspace_qr_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // 码里有公网地址 ≠ 能连上：主机所在网络必须允许入站。如实写在码旁边，
                // 而不是让用户扫完得到一个超时再回来问。
                if (qrCode != null && hasPublicV6) {
                    Text(
                        text = stringResource(Res.string.pairing_public_ipv6_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (payload != null) {
                    Text(
                        text = payload,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_close),
                onClick = onDismiss,
                primary = true,
            )
        },
    )
}
