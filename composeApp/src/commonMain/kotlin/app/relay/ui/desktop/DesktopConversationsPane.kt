// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.relay.app.PeerCandidateTypes
import app.relay.app.model.WorkspaceUi
import app.relay.app.qrCandidatesFor
import app.relay.app.qrPairingPayloadForAddresses
import app.relay.app.RelayUiState
import app.relay.ui.components.GroupCreateDialog
import app.relay.ui.components.WorkspaceGroupCreateDialog
import app.relay.ui.components.WorkspaceQrDialog
import app.relay.generated.resources.chat_no_selection_message
import app.relay.generated.resources.chat_no_selection_title
import app.relay.generated.resources.workspace_group_default_name
import app.relay.core.qr.QrEncoder
import app.relay.feature.chat.ChatScreen
import app.relay.ui.ContentWidths
import app.relay.ui.EmptyState
import app.relay.ui.components.QrCodeView
import app.relay.generated.resources.Res
import app.relay.generated.resources.desktop_conv_empty
import app.relay.generated.resources.nodes_my_qr_desc
import app.relay.generated.resources.pairing_public_ipv6_hint
import app.relay.generated.resources.r2_shell_empty_agent_sub
import app.relay.generated.resources.r2_shell_empty_agent_title
import app.relay.generated.resources.r2_shell_empty_drop_sub
import app.relay.generated.resources.r2_shell_empty_drop_title
import app.relay.generated.resources.r2_shell_empty_footline
import app.relay.generated.resources.r2_shell_empty_footline_tail
import app.relay.generated.resources.r2_shell_empty_footline_warn
import app.relay.generated.resources.r2_shell_empty_lede
import app.relay.generated.resources.r2_shell_empty_lede2
import app.relay.generated.resources.r2_shell_empty_scan_sub
import app.relay.generated.resources.r2_shell_empty_scan_title
import app.relay.generated.resources.r2_shell_empty_title
import app.relay.generated.resources.r2_shell_nosel_hint
import app.relay.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
import app.relay.app.model.ConversationUi
import app.relay.app.model.NodePhase
import app.relay.ui.theme.ShapeRoles
import app.relay.ui.theme.RelayShapes

/**
 * 空态副题的收栏宽度：原型 `.emptyd .lede{max-width:460px}`（`prototype/relay.html:775`），
 * 邻座的 `.entrygrid{max-width:720px}`（`:776`）就是代码里的 `ContentWidths.Pane`。
 * 460 不落在 `AppSpacing` 任何档上（也不是 `ContentWidths` 的取值），所以按「保真档」
 * 用具名常量表达 —— 值取自原型，不是随手挑的；写成字面量会被 token 棘轮记一笔欠债。
 */
internal val EmptyLedeMaxWidth = 460.dp

// ------------------------------------------------------------------ 会话视图

@Composable
public fun ConversationsPane(
    state: RelayUiState,
    controller: DesktopPaneActions,
    sizeClass: DesktopWindowSizeClass,
    sessions: List<ConversationUi>,
    selectedPeerId: String?,
    /** 当前搜索词与清除入口：只透给侧栏空态，见 [ConversationSidebar]。 */
    query: String,
    onClearQuery: () -> Unit,
    onCopyPeerId: (String) -> Unit,
    onOpenInNewWindow: ((String) -> Unit)?,
    onSelect: (String) -> Unit,
    onGoToPairing: () -> Unit,
    now: Long,
    /** 输入框预置草稿，只给截图门禁用（见 [ChatScreen.initialDraft]）。 */
    initialDraft: String = "",
    /**
     * 建群入口。null = 不画这个按钮（预览、或宿主没有这条能力）。
     *
     * 与手机端同一条规矩：按钮长在**会话列表**上，因为群就是列表里的第三种行；
     * 而"没有群"那一节仍要画出来，它是这个按钮唯一的落点。
     */
    onCreateGroup: ((name: String, members: List<String>, description: String) -> String?)? = null,
    /** 冷启动时预置的全局搜索结果，只给截图门禁用（见 [DesktopShell.initialGlobalSearch]）。 */
    initialGlobalSearch: app.relay.app.GlobalSearchResult? = null,
    /**
     * 非空 `query` 交给**整幅宽**的全局搜索页（原型 `screenDSearch`，`prototype/relay.html:4454`）。
     *
     * 生产恒为 true：桌面外壳工具条那个常驻搜索框（`DesktopChrome.kt` 的 `DesktopToolbar`）
     * 就是这条全局搜索的入口。传 false 时退回侧栏自己那套「筛会话」
     * （原型 `screenDNoMatch`，`prototype/relay.html:5171`）：空则画「没有匹配「q」的结果」
     * +「清除搜索」，右栏照旧。**生产没有任何调用方传 false**，它和
     * [initialGlobalSearch] 一样是给截图门禁（以及将来没有全局搜索能力的宿主）留的接缝 ——
     * 不给这条接缝，侧栏那半张 `d-nomatch` 就再也拍不到（`ConversationSidebar` 的
     * 筛选空态分支只在 `onSearchGlobal == null` 时才可达）。
     */
    globalSearchEnabled: Boolean = true,
    /**
     * 首启空态第三张卡「本机已在跑 Agent」的落点。
     *
     * 桌面外壳把它接到「切到工作区视图」（壳里 `openAgents`）；传 null 时这张卡画成
     * 不可点的卡片。与 [onCreateGroup] 同一条规矩：**能力不存在就不画按钮**，
     * 不放假动作。
     * 名字留作 `onOpenAgentConsole`：工作区为平级视图，该回调负责切换视图。
     */
    onOpenAgentConsole: (() -> Unit)? = null,
    selectedWsId: String? = null,
    activeSessionId: String? = null,
    onSelectAgentSession: ((String) -> Unit)? = null,
    /**
     * 进入当前会话那一刻的未读条数（task-2 接线，见 [ChatPane.unreadOnEntry]）。
     *
     * 壳在 `controller.selectConversation` 清未读**之前**采下，再原样透传到这里 ——
     * 这一层只搬运，不做判断。
     */
    unreadOnEntry: Int = 0,
) {
    var creatingGroup by remember { mutableStateOf(false) }
    var creatingWorkspaceGroup by remember { mutableStateOf(false) }
    var showWsQrFor by remember { mutableStateOf<WorkspaceUi?>(null) }
    if (creatingGroup && onCreateGroup != null) {
        GroupCreateDialog(
            peers = state.peers,
            onDismiss = { creatingGroup = false },
            onConfirm = { name, members ->
                creatingGroup = false
                val gid = onCreateGroup(name, members, "")
                if (gid != null) {
                    onSelect(app.relay.core.protocol.ConvId.group(gid))
                }
            },
        )
    }
    if (creatingWorkspaceGroup && onCreateGroup != null) {
        val defaultGroupNameFormat = stringResource(Res.string.workspace_group_default_name, "%s")
        WorkspaceGroupCreateDialog(
            workspaces = state.agentWorkspaces.values.toList(),
            groups = state.settings.groups,
            onDismiss = { creatingWorkspaceGroup = false },
            onOpenChat = { groupId ->
                creatingWorkspaceGroup = false
                onSelect(app.relay.core.protocol.ConvId.group(groupId))
            },
            onShowQr = { ws ->
                showWsQrFor = ws
            },
            onQuickCreate = { ws, _ ->
                creatingWorkspaceGroup = false
                val name = defaultGroupNameFormat.replace("%s", ws.wsTitle ?: ws.wsId)
                val members = state.peers.filter { it.online }.map { it.nodeId }
                val gid = onCreateGroup(name, members, ws.wsPath ?: ws.wsId)
                if (gid != null) {
                    onSelect(app.relay.core.protocol.ConvId.group(gid))
                }
            },
            onCreateNew = { path, _, groupName, _ ->
                creatingWorkspaceGroup = false
                val members = state.peers.filter { it.online }.map { it.nodeId }
                val gid = onCreateGroup(groupName, members, path)
                if (gid != null) {
                    onSelect(app.relay.core.protocol.ConvId.group(gid))
                }
            },
        )
    }
    showWsQrFor?.let { ws ->
        WorkspaceQrDialog(
            wsTitle = ws.wsTitle ?: ws.wsId,
            wsId = ws.wsId,
            wsPath = ws.wsPath,
            host = state.network.localAddresses.firstOrNull()
                ?: state.peers.firstOrNull { it.online }?.let { it.host.ifBlank { it.ip } },
            port = state.network.boundUdpPort.takeIf { it > 0 } ?: state.settings.udpPort,
            // 给多候选能力：有本机地址时直通码也带上全部候选（公网 v6 在跨网时才可能连通）。
            addresses = state.network.localAddresses,
            nodeId = state.selfNodeId,
            // task-86：直通码也必须带 pin，否则手机建不出公网 TLS 目标。
            tlsPin = state.network.selfTlsPin,
            // 带昵称：扫完立刻是真名，不是 node-a1b2c3d4。
            nick = state.settings.nick,
            onDismiss = { showWsQrFor = null },
        )
    }
    val chatPane: @Composable (Modifier, (() -> Unit)?) -> Unit = { paneModifier, back ->
        ChatPane(
            state = state,
            controller = controller,
            selectedPeerId = selectedPeerId,
            sizeClass = sizeClass,
            now = now,
            initialDraft = initialDraft,
            onSelect = onSelect,
            onGoToPairing = onGoToPairing,
            onCopyPeerId = onCopyPeerId,
            onOpenInNewWindow = onOpenInNewWindow,
            onBack = back,
            modifier = paneModifier,
            selectedWsId = selectedWsId,
            activeSessionId = activeSessionId,
            unreadOnEntry = unreadOnEntry,
            onSelectAgentSession = onSelectAgentSession,
        )
    }

    // 详情区就是聊天面板。侧栏、窄档的「列表」返回、整幅宽搜索与首启空态都不受影响。
    // （工作区不再是「详情区的替身」，见 [onOpenAgentConsole] 的 KDoc。）
    val detailPane: @Composable (Modifier, (() -> Unit)?) -> Unit = { paneModifier, back ->
        chatPane(paneModifier, back)
    }

    // 整幅宽全局搜索页（原型 `screenDSearch` / `screenDSearchEmpty`，`prototype/relay.html:4454-4472`）。
    //
    // 位置放在 `when (sizeClass)` **之前**：搜索不是「窄档下的另一种列表」，是**换了一页**。
    // 原型那两屏连 `dwin(...)` 都没调 —— 没有侧栏、没有右栏聊天区，就是一条 topbar 加一整幅
    // detail。旧写法把 `DesktopGlobalSearchPanel` 塞进 `ConversationSidebar` 的槽里，
    // Expanded 下只有 300dp 宽、右边还并排画着聊天区（报告 §14 第 1/2 条）。
    // 三档共用这一支：`.spbox` 与三段列表的宽度本来就跟着窗口走。
    if (globalSearchEnabled && query.isNotBlank()) {
        DesktopGlobalSearchPage(
            query = query,
            sessionCount = sessions.size,
            onClearQuery = onClearQuery,
            onSearch = controller::searchGlobal,
            onSelectPeer = onSelect,
            onSelectMessage = onSelect,
            onCreateGroup = onCreateGroup?.let { { creatingGroup = true } },
            initialResult = initialGlobalSearch,
        )
        return
    }

    // 首启空态：**一个对端都没有**（原型 `screenDEmpty`，`prototype/relay.html:5124`）。
    //
    // 位置在整幅宽搜索之后、`when (sizeClass)` 之前：原型这一屏是
    // `dwin('chat', …, {side:false, view:'chat', connected:false})` —— 没有侧栏、没有右栏，
    // 就是一块两轴居中的引导。原先无论哪一档都只画 `ConversationSidebar` 的
    // `EmptyState`（`desktop_conv_empty` + 「去配对节点」），那是「列表空了」的形态，
    // 不是「这台机器还谁也不认识」的形态（报告 §1）。
    //
    // 判据用 `state.peers`（远端节点表，本机不在其中）而不是 `sessions` 单条：
    // 会话可以为空而节点表非空（刚配对、还没聊过），那时该画的是普通空列表。
    // `NodePhase.Starting` 让位给侧栏那句「正在启动」（见 `ConversationSidebar`）。
    if (sessions.isEmpty() && state.peers.isEmpty() && state.network.phase != NodePhase.Starting) {
        DesktopEmptyOnboarding(
            selfTlsPin = state.network.selfTlsPin,
            nodeId = state.selfNodeId,
            nick = state.settings.nick,
            addresses = state.network.localAddresses,
            port = state.network.boundUdpPort,
            onPair = onGoToPairing,
            onOpenAgentConsole = onOpenAgentConsole,
        )
        return
    }

    when (sizeClass) {
        DesktopWindowSizeClass.Expanded -> Row(Modifier.fillMaxSize()) {
            ConversationSidebar(
                sessions = sessions,
                phase = state.network.phase,
                selectedPeerId = selectedPeerId,
                query = query,
                onClearQuery = onClearQuery,
                onSelect = onSelect,
                onGoToPairing = onGoToPairing,
                now = now,
                modifier = Modifier.width(DesktopMetrics.SIDEBAR_PX.dp),
                onCreateGroup = onCreateGroup?.let { { creatingGroup = true } },
                onCreateWorkspaceGroup = onCreateGroup?.let { { creatingWorkspaceGroup = true } },
                onSearchMessages = controller::searchHistoricalMessages,
                onSearchGlobal = if (globalSearchEnabled) controller::searchGlobal else null,
                initialGlobalSearch = initialGlobalSearch,
            )
            VerticalDividerLine()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                detailPane(Modifier.fillMaxSize(), null)
            }
        }

        // Medium：侧栏收窄到 240px，否则 600px 宽装不下 300px 侧栏。
        // 竖导不在这里 —— 它属于壳，由 DesktopShell 铺在左侧（见 NavigationRail 的说明）。
        DesktopWindowSizeClass.Medium -> Row(Modifier.fillMaxSize()) {
            ConversationSidebar(
                sessions = sessions,
                phase = state.network.phase,
                selectedPeerId = selectedPeerId,
                query = query,
                onClearQuery = onClearQuery,
                onSelect = onSelect,
                onGoToPairing = onGoToPairing,
                now = now,
                modifier = Modifier.width(240.dp),
                onCreateGroup = onCreateGroup?.let { { creatingGroup = true } },
                onCreateWorkspaceGroup = onCreateGroup?.let { { creatingWorkspaceGroup = true } },
                onSearchMessages = controller::searchHistoricalMessages,
                onSearchGlobal = if (globalSearchEnabled) controller::searchGlobal else null,
                initialGlobalSearch = initialGlobalSearch,
            )
            VerticalDividerLine()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                detailPane(Modifier.fillMaxSize(), null)
            }
        }

        // Compact：单栏。列表与详情互斥，靠详情头的「列表」按钮切换；
        // 底部三段导航同样由壳提供。
        DesktopWindowSizeClass.Compact -> {
            // 选中的会话占这一格；没选中就是列表。
            val detail = selectedPeerId != null
            Box(Modifier.fillMaxSize()) {
                if (detail) {
                    detailPane(Modifier.fillMaxSize()) { onSelect("") }
                } else {
                    ConversationSidebar(
                        sessions = sessions,
                        phase = state.network.phase,
                        selectedPeerId = selectedPeerId,
                        query = query,
                        onClearQuery = onClearQuery,
                        onSelect = onSelect,
                        onGoToPairing = onGoToPairing,
                        now = now,
                        modifier = Modifier.fillMaxSize(),
                        onCreateGroup = onCreateGroup?.let { { creatingGroup = true } },
                        onCreateWorkspaceGroup = onCreateGroup?.let { { creatingWorkspaceGroup = true } },
                        onSearchMessages = controller::searchHistoricalMessages,
                        onSearchGlobal = if (globalSearchEnabled) controller::searchGlobal else null,
                initialGlobalSearch = initialGlobalSearch,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 首启空态

/**
 * 「还没有连接到任何设备」整幅宽引导（原型 `screenDEmpty`，`prototype/relay.html:5124-5140`）。
 *
 * 原型自己标注这一屏是**提案**（`:5121-5123`「Code 里没有首启引导…不算既有能力」），
 * 但它是「刚装完、一台设备都没连上」时唯一会出现的画面，值得照做。三处与原型不同，
 * 都与「不画假东西」有关：
 *
 * 1. **二维码是真的**：原型那块 `.qrmock` 是 CSS 画的棋盘格占位（它自己也没有载荷），
 *    这里走 [pairingPayload] + [QrEncoder] 印本机配对码；地址还没就绪时**整块不画**，
 *    与 `MyQrSheet`（`feature/nodes/NodeSheets.kt`）同一取舍 ——
 *    宁可少一块，不印一张扫不出来的码。外径 132dp（116 + 8×2）＝ 原型 `.qrmock` 的尺寸。
 * 2. **第二张卡不可点**：`DesktopShellActions.onImportFiles` 收的是 `peerId`
 *    （[DesktopShellActions.onImportFiles]），一个对端都没有时没有收件人；「拖文件进窗口」是窗口级手势，
 *    不是这个 Composable 能接的能力。所以它画成卡片、不挂 onClick，登记为偏差。
 * 3. 第一/第三张卡是真动作：[onPair] 去「节点」视图（那里有扫码入口），
 *    [onOpenAgentConsole] 打开本机 Agent 控制台。
 *
 * 版式照原型逐值：外框 `padding:34px 40px`（→ `xxl` 32，snap 2dp）、内容两轴居中、
 * 标题 16px/700、lede 12px `--on-2` 且 `max-width:460px`（行高 1.65 → 20sp）、
 * 三卡 `gap:12px` 且 `max-width:720px`（= [ContentWidths.Pane]，出处就是这条 CSS）、
 * 卡内 `padding:14px` / 圆角 16px / 1px 发丝线 / `--shadow`。脚注 11px、
 * 警示句用 `--on-queued-c` —— 本仓精确映射是 `colorScheme.onSecondaryContainer`
 * （`ui/theme/Color.kt:56-57` 亮色 `#5C3D1E`、`:104-105` 暗色 `#F5E6D3`，与原型的
 * `:46`/`:78` 两个值逐字相同）。
 */
@Composable
private fun DesktopEmptyOnboarding(
    nodeId: String,
    nick: String?,
    addresses: List<String>,
    port: Int,
    /** 本机 TLS pin：必须印进码里（task-86），否则手机建不出公网 TLS 目标。 */
    selfTlsPin: String?,
    onPair: () -> Unit,
    onOpenAgentConsole: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    // 二维码载荷：按前缀折叠 + 按类型轮转 + 裁到二维码容量内（见 `qrPairingPayloadForAddresses`）。
    // 只印一个 IPv4 时，手机一换网络就只剩"手动填地址"；全量塞进去又超容量 ⇒ 编码器直接抛异常。
    val payload = qrPairingPayloadForAddresses(
        nodeId = nodeId,
        addresses = addresses,
        port = port,
        tlsPin = selfTlsPin,
        nick = nick,
    )
    // 有公网 v6 才提示"要放行入站"：没有公网地址时提示它只会变成一句无用的噪声。
    // 判据取**二维码里真的带着**的那一份：预算必须与上面那次生成**同一组参数**（含 pin 与昵称），
    // 否则裁剪结果会不一样，提示就会指着一个已经被裁掉的候选说"要放行它"。
    val hasPublicV6 = qrCandidatesFor(addresses, port, nodeId, tlsPin = selfTlsPin, nick = nick)
        .any { it.type == PeerCandidateTypes.PUBLIC_V6 }
    // 编码器对超长内容抛异常（它拒绝截断），地址为空时 payload 也是 null：两种情况都不画码。
    val code = payload?.let { runCatching { QrEncoder.encode(it) }.getOrNull() }
    val lede = stringResource(Res.string.r2_shell_empty_lede) + "\n" +
        stringResource(Res.string.r2_shell_empty_lede2)
    val footline = stringResource(Res.string.r2_shell_empty_footline)
    val footlineWarn = stringResource(Res.string.r2_shell_empty_footline_warn)
    val footlineTail = stringResource(Res.string.r2_shell_empty_footline_tail)
    val warnColor = MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = sp.xxl, vertical = sp.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (code != null) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(bottom = sp.md),
                ) {
                    QrCodeView(
                        code = code,
                        contentDescription = stringResource(Res.string.nodes_my_qr_desc),
                        modifier = Modifier.padding(sp.sm).size(116.dp),
                    )
                }
            }
            // 如实说明：码里有公网地址**不等于**能连上 —— 主机所在网络必须允许入站。
            // 不写这一句，用户会以为"扫了就该通"，然后在真机上得到一次超时。
            if (code != null && hasPublicV6) {
                Text(
                    text = stringResource(Res.string.pairing_public_ipv6_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = sp.xs).widthIn(max = EmptyLedeMaxWidth),
                )
            }
            Text(
                text = stringResource(Res.string.r2_shell_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = lede,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = sp.sm).widthIn(max = EmptyLedeMaxWidth),
            )
            Row(
                modifier = Modifier
                    .padding(top = sp.lg)
                    .widthIn(max = ContentWidths.Pane)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                OnboardingEntryCard(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = stringResource(Res.string.r2_shell_empty_scan_title),
                    subtitle = stringResource(Res.string.r2_shell_empty_scan_sub),
                    onClick = onPair,
                    modifier = Modifier.weight(1f),
                )
                OnboardingEntryCard(
                    icon = Icons.Outlined.Download,
                    title = stringResource(Res.string.r2_shell_empty_drop_title),
                    subtitle = stringResource(Res.string.r2_shell_empty_drop_sub),
                    // 见本函数 KDoc 第 2 条：零对端时 onImportFiles 没有收件人。
                    onClick = null,
                    modifier = Modifier.weight(1f),
                )
                OnboardingEntryCard(
                    icon = Icons.Outlined.SmartToy,
                    title = stringResource(Res.string.r2_shell_empty_agent_title),
                    subtitle = stringResource(Res.string.r2_shell_empty_agent_sub),
                    onClick = onOpenAgentConsole,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = buildAnnotatedString {
                    append(footline)
                    append('\n')
                    withStyle(SpanStyle(color = warnColor)) { append(footlineWarn) }
                    append(footlineTail)
                },
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = sp.lg).widthIn(max = EmptyLedeMaxWidth),
            )
        }
    }
}

/**
 * 首启引导里的一格 `.entry`（原型 `prototype/relay.html:786-793`）。
 *
 * [onClick] 为 null 时画成**不可点**的卡片：不挂 clickable，也不出现悬停描边 ——
 * 原型 `.entry:hover{border-color:var(--primary)}` 只对真能点的卡片成立。
 * 图标是纯装饰（`contentDescription = null`），语义由标题那条文字承担。
 */
@Composable
private fun OnboardingEntryCard(

    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        // 原型 `.entry{border-radius:var(--r-card)}`：桌面窗里 `--r-card` 是 **12px**
        // （`.win{--r-card:12px}`，手机/平板 `.app` 才是 16px）—— 照 [LocalCardShape] 走，
        // 桌面壳把它覆盖成 `RelayShapes.medium`(12dp)。写死 16dp 会让桌面比原型圆 4dp。
        shape = LocalCardShape.current,
        border = BorderStroke(
            1.dp,
            if (hovered && onClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        shadowElevation = 2.dp,
        modifier = modifier
            .hoverable(interaction, enabled = onClick != null)
            .then(if (onClick == null) Modifier else Modifier.clickable { onClick() }),
    ) {
        Column(Modifier.padding(sp.lg14)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = ShapeRoles.ListCard,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(19.dp),
                )
            }
            // 原型 `.entry{gap:9px}` + `.entry p{margin-top:-3px}` ⇒ 图标→标题 9、标题→副题 6。
            Spacer(Modifier.height(sp.sm))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(sp.sm6))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 没选中会话时的右栏空态（原型 `screenDNoSel`，`prototype/relay.html:5145-5151`）。
 *
 * 为什么不在 `ChatScreen` 里边改：那条空态是手机与桌面**共用**的
 * （`feature/chat/ChatScreen.kt` 中针对未选中会话的 `EmptyState(..., modifier.fillMaxSize())`），
 * 而共用的 `EmptyState`→`AppStates.kt:87 MessageState` 是 `fillMaxWidth().padding(xl)`，
 * **顶对齐**；原型的 `.emptyd`（`prototype/relay.html:772-773`）是
 * `flex:1` + `align-items:center` + `justify-content:center` —— **两轴居中**。
 * 与第一轮 `DesktopFilesPane` 的空态同一做法：桌面壳在自己这一层画居中的那张，
 * 不去动服务手机端的共享组件（`fillMaxSize()` 只给尺寸，居中是父级的事）。
 *
 * 版式逐值（原型 → 代码）：
 * - `.emptyd{padding:34px 40px}` → 外层 `padding(xl)`(24)（档位表里最接近的一档）。
 * - `.big{font-size:16px;font-weight:700}` → `titleMedium`(16sp) + `Bold`。
 * - `.lede{font-size:12px;margin-top:7px;line-height:1.65;max-width:460px}` →
 *   `bodySmall`(12sp) + `Spacer(sm6)`(6dp，差 1dp) + `lineHeight 20.sp`(原型 19.8) + `widthIn(max 460.dp)`。
 * - 第三行 `.hint{font-size:11px;color:var(--on-2);margin-top:5px;line-height:1.5}`，本屏被内联
 *   覆盖成 `margin-top:14px` → `labelSmall`(11sp) + `onSurfaceVariant` + `Spacer(lg14)`(14dp)。
 *   文案 `r2_shell_nosel_hint` 只在原型里有（原型注释自陈是「提案」），
 *   它说的拖放能力桌面上确实存在（`desktopApp/.../DesktopHosting.kt` 装了 AWT `DropTarget`）。
 */
@Composable
internal fun DesktopNoSelectionEmpty(modifier: Modifier = Modifier) {
    val sp = LocalAppSpacing.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = EmptyLedeMaxWidth)
                .padding(horizontal = sp.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.chat_no_selection_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(sp.sm6))
            Text(
                text = stringResource(Res.string.chat_no_selection_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(sp.lg14))
            Text(
                text = stringResource(Res.string.r2_shell_nosel_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}