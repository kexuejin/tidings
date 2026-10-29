// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.domain.DesktopPaneActions

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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.backhandler.BackHandler
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
import com.kapp.tidings.ui.theme.LocalAppSpacing

import com.kapp.tidings.domain.GlobalSearchResult
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.transport.LocalFile
import com.kapp.tidings.feature.agents.AgentConsoleScreen
import com.kapp.tidings.feature.chat.draftOf
import com.kapp.tidings.feature.chat.updateDraft
import com.kapp.tidings.feature.chat.DraftsByConvSaver
import com.kapp.tidings.feature.chat.ChatScreen
import com.kapp.tidings.ui.components.AppBanner
import com.kapp.tidings.generated.resources.workspace_group_default_name
import com.kapp.tidings.ui.components.WorkspaceGroupCreateDialog
import com.kapp.tidings.ui.components.WorkspaceGroupPromptDialog
import com.kapp.tidings.ui.components.WorkspaceQrDialog
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.ui.theme.LocalCardShape
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.desktop_action_quit
import com.kapp.tidings.generated.resources.desktop_menu_agents
import com.kapp.tidings.generated.resources.desktop_menu_copy_node_id
import com.kapp.tidings.generated.resources.desktop_menu_diagnostics
import com.kapp.tidings.generated.resources.desktop_menu_refresh_nodes
import com.kapp.tidings.generated.resources.desktop_overflow_open_downloads
import com.kapp.tidings.generated.resources.desktop_overflow_theme_dark
import com.kapp.tidings.generated.resources.desktop_overflow_theme_light
import com.kapp.tidings.generated.resources.desktop_view_agents
import com.kapp.tidings.generated.resources.desktop_view_cabinet
import com.kapp.tidings.generated.resources.desktop_view_conversations
import com.kapp.tidings.generated.resources.desktop_view_files
import com.kapp.tidings.generated.resources.desktop_view_nodes
import com.kapp.tidings.generated.resources.screen_settings
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_copy
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_info
import com.kapp.tidings.generated.resources.ic_moon
import com.kapp.tidings.generated.resources.ic_refresh
import com.kapp.tidings.generated.resources.ic_settings
import com.kapp.tidings.generated.resources.ic_terminal
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
    /**
     * 「在文件管理器里打开**共享目录**」。null = 宿主给不出这条出口（或本机还没设
     * 共享目录），文件柜那张卡上的「打开」按钮**整颗不画**（与 [onOpenDownloadsFolder]
     * 同一判据：能力不存在就不给一个按下去没反应的入口）。
     *
     * 与 [onOpenDownloadsFolder] 的区别是**打开哪个目录**：那条开的是数据/接收目录，
     * 这条开的是用户设的共享目录（`settings.shareRoot`）。两者不能互相冒充 ——
     * 打开错的目录和点了没反应一样是在骗人。
     */
    val onOpenShareRoot: (() -> Unit)? = null,
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
 * 结构自上而下固定三段：标题栏 40px / 工具栏 46px / 内容 / 状态栏 26px，
 * 总计 [DesktopMetrics.CHROME_PX] = 112px。这条预算一开始就写死了：
 * 原型第一版把侧栏 300px 也算进高度，结果输入框被挤出可视区。
 *
 * 原先还有第四段：窗口内那条 26px 自绘菜单栏。它按用户裁决整条删掉，
 * 改由系统菜单栏承载（`desktopApp` 窗口里的 Compose `MenuBar`，见
 * `DesktopWindow.kt`）—— 原型自己就写着那 26px 是 macOS 系统菜单栏的示意
 * （`prototype/relay.html` 的 `.menubar` 注释）。
 */
// BackHandler（系统返回键）在 Compose Multiplatform 里仍是实验 API：opt-in 收在这一个函数上，
// 与其他壳层入口（App.kt 的 BackKeyHandler）同一处理方式。
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
     * 「把添加节点面板打开」的外来请求计数（同一套意图计数约定）。
     *
     * 与 [externalNodesRequest] 配套：窗口层系统菜单的「新建会话」同时递增两者 ——
     * 前者切视图、后者开面板。**只切视图不够**：用户已经在节点页时那一按没有任何反馈
     * （真机上就是「点新建会话没反应」）。
     */
    externalAddNodeRequest: Int = 0,
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
    stagedFilesByConv: Map<String, List<LocalFile>> = emptyMap(),
    onStagedFilesChange: (convId: String, files: List<LocalFile>) -> Unit = { _, _ -> },
    actions: DesktopShellActions,
    modifier: Modifier = Modifier,
) {
    val sizeClass = desktopWindowSizeClass(windowWidthDp, windowHeightDp, chromeMode)
    // ⚠ 下面这六格（view / selectedPeerId / selectedWsId / selectedAgentSessionId / query /
    // searching）**必须活过配置变更**：平板形态走的就是这条壳，而 Manifest 没有
    // configChanges —— 转一次屏整个 Activity 重建，裸 remember 会把它们全扔回初值，
    // 用户正在看的会话直接消失、视图跳回列表。手机壳由 App.kt 的 AppNavStateSaver 负责，
    // 这条壳此前只有草稿表是 Saveable。改动它们之前先看 AppShellSaveableStateGuardTest。
    var view by rememberSaveable(stateSaver = DesktopViewSaver) { mutableStateOf(initialView) }

    // 深链进来时把用户送到「节点」视图：新加的节点就在那里，留在会话列表会让人
    // 以为链接没生效。
    LaunchedEffect(externalNodesRequest) {
        if (externalNodesRequest > 0) view = DesktopView.Nodes
    }
    // 托盘菜单「收到的文件」同理：只报请求，路由仍归 shell。
    LaunchedEffect(externalFilesRequest) {
        if (externalFilesRequest > 0) view = DesktopView.Files
    }
    var selectedPeerId by rememberSaveable { mutableStateOf(initialPeerId) }

    // 复制回执：状态栏右侧「已复制」小字，2 秒自清 —— 与行内回执同一范式，
    // 取代「复制一发系统通知」的旧做法（一次性动作留在窗内就够，不该进通知中心）。
    var copyReceiptAt by remember { mutableStateOf(0L) }
    LaunchedEffect(copyReceiptAt) {
        if (copyReceiptAt != 0L) {
            kotlinx.coroutines.delay(2_000)
            copyReceiptAt = 0L
        }
    }

    // 每个会话各自的草稿 —— 与手机壳同一条理由（见 App.kt 的 draftsByConv）：
    // ChatScreen 的 draft 是 `remember(peerId)` 的，切会话就重建，写到一半切走回来字没了。
    // 桌面窗口常驻，这张表活得比手机更久，但同样只活在进程内（不落盘）。
    //
    // **必须是 rememberSaveable**：平板形态（Android 平板/折叠屏展开）走的就是这条壳，
    // 而 Manifest 没有 configChanges —— 转一次屏整个 Activity 重建，裸 remember 会把草稿
    // 全清掉。手机壳（App.kt）早就换成了 Saveable，桌面壳这边漏了，症状是"同一个应用，
    // 手机上转屏不丢、平板上转屏全丢"。存档格式由 [DraftsByConvSaver] **唯一**决定 ——
    // 它就是手机壳用的那一个实例（声明在 `feature/chat`，两壳谁都不许再包一层）。
    var draftsByConv by rememberSaveable(stateSaver = DraftsByConvSaver) {
        mutableStateOf(mapOf<String, String>())
    }
    var internalStagedFilesByConv by remember {
        mutableStateOf(mapOf<String, List<LocalFile>>())
    }
    val effectiveStagedFilesByConv = if (stagedFilesByConv.isNotEmpty()) stagedFilesByConv else internalStagedFilesByConv
    val effectiveOnStagedFilesChange: (String, List<LocalFile>) -> Unit = { convId, files ->
        onStagedFilesChange(convId, files)
        internalStagedFilesByConv = if (files.isEmpty()) {
            internalStagedFilesByConv - convId
        } else {
            internalStagedFilesByConv + (convId to files)
        }
    }
    var selectedWsId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAgentSessionId by rememberSaveable(selectedPeerId) { mutableStateOf<String?>(null) }
    var showWsQrFor by remember { mutableStateOf<WorkspaceUi?>(null) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    // 窄档搜索面板的展开态由壳持有，工具栏只负责呈现这个开关。
    // 面板收起后 query 仍保留：否则用户点一下「关闭」就把正在用的筛选无声清空了。
    var searching by rememberSaveable { mutableStateOf(false) }
    var showCreateWsDialog by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    var pendingFocusSearch by remember { mutableStateOf(false) }

    LaunchedEffect(pendingFocusSearch) {
        if (pendingFocusSearch) {
            searchFocusRequester.requestFocus()
            pendingFocusSearch = false
        }
    }

    // 系统返回键（平板 two-pane）。
    //
    // ## 选中态在谁手里（与 App.kt 的边界）
    //
    // 宽屏形态下**「当前看的是哪个会话」只住在这一层**（selectedPeerId / selectedWsId /
    // selectedAgentSessionId），`App.kt` 的 `nav.activeSessionId` 是**手机壳**的状态：
    // 两栏分支既不读它、也不被它渲染。所以平板的返回键该由这里消费 —— App.kt 那几条
    // 导航返回键在平板应当保持关闭（`&& !twoPane`），否则 `nav` 里可能残留一个
    // **界面上看不见**的 activeSessionId（通知点进来那条路会写它），同一次返回被两层
    // 各自消费一次，用户就会遇到"按两次才退出"。
    //
    // ## 只处理自己的状态，并且让**更深的层先消费**
    //
    // 这里只写上面三个本地变量，绝不碰 `nav` / controller；`state.cabinet.activePeerId`
    // 例外：同事文件柜由 App.kt 那条 `peerCabinetOnTop` 负责（它是 controller 状态、两壳共享），
    // 所以文件柜打开时这一条必须**让位**，保证同一次返回只有一个消费方、且顺序是
    // 文件柜（更深）→ 会话详情 → 选中会话 → 交还系统。
    //
    // 桌面（JVM）上 BackHandler 是 no-op，不改变桌面行为。
    BackHandler(
        enabled = (selectedAgentSessionId != null || selectedPeerId != null) &&
            state.cabinet.activePeerId == null,
    ) {
        when {
            selectedAgentSessionId != null -> selectedAgentSessionId = null
            else -> {
                selectedPeerId = null
                selectedWsId = null
            }
        }
    }
    // task-2 接线：进入会话**那一刻**的未读条数。必须在 controller.selectConversation
    // （它会清零未读）之前采下，否则永远是 0 —— 屏外未读指示就只能退化成一个没有数字的
    // 「回到最新」。采集只能发生在壳里：只有壳知道"用户点了哪一行"，控制器在清完未读之后
    // 已经拿不到原值了。
    var desktopChatUnreadOnEntry by remember { mutableStateOf(0) }
    // 所有"打开一个会话"的路径都走它：先采未读，再真正切换。分散写 controller.selectConversation
    // 的地方有十来处，漏掉任何一处都会让那一处进来的会话丢指示。
    val openConversation: (String) -> Unit = { convId ->
        desktopChatUnreadOnEntry = state.unreadByPeer[convId] ?: 0
        controller.selectConversation(convId)
    }
    // issue 7：某个工作区没有绑定协同群时的建群提示对象。
    // 与 showCreateWsDialog（「所有工作区」列表，独立入口「协同群」）分开两格 ——
    // 那是"挑一个工作区建群"，这是"就这一行工作区建群"，两个动作不能共用一个开关。
    var groupCreatePromptFor by remember { mutableStateOf<WorkspaceUi?>(null) }
    // 工作区是**第五个平级视图**，统一通过切换到 [DesktopView.Agents] 呈现。
    //
    // 顺带删掉了 `DesktopAgentPane`：它存在的唯一理由就是给那个浮层做外壳
    // （自带标题行 + 关闭按钮）。视图切换器已经表达了"离开这一屏"，浮层那套
    // 开关/关闭按钮在第五视图里没有对应物。
    val openAgents: () -> Unit = { view = DesktopView.Agents }

    // 往窗口层报当前会话，供拖放接收与托盘跳转使用。见 [DesktopShellActions.onSelectionChange]。
    // 同一条变化线也把"正看着谁"推给控制器：桌面壳不走 `nav`，控制器自己看不见选中项，
    // 不推的话停在一个会话上收消息会留下一个不消的未读（手机/平板是同一条规矩）。
    // 当视图不在会话页时视为空，切回会话页时恢复并清空未读。
    val currentVisibleConvId = if (view == DesktopView.Conversations) selectedPeerId else null
    LaunchedEffect(currentVisibleConvId) {
        actions.onSelectionChange(currentVisibleConvId)
        controller.setVisibleConversation(currentVisibleConvId)
    }

    // 戳一碰的"定位到该会话"：切到会话视图并选中它。请求由外壳消费一次就清掉
    // （App 层调 `consumeFocusRequest`），所以这里只认非空的那一帧。
    LaunchedEffect(focusPeerId) {
        val target = focusPeerId ?: return@LaunchedEffect
        view = DesktopView.Conversations
        selectedPeerId = target
        selectedWsId = null
    }

    // 列表数据流走与手机端同一个入口（orderConversationList = 查询词筛选 + orderInboxFeed）：
    // 置顶的第一排序键只在 orderInboxFeed 里，桌面过去只做筛选、原样 map ⇒ 置顶点了没效果。
    // 会话顺序的唯一真源仍是 RelayUiState.conversations；无置顶时顺序与它逐条一致（稳定排序）。
    val sessions = remember(state.conversations, query) {
        com.kapp.tidings.feature.conversations.orderConversationList(state.conversations, query)
    }

    // 快捷键挂在根 Column 上。Compose 的键事件沿焦点路径分发：preview 阶段从根往下走，
    // 所以根先于输入框看到按键。必须 `.focusable()` —— 不在焦点路径上的 Column 收不到
    // 预览事件；但刻意**不** requestFocus：父级的 LaunchedEffect 比子级后跑，抢焦点会把
    // 输入框的启动自动聚焦顶掉，窗口一打开就打不了字。
    // 「新建会话」的**唯一**实现（缺陷②）：切到节点视图 + 宿主动作。
    //
    // **这一个 lambda 就是「新建会话」的全部语义**：切到节点视图 + 打开添加节点面板。
    //
    // 修过两次，记下来免得再退化：
    //  · 第一版是 `view = Nodes` + `actions.onNewConversation()`，而宿主动作是空实现
    //    ⇒ 全部效果只有一次视图切换，用户已经在节点页时**按下去什么都不发生**。
    //  · 第二版只把窗口层的系统菜单改成 `host.requestNewConversation()`，**漏了这一条**
    //    —— 工具栏那颗按钮与 ⌘N 走的还是旧路，于是「系统菜单能用、按钮没反应」。
    //
    // 现在三条入口（工具栏 / ⌘N / 系统菜单）表达的是同一件事：面板真的打开。
    // 面板的开合归 `NodesPane` 自己，这里只递增一个意图计数。
    var addNodeRequest by remember { mutableStateOf(0) }
    val newConversation: () -> Unit = {
        view = DesktopView.Nodes
        addNodeRequest++
        actions.onNewConversation()
    }

    fun handleShortcut(shortcut: DesktopShortcut): Boolean = when (shortcut) {
        DesktopShortcut.NewConversation -> {
            newConversation()
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
            copyReceiptAt = now
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
        DesktopShortcut.FocusSearch -> {
            searching = true
            pendingFocusSearch = true
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
                    onClick = { actions.onCopyNodeId(state.selfNodeId); copyReceiptAt = now },
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
                // Alt+↑/↓ 在会话列表里移动选中项（a-chat 已把会话行做成可聚焦，这里补列表级移动）。
                // **不用裸方向键**：壳的 onPreviewKeyEvent 先于输入框收到事件，裸 ↑/↓ 会让
                // 「在消息里挪光标」变成「切换会话」。视图不在会话页时不消费，交还给系统。
                if (event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed) {
                    val delta = when (event.key) {
                        androidx.compose.ui.input.key.Key.DirectionDown -> 1
                        androidx.compose.ui.input.key.Key.DirectionUp -> -1
                        else -> 0
                    }
                    if (delta != 0 && view == DesktopView.Conversations) {
                        nextConversationId(selectedPeerId, sessions.map { it.convId }, delta)?.let { next ->
                            selectedPeerId = next
                            selectedWsId = state.workspaceOfConv(next)?.wsId
                            selectedAgentSessionId = null
                            openConversation(next)
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                // Esc 关自绘浮层与弹窗栈：自顶向下逐层收口（Modal Dialog Stack Unwinding）
                if (event.key == androidx.compose.ui.input.key.Key.Escape) {
                    when (nextModalLayerToDismiss(
                        hasWsQr = showWsQrFor != null,
                        hasGroupCreatePrompt = groupCreatePromptFor != null,
                        hasCreateWs = showCreateWsDialog,
                        hasSearchQuery = query.isNotEmpty(),
                        isSearching = searching,
                        hasAgentSession = selectedAgentSessionId != null,
                    )) {
                        DesktopModalLayer.WsQr -> {
                            showWsQrFor = null
                            return@onPreviewKeyEvent true
                        }
                        DesktopModalLayer.GroupCreatePrompt -> {
                            groupCreatePromptFor = null
                            return@onPreviewKeyEvent true
                        }
                        DesktopModalLayer.CreateWs -> {
                            showCreateWsDialog = false
                            return@onPreviewKeyEvent true
                        }
                        DesktopModalLayer.SearchQuery -> {
                            query = ""
                            return@onPreviewKeyEvent true
                        }
                        DesktopModalLayer.Searching -> {
                            searching = false
                            return@onPreviewKeyEvent true
                        }
                        DesktopModalLayer.AgentSession -> {
                            selectedAgentSessionId = null
                            return@onPreviewKeyEvent true
                        }
                        DesktopModalLayer.None -> Unit
                    }
                }
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
                onNewConversation = newConversation,
                // 缺陷③：没选中会话时「导入文件」置灰（判据与系统菜单栏那一项同源）。
                canImportFiles = canImportFiles(selectedPeerId),
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
                searchFocusRequester = searchFocusRequester,
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
                            selectedWsId = selectedWsId,
                            activeSessionId = selectedAgentSessionId,
                            onSelectAgentSession = { selectedAgentSessionId = it },
                            unreadOnEntry = desktopChatUnreadOnEntry,
                            // 草稿预览：与手机壳同一份表（App.kt 传同形 lambda）。空白草稿按
                            // 「没有」处理，否则列表上会出现一个只剩空格的「草稿：」。
                            draftPreviewOf = { convId -> draftsByConv[convId]?.takeIf { it.isNotBlank() } },
                            query = query,
                            onClearQuery = { query = "" },
                            onCopyPeerId = { actions.onCopyNodeId(it); copyReceiptAt = now },
                            onOpenInNewWindow = actions.onOpenPeerWindow,
                            onSelect = { id ->
                                // **再点当前会话 = 收起**（对齐上游 #59 的交互）：
                                // 宽窗右栏回到欢迎页，草稿留在 draftsByConv 里不丢，
                                // 再点同一行原样恢复。收起不清未读（没点开就不算看过）。
                                if (id == selectedPeerId) {
                                    selectedPeerId = null
                                    selectedWsId = null
                                    selectedAgentSessionId = null
                                } else {
                                    val ws = state.workspaceOfConv(id)
                                    selectedPeerId = id
                                    selectedWsId = ws?.wsId
                                    selectedAgentSessionId = null
                                    openConversation(id)
                                }
                            },
                            onGoToPairing = { view = DesktopView.Nodes },
                            now = now,
                            // 有真草稿用它；否则退回 initialDraft（截图门禁的预置值）。
                            initialDraft = draftOf(draftsByConv, selectedPeerId)
                                .ifEmpty { initialDraft },
                            onDraftChange = { convId, text ->
                                draftsByConv = updateDraft(draftsByConv, convId, text)
                            },
                            initialStagedFiles = effectiveStagedFilesByConv[selectedPeerId].orEmpty(),
                            onStagedFilesChange = effectiveOnStagedFilesChange,
                            initialGlobalSearch = initialGlobalSearch,
                            onCreateGroup = { name, members, description ->
                                val gid = controller.createGroup(name, members, description)
                                if (gid != null) {
                                    val convId = com.kapp.tidings.core.protocol.ConvId.group(gid)
                                    val ws = state.workspaceOfGroup(gid)
                                    selectedPeerId = convId
                                    selectedWsId = ws?.wsId ?: description.takeIf { it.isNotBlank() }
                                    selectedAgentSessionId = null
                                    openConversation(convId)
                                }
                                gid
                            },
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
                                val ws = state.workspaceOfConv(id)
                                selectedPeerId = id
                                selectedWsId = ws?.wsId
                                selectedAgentSessionId = null
                                view = DesktopView.Conversations
                                openConversation(id)
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
                            if (showCreateWsDialog) {
                                val groupNameFormat = org.jetbrains.compose.resources.stringResource(
                                    com.kapp.tidings.generated.resources.Res.string.workspace_group_default_name,
                                    "%s",
                                )
                                WorkspaceGroupCreateDialog(
                                    workspaces = state.agentWorkspaces.values.toList(),
                                    groups = state.settings.groups,
                                    onDismiss = { showCreateWsDialog = false },
                                    onOpenChat = { groupId ->
                                        showCreateWsDialog = false
                                        val convId = com.kapp.tidings.core.protocol.ConvId.group(groupId)
                                        val ws = state.workspaceOfGroup(groupId)
                                        selectedPeerId = convId
                                        selectedWsId = ws?.wsId
                                        selectedAgentSessionId = null
                                        view = DesktopView.Conversations
                                        openConversation(convId)
                                    },
                                    onShowQr = { ws ->
                                        showWsQrFor = ws
                                    },
                                    onQuickCreate = { ws, _ ->
                                        showCreateWsDialog = false
                                        // 群名走共享资源（原来这里是硬编码的"… 协同群"）：
                                        // 手机壳一直用 workspace_group_default_name，两条壳的默认名
                                        // 必须同源 —— 否则同一次建群在两端叫两个名字。
                                        val name = groupNameFormat.replace("%s", ws.wsTitle ?: ws.wsId)
                                        val members = state.peers.filter { it.online }.map { it.nodeId }
                                        // 走工作区建群：agent 声明（含 defaultWsId）与群一起落库 ——
                                        // 绑定是声明，不靠简介子串反猜（债#2）。简介留给展示名，
                                        // 不写本机路径（真机反馈 #4）。
                                        val gid = controller.createWorkspaceGroup(
                                            name = name,
                                            wsId = ws.wsId,
                                            memberIds = members,
                                        )
                                        if (gid != null) {
                                            val convId = com.kapp.tidings.core.protocol.ConvId.group(gid)
                                            selectedPeerId = convId
                                            selectedWsId = ws.wsId
                                            selectedAgentSessionId = null
                                            view = DesktopView.Conversations
                                            openConversation(convId)
                                        }
                                    },
                                    onCreateNew = { path, _, groupName, _ ->
                                        showCreateWsDialog = false
                                        val members = state.peers.filter { it.online }.map { it.nodeId }
                                        // 全新目录还没有工作区行（wsId=null）：群先建出来当协同通道，
                                        // 工作区行随后接上；此时没有可声明的 agent，与旧行为一致。
                                        val gid = controller.createWorkspaceGroup(
                                            name = groupName,
                                            wsId = null,
                                            memberIds = members,
                                            description = path,
                                        )
                                        if (gid != null) {
                                            val convId = com.kapp.tidings.core.protocol.ConvId.group(gid)
                                            val ws = state.workspaceOfGroup(gid)
                                            selectedPeerId = convId
                                            selectedWsId = ws?.wsId
                                            selectedAgentSessionId = null
                                            view = DesktopView.Conversations
                                            openConversation(convId)
                                        }
                                    },
                                )
                            }
                            state.banner?.let { AppBanner(it, onDismiss = controller::dismissBanner) }
                            AgentConsoleScreen(
                                state = state,
                                onRevokeSessionApproval = controller::revokeSessionApproval,
                                // 谁能用这个 agent 干活 —— 走 controller 的宿主侧桥。
                                // 手机壳不接这个桥（null），所以那一侧不画配置区。
                                // 可空 → 非空：null（壳没有台账）时给空列表，界面据此不画配置区。
                                execDevicesOf = { agentKey -> controller.execDevicesOf(agentKey).orEmpty() },
                                onToggleExecDevice = { agentKey, nodeId, checked ->
                                    controller.setExecDeviceAllowed(agentKey, nodeId, checked)
                                },
                                // 「添加设备」只要有台账就给出口；候选为空时弹层自己说「都已经在名单里了」，
                                // 那比把入口藏起来好 —— 藏起来用户会以为这个功能没有。
                                onAddExecDevice = {},
                                selectedAgentId = selectedPeerId,
                                onSelectAgent = { selectedPeerId = it },
                                onOpenChat = { id, wsId ->
                                    val ws = state.agentWorkspaces.values.firstOrNull { it.wsId == wsId }
                                    // 跨模块 public 属性先落成局部量，否则 smart cast 不成立。
                                    val wsPath = ws?.wsPath
                                    val wsTitle = ws?.wsTitle
                                    val boundGroup = state.settings.groups.firstOrNull { g ->
                                        val matchPath = !wsPath.isNullOrBlank() && g.description?.contains(wsPath) == true
                                        val matchTitle = !wsTitle.isNullOrBlank() && g.name.contains(wsTitle)
                                        matchPath || matchTitle
                                    }
                                    if (boundGroup != null) {
                                        val groupConvId = com.kapp.tidings.core.protocol.ConvId.group(boundGroup.groupId)
                                        selectedPeerId = groupConvId
                                        view = DesktopView.Conversations
                                        openConversation(groupConvId)
                                    } else if (ws != null) {
                                        // issue 7：这一行是某个具体工作区且没有绑定群 ⇒ **针对它**
                                        // 提示建群并进入。此前这里是 `id == state.selfNodeId` 时
                                        // `showCreateWsDialog = true` —— 弹出一张跟这一行无关的
                                        // 「所有工作区」列表，用户点的那一行在列表里还要自己再找一遍。
                                        // 那张列表仍然保留，但只能是独立入口（会话栏的「协同群」按钮）。
                                        groupCreatePromptFor = ws
                                    } else {
                                        // 没有 wsId 的 Agent 行（或者工作区对象还没到）：直接进这个
                                        // 节点的会话，与手机壳同一条 —— 不再因为"是本机"就换一条路。
                                        selectedPeerId = id
                                        selectedWsId = wsId
                                        selectedAgentSessionId = null
                                        view = DesktopView.Conversations
                                        openConversation(id)
                                        if (wsId != null) {
                                            controller.requestAgentSessionPage(id, wsId, offset = 0)
                                        }
                                    }
                                },
                                onLoadWorkspaceSessions = { id, wsId ->
                                    controller.requestAgentSessionPage(id, wsId, offset = 0)
                                },
                                onFindNodes = { view = DesktopView.Nodes },
                                onRespondApproval = controller::respondApproval,
                                // 三参处置（「本会话免审」）：与手机壳同一条，接 controller 的
                                // 三参重载 —— 此前只接两参，免审按钮永远不出现。
                                onRespondChoice = controller::respondApproval,
                                onCancelTask = controller::cancelAgentTask,
                                onCreateWorkspaceGroup = { showCreateWsDialog = true },
                                onRefresh = {
                                    controller.refreshPeers()
                                    actions.onRefresh()
                                },
                                modifier = Modifier.fillMaxSize(),
                                now = now,
                            )
                        }

                        DesktopView.Cabinet -> CabinetPane(
                            state = state,
                            controller = controller,
                            now = now,
                            // 宿主给不出（或还没设共享目录）时是 null ⇒ 那颗「打开」不画。
                            onOpenShareRoot = actions.onOpenShareRoot,
                        )

                        DesktopView.Nodes -> NodesPane(
                            state = state,
                            controller = controller,
                            // 「新建会话」的落点：把面板直接打开，而不是只切过来。
                            // 两个来源相加：系统菜单（窗口层意图计数）+ 工具栏/⌘N（本文件的）。
                            // 两者都是单调递增的计数器，相加后仍然单调 —— `LaunchedEffect`
                            // 只关心「变了」，所以合并不会漏触发。
                            externalAddNodeRequest = externalAddNodeRequest + addNodeRequest,
                            // 新稿不再画「本机节点 ID」条，这个实参只为兼容第一轮帧文件保留。
                            onCopyNodeId = { actions.onCopyNodeId(state.selfNodeId); copyReceiptAt = now },
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
                                selectedWsId = null
                                view = DesktopView.Conversations
                                openConversation(peerId)
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

            // 窄档下详情类二级页是否盖满内容区：会话详情（选中会话即详情）与
            // 同事文件柜（controller 状态、两壳共享）。节点与 Agent 面板的详情
            // 分支状态收在各自的组合内部，壳读不到 —— 那两处不并入本规则。
            val compactDetailOnTop = sizeClass == DesktopWindowSizeClass.Compact &&
                (
                    (view == DesktopView.Conversations && selectedPeerId != null) ||
                        state.cabinet.activePeerId != null
                    )

            when (sizeClass) {
                // 竖导是唯一的视图切换器：工具栏里那一排 chips 已经删掉，
                // Expanded 再不给切换入口，宽窗（尤其是没有菜单栏的平板横屏）
                // 就只剩菜单项能换页，触屏/窄键盘都摸不到。
                DesktopWindowSizeClass.Expanded,
                DesktopWindowSizeClass.Medium,
                -> Row(Modifier.fillMaxSize()) {
                    NavigationRail(view = view, onViewChange = { view = it }, badges = desktopNavBadges(state), onOpenSettings = actions.onOpenSettings)
                    Box(Modifier.weight(1f).fillMaxHeight()) { viewContent() }
                }

                // Compact：底导常驻。再点一次当前页的「会话」从详情退回列表 ——
                // 底导是窄窗里唯一的返回入口，只切视图会让用户卡在详情里；
                // 详情盖满内容区时（见下）底导整个不画，退路是详情自己的顶栏返回。
                DesktopWindowSizeClass.Compact -> Column(Modifier.fillMaxSize()) {
                    // 窄窗补搜索入口：这一档整条工具栏都不渲染，而 query 仍是壳层状态 ——
                    // 从宽窗收窄过来时列表会一直被旧词过滤，却没有任何输入框（review #8）。
                    // 只在「会话」视图显示：其它视图不按 query 过滤，摆一个输入框是在骗人。
                    if (view == DesktopView.Conversations) {
                        CompactSearchRow(query = query, onQueryChange = { query = it })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { viewContent() }
                    // 二级页（详情）盖满内容区时不再画底导 —— 与手机壳同一条规则：
                    // tab 页是唯一挂底导的层。此前聊天详情保留底导，与手机（聊天全屏、
                    // 其余子页留底导）凑成三套规则；窄窗详情的退路是详情自己的顶栏
                    // （会话详情头的「列表」、同事文件柜的返回）。
                    if (!compactDetailOnTop) {
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
        }

        if (chromeMode.showsDesktopChrome) {
            DesktopStatusBar(
                state = state,
                view = view,
                sizeClass = sizeClass,
                controlPlanePort = controlPlanePort,
                actions = actions,
                copyReceiptActive = copyReceiptAt != 0L,
                onCopied = { copyReceiptAt = now },
            )
        }

        com.kapp.tidings.ui.components.RemoteViewSessionDialog(
            state = state.screenSession,
            onAccept = { controller.respondScreenView(true) },
            onReject = { controller.respondScreenView(false) },
            onStop = { controller.stopScreenView() },
            onSetMode = { controller.setScreenViewMode(it) },
        )

        showWsQrFor?.let { ws ->
            WorkspaceQrDialog(
                wsTitle = ws.wsTitle ?: ws.wsId,
                wsId = ws.wsId,
                wsPath = ws.wsPath,
                host = state.network.localAddresses.firstOrNull()
                    ?: state.peers.firstOrNull { it.online }?.let { it.host.ifBlank { it.ip } },
                port = state.network.boundUdpPort.takeIf { it > 0 } ?: state.settings.udpPort,
                // 全部候选：跨网络的手机才能靠这张码找到本机（公网 v6 在二维码预算内）。
                // 用「宣告」那份：含 Tailscale / ZeroTier 隧道地址（见 NetworkUi 的分工）。
                addresses = state.network.advertisedAddresses,
                nodeId = state.selfNodeId,
                // task-86：直通码也必须带 pin，否则手机建不出公网 TLS 目标。
                tlsPin = state.network.selfTlsPin,
                // 带昵称：扫完立刻是真名，不是 node-a1b2c3d4。
                nick = state.settings.nick,
                onDismiss = { showWsQrFor = null },
            )
        }

        // issue 7：某个工作区没有绑定协同群时的建群提示（与手机壳同一支对话框）。
        // 群名/成员与「快速建群」同一口径：默认名格式 + 当前在线设备。
        groupCreatePromptFor?.let { ws ->
            val groupNameFormat = org.jetbrains.compose.resources.stringResource(
                com.kapp.tidings.generated.resources.Res.string.workspace_group_default_name,
                "%s",
            )
            WorkspaceGroupPromptDialog(
                workspace = ws,
                onDismiss = { groupCreatePromptFor = null },
                onCreate = {
                    groupCreatePromptFor = null
                    val name = groupNameFormat.replace("%s", ws.wsTitle ?: ws.wsId)
                    val members = state.peers.filter { it.online }.map { it.nodeId }
                    // 走工作区建群：agent 声明与群一起落库（绑定是声明，不靠简介反猜）。
                    val gid = controller.createWorkspaceGroup(
                        name = name,
                        wsId = ws.wsId,
                        memberIds = members,
                    )
                    if (gid != null) {
                        val convId = com.kapp.tidings.core.protocol.ConvId.group(gid)
                        selectedPeerId = convId
                        selectedWsId = ws.wsId
                        selectedAgentSessionId = null
                        view = DesktopView.Conversations
                        openConversation(convId)
                    }
                },
                onOpenWithoutGroup = {
                    groupCreatePromptFor = null
                    selectedPeerId = ws.peerId
                    selectedWsId = ws.wsId
                    selectedAgentSessionId = null
                    view = DesktopView.Conversations
                    openConversation(ws.peerId)
                },
            )
        }
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

/**
 * [DesktopView] 的 Saveable 映射。
 *
 * 枚举在 Android Bundle 里可以靠 Serializable 过关，但各平台对「Bundle 里允许放什么」的
 * 口径并不一致（与草稿表的 Saver 同一条理由）；显式只存 name，让存档里只有一个字符串，
 * 读到不认识的视图名就退回会话列表 —— 不猜、也不崩在启动路径上。
 */
/**
 * Alt+↑/↓ 的**纯判据**：在给定的会话顺序里从 [current] 移动 [delta] 格。
 *
 * ## 为什么要一个纯函数
 *
 * 列表级键盘移动的边界（第一次按 ↓ 从哪开始、到头之后怎么办、当前选中已被删掉怎么办）
 * 是这条交互的全部内容；放在可执行的判断里，测试才能把"到头就停"和"跳过不存在的 id"
 * 分开钉住，而不是靠一段 UI 代码的注释声明。
 *
 * 规则：
 * - 列表为空 ⇒ null（没有可去的地方，调用方不要消费按键）；
 * - [current] 为 null 或已不在列表里 ⇒ 从就近的一端进入（↓ 取第一条，↑ 也取第一条 ——
 *   用户按 ↑ 时通常想要"最后一条"，但那条更常见于底部列表；侧栏是自上而下的时间序，
 *   进第一条与手机/桌面其它列表的键盘行为一致，且不会让人以为选中跑到了屏幕外）；
 * - 越界**夹住**而不是循环：循环会让"一直按 ↓"在两端来回跳，用户以为选中丢了。
 */
internal fun nextConversationId(current: String?, orderedIds: List<String>, delta: Int): String? {
    if (orderedIds.isEmpty()) return null
    val index = current?.let { orderedIds.indexOf(it) }?.takeIf { it >= 0 }
    if (index == null) return orderedIds.first()
    val target = (index + delta).coerceIn(0, orderedIds.lastIndex)
    return orderedIds[target]
}

private val DesktopViewSaver: Saver<DesktopView, String> = Saver(
    save = { it.name },
    restore = { name -> DesktopView.entries.firstOrNull { it.name == name } },
)
