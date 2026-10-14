// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import app.relay.ui.desktop.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.relay.app.RelayController
import app.relay.core.transport.LocalFile
import app.relay.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.Collections

/**
 * 跨窗口共享的宿主状态。
 *
 * 主窗和设置窗是两个独立窗口，但主题、配对选中的节点、当前会话选择
 * 必须在两者之间一致，所以提到这里由 [DesktopHostWindow] 持有，
 * 避免"两个真相来源"。
 */
internal class DesktopHost(
    val controller: RelayController,
    val scope: CoroutineScope,
    val extension: DesktopExtension,
    val screenCaptureService: app.relay.desktop.capture.DesktopScreenCaptureService? = null,
    /**
     * 启动时是否直接收进托盘（登录自启带 `--background` 时是 true）。
     *
     * 只是**初值**：之后主窗可见性由 [DesktopHostWindow] 的 Compose 状态拥有，
     * 深链/托盘点击照样能把它叫出来。
     */
    val startHidden: Boolean = false,
) : DesktopSettingsHost {
    /** 主题从磁盘偏好起步，读不到就回退深色 —— 偏好坏了不该让人重设一次。 */
    var darkTheme by mutableStateOf(
        DesktopUiPrefs.loadDarkTheme(controller.dataDirectory, fallback = true),
    )
        private set

    /** 当前会话选择。真正的拥有者是 [DesktopShell]，这里只是它的观察者。 */
    var selectedPeerId by mutableStateOf<String?>(null)

    /**
     * 已经用**独立窗口**打开的会话。主窗不在这张表里。
     *
     * 多窗口的边界（§5.1 D10）：每窗口一份 UI 状态，`:core` 的运行时全局只有一份。
     * 所以这张表存的是"要开哪些窗口"，而窗口里的视图/搜索/标签页状态由
     * [DesktopShell] 自己的 `remember` 拥有 —— 两个窗口各改各的，互不影响。
     */
    val peerWindows = PeerWindowRegistry()

    /**
     * 给某个会话开一扇独立窗口。已经开着就不再开第二扇。
     *
     * 空白 id 会被 [PeerWindowRegistry.open] 忽略：那会变成一扇没有会话的窗口，
     * 用户只能把它关掉。
     */
    fun openPeerWindow(peerId: String) {
        peerWindows.open(peerId)
    }

    fun closePeerWindow(peerId: String) {
        peerWindows.close(peerId)
    }

    var settingsOpen by mutableStateOf(false)

    var pairingPeerId by mutableStateOf<String?>(null)

    /**
     * 省电后台档：托盘常驻时把心跳与扫描放慢（见 [DesktopPowerSaver]）。
     *
     * 初值从磁盘读，且**必须**与 [MainKt] 装配 `RelayController` 时用的那档一致 ——
     * 那里也是先读偏好再注入 `timings`，否则会出现"开关显示开着、实际按默认档跑"。
     */
    override var powerSaver by mutableStateOf(
        DesktopUiPrefs.loadPowerSaver(controller.dataDirectory),
    )
        private set

    /** 开机自启。默认关，理由见 [DesktopLoginItem]。 */
    override var launchAtLogin by mutableStateOf(
        DesktopUiPrefs.loadLaunchAtLogin(controller.dataDirectory),
    )
        private set

    /** 自启注册失败的原因；成功或还没试过是 null。界面靠它解释开关为什么弹回去了。 */
    override var launchAtLoginError by mutableStateOf<String?>(null)
        private set

    override val launchAtLoginSupported: Boolean
        get() = DesktopLoginItem.isSupported(desktopOs(System.getProperty("os.name").orEmpty()))

    /**
     * 切省电档。
     *
     * `timings` 是 `NodeRuntime` 的构造参数，所以改档 = 重建运行时 —— 不能"先记下来
     * 等下次启动生效"，那会让开关看起来是坏的。借 [RelayController.requestRestart]：
     * 它保证旧节点把 UDP/TCP 端口放掉之后才重新绑。重启期间待发消息不会丢
     * （出站队列会从磁盘重注入）。
     */
    override fun applyPowerSaver(enabled: Boolean) {
        if (enabled == powerSaver) return
        powerSaver = enabled
        DesktopUiPrefs.savePowerSaver(controller.dataDirectory, enabled)
        controller.requestRestart()
        DesktopNotifications.show(
            "捎话",
            if (enabled) "已切到省电后台档：心跳与扫描放慢 ${DesktopPowerSaver.SLOWDOWN} 倍" else "已回到默认心跳频率",
        )
    }

    /**
     * 切开机自启。
     *
     * 失败时**不落盘、不改状态**：开关自己弹回原位，原因进 [launchAtLoginError]。
     * 记下一个做不到的"已开启"，比当场报错更糟 —— 用户会以为下次开机它会出现。
     */
    override fun applyLaunchAtLogin(enabled: Boolean) {
        if (enabled == launchAtLogin) return
        val result = DesktopLoginItem.setEnabled(enabled)
        val failure = result.exceptionOrNull()
        if (failure == null) {
            launchAtLoginError = null
            launchAtLogin = enabled
            DesktopUiPrefs.saveLaunchAtLogin(controller.dataDirectory, enabled)
        } else {
            launchAtLoginError = failure.message ?: failure.javaClass.simpleName
        }
    }

    /**
     * 外部入口（`relay://` 深链、`.relaymsg` 双击、命令行参数）要求「把主窗显示出来」
     * 的次数。
     *
     * 为什么是计数器而不是 Bool：主窗可见性由 [DesktopHostWindow] 里的 Compose 状态
     * 拥有（关闭 = 隐藏，托盘常驻）。如果这里存 Bool，第二次请求同名值不会触发重组，
     * 第 2 个深链就没人理。计数每次 +1，`LaunchedEffect` 必然重跑。
     */
    var showMainTicks by mutableStateOf(0)
        private set

    var hideMainTicks by mutableStateOf(0)
        private set

    /** 同上：外部入口要求把界面切到「节点」视图（深链 = 添加节点流程）。 */
    var nodesViewTicks by mutableStateOf(0)
        private set

    /** 同上：托盘菜单「收到的文件」要求把界面切到「文件」视图。 */
    var filesViewTicks by mutableStateOf(0)
        private set

    fun requestShowMain() {
        showMainTicks++
    }

    fun hideMain() {
        hideMainTicks++
    }

    fun requestNodesView() {
        nodesViewTicks++
    }

    fun requestFilesView() {
        filesViewTicks++
    }

    fun toggleTheme() {
        darkTheme = !darkTheme
        DesktopUiPrefs.saveDarkTheme(controller.dataDirectory, darkTheme)
    }
}

/**
 * 主窗 + 设置窗的宿主。
 *
 * 主窗的菜单栏由 [DesktopShell] 自己提供（26px 那一行），这里**不再**加第二层
 * MenuBar —— 两层菜单会让 macOS 菜单多出一整条空白。
 */
@Composable
internal fun DesktopHostWindow(
    host: DesktopHost,
    onExitApplication: () -> Unit,
) {
    val controller = host.controller
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 主窗可见性是 Compose 状态：关闭按钮只隐藏窗口（托盘常驻），托盘点击再恢复。
    // 用 AWT 的 isVisible 管这个会导致「隐藏后 Compose 失去窗口引用，无法再显示」。
    // 初值取决于启动参数：登录自启要静默驻留托盘，不能让用户看到自己没开的窗口。
    var mainVisible by remember { mutableStateOf(!host.startHidden) }

    // 深链 / 文件关联 / 命令行参数要求显示主窗：外部事件不知道窗口当前是隐藏还是
    // 被别的窗口盖住，所以这里既恢复可见也置顶。
    LaunchedEffect(host.showMainTicks) {
        if (host.showMainTicks > 0) mainVisible = true
    }
    LaunchedEffect(host.hideMainTicks) {
        if (host.hideMainTicks > 0) mainVisible = false
    }

    // 入站通知去重集合。只在消息真正到达时新增，避免每次重组都重放一遍旧消息。
    val seenMessageIds = remember {
        Collections.synchronizedSet(mutableSetOf<String>())
    }
    LaunchedEffect(state.messages) {
        state.messages.forEach { (peerId, list) ->
            list.forEach { msg ->
                if (msg.mine) return@forEach
                // 当前正打开的会话不再弹通知：用户已经看着它。
                // 独立窗口（D10）里正显示着的会话同理 —— 那扇窗口没有自己的通知去重，
                // 而它显示的内容和主窗选中的会话无关，只看 selectedPeerId 会漏掉它。
                if (peerId == host.selectedPeerId) return@forEach
                if (peerId in host.peerWindows.open) return@forEach
                if (!seenMessageIds.add(msg.id)) return@forEach
                val title = state.conversations.firstOrNull { it.convId == peerId }?.title ?: "捎话"
                DesktopNotifications.show(title, msg.text.take(80).ifBlank { "收到一个文件" })
            }
        }
    }

    Window(
        onCloseRequest = {
            // 关闭主窗不退出进程：托盘常驻，用户从托盘再唤回。
            mainVisible = false
        },
        state = rememberWindowState(
            size = DpSize(
                DesktopMetrics.DEFAULT_WINDOW_WIDTH_PX.dp,
                DesktopMetrics.DEFAULT_WINDOW_HEIGHT_PX.dp,
            ),
        ),
        visible = mainVisible,
        title = "捎话 Tidings",
    ) {
        // 以下全部装在 Window 内容里：只有这里才有 FrameWindowScope.window 这个
        // 真实 AWT 窗口。此前这里 new 了一个游离 java.awt.Frame，改标题/隐藏的都是
        // 那张没人看的窗 —— 托盘唤不回来，正是因为聚焦错了窗口。
        val received = receivedFileStats(state.messages)
        DesktopWindowChrome(
            mainVisible = mainVisible,
            // 托盘模型只持资源+参数，落成字符串必须在 composable 里做（AWT 那侧拿不到资源）。
            tray = trayMenuSpec(
                unreadTotal = state.unreadByPeer.values.sum(),
                receivedFiles = received.first,
                receivedBytes = received.second,
                // 「本机 Agent」= 本机控制面（菜单里的「Agent 控制台」）有没有真的在监听。
                // 端口 0 表示还没绑上，这时说「运行中」就是假的。
                agentRunning = host.extension.controlPlanePort > 0,
            ).resolve(),
            onTrayAction = { action ->
                when (action) {
                    // 唤回主窗是所有"要看得见东西"的动作的前置：托盘里点的任何东西
                    // 都不该在一个隐藏窗口里发生。
                    TrayAction.ShowMain -> host.requestShowMain()
                    TrayAction.NewConversation -> {
                        host.requestShowMain()
                        host.requestNodesView()
                    }
                    TrayAction.OpenReceivedFiles -> {
                        host.requestShowMain()
                        host.requestFilesView()
                    }
                    TrayAction.OpenSettings -> host.settingsOpen = true
                    TrayAction.Quit -> onExitApplication()
                }
            },
            unreadTotal = state.unreadByPeer.values.sum(),
            focusRequest = state.focusRequest,
            onFocusRequestConsumed = controller::consumeFocusRequest,
        )

        // 拖放：把文件丢进窗口直接发给当前会话。
        DesktopDropTarget(
            enabled = true,
            // 覆盖层自己重铺了一套主题，而调用点在 AppTheme 之前，所以真实偏好只能
            // 从这里传下去；否则用户选深色、系统外观却是浅色时，遮罩会变成一张白卡片。
            darkTheme = host.darkTheme,
            onFiles = { files ->
                val peerId = host.selectedPeerId
                if (peerId == null) {
                    DesktopNotifications.show("捎话", "先选一个会话，再把文件拖进来")
                } else {
                    controller.sendFiles(
                        peerId,
                        files.map { LocalFile(it.absolutePath, it.name, it.length()) },
                    )
                }
            },
        )

        AppTheme(darkTheme = host.darkTheme) {
            val actions = remember(host, scope) {
                DesktopShellActions(
                    onOpenSettings = { host.settingsOpen = true },
                    onToggleTheme = { host.toggleTheme() },
                    onExportDiagnostics = { exportDiagnostics(host) },
                    onOpenDownloadsFolder = { openDownloadsFolder(controller.dataDirectory) },
                    onRefresh = { controller.handshake() },
                    // 顶层视图路由归 DesktopShell：它自己先把视图切到「节点」再回调。
                    // 窗口层再写一次 view 会把那次路由覆盖掉 —— 顶层视图只有 shell 一个主人。
                    onNewConversation = {},
                    onImportFiles = { peerId -> controller.pickAndSendFiles(peerId) },
                    onCopyPairingLink = { link ->
                        putTextOnClipboard(link)
                        DesktopNotifications.show("捎话", "已复制配对链接")
                    },
                    onCopyNodeId = { nodeId ->
                        putTextOnClipboard(nodeId)
                        DesktopNotifications.show("捎话", "已复制本机节点 ID")
                    },
                    onOpenPeerWindow = { peerId ->
                        // D10：真的开一扇独立窗口，而不是把主窗的选择改掉 ——
                        // 「多开」的意思是两个会话同时可见，改选择做不到这件事。
                        host.openPeerWindow(peerId)
                    },
                    onQuit = onExitApplication,
                    onSelectionChange = { host.selectedPeerId = it },
                )
            }

            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // 文件卡片「拖出去」要原生 AWT 窗口句柄，而 FrameWindowScope.window
                // 只在这一层可见，所以从这里往下提供，不必改 DesktopShell 的签名。
                val windowSize = rememberDesktopWindowSizeDp()
                CompositionLocalProvider(LocalAwtWindow provides window) {
                    ProvideDesktopFileDragSource {
                        DesktopShell(
                            state = state,
                            controller = controller,
                            windowWidthDp = windowSize.width,
                            windowHeightDp = windowSize.height,
                            darkTheme = host.darkTheme,
                            controlPlanePort = host.extension.controlPlanePort,
                            actions = actions,
                            externalNodesRequest = host.nodesViewTicks,
                            externalFilesRequest = host.filesViewTicks,
                            focusPeerId = state.focusRequest,
                        )
                    }
                }
            }
        }
    }

    // 设置窗：独立窗口，720×560（设计稿要求的第二窗口尺寸）。
    if (host.settingsOpen) {
        DesktopSettingsWindow(host = host, onClose = { host.settingsOpen = false })
    }

    // 全屏置顶截图标注窗口
    val capture = host.screenCaptureService?.captureState
    if (capture != null) {
        Window(
            onCloseRequest = capture.onCancel,
            state = rememberWindowState(
                size = DpSize(capture.bounds.width.dp, capture.bounds.height.dp),
                position = WindowPosition(capture.bounds.x.dp, capture.bounds.y.dp),
            ),
            undecorated = true,
            alwaysOnTop = true,
            resizable = false,
            title = "Capture",
            // 窗口级 Esc：覆盖窗是「无标题栏 + 置顶」的全屏窗，主窗口在截图期间被隐藏，
            // 光靠内容层某个节点拿焦点并不可靠 —— 这层保底，Esc 一定退得掉
            // （原型 screenDCapture 的 select 相位逐字写着「拖拽框选区域 · Esc 取消」）。
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    capture.onCancel()
                    true
                } else {
                    false
                }
            },
        ) {
            // 覆盖窗要真的拿到键盘焦点：置顶窗被 show 出来时不一定抢焦点，
            // 而 Esc/Enter 只有窗口有焦点时才送得到（实测过：没有这一句，Esc 无效）。
            LaunchedEffect(Unit) {
                window.toFront()
                window.requestFocus()
            }
            AppTheme(darkTheme = host.darkTheme) {
                app.relay.desktop.capture.CaptureOverlayView(
                    rawImage = capture.image,
                    viewportWidth = capture.bounds.width.toFloat(),
                    viewportHeight = capture.bounds.height.toFloat(),
                    onConfirm = capture.onConfirm,
                    onCopy = capture.onCopy,
                    onCancel = capture.onCancel,
                )
            }
        }
    }

    // scope 记在这里只是为了提醒：Main.kt 用的是进程级 scope，
    // 这里不能 cancel —— 否则关窗就把传输任务一起杀了。
    remember(scope) { Unit }
}

/**
 * 真实 AWT 窗口上的原生属性：最小尺寸、标题（随未读更新）、托盘。
 *
 * 全部走 [DisposableEffect] 装卸：写在组合体里会每次重组注册一次监听器，
 * 而 [LaunchedEffect] 托盘在装好之前就跑完 onDispose，句柄永远不会被释放。
 */
@Composable
private fun FrameWindowScope.DesktopWindowChrome(
    mainVisible: Boolean,
    tray: TrayMenuModel,
    onTrayAction: (TrayAction) -> Unit,
    unreadTotal: Int,
    focusRequest: String? = null,
    onFocusRequestConsumed: (() -> Unit)? = null,
) {
    val awtWindow = window

    DisposableEffect(awtWindow) {
        awtWindow.minimumSize = java.awt.Dimension(
            DesktopMetrics.MIN_WINDOW_WIDTH_PX,
            DesktopMetrics.MIN_WINDOW_HEIGHT_PX,
        )
        onDispose { awtWindow.minimumSize = java.awt.Dimension() }
    }

    // 托盘菜单随状态变（标题、未读条数、收到的文件数）。菜单模型每次重组都是新的
    // 数据对象，但只有内容真变了才需要重画，所以句柄和被渲染的模型都进 key。
    var trayHandle by remember { mutableStateOf<DesktopTrayHandle?>(null) }
    val latestTrayAction by rememberUpdatedState(onTrayAction)
    DisposableEffect(awtWindow) {
        val handle = DesktopTray.install(
            model = tray,
            // 回调走 rememberUpdatedState 取最新的那一份：句柄只装一次，
            // 而它捕获的动作会随着重组换成新的闭包。
            onAction = { action -> latestTrayAction(action) },
        )
        trayHandle = handle
        onDispose {
            // 先取出来再置空：置空后 `trayHandle?.dispose()` 就什么也释放不掉了。
            val disposed = trayHandle
            trayHandle = null
            disposed?.dispose()
        }
    }
    LaunchedEffect(trayHandle, tray, unreadTotal) {
        trayHandle?.apply(tray, unreadTotal > 0)
    }

    // 窗口从托盘唤回时才拉焦点，否则它会抢走当时正在用的那个 App。
    LaunchedEffect(mainVisible) {
        if (mainVisible) {
            awtWindow.isVisible = true
            awtWindow.toFront()
            awtWindow.requestFocus()
        }
    }

    LaunchedEffect(focusRequest) {
        if (focusRequest != null) {
            awtWindow.isVisible = true
            awtWindow.toFront()
            awtWindow.requestFocus()
            shakeWindow(awtWindow)
            onFocusRequestConsumed?.invoke()
        }
    }

    awtWindow.title = if (unreadTotal > 0) "捎话 Tidings（$unreadTotal）" else "捎话 Tidings"
}

@Composable
private fun DesktopSettingsWindow(host: DesktopHost, onClose: () -> Unit) {
    val state by host.controller.state.collectAsState()
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(
            size = DpSize(
                DesktopMetrics.SETTINGS_WINDOW_WIDTH_PX.dp,
                DesktopMetrics.SETTINGS_WINDOW_HEIGHT_PX.dp,
            ),
            position = WindowPosition(Alignment.Center),
        ),
        title = "捎话 设置",
    ) {
        AppTheme(darkTheme = host.darkTheme) {
            DesktopSettingsPane(state, host.controller, host, onClose = onClose)
        }
    }
}

/**
 * 导出诊断包到缓存目录。
 *
 * 内容全部来自真实 state，而不是占位文案：诊断包的价值就在于它不是猜的。
 */
internal fun exportDiagnostics(host: DesktopHost) {
    val controller = host.controller
    val state = controller.state.value
    val report = DesktopDiagnostics.write(
        directory = controller.cacheDirectory,
        nodeId = state.settings.nodeId,
        profileRev = state.settings.profileRev,
        settingsLines = listOf(
            "昵称 = ${state.settings.nick}",
            "单位 = ${state.settings.company} ${state.settings.dept} ${state.settings.team}".trim(),
            "UDP = ${state.settings.udpPort}",
            "TCP = ${state.settings.tcpPort}",
            "消息落盘 = ${state.settings.persistMessages}",
            "手动节点 = ${state.settings.manualPeers.size}",
        ),
        networkLines = listOf(
            "阶段 = ${state.network.phase}",
            "传输 = ${state.network.transport}",
            "本机地址 = ${state.network.localAddresses.joinToString(", ").ifBlank { "（无）" }}",
            "VPN = ${state.network.vpnActive}",
            "已绑 UDP 端口 = ${state.network.boundUdpPort}",
            "mDNS = ${state.network.mdnsAdvertised}",
            "故障 = ${state.network.failure?.let { it::class.simpleName } ?: "无"}",
            "控制面 = ${host.extension.controlPlanePort.takeIf { it > 0 } ?: "未启用"}",
        ),
        peerLines = state.peers.map {
            "${it.displayName} (${it.nodeId.take(8)}) · ${if (it.online) "在线" else "离线"} · UDP ${it.udpPort}/TCP ${it.tcpPort}"
        },
    )
    DesktopNotifications.show(
        "捎话",
        report?.summary ?: "诊断包导出失败，检查数据目录是否可写",
    )
}

/**
 * 打开收件目录。
 *
 * 目录从 `dataDirectory` 推导（[desktopReceivedFilesDirectory]），与写入方
 * `RelayController` 的 `downloadDir` 同源。这里以前写死 `~/Downloads/捎话`，
 * 而文件落在 `${dataDirectory}/downloads`，所以按钮打开的是空目录（审计 P1-9）。
 */
internal fun openDownloadsFolder(dataDirectory: String) {
    val dir = File(desktopReceivedFilesDirectory(dataDirectory))
    // 一个文件都没收过时目录还不存在：Finder/资源管理器打开不存在的路径是静默失败，
    // 用户看到的仍然只是「点了没反应」。先建出来，让空目录也真的被打开。
    if (!dir.isDirectory) dir.mkdirs()
    openDirectory(dir)
}

private fun openDirectory(dir: File) {
    runCatching {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            java.awt.Desktop.getDesktop().open(dir)
        }
    }
}

/**
 * 写系统剪贴板。
 *
 * headless（无桌面/无剪贴板）或权限受限时 `setContents` 会抛异常，而「复制没成功」
 * 不该把界面弄崩 —— 用户顶多是发现粘出来是空的，这比弹栈好得多。
 */
internal fun putTextOnClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
            java.awt.datatransfer.StringSelection(text),
            null,
        )
    }
}
