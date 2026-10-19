// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.app.ManualPeerEntry
import app.relay.app.RelayController
import app.relay.app.RelayUiState
import app.relay.app.manualPeerStatuses
import app.relay.core.nowMillis
import app.relay.core.qr.QrEncoder
import app.relay.generated.resources.Res
import app.relay.generated.resources.disc_cleanup_unreachable
import app.relay.generated.resources.disc_retry_start
import app.relay.generated.resources.disc_title
import app.relay.generated.resources.disc_vpn_warning
import app.relay.generated.resources.error_unknown
import app.relay.generated.resources.ic_qr_code_scanner
import app.relay.generated.resources.nodes_group_add
import app.relay.generated.resources.nodes_subtitle_short
import app.relay.ui.AppError
import app.relay.ui.ErrorState
import app.relay.ui.components.AddNodeAction
import app.relay.ui.components.AppBanner
import app.relay.ui.components.bannerText
import app.relay.ui.components.LargeTitleBar
import app.relay.ui.components.SectionHeader
import app.relay.ui.components.TitleBarDivider
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.components.relativeTime
import app.relay.generated.resources.ic_link
import app.relay.generated.resources.ic_warn
import app.relay.generated.resources.nodes_add_entry_sub
import app.relay.generated.resources.nodes_group_nodes_count
import app.relay.generated.resources.nodes_my_qr_entry_sub
import app.relay.generated.resources.nodes_my_qr_entry_title
import app.relay.generated.resources.nodes_nodes_empty
import app.relay.app.model.NodePhase


/**
 * 节点页 —— 原型 `screenNodes()`。
 *
 * **这一页合并了原来的「发现」和「加入」两屏**，因为它们本来就是同一件事的两半：
 * 发现是"谁在附近"，加入是"怎么把不在附近的那个弄进来"，而用户在这两件事之间
 * 并不需要切换身份。把它们拆成两个页签的代价是实打实的：底栏 5 个位置里有 2 个
 * 在讲同一类信息，窄屏上标签被挤到截断，而"我现在附近有谁 / 我怎么加一个"这个
 * 问题必须同时看着两屏才能答完整。原型 `NAV` 因此只剩 4 项
 * （`prototype/relay.html:2441` 的 NAV 定义），`AppRoutes.PAIRING` 连同 `feature/pairing/`
 * 一起消失。
 *
 * 三条发现路径的**能力边界不同**，页面必须同时说清"我在广播"和"什么能出现在这里"：
 *  1. UDP 广播 presence —— 与传统单播/广播节点互通（不依赖 mDNS）；
 *  2. mDNS `_relay._udp` —— **只在两个捎话设备之间**生效，电脑永远不会出现在
 *     「局域网发现」里，所以那一栏为空是正常的，栏下有解释而不是空白；
 *  3. 手动填地址 —— 永远可用的兜底，现在收进底部弹层。
 *
 * 顶栏副标题「N 个已知 · X 在线 · Y 离线」与同步条都是**派生量**，直接由
 * [RelayUiState] 现算，不另存一份 —— 存一份就迟早会和下面的列表对不上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    state: RelayUiState,
    onAddPeer: (String, String) -> Unit,
    onRemovePeer: (ManualPeerEntry) -> Unit,
    onCleanupUnreachable: () -> Unit,
    onRetryStart: () -> Unit,
    /**
     * 打开扫码页（真的去调相机扫一次码）。
     *
     * `null` = 调用方这台设备**没有能用的扫码相机**（桌面与平板：没有相机栈），
     * 此时 [AddNodeSheet] 的扫码页不画"按了没反应"的空取景框，而是把原因写出来并把人
     * 导向「填地址」那条路。
     *
     * 手机端（`App.kt`）传的是 `nav.startScanning()` —— 它盖一整屏取景框，而不是就地
     * 调一次 `scanQrCode()`：`scan()` 要等**预览**把结果回调进来，预览不常驻就永不返回。
     * 这条管线由生成侧 `QrEncoder` 与扫描侧 `ScanScreen` 两端组成。
     */
    onScanQr: (() -> Unit)? = null,
    /**
     * 从相册里挑一张二维码图片来解（原型扫码段那颗「从图片导入二维码」）。
     *
     * **与取景框走同一道闸**：`null` 的判据和 [onScanQr] 完全一样 —— 没有扫码相机栈
     * 的平台（桌面 / 平板）两个都是 null，于是这两颗按钮一起消失，而不是留下一颗
     * 按了只会弹「选不了图」的空按钮。手机壳（`App.kt`）传 `controller::importQrImage`，
     * 它内部是 `platform.filePicker.pick()` → `platform.qrScanner.scanImage(path)`
     * → 和扫到码时同一条 `handleQrCode()`（见 `RelayController.importQrImage`）。
     *
     * 为什么是 `suspend () -> Boolean` 而不是 `() -> Unit`：这条路径要等用户选完图、
     * 解完码才回来，调用方要能用返回值区分「解出来了」和「用户取消了 / 图里没有码」。
     * 原型那颗按钮是 `btn block`（通栏描边次级），所以渲染成 `OutlinedButton`。
     */
    onImportQrFromImage: (suspend () -> Boolean)? = null,
    /**
     * 详情页底部那颗「发消息」按下时，跳到那个节点的会话。
     *
     * **列表行本身不再直冲会话**（原型：点一行 = 进这个节点的详情，
     * 见 [onOpenPeerDetail]）—— 发消息从列表撤到详情页底部，与原型
     * `nodeDetailBody` 的 `<button class="btn block pri">发消息</button>` 一致。
     *
     * 为什么不在这里直接导航：`NodesScreen` 是 feature 层的一个组合函数，不认识
     * `RelayNav`（它只认 `app.relay.app` 的几个数据类）—— 组合根 `App.kt` 才拿着导航。
     * 所以这条路和其它跨屏跳转一样，从上面接一条回调进来。
     *
     * `null` = 这个壳没有"打开会话"这件事（桌面 `DesktopNodesPane` 的委托、截图门禁的
     * 静态帧都传 null）。此时详情页不画那颗按钮，而不是给一个点了没反应的入口。
     */
    onOpenChat: ((String) -> Unit)? = null,
    /**
     * 「填对方地址」段里那条**带名字**的连接路径（可选）。
     *
     * `null` = 这个壳没有"给手动节点起个本机显示名"这件事，于是那一格**不画**（见
     * [AddNodeSheet] 的 `onConnectNamed`）；传了才会多出「名字（可选）」那个框。
     * 名字只存本机、不进 wire —— 理由见 `ManualPeerEntry.name` 的 KDoc。
     */
    onAddPeerNamed: ((String, String, String?) -> Unit)? = null,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 桌面壳自己画统一顶栏时置 false —— 三个视图共用一条标题栏，
     * 每屏各画一条会让桌面看起来像三个应用拼在一起。默认 true，手机行为不变。
     */
    showHeader: Boolean = true,
    /** 列表里「离线 ⌯ 12 分钟前」用的时钟，与仓里其它屏同一个约定。 */
    now: Long = nowMillis(),
    /**
     * 正在看哪个节点的详情（`null` = 看列表）。
     *
     * 原型把「点一行」的含义改掉了：不再是直冲会话，而是进这个节点的详情
     * （`nodeRow` 的 `onclick="selNode='…';go('m-nodedetail')"`，`prototype/relay.html:3287`）。
     * 详情是**这一页的一个分支**，不是独立路由 —— 原型 `screenNodeDetail()`
     * （`:3333`）画的仍是 `m-nodes` 这一屏（顶栏返回键跳 `m-nodes`，底栏照画），
     * 所以状态留在这一层，导航层只负责记住"正在看谁"（`AppNavState.detailPeerId`）。
     *
     * 取值口径见 [NodeListItem.key]：对端是 `nodeId`，从没握过手的手动地址是 `host:port`。
     */
    detailPeerId: String? = null,
    /**
     * 点列表里一行时，告诉调用方"现在要看这个节点的详情"。
     *
     * `null` = 这个壳没有详情这一层（桌面 `DesktopNodesPane` 的委托、截图门禁的静态帧），
     * 此时行不可点 —— 而不是给一个点了没反应的入口。
     */
    onOpenPeerDetail: ((String) -> Unit)? = null,
    /** 详情页顶栏返回键：回到节点列表。 */
    onLeavePeerDetail: () -> Unit = {},
    /**
     * 详情页「保存备注」按下时，把 `(nodeId, 备注)` 交给调用方。
     *
     * `null` = 这个壳不提供改备注这条路（截图门禁的静态帧），此时备注框只读、
     * 保存按钮不画 —— 而不是给一颗按了不落盘的按钮。
     */
    onSetPeerRemark: ((String, String) -> Unit)? = null,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    // 弹层从哪一段开：点了「扫码配对」那一行就该落在扫码段，而不是让人自己再点一次。
    var sheetStartsOnScan by remember { mutableStateOf(false) }
    // 「我的二维码」弹层。原型 Gen2 把它从页面正文搬到这条入口后面（见 MyQrSheet 的 KDoc）。
    var myQrOpen by remember { mutableStateOf(false) }
    val network = state.network
    val manualStatuses = manualPeerStatuses(state.settings.manualPeers, state.peers)
    // 合并成一条列表之后，顶栏那两个数字必须**按行数**算，而不是把两个来源相加：
    // 一条手动地址如果已经握上手，它本来就出现在 `peers` 里（`nodeId != null`），
    // 再加一遍 `manualStatuses.size` 会把同一台设备数两次 —— 副标题就会写着「7 个已知」
    // 而下面只有 6 行。口径：已知 = 对端 + 从没回应过的手动地址。
    val unreachableCount = manualStatuses.count { it.unreachable }
    val known = state.peers.size + unreachableCount
    val online = state.onlineCount
    val udpPort = network.boundUdpPort.ifZero(state.settings.udpPort)

    // 节点列表：**一条列表，不分组**。
    //
    // 上一版这里是两个分组（「局域网发现」/「已保存的节点（管理）」）+ 两套行。那套分法
    // 回答的是"它怎么进来的"，而用户在这一页要回答的是"谁现在能用"；它的实际代价是
    // 同一台设备会在两处各出现一次 —— 去重只认 `nodeId`，而**从来没有回应过**的地址
    // 没有 `nodeId`，于是它既落进"手动"那节、又被 mDNS 扫进"发现"那节。
    // 来源因此降级成副标题里的一小段（「局域网」/「手动添加」）。
    // 排序键：在线 → 离线 → 没有回应，同一档内按昵称（稳定、可预期）。
    // 出处：prototype/relay.html:3242-3299 的 `nodeRow` / `nodeRank` / `allNodes`。
    val manualByNodeId = manualStatuses
        .mapNotNull { status -> status.nodeId?.let { it to status } }
        .toMap()
    val nodes = buildList {
        state.peers.forEach { peer ->
            val status = manualByNodeId[peer.nodeId]
            add(
                NodeListItem(
                    key = peer.nodeId,
                    nodeId = peer.nodeId,
                    displayName = peer.displayName,
                    isAgent = peer.isAgent,
                    online = peer.online,
                    // 「没有回应」只可能出现在手动地址上：对端只要能进节点表，
                    // 就说明它至少接过一次手 —— 那是「离线」，不是「没有回应」。
                    never = false,
                    lastSeenText = if (peer.online || peer.lastSeenAt <= 0L) {
                        null
                    } else {
                        relativeTime(peer.lastSeenAt, now)
                    },
                    host = peer.ip.ifBlank { peer.host },
                    port = peer.udpPort.ifZero(state.settings.udpPort),
                    hostName = peer.host,
                    platform = peer.platform,
                    remark = peer.remark,
                    manual = status != null,
                    manualEntry = status?.entry,
                ),
            )
        }
        // 没对上号的（`manualPeerStatuses` 里 `nodeId` 为 null）就是"这条地址从来
        // 没接通过"，它们只可能来自手动那一批。
        manualStatuses.filter { it.nodeId == null }.forEach { status ->
            add(
                NodeListItem(
                    key = "${status.entry.host}:${status.entry.port}",
                    // 从没握过手 = 还没有节点身份：对端不认识这条地址，
                    // 本端也没有它的 `nodeId` 可用（备注卡、会话都要它）。
                    nodeId = null,
                    // 没起名字的手动地址退化成 `host:port` —— 一个光秃秃的地址也比
                    // 一条没有标题的行强。原型 `m08-nodes` 里那两条都是有名字的。
                    displayName = status.entry.name?.takeIf { it.isNotBlank() }
                        ?: "${status.entry.host}:${status.entry.port}",
                    isAgent = false,
                    online = false,
                    never = true,
                    lastSeenText = null,
                    host = status.entry.host,
                    port = status.entry.port,
                    // 这条地址从未接通过：对端没跟它握过手，就没有主机名、平台与备注
                    // 可问 —— 三样都空着，而不是编一个「—」出来。
                    hostName = "",
                    platform = "",
                    remark = "",
                    manual = true,
                    manualEntry = status.entry,
                ),
            )
        }
    }.sortedWith(compareBy<NodeListItem> { it.rank }.thenBy(nodeNameOrder) { it.displayName })

    Column(modifier = modifier.fillMaxSize()) {
        // 详情层与列表层是同一条屏的两个分支。原型 `m-nodedetail` 是**节点页签内部的一个分支**
        // （底栏照画、只多一个顶栏返回键，见 `AppNavState` 里 `detailPeerId` 的 KDoc），
        // 所以这里换掉的是页签内容，不是整屏。
        // 目标行按 [NodeListItem.key] 找：对端是 `nodeId`，从没握过手的手动地址是 `host:port`。
        // 找不到（例如刚被移除）就落回列表 —— 一个空白详情页比列表更难解释。
        val detailItem = detailPeerId?.let { id -> nodes.firstOrNull { it.key == id } }
        if (detailItem != null) {
            if (showHeader) {
                LargeTitleBar(
                    title = detailItem.displayName,
                    subtitle = "${detailItem.host}:${detailItem.port}",
                    onBack = onLeavePeerDetail,
                )
                TitleBarDivider()
            }
            NodeDetailBody(
                item = detailItem,
                onSetPeerRemark = onSetPeerRemark,
                onOpenChat = onOpenChat,
                modifier = Modifier.weight(1f),
            )
        } else {
            if (showHeader) {
                LargeTitleBar(
                    title = stringResource(Res.string.disc_title),
                    // 原型把副标题收成两个数：`screenNodes()` 逐字
                    // `` `${nodes.length} 个节点 · ${online} 在线` ``（`prototype/relay.html:3357`）。
                    // 旧稿是三个数（「N 个已知 · X 在线 · Y 离线」），第三个是前两个的差 ——
                    // 说两遍同一件事的那一类。`nodes_subtitle` 因此不再被任何界面消费。
                    subtitle = stringResource(Res.string.nodes_subtitle_short, known, online),
                    actions = { AddNodeAction(onClick = { sheetStartsOnScan = false; sheetOpen = true }) },
                )
                TitleBarDivider()
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LocalAppSpacing.current.lg),
                // 卡间距 10dp：原型这一页的每张卡之间是内联 `<div style="height:10px"></div>`
                // （`prototype/relay.html` 的 `screenNodes()`，与 `screenCabinetMine()` 同一个手法），
                // 不是页面级的 14dp 行距。上一版取 `lg14` 让整页比原型松一档，
                // 六个节点 + 本机卡 + 添加节点就多滚出小半屏。
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
            ) {
                state.banner?.let { AppBanner(it, onDismissBanner) }

                if (network.vpnActive) {
                    // 原型这一条是 `.banner.warn`（`prototype/relay.html:359`）：
                    // `background:var(--queued-c);color:var(--on-queued-c)` —— 浅橙底 + 深棕字，
                    // 左侧一颗 16px 感叹号（`.banner svg{width:16px;height:16px;margin-top:1px}`），
                    // 圆角 `var(--r-ctl)`=12px，内边距 10/12。
                    // 上一版画成 `surfaceVariant` 灰底 + `onSurfaceVariant` 灰字：形态退化成
                    // 「一句普通说明」，而这句是**警告**（广播发不出去，对方搜不到你）。
                    // 排队色在主题里钉进 M3 `secondaryContainer`/`onSecondaryContainer`
                    // （`ui/theme/Accents.kt` 的 KDoc 明说这条映射）。
                    //
                    // **位置**：原型把它排在**本机卡之前**（`screenNodesEmpty()` 的 `.pad` 第一件
                    // 就是这条 `.banner.warn`，本机卡紧随其后），不是卡后面。理由是这句警告解释的
                    // 正是下面那张卡为什么全都不正常（"广播到不了对端"⇒"没有在广播"⇒"正在监听端口…"），
                    // 先读警告再读证据，因果顺序才对；摆在卡后面，用户先看到一屏"没拿到地址"
                    // 才被告知原因，会先以为程序坏了。
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = LocalAppSpacing.current.md,
                                vertical = LocalAppSpacing.current.md10,
                            ),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_warn),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .size(IconSizes.Body),
                            )
                            Text(
                                text = stringResource(Res.string.disc_vpn_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(start = LocalAppSpacing.current.sm),
                            )
                        }
                    }
                }

                // 「本机」被原型撤掉了 —— `screenNodes()` 的注释逐字：
                // 「节点页不再画「本机」卡（地址 / 发现方式 / 状态 / 节点 ID 都是开发层 plumbing，
                // 普通用户在节点列表用不到；本机身份改由「我的二维码」入口承担）」
                // （`prototype/relay.html:3352`）。地址/发现方式/状态三行都是排查用的，
                // 而它们要回答的问题（"对方搜不到我"）由上面那条 VPN 警告和顶栏那颗「+」回答。
                //
                // 这里**不是**把信息删掉：`network` 仍然被下面读（`vpnActive` / `phase` / 端口），
                // 本机地址与二维码仍在 `MyQrSheet` 里 —— 只是不再占节点页的第一屏。

                if (network.phase == NodePhase.Failed) {
                    ErrorState(
                        error = AppError(
                            code = "",
                            message = network.failure?.let { bannerText(it) }
                                ?: stringResource(Res.string.error_unknown),
                        ),
                        actionLabel = stringResource(Res.string.disc_retry_start),
                        onAction = onRetryStart,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }


                SectionHeader(stringResource(Res.string.nodes_group_nodes_count, nodes.size))
                // 「这一页现在什么都没有」= 一个节点都没有**且**没有扫到还没握手的 mDNS 服务。
                // 两个条件都要：只有 mDNS 服务而没有已入表节点时，下面那半仍然有内容可画，
                // 画成空态会把刚扫到的服务一起藏掉。
                val isEmpty = nodes.isEmpty() && state.mdnsServices.isEmpty()

                // 入口行（`.krow`）与它上面那张卡/那块列表同属一个孩子 —— 原型的 DOM 里
                // `.krow` 紧跟在 `.rows` 之后（`prototype/relay.html:3360-3365`），
                // `.pad` 内部没有任何 gap 容器：`.rows{…gap:8px}` 只管行与行之间（`:146`），
                // `.krow` 与它上面那张卡之间**只有卡片自身的投影**（实测原型 `m08-nodes.png`：末卡白底到 y1210、krow 白底从 y1262 起，中间 51px 全是 `--shadow` 由深到浅的渐变，没有一段纯页面底）。
                // 而 `Arrangement.spacedBy` 是作用在**直接孩子**之间的 —— 上一版把入口行
                // 直接挂在滚动列下，于是凭空多出 10dp 纯底色空隙（代码帧实测 26px @2.5px/dp）。
                // 包一层零间距的 `Column` 就是让「入口行贴着卡」这件事在结构上成立。
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isEmpty) {
                        // 空态是**无条件**的：原型 `screenNodesEmpty()`（`prototype/relay.html:3396-3435`）
                        // 没有任何相位分支，「还在启动」不是整屏加载态，而是本机卡「状态」那一行的
                        // 一句话（`:3408` 的 `正在监听端口…`）。上一版这里挂过一支
                        // `phase == Starting → LoadingState` —— 那等于把原型的设计换成了原型没有的东西，
                        // 而且和「状态」行说同一件事。已按裁决删掉，状态行仍按 phase 出字（见上）。
                        //
                        // 空态是一张**居中的卡**，不是一行靠左的小字：原型 `m10-nodes-empty`
                        // 是 `<div class="card"><div class="padc"><div class="empty" style="padding:22px 12px">
                        // <p>…</p></div></div></div>`，`.empty{text-align:center;color:var(--on-2)}` +
                        // `.empty p{font-size:14px;line-height:1.6;max-width:330px;margin:0 auto}`
                        // （`prototype/relay.html:366-369`）。上一版画成左对齐的 `EmptyHint`（12sp），
                        // 与原型不是同一种东西 —— 这也是 `design-screen-diff.py` 看不见的那类差。
                        EmptyCard(stringResource(Res.string.nodes_nodes_empty))
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                        ) {
                            nodes.forEach { item ->
                                NodeRow(
                                    item = item,
                                    onOpen = onOpenPeerDetail?.let { open -> { open(item.key) } },
                                )
                            }
                            // 广播到但还没握手的 mDNS 服务：给一颗「连接」按钮，因为 mDNS 只知道地址，
                            // 真正入表要等一次 entry/ACK 往返。
                            //
                            // 这一段是代码**独有**的：原型的静态数据里没有"扫到了但还没握手"这个中间态，
                            // 而真机上它恰恰是"局域网发现为空"最常见的原因。它不进 `nodes` —— 还没进
                            // 节点表的东西不该算进「N 个已知」。
                            state.mdnsServices.forEach { service ->
                                MdnsServiceRow(service) { onAddPeer(service.host, service.port.toString()) }
                            }
                            // 「清理没有回应的」一行在这里被原型撤掉了。新稿
                            // `prototype/relay.html` 里 `disc_cleanup_unreachable` 已无任何调用点，
                            // 只剩 `:1675` 一条注释讲"换网段之后会一直挂在这里"的来由；
                            // `nodeRow` 的 KDoc 逐字写着「不区分"握过手又掉"和"从来没通"——
                            // 那是清理时才关心的工程细节，界面只用灰点表达」。
                            // 判据：管理动作不进主列表（同一次改稿撤掉的还有行尾那颗 ⋯ 菜单）。
                            // `onCleanupUnreachable` 形参保留 —— 设置页将来要接的正是这条出路。
                        }
                    }

                    // 添加节点：不再是一张塞在页面底部的表单，而是入口行 ——
                    // 非空态只有「我的二维码」一条，空态多一条「添加节点」。
                    // 「扫码配对 / 填地址」两个入口现在都只在弹层里（顶栏那颗「+」开的就是它），
                    // 原型 `screenNodes()` 逐字：`rows` 之后只有一条 `.krow` →
                    // `<div class="krow" onclick="go('m-myqr')">`（`prototype/relay.html:3361-3365`）；
                    // `screenNodesEmpty()` 才是两条（`:3422-3429`）。
                    //
                    // 这一块**不是卡片**：`.krow{background:var(--surface);border-bottom:1px solid var(--hair)}`
                    // 平铺、无圆角、行与行之间只有一条发丝线（`prototype/relay.html:199-200` +
                    // `.krow:last-child{border-bottom:0}` `:223`）。上一版套了一层
                    // `Surface(shape = shapes.medium, tonalElevation = …)` ⇒ 一屏里凭空多出一张
                    // 圆角卡、多一层色调浮起，而且把「入口行」画成了「卡片」这一档语义。
                    // 上一版还在它前面挂了一行 `SectionHeader(nodes_group_add)`：新稿非空态
                    // 根本没有「添加节点」这个小节标题（`screenNodes()` 只有 `节点（N）` 一个）。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        if (isEmpty) {
                            AddNodeEntryRow(
                                title = stringResource(Res.string.nodes_group_add),
                                subtitle = stringResource(Res.string.nodes_add_entry_sub),
                                icon = Res.drawable.ic_link,
                                onClick = { sheetStartsOnScan = false; sheetOpen = true },
                                showDivider = true,
                            )
                        }
                        // 「扫码配对」是"我去扫别人"，这一条是"让别人扫我" —— 少了它，
                        // 扫码配对就只做通了一半。原型 Gen2 把二维码**从页面正文搬到了这条入口后面**
                        // （见 `MyQrSheet` 的 KDoc：那张码原来占节点页一整张卡，实测 276px、
                        // 占该页内容 20%，把「有哪些节点」这个主任务挤到了第二屏）。
                        AddNodeEntryRow(
                            title = stringResource(Res.string.nodes_my_qr_entry_title),
                            subtitle = stringResource(Res.string.nodes_my_qr_entry_sub),
                            icon = Res.drawable.ic_qr_code_scanner,
                            onClick = { myQrOpen = true },
                            showDivider = false,
                        )
                    }
                }
        }
        }
    }

    if (sheetOpen) {
        AddNodeSheet(
            state = state,
            onConnect = { host, port ->
                onAddPeer(host, port)
                sheetOpen = false
            },
            // 带名字那条（可选）：手机壳传 `controller::addNamedManualPeer`，于是弹层里
            // 多一个「名字（可选）」框；不传的壳连那个框都不画。
            onConnectNamed = onAddPeerNamed?.let { add ->
                { host, port, name ->
                    add(host, port, name)
                    sheetOpen = false
                }
            },
            onDismiss = { sheetOpen = false },
            // 弹层里的扫码沿用本屏传下来的那条路径（`onScanQr`）。
            // 手机端传的是真入口（点下去盖一整屏取景框）；画不出相机预览的平台（桌面/平板壳）
            // 传 null，弹层就整段不画，而不是给一个"打开相机"却开不出相机的按钮。
            // 扫完节点是直接加进列表的，所以那天空出来时也不关弹层。
            initialScanMode = sheetStartsOnScan,
            onScanQr = onScanQr,
            // 「从图片导入二维码」与取景框同一道闸：桌面壳传下来的也是 null，两颗一起消失。
            onImportQrFromImage = onImportQrFromImage,
        )
    }

    if (myQrOpen) {
        MyQrSheet(
            host = network.localAddresses.firstOrNull(),
            port = udpPort,
            onDismiss = { myQrOpen = false },
            // 带全部候选：手机「我的二维码」也要能跨网络被找到（见 pairingQrPayloadFor）。
            addresses = network.localAddresses,
            nodeId = state.selfNodeId,
            // task-86：还要带 pin，否则跨网手机建不出公网 TLS 目标（真机就死在这一格）。
            tlsPin = network.selfTlsPin,
            // 带昵称：对方扫完立刻看见真名，而不是 node-a1b2c3d4。
            nick = state.settings.nick,
        )
    }
}

/**
 * 节点列表里的一行，**在进列表之前就消化掉两个分组的差异**。
 *
 * 上一版是两套行（`PeerRow` / `ManualPeerRow`）、两个分组，字段各画各的：手动那行只画
 * `host:port`，于是"手动加进来的老周"和"扫到的老周"看起来是两种东西。合成一行之后，
 * 差异只剩三个布尔：`online` / `never` / `manual`（后两个只在手动地址上可能出现）。
 *
 * `never` 与 `online == false` 必须分开：离线是握过手又掉了，对方开机就回来；
 * 「没有回应」是这条地址从来没接通过 —— 只有它才轮得到清理，也只有它不能点（点进去
 * 只会看到一个永远发不出去的会话）。原型把这条写进了 `nodeRank` 与 `nodeRow`。
 */
internal data class NodeListItem(
    /** 稳定标识：对端用 `nodeId`，没握过手的手动地址用 `host:port`。 */
    val key: String,
    /**
     * 对端的节点身份；`null` = 这条地址从来没握过手（手动填的 `host:port`）。
     *
     * 与 [key] 分开：`key` 要**每一行都有值**（选中态、详情定位都拿它比），
     * 而这一条说的是"对端有没有一个身份"。详情页的备注卡与「发消息」都要它
     * —— 原型给这两样各设了一道同一形状的闸（`saveRemark` 的 `if (el && p.nodeId)`、
     * 「发消息」的 `p.never` 禁用，`prototype/relay.html:3322-3344`）。
     */
    val nodeId: String?,
    val displayName: String,
    val isAgent: Boolean,
    val online: Boolean,
    val never: Boolean,
    /** 只有「离线」才印：最后一次见到它是什么时候。`null` = 不印。 */
    val lastSeenText: String?,
    /** 列表第二行印的那个地址：对端优先用 `ip`，没拿到就回落主机名。 */
    val host: String,
    val port: Int,
    /**
     * 详情页「主机」那一行：对端自己声明的主机名（`zhou-pc` / `Lin-MacBook-Pro`）。
     *
     * 与 [host] 是**两件事**：`host` 是"怎么连上它"（IP），这一条是"它自称叫什么"
     * （原型 `nodeDetailBody` 的 `<span class="v mono">${p.hostName}</span>`）。
     * 上一版列表把来源压进副标题，于是这个字段没有任何界面读它 —— 详情页是它
     * 唯一的出口。
     */
    val hostName: String,
    /** 详情页「平台」那一行。手动地址没有对端声明，写 `—`。 */
    val platform: String,
    /** 详情页备注框的初值。空串 = 没起过（原型 `${p.remark || ''}`）。 */
    val remark: String,
    /**
     * 这条地址是手动加的。
     *
     * 之后它**不再**决定副标题（原型把「来源」那半砍了），只决定
     * 「没有回应」这一档能不能出现（见 [never]）。
     */
    val manual: Boolean,
    /** 手动节点的原始记录；`null` = 这条是局域网扫到的。 */
    val manualEntry: ManualPeerEntry?,
) {
    /** 排序键：在线 → 离线 → 没有回应。出处：原型 `nodeRank`。 */
    val rank: Int get() = if (never) 2 else if (online) 0 else 1
}






