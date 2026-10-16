// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import androidx.compose.ui.window.application
import app.relay.agent.ControlPlane
import app.relay.app.RelayController
import app.relay.app.createAgentStore
import app.relay.app.createMessageStore
import app.relay.app.createSettingsStore
import app.relay.core.protocol.DEFAULT_TIMINGS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 捎话桌面端入口。
 *
 * ## 装配顺序是有讲究的
 * 1. 先建平台实现与两个存储（设置 `settings.json`、消息 `messages.json`，都落在
 *    [DesktopAppPlatform] 给的持久目录里，不是工作目录）。
 * 2. 再建 [DesktopExtension] 并 `start()`，**最后**才 `controller.start()`。
 *    原因见 [DesktopExtension.start]：控制面靠轮询 `controller.activeRuntime()` 跟随，
 *    运行时是异步创建的，先挂扩展才不会错过创建时机。
 * 3. 退出时先把控制面摘掉、再停控制器 —— 顺序反了会在控制面还读着运行时时把端口抽走。
 *
 * ## 为什么用 [SupervisorJob]
 * 一个子协程失败不该带塌整棵协程树：某个 peer 的握手崩了，顶多丢一次握手，
 * 不该把托盘和控制面一起带走。
 *
 * ## 命令行参数与系统「打开方式」
 * [args] 与 [installDesktopOpenHandlers] 是同一件事的两个入口（冷启动走参数、进程已
 * 在运行走系统回调），两者都收口到 [handleExternalOpen]。判断顺序是**先深链后文件**：
 * `relay://` 是协议地址，只有它能变成一个节点；后缀判断放后面，免得某个叫
 * `relay://x.relaymsg` 的怪路径被两种规则各解释一次。
 */
fun main(args: Array<String>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var hostRefLocal: DesktopHost? = null
    val defaultDataDir = defaultDataDirectory()
    val defaultCacheDir = defaultCacheDirectory()
    val screenCaptureService = app.relay.desktop.capture.DesktopScreenCaptureService(
        scope = scope,
        cacheDirProvider = { defaultCacheDir },
        onCaptureWindowToggle = { visible ->
            if (visible) hostRefLocal?.requestShowMain() else hostRefLocal?.hideMain()
        },
    )
    val platform = DesktopAppPlatform(screenCapture = screenCaptureService)

    // 省电档必须在**构造控制器之前**读出来：Timings 是 NodeRuntime 的构造参数，
    // 起点档位决定了这次启动的心跳频率。界面上的开关只是后续切档的入口。
    val dataDirectory = platform.dataDirectory.dataDirectory()
    val timings = if (DesktopUiPrefs.loadPowerSaver(dataDirectory)) {
        DesktopPowerSaver.timings()
    } else {
        DEFAULT_TIMINGS
    }

    val controller = RelayController(
        platform = platform,
        store = createSettingsStore(platform),
        messageStore = createMessageStore(platform),
        agentStore = createAgentStore(platform),
        scope = scope,
        timings = timings,
        // 桌面端**承载 agent 节点**：
        // 依托系统托盘与常驻能力，固定监听回环 8788 并广播 `ag1`，内嵌承载控制面。
        advertiseAgent = true,
    )
    // 固定端口 8788：dsh-tidings-bridge 插件的 `baseUrl` 默认就是这个值，两边必须一致。
    // 传 `ControlPlane.DEFAULT_HTTP_PORT` 而不是字面量 8788 —— 单一定义点。
    val extension = DesktopExtension(
        controller = controller,
        scope = scope,
        httpPort = ControlPlane.DEFAULT_HTTP_PORT,
    )

    // 单进程单实例：桌面端不会存在第二个 application，所以文件级引用是安全的。
    // 有了它，Composable 里的退出回调不必把 controller/extension 层层往下传。
    controllerRef = controller
    extensionRef = extension

    // 必须早于 controller.start()，见上面的说明。
    extension.start()
    controller.start()

    val host = DesktopHost(
        controller = controller,
        scope = scope,
        extension = extension,
        screenCaptureService = screenCaptureService,
        // 登录自启（launchd / autostart / Run 注册项）带 --background：只驻留托盘。
        // 用户手点图标时不带这个参数，主窗照常出现。
        startHidden = DesktopLoginItem.wantsBackground(args.asList()),
    )
    hostRef = host
    hostRefLocal = host

    // 冷启动的深链/文件关联（macOS 从 Finder 双击、Linux .desktop 的 %u）在参数里。
    // 先于 application 处理：这样主窗第一次绘制时就已经切到「节点」视图，不会先闪一下
    // 会话列表再跳走。
    // --background 是启动开关而不是外部打开请求，必须排除：否则它会被当成一个
    // 无法解析的参数（虽然 handleExternalOpen 会忽略，但那属于「碰巧对」）。
    args.forEach { arg ->
        if (arg != DesktopLoginItem.BACKGROUND_FLAG) handleExternalOpen(arg)
    }

    // 进程已经在跑时，系统把 URL/文件交给这两个回调（macOS 的 open urls、Windows 的
    // 第二实例转发）。注册失败不影响主功能，见函数内注释。
    installDesktopOpenHandlers()

    // exitProcessOnExit=false：application 返回后我们自己做收尾（摘控制面 → 停运行时 → 停 scope）。
    application(exitProcessOnExit = false) {
        DesktopHostWindow(
            host = host,
            onExitApplication = ::shutdown,
        )
        // 会话独立窗口（D10）。放在同一个 application 组合里：它们和主窗共享同一个
        // RelayController（也就是同一份 NodeRuntime），只是 UI 状态各一份。
        DesktopPeerWindows(
            host = host,
            onExitApplication = ::shutdown,
        )
    }

    // application 返回（所有窗口关闭 / 显式退出循环）后收尾。
    shutdown()
    scope.cancel()
    kotlin.system.exitProcess(0)
}

private var shutDown = false

/** 幂等退出：先摘控制面，再停运行时。菜单「退出」与窗口层退出都会走这里。 */
private fun shutdown() {
    if (shutDown) return
    shutDown = true
    // stop() 是 suspend，而退出路径本来就是阻塞式的（退完就结束进程），
    // 所以这里 runBlocking 是对的：让 UDP/TCP 端口和定时器真正释放，而不是丢进一个将被取消的 scope。
    runCatching { runBlocking { extensionRef?.stop() } }
    runCatching { runBlocking { controllerRef?.stop() } }
}

/**
 * [application] 里的回调需要拿到装配好的扩展与控制器，而它们是在 main 里就地构造的。
 * 用两个文件级引用把它们暴露给 [shutdown]，避免把回调塞进 Composable 的参数列表里绕圈。
 */
private var extensionRef: DesktopExtension? = null
private var controllerRef: RelayController? = null
private var hostRef: DesktopHost? = null

/**
 * 注册系统级「用捎话打开」回调。
 *
 * 三种不注册的情形都是正常的，不该让启动失败：无图形环境（headless，例如 CI 里跑
 * 冒烟）、JDK 没实现该动作、系统策略拒绝。注册不上只意味着双击关联不生效，主窗、
 * 托盘、传输全都不受影响 —— 所以这里只留一行日志。
 */
private fun installDesktopOpenHandlers() {
    if (runCatching { java.awt.GraphicsEnvironment.isHeadless() }.getOrDefault(true)) return
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) {
            desktop.setOpenURIHandler { event -> handleExternalOpen(event.uri.toString()) }
        }
        if (desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler { event ->
                event.files.forEach { handleExternalOpen(it.absolutePath) }
            }
        }
    }.onFailure {
        println("捎话：未注册系统「打开方式」回调（${it.javaClass.simpleName}: ${it.message}），双击关联不可用。")
    }
}

/**
 * 外部入口统一收口：命令行参数、`relay://` URL 事件、`.relaymsg` 文件关联都走这里。
 *
 * 拿不到 controller/host（理论上只可能在装配完成前）就静默返回：这个函数会被系统在
 * 任意线程上调用，抛异常只会变成一次无人处理的线程崩溃。
 */
private fun handleExternalOpen(raw: String) {
    val controller = controllerRef ?: return
    val host = hostRef ?: return
    val text = raw.trim()
    if (text.isEmpty()) return

    val link = DesktopDeepLink.parse(text)
    if (link != null) {
        val (nodeHost, port) = link
        // 直接落成手动节点，而不是把值填进「添加节点」表单：表单状态在共享的
        // NodesScreen 里，桌面端不复制它。加完再切到节点视图，用户看到的是结果本身。
        controller.addManualPeer(nodeHost, port.toString())
        host.requestNodesView()
        host.requestShowMain()
        DesktopNotifications.show("捎话", "已从链接添加节点 $nodeHost:$port")
        return
    }

    if (DesktopDeepLink.isMessageFile(text)) {
        // 本期只做到「双击 = 打开主窗」——消息文件格式尚未定义，这里不解析、不投递。
        // 猜一个格式出来，只会造出别人打不开的文件。
        host.requestShowMain()
        DesktopNotifications.show("捎话", "捎话消息文件：本期只打开主窗，格式尚未定义")
        return
    }

    // 其余参数一律忽略。macOS 从 Finder 启动会塞一个 `-psn_0_12345`，Windows 会塞
    // 工作目录；把它们当节点地址加进去会凭空造出假节点。
}