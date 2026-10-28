// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.ui.desktop.*
import com.kapp.tidings.core.protocol.FileTransfer
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.conv_title
import com.kapp.tidings.generated.resources.desktop_drop_pick_conversation
import com.kapp.tidings.generated.resources.desktop_notification_title
import com.kapp.tidings.generated.resources.file_folder_empty_skipped
import com.kapp.tidings.generated.resources.file_folder_too_many
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.kapp.tidings.ui.theme.ThemeMode
import com.kapp.tidings.ui.theme.AppTheme

/**
 * 已打开的会话窗口表。
 *
 * 单独拎出来是为了能被单测钉住：`DesktopHost` 需要真实的 `RelayController`（会绑端口、
 * 落真实磁盘），在单测里造不出来，所以「同一会话只开一扇窗」「关掉就真的从表里消失」
 * 这两条规则要有自己的可测载体。
 *
 * 用 `mutableStateListOf` 而不是普通 List：开/关窗口要立刻反映到窗口集合上，
 * 否则第二扇窗口会等到别的状态变化才出现。
 */
internal class PeerWindowRegistry {
    private val ids = androidx.compose.runtime.mutableStateListOf<String>()

    /** 当前打开顺序的快照。遍历它去建窗口，不要在遍历时改表。 */
    val open: List<String> get() = ids

    /**
     * 登记一扇窗口。返回是否**新开**了一扇（已经开着就是 false —— 同一会话不开第二扇，
     * 否则用户点两次会得到两扇窗口显示同一个会话，还各自算一套滚动位置）。
     */
    fun open(peerId: String): Boolean {
        if (peerId.isBlank()) return false
        if (peerId in ids) return false
        ids += peerId
        return true
    }

    fun close(peerId: String) {
        ids -= peerId
    }
}

/**
 * 会话独立窗口（§5.1 D10「多窗口」）。
 *
 * ## 边界：每窗口一份 UI 状态，运行时全局一份
 * 每扇窗口是各自的 [DesktopShell] 组合体，它的视图/搜索/标签页/选中全是自己的
 * `remember` —— 所以两个窗口可以同时看着两个会话，互不干扰。而 `RelayController`
 * （它持有唯一的 `NodeRuntime`）、出站队列、去重表、磁盘存储全都来自 [host]，
 * 只有一份。**不要**在这里 `RelayController(...)`：那会绑第二个 UDP/TCP 端口，
 * 对方看到的就成了两个节点，而且两边的未读会各算一套。
 *
 * ## 为什么不是「把主窗的选择改掉」
 * 那样一次只能看到一个会话，「多开」这个需求本身就没被满足。真正的多窗口必须
 * 让两扇窗口同时存在。
 *
 * ## 托盘只装在主窗
 * 见 [DesktopPeerWindowChrome]：一扇窗口一个托盘图标会让用户看到四个茶壶。
 */
@Composable
internal fun DesktopPeerWindows(host: DesktopHost, onExitApplication: () -> Unit) {
    // 先取快照再遍历：关窗会在遍历过程中改 peerWindows，直接遍历可变列表会
    // 抛 ConcurrentModificationException。
    val peerIds = host.peerWindows.open.toList()
    peerIds.forEach { peerId ->
        // key：窗口位置/尺寸这类 composition 状态按 peerId 隔离。少了它，关掉
        // 中间一扇窗口会让后面每扇窗口都继承前一扇的位置。
        key(peerId) {
            DesktopPeerWindow(host = host, peerId = peerId, onExitApplication = onExitApplication)
        }
    }
}

@Composable
private fun DesktopPeerWindow(
    host: DesktopHost,
    peerId: String,
    onExitApplication: () -> Unit,
) {
    val controller = host.controller
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = remember(state.settings.themeMode, systemDark) {
        val wire = state.settings.themeMode
        if (wire.isEmpty()) {
            DesktopUiPrefs.loadDarkTheme(host.controller.dataDirectory, fallback = systemDark)
        } else {
            ThemeMode.fromWire(wire).resolve(systemDark)
        }
    }

    val conversation = state.conversations.firstOrNull { it.convId == peerId }
    // 会话可能还没落到 conversations 里（刚配对完就开窗）：退回节点昵称，再退回节点 ID。
    val peer = state.peer(peerId)
    val title = conversation?.title
        ?: peer?.displayName?.takeIf { it.isNotBlank() }
        ?: peer?.nick?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.conv_title)

    Window(
        onCloseRequest = { host.closePeerWindow(peerId) },
        state = rememberWindowState(
            size = DpSize(
                DesktopMetrics.DEFAULT_WINDOW_WIDTH_PX.dp,
                DesktopMetrics.DEFAULT_WINDOW_HEIGHT_PX.dp,
            ),
        ),
        title = "$title · 捎话",
    ) {
        DesktopPeerWindowChrome()

        // 文案在 Composable 里先解析：`onFiles` 是 AWT 回调，里面调不了 stringResource。
        val dropAppTitle = stringResource(Res.string.desktop_notification_title)
        val dropTooMany = stringResource(
            Res.string.file_folder_too_many,
            FileTransfer.MAX_FILES_PER_TRANSFER,
        )
        val dropEmptyDirs = stringResource(Res.string.file_folder_empty_skipped)
        val dropPickConversation = stringResource(Res.string.desktop_drop_pick_conversation)

        // 拖进这扇窗口就发给**这扇窗口的**会话，而不是主窗当前选中的那个 ——
        // 用户把文件丢在哪扇窗口上，意图已经写在动作里了。
        DesktopDropTarget(
            enabled = true,
            // 覆盖层的收件人就是这扇窗口自己的会话，不用猜。
            recipientName = title,
            // 同主窗：覆盖层自己重铺主题，只能由调用点把真实偏好传下去。
            darkTheme = effectiveDark,
            onFiles = { files ->
                // 与主窗共用一份展开/降级逻辑：拖放行为不该因为窗口不同而不同。
                handleDroppedFiles(
                    controller = controller,
                    peerId = peerId,
                    dropped = files,
                    notify = { body -> DesktopNotifications.show(dropAppTitle, body) },
                    onStageFiles = { staged ->
                        host.stageFilesFor(peerId, staged)
                    },
                    texts = DropTexts(
                        pickConversation = dropPickConversation,
                        tooMany = dropTooMany,
                        emptyDirsSkipped = dropEmptyDirs,
                    ),
                )
            },
        )

        AppTheme(darkTheme = effectiveDark) {
            // 与主窗同一条：没设共享目录就不给这条出口（null ⇒ 文件柜那颗「打开」不画），
            // 并且它必须是 remember 的键，否则设 / 停共享目录后 actions 还是旧的那一份。
            val shareRoot = state.settings.shareRoot?.takeIf { it.isNotBlank() }
            val actions = remember(host, scope, peerId, systemDark, shareRoot) {
                DesktopShellActions(
                    onOpenSettings = { host.settingsOpen = true },
                    onToggleTheme = { host.toggleTheme(systemDark) },
                    onExportDiagnostics = { exportDiagnostics(host) },
                    onOpenDownloadsFolder = { openDownloadsFolder(controller.dataDirectory) },
                    onOpenShareRoot = shareRoot?.let { root -> { openShareRoot(root) } },
                    onRefresh = { controller.handshake() },
                    // 顶层视图路由归 shell 自己，窗口层不再写第二次（同主窗的理由）。
                    onNewConversation = {},
                    onImportFiles = { id -> controller.pickAndSendFiles(id) },
                    onCopyPairingLink = { link ->
                        putTextOnClipboard(link)
                    },
                    onCopyNodeId = { nodeId ->
                        putTextOnClipboard(nodeId)
                    },
                    onOpenPeerWindow = { id -> host.openPeerWindow(id) },
                    onQuit = onExitApplication,
                    // 刻意**不**往 host 报本窗的选中：host.selectedPeerId 是主窗拖放
                    // 接收的目标，被别的窗口改掉会让主窗把文件发给错的会话。
                    onSelectionChange = {},
                )
            }

            // 会话独立窗的系统菜单栏：**精简版**（设置 / 截图 / 诊断包 / 退出）。
            // 不画「新建会话」与「导入文件」—— 这扇窗没有视图切换，而 onNewConversation
            // 在窗口层本来就是空实现、导入文件的收件人是主窗的选中项，在这扇窗上放它们
            // 等于给一个点了没反应或点错人的入口（同「没有能力就不画」）。
            // 截图打的是**这扇窗自己的会话**，不是主窗当前选中的那个。
            DesktopSystemMenuBar(
                onOpenSettings = actions.onOpenSettings,
                onScreenCapture = { controller.triggerScreenCapture(peerId) },
                onExportDiagnostics = actions.onExportDiagnostics,
                onQuit = onExitApplication,
            )

            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // 拖出要原生 AWT 句柄，而 FrameWindowScope.window 只在这一层可见。
                val windowSize = rememberDesktopWindowSizeDp()
                CompositionLocalProvider(LocalAwtWindow provides window) {
                    ProvideDesktopFileDragSource {
                        DesktopShell(
                            state = state,
                            controller = controller,
                            windowWidthDp = windowSize.width,
                            windowHeightDp = windowSize.height,
                            darkTheme = effectiveDark,
                            controlPlanePort = host.extension.controlPlanePort,
                            actions = actions,
                            initialPeerId = peerId,
                            stagedFilesByConv = host.stagedFilesByConv,
                            onStagedFilesChange = host::updateStagedFiles,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 会话窗口的原生属性：只设最小尺寸，**不装托盘**。
 *
 * 托盘是应用级资源，由主窗安装（[DesktopWindowChrome]）。每扇窗口各装一次的结果
 * 是任务栏里出现一排茶壶图标，而且主窗一关、它的 `onDispose` 会把自己那个句柄
 * 释放掉 —— 到底还剩几个完全看关窗顺序。所以这里只做窗口自己的事。
 */
@Composable
private fun FrameWindowScope.DesktopPeerWindowChrome() {
    val awtWindow = window
    DisposableEffect(awtWindow) {
        awtWindow.minimumSize = java.awt.Dimension(
            DesktopMetrics.MIN_WINDOW_WIDTH_PX,
            DesktopMetrics.MIN_WINDOW_HEIGHT_PX,
        )
        onDispose { awtWindow.minimumSize = java.awt.Dimension() }
    }
}
