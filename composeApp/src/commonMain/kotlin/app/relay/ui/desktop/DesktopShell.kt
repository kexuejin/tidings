// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.relay.ui.theme.LocalAppSpacing

import app.relay.app.GlobalSearchResult
import app.relay.app.RelayUiState
import app.relay.core.nowMillis
import app.relay.feature.agents.AgentConsoleScreen
import app.relay.feature.chat.ChatScreen
import app.relay.ui.components.AppBanner
import app.relay.ui.components.relativeTime
import app.relay.ui.theme.LocalCardShape
import app.relay.ui.theme.RelayShapes
import app.relay.generated.resources.Res
import app.relay.generated.resources.desktop_action_quit
import app.relay.generated.resources.desktop_menu_agents
import app.relay.generated.resources.desktop_menu_copy_node_id
import app.relay.generated.resources.desktop_menu_diagnostics
import app.relay.generated.resources.desktop_menu_refresh_nodes
import app.relay.generated.resources.desktop_overflow_open_downloads
import app.relay.generated.resources.desktop_overflow_theme_dark
import app.relay.generated.resources.desktop_overflow_theme_light
import app.relay.generated.resources.desktop_view_agents
import app.relay.generated.resources.desktop_view_cabinet
import app.relay.generated.resources.desktop_view_conversations
import app.relay.generated.resources.desktop_view_files
import app.relay.generated.resources.desktop_view_nodes
import app.relay.generated.resources.screen_settings
import app.relay.generated.resources.ic_close
import app.relay.generated.resources.ic_copy
import app.relay.generated.resources.ic_folder
import app.relay.generated.resources.ic_info
import app.relay.generated.resources.ic_moon
import app.relay.generated.resources.ic_refresh
import app.relay.generated.resources.ic_settings
import app.relay.generated.resources.ic_terminal
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 桌面五视图。会话 / 文件 / 文件柜 / 节点 / 工作区平权 —— 不是"主视图 + 设置页"的关系，
 * 因为桌面上它们使用频率接近（收发消息、看落盘文件、翻共享文件柜、盯节点表、盯工作区），
 * 把任何一个塞进二级菜单都会让另外几个变得难找。
 *
 * **顺序即导航顺序**（[NavigationRail] / [CompactNavigationBar] 都遍历 [entries]），
 * 与原型 DVIEWS 的 chat / files / cabinet / nodes / agents 对齐。
 * 工作区与原型 DVIEWS 的 chat / files / cabinet / nodes / agents 五项对齐。
 */
public enum class DesktopView(val label: StringResource) {
    Conversations(Res.string.desktop_view_conversations),
    Files(Res.string.desktop_view_files),
    Cabinet(Res.string.desktop_view_cabinet),
    Nodes(Res.string.desktop_view_nodes),
    Agents(Res.string.desktop_view_agents),
}

/**
 * 工具栏右侧动作。全部由 Main 注入，shell 自己不碰副作用。
 *
 * 四个**桌面专有**动作是 `null` 表示"这台设备根本没有这个能力"，此时对应的
 * 菜单项**不画**（而不是画一个点了没反应的按钮）：
 * [onExportDiagnostics] / [onOpenDownloadsFolder] / [onOpenPeerWindow] / [onQuit]。
 * 平板复用这个壳但既没有诊断包导出、没有系统下载目录、没有多窗口、也没有退出应用，
 * 所以它们必须是可缺席的 —— 这正是"占了位却什么都不做"那类缺陷的解药。
 */
public class DesktopShellActions(
    val onOpenSettings: () -> Unit = {},
    val onToggleTheme: () -> Unit = {},
    val onExportDiagnostics: (() -> Unit)? = null,
    val onOpenDownloadsFolder: (() -> Unit)? = null,
    val onRefresh: () -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onImportFiles: (String) -> Unit = {},
    val onCopyPairingLink: (String) -> Unit = {},
    /**
     * 复制本机节点 ID。
     *
     * 存在的理由：节点 ID 是**对方添加你**时要用的东西，而它在界面上被截断显示
     * （标题栏只留 8 位），用户没法自己抄全。给一个复制入口，别让人去翻诊断包。
     * 注意这里只复制节点 ID：`:core` 里没有公钥概念，`fingerprintOf` 是资料指纹
     * （用于推进 profileRev）而不是加密身份指纹，把它当「公钥指纹」展示会误导人。
     */
    val onCopyNodeId: (String) -> Unit = {},
    /** 把一个会话拆到独立窗口。移动端没有多窗口，缺席时入口不画。 */
    val onOpenPeerWindow: ((String) -> Unit)? = null,
    /** 退出应用。移动端由系统回收，缺席时入口不画。 */
    val onQuit: (() -> Unit)? = null,
    /**
     * 选中的会话对端变化时回调一次。
     *
     * 存在的理由：拖放接收、托盘跳转这些**窗口之外**的入口也需要知道
     * 「现在打开的是谁」。选中态必须留在 shell 里（切视图不该把会话选择丢掉），
     * 所以这里往外**报**一次，而不是把选中态搬到窗口层再往回灌 ——
     * 那会立刻变成两个真相来源。
     */
    val onSelectionChange: (String?) -> Unit = {},
)

/**
 * 桌面壳主体。
 *
 * 结构自上而下固定四段：菜单栏 26px / 标题栏 40px / 工具栏 46px / 内容 / 状态栏 26px，
 * 总计 [DesktopMetrics.CHROME_PX] = 138px。这条预算一开始就写死了：
 * 原型第一版把侧栏 300px 也算进高度，结果输入框被挤出可视区。
 */
@Composable
public fun DesktopShell(
    state: RelayUiState,
    controller: DesktopPaneActions,
    /**
     * 窗口的**逻辑**尺寸（dp），由窗口层用 [rememberDesktopWindowSizeDp] 取。
     *
     * 调用方不要传 `window.width`：那不是 Compose 状态，只在组合时读一次，
     * 整壳会被永久钉在启动那一刻的档位（见 [rememberDesktopWindowSizeDp] 的说明）。
     */
    windowWidthDp: Int,
    windowHeightDp: Int,
    darkTheme: Boolean,
    controlPlanePort: Int = 0,
    /**
     * 画哪一套外壳。
     *
     * 平板复用同一个壳（见 [DesktopChromeMode]），传 [DesktopChromeMode.Tablet]：
     * 菜单栏/标题栏/状态栏是**桌面 chrome**，平板不画。它们承载的动作里，
     * 「没有桌面 chrome 也说得通」的那几条搬进工具栏溢出菜单（设置、切主题、
     * Agent 控制台、复制本机 ID、刷新节点）；桌面宿主独有的（诊断包、打开下载目录、
     * 退出）在平板上本来就是 `null`，于是不画 —— 平板没有"退出应用"这回事，
     * 系统自己管，硬塞一个入口才是编出来的功能。**不是**"动作一条不落地搬过去"。
     *
     * 只影响 chrome，**不**影响档位判定本身的内容部分 —— 断点仍按窗口宽度算。
     */
    chromeMode: DesktopChromeMode = DesktopChromeMode.Desktop,
    /**
     * 外部入口（`relay://` 深链）要求切到「节点」视图的次数。
     *
     * 视图路由归 shell 独占（见 [DesktopShellActions.onNewConversation] 的说明），
     * 所以窗口层只报「被请求了几次」，由 shell 自己决定怎么切。
     */
    externalNodesRequest: Int = 0,
    /**
     * 外部入口（托盘菜单「收到的文件」）要求切到「文件」视图的次数。
     *
     * 与 [externalNodesRequest] 同一套约定：计数器而不是 Bool，第二次请求才会再触发。
     */
    externalFilesRequest: Int = 0,
    /**
     * 打开时落在哪个视图。
     *
     * 主窗和会话独立窗口都是冷启动在 [DesktopView.Conversations]，所以默认值不变；
     * 截图预览需要直接落到「文件」「节点」那一屏，而外部请求计数器走的是
     * [LaunchedEffect] —— 静态帧是在副作用之前抓的，等不到切换生效，所以给它一个
     * 冷启动初值。深链/托盘仍然用计数器，两条通道互不影响。
     */
    initialView: DesktopView = DesktopView.Conversations,
    /**
     * 打开时预先选中的会话。
     *
     * 只有 D10 的**会话独立窗口**会传它：那种窗口存在的唯一目的就是显示这一个会话，
     * 让它先落在空白的会话列表上，用户还得自己再点一次。主窗传 null（保持冷启动空选）。
     *
     * 只用于**初始值**：窗口内的后续选择仍归 shell 自己，和主窗各改各的。
     */
    initialPeerId: String? = null,
    /**
     * 打开时输入框里预置的草稿。
     *
     * 与 [initialView] 同一条理由：草稿是屏内状态（`ChatScreen` 用 `remember(peerId)` 持有），
     * 空草稿时输入栏那一整行状态不画 —— 字节数、走 UDP 还是 TCP、超限报错三支全靠草稿触发，
     * 静态帧抓不到，于是那三支就是结构上录不到的死角。
     * 生产调用方一律用默认值（空）；只有截图预览传非空串。
     */
    initialDraft: String = "",
    /**
     * 冷启动时预置的搜索词。
     *
     * 与 [initialDraft] 同一条理由：`query` 是壳内状态，而「搜了但没命中」那条空态
     * 只有在 query 非空时才画得出来 —— 静态帧没有输入事件，不预置就永远录不到那一支，
     * 于是「没有会话」和「搜索无结果」这两句在参考图里长得一模一样。
     * 生产调用方一律用默认值（空）；只有截图预览传非空串。
     */
    initialQuery: String = "",
    /**
     * 冷启动时预置选中的传输（文件视图右侧详情）。
     *
     * 还是 [initialDraft] 那条理由：选中项是 `FilesPane` 内部状态，静态帧点不了行，
     * 不预置就永远录不到详情面板 —— 「已完成但 `savedPaths` 为空」这一支的
     * 「还没落盘」文案因此一直没有图证明。生产调用方一律用默认值（不选中）；
     * 只有截图预览传一个 `transferId`。
     */
    initialTransferId: String? = null,
    /**
     * 冷启动时预置的全局搜索结果。
     *
     * 全局搜索面板有四支状态，`Success` 那一支（三段分组：联系人 / 聊天记录 / 文件）
     * 一直是零门禁 —— 而且**加一个能返回非空结果的 controller 也拍不到**：面板的
     * `state` 初值是 `Loading`，唯一能推到 `Success` 的是 `LaunchedEffect(query)` 的
     * 协程体，而截图渲染器根本不执行它。
     *
     * 所以这一支必须像 [initialDraft] 一样，把结果放进**第一帧就能算出来**的地方。
     * 生产调用方一律用默认值（null，走正常的 `Loading → …` 流程）；
     * 只有截图预览传一个非空结果。
     */
    initialGlobalSearch: GlobalSearchResult? = null,
    /**
     * 相对时间的基准时刻（毫秒）。
     *
     * 壳里所有 `relativeTime` 都以它为准：标题栏的「最后在线 …」和列表里的时间戳。
     * 生产传 `nowMillis()`（默认值）；截图预览必须传 [PreviewState.PREVIEW_NOW]，
     * 否则参考图里的「3 分钟前」每天都在变，参考图当天就作废。
     */
    now: Long = nowMillis(),
    /**
     * 要求把某个会话摆到用户面前的**一次性**请求（协议 §8 的戳一碰"定位到该会话"）。
     *
     * 与 [initialPeerId] 的差别是"之后还能再变"：选中项归壳独占（见
     * [DesktopShellActions.onSelectionChange]），窗口层无法直接改，所以只能这样报一次请求。
     * 传 `null` 表示没有请求（桌面宿主与截图预览都不传）。
     */
    focusPeerId: String? = null,
    /**
     * 把工具栏的溢出面板**就地画出来**（截图接缝，生产恒 `false`）。
     *
     * 平板宿主没有菜单栏，窗口级动作全在那颗「⋯」后面的 [DropdownMenu] 里，
     * 而 `DropdownMenu` 走 `Popup` —— Layoutlib 不组合它的子内容，
     * 于是 `d-home-more` 这一屏（它的**全部内容**就是这块面板）一直没有图。
     *
     * 传 `true` 时壳在内容之上叠一块 [ToolbarOverflowPanel]（与菜单同一份 `items`）。
     * 生产调用方一律用默认值；只有截图预览传 `true`。
     */
    overflowPanelPreview: Boolean = false,
    actions: DesktopShellActions,
    modifier: Modifier = Modifier,
) {
    val sizeClass = desktopWindowSizeClass(windowWidthDp, windowHeightDp, chromeMode)
    var view by remember { mutableStateOf(initialView) }

    // 深链进来时把用户送到「节点」视图：新加的节点就在那里，留在会话列表会让人
    // 以为链接没生效。
    LaunchedEffect(externalNodesRequest) {
        if (externalNodesRequest > 0) view = DesktopView.Nodes
    }
    // 托盘菜单「收到的文件」同理：只报请求，路由仍归 shell。
    LaunchedEffect(externalFilesRequest) {
        if (externalFilesRequest > 0) view = DesktopView.Files
    }
    var selectedPeerId by remember { mutableStateOf(initialPeerId) }
    var query by remember { mutableStateOf(initialQuery) }
    // 窄档搜索面板的展开态由壳持有，工具栏只负责呈现这个开关。
    // 面板收起后 query 仍保留：否则用户点一下「关闭」就把正在用的筛选无声清空了。
    var searching by remember { mutableStateOf(false) }
    // 工作区是**第五个平级视图**，统一通过切换到 [DesktopView.Agents] 呈现。
    //
    // 顺带删掉了 `DesktopAgentPane`：它存在的唯一理由就是给那个浮层做外壳
    // （自带标题行 + 关闭按钮）。视图切换器已经表达了"离开这一屏"，浮层那套
    // 开关/关闭按钮在第五视图里没有对应物。
    val openAgents: () -> Unit = { view = DesktopView.Agents }

    // 往窗口层报当前会话，供拖放接收与托盘跳转使用。见 [DesktopShellActions.onSelectionChange]。
    // 同一条变化线也把"正看着谁"推给控制器：桌面壳不走 `nav`，控制器自己看不见选中项，
    // 不推的话停在一个会话上收消息会留下一个不消的未读（手机/平板是同一条规矩）。
    LaunchedEffect(selectedPeerId) {
        actions.onSelectionChange(selectedPeerId)
        controller.setVisibleConversation(selectedPeerId)
    }

    // 戳一碰的"定位到该会话"：切到会话视图并选中它。请求由外壳消费一次就清掉
    // （App 层调 `consumeFocusRequest`），所以这里只认非空的那一帧。
    LaunchedEffect(focusPeerId) {
        val target = focusPeerId ?: return@LaunchedEffect
        view = DesktopView.Conversations
        selectedPeerId = target
    }

    val sessions = remember(state.conversations, query) {
        val list = state.conversations
        if (query.isBlank()) list else list.filter {
            it.title.contains(query, ignoreCase = true) || it.preview?.contains(query, ignoreCase = true) == true
        }
    }

    // 快捷键挂在根 Column 上。Compose 的键事件沿焦点路径分发：preview 阶段从根往下走，
    // 所以根先于输入框看到按键。必须 `.focusable()` —— 不在焦点路径上的 Column 收不到
    // 预览事件；但刻意**不** requestFocus：父级的 LaunchedEffect 比子级后跑，抢焦点会把
    // 输入框的启动自动聚焦顶掉，窗口一打开就打不了字。
    fun handleShortcut(shortcut: DesktopShortcut): Boolean = when (shortcut) {
        DesktopShortcut.NewConversation -> {
            view = DesktopView.Nodes
            actions.onNewConversation()
            true
        }

        // 没选中会话就没法发文件：与其静默吞掉按键，不如让它继续往下传。
        DesktopShortcut.ImportFiles -> {
            val peer = selectedPeerId
            if (peer == null) false else { actions.onImportFiles(peer); true }
        }

        // ⌘⇧E：工作区的**第二入口**（第一入口是 View 菜单的 ⌘5）。
        // 原型工具栏/编辑菜单里一直都写作「工作区」，所以这里也切视图而不是开浮层。
        DesktopShortcut.OpenAgents -> {
            openAgents()
            true
        }

        // 复制的是节点 ID，不是加密指纹：`:core` 里没有公钥概念，
        // `agentNode.fingerprintOf` 算的是资料指纹（推 profileRev），
        // 标成「指纹」会让人以为它验证了密钥。
        DesktopShortcut.CopyNodeId -> {
            actions.onCopyNodeId(state.selfNodeId)
            true
        }

        DesktopShortcut.ToggleTheme -> { actions.onToggleTheme(); true }
        DesktopShortcut.RefreshNodes -> { actions.onRefresh(); true }
        DesktopShortcut.OpenSettings -> { actions.onOpenSettings(); true }
        DesktopShortcut.Quit -> { actions.onQuit?.invoke(); true }
        DesktopShortcut.ExportDiagnostics -> { actions.onExportDiagnostics?.invoke(); true }
        DesktopShortcut.ViewConversations -> { view = DesktopView.Conversations; true }
        DesktopShortcut.ViewFiles -> { view = DesktopView.Files; true }
        DesktopShortcut.ViewCabinet -> { view = DesktopView.Cabinet; true }
        DesktopShortcut.ViewNodes -> { view = DesktopView.Nodes; true }
        DesktopShortcut.ViewAgents -> { view = DesktopView.Agents; true }
        DesktopShortcut.ScreenCapture -> {
            controller.triggerScreenCapture(selectedPeerId)
            true
        }
    }

    // 卡片圆角随**外壳**切换，不随调用点：原型 `--r-card` 在 `.app`（手机/平板）是 16px
    // （`prototype/relay.html:58`），在 `.win`（桌面窗）覆盖成 12px（`:584`）。
    // 桌面 chrome 档 = 桌面窗 ⇒ medium(12)；平板档复用同一个壳但仍是 `.app` ⇒ large(16)。
    CompositionLocalProvider(
        LocalCardShape provides
            if (chromeMode.showsDesktopChrome) RelayShapes.medium else RelayShapes.large,
    ) {
    // 截图接缝用的覆盖层：生产恒不画（`overflowPanelPreview` 默认 false），
    // 所以这里只是给内容区加一层「可以叠东西」的父级，不改布局。
    Box {
        // 溢出菜单条目在这里**算一次**，两条路共用同一份列表：生产的 DropdownMenu
        // 与截图接缝的 `ToolbarOverflowPanel`（`overflowPanelPreview`）。
        // 分隔线照原型 `.thr`（`prototype/relay.html:2625-2635`）：
        // 设置/主题/工作区 ｜ 复制本机 ID/诊断包/刷新节点/打开下载目录 ｜ 退出。
        val overflowItems: List<DesktopToolbarOverflowItem> = if (chromeMode.showsDesktopChrome) {
            emptyList()
        } else {
            // 桌面专属能力（诊断包 / 打开下载目录 / 退出）在平板上是 null：
            // 少一个入口不等于功能换了位置，所以宁可不画，也不留「点了没反应」。
            listOfNotNull(
                DesktopToolbarOverflowItem(
                    label = stringResource(Res.string.screen_settings),
                    onClick = actions.onOpenSettings,
                    icon = painterResource(Res.drawable.ic_settings),
                ),
                DesktopToolbarOverflowItem(
                    if (darkTheme) stringResource(Res.string.desktop_overflow_theme_light) else stringResource(Res.string.desktop_overflow_theme_dark),
                    actions.onToggleTheme,
                    icon = painterResource(Res.drawable.ic_moon),
                ),
                // 菜单栏上这两条在桌面有第二条入口：⌘ 快捷键。平板没有
                // 物理键盘，快捷键那条路等于不存在，所以必须在这里补回来。
                DesktopToolbarOverflowItem(
                    label = stringResource(Res.string.desktop_menu_agents),
                    onClick = { openAgents() },
                    icon = painterResource(Res.drawable.ic_terminal),
                ),
                DesktopToolbarOverflowItem(
                    label = stringResource(Res.string.desktop_menu_copy_node_id),
                    onClick = { actions.onCopyNodeId(state.selfNodeId) },
                    dividerBefore = true,
                    icon = painterResource(Res.drawable.ic_copy),
                ),
                actions.onExportDiagnostics?.let {
                    DesktopToolbarOverflowItem(stringResource(Res.string.desktop_menu_diagnostics), it, icon = painterResource(Res.drawable.ic_info))
                },
                DesktopToolbarOverflowItem(
                    label = stringResource(Res.string.desktop_menu_refresh_nodes),
                    onClick = actions.onRefresh,
                    icon = painterResource(Res.drawable.ic_refresh),
                ),
                actions.onOpenDownloadsFolder?.let {
                    DesktopToolbarOverflowItem(stringResource(Res.string.desktop_overflow_open_downloads), it, icon = painterResource(Res.drawable.ic_folder))
                },
                actions.onQuit?.let {
                    DesktopToolbarOverflowItem(
                        label = stringResource(Res.string.desktop_action_quit),
                        onClick = it,
                        dividerBefore = true,
                        icon = painterResource(Res.drawable.ic_close),
                        // 原型 `.mi2.danger`：退出是这一档里唯一的破坏性动作。
                        danger = true,
                    )
                },
            )
        }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val spec = desktopShortcutKeySpec(event.key) ?: return@onPreviewKeyEvent false
                val shortcut = desktopShortcutFor(
                    key = spec,
                    // isPrimaryModifier = ⌘ on macOS, Ctrl elsewhere.
                    isPrimaryModifier = event.isMetaPressed || event.isCtrlPressed,
                    isShift = event.isShiftPressed,
                    isAlt = event.isAltPressed,
                ) ?: return@onPreviewKeyEvent false
                handleShortcut(shortcut)
            },
    ) {
        // 菜单栏是桌面 chrome 里的第一段；平板把它整条拿掉（见 [DesktopChromeMode]）。
        if (chromeMode.showsDesktopChrome) {
            DesktopMenuBar(
                darkTheme = darkTheme,
                onOpenSettings = actions.onOpenSettings,
                onToggleTheme = actions.onToggleTheme,
                onExportDiagnostics = actions.onExportDiagnostics,
                onRefresh = actions.onRefresh,
                onQuit = actions.onQuit,
                onOpenAgents = openAgents,
                onCopyNodeId = { actions.onCopyNodeId(state.selfNodeId) },
                onNewConversation = {
                    view = DesktopView.Nodes
                    actions.onNewConversation()
                },
                onImportFiles = { selectedPeerId?.let(actions.onImportFiles) },
                onSelectView = { view = it },
                // 与上面 [DesktopShortcut.ScreenCapture] 那条快捷键**逐字同一个**表达式：同一个组合键
                // 两条入口必须同一种行为（task-25）。不在这里顺带 `view = …`，截图覆盖窗是独立窗。
                onScreenCapture = { controller.triggerScreenCapture(selectedPeerId) },
            )
        }
        // 窗口级标题栏只在 Expanded/Medium 画 —— 窄窗（Compact，< DesktopMetrics.MEDIUM_MIN_PX）
        // 不画它：右栏自带的 `.dhead` 里已经有身份与复制按钮，再叠一行「捎话 Tidings」就是
        // 同一份信息占掉两行高度。原型见 `prototype/relay.html?size=compact`。
        if (sizeClass != DesktopWindowSizeClass.Compact && chromeMode.showsDesktopChrome) {
            DesktopTitleBar(
                state = state,
                view = view,
                selectedPeerId = selectedPeerId,
                now = now,
            )
        }
        // 工具栏只在宽档画：视图切换交给左侧竖导，这里只留搜索与四个动作按钮，
        // 搜索框加四个按钮在 Compact 的宽度下会把工具栏挤到换行。
        // Compact 靠底部三段导航返回列表，没有这一行也不缺入口。
        if (sizeClass != DesktopWindowSizeClass.Compact) {
            DesktopToolbar(
                sizeClass = sizeClass,
                query = query,
                onQueryChange = { query = it },
                onImportFiles = { selectedPeerId?.let(actions.onImportFiles) },
                onNewConversation = { view = DesktopView.Nodes; actions.onNewConversation() },
                // 没有选中会话时按钮是灰的：开一扇空窗口对用户没有意义。
                canOpenInNewWindow = selectedPeerId != null && actions.onOpenPeerWindow != null,
                onOpenInNewWindow = { selectedPeerId?.let { id -> actions.onOpenPeerWindow?.invoke(id) } },
                // Medium/Compact 的筛选入口：搜索面板仍由 Expanded 常驻，
                // 窄档用「搜索」按钮按需展开，不再让用户看见「清除搜索」却看不到搜索框。
                onToggleSearch = { searching = !searching },
                searching = searching,
                // 平板没有菜单栏，下面这几条在桌面上主要靠菜单栏（或 ⌘ 快捷键）到，
                // 平板两样都没有，所以必须在这里补出第二条路。
                // 桌面传空列表，菜单栏继续独占这些动作 —— 两套 chrome 各管各的，
                // 不做「桌面上也塞一份溢出菜单」的重复。
                overflowItems = overflowItems,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 导航条是**壳**的一部分，五视图平级：Expanded/Medium 在左、Compact 在下。
            // 工具栏里没有视图切换器（原型 `toolbar()` 的注释写死「没有视图切换 chips ——
            // 那一排已经删了，视图切换只在左侧竖导」），所以竖导/底导是**唯一**的切换器：
            // 宽窗再不给入口，触屏和没有菜单栏的平板就只剩菜单项能换页。
            val viewContent: @Composable () -> Unit = {
                when (view) {
                    DesktopView.Conversations -> ConversationsPane(
                            state = state,
                            controller = controller,
                            sizeClass = sizeClass,
                            sessions = sessions,
                            selectedPeerId = selectedPeerId,
                            query = query,
                            onClearQuery = { query = "" },
                            onCopyPeerId = { actions.onCopyNodeId(it) },
                            onOpenInNewWindow = actions.onOpenPeerWindow,
                            onSelect = { id -> selectedPeerId = id },
                            onGoToPairing = { view = DesktopView.Nodes },
                            now = now,
                            initialDraft = initialDraft,
                            initialGlobalSearch = initialGlobalSearch,
                            // 返回值（新群的 groupId）在这里没用：选中新群由控制器自己
                            // 通过会话列表把它摆出来，壳不需要跳转。
                            onCreateGroup = { name, members -> controller.createGroup(name, members) },
                            // 首启空态第三张卡（"看看工作区"）：切到工作区视图，见 [openAgents]。
                            onOpenAgentConsole = openAgents,
                        )

                        DesktopView.Files -> FilesPane(
                            state = state,
                            query = query,
                            onClearQuery = { query = "" },
                            onDismissBanner = controller::dismissBanner,
                            onOpenFolder = actions.onOpenDownloadsFolder,
                            onJumpToConversation = { id ->
                                selectedPeerId = id
                                view = DesktopView.Conversations
                            },
                            onRefresh = actions.onRefresh,
                            now = now,
                            initialTransferId = initialTransferId,
                            onResumeTransfer = controller::resumeTransfer,
                            onCancelTransfer = controller::cancelTransfer,
                            // 壳已扣过 chrome（下面那条 Row 里的竖导），这里不能再扣一次。
                            sizeClass = sizeClass,
                        )

                        // 工作区视图：整块就是这个屏幕（内容宽度由 AgentConsoleScreen 自己收列，
                        // 所以旁边还有竖导时也不会顶到边）。页头（"工作区" + 汇总行 + 刷新）
                        // 由 WorkspaceBoardView 自己画，壳不再叠一行标题 —— 浮层时代那行标题
                        // 外加一颗关闭按钮，在视图切换器面前已经没有意义了。
                        DesktopView.Agents -> Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        ) {
                            // 横幅是**窗口级**提示（断线、传输失败…），它跟随当前视图。
                            // 浮层时代由 AgentPane 画，现在这一屏自己画 —— 少了这一行，
                            // 用户在工作区屏就看不到任何横幅了（其它 pane 都各自画了）。
                            state.banner?.let { AppBanner(it, onDismiss = controller::dismissBanner) }
                            AgentConsoleScreen(
                                state = state,
                                selectedAgentId = selectedPeerId,
                                onSelectAgent = { selectedPeerId = it },
                                // 壳的视图路由是**它自己**管的（视图路由归 shell 独占），
                                // 它没有 nav 对象。所以这里不传 wsId —— 桌面壳的聊天窗本来就是
                                // 分栏的，左栏一直在，用户不会「进去之后不知道在哪个工作区」。
                                // 要补收窄得先给这个壳一套工作区选择，那是另一件事。
                                onOpenChat = { id, _ ->
                                    selectedPeerId = id
                                    view = DesktopView.Conversations
                                },
                                onFindNodes = { view = DesktopView.Nodes },
                                onRespondApproval = controller::respondApproval,
                                onCancelTask = controller::cancelAgentTask,
                                modifier = Modifier.fillMaxSize(),
                                now = now,
                            )
                        }

                        DesktopView.Cabinet -> CabinetPane(
                            state = state,
                            controller = controller,
                            now = now,
                        )

                        DesktopView.Nodes -> NodesPane(
                            state = state,
                            controller = controller,
                            // 新稿不再画「本机节点 ID」条，这个实参只为兼容第一轮帧文件保留。
                            onCopyNodeId = { actions.onCopyNodeId(state.selfNodeId) },
                            onSetPeerRemark = controller::setPeerRemark,
                            // 壳的 `initialPeerId` 本来就是「帧要预置选中的那个对端」，
                            // 节点页借用同一条接缝：它既接受 nodeId，也接受节点行的 key
                            // （`"manual:<host>:<port>"` —— 死地址没有 nodeId，只有 key 能选中）。
                            initialNodeId = initialPeerId,
                            // 详情的「发消息」在原型里是 `go('m-chat')`（切到会话），
                            // 所以既要选中对端、也要把视图切回去 —— 只置 peer 而留在节点页
                            // 是「点了没反应」，与早期只改浮层开关而不换视图那次同一个形状。
                            onOpenChat = { peerId ->
                                selectedPeerId = peerId
                                view = DesktopView.Conversations
                            },
                            // 壳已扣过 chrome，这里不能再扣一次。
                            sizeClass = sizeClass,
                            // 时钟必须由壳传进来：不传就走 `now: Long = nowMillis()` 的默认值，
                            // 详情「最近」那一行会拿**真实墙钟**去算固定基准的夹具时间
                            // （实测把「12 秒前」渲染成「32 天前」—— 开图才看得见，差集与配对判定都读不出）。
                            now = now,
                            // 节点副标题在原型里属**窗口标题栏**（`tsub('nodes')`），
                            // 桌面 chrome 那份由 `DesktopTitleBar` 画；平板宿主没有标题栏，
                            // 才需要面板自己补一行，否则「N 个节点 · M 在线」在那台机器上不出现。
                            showNodeCountInPane = !chromeMode.showsDesktopChrome,
                        )
                    }
                }

            when (sizeClass) {
                // 竖导是唯一的视图切换器：工具栏里那一排 chips 已经删掉，
                // Expanded 再不给切换入口，宽窗（尤其是没有菜单栏的平板横屏）
                // 就只剩菜单项能换页，触屏/窄键盘都摸不到。
                DesktopWindowSizeClass.Expanded,
                DesktopWindowSizeClass.Medium,
                -> Row(Modifier.fillMaxSize()) {
                    NavigationRail(view = view, onViewChange = { view = it }, badges = desktopNavBadges(state))
                    Box(Modifier.weight(1f).fillMaxHeight()) { viewContent() }
                }

                // Compact：底导常驻。再点一次当前页的「会话」从详情退回列表 ——
                // 底导是窄窗里唯一的返回入口，只切视图会让用户卡在详情里。
                DesktopWindowSizeClass.Compact -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { viewContent() }
                    CompactNavigationBar(
                        view = view,
                        onViewChange = { next ->
                            if (next == view && next == DesktopView.Conversations) selectedPeerId = null else view = next
                        },
                        badges = desktopNavBadges(state),
                    )
                }
            }
        }

        if (chromeMode.showsDesktopChrome) {
            DesktopStatusBar(
                state = state,
                view = view,
                sizeClass = sizeClass,
                controlPlanePort = controlPlanePort,
                actions = actions,
            )
        }

        app.relay.ui.components.RemoteViewSessionDialog(
            state = state.screenSession,
            onAccept = { controller.respondScreenView(true) },
            onReject = { controller.respondScreenView(false) },
            onStop = { controller.stopScreenView() },
            onSetMode = { controller.setScreenViewMode(it) },
        )
    }

        if (overflowPanelPreview && overflowItems.isNotEmpty()) {
            // 面板锚在工具栏那颗「⋯」下面：平板宿主没有菜单栏/标题栏，
            // 所以顶距 = 工具条高 + 原型 `.tbmore .menu{top:calc(100% + 8px)}`（`:2623`）。
            ToolbarOverflowPanel(
                items = overflowItems,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = DesktopMetrics.TABLET_CHROME_PX.dp + LocalAppSpacing.current.sm,
                        end = LocalAppSpacing.current.lg,
                    ),
            )
        }
    }
    }
}
