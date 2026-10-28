// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.domain.DesktopPaneActions

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.kapp.tidings.ui.components.PressableSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import com.kapp.tidings.platform.PlatformLazyListScrollbar
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.NodeRemoval
import com.kapp.tidings.domain.manualPeerStatuses
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.feature.nodes.NodesScreen
import com.kapp.tidings.feature.nodes.NodeDetailBody
import com.kapp.tidings.feature.nodes.NodeListItem
import com.kapp.tidings.feature.nodes.NodePresence
import com.kapp.tidings.feature.nodes.AddNodeSheet
import com.kapp.tidings.feature.nodes.manualEntriesByNodeId
import com.kapp.tidings.feature.nodes.nodeNameOrder
import com.kapp.tidings.ui.components.AddNodeAction
import com.kapp.tidings.ui.components.AppBanner
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppIconButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.KindBadge
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.StatusDot
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import androidx.compose.material3.minimumInteractiveComponentSize
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.ui.theme.accents
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.desktop_nodes_copy_id
import com.kapp.tidings.generated.resources.desktop_nodes_local_id
import com.kapp.tidings.generated.resources.disc_cleanup_unreachable
import com.kapp.tidings.generated.resources.disc_manual_offline
import com.kapp.tidings.generated.resources.disc_manual_online
import com.kapp.tidings.generated.resources.disc_manual_unreachable
import com.kapp.tidings.generated.resources.node_detail_never_hint
import com.kapp.tidings.generated.resources.nodes_lan_empty
import com.kapp.tidings.generated.resources.nodes_subtitle_short
import com.kapp.tidings.generated.resources.ic_copy
import com.kapp.tidings.generated.resources.ic_lan_search
import org.jetbrains.compose.resources.stringResource

// ------------------------------------------------------------------ 节点视图

/**
 * 桌面节点视图（原型 `d-nodes`）：**左边一份精简节点列表 + 右边选中节点的详情**。
 *
 * ## 版式出处（用户亲改原型之后的新稿）
 *
 * `prototype/relay.html` 的 `screenDNodes()`（`:4992-4996`）逐字是
 * `dwin('nodes', `<div class="dbody">${nodeDetailBody(p)}</div>`, {side: sideNodes(selNode), view:'nodes'})`
 * —— 左栏 `sideNodes`（`:4737-4751`）、右栏 `nodeDetailBody`（`:3296-3331`）。
 * 上一版把手机那份 `NodesScreen` 整张塞进右栏（于是屏幕上同时出现**两份列表**），
 * 左栏还按「局域网发现 / 已保存」分两组、每行带行尾动作 —— 都与新稿不符，已删。
 *
 * 新稿上方那段原型注释（`:4985-4991`）逐字交代两处收敛：① 桌面**不再**单独画 mDNS
 * 同步条、**不再**画「本机节点 ID」条（两条都是开发层 plumbing，普通用户在节点页用不到）；
 * ② 左栏收平为**单列表**，一行只有 昵称 + IP + 在线/离线，不分组、不列平台/lastSeen。
 * 于是 [SelfNodeIdStrip] 在节点页**已无调用点**（函数体保留，见它自己的 KDoc）。
 *
 * ## 为什么这里要自己再判一次档位
 *
 * 形状照抄 [FilesPane]（`DesktopFilesPane.kt` 的 KDoc 与 `BoxWithConstraints`），
 * 理由也一样：`DesktopShell` 在 Expanded/Medium 档会先切掉左侧 64px 竖导
 * （`DesktopMetrics.MEDIUM_RAIL_PX`），这块面板真正拿到的宽度**不等于**窗口宽 ——
 * 该不该收列要按本面板自己的可用宽度判，不是照抄整个窗口的档位。参数留 `null` 默认值，
 * 调用点不传也能编译。
 *
 * ## 档位与版式
 *
 * - **Expanded / Medium**：双栏。左栏 `sideNodes`（本文件 [NodeList]）、右栏
 *   `nodeDetailBody`（[NodeDetailPane]）。
 * - **Compact**：单列，仍是手机那份 `NodesScreen`（原型 `GALLERY` 只给了
 *   `['d-nodes','expanded']` 一格，对窄档没有表态 —— 这一档是代码欠账的修复，不是照原型）。
 *
 * 这里**只**用宽度分档：`desktopWindowSizeClass` 里 Compact 由
 * `widthPx < MEDIUM_MIN_PX`(664) 单独决定，与高度无关。高度那一半在面板里其实是
 * **不可靠**的 —— 壳已经把窗口高度扣过一次 chrome，`BoxWithConstraints` 拿到的
 * `maxHeight` 是扣过之后的，再交给 `desktopWindowSizeClass` 会被扣第二次（审计 §2 记的
 * 口径瑕疵）。它最多把本面板从 Expanded 误降成 Medium，而在本函数里这两档走同一条路
 * （都是双栏），所以结论不受影响。
 *
 * ## 诚实边界
 *
 * 1. 「没有回应」的手动地址在数据层**没有 nodeId**（`ManualPeerStatus.unreachable`
 *    的定义就是 `nodeId == null`），而备注是按 nodeId 存的（`RelaySettings.peerRemarks`）
 *    ⇒ 这一类节点**不画备注卡**（原型画了，但它的数据里 `p.nodeId` 对死地址也存在）。
 *    「发消息」按原型置灰，下面补一行 `node_detail_never_hint`。
 * 2. 副标题（原型 `tsub('nodes')`，`:4849-4853`）在原型里属**窗口标题栏**，所以默认由
 *    桌面壳画（`DesktopChrome.kt` 的 `desktop_title_nodes` 已换成同一个 key）。只有
 *    平板宿主这种**没有标题栏**的档，才由 [showNodeCountInPane] 在左栏顶部补一行 ——
 *    否则这一读数在那台机器上根本不出现。
 * 3. 手写体量：`--kv-k`（详情里「最近 / IP 地址 / 主机 / 平台」的**标签列宽**）在桌面窗
 *    是 `104px`（`prototype/relay.html:600` 的 `.win` 覆盖值），不在 `AppSpacing` 任何档上，
 *    按「保真档」保留并登记。
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
public fun NodesPane(
    state: RelayUiState,
    controller: DesktopPaneActions,
    /**
     * 已无用途，**保留形参**：第一轮的帧文件（`ScreenshotPreviewsDesktopPanes.kt`，
     * 本轮禁改）仍按命名实参传它。新稿不再画「本机节点 ID」条。
     */
    onCopyNodeId: () -> Unit,
    /**
     * 保存本地备注（空串 = 删除）。备注只存本机、重名时用于辨认
     * （`RelaySettings.peerRemarks`，上限 `LIMIT_REMARK = 32`）。
     */
    onSetPeerRemark: (String, String) -> Unit = { _, _ -> },
    /** 详情底部「发消息」；参数是 nodeId。 */
    onOpenChat: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * 壳传进来的窗口分档；`null` = 没传，按**本面板自己**的可用宽度用
     * [desktopWindowSizeClass] 重判一次。语义与 `FilesPane.sizeClass` 完全一致。
     */
    sizeClass: DesktopWindowSizeClass? = null,
    /**
     * 预置选中项（截图帧用）；`null` = 选列表第一项。
     *
     * 既能传 `nodeId`，也能传列表项的 `key`（`"peer:<nodeId>"` / `"manual:<host>:<port>"`）——
     * 后者是**唯一**能把「没有回应」那条死地址的详情摆到右栏的办法：它没有 nodeId，
     * 列表里排在对端之后，不预置就永远拍不到。
     */
    initialNodeId: String? = null,
    /**
     * 左栏顶部是否补一行「N 个节点 · M 在线」。原型这句话在窗口标题栏，桌面壳会画；
     * 这里只为**没有标题栏的宿主**（平板）兜底，默认 `false`。
     */
    showNodeCountInPane: Boolean = false,
    /**
     * 「最近」那行要写相对时间（`34 天前`），所以得有一个可注入的"现在"。
     * 默认取真实时钟；预览帧传固定值，否则同一张参考图每天都不一样。
     */
    now: Long = nowMillis(),
    /**
     * 外部（系统菜单「新建会话」）要求**打开添加节点面板**的请求计数。
     *
     * 用计数器而不是 Bool：同一个命令按两次要能触发两次。与 `externalNodesRequest`
     * 同一套约定（见 `DesktopShell` 里那条的 KDoc）。
     */
    externalAddNodeRequest: Int = 0,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val tier = sizeClass
            ?: desktopWindowSizeClass(maxWidth.value.toInt(), maxHeight.value.toInt())

        val entries = nodeEntries(state)
        val onlineCount = entries.count { it.presence == NodePresence.Online }
        // 选中态用 key（`peer:<nodeId>` / `manual:<host>:<port>`）而不是 nodeId：
        // 「没有回应」的手动地址根本没有 nodeId。`remember` 不带 entries 作 key ——
        // entries 每次组合都是新 List，带上它等于每次重组都把选中项重置回首项。
        var selectedKey by remember {
            mutableStateOf(
                initialNodeId?.let { id -> entries.firstOrNull { it.nodeId == id || it.key == id }?.key }
                    ?: entries.firstOrNull()?.key,
            )
        }
        val selected = entries.firstOrNull { it.key == selectedKey } ?: entries.firstOrNull()
        // 宽档的「添加节点」弹层。Compact 那支由 `NodesScreen` 自己管这个状态，
        // 这里必须自己有一份 —— 否则宽窗上那个入口点下去什么都不发生。
        var addNodeOpen by remember { mutableStateOf(false) }
        // 外部请求（系统菜单「新建会话」）：计数器变了就打开面板。
        // 用 `LaunchedEffect(key)` 而不是在组合里直接改状态 —— 组合期写状态会触发重组循环。
        LaunchedEffect(externalAddNodeRequest) {
            if (externalAddNodeRequest > 0) addNodeOpen = true
        }

        // 横幅宿主只有**一个**：整个节点页要么走窄档、要么走宽档，两条分支互斥
        // （同一个 if/else 决定哪棵子树存在）。
        //  - 窄档：把整页委托给 `NodesScreen`，横幅由它画（它自己的 `state.banner?.let`）；
        //  - 宽档：本文件自建双栏、不调 `NodesScreen`，所以横幅只能由下面那行画。
        // 因此 `state.banner?.let { AppBanner(...) }` **必须**留在 else 分支里：
        // 抬到 if 之上就成了「窄档时本文件一条 + NodesScreen 一条」的双横幅，
        // 且两条都带自己的关闭按钮（同一条提示两个 ✕）。
        if (tier == DesktopWindowSizeClass.Compact) {
            Column(Modifier.fillMaxSize()) {
                // 这里**不要**再画一次 `state.banner`：下面的 `NodesScreen` 自己就会画
                // （`NodesScreen.kt` 的 `state.banner?.let { AppBanner(...) }`），
                // 两处都画会在桌面节点视图上把同一条横幅画两次、带两个「关闭」——
                // `desk-nodes-banner-expanded` 那张参考图就是为钉住这一支才建的。
                if (showNodeCountInPane) NodeCountLine(entries.size, onlineCount)
                NodesScreen(
                    state = state,
                    onAddPeer = controller::addManualPeer,
                    onRemovePeer = controller::removeNode,
                    // 「连接」已保存但不通的地址：mDNS 行被判重滤掉后，这是唯一能把设备连回来的动作。
                    onReconnectPeer = controller::reconnectManualPeer,
                    onCleanupUnreachable = controller::removeUnreachableManualPeers,
                    onRetryStart = controller::requestRestart,
                    // 桌面端本来就没有相机（`DesktopPlatform.cameraGate` 直接抛 Unsupported），
                    // 接上去只会在点一下之后报一条"扫不了"的横幅 —— 那是在给一个注定失败的按钮。
                    // 现在传 null，扫码页如实写"这台设备扫不了 + 请填地址"。
                    onDismissBanner = controller::dismissBanner,
                    showHeader = false,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // 横幅：Compact 那支调 `NodesScreen`，它自己会画（见上面那段注释）；
                // 宽档走的是本文件的自建双栏、**不再**调 `NodesScreen`，所以要在这里补一次。
                // 漏掉的后果是实打实的退化：节点起不来时宽窗上那条 `BindFailed` 横幅整条消失，
                // `desk-nodes-banner-expanded` 与 `desk-nodes-expanded` 会变成逐字节相同的两张图
                // （`render-preview.py --dup` 就是那样把它抓出来的）。
                state.banner?.let { AppBanner(it, onDismiss = controller::dismissBanner) }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    NodeList(
                        entries = entries,
                        selectedKey = selected?.key,
                        onlineCount = onlineCount,
                        showCount = showNodeCountInPane,
                        onSelect = { entry -> selectedKey = entry.key },
                        onAddNode = { addNodeOpen = true },
                        // 「清理没有回应的」与手机列表同一条出路：只在真有死地址时出现。
                        onCleanupUnreachable = controller::removeUnreachableManualPeers,
                        unreachableCount = entries.count { it.never },
                        modifier = Modifier
                            .width(DesktopMetrics.SIDEBAR_PX.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDividerLine()
                    NodeDetailPane(
                        entry = selected,
                        now = now,
                        onSetPeerRemark = onSetPeerRemark,
                        onOpenChat = onOpenChat,
                        // 「移除节点」与手机详情同一颗（`NodeDetailBody` 内带确认框，
                        // 只对有手动记录的行出现）。
                        onRemovePeer = controller::removeNode,
                        // 「连接」：手动保存过、当前不通的地址（mDNS 行被判重滤掉后的唯一出路）。
                        onReconnectPeer = controller::reconnectManualPeer,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
        // **宽档的添加节点弹层。** 与 `NodesScreen` 里那份是同一个组件（`AddNodeSheet`），
        // 不重写：扫码两个回调传 null —— 桌面端没有相机栈，传了只会多出一个点下去报错的
        // 按钮；组件在 null 时如实写「这台设备扫不了」并把路引到填地址。
        // **这里不关弹层**：连接是异步握手，关早了弹层里那套"超时红字 + 原地重试"
        // 就永远走不到；成功关层由弹层自己判（节点表里出现目标即收）。
        if (addNodeOpen) {
            AddNodeSheet(
                state = state,
                onConnect = { host, port ->
                    controller.addManualPeer(host, port)
                },
                onDismiss = { addNodeOpen = false },
            )
        }
    }
}

// ------------------------------------------------------------------ 数据


/**
 * 左栏与详情共用的**一行**。
 *
 * [nodeId] 为 null 只有一种来源：「保存了但从来没接通过」的手动地址
 * （`manualPeerStatuses` 里 `unreachable` 的那些，它们在节点表 `state.peers` 里没有对应行）。
 */
/**
 * 节点表 + 「只有地址、从没接通」的手动条目，合成一份列表。
 *
 * 一条列表，不分组。排序与手机端**同一把**（`NodesScreen` 的口径：
 * 在线 → 离线 → 没有回应，档内按昵称）—— 同一批数据两个壳必须同一副面孔。
 */
internal fun nodeEntries(state: RelayUiState): List<NodeListItem> {
    val statuses = manualPeerStatuses(state.settings.manualPeers, state.peers)
    // 手动地址 ↔ 节点表的对应关系与手机端**同一份**（[manualEntriesByNodeId]）。
    // 上一版这里把每个对端的 `manualEntry` 硬置 `null`，于是「已经握过手的手动地址」
    // 在桌面右栏详情没有「移除」、在手机上却有 —— 同一行两个壳两副面孔。
    val manualByNodeId = manualEntriesByNodeId(statuses)
    val known = state.peers.map { peer ->
        NodeListItem(
            key = "peer:${peer.nodeId}",
            nodeId = peer.nodeId,
            displayName = peer.displayName,
            isAgent = peer.isAgent,
            online = peer.online,
            // 「没有回应」只可能出现在手动地址上：对端只要能进节点表，就说明它至少
            // 接过一次手 —— 那是「离线」，不是「没有回应」。
            never = false,
            host = peer.ip.ifBlank { peer.host },
            port = peer.udpPort,
            hostName = peer.host,
            platform = peer.platform,
            remark = peer.remark,
            manual = manualByNodeId.containsKey(peer.nodeId),
            manualEntry = manualByNodeId[peer.nodeId],
            lastSeenAt = peer.lastSeenAt,
        )
    }
    val never = statuses
        .filter { it.nodeId == null }
        .map { status ->
            NodeListItem(
                key = "manual:${status.entry.host}:${status.entry.port}",
                nodeId = null,
                displayName = status.entry.name?.takeIf { it.isNotBlank() }
                    ?: "${status.entry.host}:${status.entry.port}",
                isAgent = false,
                online = false,
                never = true,
                host = status.entry.host,
                port = status.entry.port,
                // 「主机」是对端**自己声明**的名字；这条地址从没通过，就没有声明可读。
                // 上一版把用户填的 entry.host 塞进来 —— 那是"怎么连它"，不是"它自称什么"，
                // 详情页会印出一个像真名其实是输入残片的值。空串让详情页印「—」，
                // 与手机端同一口径。
                hostName = "",
                platform = "",
                // 从没接通过：没有备注可问，三个空值而不是编一个占位符。
                remark = "",
                manual = true,
                manualEntry = status.entry,
                lastSeenAt = 0L,
            )
        }
    // 与手机同一把排序（`NodesScreen` 的口径：在线 → 离线 → 没有回应，档内按昵称）。
    // 上一版「节点表顺序 + 死地址垫底」，同一批数据两个壳两副面孔 ——
    // 手机把刚离线的排中间，桌面把它压到末尾，并排看就像两份不同的列表。
    return (known + never).sortedWith(
        compareBy<NodeListItem> { it.rank }.thenBy(nodeNameOrder) { it.displayName },
    )
}

@Composable
private fun presenceLabel(presence: NodePresence): String = when (presence) {
    NodePresence.Online -> stringResource(Res.string.disc_manual_online)
    NodePresence.Offline -> stringResource(Res.string.disc_manual_offline)
    NodePresence.Never -> stringResource(Res.string.disc_manual_unreachable)
}

/** 状态点颜色：在线用 `accents.online`，离线 `onSurfaceVariant`，从来没有回应用 `error`。 */
@Composable
private fun presenceDotColor(presence: NodePresence): Color = when (presence) {
    NodePresence.Online -> MaterialTheme.accents.online
    NodePresence.Offline -> MaterialTheme.colorScheme.onSurfaceVariant
    NodePresence.Never -> MaterialTheme.colorScheme.error
}

// ------------------------------------------------------------------ 左栏

/**
 * 左栏：原型 `sideNodes`（`prototype/relay.html:4737-4751`）的单列表。
 *
 * `.sidelist{flex:1;min-height:0;overflow-y:auto;padding:0 8px;gap:1px}`（`:684`）——
 * 横向 8px、行间 1px、**没有标题也没有分组**。
 */
@Composable
private fun NodeList(
    entries: List<NodeListItem>,
    selectedKey: String?,
    onlineCount: Int,
    showCount: Boolean,
    onSelect: (NodeListItem) -> Unit,
    /**
     * 「添加节点」。
     *
     * **宽档必须自己提供这一条**：`Compact` 那支调的是 `NodesScreen`，它标题栏里有
     * `AddNodeAction`、列表底部还有一行「添加节点」；而宽档走的是本文件自建的双栏，
     * 这两处都不在里面 —— 真机上宽窗因此**完全没有添加入口**，用户只能看见已有节点。
     */
    onAddNode: () -> Unit,
    /**
     * 「清理没有回应的」（手机列表同一条出路，`NodesScreen` 里那份的桌面孪生）。
     * `unreachableCount > 0` 时才画 —— 没有死地址时一条管理行都不该占地方。
     */
    onCleanupUnreachable: () -> Unit,
    /** 「没有回应」的条数，画进按钮文案（`disc_cleanup_unreachable` 带 %1$d）。 */
    unreachableCount: Int,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Column(modifier) {
        // 计数行与添加入口同一行：入口在最常用的位置，又不额外占一条纵向空间。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showCount) {
                Box(Modifier.weight(1f)) { NodeCountLine(entries.size, onlineCount) }
            } else {
                Spacer(Modifier.weight(1f))
            }
            AddNodeAction(onClick = onAddNode, modifier = Modifier.padding(end = sp.xs))
        }
        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xs),
                verticalArrangement = Arrangement.spacedBy(sp.xxs),
            ) {
                items(entries, key = { it.key }) { entry ->
                    NodeListRow(
                        entry = entry,
                        selected = entry.key == selectedKey,
                        onClick = { onSelect(entry) },
                    )
                }
            }
            PlatformLazyListScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        if (unreachableCount > 0) {
            AppTextButton(
                text = stringResource(Res.string.disc_cleanup_unreachable, unreachableCount),
                onClick = onCleanupUnreachable,
                // 命中区 48dp：与手机端同一条无障碍下限。
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = sp.sm, vertical = sp.xs),
                size = AppButtonSize.Small,
            )
        }
    }
}

/** 「N 个节点 · M 在线」。原型里它在标题栏（`tsub('nodes')`），这里只给无标题栏的宿主兜底。 */
@Composable
private fun NodeCountLine(count: Int, onlineCount: Int) {
    val sp = LocalAppSpacing.current
    Text(
        text = stringResource(Res.string.nodes_subtitle_short, count, onlineCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.sm6),
    )
}

/**
 * 左栏一行（原型 `.li`，`prototype/relay.html:688-705`）：
 * 头像 32（圆角 12）+ 右下角状态点 + 昵称（14/600，可选 `Agent` 标签）+
 * 第二行「在线/离线/没有回应 · IP:端口」（12px，`--on-2`）。
 *
 * 选中态 = `aria-selected="true"`：`background:var(--surface)` +
 * `box-shadow:0 0 0 1px var(--hair)`（`:690`）。这里用同色的 `border` 表达那圈 hairline。
 */
@Composable
private fun NodeListRow(entry: NodeListItem, selected: Boolean, onClick: () -> Unit) {
    val sp = LocalAppSpacing.current
    val scheme = MaterialTheme.colorScheme
    val borderStroke = if (selected) BorderStroke(1.dp, scheme.outlineVariant) else null
    PressableSurface(
        onClick = onClick,
        color = if (selected) scheme.surface else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (borderStroke != null) Modifier.border(borderStroke, MaterialTheme.shapes.medium) else Modifier),
    ) {
        Row(
            // `.li{padding:7px 9px;min-height:52px}` + 32px 头像 ⇒ 上下各 10px 时正好 52；
            // 横向 9px 取最近的 sp.sm(8)。
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.md10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.md10),
        ) {
            NodeAvatar(
                name = entry.displayName,
                isAgent = entry.isAgent,
                presence = entry.presence,
                size = NodeListAvatarSize,
                shape = MaterialTheme.shapes.medium,
                ringed = false,
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isAgent) KindBadge(isAgent = true)
                }
                Text(
                    text = "${presenceLabel(entry.presence)} · ${entry.host}:${entry.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 头像

private val NodeListAvatarSize = 32.dp
private val NodeDetailAvatarSize = 72.dp

/**
 * 头像 + 右下角状态点。
 *
 * `PeerAvatar` 与 `StatusDot` 都没有 `modifier` 形参（见 `ui/components/Common.kt` 里各自的声明），
 * 只能用一个 `Box` 把点叠在头像右下角。`.av.xl .p`（`:302`）是 18px 实心圆 + 2px 的
 * `--surface` 描边 —— 那圈描边让点在大头像上不与头像底色糊在一起，所以只有大头像
 * （[ringed]）才补它；列表里的 10px 小点按原型本来就没有描边。
 */
@Composable
private fun NodeAvatar(
    name: String,
    isAgent: Boolean,
    presence: NodePresence,
    size: Dp,
    shape: Shape,
    ringed: Boolean,
) {
    val sp = LocalAppSpacing.current
    Box {
        PeerAvatar(name = name, isAgent = isAgent, size = size, shape = shape)
        val dot = if (ringed) IconSizes.Inline else IconSizes.Badge
        val dotModifier = if (ringed) {
            Modifier
                .align(Alignment.BottomEnd)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(sp.xxs)
        } else {
            Modifier.align(Alignment.BottomEnd)
        }
        Box(modifier = dotModifier) {
            StatusDot(
                online = presence == NodePresence.Online,
                size = dot,
                color = presenceDotColor(presence),
            )
        }
    }
}

// ------------------------------------------------------------------ 右栏详情

/**
 * 右栏：原型 `nodeDetailBody`（`prototype/relay.html:3296-3331`），装在 `.dbody` 里
 * （`:731`：`padding:14px 16px 16px` + `gap:13px` 的纵向 flex）。
 *
 * 四块：头部（72px 头像 + 昵称 + `Agent` 标签 + 状态药丸 + 「未分组」）→ 备注卡 →
 * 详细信息卡（最近 / IP 地址 / 主机 / 平台）→ 整宽「发消息」。
 *
 * [entry] 为 null 只在「一个节点都没有」时发生：那由外壳的空态负责，这里留空即可。
 */
@Composable
private fun NodeDetailPane(
    entry: NodeListItem?,
    now: Long,
    onSetPeerRemark: (String, String) -> Unit,
    onOpenChat: (String) -> Unit,
    /** 「移除节点」确认后直通 `controller::removeNode`（见 [NodeDetailBody]）。 */
    onRemovePeer: (NodeRemoval) -> Unit,
    /** 「连接」一条已保存但不通的手动地址（见 `RelayController.reconnectManualPeer`）。 */
    onReconnectPeer: ((ManualPeerEntry) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (entry == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.nodes_lan_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // **手机与桌面共用同一个详情面板**（`NodeDetailBody`）：头部 + 备注卡 + 详细信息卡
    // + 发消息 + 管理动作（复制 / 移除），四处都只有一份实现。桌面这里只多一层
    // "没有选中项"的空态。
    NodeDetailBody(
        item = entry,
        onSetPeerRemark = onSetPeerRemark,
        onOpenChat = onOpenChat,
        onRemovePeer = onRemovePeer,
        onReconnectPeer = onReconnectPeer,
        now = now,
        modifier = modifier,
    )
}

/**
 * `.kv .k{width:var(--kv-k)}` 的桌面窗取值（`prototype/relay.html:600` 的 `.win` 覆盖
 * `--kv-k:104px`，`:59` 的根值是 118px —— 桌面窗以 104 为准）。
 */
private val DetailKeyWidth = 104.dp

/** `RelaySettings.LIMIT_REMARK`；这里只要长度上界，不引 `RelaySettings` 以免把设置模型拖进来。 */
private const val RemarkLimit = 32

/**
 * 本机节点 ID 条。
 *
 * **当前无调用点**：用户亲改原型时，桌面节点页按新稿去掉了这条 plumbing
 * （`screenDNodes` 上方注释逐字「不再单独画『本机节点 ID』条（这两条都是开发层 plumbing，
 * 普通用户在节点页用不到）」）。函数体保留待用 —— 手机端仍把同一张卡画在节点页正文里，
 * 桌面若哪天需要「拿到全量 nodeId 并复制」的入口，这里就是现成的实现。
 *
 * 节点 ID 是对方「手动添加节点」时要填的东西；标题栏只显示前 8 位，界面别处也没有
 * 完整值 —— 不给全量与复制入口，用户就只能去翻诊断包。等宽字体是因为
 * 这串东西要被人逐字符核对，比例字体里 `0/O`、`1/l` 分不清。
 *
 * @param modifier 调用点自己的修饰符。
 * @param contentMaxWidth 非 null 时把**整条卡片**收进这么宽一列并居中；单列档（Compact）
 *   传 `null`，卡片照旧满幅 —— 那一档面板窄于上限，收列本来就是空操作。
 */
@Composable
public fun SelfNodeIdStrip(
    nodeId: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    contentMaxWidth: Dp? = null,
) {
    if (nodeId.isBlank()) return
    // 收列必须靠「外层 Box 居中 + 内层 widthIn 夹宽」，不能把 `centerCapped` 直接挂在
    // Surface 上：`centerCapped` 对外报告的是父级满宽（见 `AdaptiveContent.kt` 对四段链的
    // 说明），挂在 Surface 上只会夹住卡片**内容**，圆角底色仍按父级满宽画出来。
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = RelayElevations.Card,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.sm),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_lan_search),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(LocalAppSpacing.current.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.desktop_nodes_local_id),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        nodeId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AppIconButton(
                    icon = Res.drawable.ic_copy,
                    contentDescription = stringResource(Res.string.desktop_nodes_copy_id),
                    onClick = onCopy,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
