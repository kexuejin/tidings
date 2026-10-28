// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.ScanRangeEntry
import com.kapp.tidings.domain.pairingQrPayloadFor
import com.kapp.tidings.core.protocol.CidrUtils
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.core.qr.QrEncoder
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.banner_scan_range_invalid
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.cidr_add_action
import com.kapp.tidings.generated.resources.cidr_delete
import com.kapp.tidings.generated.resources.cidr_empty_ranges
import com.kapp.tidings.generated.resources.cidr_input_hint
import com.kapp.tidings.generated.resources.cidr_input_label
import com.kapp.tidings.generated.resources.cidr_scan_last
import com.kapp.tidings.generated.resources.cidr_scan_never
import com.kapp.tidings.generated.resources.cidr_scan_now
import com.kapp.tidings.generated.resources.cidr_scan_progress
import com.kapp.tidings.generated.resources.cidr_scanning
import com.kapp.tidings.generated.resources.cidr_section_title
import com.kapp.tidings.generated.resources.cidr_source_peer
import com.kapp.tidings.generated.resources.cidr_source_self
import com.kapp.tidings.generated.resources.ic_copy
import com.kapp.tidings.generated.resources.ic_link
import com.kapp.tidings.generated.resources.nodes_my_qr_copy
import com.kapp.tidings.generated.resources.nodes_my_qr_desc
import com.kapp.tidings.generated.resources.nodes_port_bad
import com.kapp.tidings.generated.resources.nodes_my_qr_hint
import com.kapp.tidings.generated.resources.nodes_my_qr_title
import com.kapp.tidings.generated.resources.r2_set_conn_card_port
import com.kapp.tidings.generated.resources.set_conn_default_port
import com.kapp.tidings.generated.resources.set_conn_default_port_note
import com.kapp.tidings.generated.resources.set_conn_fill_default_port
import com.kapp.tidings.generated.resources.set_conn_qr_fallback
import com.kapp.tidings.generated.resources.set_conn_qr_no_address
import com.kapp.tidings.generated.resources.set_conn_qr_payload
import com.kapp.tidings.generated.resources.set_conn_qr_too_long
import com.kapp.tidings.generated.resources.set_conn_reconnect
import com.kapp.tidings.generated.resources.set_conn_reconnecting
import com.kapp.tidings.generated.resources.set_conn_save
import com.kapp.tidings.generated.resources.set_ports_saved
import com.kapp.tidings.generated.resources.set_conn_title
import com.kapp.tidings.generated.resources.set_keep_alive_card_title
import com.kapp.tidings.generated.resources.set_keep_alive_desc
import com.kapp.tidings.generated.resources.set_keep_alive_title
import com.kapp.tidings.generated.resources.set_conn_subtitle
import com.kapp.tidings.generated.resources.settings_copy_done
import com.kapp.tidings.ui.ContentWidths
import com.kapp.tidings.ui.SectionCard
import com.kapp.tidings.ui.centerCapped
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppIconButton
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.LargeTitleBar
import com.kapp.tidings.ui.components.QrCodeView
import com.kapp.tidings.ui.components.TitleBarDivider
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.LocalAppSpacing

/** 二级子页：连接设置。 */
@Composable
fun SettingsConnectionSubpage(
    state: RelayUiState,
    defaultPort: String?,
    onSavePorts: (String, String) -> Unit,
    onRestart: () -> Unit,
    /**
     * 网段探测的三个动作。**可空槽**（与诊断页 `onExportDiagnostics` / `onRunSelfCheck`
     * 同一条纪律）：宿主给得出真实动作就传，给不出就传 null —— 三者任一为 null 时
     * 整张 CIDR 卡**不画**。以前默认值是空 lambda，桌面上点「添加网段」输入框被清空、
     * 看似成功实际什么都没发生（一个按下去没反应的入口比没有更糟）。
     */
    onAddScanRange: ((String) -> Unit)? = null,
    onRemoveScanRange: ((String) -> Unit)? = null,
    onTriggerCidrScan: ((String?) -> Unit)? = null,
    /**
     * 「取消正在跑的网段探测」。可空槽：宿主给不出取消能力时（旧接线 / 预览），
     * 行内只把「探测」置灰，不画一颗按下去什么都不发生的「取消」。
     */
    onCancelCidrScan: (() -> Unit)? = null,
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
    // 上一次「保存」被本地规则拒了（端口不在 1..65535）。清空后由编辑动作复位。
    var portInvalid by remember(portSeed) { mutableStateOf(false) }
    // 「已保存」回执：这一页自己的动作，就地给结果 —— 手机子页的横幅宿主只画
    // `state.banner`（失败），保存回执走的是 `settingsNote`，在这一页上没人渲染。
    //
    // **不要按 portSeed 起 key**：保存成功后外部 settings.udpPort 会变成刚存的值，
    // 若 key 跟着变，这一行回执会在同一帧里被重置掉（闪一下就没），用户看不到。
    // 它只由"编辑输入/再次保存"驱动。
    var portsSaved by remember { mutableStateOf(false) }
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
                    // 与「上传头像」页的「换一张」同一档：小字也要够得着。
                    // 11sp 裸文本的命中区只有文字本身（约 9×11dp），触屏基本点不中，
                    // 读屏软件也不会把一行 Text 报成按钮 —— 而这是"把默认值填回去"
                    // 的唯一入口。minimumInteractiveComponentSize 给到 48dp，
                    // Role.Button 让读屏按按钮播报。
                    Text(
                        text = stringResource(Res.string.set_conn_fill_default_port),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(role = Role.Button) {
                                portText = Protocol.DEFAULT_UDP_PORT.toString()
                                portInvalid = false
                                portsSaved = false
                            },
                    )
                },
            ) {
                LabeledFieldWithKey(
                    label = stringResource(Res.string.set_conn_default_port),
                    fieldKey = KEY_DEFAULT_PORT,
                    value = portText,
                    onValueChange = {
                        portText = it
                        // 用户一改就清错：错误标记属于"上一次提交的值"，不跟着新输入走。
                        portInvalid = false
                        portsSaved = false
                    },
                    modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                    digitsOnly = true,
                    // 值不合法时这句换成规则本身（就地反馈，不等横幅）。
                    supporting = stringResource(
                        if (portInvalid) Res.string.nodes_port_bad else Res.string.set_conn_default_port_note,
                    ),
                    isError = portInvalid,
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
                    // 原型这两颗是 `.cardfoot` 里的标准档 `.btn`（不是 `.btn sm`），
                    // 所以走默认的 Medium 档。
                    AppOutlinedButton(
                        text = stringResource(
                            if (state.restarting) Res.string.set_conn_reconnecting
                            else Res.string.set_conn_reconnect,
                        ),
                        onClick = onRestart,
                        enabled = !state.restarting,
                    )
                    AppButton(
                        text = stringResource(Res.string.set_conn_save),
                        onClick = {
                            // 先在本地按同一条规则（1..65535，空 = 用当前值）判一次：
                            // 不合法就**就地**把字段标红，而不是把值递下去等控制器 warn ——
                            // 子页没有横幅宿主时那条 warn 谁也看不见（本轮 P1）。
                            val port = parsePortInput(portText, settings.udpPort)
                            if (port == null) {
                                portInvalid = true
                                portsSaved = false
                            } else {
                                portInvalid = false
                                portsSaved = true
                                onSavePorts(port.toString(), settings.tcpPort.toString())
                            }
                        },
                    )
                }
                // 保存回执：就地给（`settingsNote` 在子页上没人渲染）。
                if (portsSaved) {
                    Text(
                        text = stringResource(Res.string.set_ports_saved, parsePortInput(portText, settings.udpPort) ?: settings.udpPort, settings.tcpPort),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                    )
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
                    // 同其他出码点：用「宣告」那份，含隧道地址。
                    addresses = state.network.advertisedAddresses,
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
                        // 载荷行：那串字长得没法手抄（URL + pin + nodeId），不给复制的话
                        // 「相机坏了就抄这串字」就是一句空话。行内复制 + 短暂的「已复制」回执
                        // （与节点页弹层的同一套范式）。多行展示：单行省略会让中段 pin 根本读不到。
                        val clipboard = LocalClipboardManager.current
                        var payloadCopied by remember { mutableStateOf(false) }
                        // 行内反馈自清：2 秒足够看清「已复制」，又不至于一直挂着两份文案。
                        LaunchedEffect(payloadCopied) {
                            if (payloadCopied) {
                                delay(2_000)
                                payloadCopied = false
                            }
                        }
                        KvRow(
                            label = stringResource(Res.string.set_conn_qr_payload),
                            value = qrPayload,
                            mono = true,
                            valueMaxLines = Int.MAX_VALUE,
                        ) {
                            AppIconButton(
                                icon = Res.drawable.ic_copy,
                                contentDescription = stringResource(Res.string.nodes_my_qr_copy),
                                onClick = {
                                    clipboard.setText(AnnotatedString(qrPayload))
                                    payloadCopied = true
                                },
                            )
                        }
                        if (payloadCopied) {
                            Text(
                                text = stringResource(Res.string.settings_copy_done),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(Res.string.set_conn_qr_fallback),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                        )
                    }
                }
            }

            // 「网段探测与同步」卡是**能力门控**的：三个回调任一没接（null）就整张不画 ——
            // 否则「添加网段 / 立即探测 / 删除」就是一排按下去没反应的按钮（桌面设置窗曾如此）。
            val addRange = onAddScanRange
            val removeRange = onRemoveScanRange
            val triggerScan = onTriggerCidrScan
            if (addRange != null && removeRange != null && triggerScan != null) {
                SectionCard(title = stringResource(Res.string.cidr_section_title)) {
                    var newCidr by remember { mutableStateOf("") }
                    // 上一次「添加」被本地规则拒了（格式不对 / 主机数超上限）。
                    // 旧行为：不管对错都清空输入框 —— 格式错时输入丢失、又没有横幅，
                    // 看起来正是"点了没反应/加了没生效"。现在错了就**留着**并按规则报错。
                    var cidrInvalid by remember { mutableStateOf(false) }

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
                            onValueChange = {
                                newCidr = it
                                cidrInvalid = false
                            },
                            label = { Text(stringResource(Res.string.cidr_input_label)) },
                            placeholder = { Text(stringResource(Res.string.cidr_input_hint)) },
                            modifier = Modifier.weight(1f),
                            singleLine = false,
                            maxLines = 4,
                            isError = cidrInvalid,
                        )
                        // 原型这颗是 `.btn sm pri`（`prototype/relay.html:5097`）：输入框右侧的密排位置。
                        AppButton(
                            text = stringResource(Res.string.cidr_add_action),
                            onClick = {
                                val batch = parseBatchScanRanges(newCidr)
                                val target = normalizeScanRangeOrNull(newCidr) ?: batch.valid.firstOrNull()
                                if (target == null) {
                                    cidrInvalid = true
                                } else {
                                    cidrInvalid = batch.invalid.isNotEmpty()
                                    addRange(target)
                                    if (batch.invalid.isEmpty()) newCidr = "" else newCidr = batch.invalid.joinToString("\n")
                                    for (item in batch.valid) if (item != target) addRange(item)
                                }
                            },
                            size = AppButtonSize.Small,
                        )
                    }
                    if (cidrInvalid) {
                        Text(
                            text = stringResource(Res.string.banner_scan_range_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                        )
                    }

                    if (settings.scanRanges.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.cidr_empty_ranges),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = LocalAppSpacing.current.md),
                        )
                    } else {
                        // 行型统一走 `SettingRow`（标题 + 副标题 + 行尾动作）+ 发丝线：
                        // 原型这一屏的扫描网段是平铺 `.row`（`prototype/relay.html:5088-5094`），
                        // 不是"卡片里的卡片"。此前每行包了一层 8dp 圆角 + surfaceVariant 的
                        // Surface，同一个列表在本页与其它设置页长两个样（差集里
                        // `m-set-conn radius 只代码 8` 就是它）。
                        Column(modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm)) {
                            settings.scanRanges.forEachIndexed { index, entry ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                val sourceDesc = if (entry.source == "self") {
                                    stringResource(Res.string.cidr_source_self)
                                } else {
                                    stringResource(Res.string.cidr_source_peer, entry.sourceNick ?: (entry.sourceNodeId?.take(8) ?: ""))
                                }
                                // 副标题 = 来源 + 这一次真的探过的读数（原型 `.t2`：
                                // 「手动添加 · 上次探测 3 分钟前，扫到 2 个」）。
                                // 没探过就说「还没探测过」——不要拿 0 冒充"探过了、一个没有"。
                                val history = when (val h = scanRangeHistoryOf(entry)) {
                                    ScanRangeHistoryUi.Never -> stringResource(Res.string.cidr_scan_never)
                                    is ScanRangeHistoryUi.Scanned -> stringResource(
                                        Res.string.cidr_scan_last,
                                        relativeTime(h.atMillis, nowMillis()),
                                        h.foundCount,
                                    )
                                }
                                val subtitle = "$sourceDesc · $history"
                                SettingRow(title = entry.cidr, subtitle = subtitle) {
                                    // 原型这条行尾是两颗 `.btn sm`（`prototype/relay.html:5091-5092`）：
                                    // 列表行内的动作，走小档。
                                    //
                                    // 扫描进行中：**正在扫的那一段**把「探测」换成「取消」，
                                    // 其余段保持灰着的「探测」。取消放在行内而不是只在页脚，
                                    // 是因为用户的注意力在那一行（进度条也写着这一段）。
                                    val progress = state.scanProgress
                                    val scanningThisRow = progress != null && progress.currentCidr == entry.cidr
                                    val cancel = onCancelCidrScan
                                    if (scanningThisRow && cancel != null) {
                                        AppTextButton(
                                            text = stringResource(Res.string.common_cancel),
                                            onClick = cancel,
                                            size = AppButtonSize.Small,
                                        )
                                    } else {
                                        AppTextButton(
                                            text = stringResource(Res.string.cidr_scan_now),
                                            onClick = { triggerScan(entry.cidr) },
                                            // 扫描中别的段不能点：点了会 cancel 当前扫描重来
                                            // （进度条归零），看起来像"卡住又重启"。
                                            enabled = progress == null,
                                            size = AppButtonSize.Small,
                                        )
                                    }
                                    AppTextButton(
                                        text = stringResource(Res.string.cidr_delete),
                                        onClick = { removeRange(entry.cidr) },
                                        size = AppButtonSize.Small,
                                    )
                                }
                            }
                        }

                        // 原型「全部立即探测」是 `.btn sm`（`prototype/relay.html:5098`），
                        // 与上一颗同一张 `.cardfoot`；本仓把它画成整宽（宽度不改档，字号仍按原型走小档）。
                        AppButton(
                            text = stringResource(if (state.scanProgress != null) Res.string.cidr_scanning else Res.string.cidr_scan_now),
                            onClick = { triggerScan(null) },
                            modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                            enabled = state.scanProgress == null,
                            size = AppButtonSize.Small,
                        )
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

/**
 * 「默认端口」输入框的解析与校验（纯函数，可单测）。
 *
 * 判据与控制器 [RelayControllerSettingsComponent.handleSavePorts] 的 `1..65535` 完全一致 ——
 * 这里不是另立一套规则，而是把同一条规则**提前到字段上**：不合法时当场标红，
 * 而不是把值递下去、等一条用户很可能看不见的横幅。
 *
 * 空输入 = 用 [fallback]（当前端口），与保存按钮原来的行为一致；仍不合法则返回 null。
 */
internal fun parsePortInput(raw: String, fallback: Int): Int? {
    val text = raw.trim()
    val value = if (text.isEmpty()) fallback else (text.toIntOrNull() ?: return null)
    return if (value in 1..65535) value else null
}

/**
 * 「添加网段」输入框的解析与校验（纯函数，可单测）。
 *
 * 与控制器同一条链路：`CidrUtils.normalizeCidr` 规范化 + `CidrUtils.parseCidrHosts`
 * 判主机数上限（/22 及更小 ⇒ 超过 1024 台会被拒）。返回**规范化后**的网段，
 * 非法返回 null —— 调用点据此"留着输入 + 就地报错"，而不是清空输入框。
 */
internal fun normalizeScanRangeOrNull(raw: String): String? {
    val normalized = CidrUtils.normalizeCidr(raw.trim()) ?: return null
    return if (CidrUtils.parseCidrHosts(normalized) != null) normalized else null
}

data class CidrBatchParseResult(
    val valid: List<String>,
    val invalid: List<String>,
)

/**
 * 批量解析与规范化 CIDR 列表（纯函数，可单测）。
 *
 * 支持以换行、逗号、分号分隔的多行粘贴输入。
 * 规范化主机位、去重、检查主机数上限（≤ 1024），保留非法条目以便反馈。
 */
internal fun parseBatchScanRanges(raw: String, maxItems: Int = 50): CidrBatchParseResult {
    val tokens = raw.split(Regex("[\\r\\n,;]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val valid = mutableListOf<String>()
    val invalid = mutableListOf<String>()
    for (token in tokens) {
        val normalized = normalizeScanRangeOrNull(token)
        if (normalized != null) {
            if (!valid.contains(normalized)) {
                if (valid.size < maxItems) {
                    valid.add(normalized)
                }
            }
        } else {
            invalid.add(token)
        }
    }
    return CidrBatchParseResult(valid = valid, invalid = invalid)
}

/**
 * 网段行副标题里的「历史读数」形状（纯函数，可单测）。
 *
 * **null 与 0 不能合并**：`lastScanAt == null` 是"从没探过"，显示「还没探测过」；
 * 探过但一个都没扫到是「扫到 0 个」—— 后者是真读数，拿"没探过"去说它是撒谎。
 */
internal sealed interface ScanRangeHistoryUi {
    data object Never : ScanRangeHistoryUi

    data class Scanned(val atMillis: Long, val foundCount: Int) : ScanRangeHistoryUi
}

internal fun scanRangeHistoryOf(entry: ScanRangeEntry): ScanRangeHistoryUi {
    // 先取本地 val：lastScanAt 是另一个模块的 public 属性，K2 不允许跨模块 smart cast。
    val at = entry.lastScanAt ?: return ScanRangeHistoryUi.Never
    // lastFoundCount 缺失（老数据 / 半写）时按 0 显示：时间已经证明「探过」，
    // 计数缺了就是 0 个，不能说回「没探过」。
    return ScanRangeHistoryUi.Scanned(at, entry.lastFoundCount ?: 0)
}


