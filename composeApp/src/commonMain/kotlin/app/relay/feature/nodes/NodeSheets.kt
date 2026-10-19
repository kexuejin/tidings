// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.relay.ui.components.QrCodeView
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.RelayElevations
import app.relay.ui.theme.accents
import app.relay.app.ManualPeerEntry
import app.relay.app.RelayController
import app.relay.app.RelayUiState
import app.relay.app.pairingQrPayloadFor
import app.relay.core.protocol.Protocol
import app.relay.core.qr.QrEncoder
import app.relay.generated.resources.Res
import app.relay.generated.resources.nodes_my_qr_desc
import app.relay.generated.resources.nodes_my_qr_hint
import app.relay.generated.resources.nodes_my_qr_no_address
import app.relay.generated.resources.nodes_my_qr_title
import app.relay.generated.resources.nodes_my_qr_too_long
import app.relay.generated.resources.ic_qr_code_scanner
import app.relay.generated.resources.nodes_error_host
import app.relay.generated.resources.nodes_error_two_ports
import app.relay.generated.resources.nodes_group_add
import app.relay.generated.resources.nodes_port_bad
import app.relay.generated.resources.nodes_port_label
import app.relay.generated.resources.nodes_port_ok
import app.relay.generated.resources.nodes_recognized
import app.relay.generated.resources.nodes_scan_action
import app.relay.generated.resources.nodes_scan_connect
import app.relay.generated.resources.nodes_scan_field_host
import app.relay.generated.resources.nodes_scan_field_host_hint
import app.relay.generated.resources.nodes_manual_name
import app.relay.generated.resources.nodes_manual_name_hint
import app.relay.generated.resources.nodes_scan_frame_hint
import app.relay.generated.resources.nodes_scan_unavailable
import app.relay.generated.resources.nodes_seg_manual
import app.relay.generated.resources.nodes_seg_scan
import app.relay.generated.resources.nodes_timeout
import app.relay.generated.resources.scan_import_image
import app.relay.generated.resources.scan_manual_hint
import app.relay.ui.components.AppBanner
import app.relay.ui.components.LargeTitleBar
import app.relay.ui.components.PeerAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextOverflow
import app.relay.generated.resources.common_close
import app.relay.generated.resources.ic_chev
import app.relay.generated.resources.ic_close
import app.relay.generated.resources.ic_info
import app.relay.generated.resources.nodes_my_qr_payload
import app.relay.generated.resources.nodes_my_qr_payload_hint

/**
 * 「添加节点」区里的入口行，逐档对齐原型的 `.krow`（`prototype/relay.html`）：
 *
 * | 原型 | 值 | 代码 |
 * |---|---|---|
 * | `.krow{gap:12px}` | 12px | `sp.md` |
 * | `.krow{padding:11px 14px}` | 11/14px | `sp.md10` / `sp.lg14`（11→10 是 1dp 就近 snap） |
 * | `.krow .av` | 40px 圆、`surface-2` 底 | `Modifier.size(sp.xxl + sp.sm) + CircleShape + surfaceVariant` |
 * | `.krow .av` 里的 `ic(...)` | `color:var(--primary)`、18px 字形 | `tint = primary`、`IconSizes.Body` |
 * | `.krow .t1{font-weight:600}` | 600 | `bodyMedium + FontWeight.SemiBold` |
 * | `.krow .t2{font-size:12px;margin-top:4px}` | 12px / 4px | `bodySmall` / `padding(top = sp.xs)` |
 * | `.krow .rt` = `ic('chev')` | 行尾雪佛龙 | `IconSizes.Inline`（14dp） |
 * | `border-bottom:1px solid var(--hair)` | 行间 hairline | `HorizontalDivider`（[showDivider]） |
 * | `.krow .t1` 里内联的 `.tag.muted` | 计数胶囊 | `titleTrailing` 槽位（`CircleShape` + `surfaceVariant`） |
 * | `.krow{background:var(--surface)}` | 白底列表行 | `.background(surface)`（挂页面色时才是可见的一笔） |
 */
/**
 * 底部弹层的标题行，逐档对齐原型 `.sheet .sh-head`（`prototype/relay.html:1351-1352`）：
 * `padding:6px 16px 10px`、`gap:10px`、`h2{font-size:16px;font-weight:700;flex:1}`，
 * 右端一颗关闭按钮（`.ibtn.plain`，`aria-label="关闭"`）。
 *
 * 横向那 16px 由调用点提供（两处弹层的 Column 都已有 `padding(horizontal = sp.lg)`），
 * 所以这里只补纵向。
 *
 * 为什么不用共享的 `LargeTitleBar`：那一支是**页面顶栏**的档
 * （`headlineSmall` = 24sp/SemiBold，见 `AppTheme.kt:53-58`），而原型弹层标题是
 * 16px/700（`.sheet .sh-head h2`）—— 同一支用在两处会差两个档位。
 * `AppTheme` 的 `titleMedium` 正好是 16sp，但字重是 Medium(500)，故覆写为 Bold(700)。
 */
@Composable
private fun SheetHead(title: String, onClose: () -> Unit) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = sp.sm6, bottom = sp.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(
                painter = painterResource(Res.drawable.ic_close),
                contentDescription = stringResource(Res.string.common_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AddNodeEntryRow(
    title: String,
    subtitle: String,
    icon: DrawableResource,
    // 可空：没有去向的行（例如工作区看板的「未归属会话」在没有活跃会话时）
    // 原型里也没有 chev —— 画一颗点不动的箭头比不画更糟。
    onClick: (() -> Unit)?,
    showDivider: Boolean,
    // 标题行右端的可选槽位（原型 `.krow .t1` 里内联的那颗 `.tag.muted` 计数胶囊）。
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 原型 `.krow{background:var(--surface)}`（`prototype/relay.html:199`）。
                // 弹层里这一层与容器同色（像素不变）；工作区看板的未归属行挂在页面
                // 色（#FAFAF8）上，正是这一笔把它画成原型那样的白底列表行。
                .background(MaterialTheme.colorScheme.surface)
                .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
                .padding(horizontal = sp.lg14, vertical = sp.md10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            Box(
                modifier = Modifier
                    // 原型 `.krow .av{width:40px;height:40px}`（`prototype/relay.html:202`）。
                    // 40 在 `AppSpacing` 里没有档位，用两档相加而不是新增裸字面量 ——
                    // `:designTokenUsageGate` 的 dimension 棘轮正在红。
                    .size(sp.xxl + sp.sm)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(IconSizes.Body),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // fill = false：标题只占自己需要的宽度，计数胶囊紧跟在它后面；
                        // 标题过长时（长节点名）才被压缩，胶囊与 chev 都不会被挤出卡外。
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    titleTrailing?.invoke()
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = sp.xs),
                )
            }
            if (onClick != null) {
                Icon(
                    painter = painterResource(Res.drawable.ic_chev),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizes.Inline),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
/**
 * 添加节点底部弹层 —— 原型 `screenAddNode()`。
 *
 * 为什么是弹层而不是新页面：加一个节点是**从任意一页都能做的动作**，做成页面就意味着
 * 用户必须先记住"去节点页 → 点 +"。做成弹层之后，节点页顶栏的加号和页面里那两行入口
 * 都能直接唤起它，省掉整页的导航往返。
 *
 * 两种方式放在一个分段控件后面，因为它们是**同一个结果的两条路径**：扫到码和手打地址
 * 最终都要落到 `(host, port)` 这一对值上，后面共用同一套校验和同一颗「连接」按钮 ——
 * 分成两个页面就会出现两套校验，两套校验就会有两套不一致的报错文案。
 *
 * 握手判定沿用旧加入页的做法：发出 `entry` 之后观察节点表，3 秒内出现同 IP 的节点
 * 即成功，否则给"连不上"的出路，而不是转圈到天荒地老。
 *
 * 公开可见是有原因的：`androidApp` 的截图门禁在另一个 module 里，没法访问
 * `commonMain` 的 `internal`。它需要能单独渲染这一层弹层，否则弹层的外观
 * 就没有任何自动校验。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNodeSheet(
    state: RelayUiState,
    onConnect: (String, String) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState? = null,
    /**
     * 真的去调相机扫一次码。
     *
     * null = 这个壳没有扫码能力（相机栈在 platform 层，桌面端明确不接），
     * 此时扫码页不再画一个"按了没反应"的空取景框，而是把原因写出来并把人导向
     * 「填地址」那条一定能走通的路。文案在 values 目录下的 strings.xml。
     */
    onScanQr: (() -> Unit)? = null,
    /**
     * 从相册挑一张二维码图片来解（原型扫码段那颗「从图片导入二维码」）。
     *
     * 与 [onScanQr] 是**同一道闸**：生产侧两者由 `App.kt` 里同一个表达式
     * （`controller.qrScannerPreview != null`）算出，没有相机栈的平台两个都是 null，
     * 两颗按钮一起消失。渲染时各自只看自己那个回调在不在 —— 它们是两个独立入口，
     * 谁也不该替谁决定画不画。
     */
    onImportQrFromImage: (suspend () -> Boolean)? = null,
    /**
     * 弹层打开时停在「扫码」段还是「填地址」段。
     *
     * 默认停在填地址：那是**一定能走通**的那条路（没有相机、没给权限的设备也得能加节点）。
     * 节点页那行「扫码配对」会把它置 true —— 用户点的就是扫码，再让他自己点一次分段控件
     * 是白白多一步。截图门禁也靠它录"扫码段"那一帧（分段是弹层内部状态，外部点不进去）。
     */
    initialScanMode: Boolean = false,
    /**
     * 「填地址」段两个输入框的**初始值**，只给截图门禁喂"已经填好"的那一档用。
     *
     * 原型有两张填地址的图：`m09b-addnode-fill`（框里已经写着 `192.168.1.31` / `17878`、
     * 「连接」是绿实心）与空框那一档。生产调用一律不传 = 空框，行为与加这两个形参之前完全一致。
     * 没有它们，门禁只能录空框，那一档"填好之后长什么样"（尤其「连接」从灰变绿）
     * 就永远没有帧盯着 —— 与 `initialScanMode`、`initialMenuKey` 同一类参数。
     */
    initialHost: String = "",
    initialPort: String = "",
    /**
     * 带名字的连接（可选）—— 传了才画「名字（可选）」那个框。
     *
     * 手机壳（`App.kt`）传的是 `controller::addNamedManualPeer`；不传的壳（截图门禁的
     * 既有帧、以及任何没有"本机显示名"这条路的壳）**一个像素都不变** —— 一个填了也没人
     * 接的框比没有框更糟。名字只存本机、不进 wire，理由见 `ManualPeerEntry.name` 的 KDoc。
     */
    onConnectNamed: ((String, String, String) -> Unit)? = null,
    /** 「名字（可选）」框的初始值，只给截图门禁喂"已经填好"的那一档用。 */
    initialName: String = "",
) {
    // 传进来的优先：截图门禁要的是一个**已经展开**的静态帧，而
    // rememberModalBottomSheetState 交出的永远是 Hidden，得靠动画走到位 ——
    // 截图抓的是一帧，动画还没跑完就是一张纯底色的空图（这个坑已经踩过一次）。
    val ownSheetState = rememberModalBottomSheetState()
    val sheet = sheetState ?: ownSheetState
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var name by remember { mutableStateOf(initialName) }
    var scanMode by remember { mutableStateOf(initialScanMode) }
    var attempted by remember { mutableStateOf("") }
    // 「从图片导入二维码」要等用户选图 + 解码才回来，是一段挂起操作，所以需要自己的
    // 作用域；`importing` 只用来在导入期间禁用那颗按钮自己（防连点开出两个选图器），
    // 与 `ScanScreen` 里那颗同名按钮的做法一致。
    val importScope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }

    val hostError = stringResource(Res.string.nodes_error_host)
    val portError = stringResource(Res.string.nodes_port_bad)
    val timeoutError = stringResource(Res.string.nodes_timeout)
    // 地址里带了端口、端口框里又填了一个：这是歧义，不是"地址不对"。
    // 说清楚该留空哪一个，用户才知道下一步做什么。
    val bothPortsError = stringResource(Res.string.nodes_error_two_ports)

    // 握手结果靠观察节点表：发出后 3 秒内出现同 IP 的节点就算连上，否则把"为什么没连上"
    // 留在原地（弹层不自己关：用户多半想改个地址重试）。`timedOut` 是**真的等过**之后
    // 才置位 —— 上一版把红字挂在 `attempted` 上，一点「连接」就立刻报"连接超时"，
    // 而那时连 3 秒都还没过去。
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(attempted, state.peers) {
        timedOut = false
        if (attempted.isEmpty()) return@LaunchedEffect
        if (state.peers.any { it.ip == attempted || it.host == attempted }) {
            onDismiss()
        } else {
            delay(3_200)
            timedOut = true
        }
    }

    // spec 的 sheet 档是 3.dp（RelayElevations.Sheet）。ModalBottomSheet 不接受阴影参数，
    // 它自己的 surface 就是 M3 Level2 = 3.dp，与 spec 恰好一致，所以这里不再多传一个
    // 主题里查不到出处的数字；真要改档，改的是 ModalBottomSheet 的默认档位。
    // 底色必须显式给：M3 的默认 `containerColor` 是 `surfaceContainerLow`（本仓浅色 = 页面底
    // #FAFAF8），而原型 `.sheet{background:var(--surface)}`（prototype/relay.html:1402）是纯白。
    // 海拔染色被拆掉之后（见 ui/theme/Color.kt 的 surfaceTint 说明），不给这一句弹层就会
    // 跟页面同一个底色。
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalAppSpacing.current.lg),
        ) {
            // 弹层标题按原型 `.sh-head` 那一档（16px/700），不是页面顶栏那一档。
            // 原型这里**没有副标题**（`prototype/relay.html:3362` 只有 `<h2>添加节点</h2>` + 关闭钮），
            // 上一版挂的 `nodes_add_sheet_sub` 是代码自造的，已删。
            SheetHead(
                title = stringResource(Res.string.nodes_group_add),
                onClose = onDismiss,
            )

            // 分段控件：扫码 / 填地址。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = LocalAppSpacing.current.sm)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                SegTab(
                    text = stringResource(Res.string.nodes_seg_scan),
                    selected = scanMode,
                    modifier = Modifier.weight(1f),
                    onClick = { scanMode = true },
                )
                SegTab(
                    text = stringResource(Res.string.nodes_seg_manual),
                    selected = !scanMode,
                    modifier = Modifier.weight(1f),
                    onClick = { scanMode = false },
                )
            }

            if (scanMode) {
                // 这一页能不能用，全看 onScanQr 在不在：在（手机端），就给一个真的能
                // 打开取景框的按钮；不在（桌面 / 平板，没有相机栈），按钮不画，
                // 由下面那条 `.banner.info` 把"为什么扫不了、该走哪条路"写清楚。
                //
                // 取景框里那句**永远是** `nodes_scan_frame_hint`（原型 `.scanhint` 就是
                // "把对方的节点码放进框里"，不随平台变）。上一版在 onScanQr == null 时
                // 把 `nodes_scan_unavailable` 顶到框里，等于同一句话在两个位置之间搬家，
                // 而原型里它俩是**同时存在**的两处（`prototype/relay.html:3364-3370`）。
                ScanFrame(
                    modifier = Modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.md),
                    hint = stringResource(Res.string.nodes_scan_frame_hint),
                )
                if (onScanQr != null) {
                    Button(
                        onClick = { onScanQr() },
                        modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.md10),
                    ) {
                        Text(stringResource(Res.string.nodes_scan_action))
                    }
                }
                // 第二条路：不从相机读，从一张已有的图里读。原型把这三样排成
                // 「绿实心打开相机扫码」→「通栏描边从图片导入二维码」→ `.banner.info`
                // （`prototype/relay.html:3371-3375`），所以这颗是 `OutlinedButton` 且
                // 夹在中间，不是随便挂一个次级入口。
                //
                // 闸门判据只这一条：`onImportQrFromImage` 在不在。生产侧它和 `onScanQr`
                // 由**同一个表达式**算出（`App.kt`：`controller.qrScannerPreview != null`），
                // 所以"有相机、没相册"这种组合在真实设备上不存在；这里不额外叠一道
                // `onScanQr != null` —— 这两颗本来就是两个独立入口，各自按自己的
                // 回调在不在决定画不画（与上面那颗 `Button` 同一个写法）。
                if (onImportQrFromImage != null) {
                    OutlinedButton(
                        onClick = {
                            importScope.launch {
                                importing = true
                                // 成功时控制器自己把节点加进表里、弹层由外层关闭；
                                // 取消 / 选图失败 / 图里没码都会在控制器里发一条横幅，
                                // 这里不需要再画一次错误。
                                onImportQrFromImage()
                                importing = false
                            }
                        },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.md10),
                    ) {
                        Text(stringResource(Res.string.scan_import_image))
                    }
                }
                // `.banner.info`：原型扫码段里紧跟在按钮下面那条提示条
                // （`--surface-2` 底 + `--on-2` 字 + 16px 信息图标 + 圆角 `--r-ctl`）。
                // 文案与 `m-nodes-empty` 的 VPN 条不同：那条是 `.banner.warn`（橙黄），
                // 这条是 `.banner.info`（灰），所以**不能**复用 `AppBanner` 的 Warn 档。
                ScanInfoBanner(text = stringResource(Res.string.nodes_scan_unavailable))
                // 扫码段的「出路」：原型 `.cardfoot` 两颗小按钮的第一颗
                // （`prototype/relay.html:3381`：`<button class="btn sm">扫不到？回节点页填地址</button>`）。
                // **第二颗「连接」不补**（曾加过一颗，撤回）：扫码段里 `host` 是空的，
                // 按本文件里 `host.isBlank()` 那条判据会常亮「地址看起来不对」；而且扫码段根本
                // 不存在「已识别但还没连」的中间态 —— `RelayControllerCidrComponent.scanQrCode()`
                // → `handleQrCode()` 在 `ParsedQr.LanPeer` 时直接 `addManualPeer(...)`，
                // 扫到就连上了。原型那张「已识别 老周 · 周 / 已 ACK · tw1」的 `.card` 是
                // **写死的演示数据**。画一颗永远灰的「连接」就是制造假按钮。
                //
                // 复用现成 key `scan_manual_hint`（`composeResources/values/strings.xml:220`，
                // en `:231`），不新增 key；另一处消费点 `feature/scan/ScanScreen.kt`
                // 是失败态专用，同一句话在两个位置都成立。
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.scan_manual_hint))
                }
                // 这里**不要**再摆 `nodes_scan_field_host_hint`：那句话是给「填地址」
                // 那一段讲端口默认值的（"不填端口时用 17878"），放在扫码段里既答非所问，
                // 又让人以为扫码还得先填个地址。取景框下面那句 `nodes_scan_frame_hint`
                // 已经把该做的事说完了。真机上就是这么露馅的。
            } else {
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        attempted = ""
                    },
                    label = { Text(stringResource(Res.string.nodes_scan_field_host)) },
                    supportingText = {
                        Text(
                            stringResource(
                                Res.string.nodes_scan_field_host_hint,
                                Protocol.DEFAULT_UDP_PORT,
                            ),
                        )
                    },
                    isError = host.isNotEmpty() && !host.trim().any { it.isLetterOrDigit() },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.md),
                )
                // 留空表示"用默认端口"，所以空值不算错 —— 原型的端口提示就是这么说的。
                val portUsable = port.trim().isEmpty() ||
                    (port.trim().toIntOrNull()?.let { it in 1..65535 } == true)
                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it.filter(Char::isDigit).take(5)
                        attempted = ""
                    },
                    label = { Text(stringResource(Res.string.nodes_port_label)) },
                    supportingText = {
                        Text(
                            when {
                                !portUsable -> stringResource(Res.string.nodes_port_bad)
                                else -> stringResource(
                                    Res.string.nodes_port_ok,
                                    Protocol.DEFAULT_UDP_PORT,
                                )
                            },
                        )
                    },
                    isError = !portUsable,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.md),
                )
                // 「名字（可选）」：只在壳真的能收下它时才画（见 `onConnectNamed` 的 KDoc）。
                // 它排在第**三**个框，前面两个是地址与端口 —— 顺序与"先有地址、再给个便于
                // 辨认的名字"一致，也不打乱原型 `m09b-addnode-fill` 里那两个框的相对次序。
                if (onConnectNamed != null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.nodes_manual_name)) },
                        supportingText = { Text(stringResource(Res.string.nodes_manual_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.md),
                    )
                }
            }

            // 已识别卡：把"我认出来的是什么"摆在明面上，否则用户分不清
            // 真扫到了码还是自己随便打了一串字符。
            //
            // **只在扫码段画**：原型 `screenAddNode(1)` 的这张卡长在扫码段里，
            // 填地址段（`screenAddNode(2)`）没有它 —— 而两个段共用同一个 `host` 状态，
            // 上一版按 `host.isNotEmpty()` 画，于是填地址时框里一有值这张卡就冒出来，
            // 在原型里是**多出来的元素**（`m09b-addnode-fill` 并排图可见）。
            // 上面那句理由本身也只对扫码段成立，所以按段收起是同时忠于原型与这段注释。
            //
            // 这里显示的是**解析之后**的 `host:port`，不是输入框里的原串：提示承诺
            // `ip:port` 可用（见 `RelayController.splitHostPort`），拆不开时
            // `resolved` 是 null，卡片就把原串照实显示、同时下面的红字说明为什么不行。
            //
            // 这里**不显示能力标签**：上一版对任何非空输入都挂一个写死的 `tw1` ——
            // 那个节点还没被联系过，我们不知道它声明了什么。能力只能来自它自己的 `alive`。
            val trimmedHost = host.trim()
            val trimmedPort = port.trim()
            val resolved = RelayController.splitHostPort(trimmedHost, trimmedPort)
            if (trimmedHost.isNotEmpty() && scanMode) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.md),
                ) {
                    Row(modifier = Modifier.padding(LocalAppSpacing.current.md), verticalAlignment = Alignment.CenterVertically) {
                        PeerAvatar(name = trimmedHost, isAgent = false, size = 32.dp)
                        Column(Modifier.weight(1f).padding(start = LocalAppSpacing.current.sm)) {
                            Text(
                                text = stringResource(Res.string.nodes_recognized),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = resolved
                                    ?.let { (h, p) ->
                                        "$h:${p.ifEmpty { Protocol.DEFAULT_UDP_PORT.toString() }}"
                                    }
                                    ?: trimmedHost,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            val hostOk = resolved != null && trimmedHost.isNotEmpty() &&
                trimmedHost.any { it.isLetterOrDigit() }
            // 留空表示"用默认端口"，所以空值不是错误 —— 原型的提示也是这么说的。
            val portOk = trimmedPort.isEmpty() ||
                (trimmedPort.toIntOrNull()?.let { it in 1..65535 } == true)

            // 只有**真的等过**那 3 秒才报超时（见上面那个 LaunchedEffect）：
            // 上一版把这句红字挂在 `attempted` 上，点一下「连接」就立刻说"连接超时"。
            if (timedOut && state.peers.none { it.ip == attempted || it.host == attempted }) {
                Text(
                    text = timeoutError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                )
            }

            // 「连接」这颗按钮只属于「填地址」那一段：扫码段里它既没有输入框可提交，
            // 又会因为地址是空的而**常亮一句"地址看起来不对"** —— 用户明明在扫码，
            // 却被红字说地址错了。所以整段（按钮 + 两条校验说明）只在手动段画。
            if (!scanMode) {
                Button(
                    onClick = {
                        // 记的是**解析后**的主机名：超时判据要拿它去比对节点表里的 ip/host，
                        // 原串 `1.2.3.4:17899` 永远比不中。
                        attempted = resolved?.first ?: trimmedHost
                        // 端口原样传下去（空串 = 用默认 17878），不再用**本机**端口兜底：
                        // 本机改过 UDP 端口后，那样会连到 `host:<本机端口>`。
                        // 名字同理：只在壳收得下它时走带名字那条（空串 = 不起名）。
                        val trimmedName = name.trim()
                        if (onConnectNamed != null) {
                            onConnectNamed(trimmedHost, trimmedPort, trimmedName)
                        } else {
                            onConnect(trimmedHost, trimmedPort)
                        }
                    },
                    enabled = hostOk && portOk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LocalAppSpacing.current.lg, bottom = LocalAppSpacing.current.xl),
                ) {
                    Text(stringResource(Res.string.nodes_scan_connect))
                }

                // 校验失败时把原因说清楚，而不是只让按钮变灰：
                // 灰按钮在移动端是最典型的"无出路"状态。
                //
                // 但**空输入框不算"失败"**：那只是还没开始填。上一版这里只判 `!hostOk`，
                // 于是一进「填地址」就先红字"地址看起来不对。"——真机上就是这个观感。空字段该给的是输入框自己那句 supportingText，
                // 不是一句指责。
                if (!portOk) {
                    Text(
                        text = portError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (trimmedHost.isNotEmpty() && !hostOk) {
                    // 两处都写了端口是**歧义**，不是"地址不对"：说清楚该留空哪一个。
                    val portInHost = trimmedHost.count { it == ':' } == 1 &&
                        trimmedHost.substringAfter(':').toIntOrNull()?.let { it in 1..65535 } == true
                    Text(
                        text = if (portInHost && trimmedPort.isNotEmpty()) bothPortsError else hostError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
/** 分段控件里的一格。 */
@Composable
private fun SegTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        )
    }
}

/**
 * 扫码取景框。
 *
 * 画的是占位框而不是真相机预览：相机权限与预览挂在 platform 层，
 * 而这里要先把信息层次摆对（框 + 一句提示），预览接上后框的位置不用改。
 *
 * [hint] 由调用点给：能扫码时是"把码放进框里"，不能扫码时是"为什么不能扫"。
 * 这个框**自己不点** —— 空框做成可点的形状只会让人反复点一个没有反馈的地方，
 * 真正的入口是它下面那颗按钮。
 */
@Composable
private fun ScanFrame(
    modifier: Modifier = Modifier,
    hint: String,
) {
    // 原型 `.scanwrap` 是**正方形**（`aspect-ratio:1/1`、`max-width:200px`、`2px dashed`
    // 描边、`--surface-2` 底、圆角 16），框里摆一块 132×132 的棋盘 `.qr`，
    // 右下角叠一颗 30×30 的 `.lamp` 圆钮；说明那句 `.scanhint` 在**框外下方**，
    // 不是框里的文字（`prototype/relay.html:790-800`）。
    //
    // 上一版是 `height(180.dp)` + 1dp 实线 + 把说明塞在框正中：三点都不对 ——
    // 框不是方的、描边不是虚线、说明本该在框下面。并排图里最扎眼的就是这一处。
    val sp = LocalAppSpacing.current
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .widthIn(max = 200.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .drawBehind {
                    val radius = 16.dp.toPx()
                    drawRoundRect(color = surfaceVariant, cornerRadius = CornerRadius(radius, radius))
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                            ),
                        ),
                    )
                },
        ) {
            // `.qr`：`repeating-conic-gradient(var(--on) 0 25%, transparent 0 50%) 0 0/20px 20px`
            // —— 20px 一格的棋盘，整体 88% 不透明。这里按格子逐块画，等价于那条渐变。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(132.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .drawBehind {
                        val cell = 20.dp.toPx()
                        val ink = onSurface.copy(alpha = 0.88f)
                        var row = 0
                        var y = 0f
                        while (y < size.height) {
                            var col = 0
                            var x = 0f
                            while (x < size.width) {
                                if ((row + col) % 2 == 0) {
                                    drawRect(color = ink, topLeft = Offset(x, y), size = Size(cell, cell))
                                }
                                x += cell
                                col++
                            }
                            y += cell
                            row++
                        }
                    },
            )
            // `.lamp`：右下角那颗 30×30 圆钮，底 `--surface-3`（**填充** token ⇒
            // `MaterialTheme.accents.surface3`，不是做描边的 `outlineVariant`）、
            // 图标 16px。原型里它是 `aria-pressed` 的按钮，但这一层在代码里只是
            // **取景框的占位**（真扫码是整屏 `ScanScreen`），所以画成静态形状、
            // 不挂一个按下去没反应的点击 —— 与这个框"自己不点"的理由一致。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(9.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.accents.surface3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_qr_code_scanner),
                    contentDescription = null,
                    tint = onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = sp.md10),
        )
    }
}

/**
 * 原型 `.banner.info`（`prototype/relay.html:354-362`）：`--surface-2` 底、`--on-2` 字、
 * 16px 信息图标、圆角 `--r-ctl`(12)、`padding:10px 12px`、`gap:9px`、`line-height:1.45`。
 *
 * **为什么不复用 `AppBanner`**：那个组件的 `BannerTone.Info` 也映射到
 * `surfaceVariant`/`onSurfaceVariant`（`ui/components/Common.kt:393-430`），但它是
 * "可关闭的页级横幅" —— 带一颗 `common_close` 文字按钮、`tonalElevation`、`maxLines=3`。
 * 扫码段这条是**不可关闭的说明条**（原型里 `.banner.info` 没有 `.x`），
 * 硬套那个组件会凭空多出一颗关闭钮。而 `AppBanner` 住在 Lead 独占的
 * `ui/components/Common.kt`，不能为这一处去改它的形状。
 */
@Composable
private fun ScanInfoBanner(text: String) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = sp.md),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.md, vertical = sp.md10),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp).size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
    }
}

/**
 * 「我的二维码」底部弹层 —— 原型 `screenMyQr()`（`prototype/relay.html:3437-3460`）。
 *
 * **为什么是弹层，而不是页面里的一张常驻卡**：原型 Gen2 明确把这张码从节点页正文搬走了。
 * 理由写在 `screenMyQr` 的 KDoc 里：它自己占 276px，曾是这一页最大的单块（约该页内容的
 * 20%），把"我有哪些节点"这个主任务挤到了第二屏；而它是**本机身份凭证**，不是"附近有谁"，
 * 常驻位置因此搬到设置里的「连接」那张卡（`m-set-conn`，W3 的屏），节点页只留一条入口。
 * 上一版代码里它还是一张 Gen1 的常驻卡 —— 那正是这一屏和原型对不上的地方。
 *
 * 复用 W3 已落地的 `nodes_my_qr_*` 一组 key（同一件东西：本机身份二维码）。
 *
 * 三种情况各自说清，而不是画一张永远转圈的取景框：
 *  - 拿不到本机地址（节点还没起来）→ 说明为什么现在印不出；
 *  - 地址印不成码（超长等）→ 说明让对方手填地址；
 *  - 正常 → 二维码 + 居中说明 + **载荷原文**（相机坏了、码太远扫不清时，抄这串字一样能配对）。
 *
 * 公开可见的理由与 `AddNodeSheet` 相同：`androidApp` 的截图门禁在另一个 module，
 * 看不见 `commonMain` 的 `internal`，而这一层弹层必须有帧。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQrSheet(
    host: String?,
    port: Int,
    onDismiss: () -> Unit,
    sheetState: SheetState? = null,
    /**
     * 本机全部配对候选（`state.network.localAddresses`）与自己的 nodeId，可选。
     *
     * 给了就走多候选载荷（二维码预算版：内网一条 + 公网 v6 一条）—— 跨网络的对方才能靠
     * 这张码找到我；不给则沿用旧的单地址形态（旧调用点与截图夹具行为不变）。
     */
    addresses: List<String> = emptyList(),
    nodeId: String = "",
    /** 本机 TLS pin（`state.network.selfTlsPin`）；不传则码里没有 pin，跨网手机建不出公网目标。 */
    tlsPin: String? = null,
    /**
     * 本机昵称（`state.settings.nick`）。带上它，对方节点表里**第一眼**就是真名，
     * 而不是 `node-a1b2c3d4` 这种合成名（见 `qrPairingPayloadForAddresses` 的 KDoc）。
     */
    nick: String? = null,
) {
    val sp = LocalAppSpacing.current
    val ownSheetState = rememberModalBottomSheetState()
    val sheet = sheetState ?: ownSheetState
    val payload = pairingQrPayloadFor(
        host = host,
        addresses = addresses,
        port = port,
        nodeId = nodeId,
        tlsPin = tlsPin,
        nick = nick,
    )
    // 编码器对超长内容会抛异常（它拒绝截断）；这里不能让它把整屏打崩，
    // 但也绝不"画一张能扫、内容却是错的码"。
    val code = payload?.let { runCatching { QrEncoder.encode(it) }.getOrNull() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        // 底色同上：原型 `.sheet{background:var(--surface)}`，不取 M3 默认的 surfaceContainerLow。
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = sp.lg)) {
            SheetHead(
                title = stringResource(Res.string.nodes_my_qr_title),
                onClose = onDismiss,
            )
            when {
                payload == null || code == null -> {
                    Text(
                        text = stringResource(
                            if (payload == null) {
                                Res.string.nodes_my_qr_no_address
                            } else {
                                Res.string.nodes_my_qr_too_long
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = sp.lg),
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = sp.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        QrCodeView(
                            code = code,
                            contentDescription = stringResource(Res.string.nodes_my_qr_desc),
                            modifier = Modifier.size(200.dp),
                        )
                    }
                    Text(
                        text = stringResource(Res.string.nodes_my_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 「载荷」一行：左边标签、右边等宽原文，中间一条分隔线（原型是一条 kv）。
                    HorizontalDivider(Modifier.padding(top = sp.lg))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = sp.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.nodes_my_qr_payload),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = payload,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.nodes_my_qr_payload_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 弹层底部留一口气，别让最后一行贴着系统手势条。
            Spacer(Modifier.height(sp.xxl))
        }
    }
}
