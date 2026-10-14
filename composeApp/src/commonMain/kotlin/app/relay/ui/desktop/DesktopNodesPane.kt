// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import app.relay.app.RelayUiState
import app.relay.app.manualPeerStatuses
import app.relay.core.nowMillis
import app.relay.feature.nodes.NodesScreen
import app.relay.ui.components.AppBanner
import app.relay.ui.components.KindBadge
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.StatusDot
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.LocalCardShape
import app.relay.ui.theme.RelayElevations
import app.relay.ui.theme.accents
import app.relay.generated.resources.Res
import app.relay.generated.resources.common_never
import app.relay.generated.resources.desktop_nodes_copy_id
import app.relay.generated.resources.desktop_nodes_local_id
import app.relay.generated.resources.disc_manual_offline
import app.relay.generated.resources.disc_manual_online
import app.relay.generated.resources.disc_manual_unreachable
import app.relay.generated.resources.node_detail_info_host
import app.relay.generated.resources.node_detail_info_ip
import app.relay.generated.resources.node_detail_info_platform
import app.relay.generated.resources.node_detail_info_recent
import app.relay.generated.resources.node_detail_info_section
import app.relay.generated.resources.node_detail_never_hint
import app.relay.generated.resources.node_detail_recent_just_now
import app.relay.generated.resources.node_detail_remark_hint
import app.relay.generated.resources.node_detail_remark_placeholder
import app.relay.generated.resources.node_detail_remark_save
import app.relay.generated.resources.node_detail_remark_section
import app.relay.generated.resources.node_detail_ungrouped
import app.relay.generated.resources.nodes_lan_empty
import app.relay.generated.resources.nodes_menu_message
import app.relay.generated.resources.nodes_subtitle_short
import app.relay.generated.resources.ic_chat_bubble
import app.relay.generated.resources.ic_copy
import app.relay.generated.resources.ic_lan_search
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
                    onRemovePeer = controller::removeManualPeer,
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
                        modifier = Modifier
                            .width(DesktopMetrics.SIDEBAR_PX.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDividerLine()
                    NodeDetailPane(
                        entry = selected,
                        remark = selected?.nodeId?.let { id -> state.settings.peerRemarks[id].orEmpty() }.orEmpty(),
                        now = now,
                        onSetPeerRemark = onSetPeerRemark,
                        onOpenChat = onOpenChat,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 数据

/** 一行节点在列表与详情里的三种状态（原型 `nodeRow` 的 `online` / `never` 分支）。 */
private enum class NodePresence { Online, Offline, Never }

/**
 * 左栏与详情共用的**一行**。
 *
 * [nodeId] 为 null 只有一种来源：「保存了但从来没接通过」的手动地址
 * （`manualPeerStatuses` 里 `unreachable` 的那些，它们在节点表 `state.peers` 里没有对应行）。
 */
private data class NodeEntry(
    val key: String,
    val nodeId: String?,
    val name: String,
    val isAgent: Boolean,
    val presence: NodePresence,
    /** 列表第二行与详情「IP 地址」显示的值：`ip` 优先，空则回落 `host`。 */
    val address: String,
    val port: Int,
    /** 详情「主机」行（原型是 `hostName`，与列表用的地址不是一回事）。 */
    val hostName: String,
    val platform: String,
    val lastSeenAt: Long,
)

/**
 * 节点表 + 「只有地址、从没接通」的手动条目，合成一份列表。
 *
 * 顺服原型 `sideNodes` 的口径：**一条列表**，不分组。已知节点保持 `state.peers` 的顺序
 * （它在别处已经按「在线优先 / 最近活跃」排过），死地址追加在末尾 —— 原型的 `allNodes()`
 * 也是这个顺序。
 */
private fun nodeEntries(state: RelayUiState): List<NodeEntry> {
    val known = state.peers.map { peer ->
        NodeEntry(
            key = "peer:${peer.nodeId}",
            nodeId = peer.nodeId,
            name = peer.displayName,
            isAgent = peer.isAgent,
            presence = if (peer.online) NodePresence.Online else NodePresence.Offline,
            address = peer.ip.ifBlank { peer.host },
            port = peer.udpPort,
            hostName = peer.host,
            platform = peer.platform,
            lastSeenAt = peer.lastSeenAt,
        )
    }
    val never = manualPeerStatuses(state.settings.manualPeers, state.peers)
        .filter { it.nodeId == null }
        .map { status ->
            NodeEntry(
                key = "manual:${status.entry.host}:${status.entry.port}",
                nodeId = null,
                name = status.entry.name?.takeIf { it.isNotBlank() }
                    ?: "${status.entry.host}:${status.entry.port}",
                isAgent = false,
                presence = NodePresence.Never,
                address = status.entry.host,
                port = status.entry.port,
                hostName = status.entry.host,
                platform = "",
                lastSeenAt = 0L,
            )
        }
    return known + never
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
    entries: List<NodeEntry>,
    selectedKey: String?,
    onlineCount: Int,
    showCount: Boolean,
    onSelect: (NodeEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Column(modifier) {
        if (showCount) NodeCountLine(entries.size, onlineCount)
        LazyColumn(
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
private fun NodeListRow(entry: NodeEntry, selected: Boolean, onClick: () -> Unit) {
    val sp = LocalAppSpacing.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (selected) scheme.surface else Color.Transparent,
        contentColor = scheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = if (selected) BorderStroke(1.dp, scheme.outlineVariant) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            // `.li{padding:7px 9px;min-height:52px}` + 32px 头像 ⇒ 上下各 10px 时正好 52；
            // 横向 9px 取最近的 sp.sm(8)。
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.md10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.md10),
        ) {
            NodeAvatar(
                name = entry.name,
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
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isAgent) KindBadge(isAgent = true)
                }
                Text(
                    text = "${presenceLabel(entry.presence)} · ${entry.address}:${entry.port}",
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
 * `PeerAvatar` 与 `StatusDot` 都没有 `modifier` 形参（`ui/components/Common.kt:140/162`），
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
    entry: NodeEntry?,
    remark: String,
    now: Long,
    onSetPeerRemark: (String, String) -> Unit,
    onOpenChat: (String) -> Unit,
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
    val sp = LocalAppSpacing.current
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sp.lg, vertical = sp.lg14),
        verticalArrangement = Arrangement.spacedBy(sp.lg14),
    ) {
        NodeDetailHeader(entry)
        // 备注卡只在**有 nodeId** 时出现：备注是按 nodeId 存的
        // （`RelaySettings.peerRemarks`），「从来没有回应」的手动地址没有 nodeId 可挂。
        entry.nodeId?.let { nodeId ->
            RemarkCard(
                nodeId = nodeId,
                initial = remark,
                onSave = onSetPeerRemark,
            )
        }
        InfoCard(entry = entry, now = now)
        SendMessageButton(entry = entry, onOpenChat = onOpenChat)
    }
}

/** `.ndhead{gap:14px;padding:6px 2px 14px}` + `.ndmeta .t1{17px/600}` + `.ndtags{gap:6px;margin-top:6px}`。 */
@Composable
private fun NodeDetailHeader(entry: NodeEntry) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.lg14),
    ) {
        NodeAvatar(
            name = entry.name,
            isAgent = entry.isAgent,
            presence = entry.presence,
            size = NodeDetailAvatarSize,
            shape = CircleShape,
            ringed = true,
        )
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.isAgent) KindBadge(isAgent = true)
            }
            Row(
                modifier = Modifier.padding(top = sp.sm6),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                NodeStatePill(entry.presence)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                ) {
                    Text(
                        text = stringResource(Res.string.node_detail_ungrouped),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                    )
                }
            }
        }
    }
}

/**
 * 状态药丸（`.pill`，`:306-309`）。
 *
 * 「在线」的配色在原型里是 `color-mix(in srgb, var(--online) 18%, transparent)`，
 * 在 Compose 里就是同一个颜色的 18% 透明度；「没有回应」用 M3 的 `errorContainer`
 * （原型 `--error-c` / `--on-error-c` 与 M3 语义槽同值）。
 */
@Composable
private fun NodeStatePill(presence: NodePresence) {
    val sp = LocalAppSpacing.current
    val scheme = MaterialTheme.colorScheme
    val container = when (presence) {
        NodePresence.Online -> MaterialTheme.accents.online.copy(alpha = 0.18f)
        NodePresence.Offline -> scheme.surfaceVariant
        NodePresence.Never -> scheme.errorContainer
    }
    val content = when (presence) {
        NodePresence.Online -> MaterialTheme.accents.online
        NodePresence.Offline -> scheme.onSurfaceVariant
        NodePresence.Never -> scheme.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Text(
            text = presenceLabel(presence),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
        )
    }
}

/** 备注卡：`.card > .padc{padding:8px 13px 13px}`（`.win` 的 `--padc`，`:600`）+ `.grphead.sm`（`:311`）。 */
@Composable
private fun RemarkCard(nodeId: String, initial: String, onSave: (String, String) -> Unit) {
    val sp = LocalAppSpacing.current
    var text by remember(nodeId, initial) { mutableStateOf(initial) }
    DesktopDetailCard {
        CardGroupHeader(stringResource(Res.string.node_detail_remark_section))
        Text(
            text = stringResource(Res.string.node_detail_remark_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = sp.sm),
        )
        OutlinedTextField(
            value = text,
            // 原型 `maxlength` 是 32（`RelaySettings.LIMIT_REMARK`）；输入框自己拦一道，
            // 免得把超出长度的内容送到控制层再被悄悄截断 —— 那样用户看不到自己被截了。
            onValueChange = { next -> if (next.length <= RemarkLimit) text = next },
            placeholder = { Text(stringResource(Res.string.node_detail_remark_placeholder)) },
            singleLine = false,
            minLines = 2,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onSave(nodeId, text.trim()) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(top = sp.sm),
        ) {
            Text(stringResource(Res.string.node_detail_remark_save))
        }
    }
}

/** 详细信息卡：四行 kv（`.kv{padding:9px 14px;border-bottom:1px solid var(--hair)}`，`:277`）。 */
@Composable
private fun InfoCard(entry: NodeEntry, now: Long) {
    val sp = LocalAppSpacing.current
    DesktopDetailCard {
        CardGroupHeader(stringResource(Res.string.node_detail_info_section))
        DetailRow(
            label = stringResource(Res.string.node_detail_info_recent),
            // 原型 `const ls = p.lastSeen === 0 ? '刚刚' : p.lastSeen;`（`prototype/relay.html:3300`），
            // 它模型里的 `0` 是「现在还在线」；本端 `lastSeenAt` 是时间戳，`0` 是**从来没有过**，
            // 两者语义相反，所以分开判：
            //   · 从没连通过（死地址）→ `common_never`（房内同款回落：`feature/nodes/NodeDetail.kt`
            //     与 `feature/agents/AgentSessionWidgets.kt` 都这么写）—— 写「刚刚」会假装它出现过；
            //   · 在线 → 「刚刚」（原型对 online 的那种 0 语义）；
            //   · 其余有时间的 → 相对时间；时间戳也是 0 的 → `common_never`。
            value = when {
                entry.presence == NodePresence.Never -> stringResource(Res.string.common_never)
                entry.presence == NodePresence.Online -> stringResource(Res.string.node_detail_recent_just_now)
                entry.lastSeenAt > 0L -> relativeTime(entry.lastSeenAt, now)
                else -> stringResource(Res.string.common_never)
            },
        )
        DetailRow(
            label = stringResource(Res.string.node_detail_info_ip),
            value = "${entry.address}:${entry.port}",
            monospace = true,
        )
        DetailRow(
            label = stringResource(Res.string.node_detail_info_host),
            // 空值占位符跟手机那份同款（`feature/nodes/NodeDetail.kt` 的 `"—"`）。
            value = entry.hostName.ifBlank { "—" },
            monospace = true,
        )
        DetailRow(
            label = stringResource(Res.string.node_detail_info_platform),
            value = entry.platform.ifBlank { "—" },
        )
    }
}

/** 整宽「发消息」；「从来没有回应」的地址按原型置灰，并在下面补一行解释。 */
@Composable
private fun SendMessageButton(entry: NodeEntry, onOpenChat: (String) -> Unit) {
    val sp = LocalAppSpacing.current
    val nodeId = entry.nodeId
    Column(Modifier.fillMaxWidth()) {
        Button(
            onClick = { nodeId?.let(onOpenChat) },
            enabled = nodeId != null,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_chat_bubble),
                contentDescription = null,
                modifier = Modifier.size(IconSizes.Label),
            )
            Spacer(Modifier.width(sp.sm6))
            Text(stringResource(Res.string.nodes_menu_message))
        }
        if (nodeId == null) {
            Text(
                text = stringResource(Res.string.node_detail_never_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = sp.sm),
            )
        }
    }
}

/** 详情里的卡：`LocalCardShape`（桌面壳覆盖成 12dp，= `.win{--r-card:12px}`）+ 原型 `.card` 的内边距。 */
@Composable
private fun DesktopDetailCard(content: @Composable () -> Unit) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = LocalCardShape.current,
        shadowElevation = RelayElevations.Card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = sp.lg14,
                end = sp.lg14,
                top = sp.sm,
                bottom = sp.lg14,
            ),
        ) {
            content()
        }
    }
}

/** 卡内小标题（`.grphead.sm`，`:311`：`padding:10px 0 4px;font-size:12px;opacity:.92`）。 */
@Composable
private fun CardGroupHeader(text: String) {
    val sp = LocalAppSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = sp.md10, bottom = sp.xs),
    )
}

/**
 * `详细信息` 的一行。
 *
 * 标签的**列宽**在原型里是 `--kv-k`（桌面窗 `104px`，`:600`）；它落在任何 M3 尺寸槽之外，
 * 所以按「保真档」用一个具名常量表达（值来自原型，不是随手挑的）。
 * 行底那根 hairline 用 `outlineVariant`（= 原型 `--hair`，见 `Accents.kt` 的说明）。
 */
@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    val sp = LocalAppSpacing.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = sp.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(DetailKeyWidth),
            )
            Text(
                text = value,
                style = if (monospace) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
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
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painterResource(Res.drawable.ic_copy),
                        contentDescription = stringResource(Res.string.desktop_nodes_copy_id),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
