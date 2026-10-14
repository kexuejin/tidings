// SPDX-License-Identifier: GPL-3.0-only

package app.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.relay.app.RelayController
import app.relay.core.fs.directoryUsageBytes
import app.relay.core.transport.PartFile
import app.relay.app.navBadges
import app.relay.feature.agents.AgentConsoleScreen
import app.relay.feature.cabinet.CabinetScreen
import app.relay.feature.chat.ChatScreen
import app.relay.feature.chat.ChatScreenActions
import app.relay.feature.chat.ChatScreenChrome
import app.relay.feature.chat.ChatScreenGroupActions
import app.relay.feature.chat.ChatScreenState
import app.relay.feature.conversations.ConversationsScreen
import app.relay.feature.nodes.NodesScreen
import app.relay.feature.scan.ScanScreen
import app.relay.feature.search.GlobalSearchScreen
import app.relay.feature.settings.SETTINGS_PAGE_APPEAR
import app.relay.feature.settings.SETTINGS_PAGE_AVATAR
import app.relay.feature.settings.SETTINGS_PAGE_CAPS
import app.relay.feature.settings.SETTINGS_PAGE_DATA
import app.relay.feature.settings.SETTINGS_PAGE_DIAG
import app.relay.feature.settings.SETTINGS_PAGE_NOTIFY
import app.relay.feature.settings.SETTINGS_PAGE_UPDATE
import app.relay.feature.settings.SettingsAppearSubpage
import app.relay.feature.settings.SettingsCapsSubpage
import app.relay.feature.settings.SettingsDataSubpage
import app.relay.feature.settings.SettingsDiagSubpage
import app.relay.feature.settings.SettingsNotifySubpage
import app.relay.feature.settings.SetAvatarPage
import app.relay.feature.settings.SettingsUpdateSubpage
import app.relay.feature.settings.SettingsScreen
import app.relay.navigation.AppLayoutMode
import app.relay.navigation.AppNavItems
import app.relay.navigation.AppNavState
import app.relay.navigation.AppRoutes
import app.relay.platform.CareSystemBars
import app.relay.ui.ContentWidths
import app.relay.ui.components.CompactScaffold
import app.relay.ui.desktop.DesktopChromeMode
import app.relay.ui.desktop.DesktopMetrics
import app.relay.ui.desktop.DesktopShell
import app.relay.ui.desktop.DesktopShellActions
import app.relay.ui.theme.AppTheme
import app.relay.ui.theme.ThemeMode
import app.relay.app.model.ConversationUi
import app.relay.app.model.PeerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // 外观是「跟随系统 / 浅色 / 深色」三选一，不是布尔量：只有三选一才能既表达
    // 「不表态」，又让状态栏图标亮度拿到一个确定的答案（见 CareSystemBars）。
    // 放在 RelayApp 而不是设置屏里，是因为 AppTheme 和系统栏都在这一层决策。
    //
    // 初值从**设置文件**读（`controller.state.settings.themeMode`），不是硬编码 System：
    // 用户选过深色，重启就该还是深色。这里刻意不用 rememberSaveable —— 那会变成第二个
    // 事实来源（配置变更时快照和设置文件各说各话）。进程重建后本函数重新组合，
    // 从设置里读回来的就是对的。
    var themeMode by remember {
        mutableStateOf(ThemeMode.fromWire(controller.state.value.settings.themeMode))
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

                var nav by remember { mutableStateOf(AppNavState()) }

                // 折叠屏展开 / 平板旋转会让外壳从手机换成两栏，而两栏那条路没有扫码页：
                // 不在这里收掉的话，页面会无声消失（scanning 还挂着、相机也一直开着），
                // 折回去又自己冒出来。`selectTab` 与关闭按钮之外的第三条清路。
                LaunchedEffect(twoPane) {
                    if (twoPane && nav.scanning) nav = nav.stopScanning()
                }

                // 「在看哪个 Agent 的日志」和「聊天里开着哪几个会话」是两件事，
                // 以前它们共用 selectedPeerId，于是打开一个 Agent 日志会顺手改掉聊天的当前会话。
                var selectedAgentId by remember { mutableStateOf<String?>(null) }

                // 戳一碰要求"定位到该会话"（协议 §8）：控制器只放一个一次性请求，
                // 外壳消费一次就清掉 —— 留着它，用户手动切走之后又会被拽回来。
                // 宽屏那条路不吃 nav，所以同时把值交给 [DesktopShell]（它自己管选中项）。
                val focusRequest = state.focusRequest
                LaunchedEffect(focusRequest) {
                    if (focusRequest == null) return@LaunchedEffect
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
                var settingsPage by remember { mutableStateOf<String?>(null) }
                BackHandler(enabled = settingsPage != null) { settingsPage = null }

                // 平板外壳（DesktopShell）没有桌面菜单栏，设置只能在工具栏溢出里进入，
                // 因此在这里挂一个全屏覆盖层；同样必须接管返回键。
                var tabletSettingsOpen by remember { mutableStateOf(false) }
                BackKeyHandler(enabled = tabletSettingsOpen) { tabletSettingsOpen = false }

                // 手机端全局搜索（原型 `m-gsearch`）。它是**整屏**而不是弹层：原型
                // `screenGSearch` 顶栏那一行装的是输入框而不是标题，底导仍在 ——
                // 也就是它替换的是会话列表那**一整屏**，不是盖一层对话框。
                // 与上面两条同一个理由：盖住了整屏就必须接管返回键，否则用户在搜索页
                // 按返回会直接退出应用（这条规矩在本文件里已经写过三遍）。
                var globalSearchOpen by remember { mutableStateOf(false) }
                BackKeyHandler(enabled = globalSearchOpen) { globalSearchOpen = false }

                // 平板同样要能复制节点 ID / 配对链接：composeApp 里没有剪贴板实现，
                // 用 Compose 自带的 LocalClipboardManager（Android/iOS/桌面通用）。
                val clipboard = LocalClipboardManager.current

                // 选中一个会话后进聊天详情；两栏模式下面板本来就并排显示。
                // 参数名从 `peerId` 改成 `convId`：群的会话键是 `group:<groupId>`，
                // 它不是任何一个节点。清未读也必须按会话键清 —— `selectPeer` 清的是
                // 节点 id，对群会话是另一个键，结果是"看着消息却还挂着红点"。
                val openChat: (String) -> Unit = { convId ->
                    nav = nav.openSession(convId)
                    controller.selectConversation(convId)
                }

                // 返回键只在紧凑布局的聊天详情里被接管：两栏模式下列表本来就看得见，
                // 再吃掉返回键，用户会找不到退出应用的办法。
                val chatDetailOnTop = nav.inChatDetail && !twoPane
                BackKeyHandler(enabled = chatDetailOnTop) { nav = nav.leaveChatDetail() }

                // 扫码页盖住了整个页签，也必须接管返回键：它不是页签，进它之前
                // `activeSessionId` 已经被 `selectTab` 清掉，所以上面那条 `chatDetailOnTop`
                // 接不住 —— 不接管的话，用户在取景框上按返回会直接退出应用。
                BackKeyHandler(enabled = nav.scanning) { nav = nav.stopScanning() }

                // 节点详情也要接管返回键：原型 `m-nodedetail` 是**节点页签内部的一个分支**
                // （顶栏返回键跳 `m-nodes`、底栏照画，见 `NodesScreen` 的 `detailPeerId` KDoc），
                // 所以它不像聊天详情那样整屏，但返回键仍应先回列表而不是退出应用。
                // 与上面那条同一条理由要 `!twoPane`：两栏模式下列表本来就看得见，
                // 宽屏壳自己管选中项、根本不吃 `nav.detailPeerId`。
                val peerDetailOnTop = nav.inPeerDetail && !twoPane
                BackKeyHandler(enabled = peerDetailOnTop) { nav = nav.leavePeerDetail() }

                // 工作区会话详情也要接管返回键：从详情退出回到工作区看板，而不是退出应用
                val agentSessionDetailOnTop = nav.inAgentSessionDetail && !twoPane
                BackKeyHandler(enabled = agentSessionDetailOnTop) { nav = nav.leaveAgentSessionDetail() }

                // 扫码入口只在**真的画得出取景框**的平台上出现（见 `AppPlatform.qrScannerPreview`）：
                // 传 null 时弹层里那段会自己改成"这台设备扫不了，请走填地址"。以前这里按布局
                // （twoPane）判，桌面窄窗会画出按钮，点下去只得到一句"桌面版不做摄像头扫码"——
                // 那是注定失败的按钮（DesktopNodesPane 的注释也是这个意思）。
                val startScan: () -> Unit = { nav = nav.startScanning() }
                val onScanQr: (() -> Unit)? =
                    if (controller.qrScannerPreview != null) startScan else null

                // 「从图片导入二维码」与取景框走同一道闸：手机端两者都有（ML Kit / CIDetector
                // 与相机栈在同一套依赖里），桌面两者都没有。分开成两个开关只会多一个
                // 可以互相矛盾的组合，不会多出任何一种真实设备。
                val importQrFromImage: (suspend () -> Boolean)? =
                    if (controller.qrScannerPreview != null) controller::importQrImage else null

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
                            cacheDir = controller.cacheDirectory,
                        ),
                        actions = ChatScreenActions(
                            onSend = controller::sendToConversation,
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
                            onAcceptTransfer = controller::acceptTransfer,
                            onDeclineTransfer = controller::declineTransfer,
                            onCancelTransfer = controller::cancelTransfer,
                            onResumeTransfer = controller::resumeTransfer,
                            // 长按自己的消息撤回；头部的戳一碰（仅单聊，Agent 会话不画）。
                            onRecallMessage = controller::recall,
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
                        ),
                        groupActions = ChatScreenGroupActions(
                            onAddGroupMembers = controller::addGroupMembers,
                            onRemoveGroupMember = controller::removeGroupMember,
                            onUpdateGroupMeta = controller::updateGroupMeta,
                            onLeaveGroup = { groupId ->
                                controller.leaveGroup(groupId)
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
                    when (settingsPage) {
                        // 通知与能力声明这五个开关以前只改屏上的本地状态，没有后端 ——
                        // 也就是"拨了不改变行为"（审计 P1-7）。现在都落到 RelaySettings：
                        // 通知三条只写盘（策略在收到消息那一刻现读），能力两条会
                        // 递增 profileRev 并重启节点（对端据此重新认识我们的能力）。
                        SETTINGS_PAGE_NOTIFY -> SettingsNotifySubpage(
                            modifier = settingsModifier,
                            onBack = { settingsPage = null },
                            notificationsEnabled = state.settings.notifyAgentMessages,
                            onNotificationsEnabledChange = controller::updateNotifyAgentMessages,
                            mentionOnlyEnabled = state.settings.notifyMentionOnly,
                            onMentionOnlyEnabledChange = controller::updateNotifyMentionOnly,
                            quietHoursEnabled = state.settings.notifyQuietHours,
                            onQuietHoursEnabledChange = controller::updateNotifyQuietHours,
                        )

                        SETTINGS_PAGE_APPEAR -> SettingsAppearSubpage(
                            modifier = settingsModifier,
                            onBack = { settingsPage = null },
                            themeMode = themeMode.wire,
                            onThemeModeChange = { wire ->
                                themeMode = ThemeMode.fromWire(wire)
                                // 选择的档位立刻落盘：不落盘的话下次启动又回到上一轮的值，
                                // 那个三选一就成了"看着能选、其实白选"。
                                controller.updateThemeMode(wire)
                            },
                        )

                        SETTINGS_PAGE_CAPS -> SettingsCapsSubpage(
                            modifier = settingsModifier,
                            onBack = { settingsPage = null },
                            textCapabilityEnabled = state.settings.waitFrameCapability,
                            onTextCapabilityEnabledChange = controller::updateWaitFrameCapability,
                            recordCapabilityEnabled = state.settings.recordMessageCapability,
                            onRecordCapabilityEnabledChange = controller::updateRecordMessageCapability,
                        )

                        SETTINGS_PAGE_DATA -> SettingsDataSubpage(
                            modifier = settingsModifier,
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
                            onClearHistory = controller::clearAllMessages,
                        )

                        SETTINGS_PAGE_DIAG -> SettingsDiagSubpage(
                            state = state,
                            modifier = settingsModifier,
                            onBack = { settingsPage = null },
                        )

                        SETTINGS_PAGE_UPDATE -> SettingsUpdateSubpage(
                            state = state,
                            modifier = settingsModifier,
                            onBack = { settingsPage = null },
                            appVersion = controller.version,
                            onCheckForUpdates = controller::checkForUpdates,
                            onOpenUpdate = controller::openUpdateUrl,
                        )

                        // 「上传头像」独立页（台账 `m-set-avatar`）。它是**资料页头像
                        // 分段控件第三档的出口** —— 原型 `avSeg()` 的第三档
                        // `avOpenUpload(){ go('m-set-avatar'); }`（`prototype/relay.html:2122`）。
                        // 在加这一支之前，`SetAvatarPage` 全仓没有生产调用点，是张孤儿页：
                        // 分段控件第三档就算画出来也无处可去。
                        SETTINGS_PAGE_AVATAR -> SetAvatarPage(
                            state = state,
                            pickedAvatarBytes = state.pickedAvatarBytes,
                            onPickFromAlbum = controller::pickAvatar,
                            onRemovePicked = controller::removePickedAvatar,
                            onSaveProfile = controller::saveProfile,
                            onBack = { settingsPage = null },
                            modifier = settingsModifier,
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
                            onDismissBanner = controller::dismissBanner,
                            onClearNote = controller::clearSettingsNote,
                            onOpenPage = { settingsPage = it },
                            // 资料页头像分段控件第三档「上传图片」的出口（见上面那一支）。
                            onOpenAvatarPage = { settingsPage = SETTINGS_PAGE_AVATAR },
                            modifier = settingsModifier,
                        )
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
                            onSearch = controller::searchGlobal,
                            onBack = { globalSearchOpen = false },
                            onOpenChat = { convId ->
                                globalSearchOpen = false
                                openChat(convId)
                            },
                            // 文件在协议里属文件柜那一套（原型 `gscFile` 的落点是手机 `m-cabinet`）：
                            // 点它该看到"这个文件在哪"，不只是"谁发过"。这里先切到文件柜页签 ——
                            // 直接定位到那一项要文件柜支持"按消息 id 定位"，那是它那一组的能力，
                            // 不在这块屏里编一个注定到不了位的落点。
                            onOpenFile = {
                                globalSearchOpen = false
                                nav = nav.selectTab(AppRoutes.CABINET)
                            },
                            modifier = contentModifier,
                        )

                        // 紧凑布局下的聊天详情：整屏，顶栏带返回。
                        chatDetailOnTop -> chatPanel(contentModifier, true)

                        else -> Row(contentModifier.fillMaxSize()) {
                            when (nav.selectedTabId) {
                                AppRoutes.CABINET -> CabinetScreen(
                                    state = state,
                                    onSetShareRoot = controller::setShareRoot,
                                    onSetShareMode = controller::setShareMode,
                                    onRemoveShareGrant = controller::removeShareGrant,
                                    onSelectPeer = controller::selectCabinetPeer,
                                    onLoadPath = controller::loadCabinetPath,
                                    onToggleSelection = controller::toggleCabinetSelection,
                                    onDownloadEntries = controller::downloadCabinetEntries,
                                    onPickShareRoot = controller::pickShareRoot,
                                    onUploadToCabinet = controller::uploadToCabinet,
                                    modifier = Modifier.weight(1f),
                                )

                                AppRoutes.NODES -> NodesScreen(
                                    state = state,
                                    onAddPeer = controller::addManualPeer,
                                    // 带名字那条：手机壳能收下"给手动节点起个本机显示名"，
                                    // 于是弹层里多一个「名字（可选）」框；名字只存本机、不进 wire。
                                    onAddPeerNamed = controller::addNamedManualPeer,
                                    onRemovePeer = controller::removeManualPeer,
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

                                AppRoutes.AGENTS -> AgentConsoleScreen(
                                    state = state,
                                    selectedAgentId = selectedAgentId,
                                    onSelectAgent = { selectedAgentId = it },
                                    selectedAgentSessionId = nav.activeAgentSessionId,
                                    onSelectAgentSession = { nav = nav.selectAgentSession(it) },
                                    inDetail = false,
                                    onOpenSessionDetail = null,
                                    onBackToBoard = null,
                                    onOpenChat = { peerId, wsId ->
                                        selectedAgentId = peerId
                                        // 与消息页「工作区」段**同一条落点**：带 wsId 就收窄到它。
                                        // 这一屏是同一意图的入口，传入 wsId 收窄会话。
                                        nav = wsId?.let { ws -> nav.openWorkspaceChat(peerId, ws) }
                                            ?: nav.openSession(peerId)
                                        controller.selectConversation(peerId)
                                    },
                                    onFindNodes = { nav = nav.selectTab(AppRoutes.NODES) },
                                    onRespondApproval = controller::respondApproval,
                                    onCancelTask = controller::cancelAgentTask,
                                    onRefresh = { controller.refreshPeers() },
                                    modifier = Modifier.weight(1f),
                                )

                                AppRoutes.SETTINGS -> settingsContent(Modifier.weight(1f))

                                else -> ConversationsScreen(
                                    state = state,
                                    onOpenChat = openChat,
                                    onGoToPairing = { nav = nav.selectTab(AppRoutes.NODES) },
                                    onDismissBanner = controller::dismissBanner,
                                    onCreateGroup = controller::createGroup,
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
                                    // 后者效果对（`selectConversation` 顺手清零 unread，
                                    // `RelayController.kt:186`），但那是**借了别人的副作用** ——
                                    // 一个叫 `selectConversation` 的方法被当"批量标已读"用。
                                    // 裁决：给它一个说得出名字的入口。
                                    // （字段名是 `convId` 不是 `peerId`，理由见 `ConversationUi.convId`
                                    // 的 KDoc；`App.kt` 是组合根，一处调用补一行都要 Lead 点名授权。）
                                    onMarkAllRead = controller::markAllRead,
                                    // 「工作区」段的两颗入口：
                                    // 行 → 下钻、尾巴 → 看板。**行与看板卡片点同一处** ——
                                    // 这一段的每一行现在就是看板那张卡（`WorkspaceUnitCard`），
                                    // 同一条 item 点出两种落点会让人以为是两个对象。
                                    // 卡片自带 `active`（最活跃会话）时直接落到该会话详情，
                                    // 拿不到时才退回「工作区页签 + 选中这个 Agent」。
                                    // `m-agentdetail` / `m-workspacedetail` 两屏代码侧还没有落点，
                                    // 已登记为偏差；传 null 时整段不画，所以预览里要自己传这两个 lambda。
                                    // 「工作区」段的行 → **直接进聊天页**。
                                    //
                                    // 之前这里是 selectTab(AGENTS) + selectAgentSession：切到 Agent 页签、
                                    // 再停在它一级的**看板**上 —— 用户点一行工作区的意图是「我要跟它说话」，
                                    // 落点却是一张雷达卡片，还得再点一次才进得去。聊天页对 agent 节点本来
                                    // 就是完整的（会话切换条 + 会话选择器 + 待审批横幅，见 ChatScreen 的
                                    // peer.isAgent 分支），所以这里直接走 openChat —— 与「从节点页点一行」
                                    // 是同一条路。
                                    //
                                    // selectedAgentId 仍记一笔：Agent 页签是它自己那一屏，用户从聊天页顶栏
                                    // 点「控制台」时要落在刚看过的这个节点上。
                                    onOpenWorkspaceUnit = { unit ->
                                        selectedAgentId = unit.peerId
                                        unit.active?.let { active -> nav = nav.selectAgentSession(active.sessionId) }
                                        // 用 `openWorkspaceChat` 而不是 `openChat`：这一屏要**收窄到这个
                                        // 工作区**，否则点任何一行看到的都是同一份「节点全部会话」。
                                        // 只有行上带 wsId 时才收窄；不带（阶段一的 Agent 行）走普通聊天。
                                        nav = unit.wsId?.let { ws -> nav.openWorkspaceChat(unit.peerId, ws) }
                                            ?: nav.openSession(unit.peerId)
                                        controller.selectConversation(unit.peerId)
                                    },
                                    // 段尾「还有 N 行」→ 工作区页签（那是「全部工作区」的清单页）。
                                    // 这条**保持切页签**：它的语义是「去看完整列表」，不是「进某个会话」。
                                    onOpenWorkspaceBoard = { nav = nav.selectTab(AppRoutes.AGENTS) },
                                    modifier = Modifier.weight(1f),
                                )
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
                            themeMode = next
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
                        preview = controller.qrScannerPreview,
                        scan = controller::scanQrCode,
                        onPaired = { nav = nav.stopScanning() },
                        onCancel = { nav = nav.stopScanning() },
                        onDismissBanner = controller::dismissBanner,
                        modifier = Modifier.fillMaxSize(),
                        importFromImage = importQrFromImage,
                    )
                } else if (chatDetailOnTop || agentSessionDetailOnTop) {
                    // 紧凑布局下的聊天详情与会话详情均是**整屏全屏**（原型 m-chat 与 m-session-detail 均标记 full:true）。
                    // 详情页底下不画底部导航栏，避免侵占屏幕空间及误触；详情页退出靠顶栏返回或系统返回键回到看板。
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
                app.relay.ui.components.RemoteViewSessionDialog(
                    state = state.screenSession,
                    onAccept = { controller.respondScreenView(true) },
                    onReject = { controller.respondScreenView(false) },
                    onStop = { controller.stopScreenView() },
                    onSetMode = { controller.setScreenViewMode(it) },
                )
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
 * 高度的口径见 [TWO_PANE_MIN_HEIGHT_PX]：它曾经是一个独立的 `600`，
 * 与外壳自己的预算不同源，把 `664..999` 宽、`466..599` 高的窗口误判成手机壳。
 *
 * @return 只回答「挂哪个壳」；档位由壳内部的 `desktopWindowSizeClass` 决定。
 */
internal fun resolveAppLayoutMode(width: Dp, height: Dp): AppLayoutMode = when {
    width >= DesktopMetrics.EXPANDED_MIN_PX.dp -> AppLayoutMode.ExpandedListDetail
    width >= DesktopMetrics.MEDIUM_MIN_PX.dp && height >= TWO_PANE_MIN_HEIGHT_PX.dp ->
        AppLayoutMode.MediumTwoPane
    width > height -> AppLayoutMode.CompactLandscape
    else -> AppLayoutMode.CompactSinglePane
}

/**
 * 两栏的高度下限（**整窗**高度，不是可用高度）。
 *
 * 与桌面外壳的 [DesktopMetrics.MIN_CHAT_HEIGHT_PX] **同源**，不再各写一个数字。
 * 这条曾经是独立的 `600`，而外壳要的是 `420 + 46 = 466` —— A 比 B 严 134px，
 * 于是 `664..999` 宽、`466..599` 高的窗口（平板分屏的常见形状，如 `800×580`）
 * 被判成 `CompactLandscape`、落回**手机壳的底部页签**；同一个窗口只要再高
 * 20px 就切成桌面壳双栏，中间是硬跳变。
 *
 * **为什么减 `TABLET_CHROME_PX` 而不是 `CHROME_PX`**：`twoPane` 分支里挂的是
 * `chromeMode = DesktopChromeMode.Tablet`（菜单栏/标题栏/状态栏都不画），
 * 所以可用高度 = 整窗高度 − 46。桌面 `desktopApp` 走另一条路（直接调
 * `DesktopShell`，不经过本函数），那边按 `CHROME_PX = 138` 自己算，与本值无关。
 *
 * **两套断点的分工**：本值守着「挂手机壳还是桌面壳」；档位由
 * `desktopWindowSizeClass` 在壳内部决定。这层守卫是**必需的** ——
 * B 的 `Medium` 分支没有高度条件，没有它，`800×400` 这种窗口也会被送进双栏。
 *
 *
 */
internal const val TWO_PANE_MIN_HEIGHT_PX: Int =
    DesktopMetrics.MIN_CHAT_HEIGHT_PX + DesktopMetrics.TABLET_CHROME_PX
