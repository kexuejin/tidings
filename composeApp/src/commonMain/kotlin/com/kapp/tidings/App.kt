// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.domain.exportDiagnosticsPackage
import com.kapp.tidings.core.fs.directoryUsageBytes
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.domain.recovery.exportRecoveryBundle
import com.kapp.tidings.domain.recovery.importRecoveryBundle
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.transport.PartFile
import com.kapp.tidings.domain.navBadges
import com.kapp.tidings.feature.agents.AgentConsoleScreen
import com.kapp.tidings.feature.cabinet.CabinetScreen
import com.kapp.tidings.feature.chat.ChatScreen
import com.kapp.tidings.feature.chat.ChatScreenActions
import com.kapp.tidings.feature.chat.ChatScreenChrome
import com.kapp.tidings.feature.chat.ChatScreenGroupActions
import com.kapp.tidings.feature.chat.draftOf
import com.kapp.tidings.feature.chat.updateDraft
import com.kapp.tidings.feature.chat.ChatScreenState
import com.kapp.tidings.feature.chat.DraftsByConvSaver
import com.kapp.tidings.feature.conversations.ConversationsScreen
import com.kapp.tidings.feature.nodes.NodesScreen
import com.kapp.tidings.feature.scan.ScanScreen
import com.kapp.tidings.feature.search.GlobalSearchScreen
import com.kapp.tidings.feature.settings.SETTINGS_PAGE_APPEAR
import com.kapp.tidings.feature.settings.SETTINGS_PAGE_AVATAR
import com.kapp.tidings.feature.settings.SETTINGS_PAGE_CAPS
import com.kapp.tidings.feature.settings.SETTINGS_PAGE_DATA
import com.kapp.tidings.feature.settings.SETTINGS_PAGE_DIAG
import com.kapp.tidings.feature.settings.SETTINGS_PAGE_NOTIFY
import com.kapp.tidings.feature.settings.SETTINGS_PAGE_IME
import com.kapp.tidings.feature.settings.SETTINGS_PAGE_UPDATE
import com.kapp.tidings.feature.settings.SettingsAppearSubpage
import com.kapp.tidings.feature.settings.SettingsCapsSubpage
import com.kapp.tidings.feature.settings.SettingsDataSubpage
import com.kapp.tidings.feature.settings.SettingsDiagSubpage
import com.kapp.tidings.ui.desktop.ImeSmokePane
import com.kapp.tidings.feature.settings.SettingsNotifySubpage
import com.kapp.tidings.feature.settings.SetAvatarPage
import com.kapp.tidings.feature.settings.SettingsUpdateSubpage
import com.kapp.tidings.feature.settings.SettingsScreen
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.workspace_group_default_name
import com.kapp.tidings.navigation.AppLayoutMode
import com.kapp.tidings.navigation.AppNavItems
import com.kapp.tidings.navigation.AppNavState
import com.kapp.tidings.navigation.AppNavStateSaver
import com.kapp.tidings.navigation.AppRoutes
import com.kapp.tidings.platform.CareSystemBars
import com.kapp.tidings.ui.ContentWidths
import com.kapp.tidings.ui.components.AppBanner
import com.kapp.tidings.ui.components.CompactScaffold
import com.kapp.tidings.ui.qrScannerPreviewUi
import com.kapp.tidings.ui.desktop.DesktopChromeMode
import com.kapp.tidings.ui.desktop.DesktopMetrics
import com.kapp.tidings.ui.desktop.DesktopShell
import com.kapp.tidings.ui.desktop.DesktopShellActions
import com.kapp.tidings.ui.theme.AppTheme
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.ThemeMode
import com.kapp.tidings.domain.model.ConversationUi
import com.kapp.tidings.domain.model.PeerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * 应用根。
 *
 * 与脚手架版本的区别不是"多传了几个参数"，而是**数据来源换了**：
 * 脚手架每屏各拿一份 `List<XxxItemUi>` 假数据，所以只能渲染
 * `"TopBar adaptive contract"` 这类占位串；这里五个页签加聊天详情共享同一个
 * [RelayController] —— 同一份节点表、同一条消息流、同一个队列深度。
 *
 * [controller] 由平台壳装配（见 `MainActivity`）：手机端注入 Android 实现，
 * 预览与测试注入内存实现。
 *
 * **系统 inset 在这里消费，且只在这里消费一次**：外层 `Surface` 铺满整窗给系统栏
 * 铺底色，内层 `BoxWithConstraints` 用 `WindowInsets.safeDrawing` 把内容推下来。
 * 屏内页面与 [CompactScaffold] 都不要再加 `safeDrawingPadding` / `imePadding`，
 * 否则同一个 padding 会被算两遍 —— 表现为顶部多一段空白、底栏浮在导航栏之上。
 *
 * 宿主侧只负责「让窗口铺到系统栏后面」：Android `MainActivity` 调
 * `enableEdgeToEdge()`；iOS 走 CMP 默认的 `ComposeUIViewController`，它已经
 * 把 `safeAreaInsets` 翻译成 `WindowInsets` 交给这里，不要再 `.ignoresSafeArea()`。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RelayApp(
    controller: RelayController,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val state by controller.state.collectAsState()

    // 幂等：start() 自己会挡住重复调用，所以重组不会重复 bind。
    LaunchedEffect(controller) { controller.start() }

    // **领域层要「一句话」的桥**（解缝点）。
    //
    // 那几句话住在 composeResources 的 strings.xml 里，取它要 `getString` —— 而
    // 「要不要落一条投屏记录、要不要发一条通知」是领域判据。于是界面层在这里把真的
    // 实现装上，领域侧只认 `RelayUiTexts` 这个接口（见它的 KDoc）。
    //
    // 用 DisposableEffect 而不是 LaunchedEffect：它必须在 controller.start() 之后立刻
    // 就位（启动卫生那条路径会马上读文案），并且在离开组合时摘掉 —— 留下一个指向
    // 已离开组合的实现的桥，比没有桥更糟。摘掉之后回落到 [NoUiTexts]（空串），
    // 而不是崩溃。
    DisposableEffect(controller) {
        controller.uiTexts = com.kapp.tidings.ui.ResourceUiTexts
        onDispose { controller.uiTexts = com.kapp.tidings.domain.NoUiTexts }
    }

    // 外观是「跟随系统 / 浅色 / 深色」三选一，不是布尔量：只有三选一才能既表达
    // 「不表态」，又让状态栏图标亮度拿到一个确定的答案（见 CareSystemBars）。
    // 放在 RelayApp 而不是设置屏里，是因为 AppTheme 和系统栏都在这一层决策。
    //
    // 初值从**设置文件**读（`controller.state.settings.themeMode`），不是硬编码 System：
    // 用户选过深色，重启就该还是深色。这里刻意不用 rememberSaveable —— 那会变成第二个
    // 事实来源（配置变更时快照和设置文件各说各话）。进程重建后本函数重新组合，
    // 从设置里读回来的就是对的。
    val themeMode = remember(state.settings.themeMode) {
        ThemeMode.fromWire(state.settings.themeMode)
    }
    val dark = themeMode.resolve(darkTheme)

    AppTheme(darkTheme = dark) {
        // 系统栏图标明暗跟应用主题走，而不是跟系统设置走：
        // 应用允许手动强制浅色 / 深色，此时系统可能正好是另一套，
        // 不接管就会出现「浅色页 + 浅色状态栏图标」这类半截。
        CareSystemBars(darkTheme = dark)

        // 两层各司其职，并且这里是全树唯一消费系统 inset 的地方：
        //  - Surface 铺满整窗，负责给状态栏 / 导航栏那几条 inset 区域铺底色。
        //    窗口自身默认不上底色，露出来正是"顶部空白"的观感来源。
        //    **底色用 `surface` 而不是 `background`**：原型的状态栏区与底导区都是
        //    `--surface`（`.topbar{background:var(--surface)}`，`prototype/relay.html:545`；
        //    `--nav-bg:rgba(255,255,255,.92)`，`:50`），`--bg` 只出现在顶栏与底导之间。
        //    写 `background` 会让状态栏那一条比顶栏暗一档，在顶栏顶上留一道横向色差。
        //    下面每个内容区（`CompactScaffold` 的 Scaffold、`DesktopShell`、各 pane）
        //    都自己铺 `background`，所以这里换成 surface 不动任何页面底色。
        //  - 里面的 BoxWithConstraints 消费 safeDrawing，整棵树只 pad 这一次：
        //    内容既不顶进状态栏，底栏也不会浮在系统导航栏上。
        // safeDrawing 含 IME，所以屏内一律不再 imePadding()。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                val layoutMode = resolveAppLayoutMode(maxWidth, maxHeight)
                val twoPane = layoutMode == AppLayoutMode.MediumTwoPane ||
                    layoutMode == AppLayoutMode.ExpandedListDetail
                // 桌面壳只要整数 dp。就地取出来，免得在下面嵌套的 Box 里再访问
                // [BoxWithConstraintsScope] 的 maxWidth/maxHeight —— 内层的
                // BoxScope 会把那个隐式接收者挡住。
                val windowWidthDp = maxWidth.value.toInt()
                val windowHeightDp = maxHeight.value.toInt()

                // 导航状态**必须能活过转屏与进程重建**：本应用的 Manifest 没有
                // configChanges，转一次屏整个 Activity 重建，裸 `remember` 会被扔回初值
                // —— 用户正停在聊天里，转个屏就落回会话列表，聊天里的草稿也一起没了。
                // `rememberSaveable(stateSaver = AppNavStateSaver)` 把这件事变成不变量；
                // 存档格式（逐字段、null 的表示法）见 [AppNavStateSaver] 的 KDoc。
                var nav by rememberSaveable(stateSaver = AppNavStateSaver) {
                    mutableStateOf(AppNavState())
                }

                // 会话列表的滚动状态**提到这里**，而不是留在 ConversationsScreen 内部。
                //
                // 为什么：`ConversationsScreen` 挂在 `when` 分支里，进会话那一屏切走时它就被
                // 销毁了，内部的 rememberLazyListState 跟着没 ⇒ 返回时列表回到顶部（116 个
                // 会话的列表上，用户翻到「11 天前」那批点进去看一眼再回来，位置全丢）。
                // 放在 nav 旁边，它与导航状态同寿，进出会话都还在。
                //
                // **它自身不用另存**：`rememberLazyListState` 内部就是 `rememberSaveable`，
                // 转屏 / 进程重建自己能活；这里提上来解决的是「分支切走即销毁」，
                // 不是「转屏归零」。
                val convListState = rememberLazyListState()

                // 页签内容的 Saveable 存档按页签 id 分格：切页签（以及进聊天 / 扫码 /
                // 搜索盖上来）时旧页签离开组合，它的 rememberSaveable（各屏内部的
                // 滚动位置等）会被销毁 —— 有了这层 provider，存档留在 holder 里，
                // 切回来原样恢复。与 NavHost 保存各目的地状态是同一个机制。
                val tabStateHolder = rememberSaveableStateHolder()

                // 折叠屏展开 / 平板旋转会让外壳从手机换成两栏，而两栏那条路没有扫码页：
                // 不在这里收掉的话，页面会无声消失（scanning 还挂着、相机也一直开着），
                // 折回去又自己冒出来。`selectTab` 与关闭按钮之外的第三条清路。
                LaunchedEffect(twoPane) {
                    if (twoPane && nav.scanning) nav = nav.stopScanning()
                }

                // 「在看哪个 Agent 的日志」和「聊天里开着哪几个会话」是两件事，
                // 以前它们共用 selectedPeerId，于是打开一个 Agent 日志会顺手改掉聊天的当前会话。
                var selectedAgentId by remember { mutableStateOf<String?>(null) }

                // 每个会话各自的草稿。**必须住在壳里**：ChatScreen 自己的 `draft` 是
                // `remember(peerId)` 的，切会话就重建 —— 用户写到一半去别处确认件事，
                // 回来字数全没。壳按会话键记着，切回来时作为 `initialDraft` 喂进 ChatScreen。
                // `rememberSaveable` 而不是裸 `remember`：转屏 / 进程重建时草稿跟着壳一起
                // 存活（存档格式见 [DraftsByConvSaver]）。它仍然**不落盘**：存档活的是
                // 系统给的 SavedState（转屏与系统回收进程），用户主动划掉应用就该丢 ——
                // "切页面不丢"依旧成立，只是不再连转屏一起丢。
                var draftsByConv by rememberSaveable(stateSaver = DraftsByConvSaver) {
                    mutableStateOf(mapOf<String, String>())
                }

                // 戳一碰要求"定位到该会话"（协议 §8）：控制器只放一个一次性请求，
                // 外壳消费一次就清掉 —— 留着它，用户手动切走之后又会被拽回来。
                // 宽屏那条路不吃 nav，所以同时把值交给 [DesktopShell]（它自己管选中项）。
                // task-2 接线：进入会话**那一刻**的未读条数。必须在 controller.selectConversation
                // （它会清零未读）**之前**采下 —— 采晚了永远是 0，屏外未读指示就只剩一个不带
                // 数字的「回到最新」。采集只能发生在壳里：只有壳知道用户点了哪一行。
                // 声明在这里（而不是 openChat 旁边）：通知焦点请求那条路比 openChat 先用到它。
                var chatUnreadOnEntry by remember { mutableStateOf(0) }

                val focusRequest = state.focusRequest
                LaunchedEffect(focusRequest) {
                    if (focusRequest == null) return@LaunchedEffect
                    // 通知点进来也是一次"进入会话"：同样要在清未读**之前**把那一刻的未读采下，
                    // 否则从通知进来的那一屏永远拿不到屏外未读指示。
                    chatUnreadOnEntry = state.unreadByPeer[focusRequest] ?: 0
                    nav = nav.openSession(focusRequest)
                    // 焦点请求现在也可能是群会话键（群消息的通知落点是 `group:<groupId>`），
                    // 所以这里与 openChat 走同一个入口，不能再用只认节点 id 的 selectPeer
                    // —— 那样红点会留在群上，用户点开通知后看到的是一个还标着未读的会话。
                    controller.selectConversation(focusRequest)
                    controller.consumeFocusRequest()
                }

                // 正看着的会话不累加未读：消息就在眼前还挂着一个红点是在骗人。
                // 跟着 `nav.activeSessionId` 走而不是在 openChat/返回/切页签三处各写一遍 ——
                // 可见会话只有这一个真相来源（`inChatDetail` 就是它非空），漏一处的表现
                // 是一个永远不消的未读。真机验收群聊时 Android 标签条上挂的就是这个 1。
                LaunchedEffect(nav.activeSessionId) {
                    controller.setVisibleConversation(nav.activeSessionId)
                }

                // 设置的第二屏是**页签内的子屏**，所以它没有自己的 AppRoute：
                // 真给它一个 route，就得在 AppNavItems 里再插一项，而原型里设置
                // 只有一个页签（见 AppNavItems 的 KDoc）。用一个局部开关承载，
                // 并且必须同时接管返回键 —— 否则用户在第二屏按返回会直接退出应用。
                // 第二屏现在是**六选一**：拆分前是一个布尔 showSettingsMore，
                // 而「进第二屏」实际是六件事之一（五张原型设置页 + 代码自有的更新页）。
                var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
                BackHandler(enabled = settingsPage != null) { settingsPage = null }

                // 平板外壳（DesktopShell）没有桌面菜单栏，设置只能在工具栏溢出里进入，
                // 因此在这里挂一个全屏覆盖层；同样必须接管返回键。
                // 必须是 rememberSaveable：平板/折叠屏转屏时覆盖层若凭空消失，用户半改的设置
                // 就没了（手机侧的 settingsPage 早已是可保存的，这里此前漏了）。
                var tabletSettingsOpen by rememberSaveable { mutableStateOf(false) }
                BackKeyHandler(enabled = tabletSettingsOpen) { tabletSettingsOpen = false }

                // 手机端全局搜索（原型 `m-gsearch`）。它是**整屏**而不是弹层：原型
                // `screenGSearch` 顶栏那一行装的是输入框而不是标题，底导仍在 ——
                // 也就是它替换的是会话列表那**一整屏**，不是盖一层对话框。
                // 与上面两条同一个理由：盖住了整屏就必须接管返回键，否则用户在搜索页
                // 按返回会直接退出应用（这条规矩在本文件里已经写过三遍）。
                var globalSearchOpen by rememberSaveable { mutableStateOf(false) }
                BackKeyHandler(enabled = globalSearchOpen) { globalSearchOpen = false }

                // 平板同样要能复制节点 ID / 配对链接：composeApp 里没有剪贴板实现，
                // 用 Compose 自带的 LocalClipboardManager（Android/iOS/桌面通用）。
                val clipboard = LocalClipboardManager.current

                // 选中一个会话后进聊天详情；两栏模式下面板本来就并排显示。
                // 参数名从 `peerId` 改成 `convId`：群的会话键是 `group:<groupId>`，
                // 它不是任何一个节点。清未读也必须按会话键清 —— `selectPeer` 清的是
                // 节点 id，对群会话是另一个键，结果是"看着消息却还挂着红点"。
                val openChat: (String) -> Unit = { convId ->
                    chatUnreadOnEntry = state.unreadByPeer[convId] ?: 0
                    nav = nav.openSession(convId)
                    controller.selectConversation(convId)
                }

                // 返回键的分工（平板与手机不同源，必须说清，否则会「按两次才退出」）：
                // - 手机：详情/选中都住在 App 的 nav 里，这里接管；
                // - 平板两栏：**选中住在 DesktopShell 的本地状态**（它自己渲染右栏），App 的
                //   nav.activeSessionId 在平板只是一条残留写点（通知点击路径会写、没人清），
                //   若这里也 enabled，会先被这条看不见的状态吃掉一次返回。所以三条 nav 判据
                //   继续 `!twoPane`，平板交给壳里的 BackHandler。
                // - 例外：同事文件柜是 controller 状态、两壳共享（壳的 handler 已让位），
                //   所以下面那条**不加** `!twoPane`。
                val chatDetailOnTop = nav.inChatDetail && !twoPane
                BackKeyHandler(enabled = chatDetailOnTop) { nav = nav.leaveChatDetail() }

                // 扫码页盖住了整个页签，也必须接管返回键：它不是页签，进它之前
                // `activeSessionId` 已经被 `selectTab` 清掉，所以上面那条 `chatDetailOnTop`
                // 接不住 —— 不接管的话，用户在取景框上按返回会直接退出应用。
                BackKeyHandler(enabled = nav.scanning) { nav = nav.stopScanning() }

                // 节点详情也要接管返回键：它是节点页签的二级页（顶栏返回键回列表），
                // 按「详情类一律全屏」的统一规则整屏渲染（见 mobileContent 之前的全屏分支注释）。
                // 与上面那条同一条理由要 `!twoPane`：两栏模式下列表本来就看得见，
                // 宽屏壳自己管选中项、根本不吃 `nav.detailPeerId`。
                val peerDetailOnTop = nav.inPeerDetail && !twoPane
                BackKeyHandler(enabled = peerDetailOnTop) { nav = nav.leavePeerDetail() }

                // 同事文件柜（原型 `m-cabinet`）同样是二级页（顶栏有返回键），接管返回键先退回列表
                val peerCabinetOnTop = state.cabinet.activePeerId != null
                BackKeyHandler(enabled = peerCabinetOnTop) { controller.selectCabinetPeer(null) }

                // 设置子页与聊天详情同为二级页，按同一条规则整屏渲染。必须钉住当前
                // 页签：settingsPage 只在「设置」页签下才代表一块在看的屏 —— 若在别的
                // 页签残留了旧状态（全屏化之前用户可以带着子页状态切页签），无条件并入
                // 全屏分支就会劫持别的页签。
                val settingsSubpageOnTop = nav.selectedTabId == AppRoutes.SETTINGS && settingsPage != null

                // 工作区详情与会话详情也要接管返回键：从会话详情回到工作区详情，从工作区详情回到看板
                val agentSubpageOnTop = nav.inAgentSubpage && !twoPane
                BackKeyHandler(enabled = agentSubpageOnTop) {
                    if (nav.inAgentSessionDetail) {
                        nav = nav.leaveAgentSessionDetail()
                    } else if (nav.inAgentWorkspaceDetail) {
                        nav = nav.leaveAgentWorkspaceDetail()
                    }
                }

                // 扫码入口只在**真的画得出取景框**的平台上出现（见 `AppPlatform.qrScannerPreview`）：
                // 传 null 时弹层里那段会自己改成"这台设备扫不了，请走填地址"。以前这里按布局
                // （twoPane）判，桌面窄窗会画出按钮，点下去只得到一句"桌面版不做摄像头扫码"——
                // 那是注定失败的按钮（DesktopNodesPane 的注释也是这个意思）。
                val startScan: () -> Unit = { nav = nav.startScanning() }
                val onScanQr: (() -> Unit)? =
                    if (controller.qrScannerPreviewUi != null) startScan else null

                // 「从图片导入二维码」与取景框走同一道闸：手机端两者都有（ML Kit / CIDetector
                // 与相机栈在同一套依赖里），桌面两者都没有。分开成两个开关只会多一个
                // 可以互相矛盾的组合，不会多出任何一种真实设备。
                val importQrFromImage: (suspend () -> Boolean)? =
                    if (controller.qrScannerPreviewUi != null) controller::importQrImage else null

                var showCreateWsDialog by remember { mutableStateOf(false) }
                var showWsQrFor by remember { mutableStateOf<com.kapp.tidings.domain.model.WorkspaceUi?>(null) }
                // issue 7：某个工作区"没有绑定协同群"时的建群提示对象。
                // 与 showCreateWsDialog（「所有工作区」列表，独立入口）分开两格 —— 两者是
                // 两个不同的动作：一个是"挑一个工作区建群"，另一个是"就这个工作区建群"。
                var groupPromptFor by remember { mutableStateOf<com.kapp.tidings.domain.model.WorkspaceUi?>(null) }

                // 「中断 agent 运行」要发帧（suspend），而 ChatScreen 的 actions 是同步回调：
                // 在这里起一个壳级 scope，把 suspend 调用丢进去。选 rememberCoroutineScope 而
                // 不是 controller.scope：这条请求的寿命跟随这一屏，离开就取消。
                val chatScope = rememberCoroutineScope()

                val chatPanel: @Composable (Modifier, Boolean) -> Unit = { panelModifier, showHeader ->
                    ChatScreen(
                        data = ChatScreenState(
                            state = state,
                            peerId = nav.activeSessionId,
                            // 工作区收窄：从工作区列表点进来时非空，此时这一屏只画该工作区的会话
                            // （用户反馈「点击任何一个工作区，都是相同的会话列表」）。
                            wsId = nav.chatWorkspaceId,
                            // 用户选中的会话（导航状态里一直有它，这一屏此前没读 —— 于是头部、
                            // 切换条、选择器各自拿「第一条」当当前会话，互相不一致）。
                            activeSessionId = nav.activeAgentSessionId,
                            // task-2：进入这一刻的未读（在 openChat / 下钻入口清未读之前采下）。
                            unreadOnEntry = chatUnreadOnEntry,
                            cacheDir = controller.cacheDirectory,
                        ),
                        actions = ChatScreenActions(
                            onSend = controller::sendToConversation,
                            // 「重发」按原消息 id 重放（core 的 replayOutgoing）：换 id 重发会让
                            // 对端多落一条（真机现场同一句话两个 id）。
                            onRetryMessage = controller::retryMessage,
                            // 审批三参（含「本会话免审」）：聊天里复用看板那一行，
                            // 判据与看板同源（:core AgentApprovalAuthority），这里只转发意图。
                            onRespondChoice = controller::respondApproval,
                            // 群里那颗「中断」此前是往群里发一条 /cancel 文本（所有人可见、
                            // 对端不认就完全没中断）。现在走 PPE-1 §6.6 的 agent-cancel 帧。
                            onCancelAgentRun = { peerId, sessionId, runId ->
                                chatScope.launch {
                                    controller.cancelAgentRun(peerId, sessionId, runId, reason = "user")
                                }
                            },
                            // **从「@ 了但没有执行权」那一行提示直接申请**：
                            // 用户读到「没有执行」的那一刻就是想授权的时刻，不该让他去别处找入口。
                            onRequestAgentGrant = { agentName ->
                                nav.activeSessionId?.let { conv ->
                                    // ConvId / requestAgentGrantFromNotice 都在 com.kapp.tidings.domain 包，
                                    // 本文件也已在该包内 —— 直接调，不加全限定名。
                                    val groupId = ConvId.groupIdOf(conv)
                                    if (groupId != null) controller.requestAgentGrantFromNotice(groupId, agentName)
                                }
                            },
                            // 向上翻页：滚到历史顶端时读更早的一页（与桌面端同一条路径）。
                            // 会话键与 ChatScreenState.peerId 取同一个值。
                            onLoadOlder = {
                                nav.activeSessionId?.let { controller.loadOlderMessages(it) }
                            },
                            onOpenAgentConsole = { nav = nav.selectTab(AppRoutes.AGENTS) },
                            // 打开与 agent 的会话时，向宿主要该工作区**第一页**历史会话
                            // （PPE-1 增补 D8）。用户裁决：
                            //「正常其实用户只有在需要打开这个工作区的时候，同步下会话就好吧」
                            //
                            // offset = 0、limit = null（宿主自定）：这是首页，不要一次要全量
                            //（用户原话「也没必要一次性就加载全部会话」）。
                            //
                            // 用 controller.scope 而不是 rememberCoroutineScope：这条请求的寿命
                            // 该与控制器一致 —— 界面离开时不该把已发出的请求掐掉（宿主那边还在答，
                            // 答完的帧会正常落库）。
                            onOpenAgentHistory = { peerId, wsId ->
                                controller.scope.launch {
                                    controller.requestAgentSessionPage(peerId, wsId, offset = 0)
                                }
                            },
                            // 「切换会话」浮层点了某一条：写回导航状态里的当前会话（与工作区页
                            // 板上的同一意图走同一个入口 —— `nav.selectAgentSession`）。
                            onSelectAgentSession = { nav = nav.selectAgentSession(it) },
                            onLoadMoreAgentSessions = { peerId, wsId ->
                                controller.scope.launch {
                                    // offset = 本地已知条数：唯一的下一页起点的正确算法
                                    //（自己记计数器会在宿主零散推活动会话时对不上）。
                                    val known = controller.state.value
                                        .agentSessionsOf(peerId)
                                        .count { it.wsId == wsId }
                                    controller.requestAgentSessionPage(peerId, wsId, offset = known.toLong())
                                }
                            },
                            onDismissBanner = controller::dismissBanner,
                            onAttachFile = controller::pickAndSendFiles,
                            onPickFiles = controller::pickFiles,
                            onSendFiles = controller::sendFiles,
                            onAcceptTransfer = controller::acceptTransfer,
                            onDeclineTransfer = controller::declineTransfer,
                            onCancelTransfer = controller::cancelTransfer,
                            onResumeTransfer = controller::resumeTransfer,
                            // 长按自己的消息撤回；头部的戳一碰（仅单聊，Agent 会话不画）。
                            onRecallMessage = controller::recall,
                            // 只删本机这一条（与撤回不同：不动协议、不通知对方）。
                            onDeleteMessage = controller::deleteMessage,
                            onForwardMessage = controller::forwardMessage,
                            onNudge = controller::nudge,
                            onRequestScreenView = controller::requestScreenView,
                            onSearchInConversation = { query, filter ->
                                nav.activeSessionId?.let { convId ->
                                    controller.searchInConversation(convId, query, filter)
                                } ?: emptyList()
                            },
                            onSendPk = controller::sendPk,
                            onSendVoice = { targetId, filePath, _ -> controller.sendVoice(targetId, filePath) },
                            onRespondApproval = controller::respondApproval,
                            // 「清空本会话记录」：会话键就是 nav.activeSessionId
                            // （私聊=对方 nodeId、群=group:<groupId>），与 openChat /
                            // onSearchInConversation 用的是同一个键。只清本机，走既有的
                            // controller.clearMessages（不是设置页那条 clearAllMessages）。
                            onClearMessages = {
                                nav.activeSessionId?.let { convId -> controller.clearMessages(convId) }
                            },
                        ),
                        groupActions = ChatScreenGroupActions(
                            onAddGroupMembers = controller::addGroupMembers,
                            onRemoveGroupMember = controller::removeGroupMember,
                            onUpdateGroupMeta = controller::updateGroupMeta,
                            onLeaveGroup = { groupId ->
                                controller.leaveGroup(groupId)
                                nav = nav.leaveChatDetail()
                            },
                            // 解散同样要退出这一屏：群没了，留在群聊里就是一条死会话。
                            onDissolveGroup = { groupId ->
                                controller.dissolveGroup(groupId)
                                nav = nav.leaveChatDetail()
                            },
                        ),
                        chrome = ChatScreenChrome(
                            receivedFileActions = controller.receivedFileActions,
                            onBack = if (chatDetailOnTop) ({ nav = nav.leaveChatDetail() }) else null,
                            // 没有会话时的出口。移动端把节点页作为一个页签（selectTab），
                            // 桌面双栏里会话列表就在旁边，所以那边不传（见 DesktopConversationsPane）。
                            onFindPeers = { nav = nav.selectTab(AppRoutes.NODES) },
                            modifier = panelModifier,
                            showHeader = showHeader,
                            // 气泡比例按**外壳**定（原型 `:56` 的 82% 挂在根 token 块上，设备
                            // 渲染器继承它；`:464` 的 62% 挂在桌面外壳类 `.win` 上）。这个 lambda
                            // 只喂移动端那套壳，所以恒 82% —— 判据不是窗口多宽，推导见
                            // feature/chat/BubbleWidth.kt。
                            bubbleFraction = ContentWidths.BUBBLE_FRACTION_PHONE,
                            // 草稿按会话记：切走再回来不丢字（见 draftsByConv 的说明）。
                            initialDraft = draftOf(draftsByConv, nav.activeSessionId),
                            onDraftChange = { convId, text ->
                                draftsByConv = updateDraft(draftsByConv, convId, text)
                            },
                        ),
                    )
                }

                // 设置那一屏的内容。抽成 lambda 是因为两个壳都要用它：移动端是设置
                // 页签，平板是工具栏 overflow 打开后的整屏覆盖层 —— 平板形态的
                // [DesktopShell] 不画菜单栏，设置只有这条路进得去。
                //
                // 第二屏（[settingsPage]）是页签内部的六选一，没有自己的 AppRoute，
                // 所以返回键也得一并接管（见 settingsPage 处的注释）。
                val settingsContent: @Composable (Modifier) -> Unit = { settingsModifier ->
                    // 目录占用：只在「数据与存储」子页真的打开时数一次，而且放到后台线程 ——
                    // 聊天记录多的机器上这是几千次 stat，摆到主线程就是一次肉眼可见的卡顿。
                    // 还没数完时保持 null，界面会显示「正在统计…」，而不是编一个 0 B。
                    // （不是"数不出来"：directoryUsageBytes 三平台都只返回 Long，不会失败。）
                    val dataPageOpen = settingsPage == SETTINGS_PAGE_DATA
                    val storageUsage by produceState<Pair<Long, Long>?>(null, dataPageOpen) {
                        if (!dataPageOpen) return@produceState
                        value = withContext(Dispatchers.Default) {
                            directoryUsageBytes(controller.dataDirectory) to
                                directoryUsageBytes(controller.cacheDirectory)
                        }
                    }
                    // 续传残片：和目录占用同一套办法（只在子页打开时数一次、放后台线程）。
                    // 清理按钮按完要重新数一遍，所以拿一个 epoch 当 produceState 的键 ——
                    // 数完再 ++，而不是先 ++，否则读到的还是删之前的那份。
                    val leftoverEpoch = remember { mutableIntStateOf(0) }
                    val leftoverScope = rememberCoroutineScope()
                    val leftoverParts by produceState<List<PartFile>?>(
                        null,
                        dataPageOpen,
                        leftoverEpoch.intValue,
                    ) {
                        if (!dataPageOpen) return@produceState
                        value = withContext(Dispatchers.Default) { controller.leftoverParts() }
                    }
                    // 子页（settingsPage != null）此前**没有横幅宿主**：失败与回执都走
                    // state.banner，而宿主只在首页 SettingsHomeScreen 里 —— 于是在「连接」
                    // 页点保存，端口非法/网段非法/选图超限全都静默（CIDR「假成功」从这条
                    // 路复活）。这里给所有子页统一挂一个宿主；首页仍用它自己那一份，
                    // 两处互斥（同一时刻只画一棵子树），不会双画。
                    Column(settingsModifier) {
                        if (settingsPage != null) {
                            state.banner?.let { banner ->
                                AppBanner(banner = banner, onDismiss = controller::dismissBanner)
                            }
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (settingsPage) {
                        // 通知与能力声明这五个开关以前只改屏上的本地状态，没有后端 ——
                        // 也就是"拨了不改变行为"（审计 P1-7）。现在都落到 RelaySettings：
                        // 通知三条只写盘（策略在收到消息那一刻现读），能力两条会
                        // 递增 profileRev 并重启节点（对端据此重新认识我们的能力）。
                        SETTINGS_PAGE_NOTIFY -> SettingsNotifySubpage(
                            modifier = Modifier.fillMaxSize(),
                            onBack = { settingsPage = null },
                            notificationsEnabled = state.settings.notifyAgentMessages,
                            onNotificationsEnabledChange = controller::updateNotifyAgentMessages,
                            mentionOnlyEnabled = state.settings.notifyMentionOnly,
                            onMentionOnlyEnabledChange = controller::updateNotifyMentionOnly,
                            quietHoursEnabled = state.settings.notifyQuietHours,
                            onQuietHoursEnabledChange = controller::updateNotifyQuietHours,
                        )

                        SETTINGS_PAGE_APPEAR -> SettingsAppearSubpage(
                            modifier = Modifier.fillMaxSize(),
                            onBack = { settingsPage = null },
                            themeMode = themeMode.wire,
                            onThemeModeChange = { wire ->
                                controller.updateThemeMode(wire)
                            },
                        )

                        SETTINGS_PAGE_CAPS -> SettingsCapsSubpage(
                            modifier = Modifier.fillMaxSize(),
                            onBack = { settingsPage = null },
                            textCapabilityEnabled = state.settings.waitFrameCapability,
                            onTextCapabilityEnabledChange = controller::updateWaitFrameCapability,
                            recordCapabilityEnabled = state.settings.recordMessageCapability,
                            onRecordCapabilityEnabledChange = controller::updateRecordMessageCapability,
                        )

                        SETTINGS_PAGE_DATA -> SettingsDataSubpage(
                            modifier = Modifier.fillMaxSize(),
                            dataDirectory = controller.dataDirectory,
                            cacheDirectory = controller.cacheDirectory,
                            dataUsageBytes = storageUsage?.first,
                            cacheUsageBytes = storageUsage?.second,
                            leftoverPartCount = leftoverParts?.size,
                            leftoverPartBytes = leftoverParts?.sumOf { it.size },
                            onClearLeftoverParts = {
                                leftoverScope.launch {
                                    withContext(Dispatchers.Default) { controller.clearLeftoverParts() }
                                    // 数完再 ++：先 ++ 的话重数读到的还是删之前那份。
                                    leftoverEpoch.intValue++
                                }
                            },
                            onBack = { settingsPage = null },
                            persistMessagesEnabled = controller.persistMessages,
                            onPersistMessagesEnabledChange = controller::updatePersistMessages,
                            experimentalLuciolaEnabled = state.settings.experimentalLuciola,
                            onExperimentalLuciolaChange = controller::updateExperimentalLuciola,
                            // 「跨网通道」开关已移除（长连接恒开）。用户的心智是"哪张网都能
                            // 无感连上"，不是"我要不要开一条长连接"；而关掉它反而是不拨号。
                            onClearHistory = controller::clearAllMessages,
                            // 备份与恢复（L1）：选文件走三平台共用的 filePicker 契约
                            // （Android SAF 桥 / iOS 文稿选择器 / 桌面对话框），读回字节
                            // 交给领域层；取消选文件返回 null，页面据此静默终止。
                            onExportRecoveryBundle = controller::exportRecoveryBundle,
                            onPickBackupFile = {
                                controller.platform.filePicker.pick()?.let { readFileBytes(it.path) }
                            },
                            onImportRecoveryBundle = controller::importRecoveryBundle,
                        )

                        SETTINGS_PAGE_DIAG -> SettingsDiagSubpage(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            onBack = { settingsPage = null },
                            // 诊断包导出：打包/分享都在这条 suspend 里，页面自己起协程并显示结果
                            // （成功报文件名与大小，失败报原因 —— 不给一颗点下去没反应的按钮）。
                            onExportDiagnostics = controller::exportDiagnosticsPackage,
                            // 输入法自检的入口（此前是孤儿页，见 SETTINGS_PAGE_IME 的 KDoc）。
                            onOpenImeSmoke = { settingsPage = SETTINGS_PAGE_IME },
                        )

                        // 输入法自检：此前是孤儿页（见 SETTINGS_PAGE_IME 的 KDoc）。
                        // 它在 commonMain 里，手机与桌面同一份实现。
                        SETTINGS_PAGE_IME -> ImeSmokePane(
                            modifier = Modifier.fillMaxSize(),
                            onBack = { settingsPage = null },
                        )

                        SETTINGS_PAGE_UPDATE -> SettingsUpdateSubpage(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            onBack = { settingsPage = null },
                            appVersion = controller.version,
                            onCheckForUpdates = controller::checkForUpdates,
                            onOpenUpdate = controller::openUpdateUrl,
                        )

                        // 「上传头像」独立页（台账 `m-set-avatar`）。两个入口都落到这一支：
                        // ① 设置首页「外观与能力」组的「头像」行（`SettingsHomeScreen`）；
                        // ② 资料页头像分段控件的第三档 —— 原型 `avSeg()` 的第三档
                        // `avOpenUpload(){ go('m-set-avatar'); }`（`prototype/relay.html:2122`）。
                        // 在接上之前，这一支有 when 分支、却**全仓没有一个写入点**：
                        // 第三档被接成 `controller::pickAvatar`（选图，不换页），
                        // 于是 `SetAvatarPage` 在生产里不可达，成了孤儿页。
                        SETTINGS_PAGE_AVATAR -> SetAvatarPage(
                            state = state,
                            pickedAvatarBytes = state.pickedAvatarBytes,
                            onPickFromAlbum = controller::pickAvatar,
                            onRemovePicked = controller::removePickedAvatar,
                            onSaveProfile = controller::saveProfile,
                            onBack = { settingsPage = null },
                            modifier = Modifier.fillMaxSize(),
                        )

                        else -> SettingsScreen(
                            state = state,
                            dataDirectory = controller.dataDirectory,
                            cacheDirectory = controller.cacheDirectory,
                            onSaveProfile = controller::saveProfile,
                            onSavePorts = controller::savePorts,
                            onRestart = controller::requestRestart,
                            onAddScanRange = controller::addScanRange,
                            onRemoveScanRange = controller::removeScanRange,
                            onTriggerCidrScan = controller::triggerCidrScan,
                            onCancelCidrScan = controller::cancelCidrScan,
                            // 「后台保持连接」（task-15）：能力在 Android 上为 true，桌面/iOS
                            // 为 false ⇒ 设置页不画那个开关（能力不存在就不画入口）。
                            keepAliveSupported = controller.backgroundConnectionSupported,
                            keepAliveEnabled = state.settings.keepConnectionAlive,
                            onKeepAliveChange = controller::updateKeepConnectionAlive,
                            onDismissBanner = controller::dismissBanner,
                            onClearNote = controller::clearSettingsNote,
                            onOpenPage = { settingsPage = it },
                            // 第三档的出口是**换页**，不是选图：接成 `controller::pickAvatar`
                            // 会让这一档点了只弹文件框、永远到不了 SETTINGS_PAGE_AVATAR。
                            onOpenAvatarPage = { settingsPage = SETTINGS_PAGE_AVATAR },
                            onRemovePickedAvatar = controller::removePickedAvatar,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                        }
                    }
                }

                // 移动端那套壳的内容。抽成局部 lambda 而不是内联在 [CompactScaffold] 里，
                // 是因为宽屏走的是完全另一套壳（下面 [DesktopShell]），两者只需要共用这
                // 一份「选中页签 → 画哪个 Screen」的映射，抽出来就不必把宽屏分支塞进
                // 移动分支的 when 里 —— 那样移动端会多出一层永远进不去的死分支。
                val mobileContent: @Composable (Modifier) -> Unit = { contentModifier ->
                    when {
                        // 手机端全局搜索：**整屏**替换会话列表（原型 `m-gsearch` /
                        // `m-gsearch-empty`）。原型 `screenGSearch` 的顶栏那一行装的是输入框
                        // 而不是标题、底导仍在 —— 它换掉的是会话列表那**一整屏**，不是盖一层。
                        //
                        // 放在 `chatDetailOnTop` **之前**：搜索页是从列表进的，两者不该同时成立；
                        // 顺序写反的后果是"从命中点进会话、再返回"会落回搜索页而不是列表。
                        globalSearchOpen -> GlobalSearchScreen(
                            // 用 lambda 而不是方法引用：`searchGlobal` 有两个重载（无 scope / 带 scope），
                            // 方法引用在重载下推断不出参数类型。
                            onSearch = { q, scope -> controller.searchGlobal(q, scope) },
                            onBack = { globalSearchOpen = false },
                            onOpenChat = { convId ->
                                globalSearchOpen = false
                                openChat(convId)
                            },
                            // 文件在协议里属文件柜那一套（原型 `gscFile` 的落点是手机 `m-cabinet`）：
                            // 但命中行带的是**会话键**（`GlobalSearchFileHit.convId`），文件柜那一屏
                            // 根本没有「按消息定位」的入口 —— 过去只切到文件柜页签、把会话键扔掉，
                            // 用户点了一条文件命中，看到的却是文件柜列表，什么都没定位到。
                            // 现在打开对应会话（会话内定位到具体消息要 ChatScreen 的跳转接缝
                            // 暴露到组合根，那一步不在本轮范围 —— 先做到「打开对应会话」）。
                            onOpenFile = { convId ->
                                globalSearchOpen = false
                                openChat(convId)
                            },
                            modifier = contentModifier,
                        )

                        // 紧凑布局下的聊天详情：整屏，顶栏带返回。
                        chatDetailOnTop -> chatPanel(contentModifier, true)

                        // 页签内容包在 SaveableStateProvider 里（见上面 tabStateHolder 的
                        // 注释）：切页签 / 被聊天详情、扫码、搜索盖走再回来，各屏自己的
                        // rememberSaveable（滚动位置等）从 holder 原样恢复，而不是归零。
                        else -> tabStateHolder.SaveableStateProvider(key = nav.selectedTabId) {
                            Row(contentModifier.fillMaxSize()) {
                                when (nav.selectedTabId) {
                                    AppRoutes.CABINET -> CabinetScreen(
                                        state = state,
                                        onSetShareRoot = controller::setShareRoot,
                                        onSetShareMode = controller::setShareMode,
                                        onRemoveShareGrant = controller::removeShareGrant,
                                        onSelectPeer = controller::selectCabinetPeer,
                                        onLoadPath = controller::loadCabinetPath,
                                        // 协议已支持 offset/truncated：有更多才画「加载更多」（见 PeerCabinetView）。
                                        onLoadMore = controller::loadMoreCabinetEntries,
                                        onToggleSelection = controller::toggleCabinetSelection,
                                        onDownloadEntries = controller::downloadCabinetEntries,
                                        onPickShareRoot = controller::pickShareRoot,
                                        onUploadToCabinet = controller::uploadToCabinet,
                                        // 文件柜屏自己挂了 AppBanner（与 ConversationsScreen 同一模式），
                                        // banner 的关闭动作从组合根接进去。
                                        onDismissBanner = controller::dismissBanner,
                                        // 「最近上传」的时间列必须有一个真实基准：以前这一行不传
                                        // now，CabinetScreen 的默认值是 0，relativeTime(ts, 0) 的
                                        // delta 恒为负、恒落进"刚刚"那一档 —— 界面在说假话。
                                        // 不传也能拿到默认的 nowMillis()，这里显式写出来是为了让
                                        // 组合根这一层能一眼看出它读的是墙上时钟。
                                        now = nowMillis(),
                                        modifier = Modifier.weight(1f),
                                    )

                                    AppRoutes.NODES -> NodesScreen(
                                        state = state,
                                        onAddPeer = controller::addManualPeer,
                                        // 带名字那条：手机壳能收下"给手动节点起个本机显示名"，
                                        // 于是弹层里多一个「名字（可选）」框；名字只存本机、不进 wire。
                                        onAddPeerNamed = controller::addNamedManualPeer,
                                        onRemovePeer = controller::removeNode,
                                        // 「重连」一条已保存但当前不通的手动地址：mDNS 判重会把
                                        // 同址的广播行滤掉，只剩红色「没有回应」那行 —— 没有这个
                                        // 入口，用户只能删了重加或重启应用（review R2-#1）。
                                        onReconnectPeer = controller::reconnectManualPeer,
                                        onCleanupUnreachable = controller::removeUnreachableManualPeers,
                                        onRetryStart = controller::requestRestart,
                                        // 手机端接上了：点「打开相机扫码」会盖一整屏取景框
                                        // （见 ScanScreen）。`onScanQr` 为 null = 这个平台画不出
                                        // 取景框（桌面/平板没有相机栈），按钮不画，弹层里如实写
                                        // "请切到填地址"。
                                        onScanQr = onScanQr,
                                        // 「从图片导入二维码」与取景框同一道闸（同一个
                                        // `qrScannerPreview != null` 判据）：手机端有，
                                        // 桌面/平板两个一起是 null，弹层里两颗按钮一起不画。
                                        // 授权：Lead 批准 W2 在本文件加这一行（task-16）。
                                        onImportQrFromImage = importQrFromImage,
                                        onDismissBanner = controller::dismissBanner,
                                        // 行尾 ⋯ 菜单的「发消息」：节点是"对端"，会话是"跟它的对话"，
                                        // 点一行就该进会话 —— 走的是与 Agent 控制台同一条 `openChat`。
                                        onOpenChat = openChat,
                                        // 原型把「点一行」的含义改掉了：不再直冲会话，
                                        // 而是进这个节点的详情（`nodeRow` 的
                                        // `onclick="selNode='…';go('m-nodedetail')"`，`prototype/relay.html:3287`）。
                                        // 详情是节点页签内部的一个分支，所以"正在看谁"记在 `nav` 上、
                                        // 由 `NodesScreen` 自己决定画列表还是画详情 —— 组合根只负责
                                        // 把导航状态与三个回调接进去。
                                        detailPeerId = nav.detailPeerId,
                                        onOpenPeerDetail = { peerId -> nav = nav.openPeerDetail(peerId) },
                                        onLeavePeerDetail = { nav = nav.leavePeerDetail() },
                                        // 备注是本机给人起的名，不进协议（见 `PeerUi.remark` 的 KDoc）：
                                        // 落盘在设置里，所以走 `controller` 而不是导航。
                                        onSetPeerRemark = controller::setPeerRemark,
                                        modifier = Modifier.weight(1f),
                                    )

                                    // ## Agent 页签也要有一条 AppBanner
                                    //
                                    // 建群失败（群名为空 / 超长 / 没有其他成员）时 controller 发的就是
                                    // `state.banner` —— 本分支不挂它，用户点「创建」就只是**没反应**：
                                    // 弹窗关了、群没建出来、屏幕上一个字都没有。ConversationsScreen 的
                                    // 顶栏下有现成的同款接法，这里照同一模式补上（横幅画在 Agent 屏幕
                                    // 自己的顶栏之上 —— banner 槽在屏内，本文件够不到，组合根能给的
                                    // 位置就是这里）。
                                    AppRoutes.AGENTS -> Column(Modifier.weight(1f)) {
                                        state.banner?.let {
                                            AppBanner(
                                                banner = it,
                                                onDismiss = controller::dismissBanner,
                                                modifier = Modifier.padding(
                                                    horizontal = LocalAppSpacing.current.lg,
                                                    vertical = LocalAppSpacing.current.sm,
                                                ),
                                            )
                                        }
                                        AgentConsoleScreen(
                                            state = state,
                                            // 撤销本会话免审（撤销是一次显式动作，与逐条审批入口分开）。
                                            onRevokeSessionApproval = controller::revokeSessionApproval,
                                            // **不能用** `nav.detailPeerId`：那是**节点页**的详情（普通节点
                                            // 也有值）。用户先在节点页点开一台普通设备、再切到 Agents 页签时，
                                            // 这个 id 会流进来；它不在 `agentPeers` 里时，`AgentConsoleScreen` 的
                                            // 回落会换成「第一个 agent」⇒ **用户点 A 却看到 B 的工作区**
                                            // （AGENTS.md §2.4：一个事实只有一个来源，两个页签的「详情」是两件事）。
                                            selectedAgentId = selectedAgentId,
                                            onSelectAgent = { selectedAgentId = it },
                                            selectedAgentSessionId = nav.activeAgentSessionId,
                                            onSelectAgentSession = { nav = nav.selectAgentSession(it) },
                                            inDetail = nav.inAgentSubpage,
                                            detailWsId = nav.detailWorkspaceId,
                                            detailSessionId = nav.detailAgentSessionId,
                                            onOpenWorkspaceDetail = { peerId, wsId ->
                                                selectedAgentId = peerId
                                                nav = nav.openAgentWorkspaceDetail(peerId, wsId)
                                            },
                                            onLeaveWorkspaceDetail = {
                                                nav = nav.leaveAgentWorkspaceDetail()
                                            },
                                            onOpenSessionDetail = { peerId, sessionId ->
                                                selectedAgentId = peerId
                                                nav = nav.openAgentSessionDetail(sessionId)
                                            },
                                            onLeaveSessionDetail = {
                                                nav = nav.leaveAgentSessionDetail()
                                            },
                                            onBackToBoard = {
                                                nav = nav.leaveAgentWorkspaceDetail()
                                            },
                                            onOpenChat = { peerId, wsId ->
                                                selectedAgentId = peerId
                                                val ws = state.agentWorkspaces.values.firstOrNull { it.wsId == wsId }
                                                val boundGroup = state.settings.groups.firstOrNull { g ->
                                                    // 跨模块读 public 属性要先落成局部量：smart cast 只在同模块内成立。
                                                    val wsPath = ws?.wsPath
                                                    val wsTitle = ws?.wsTitle
                                                    val matchPath = !wsPath.isNullOrBlank() && g.description?.contains(wsPath) == true
                                                    val matchTitle = !wsTitle.isNullOrBlank() && g.name.contains(wsTitle)
                                                    matchPath || matchTitle
                                                }
                                                if (boundGroup != null) {
                                                    openChat(com.kapp.tidings.core.protocol.ConvId.group(boundGroup.groupId))
                                                } else if (ws != null) {
                                                    // issue 7：没有绑定群时**针对这一个工作区**提示建群并进入。
                                                    // 桌面端此前弹的是「所有工作区」列表（用户要处理的是当前这一行，
                                                    // 那张列表跟他点的那一行无关）；手机端此前直接进聊天，等于
                                                    // 根本没给建群这条路。两条壳现在走同一个对话框。
                                                    groupPromptFor = ws
                                                } else {
                                                    // 没有 wsId 的 Agent 行：这台设备上没有"某个工作区"这回事，
                                                    // 直接进这个节点的会话（与桌面壳同一条）。
                                                    nav = wsId?.let { w -> nav.openWorkspaceChat(peerId, w) }
                                                        ?: nav.openSession(peerId)
                                                    chatUnreadOnEntry = state.unreadByPeer[peerId] ?: 0
                                                    controller.selectConversation(peerId)
                                                }
                                            },
                                            onLoadWorkspaceSessions = { peerId, wsId ->
                                                controller.requestAgentSessionPage(peerId, wsId, offset = 0)
                                            },
                                            onFindNodes = { nav = nav.selectTab(AppRoutes.NODES) },
                                            onRespondApproval = controller::respondApproval,
                                            // 三参处置（「本会话免审」）：controller 有现成三参重载，
                                            // 此前只接了两参 —— 免审按钮与徽标在组件里备好了却永远
                                            // 不出现（issue「本次免审全链路不可达」）。
                                            onRespondChoice = controller::respondApproval,
                                            onCancelTask = controller::cancelAgentTask,
                                            // task-10：手机是「客户端」，申请授权只能由组合根接线 ——
                                            // 接口留在 feature/agents，能力从哪儿来在这里给。
                                            onRequestGrant = controller::sendAgentGrantRequest,
                                            onRefresh = { controller.refreshPeers() },
                                            // **不再按布局分支**：此前只在两栏布局给回调，于是 compact
                                            // （手机竖屏）的工作区看板连「关联工作区建群」这颗按钮都不画
                                            // —— 用户报的「手机上没法建工作区群」就是这个。对话框本体
                                            // 在两种布局下是同一个，窄屏上它自己会收成整幅宽弹层。
                                            onCreateWorkspaceGroup = { showCreateWsDialog = true },
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                        )
                                    }

                                    AppRoutes.SETTINGS -> settingsContent(Modifier.weight(1f))

                                    else -> ConversationsScreen(
                                        state = state,
                                        listState = convListState,
                                        onOpenChat = openChat,
                                        // 写到一半切回列表时，那一行要能看出「草稿：…」——
                                        // 草稿表住在壳里（draftsByConv），域层不掺和；
                                        // 桌面壳同名注入见 DesktopShell。
                                        draftPreviewOf = { convId -> draftsByConv[convId]?.takeIf { it.isNotBlank() } },
                                        onGoToPairing = { nav = nav.selectTab(AppRoutes.NODES) },
                                        onDismissBanner = controller::dismissBanner,
                                        onCreateGroup = controller::createGroup,
                                        // 工作区建群：compact 布局的唯一入口（两栏布局另有侧栏那颗）。
                                        onCreateWorkspaceGroup = { showCreateWsDialog = true },
                                        // 顶栏那颗放大镜。原型 `prototype/relay.html:2803`/`:2888`
                                        // 的 `<button class="ibtn" aria-label="全局搜索"
                                        // onclick="go('m-gsearch')">` 就在 ⋮ 左边，且是移动端
                                        // **唯一**的搜索入口（`screenGSearch` 的注释：
                                        // 列表上方那条只管标题与预览的框已经删掉）。
                                        onSearchGlobal = { globalSearchOpen = true },
                                        // 「全部标为已读」：原型 `screenHomeMore` 的第 5 项
                                        // （`prototype/relay.html:2861`，菜单共 6 项 + 2 条分隔线）。
                                        // 走 `controller.markAllRead()` 而不是
                                        // `conversations.forEach { selectConversation(it.convId) }`：
                                        // 后者效果对（`selectConversation` 顺手清零 unread），
                                        // 但那是**借了别人的副作用** ——
                                        // 一个叫 `selectConversation` 的方法被当"批量标已读"用。
                                        // 裁决：给它一个说得出名字的入口。
                                        // （字段名是 `convId` 不是 `peerId`，理由见 `ConversationUi.convId`
                                        // 的 KDoc；`App.kt` 是组合根，一处调用补一行都要 Lead 点名授权。）
                                        onMarkAllRead = controller::markAllRead,
                                        // 长按会话行的三个动作。都是纯本机行为：置顶/静音只改设置，
                                        // 删除只清本机消息与标记（不动协议、不通知对方）。
                                        onTogglePin = controller::togglePinnedConv,
                                        onToggleMute = controller::toggleMutedConv,
                                        onDeleteConversation = controller::deleteConversation,
                                        // 群会话的「删除并退出群聊」需要它（见 DeleteConversationConfirmDialog）。
                                        onLeaveGroup = controller::leaveGroup,
                                        // ## 手机端**不画**「工作区」段（用户裁决：对齐桌面）
                                        //
                                        // 这两颗 lambda 一起决定那一段画不画（见 `ConversationsScreen`
                                        // 的 `showWorkspaces`）。这里**都不传**，于是：
                                        //   · 会话列表只剩「会话」——群、同事、agent 节点的对话；
                                        //   · agent 的工作区行不再混进来（真机上它们是
                                        //     `care-in-china · Agent · dsh-… · 27 个会话` 这种行，
                                        //     而**桌面端的会话列表从来没有这一段** —— 两端不一致本身就是缺陷）。
                                        //
                                        // ## 工作区内容去哪了：一个字都没少
                                        //
                                        // 底部 **Agents 页签**就是「全部工作区」的清单页，而且用的是
                                        // **同一个** `state.inboxWorkspaceUnits()` 派生（`limit = Int.MAX_VALUE`
                                        // ⇒ 比这一段的裁剪版更全）。从那里点进去仍然是完整的工作区详情 /
                                        // 聊天 / 直通码 / 权限管理。
                                        //
                                        // 为什么不留一段「只读摘要」：那正是用户看腻的那几行。列表的职责是
                                        // 「谁刚跟我说过话」，工作区的职责是「我在哪几个仓库里干活」——
                                        // 两件事各自有一屏，混在一屏只会让两边都不清楚。
                                        //
                                        // 传给 ConversationsScreen 的那两个参数保持 `null`（默认值即 null），
                                        // 段、段头、以及副标里的「N 个工作区」会**一起**消失（它们同源，见
                                        // `showWorkspaces` 的 KDoc：判据只有这一处）。
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                // 够宽的窗口（平板 / 大屏手机横屏）走桌面壳，够窄的走移动壳。
                // 两套壳不是嵌套关系：桌面壳自带视图切换、搜索、节点/文件面板
                // 和 Agent 控制台，外面再套一层 [CompactScaffold] 会多出一条
                // 永远选不中的底部导航。桌面窗口本来就直接起 [DesktopShell]，
                // 这条分支只负责 [RelayApp] 里够宽的那些窗口。
                //
                // chromeMode = Tablet：平板不是桌面，菜单栏 / 标题栏 / 状态栏
                // 这些桌面 chrome 不该出现，它们原本独占的动作改由工具栏
                // overflow 承接（见 [DesktopShell]）。
                if (twoPane) {
                    // 平板上真能做的动作。四个**桌面专有**动作（诊断包导出 / 系统
                    // 下载目录 / 多窗口 / 退出应用）保持 null：平板没有这些能力，
                    // 壳会因此**不画**对应菜单项，而不是画一个点了没反应的按钮。
                    val tabletActions = DesktopShellActions(
                        onOpenSettings = { tabletSettingsOpen = true },
                        onToggleTheme = {
                            // 平板的快捷切换是二选一（浅/深），但落盘走的是同一条路：
                            // 切完立刻记住，否则下次启动又跟着系统跑。
                            val next = if (themeMode == ThemeMode.Dark) {
                                ThemeMode.Light
                            } else {
                                ThemeMode.Dark
                            }
                            controller.updateThemeMode(next.wire)
                        },
                        onRefresh = controller::handshake,
                        onImportFiles = controller::pickAndSendFiles,
                        onCopyPairingLink = { link -> clipboard.setText(AnnotatedString(link)) },
                        onCopyNodeId = { nodeId -> clipboard.setText(AnnotatedString(nodeId)) },
                    )
                    Box(Modifier.fillMaxSize()) {
                        DesktopShell(
                            state = state,
                            controller = controller,
                            windowWidthDp = windowWidthDp,
                            windowHeightDp = windowHeightDp,
                            // 传已经解析过的 dark：themeMode 可能把系统深浅反过来，
                            // 传原始 darkTheme 会和 AppTheme 里正在用的主题不一致。
                            darkTheme = dark,
                            chromeMode = DesktopChromeMode.Tablet,
                            // 戳一碰的"定位到该会话"（协议 §8）。宽屏壳自己管选中项，
                            // 不吃 nav，所以这份请求也交给它一份。
                            focusPeerId = focusRequest,
                            actions = tabletActions,
                        )
                        // 设置覆盖层。平板形态没有独立设置窗口可开，就地盖一整屏，
                        // 返回键由上面 tabletSettingsOpen 那处的 BackKeyHandler 接管。
                        if (tabletSettingsOpen) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                settingsContent(Modifier.fillMaxSize())
                            }
                        }
                    }
                } else if (nav.scanning) {
                    // 扫码页是**整屏**：取景框要尽量大，底下挂一条永远选不中的导航栏
                    // 既挤地方又误导（这不是一个可以"停在里面"的页签，见 AppNavState.scanning）。
                    // 放在两栏分支之后：桌面与平板没有相机，那条路进不来。
                    ScanScreen(
                        state = state,
                        preview = controller.qrScannerPreviewUi,
                        scan = controller::scanQrCode,
                        onPaired = { nav = nav.stopScanning() },
                        onCancel = { nav = nav.stopScanning() },
                        onDismissBanner = controller::dismissBanner,
                        modifier = Modifier.fillMaxSize(),
                        importFromImage = importQrFromImage,
                        // 相机被拒后的第二颗按钮：只有真的存在系统设置页的平台才画（桌面没有）。
                        onOpenAppSettings = if (controller.platform.canOpenAppSettings) {
                            { controller.platform.openAppSettings() }
                        } else {
                            null
                        },
                    )
                } else if (
                    globalSearchOpen ||
                    chatDetailOnTop ||
                    agentSubpageOnTop ||
                    settingsSubpageOnTop ||
                    peerDetailOnTop ||
                    peerCabinetOnTop
                ) {
                    // 二级页一律**整屏全屏**（m-chat、m-workspacedetail 与 m-session-detail
                    // 本就标记 full:true）。规则只有一条：**tab 页是唯一挂底导的层**，详情类
                    // 全屏、退路是各自的顶栏返回或系统返回键。
                    // 旧规则是两套并存：聊天详情全屏，而全局搜索/设置子页/节点详情/同事
                    // 文件柜底下仍挂着一级页的底导 —— 同为「详情」行为却分裂，二级页里
                    // 底导既误触又暗示「当前屏是页签」，与它的实际层级不符。
                    mobileContent(Modifier.fillMaxSize())
                } else {
                    CompactScaffold(
                        selectedScreenId = nav.selectedTabId,
                        navItems = AppNavItems,
                        onScreenSelected = { nav = nav.selectTab(it) },
                        badges = navBadges(state),
                        // 横屏（宽 > 高）时底导换成左侧竖导：横屏最贵的是高度，
                        // 底导连安全区要吃掉约 60dp，而这一档的高度本来就只有 393dp 上下。
                        // 原型对此的立场是 `nav:'rail'`（prototype/relay.html:2429）。
                        landscape = layoutMode == AppLayoutMode.CompactLandscape,
                    ) { contentModifier -> mobileContent(contentModifier) }
                }

                // 全局屏幕协助会话弹窗/查看器
                com.kapp.tidings.ui.components.RemoteViewSessionDialog(
                    state = state.screenSession,
                    onAccept = { controller.respondScreenView(true) },
                    onReject = { controller.respondScreenView(false) },
                    onStop = { controller.stopScreenView() },
                    onSetMode = { controller.setScreenViewMode(it) },
                )

                if (showCreateWsDialog) {
                    // 默认群名**按真实标题直接格式化**：`stringResource` 只能在组合里调，
                    // 而点「创建」发生在回调里（ws 那时才确定）—— 所以把每个可能被点的
                    // 工作区的默认群名都在组合期算好，回调里只查表。曾经这里是
                    // `stringResource(res, "%s")` 拿回裸模式再 `.replace("%s", …)`：
                    // 用占位符当参数、再手写一遍替换 —— 与 `stringResource(res, title)`
                    // 等价却多出两段约定（`%s` 字面量 + replace），纯黑科技。
                    val defaultGroupNames: Map<String, String> = state.agentWorkspaces.values
                        .associate { ws ->
                            ws.wsId to stringResource(
                                Res.string.workspace_group_default_name,
                                ws.wsTitle ?: ws.wsId,
                            )
                        }
                    com.kapp.tidings.ui.components.WorkspaceGroupCreateDialog(
                        workspaces = state.agentWorkspaces.values.toList(),
                        groups = state.settings.groups,
                        onDismiss = { showCreateWsDialog = false },
                        onOpenChat = { groupId ->
                            showCreateWsDialog = false
                            openChat(com.kapp.tidings.core.protocol.ConvId.group(groupId))
                        },
                        onShowQr = { ws ->
                            showWsQrFor = ws
                        },
                        onQuickCreate = { ws, _ ->
                            showCreateWsDialog = false
                            val name = defaultGroupNames[ws.wsId] ?: (ws.wsTitle ?: ws.wsId)
                            val members = state.peers.filter { it.online }.map { it.nodeId }
                            // 工作区群走专用入口：允许此刻一个其它设备都不在线，并把该工作区的
                            // agent 声明进群（普通 createGroup 会判「至少再选一个人」而拒收 ——
                            // 那张对话框里根本没有可勾的人）。
                            val gid = controller.createWorkspaceGroup(name, ws.wsId, members, ws.wsPath ?: ws.wsId)
                            if (gid != null) {
                                openChat(com.kapp.tidings.core.protocol.ConvId.group(gid))
                            }
                        },
                        onCreateNew = { path, _, groupName, _ ->
                            showCreateWsDialog = false
                            val members = state.peers.filter { it.online }.map { it.nodeId }
                            // 「关联本地全新目录」分支：还没有工作区行（wsId = null），
                            // 群先建出来，设备随后用分享码 / 群信息加进来。
                            val gid = controller.createWorkspaceGroup(groupName, null, members, path)
                            if (gid != null) {
                                openChat(com.kapp.tidings.core.protocol.ConvId.group(gid))
                            }
                        },
                    )
                }

                // issue 7：某个工作区没有绑定协同群时的建群提示。
                // 群名/成员与上面「快速建群」同一口径（默认名 + 当前在线设备）。
                // 默认群名在这里**按真实标题直接格式化**（ws 在组合期就是已知的，
                // 不必再用占位符当参数的那套黑科技，理由见上面 showCreateWsDialog 处）。
                groupPromptFor?.let { ws ->
                    val defaultGroupName = stringResource(
                        Res.string.workspace_group_default_name,
                        ws.wsTitle ?: ws.wsId,
                    )
                    com.kapp.tidings.ui.components.WorkspaceGroupPromptDialog(
                        workspace = ws,
                        onDismiss = { groupPromptFor = null },
                        onCreate = {
                            groupPromptFor = null
                            val name = defaultGroupName
                            val members = state.peers.filter { it.online }.map { it.nodeId }
                            val gid = controller.createWorkspaceGroup(name, ws.wsId, members, ws.wsPath ?: ws.wsId)
                            if (gid != null) {
                                openChat(com.kapp.tidings.core.protocol.ConvId.group(gid))
                            }
                        },
                        onOpenWithoutGroup = {
                            groupPromptFor = null
                            nav = nav.openWorkspaceChat(ws.peerId, ws.wsId)
                            chatUnreadOnEntry = state.unreadByPeer[ws.peerId] ?: 0
                            controller.selectConversation(ws.peerId)
                        },
                    )
                }

                showWsQrFor?.let { ws ->
                    com.kapp.tidings.ui.components.WorkspaceQrDialog(
                        wsTitle = ws.wsTitle ?: ws.wsId,
                        wsId = ws.wsId,
                        wsPath = ws.wsPath,
                        host = state.network.localAddresses.firstOrNull()
                            ?: state.peers.firstOrNull { it.online }?.let { it.host.ifBlank { it.ip } },
                        port = state.network.boundUdpPort.takeIf { it > 0 } ?: state.settings.udpPort,
                        // 全部候选：跨网络的手机才能靠这张码找到本机（公网 v6 在二维码预算内）。
                        // 用「宣告」那份（含 Tailscale/ZeroTier 隧道地址）：本地那份给广播
                        // 与同网段判断用，刻意窄。出码用它会让走隧道的手机永远连不上。
                        addresses = state.network.advertisedAddresses,
                        nodeId = state.selfNodeId,
                        // task-86：直通码也必须带 pin，否则手机建不出公网 TLS 目标。
                        tlsPin = state.network.selfTlsPin,
                        // 实验 luciola：直通码也带同一段（与 pin 互斥）。
                        luciola = state.network.luciolaQr,
                        // 带昵称：扫完立刻是真名，不是 node-a1b2c3d4。
                        nick = state.settings.nick,
                        onDismiss = { showWsQrFor = null },
                    )
                }
            }
        }
    }
}

/**
 * 系统返回键。
 *
 * 现在有三个地方要用它：紧凑布局的聊天详情、设置的子屏、平板的设置覆盖层，
 * 所以名字不叫 ChatDetail*。
 *
 * `BackHandler` 在 Compose Multiplatform 里仍是 `@ExperimentalComposeUiApi`，
 * 所以 opt-in 收在这一个函数里，而不是撒在 [RelayApp] 上 —— 将来它转正时，
 * 要删的注解也只有这一处。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BackKeyHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

/**
 * 断点表就是自适应布局的全部内容，所以它必须被测试盯住 —— 不是 private。
 * 取值与 `AppLayoutModeTest` 盯住的断点表、以及 `@Preview` 截图矩阵一一对应。
 *
 * 宽度两档**不在这里写数字**，直接取 [DesktopMetrics]：两栏就是那个桌面外壳，
 * 阈值重复写一遍就会漂 —— 曾经如此（这里 600、外壳 600），改动时只改一处，
 * 于是断点表和它渲染的外壳说的不是同一件事。
 *
 * 两栏还需要一个**高度下限**：手机横屏 852×393 宽度过了阈值，但只有 393dp 高，
 * 分两栏会把内容挤成一条缝。所以「够宽」和「够高」必须同时成立才给两栏，
 * 否则宁可退回紧凑横屏 —— 这正是矩阵里 852×393 → `CompactLandscape` 的意思。
 *
 * 高度的口径见 [TWO_PANE_MIN_HEIGHT_DP]：它曾经是一个独立的 `600`，
 * 与外壳自己的预算不同源，把 `664..999` 宽、`466..599` 高的窗口误判成手机壳。
 *
 * @return 只回答「挂哪个壳」；档位由壳内部的 `desktopWindowSizeClass` 决定。
 */
internal fun resolveAppLayoutMode(width: Dp, height: Dp): AppLayoutMode = when {
    width >= DesktopMetrics.EXPANDED_MIN_PX.dp -> AppLayoutMode.ExpandedListDetail
    width >= DesktopMetrics.MEDIUM_MIN_PX.dp && height >= TWO_PANE_MIN_HEIGHT_DP.dp ->
        AppLayoutMode.MediumTwoPane
    width > height -> AppLayoutMode.CompactLandscape
    else -> AppLayoutMode.CompactSinglePane
}

/**
 * 两栏的高度下限（**整窗**高度，不是可用高度）。
 *
 * **单位是 dp，名字里就写着**：调用点是 `resolveAppLayoutMode`，它收的两个参数都是
 * `Dp`。这个常量曾叫 `TWO_PANE_MIN_HEIGHT_PX` —— 名字说 px、用法是 dp，下一个
 * 改断点的人一换算就把阈值改错。外壳侧的 `DesktopMetrics.*_PX` 是**真像素**口径
 * （桌面窗口没有密度缩放那层语义），两侧单位不同，名字必须能拦住混用。
 *
 * 与桌面外壳的 [DesktopMetrics.MIN_CHAT_HEIGHT_PX] **同源**，不再各写一个数字。
 * 这条曾经是独立的 `600`，而外壳要的是 `420 + 46 = 466` —— A 比 B 严 134，
 * 于是 `664..999` 宽、`466..599` 高的窗口（平板分屏的常见形状，如 `800×580`）
 * 被判成 `CompactLandscape`、落回**手机壳的底部页签**；同一个窗口只要再高
 * 20 就切成桌面壳双栏，中间是硬跳变。
 *
 * **为什么减 `TABLET_CHROME_PX` 而不是 `CHROME_PX`**：`twoPane` 分支里挂的是
 * `chromeMode = DesktopChromeMode.Tablet`（菜单栏/标题栏/状态栏都不画），
 * 所以可用高度 = 整窗高度 − 46。桌面 `desktopApp` 走另一条路（直接调
 * `DesktopShell`，不经过本函数），那边按 `CHROME_PX = 138` 自己算，与本值无关。
 *
 * **两套断点的分工**：本值守着「挂手机壳还是桌面壳」；档位由
 * `desktopWindowSizeClass` 在壳内部决定。这层守卫是**必需的** ——
 * B 的 `Medium` 分支没有高度条件，没有它，`800×400` 这种窗口也会被送进双栏。
 */
internal const val TWO_PANE_MIN_HEIGHT_DP: Int =
    DesktopMetrics.MIN_CHAT_HEIGHT_PX + DesktopMetrics.TABLET_CHROME_PX

