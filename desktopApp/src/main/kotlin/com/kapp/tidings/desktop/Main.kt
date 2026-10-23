// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import androidx.compose.ui.window.application
import com.kapp.tidings.agent.ControlPlane
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.createAgentStore
import com.kapp.tidings.domain.createMessageStore
import com.kapp.tidings.domain.createSettingsStore
import com.kapp.tidings.core.protocol.DEFAULT_TIMINGS
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

    // **单实例**：数据目录只许一个进程独占。
    //
    // 实测过「没有它」的后果：两个进程读到同一个 nodeId、写同一个 relay.db 与同一份
    // messages.json，而 8788 只有一个能绑上 —— 另一个静默失败后继续跑，
    // 表现是「控制面时通时不通」+ 一份被两个写者交替改写的库，界面两边都正常。
    //
    // 拿不到锁 = 已经有本应用在跑：把**这一次的启动意图**转交给它，然后退出。
    // 用户点第二次图标应当看到窗口被唤到前面，而不是「点了没反应」。
    //
    // 目录不可写是另一类故障，**不许**在这里当成「已有实例」 —— 那会把一个真该
    // 让用户看见的错伪装成重复启动，而用户看到的就是「点了没反应」。
    val instanceLock = when (val acquire = SingleInstanceLock.tryAcquire(defaultDataDir)) {
        is SingleInstanceLock.Acquire.Acquired -> acquire.lock
        SingleInstanceLock.Acquire.Busy -> {
            println("relay: another instance is running; forwarding launch intent and exiting.")
            forwardIntentToRunningInstance(args)
            return
        }
        is SingleInstanceLock.Acquire.Unavailable -> {
            println("relay: instance lock unavailable (" + acquire.cause.javaClass.simpleName + "); starting anyway.")
            null
        }
    }
    val screenCaptureService = com.kapp.tidings.desktop.capture.DesktopScreenCaptureService(
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

    // `--headless`：只起运行时与控制面，**不组合任何 Compose**。
    //
    // 它不是给用户用的开关，而是两个问题的**判据**：
    // 1. 「守护进程形态要多少内存」—— 没有它就只能靠推算，而本仓的规矩是测；
    // 2. 「UI 是不是真的只是观察者」—— 如果去掉 UI 之后这个进程还能收发消息、
    //    控制面还能应答，那这个边界就是成立的，而不是"看起来分层了"。
    //
    // 判别放在 `application {}` **之前**：一旦进了 Compose 的 application 循环，
    // Skia 的库就被加载了，那一刻再想量"没有 UI"的水位就已经不纯了。
    if (HeadlessMode.requested(args.asList())) {
        println("relay: headless mode; ui not started. ctrl-c to stop.")
        // runBlocking 阻塞主线程 = 守护语义：进程活到被信号杀掉为止。
        // finally 里走同一条 shutdown，保证 UDP/TCP 端口与定时器真的释放。
        try {
            runBlocking { kotlinx.coroutines.awaitCancellation() }
        } finally {
            shutdown()
            scope.cancel()
        }
        return
    }

    // 按键诊断（默认关闭，`RELAY_KEY_TRACE=1` 才装）。装在 application 之前：
    // 它要看到的是"系统发给本进程的第一个按键"，晚于窗口创建就会漏掉开头几下。
    DesktopKeyTrace.install(dataDirectory)
    // 拖放诊断（默认关闭，`RELAY_DND_TRACE=1` 或数据目录里的 `dnd-trace.on` 才装）。
    // 与按键诊断同一立场：三种「拖了没反应」的成因截然不同，而屏幕上的表现一模一样。
    DesktopDndTrace.install(dataDirectory)
    // 补上 macOS + JDK 让空格丢掉的那一次 KEY_TYPED。常驻、默认生效 ——
    // 它是缺陷绕修而不是诊断开关，用户不该为了能打空格去开某个选项。
    DesktopSpaceKeyFix.install()
    SpaceLab.install(dataDirectory)

    // exitProcessOnExit=false：application 返回后我们自己做收尾（摘控制面 → 停运行时 → 停 scope）。
    application(exitProcessOnExit = false) {
        // 应用级组合：托盘/通知/深链与主窗是否存在无关。
        // 主窗由它内部按 mainVisible 懒建（见 DesktopApp 的 KDoc）。
        DesktopApp(
            host = host,
            onExitApplication = ::shutdown,
        )
        // 会话独立窗口（D10）。放在同一个 application 组合里：它们和主窗共享同一个
        // RelayController（也就是同一份 NodeRuntime），只是 UI 状态各一份。
        DesktopPeerWindows(
            host = host,
            onExitApplication = ::shutdown,
        )
        // 空格诊断的最小对照窗口（默认关闭，标记文件 `space-lab.on` 才出现）。
        // 它同进程、同 Compose 栈、同 AWT 桥，只把"产品壳"这一层拿掉 —— 用来二分
        // "空格缺陷在壳层还是在 CMP 桥接"。见 [SpaceLab] 的 KDoc。
        if (SpaceLab.isEnabled()) {
            SpaceLabWindow()
        }
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
        // 「再点一次」——程序坞单击、Finder 里双击已运行的 .app、`open -a` 不带参数。
        //
        // 这条**必须**注册：主窗是懒建的（关闭 = 销毁，不是隐藏），所以「窗口没了」
        // 是正常状态，而不是退出。没有这个回调，那些动作只会 activate 进程，
        // 而进程里没有任何人把窗口建出来 —— 用户看到的是「点了没反应」。
        // 实测：装上产物后 `open -a /Applications/Tidings.app` 前后 footprint 一点没变
        // （278.4M → 279.2M，IOSurface 仍为 0），正是缺这一环。
        if (java.awt.Desktop.isDesktopSupported()) {
            desktop.addAppEventListener(
                java.awt.desktop.AppReopenedListener { hostRef?.requestShowMain() },
            )
        }
    }.onFailure {
        println("捎话：未注册系统「打开方式」回调（${it.javaClass.simpleName}: ${it.message}），双击关联不可用。")
    }
}

/**
 * 已经有实例在跑时，把这一次的启动意图转交给它。
 *
 * ## 为什么不是"直接退出"
 *
 * 用户点第二次图标 / 双击第二个 `.relaymsg` 时，他的意图**不是**"再开一个进程"，
 * 而是"我要看那个东西"。静默退出会让这件事看起来像点了没反应 —— 而这正是本仓
 * 反复修的那类缺陷（能力在、路在、但用户看不到反馈）。
 *
 * ## 交接口径
 *
 * - `relay://host:port` 与 `.relaymsg` 路径：走系统「用捎话打开」那条既有通路
 *   （`java.awt.Desktop.setOpenURIHandler / setOpenFileHandler`）交给已运行的实例。
 *   系统会按 `CFBundleURLTypes` / 文件关联找到**那个**实例 —— 这也是它唯一可靠的地方：
 *   我们手上没有已运行实例的 pid（锁文件里那个 pid 是上一次写下的，崩溃后可能是别的
 *   进程的号）。
 * - 没有任何参数（用户又点了一次图标）：让已运行实例把主窗叫出来。同样走系统通路 ——
 *   对 macOS 用 `open -a` 对本应用打一次不带参数的「activate」。
 *
 * 三条都失败（无图形环境、系统拒绝）只记一行：这个进程马上就要退出了，
 * 在这里抛异常只会变成一次没有上下文的崩溃。
 */
private fun forwardIntentToRunningInstance(args: Array<String>) {
    if (java.awt.GraphicsEnvironment.isHeadless()) return
    // 用系统的 open 命令，而不是 java.awt.Desktop：只有它能把请求交给**已经运行的那一个**
    // 实例。不带路径 = activate（把窗口叫到前面）；带路径 = 走既有的打开方式通路。
    val openable = args.filter { it.isNotBlank() && it != DesktopLoginItem.BACKGROUND_FLAG }
    val bundle = System.getProperty("compose.application.resources.dir")
        ?.let { java.io.File(it).parentFile?.parentFile?.parentFile }
    // 不带参数 = 用户只是又点了一次图标。这里**必须**发一条能表达"要看见窗口"的意图：
    // 只 activate 进程是不够的 —— 主窗是懒建的，它只认 showMainTicks。
    // 实测过缺这一句的后果：第二个进程正确退出、第一个进程什么都没发生
    // （footprint 与 IOSurface 一点都不变），用户看到的是"点了没反应"。
    val requested = openable.ifEmpty { listOf(DesktopDeepLink.SHOW_MAIN_URL) }
    val command = buildList {
        add("open")
        if (bundle != null && bundle.name.endsWith(".app")) {
            add("-a")
            add(bundle.absolutePath)
        }
        requested.forEach { add(it) }
    }
    runCatching {
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().forEachLine { line -> println("relay forward: " + line) }
    }.onFailure {
        // 诊断行，不是用户可见文案（用户看不到 stdout）：走 ASCII，避免 CJK 门禁基线
        // 为一个 stderr 日志多记一条 —— 那会让「文案资源化」这条纪律被日志噪声稀释。
        println("relay: could not forward launch intent (" + it.javaClass.simpleName + "); exiting.")
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

    // 「把主窗叫出来」那条**先判**：它是转交通道的唯一载荷，且不带端口，
    // 不会被 [DesktopDeepLink.parse] 误当成节点地址（那个函数要求 host:port）。
    if (DesktopDeepLink.isShowMain(text)) {
        host.requestShowMain()
        return
    }

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