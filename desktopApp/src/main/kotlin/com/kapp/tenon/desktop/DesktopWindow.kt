// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.ui.desktop.*
import com.kapp.tenon.ui.InstallRelayUiTexts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import com.kapp.tenon.ui.components.ForwardTargetDialog
import com.kapp.tenon.ui.theme.ThemeMode
import com.kapp.tenon.core.protocol.FileTransfer
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.desktop_app_title
import com.kapp.tenon.generated.resources.desktop_app_title_waiting
import com.kapp.tenon.generated.resources.desktop_app_title_settings
import com.kapp.tenon.generated.resources.chat_drop_target_title
import com.kapp.tenon.generated.resources.desktop_drop_pick_conversation
import com.kapp.tenon.generated.resources.desktop_notification_title
import com.kapp.tenon.generated.resources.file_folder_empty_skipped
import com.kapp.tenon.generated.resources.file_folder_too_many
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.AvatarSlice
import com.kapp.tenon.domain.ChatSlice
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.core.transport.LocalFile
import com.kapp.tenon.feature.chat.mergeStagedFiles
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.ChromeSlice
import com.kapp.tenon.domain.TransferSlice
import com.kapp.tenon.domain.conversations
import com.kapp.tenon.orchestration.DesktopExtension
import com.kapp.tenon.orchestration.RelayLog
import com.kapp.tenon.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.Collections

/**
 * 跨窗口共享的宿主状态。
 *
 * 主窗和设置窗是两个独立窗口，但主题、配对选中的节点、当前会话选择
 * 必须在两者之间一致，所以提到这里由 [DesktopApp] 与各窗口共享，
 * 避免"两个真相来源"。
 */
internal class DesktopHost(
    val controller: RelayController,
    val scope: CoroutineScope,
    val extension: DesktopExtension,
    val screenCaptureService: com.kapp.tenon.desktop.capture.DesktopScreenCaptureService? = null,
    /**
     * 启动时是否直接收进托盘（登录自启带 `--background` 时是 true）。
     *
     * 只是**初值**：之后「主窗存不存在」由 [DesktopApp] 的 Compose 状态拥有
     * （关闭 = 销毁、唤回 = 重建），深链/托盘点击照样能让它出现。
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

    /** 每个会话的附件暂存列表：拖拽文件落入暂存区。 */
    var stagedFilesByConv by mutableStateOf<Map<String, List<LocalFile>>>(emptyMap())
        private set

    fun stageFilesFor(convId: String, files: List<LocalFile>) {
        val current = stagedFilesByConv[convId].orEmpty()
        val merged = mergeStagedFiles(current, files)
        stagedFilesByConv = stagedFilesByConv + (convId to merged)
    }

    fun updateStagedFiles(convId: String, files: List<LocalFile>) {
        stagedFilesByConv = if (files.isEmpty()) {
            stagedFilesByConv - convId
        } else {
            stagedFilesByConv + (convId to files)
        }
    }

    /**
     * 拖了文件但没有会话可发：暂存展开结果，等用户在目标选择器里点人。
     *
     * 拖放回调是 AWT 回调，弹不了 Compose 对话框 —— 「发给谁」只能交给组合层
     * （DesktopWindow 里的 ForwardTargetDialog）；null = 没有待发文件。
     */
    var pendingDropFiles by mutableStateOf<DroppedPayload?>(null)

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

    /**
     * **打开设置时要直达哪一页**；null = 沿用默认首页。
     *
     * 存在的理由：托盘上报「N 条授权申请待批准」之后，点它应当**直接落在**那扇面板上。
     * 否则用户还要在八行左栏里自己找到「可信设备与配对」—— 而那正是我们刚承诺「一步直达」
     * 的那一步。设置页读完就清空，避免下次打开还跳回同一页。
     */
    var settingsInitialNav by mutableStateOf<DesktopSettingsNav?>(null)

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
            DesktopCopy.value.appTitle,
            if (enabled) {
                desktopFormat(DesktopCopy.value.powerSaverOn, DesktopPowerSaver.SLOWDOWN)
            } else {
                DesktopCopy.value.powerSaverOff
            },
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
     * 为什么是计数器而不是 Bool：主窗存不存在由 [DesktopApp] 里的 Compose 状态
     * 拥有（关闭 = 销毁，托盘常驻）。如果这里存 Bool，第二次请求同名值不会触发重组，
     * 第 2 个深链就没人理。计数每次 +1，`LaunchedEffect` 必然重跑。
     */
    var showMainTicks by mutableStateOf(0)
        private set

    var hideMainTicks by mutableStateOf(0)
        private set

    /** 同上：外部入口要求把界面切到「节点」视图（深链 = 添加节点流程）。 */
    var nodesViewTicks by mutableStateOf(0)

    /**
     * 「把添加节点面板打开」的一次性请求计数。
     *
     * **为什么需要它**：`DesktopShell` 的「新建会话」原本只做 `view = Nodes` 再调
     * `actions.onNewConversation()`，而宿主注入的那个动作是空实现 —— 于是这个命令的
     * 全部效果就是切一次视图。用户已经在节点页时点它，**什么都不发生**。
     *
     * 与 `nodesViewTicks` 同一个形状（递增的意图计数，消费方用 `LaunchedEffect` 收），
     * 因为视图路由归 shell 独占，窗口层只能表达意图、不能直接改 `view`。
     */
    var addNodeTicks by mutableStateOf(0)
        private set

    /** 同上：托盘菜单「收到的文件」要求把界面切到「文件」视图。 */
    var filesViewTicks by mutableStateOf(0)
        private set

    /**
     * 菜单的 shell 意图计数（视图组的会话/工作区档与搜索）：view 与 searching 归
     * [com.kapp.tenon.ui.desktop.DesktopShell] 独占，窗口层只表达意图 ——
     * 与 [nodesViewTicks]/[filesViewTicks] 同一套「计数器而不是 Bool」约定，
     * 消费方各自 `LaunchedEffect`。
     */
    var shellNavTicks by mutableStateOf(0)
    var shellAgentsTicks by mutableStateOf(0)
    var shellSearchTicks by mutableStateOf(0)

    /** 帮助组「输入法自检」：直达设置窗诊断域里的观测页（ImeSmokePane）。 */
    fun openImeSelfCheck() {
        showImeSmokeTicks++
    }

    /** 输入法自检请求计数（消费方：设置窗的 ImeSmokePane 装配处）。 */
    var showImeSmokeTicks by mutableStateOf(0)
        private set

    /**
     * 托盘菜单项的统一落点。
     *
     * 为什么收在这里而不是留在组合里的那个 `when`：托盘现在**不依赖任何窗口**存在
     * （见 [DesktopApp] 的 KDoc），于是它捕获的那个回调也不再随窗口销毁而失效 ——
     * 但动作本身仍然需要"先让窗口存在、再表达意图"这个顺序，而那个顺序同时涉及
     * `showMainTicks`、`settingsOpen`、`settingsInitialNav` 三个状态。放在宿主上，
     * 托盘那侧就只剩"把菜单点成了什么"这一件事。
     *
     * 顺序的意义：`requestShowMain()` 是"要看得见东西"的所有动作的前置 ——
     * 托盘里点的任何东西都不该在一个还不存在的窗口里发生。
     */
    fun handleTrayAction(action: TrayAction, onExitApplication: () -> Unit) {
        when (action) {
            TrayAction.ShowMain -> requestShowMain()
            TrayAction.NewConversation -> {
                requestShowMain()
                requestNodesView()
            }
            TrayAction.OpenReceivedFiles -> {
                requestShowMain()
                requestFilesView()
            }
            TrayAction.OpenSettings -> settingsOpen = true
            // 一步直达「授信设备」面板：那里才是批准的地方，不是设置首页。
            TrayAction.OpenAgentGrants -> {
                settingsInitialNav = DesktopSettingsNav.TrustedDevices
                requestShowMain()
                settingsOpen = true
            }
            TrayAction.Quit -> onExitApplication()
        }
    }

    fun requestShowMain() {
        showMainTicks++
    }

    fun hideMain() {
        hideMainTicks++
    }

    fun requestNodesView() {
        nodesViewTicks++
    }

    /** 「新建会话」的落点：切到节点视图**并直接打开添加节点面板**。 */
    fun requestNewConversation() {
        nodesViewTicks++
        addNodeTicks++
    }

    fun requestFilesView() {
        filesViewTicks++
    }

    fun syncTheme(resolvedDark: Boolean) {
        if (darkTheme != resolvedDark) {
            darkTheme = resolvedDark
            DesktopUiPrefs.saveDarkTheme(controller.dataDirectory, resolvedDark)
        }
    }

    fun toggleTheme(systemDark: Boolean = darkTheme) {
        val currentWire = controller.state.value.settings.themeMode
        val currentMode = if (currentWire.isEmpty()) {
            if (darkTheme) ThemeMode.Dark else ThemeMode.Light
        } else {
            ThemeMode.fromWire(currentWire)
        }
        val nextMode = when (currentMode) {
            ThemeMode.Dark -> ThemeMode.Light
            ThemeMode.Light -> ThemeMode.Dark
            ThemeMode.System -> if (systemDark) ThemeMode.Light else ThemeMode.Dark
        }
        controller.updateThemeMode(nextMode.wire)
        val resolved = nextMode.resolve(systemDark)
        syncTheme(resolved)
    }
}

/**
 * 桌面壳的**应用级**组合：所有窗口的父，不是某一张窗。
 *
 * ## 为什么主窗是"懒建"的
 *
 * 旧形状是「托盘常驻 = 主窗一直存在、只是 `visible = false`」。实测那样一个**从没显示过**
 * 的窗口已经占着 15.8 MB 的 `IOSurface` 与整棵组合树（最小探针：Compose 无窗口 103 MB，
 * 加一个隐藏窗 157 MB）。它服务的能力（点托盘把窗口叫出来）不需要它先存在。
 *
 * 但"懒建"不能只把 `Window` 包进 `if`：托盘的安装、通知去重、深链消费、主题同步
 * 原本全住在 `Window` 的内容块里（只有 `FrameWindowScope` 拿得到真实 AWT 窗口），
 * 窗口一销毁它们就跟着没了 —— 于是托盘图标消失、深链再也叫不出窗口。
 *
 * 拆法就是：**把这些应用级副作用上提到这一层**，`Window` 里只留"只有那张窗口才成立"
 * 的东西（最小尺寸、标题、焦点、菜单栏、内容）。
 *
 * 判据：**窗口存在与否不该影响"进程还在正常工作"这件事**。
 *
 * ## 窗口清单（谁活着的条件）
 *
 * - 主窗：`mainVisible` —— 关闭按钮 = **销毁**（不是隐藏），托盘唤回 = 重建
 * - 设置窗：`host.settingsOpen` —— 托盘能不带主窗直接打开它
 * - 截图覆盖窗：`screenCaptureService.captureState != null` —— 截图期间主窗是被隐藏的
 *   （`onCaptureWindowToggle(false)` ⇒ `hideMain()`），把它放进主窗内容块会让
 *   「点截图 ⇒ 主窗销毁 ⇒ 覆盖窗一起没了」
 * - 独立会话窗：[DesktopPeerWindows]，由 `main` 单独组合
 *
 * 菜单栏由主窗里的 [DesktopSystemMenuBar] 提供（用户裁决）：它是**系统**菜单栏 ——
 * macOS 进屏幕顶部、Windows/Linux 进窗口菜单，不占窗口客户区高度。此前客户区里那条
 * 26px 自绘菜单栏已整条删除，所以主窗里现在必须自己发一层 MenuBar。
 */
@Composable
internal fun DesktopApp(
    host: DesktopHost,
    onExitApplication: () -> Unit,
) {
    val controller = host.controller
    // **桌面也是产品壳，也要装文案桥。**
    //
    // 修复前桌面从不经 `RelayApp`（它自己组合本函数），`controller.uiTexts` 一直是
    // `NoUiTexts` ⇒ 每句文案都是空串。而 `broadcastAgentState` 有「空正文不发帧」的
    // 纪律，于是 `agentState=failed` 只落在本机、**群流里谁都看不到** —— 真机上
    // 「kill 插件 → @agent」在手机端一条反馈都没有。读数与推演见
    // `ui/RelayUiTextsBridge.kt` 的 KDoc。
    InstallRelayUiTexts(controller)
    // 桌面壳自己的文案源（ARP-3）：AWT 对话框、自启注册、诊断包那些**不在组合里**的
    // 调用点从 DesktopCopy 同步读。装在这里与 RelayUiTexts 同一处：桌面从不经 RelayApp。
    InstallDesktopCopy()
    val settingsSlice by controller.settingsFlow.collectAsState()
    val chatSlice by controller.chatFlow.collectAsState()
    val agentSlice by controller.agentFlow.collectAsState()
    val nodeSlice by controller.nodeFlow.collectAsState()
    val chromeSlice by controller.chromeFlow.collectAsState()
    val transferSlice by controller.transferFlow.collectAsState()
    val avatarSlice by controller.avatarFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = remember(settingsSlice.settings.themeMode, systemDark) {
        val wire = settingsSlice.settings.themeMode
        if (wire.isEmpty()) {
            DesktopUiPrefs.loadDarkTheme(host.controller.dataDirectory, fallback = systemDark)
        } else {
            ThemeMode.fromWire(wire).resolve(systemDark)
        }
    }
    LaunchedEffect(effectiveDark) {
        host.syncTheme(effectiveDark)
    }

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

    /**
     * **待批准的 agent 授权申请数**。
     *
     * 为什么放在**应用级**：它同时服务两处 —— 托盘头条/角标（主窗不存在时也要对）
     * 与窗口标题。面板在「设置 → 授信设备」深处，用户没有理由主动去翻；
     * 申请发得出、台账记得下、面板也批得了，但没人看得见有个东西在等 ——
     * 那正是真机上这条死路的最后一环。
     */
    val grantStore = host.extension.grantStore
    var pendingGrantTotal by remember(grantStore) {
        mutableIntStateOf(grantStore?.snapshot()?.requests?.size ?: 0)
    }
    DisposableEffect(grantStore) {
        val off = grantStore?.addChangeListener {
            pendingGrantTotal = grantStore.snapshot().requests.size
        }
        onDispose { off?.invoke() }
    }
    val unreadTotal = chatSlice.unreadByPeer.values.sum()

    // 入站通知去重集合。只在消息真正到达时新增，避免每次重组都重放一遍旧消息。
    val seenMessageIds = remember {
        Collections.synchronizedSet(mutableSetOf<String>())
    }
    LaunchedEffect(chatSlice.messages) {
        chatSlice.messages.forEach { (peerId, list) ->
            list.forEach { msg ->
                if (msg.mine) return@forEach
                // 当前正打开的会话不再弹通知：用户已经看着它。
                // 独立窗口（D10）里正显示着的会话同理 —— 那扇窗口没有自己的通知去重，
                // 而它显示的内容和主窗选中的会话无关，只看 selectedPeerId 会漏掉它。
                if (peerId == host.selectedPeerId) return@forEach
                if (peerId in host.peerWindows.open) return@forEach
                if (!seenMessageIds.add(msg.id)) return@forEach
                val title = conversations(chatSlice, agentSlice, nodeSlice, settingsSlice).firstOrNull { it.convId == peerId }?.title
                    ?: DesktopCopy.value.appTitle
                DesktopNotifications.show(title, msg.text.take(80).ifBlank { DesktopCopy.value.notificationFile })
            }
        }
    }

    // 托盘、待批准数、未读总数都是**应用级**的：主窗不存在时它们也必须活着。
    DesktopTrayChrome(
        host = host,
        chatSlice = chatSlice,
        unreadTotal = unreadTotal,
        pendingGrantTotal = pendingGrantTotal,
        onExitApplication = onExitApplication,
    )

    if (mainVisible) {
        DesktopMainWindow(
            host = host,
            chatSlice = chatSlice,
            agentSlice = agentSlice,
            nodeSlice = nodeSlice,
            settingsSlice = settingsSlice,
            chromeSlice = chromeSlice,
            transferSlice = transferSlice,
            avatarSlice = avatarSlice,
            effectiveDark = effectiveDark,
            systemDark = systemDark,
            unreadTotal = unreadTotal,
            pendingGrantTotal = pendingGrantTotal,
            onClose = { mainVisible = false },
            onExitApplication = onExitApplication,
        )
    }

    if (host.settingsOpen) {
        DesktopSettingsWindow(host = host, onClose = { host.settingsOpen = false })
    }

    // 全屏置顶截图标注窗口。同样在主窗外面：截图期间主窗是被隐藏的
    // （capture 开始 ⇒ `onCaptureWindowToggle(false)` ⇒ `hideMain()`），
    // 放进主窗内容块会让「点截图 ⇒ 主窗销毁 ⇒ 覆盖窗一起没了」。
    DesktopCaptureWindow(host = host, effectiveDark = effectiveDark)
}

/**
 * 主窗。**只有它存在时才组合** —— 这就是懒建的全部含义。
 *
 * 关闭按钮不退出进程：托盘常驻，用户从托盘再唤回（那时本函数被重新组合，窗口重建）。
 * 因此这里不需要 `visible` 参数：存在即可见。
 */
@Composable
private fun DesktopMainWindow(
    host: DesktopHost,
    chatSlice: ChatSlice,
    agentSlice: AgentSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    chromeSlice: ChromeSlice,
    transferSlice: TransferSlice,
    avatarSlice: AvatarSlice,
    effectiveDark: Boolean,
    systemDark: Boolean,
    unreadTotal: Int,
    pendingGrantTotal: Int,
    onClose: () -> Unit,
    onExitApplication: () -> Unit,
) {
    val controller = host.controller
    val scope = rememberCoroutineScope()

    // N5 窗口几何持久化：上次关窗时的尺寸/位置/最大化在本次启动恢复；
    // 没有存档（首次启动/文件损坏）走默认 1100×720、位置交给窗口管理器 ——
    // 与 ui-prefs 同一条「读不出来退默认，不让应用起不来」的纪律（见 DesktopWindowPrefs）。
    val savedGeometry = remember { DesktopWindowPrefs.load(controller.dataDirectory) }
    val windowState = if (savedGeometry != null) {
        rememberWindowState(
            size = DpSize(savedGeometry.width.dp, savedGeometry.height.dp),
            position = WindowPosition.Absolute(savedGeometry.x.dp, savedGeometry.y.dp),
        )
    } else {
        rememberWindowState(
            size = DpSize(
                DesktopMetrics.DEFAULT_WINDOW_WIDTH_PX.dp,
                DesktopMetrics.DEFAULT_WINDOW_HEIGHT_PX.dp,
            ),
        )
    }

    Window(
        onCloseRequest = {
            // 关窗那一刻窗口还在：先记几何再走原路径。收进托盘（hideMain）不走这里 ——
            // 托盘隐藏是「窗口还在」，几何没变；重复写反而会记到隐藏瞬间的过渡态。
            // position 可能是 Aligned（用户没动过窗口）：那档不写坐标，只记尺寸 ——
            // 半条几何不如让窗口管理器下次再摆一次。
            val pos = windowState.position
            DesktopWindowPrefs.save(
                controller.dataDirectory,
                if (pos is WindowPosition.Absolute) {
                    DesktopWindowPrefs.Geometry(
                        x = pos.x.value.toInt(),
                        y = pos.y.value.toInt(),
                        width = windowState.size.width.value.toInt(),
                        height = windowState.size.height.value.toInt(),
                        isMaximized = windowState.placement == WindowPlacement.Maximized,
                    )
                } else {
                    DesktopWindowPrefs.Geometry(
                        x = 0,
                        y = 0,
                        width = windowState.size.width.value.toInt(),
                        height = windowState.size.height.value.toInt(),
                        isMaximized = windowState.placement == WindowPlacement.Maximized,
                    )
                },
            )
            onClose()
        },
        state = windowState,
        title = stringResource(Res.string.desktop_app_title),
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                if (host.pendingDropFiles != null) {
                    host.pendingDropFiles = null
                    true
                } else {
                    false
                }
            } else {
                false
            }
        },
    ) {
        // 以下全部装在 Window 内容里：只有这里才有 FrameWindowScope.window 这个
        // 真实 AWT 窗口。此前这里 new 了一个游离 java.awt.Frame，改标题/隐藏的都是
        // 那张没人看的窗 —— 托盘唤不回来，正是因为聚焦错了窗口。
        //
        // 而**托盘**不在这里：它是应用级的（见 [DesktopApp] 的 KDoc）。这个函数只管
        // 「只有这张窗口才成立」的原生属性：最小尺寸、标题、焦点。
        DesktopWindowChrome(
            unreadTotal = unreadTotal,
            pendingGrantTotal = pendingGrantTotal,
            focusRequest = chromeSlice.focusRequest,
            onFocusRequestConsumed = controller::consumeFocusRequest,
        )

        // 拖放相关的文案**必须在 Composable 里先解析好**：`onFiles` 是 AWT 回调，
        // 不是 Composable，里面调不了 `stringResource`；而 `desktopApp` 里新写硬编码中文
        // 会被 `cjkLiteralGate` 判红。
        val dropAppTitle = stringResource(Res.string.desktop_notification_title)
        val dropPickConversation = stringResource(Res.string.desktop_drop_pick_conversation)
        val dropTooMany = stringResource(
            Res.string.file_folder_too_many,
            FileTransfer.MAX_FILES_PER_TRANSFER,
        )
        val dropEmptyDirs = stringResource(Res.string.file_folder_empty_skipped)

        // 拖放：把文件丢进窗口直接发给当前会话。
        DesktopDropTarget(
            enabled = true,
            // 覆盖层自己重铺了一套主题，而调用点在 AppTheme 之前，所以真实偏好只能
            // 从这里传下去；否则用户选深色、系统外观却是浅色时，遮罩会变成一张白卡片。
            darkTheme = effectiveDark,
            onFiles = { files ->
                // 展开目录 → 传文件夹 / 传文件，与独立会话窗共用一份实现。
                // 文案在这里解析：`handleDroppedFiles` 不是 Composable，拿不到 stringResource。
                handleDroppedFiles(
                    controller = controller,
                    peerId = host.selectedPeerId,
                    dropped = files,
                    notify = { body -> DesktopNotifications.show(dropAppTitle, body) },
                    onPickTarget = { payload ->
                        // 一个会话都没有就没有可问的人：如实退回通知，别弹一个空列表。
                        if (conversations(chatSlice, agentSlice, nodeSlice, settingsSlice).isEmpty()) {
                            DesktopNotifications.show(dropAppTitle, dropPickConversation)
                        } else {
                            host.pendingDropFiles = payload
                        }
                    },
                    onStageFiles = { staged ->
                        host.selectedPeerId?.let { host.stageFilesFor(it, staged) }
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
            // 「打开共享目录」只在真的设了共享目录时给出去：null ⇒ 文件柜那颗「打开」不画。
            // 它必须是 remember 的键：用户中途设 / 停共享目录时 actions 得跟着重算，
            // 否则那颗按钮要么永远不出现、要么永远不消失。
            val shareRoot = settingsSlice.settings.shareRoot?.takeIf { it.isNotBlank() }
            val actions = remember(host, scope, systemDark, shareRoot) {
                DesktopShellActions(
                    onOpenSettings = { host.settingsOpen = true },
                    onToggleTheme = { host.toggleTheme(systemDark) },
                    onExportDiagnostics = { exportDiagnostics(host) },
                    onOpenDownloadsFolder = { openDownloadsFolder(controller.dataDirectory) },
                    onOpenShareRoot = shareRoot?.let { root -> { openShareRoot(root) } },
                    onRefresh = {
                        controller.refreshPeers()
                        controller.handshake()
                    },
                    // 顶层视图路由归 DesktopShell：它自己先把视图切到「节点」再回调。
                    // 窗口层再写一次 view 会把那次路由覆盖掉 —— 顶层视图只有 shell 一个主人。
                    onNewConversation = {},
                    onImportFiles = { peerId -> controller.pickAndSendFiles(peerId) },
                    onCopyPairingLink = { link ->
                        putTextOnClipboard(link)
                    },
                    onCopyNodeId = { nodeId ->
                        putTextOnClipboard(nodeId)
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

            // 系统菜单栏：五组（N1）—— 应用（设置/诊断包/输入法自检/退出）｜文件｜编辑｜视图｜窗口。
            // 键盘处理与菜单印刷的一致性由 DesktopMenuCommandsTest 枚举全比对钉住。
            DesktopSystemMenuBar(
                onNewConversation = {
                    // **窗口层这条入口要真的把面板打开**，不能只切视图。
                    //
                    // 原来这里写的是 `requestNodesView()` + `actions.onNewConversation()`，
                    // 而注入的那个动作是空实现 —— 效果只有一次视图切换；用户已经在节点页
                    // 时点「新建会话」什么都不发生（真机反馈）。
                    //
                    // `requestNewConversation()` 同时递增「切到节点」与「打开添加面板」两个
                    // 意图计数：view 归 shell 独占，窗口层只表达意图，消费方各自 `LaunchedEffect`。
                    host.requestNewConversation()
                },
                onImportFiles = { host.selectedPeerId?.let(actions.onImportFiles) },
                canImportFiles = canImportFiles(host.selectedPeerId),
                onOpenDownloadsFolder = actions.onOpenDownloadsFolder,
                onOpenSettings = actions.onOpenSettings,
                onCopyNodeId = { putTextOnClipboard(host.controller.state.value.settings.nodeId) },
                onToggleTheme = { host.toggleTheme() },
                onRefreshNodes = { controller.refreshPeers(); controller.handshake() },
                onViewChange = { shortcut ->
                    // 视图切换的窗口层表达 = 意图计数（files/nodes 有现成通道，与托盘同一形状）。
                    // Conversations/Agents 归 shell 的 view 独占 —— 经 external 观察计数表达
                    //（与 externalNodesRequest 同一套「计数器而不是 Bool」约定）。
                    when (shortcut) {
                        DesktopShortcut.ViewConversations -> host.shellNavTicks++
                        DesktopShortcut.ViewFiles -> host.requestFilesView()
                        DesktopShortcut.ViewNodes -> host.requestNodesView()
                        DesktopShortcut.ViewAgents -> host.shellAgentsTicks++
                        else -> Unit
                    }
                },
                onFocusSearch = { host.shellSearchTicks++ },
                // 与 DesktopShell 的 ⌘⇧A 逐字同一个表达式：截图覆盖窗是独立窗，
                // 不在这里顺带改视图（那条纪律原来写在自绘菜单栏上，见 task-25）。
                onScreenCapture = { controller.triggerScreenCapture(host.selectedPeerId) },
                onMinimize = { window.setState(java.awt.Frame.ICONIFIED) },
                onNewWindow = {
                    host.selectedPeerId?.let { peer -> host.openPeerWindow(peer) }
                },
                onCloseWindow = { host.hideMain() },
                onImeSelfCheck = { host.openImeSelfCheck() },
                onExportDiagnostics = actions.onExportDiagnostics,
                onQuit = onExitApplication,
            )

            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // 文件卡片「拖出去」要原生 AWT 窗口句柄，而 FrameWindowScope.window
                // 只在这一层可见，所以从这里往下提供，不必改 DesktopShell 的签名。
                val windowSize = rememberDesktopWindowSizeDp()
                CompositionLocalProvider(LocalAwtWindow provides window) {
                    ProvideDesktopFileDragSource {
                        DesktopShell(
                            chatSlice = chatSlice,
                            agentSlice = agentSlice,
                            nodeSlice = nodeSlice,
                            settingsSlice = settingsSlice,
                            chromeSlice = chromeSlice,
                            transferSlice = transferSlice,
                            avatarSlice = avatarSlice,
                            controller = controller,
                            desktopBadges = remember(chatSlice, agentSlice) {
                                desktopNavBadges(chatSlice, agentSlice)
                            },
                            windowWidthDp = windowSize.width,
                            windowHeightDp = windowSize.height,
                            darkTheme = effectiveDark,
                            controlPlanePort = host.extension.controlPlanePort,
                            actions = actions,
                            externalNodesRequest = host.nodesViewTicks,
                            externalAddNodeRequest = host.addNodeTicks,
                            externalFilesRequest = host.filesViewTicks,
                            externalConversationsRequest = host.shellNavTicks,
                            externalAgentsRequest = host.shellAgentsTicks,
                            externalSearchRequest = host.shellSearchTicks,
                            focusPeerId = chromeSlice.focusRequest,
                            stagedFilesByConv = host.stagedFilesByConv,
                            onStagedFilesChange = host::updateStagedFiles,
                        )
                    }
                }

                // 拖放回调（AWT）弹不了对话框：没选会话就拖文件时，把展开结果暂存到
                // host.pendingDropFiles，在这里问「发给谁」—— 空态文案承诺的就是这个，
                // 而不是只弹一句「请先选会话」。
                host.pendingDropFiles?.let { payload ->
                    ForwardTargetDialog(
                        title = stringResource(Res.string.chat_drop_target_title),
                        sessions = conversations(chatSlice, agentSlice, nodeSlice, settingsSlice),
                        onSelectTarget = { convId ->
                            host.pendingDropFiles = null
                            controller.sendFiles(
                                peerId = convId,
                                files = payload.files,
                                isFolder = payload.isFolder,
                                folderRootName = payload.folderRootName,
                            )
                            if (payload.skippedEmptyDirs > 0) {
                                DesktopNotifications.show(dropAppTitle, dropEmptyDirs)
                            }
                        },
                        onDismiss = { host.pendingDropFiles = null },
                    )
                }
            }
        }
    }

    // scope 记在这里只是为了提醒：Main.kt 用的是进程级 scope，
    // 这里不能 cancel —— 否则关窗就把传输任务一起杀了。
    remember(scope) { Unit }
}

/**
 * 全屏置顶截图标注窗口。
 *
 * **应用级**，不在主窗内容块里：截图开始时 `DesktopScreenCaptureService` 会
 * `onCaptureWindowToggle(false)` ⇒ `hideMain()`，主窗因此被销毁 —— 若覆盖窗住在
 * 主窗里，它会被一起带走，用户看到的是「点了截图，什么都没发生」。
 */
@Composable
private fun DesktopCaptureWindow(
    host: DesktopHost,
    effectiveDark: Boolean,
) {
    val capture = host.screenCaptureService?.captureState ?: return
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
        AppTheme(darkTheme = effectiveDark) {
            com.kapp.tenon.desktop.capture.CaptureOverlayView(
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

/**
 * 托盘图标：菜单模型、点击唤回、通知气泡复用。
 *
 * **应用级** —— 不挂在任何窗口上。理由见 [DesktopApp] 的 KDoc：托盘是"进程还在工作"
 * 的证据，主窗存在与否不该影响它。实测纯 AWT 的 `SystemTray` 在没有 Compose 窗口的
 * 进程里工作正常（84.6 MB 地板探针就是这么跑的）。
 *
 * 全部走 [DisposableEffect] 装卸：写在组合体里会每次重组注册一次监听器。
 */
@Composable
private fun DesktopTrayChrome(
    host: DesktopHost,
    chatSlice: ChatSlice,
    unreadTotal: Int,
    pendingGrantTotal: Int,
    onExitApplication: () -> Unit,
) {
    val received = receivedFileStats(chatSlice.messages)
    // 托盘模型只持资源+参数，落成字符串必须在 composable 里做（AWT 那侧拿不到资源）。
    val tray = trayMenuSpec(
        unreadTotal = unreadTotal,
        receivedFiles = received.first,
        receivedBytes = received.second,
        // 「本机 Agent」= 本机控制面（菜单里的「Agent 控制台」）有没有真的在监听。
        // 端口 0 表示还没绑上，这时说「运行中」就是假的。
        agentRunning = host.extension.controlPlanePort > 0,
        // 待批准的授权申请：只在真的有时才让托盘头条与直达条目出现。
        pendingGrants = pendingGrantTotal,
    ).resolve()

    var trayHandle by remember { mutableStateOf<DesktopTrayHandle?>(null) }
    DisposableEffect(Unit) {
        val handle = DesktopTray.install(
            model = tray,
            // 回调只装一次，而它读到的是 host 上的 Compose 状态 ⇒ 永远是最新意图。
            onAction = { action -> host.handleTrayAction(action, onExitApplication) },
        )
        trayHandle = handle
        onDispose {
            // 先取出来再置空：置空后 `trayHandle?.dispose()` 就什么也释放不掉了。
            val disposed = trayHandle
            trayHandle = null
            disposed?.dispose()
        }
    }
    LaunchedEffect(trayHandle, tray, unreadTotal, pendingGrantTotal) {
        // 托盘高亮：未读消息 **或** 有待批准的授权申请都算「有东西等你」。
        trayHandle?.apply(tray, unreadTotal > 0 || pendingGrantTotal > 0)
    }
}

/**
 * 真实 AWT 窗口上的原生属性：最小尺寸、标题、焦点。
 *
 * 只剩「只有这张窗口才成立」的三件事。托盘已经上提到 [DesktopTrayChrome] ——
 * 它原先住在这里，于是主窗一销毁托盘图标就跟着没了。
 */
@Composable
private fun FrameWindowScope.DesktopWindowChrome(
    unreadTotal: Int,
    pendingGrantTotal: Int,
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

    LaunchedEffect(focusRequest) {
        if (focusRequest != null) {
            awtWindow.isVisible = true
            awtWindow.toFront()
            awtWindow.requestFocus()
            shakeWindow(awtWindow)
            onFocusRequestConsumed?.invoke()
        }
    }

    // 标题里的数字要能区分两种「等你」：未读消息、待批准的授权。合计给总数 ——
    // 窗口标题只放得下一个括号，具体构成在面板里（那边分得清）。
    val waitingTotal = unreadTotal + pendingGrantTotal
    awtWindow.title = if (waitingTotal > 0) {
        stringResource(Res.string.desktop_app_title_waiting, waitingTotal)
    } else {
        stringResource(Res.string.desktop_app_title)
    }
}

@Composable
private fun DesktopSettingsWindow(host: DesktopHost, onClose: () -> Unit) {
    val settingsSlice by host.controller.settingsFlow.collectAsState()
    val chromeSlice by host.controller.chromeFlow.collectAsState()
    val avatarSlice by host.controller.avatarFlow.collectAsState()
    val transferSlice by host.controller.transferFlow.collectAsState()
    val nodeSlice by host.controller.nodeFlow.collectAsState()
    val chatSlice by host.controller.chatFlow.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = remember(settingsSlice.settings.themeMode, systemDark) {
        val wire = settingsSlice.settings.themeMode
        if (wire.isEmpty()) {
            DesktopUiPrefs.loadDarkTheme(host.controller.dataDirectory, fallback = systemDark)
        } else {
            ThemeMode.fromWire(wire).resolve(systemDark)
        }
    }
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(
            size = DpSize(
                DesktopMetrics.SETTINGS_WINDOW_WIDTH_PX.dp,
                DesktopMetrics.SETTINGS_WINDOW_HEIGHT_PX.dp,
            ),
            position = WindowPosition(Alignment.Center),
        ),
        title = stringResource(Res.string.desktop_app_title_settings),
    ) {
        AppTheme(darkTheme = effectiveDark) {
            DesktopSettingsPane(
                settingsSlice = settingsSlice,
                chromeSlice = chromeSlice,
                avatarSlice = avatarSlice,
                transferSlice = transferSlice,
                nodeSlice = nodeSlice,
                chatSlice = chatSlice,
                controller = host.controller,
                host = host,
                onClose = onClose,
            )
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
    // 诊断包里的标题也是文案（要翻译），所以走 DesktopCopy —— 这里是纯 JVM 路径。
    val diag = DesktopCopy.value.diag
    val report = DesktopDiagnostics.write(
        directory = controller.cacheDirectory,
        nodeId = state.settings.nodeId,
        profileRev = state.settings.profileRev,
        settingsLines = listOf(
            desktopFormat(diag.nick, state.settings.nick),
            desktopFormat(diag.org, "${state.settings.company} ${state.settings.dept} ${state.settings.team}".trim()),
            "UDP = ${state.settings.udpPort}",
            "TCP = ${state.settings.tcpPort}",
            desktopFormat(diag.persist, state.settings.persistMessages),
            desktopFormat(diag.manualPeers, state.settings.manualPeers.size),
        ),
        networkLines = listOf(
            desktopFormat(diag.phase, state.network.phase),
            desktopFormat(diag.transport, state.network.transport),
            desktopFormat(
                diag.localAddrs,
                state.network.localAddresses.joinToString(", ").ifBlank { diag.none },
            ),
            "VPN = ${state.network.vpnActive}",
            desktopFormat(diag.boundUdp, state.network.boundUdpPort),
            "mDNS = ${state.network.mdnsAdvertised}",
            desktopFormat(diag.failure, state.network.failure?.let { it::class.simpleName } ?: diag.noFailure),
            desktopFormat(
                diag.controlPlane,
                host.extension.controlPlanePort.takeIf { it > 0 } ?: diag.controlPlaneOff,
            ),
        ),
        peerLines = state.peers.map {
            val presence = if (it.online) diag.online else diag.offline
            "${it.displayName} (${it.nodeId.take(8)}) · $presence · UDP ${it.udpPort}/TCP ${it.tcpPort}"
        },
        // 过程节：编排水面下最近发生的事（ARP-8）。取的是当前环里**已发生**的行，
        // 所以导出与「用户点按钮」之间没有延迟窗口，也不会为了凑内容去阻塞控制面。
        recentLogLines = RelayLog.lines(),
    )
    DesktopNotifications.show(
        DesktopCopy.value.appTitle,
        report?.summary ?: diag.exportFailed,
    )
}

/**
 * 打开收件目录。
 *
 * 目录从 `dataDirectory` 推导（[desktopReceivedFilesDirectory]），与写入方
 * `RelayController` 的 `downloadDir` 同源。这里以前写死 `~/Downloads/榫卯`，
 * 而文件落在 `${dataDirectory}/downloads`，所以按钮打开的是空目录（审计 P1-9）。
 */
internal fun openDownloadsFolder(dataDirectory: String) {
    val dir = File(desktopReceivedFilesDirectory(dataDirectory))
    // 一个文件都没收过时目录还不存在：Finder/资源管理器打开不存在的路径是静默失败，
    // 用户看到的仍然只是「点了没反应」。先建出来，让空目录也真的被打开。
    if (!dir.isDirectory) dir.mkdirs()
    openDirectory(dir)
}

/**
 * 打开**共享目录**（文件柜那张卡上的「打开」）。
 *
 * 与 [openDownloadsFolder] 是**两个目录**：那条开的是数据 / 接收目录，这条开的是
 * 用户在文件柜里设的共享目录。打开错的目录和点了没反应一样是在骗人，所以这里
 * 只认传进来的那一个路径。
 *
 * 同 [openDownloadsFolder] 的处理：目录不存在时先建出来 —— 系统文件管理器打开一个
 * 不存在的路径是**静默失败**，用户看到的仍然只是「点了没反应」。
 */
internal fun openShareRoot(path: String) {
    val dir = File(path)
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
