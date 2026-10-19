// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.app.pairingQrPayloadFor
import app.relay.core.protocol.Protocol
import app.relay.core.qr.QrEncoder
import app.relay.generated.resources.Res
import app.relay.generated.resources.cidr_add_action
import app.relay.generated.resources.cidr_delete
import app.relay.generated.resources.cidr_empty_ranges
import app.relay.generated.resources.cidr_input_hint
import app.relay.generated.resources.cidr_input_label
import app.relay.generated.resources.cidr_scan_now
import app.relay.generated.resources.cidr_scan_progress
import app.relay.generated.resources.cidr_scanning
import app.relay.generated.resources.cidr_section_title
import app.relay.generated.resources.cidr_source_peer
import app.relay.generated.resources.cidr_source_self
import app.relay.generated.resources.r2_set_conn_card_port
import app.relay.generated.resources.ic_link
import app.relay.generated.resources.nodes_my_qr_desc
import app.relay.generated.resources.nodes_my_qr_hint
import app.relay.generated.resources.nodes_my_qr_title
import app.relay.generated.resources.set_conn_default_port
import app.relay.generated.resources.set_conn_default_port_note
import app.relay.generated.resources.set_conn_qr_fallback
import app.relay.generated.resources.set_conn_qr_no_address
import app.relay.generated.resources.set_conn_qr_payload
import app.relay.generated.resources.set_conn_qr_too_long
import app.relay.generated.resources.set_conn_reconnect
import app.relay.generated.resources.set_conn_reconnecting
import app.relay.generated.resources.set_conn_reset
import app.relay.generated.resources.set_conn_save
import app.relay.generated.resources.set_conn_title
import app.relay.generated.resources.set_keep_alive_card_title
import app.relay.generated.resources.set_keep_alive_desc
import app.relay.generated.resources.set_keep_alive_title
import app.relay.generated.resources.set_conn_subtitle
import app.relay.ui.ContentWidths
import app.relay.ui.SectionCard
import app.relay.ui.centerCapped
import app.relay.ui.components.LargeTitleBar
import app.relay.ui.components.QrCodeView
import app.relay.ui.components.TitleBarDivider
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.theme.LocalAppSpacing

/** 二级子页：连接设置。 */
@Composable
fun SettingsConnectionSubpage(
    state: RelayUiState,
    defaultPort: String?,
    onSavePorts: (String, String) -> Unit,
    onRestart: () -> Unit,
    onAddScanRange: (String) -> Unit = {},
    onRemoveScanRange: (String) -> Unit = {},
    onTriggerCidrScan: (String?) -> Unit = {},
    /**
     * 「后台保持连接」这条能力本平台有没有（Android 有；桌面端与 iOS 的进程模型不需要它）。
     * false = 整张卡不画 —— 一个按下去没反应的开关比没有更糟。
     */
    keepAliveSupported: Boolean = false,
    keepAliveEnabled: Boolean = true,
    onKeepAliveChange: (Boolean) -> Unit = {},
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val portSeed = defaultPort ?: settings.udpPort.toString()
    var portText by remember(portSeed) { mutableStateOf(portSeed) }
    // 本地状态：回调接上后它会被外部的值覆盖，没接上时交互仍然是真的
    // （与 SettingsNotifySubpage 那三个开关同一套写法）。
    var keepAlive by rememberSaveable(keepAliveEnabled) { mutableStateOf(keepAliveEnabled) }

    Column(modifier = modifier.fillMaxSize()) {
        LargeTitleBar(
            title = stringResource(Res.string.set_conn_title),
            subtitle = stringResource(Res.string.set_conn_subtitle),
            onBack = onBack,
            contentMaxWidth = ContentWidths.Settings,
        )
        TitleBarDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                // 宽屏收列（原型 `.pad.set{max-width:640px;margin:0 auto}`，:1045）。
                // 放在 verticalScroll **之前**：被夹过的这一列就是滚动容器本身，
                // 于是滚动条、overscroll 与内容列边界三者重合，不会出现"能滚的比能看的宽"。
                .centerCapped(ContentWidths.Settings)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = LocalAppSpacing.current.lg,
                    top = LocalAppSpacing.current.lg14,
                    end = LocalAppSpacing.current.lg,
                    bottom = LocalAppSpacing.current.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            // 原型卡头是「端口」而不是页标题那句「连接」（`prototype/relay.html:3818`）：
            // `<h3>${ic('link')} 端口<span class="r">恢复默认</span></h3>` ——「恢复默认」是卡头
            // 右端那枚 `.card>h3 .r`（11px / weight 500 / on-2），走 `SectionCard` 的 `trailing` 槽，
            // 不是独占一行的内容（旧注释说 SectionCard 没有 trailing 插槽，已过时）。
            SectionCard(
                title = stringResource(Res.string.r2_set_conn_card_port),
                icon = Res.drawable.ic_link,
                trailing = {
                    Text(
                        text = stringResource(Res.string.set_conn_reset),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            portText = Protocol.DEFAULT_UDP_PORT.toString()
                        },
                    )
                },
            ) {
                LabeledFieldWithKey(
                    label = stringResource(Res.string.set_conn_default_port),
                    fieldKey = KEY_DEFAULT_PORT,
                    value = portText,
                    onValueChange = { portText = it },
                    modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                    digitsOnly = true,
                    supporting = stringResource(Res.string.set_conn_default_port_note),
                )

                // 原型 `.kv{padding:5px 0}` 的 TCP 行（`prototype/relay.html:3824`）：
                // UDP 监听端口下面紧跟对外的 TCP 端口（`settings.tcpPort`）—— 第一轮整行漏了。
                KvRow(label = "TCP", value = settings.tcpPort.toString(), mono = true)

                // 原型 `.cardfoot{justify-content:flex-end;gap:8px}`：两枚**内容宽**按钮右对齐，
                // 描边的「重连」在前、实心的「保存连接配置」在后（原来顺序反了，且保存被撑满整行）。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm, Alignment.End),
                ) {
                    OutlinedButton(enabled = !state.restarting, onClick = onRestart) {
                        Text(
                            stringResource(
                                if (state.restarting) Res.string.set_conn_reconnecting
                                else Res.string.set_conn_reconnect,
                            ),
                        )
                    }
                    Button(
                        onClick = {
                            val port = portText.trim().ifEmpty { settings.udpPort.toString() }
                            onSavePorts(port, settings.tcpPort.toString())
                        },
                    ) {
                        Text(stringResource(Res.string.set_conn_save))
                    }
                }
            }

            // 「我的二维码」。这张码原来常驻节点页，原型 Gen2 把它搬到了这一页
            // （`prototype/relay.html:3437-3448` 的 KDoc）：它本身不是「节点」，是**本机的
            // 身份凭证**，占节点页一整张卡（实测 276px）会把「有哪些节点」这个主任务挤到
            // 第二屏。节点页只留「添加节点」里那一条入口，两个方向仍在同一屏。
            // 位置也照原型：卡二，夹在「端口」与「网段探测与同步」之间。
            SectionCard(title = stringResource(Res.string.nodes_my_qr_title)) {
                // 带全部候选（多候选二维码载荷）：只给一个 IPv4 时，跨网络的对方扫了也没用。
                // 没有可用候选时退回单地址形态，与旧行为一致。
                // task-86：这张常驻码也必须带 pin，否则跨网手机建不出公网 TLS 目标
                // （真机症状：dialLastTarget 一直是局域网明文口 ⇒ connect-timeout）。
                val qrPayload = pairingQrPayloadFor(
                    host = state.network.localAddresses.firstOrNull(),
                    addresses = state.network.localAddresses,
                    port = settings.udpPort,
                    nodeId = state.selfNodeId,
                    // task-86：**必须**带 pin（公网目标要求 pin != null，fail-closed）。
                    tlsPin = state.network.selfTlsPin,
                    // 带昵称：对方扫完立刻看见真名，而不是 node-a1b2c3d4。
                    nick = state.settings.nick,
                )
                // 编码器对超长内容抛异常（它拒绝截断）；这里既不能让它把整屏打崩，
                // 也绝不画一张「能扫、但内容是错的」码 —— 三种情况各自说清。
                val qrCode = qrPayload?.let { runCatching { QrEncoder.encode(it) }.getOrNull() }
                when {
                    qrPayload == null -> Text(
                        text = stringResource(Res.string.set_conn_qr_no_address),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    qrCode == null -> Text(
                        text = stringResource(Res.string.set_conn_qr_too_long),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            QrCodeView(
                                code = qrCode,
                                // 「对方怎么用这张码」这一句与节点页那张卡是同一句，
                                // 复用同一条资源，避免两份文案各漂各的。
                                contentDescription = stringResource(Res.string.nodes_my_qr_desc),
                                modifier = Modifier.size(180.dp),
                            )
                        }
                        Text(
                            text = stringResource(Res.string.nodes_my_qr_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.md),
                        )
                        KvRow(
                            label = stringResource(Res.string.set_conn_qr_payload),
                            value = qrPayload,
                            mono = true,
                        )
                        Text(
                            text = stringResource(Res.string.set_conn_qr_fallback),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                        )
                    }
                }
            }

            SectionCard(title = stringResource(Res.string.cidr_section_title)) {
                var newCidr by remember { mutableStateOf("") }

                state.scanProgress?.let { progress ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.sm),
                    ) {
                        Column(Modifier.padding(LocalAppSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs)) {
                            Text(
                                text = stringResource(
                                    Res.string.cidr_scan_progress,
                                    progress.currentCidr,
                                    progress.scannedHosts,
                                    progress.totalHosts,
                                    progress.discoveredCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newCidr,
                        onValueChange = { newCidr = it },
                        label = { Text(stringResource(Res.string.cidr_input_label)) },
                        placeholder = { Text(stringResource(Res.string.cidr_input_hint)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            val target = newCidr.trim()
                            if (target.isNotEmpty()) {
                                onAddScanRange(target)
                                newCidr = ""
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.cidr_add_action))
                    }
                }

                if (settings.scanRanges.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.cidr_empty_ranges),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = LocalAppSpacing.current.md),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                        verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                    ) {
                        settings.scanRanges.forEach { entry ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = entry.cidr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        val sourceDesc = if (entry.source == "self") {
                                            stringResource(Res.string.cidr_source_self)
                                        } else {
                                            stringResource(Res.string.cidr_source_peer, entry.sourceNick ?: (entry.sourceNodeId?.take(8) ?: ""))
                                        }
                                        Text(
                                            text = sourceDesc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { onTriggerCidrScan(entry.cidr) }) {
                                        Text(stringResource(Res.string.cidr_scan_now))
                                    }
                                    TextButton(onClick = { onRemoveScanRange(entry.cidr) }) {
                                        Text(stringResource(Res.string.cidr_delete))
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { onTriggerCidrScan(null) },
                        modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                        enabled = state.scanProgress == null,
                    ) {
                        Text(stringResource(if (state.scanProgress != null) Res.string.cidr_scanning else Res.string.cidr_scan_now))
                    }
                }
            }

            // 「后台保持连接」（Android 前台服务保活）。放在这一页最后一张卡：
            // 它是"退到后台还能不能被找到"的行为开关，与前面的端口 / 二维码 / 网段
            // 都不是同一件事，但都属于"连接"这个语境。能力不存在时整张卡不画。
            if (keepAliveSupported) {
                SectionCard(
                    title = stringResource(Res.string.set_keep_alive_card_title),
                    icon = Res.drawable.ic_link,
                ) {
                    SettingSwitchRow(
                        title = stringResource(Res.string.set_keep_alive_title),
                        subtitle = stringResource(Res.string.set_keep_alive_desc),
                        checked = keepAlive,
                        onCheckedChange = { next ->
                            keepAlive = next
                            onKeepAliveChange(next)
                        },
                    )
                }
            }

        }
    }
}
