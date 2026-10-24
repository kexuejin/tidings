// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Window
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.domain.DiagnosticsExportFailure
import com.kapp.tidings.domain.DiagnosticsExportResult
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.diag_export_done_saved
import com.kapp.tidings.generated.resources.diag_export_done_shared
import com.kapp.tidings.generated.resources.diag_export_failed_unexpected
import com.kapp.tidings.generated.resources.diag_export_failed_write
import com.kapp.tidings.generated.resources.diag_export_hint
import com.kapp.tidings.generated.resources.diag_export_package
import com.kapp.tidings.generated.resources.diag_export_running
import com.kapp.tidings.generated.resources.ic_copy
import com.kapp.tidings.generated.resources.ic_eye
import com.kapp.tidings.generated.resources.ic_info
import com.kapp.tidings.generated.resources.set_diag_card_title
import com.kapp.tidings.generated.resources.set_diag_cert_expiry
import com.kapp.tidings.generated.resources.set_diag_cert_expiry_hint
import com.kapp.tidings.generated.resources.set_diag_copy_node_id
import com.kapp.tidings.generated.resources.set_diag_crypto
import com.kapp.tidings.generated.resources.set_diag_crypto_value
import com.kapp.tidings.generated.resources.set_diag_crypto_value_lan_cross
import com.kapp.tidings.generated.resources.set_diag_disabled
import com.kapp.tidings.generated.resources.set_diag_enabled
import com.kapp.tidings.generated.resources.set_diag_group_text
import com.kapp.tidings.generated.resources.set_diag_ime_smoke
import com.kapp.tidings.generated.resources.set_diag_inset
import com.kapp.tidings.generated.resources.set_diag_inset_value
import com.kapp.tidings.generated.resources.set_diag_peers_value
import com.kapp.tidings.generated.resources.set_diag_protocol
import com.kapp.tidings.generated.resources.set_diag_protocol_value
import com.kapp.tidings.generated.resources.set_diag_queued
import com.kapp.tidings.generated.resources.set_diag_selfcheck
import com.kapp.tidings.generated.resources.set_diag_selfcheck_unavailable
import com.kapp.tidings.generated.resources.set_diag_selfcheck_unavailable_no_export
import com.kapp.tidings.generated.resources.set_diag_subtitle
import com.kapp.tidings.generated.resources.set_diag_title
import com.kapp.tidings.generated.resources.set_node_id
import com.kapp.tidings.generated.resources.set_peer_count
import com.kapp.tidings.generated.resources.settings_copy_done
import com.kapp.tidings.ui.SectionCard
import com.kapp.tidings.ui.components.AdaptiveDiagDialog
import com.kapp.tidings.ui.components.AppIconButton
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.InfoRow
import com.kapp.tidings.ui.components.formatDay
import com.kapp.tidings.generated.resources.r2_set_diag_insets_sub
import com.kapp.tidings.generated.resources.r2_set_diag_insets_title
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayElevations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 · 诊断（原型 `screenSetDiag()`，`prototype/relay.html:3948`）。
 *
 * 原型这一页：卡「实时状态」＋七条 kv（节点 ID / 协议 / 已知节点 / 排队消息 /
 * group-text 已启用 / 传输加密 / 状态栏 inset）＋「运行自检」＋一句 hint ＋
 * 一条 `.navrow`「适配对照」通向 `m-insets`。标题栏是它自己的「诊断 / 实时状态，非配置」。
 *
 * 「运行自检」是一颗**可空**按钮，判据与 `AppPlatform.receivedFileActions` /
 * `MessageComposer` 同一条：宿主给得出真实动作就传 [onRunSelfCheck]，按钮才画；
 * 给不出就传 null，按钮**整颗不画**，只在原位留一句说明。
 *
 * 为什么不是"画一颗灰的"：它是这一页唯一的动作，一颗永远灰着的按钮读起来像页面坏了
 * 一半。**也不能**传一个空 lambda 把它"点亮" —— 那只是把"点不动"换成"点了没反应"，
 * 两种都在骗人。`RelayController` 至今没有自检入口（没有任何公开 API 能重跑那些检查），
 * 所以 [onRunSelfCheck] 在所有宿主上都是 null，这一屏显示的都是实时派生量。
 *
 * 「导出诊断包」（[onExportDiagnostics]）是同一页上的另一个动作，走同一条可空槽纪律：
 * 手机组合根把它接到 `RelayController.exportDiagnosticsPackage`；没有这条出口的宿主不接，
 * 这一页就不画这颗按钮 —— 而不是画一颗按下去什么都不发生的。
 */
@Composable
fun SettingsDiagSubpage(
    state: RelayUiState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    protocolSummary: String? = null,
    groupTextEnabled: Boolean = true,
    onRunSelfCheck: (() -> Unit)? = null,
    /**
     * 「导出诊断包」。同样是**可空槽**：宿主给得出真实动作就画按钮，给不出就整颗不画
     * （与 [onRunSelfCheck] 同一条纪律，见 `DeadButtonGuardTest`）。
     *
     * `suspend` 是因为打包要读日志、写 zip、再交给系统分享面板 —— 这些都在 IO 上，
     * 页面自己用 `rememberCoroutineScope` 起协程，结果回来再落到这一屏的一行说明上。
     */
    onExportDiagnostics: (suspend () -> DiagnosticsExportResult)? = null,
    /**
     * 「输入法自检」的入口。宿主给得出就画，给不出就不画（同 [onRunSelfCheck] 的可空槽纪律）。
     *
     * 为什么需要它：那个自检页此前是**孤儿页**（`ImeSmokePane` 零处调用），
     * 用户在文档里看到「设置 → 诊断 → 输入法自检」，却没有任何一条路能走到。
     * 桌面端打不进空格这条缺陷，正是**没有它就做不了二分**。
     *
     * 它是**独立一行**（NavRow），不嵌在「运行自检」那颗按钮的分支里 ——
     * 以前嵌在里面，而 `onRunSelfCheck` 全宿主为 null，于是这行入口在任何宿主上都出不来。
     */
    onOpenImeSmoke: (() -> Unit)? = null,
    /**
     * 「状态栏 inset」这一行要不要画。这行的语义只存在于**有系统 insets 的平台**（Android）；
     * 桌面没有状态栏，画一行「24 dp」是编造读数 —— 桌面宿主传 false 整行不画。
     */
    statusBarInsetShown: Boolean = true,
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
            // 节点 ID 那串字没法手抄，行尾给一颗「复制」（行内回执自清，与连接页载荷同一套范式）。
            val clipboard = LocalClipboardManager.current
            var nodeIdCopied by remember { mutableStateOf(false) }
            LaunchedEffect(nodeIdCopied) {
                if (nodeIdCopied) {
                    delay(2_000)
                    nodeIdCopied = false
                }
            }
            KvRow(
                label = stringResource(Res.string.set_node_id),
                value = state.settings.nodeId,
            ) {
                AppIconButton(
                    icon = Res.drawable.ic_copy,
                    // 「复制」两个字在这一屏有两个对象（节点 ID 在这张卡、二维码载荷在连接页），
                    // 读屏用户听到同一句无法分辨；这里说清复制的是什么。
                    contentDescription = stringResource(Res.string.set_diag_copy_node_id),
                    onClick = {
                        clipboard.setText(AnnotatedString(state.settings.nodeId))
                        nodeIdCopied = true
                    },
                )
            }
            if (nodeIdCopied) {
                Text(
                    text = stringResource(Res.string.settings_copy_done),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            InfoRow(
                label = stringResource(Res.string.set_diag_protocol),
                // 兼容串是 UI 文案而不是数据：留在 Kotlin 里等于把它钉死成不可翻译的
                // 中文常量，而这里本来就是「没传就用当前协议说明」的默认值。
                // 兜底值从 core 的 Protocol.VERSION 拼出来 —— 以前写死的「v1 · 与 desktop
                // 0.61.1 兼容」是两处会随实现漂移的死数。
                value = protocolSummary ?: stringResource(Res.string.set_diag_protocol_value, Protocol.VERSION),
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
            // 原型顺序是「传输加密」在「状态栏留白」之前（`prototype/relay.html:3959-3960`）。
            //
            // **这一行的值必须与下面那行证书读数同源判档**：本机有 TLS 身份（证书/引脚存在）
            // 时，跨网那条路走的就是 TLS + 双向 pin，局域网仍是明文 —— 旧代码恒定写
            // 「无 · 局域网内明文」，配上同屏可见的「证书到期」，等于一张卡自己打自己。
            val hasTlsIdentity = state.network.selfCertNotAfterMillis != null || state.network.selfTlsPin != null
            InfoRow(
                label = stringResource(Res.string.set_diag_crypto),
                value = stringResource(
                    when (cryptoSummary(hasTlsIdentity)) {
                        CryptoSummary.LanOnlyPlaintext -> Res.string.set_diag_crypto_value
                        CryptoSummary.LanPlaintextCrossTls -> Res.string.set_diag_crypto_value_lan_cross
                    },
                ),
            )
            // 证书到期读数。**只在真有 TLS 身份时画这一行** —— 明文实现 / 身份还没生成时
            // 显示「无」是对的，但画一行「到期：无」不如不画（那会让人以为证书没了）。
            state.network.selfCertNotAfterMillis?.let { notAfter ->
                InfoRow(
                    label = stringResource(Res.string.set_diag_cert_expiry),
                    value = formatDay(notAfter),
                )
                // 只有日期、没有后果，用户会以为到期那天要做什么；答案是什么都不用做。
                Text(
                    text = stringResource(Res.string.set_diag_cert_expiry_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = LocalAppSpacing.current.xs),
                )
            }
            if (statusBarInsetShown) {
                InfoRow(label = stringResource(Res.string.set_diag_inset), value = stringResource(Res.string.set_diag_inset_value))
            }

            // 原型 `.cardfoot{display:flex;justify-content:flex-end;gap:8px;padding:10px 14px}`
            // （`prototype/relay.html:128`）：一枚**内容宽**的按钮右对齐。
            // 原来写的是 `weight(1f)` 撑满整行 —— 原型里没有任何一颗按钮是这样。
            val runSelfCheck = onRunSelfCheck
            if (runSelfCheck != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // 原型这颗是 `.cardfoot` 里的标准档 `.btn`（不是 `.btn sm`）。
                    AppOutlinedButton(
                        // 原型这颗按钮的字面是「运行自检」（`prototype/relay.html:3962`），不是「重新自检」。
                        text = stringResource(Res.string.set_diag_selfcheck),
                        onClick = runSelfCheck,
                    )
                }
            } else {
                // 没有真实动作：不画按钮，只在原位说明这一屏的诚实状态。
                // 文案按「导出诊断包」接没接分两版 —— 引用一颗不存在的按钮也是说谎。
                Text(
                    text = stringResource(
                        if (onExportDiagnostics != null) {
                            Res.string.set_diag_selfcheck_unavailable
                        } else {
                            Res.string.set_diag_selfcheck_unavailable_no_export
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                )
            }

            // 诊断包导出：宿主给得出动作才画。成功要报**文件名与大小**（用户把它发出去之前
            // 得知道拿走的是哪一份），失败要报**为什么**（"没反应"是最难排查的失败态）。
            val exportPackage = onExportDiagnostics
            if (exportPackage != null) {
                val scope = rememberCoroutineScope()
                var exporting by remember { mutableStateOf(false) }
                var exportResult by remember { mutableStateOf<DiagnosticsExportResult?>(null) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppOutlinedButton(
                        text = stringResource(
                            if (exporting) Res.string.diag_export_running else Res.string.diag_export_package,
                        ),
                        // 打包期间置灰，避免点两下同时写同一个文件；这不是"永远灰着"，
                        // 协程一结束就恢复（`DeadButtonGuardTest` 的判据是宿主有没有真实动作）。
                        enabled = !exporting,
                        onClick = {
                            if (!exporting) {
                                exporting = true
                                scope.launch {
                                    exportResult = exportPackage()
                                    exporting = false
                                }
                            }
                        },
                    )
                }
                Text(
                    text = stringResource(Res.string.diag_export_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                )
                val result = exportResult
                if (result != null) {
                    val resultText = when (result) {
                        is DiagnosticsExportResult.Exported -> stringResource(
                            if (result.shared) {
                                Res.string.diag_export_done_shared
                            } else {
                                Res.string.diag_export_done_saved
                            },
                            result.fileName,
                            formatDiagnosticsByteCount(result.byteCount),
                        )

                        is DiagnosticsExportResult.Failed -> when (result.reason) {
                            DiagnosticsExportFailure.WriteFailed ->
                                stringResource(Res.string.diag_export_failed_write)

                            DiagnosticsExportFailure.Unexpected ->
                                stringResource(Res.string.diag_export_failed_unexpected)
                        }
                    }
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (result is DiagnosticsExportResult.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                    )
                }
            }
        }

        // 「输入法自检」是**独立一行**（与下面那条「适配对照」同一种 `.navrow`）：
        // 以前它嵌在「运行自检」按钮的分支里，而 onRunSelfCheck 全宿主为 null，
        // 于是这行入口在任何宿主上都出不来 —— 文档里写的那条路走不通。
        val openIme = onOpenImeSmoke
        if (openIme != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = RelayElevations.Card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsNavRow(
                    // 与桌面侧栏原「输入法自检」项同一枚 ic_eye —— 那一项已并入这条入口。
                    icon = Res.drawable.ic_eye,
                    title = stringResource(Res.string.set_diag_ime_smoke),
                    sub = null,
                    valText = null,
                    onClick = openIme,
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

/**
 * 诊断包大小的展示形态（`812 B` / `121.4 KiB` / `1.4 MiB`）。
 *
 * 不用 `String.format`（common 里没有），也不追求与文件管理器逐字节一致：这里只求
 * 用户扫一眼知道"导出的是不是空包、有没有异常地大"。**不要拿它做任何判断** ——
 * 需要精确值时读 [DiagnosticsExportResult.Exported.byteCount]。
 */
private fun formatDiagnosticsByteCount(bytes: Int): String {
    if (bytes < 1024) return bytes.toString() + " B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return oneDecimal(kib) + " KiB"
    return oneDecimal(kib / 1024.0) + " MiB"
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10.0).toInt()
    return (tenths / 10).toString() + "." + (tenths % 10).toString()
}

/**
 * 「传输加密」这一行当前该说哪一句话。
 *
 * 为什么要有这一档：本机**有 TLS 身份**（证书到期 / pin 存在）时，跨网那条路是
 * TLS + 双向 pin，局域网那条仍是明文 —— 旧代码恒定写「无 · 局域网内明文」，
 * 与同屏可见的「证书到期」直接矛盾，排障的人会据此得出「跨网在裸奔」的错误结论。
 *
 * 抽成纯函数是为了可单测：把入参翻过来，文案必须跟着翻。
 */
internal enum class CryptoSummary {
    /** 没有 TLS 身份：只有局域网明文一条路。 */
    LanOnlyPlaintext,

    /** 有 TLS 身份：局域网明文 + 跨网 TLS（双向 pin）。 */
    LanPlaintextCrossTls,
}

internal fun cryptoSummary(hasTlsIdentity: Boolean): CryptoSummary =
    if (hasTlsIdentity) CryptoSummary.LanPlaintextCrossTls else CryptoSummary.LanOnlyPlaintext

