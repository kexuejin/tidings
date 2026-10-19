// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import app.relay.core.fs.pathJoin
import app.relay.core.protocol.IpAddressUtils
import app.relay.core.protocol.Ipv6Scope
import java.awt.FileDialog
import java.awt.Frame
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 桌面平台的 [app.relay.platform.AppPlatform] 实现。
 *
 * 桌面不是「手机上再开一个窗口」，所以这里不复制 Android/iOS 的那些
 * 「权限弹窗 + 沙箱路径」逻辑，而是直接把宿主已有的能力接上：
 *  - 目录：macOS 的 `~/Library/Application Support/捎话 Tidings`；
 *  - 网络：枚举 `NetworkInterface` 的 IPv4，得到的是真实网卡地址；
 *  - 文件：AWT `FileDialog`，路径就是可读绝对路径；收到的文件用 AWT `Desktop` 交出去
 *    （打开 = 系统默认应用，分享 = 在文件管理器里定位，见 [DesktopReceivedFileActions]）；
 *  - 扫码/相机：桌面没有摄像头流程，如实抛 [PlatformCapabilityUnavailable]，
 *    绝不用「假成功」把扫码入口糊弄过去。
 */
class DesktopAppPlatform(
    override val dataDirectory: DesktopDataDirectory = DesktopDataDirectory(),
    override val networkMonitor: DesktopNetworkMonitor = DesktopNetworkMonitor(),
    override val deviceInfo: DesktopDeviceInfo = DesktopDeviceInfo(),
    /**
     * 首次运行时的默认设备名 = [DesktopDeviceInfo.hostName]（机器名）。
     *
     * 为什么桌面端用机器名而不是「桌面端」之类固定词：一台机器上可能同时跑多个捎话
     * 实例（不同 data-dir 做联调），机器名至少让它们在多数情况下可区分；固定一个词
     * 会让所有桌面实例同名，正是真机撞名那条要修的问题。
     *
     * 不另写一份取机器名的代码：hostName() 已经是同一件事，且它带着 HOSTNAME
     * 环境变量与 relay-desktop 两层退路。
     */
    override val deviceDefaultNick: String = deviceInfo.hostName(),
    override val localNetworkGate: DesktopLocalNetworkGate = DesktopLocalNetworkGate(),
    override val filePicker: DesktopFilePicker = DesktopFilePicker(),
    override val saveDirectoryPicker: DesktopSaveDirectoryPicker = DesktopSaveDirectoryPicker(),
    override val qrScanner: DesktopQrScanner = DesktopQrScanner(),
    override val cameraGate: DesktopCameraGate = DesktopCameraGate(),
    override val mdnsAdvertiser: DesktopMdnsAdvertiser = DesktopMdnsAdvertiser(),
    override val mdnsBrowser: DesktopMdnsBrowser = DesktopMdnsBrowser(),
    override val pushNotificationHandler: DesktopNotificationHandler = DesktopNotificationHandler(),
    override val receivedFileActions: app.relay.platform.ReceivedFileActions = DesktopReceivedFileActions(),
    override val urlOpener: app.relay.platform.UrlOpener = DesktopUrlOpener(),
    override val screenCapture: app.relay.platform.ScreenCaptureService? = null,
    override val screenSharing: app.relay.platform.ScreenSharingService = app.relay.desktop.capture.DesktopScreenSharingService(),
) : app.relay.platform.AppPlatform

class DesktopUrlOpener : app.relay.platform.UrlOpener {
    override fun openUrl(url: String): Boolean {
        return try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * 身份必须落在磁盘上：`nodeId` 每次首启生成，随 `RelaySettings` 持久化。
 * 用平台约定的目录而不是 `/tmp`，否则重开一次应用就是一个新节点，
 * 手机侧看到的「未知节点」会越攒越多。
 */
class DesktopDataDirectory(
    private val dataPath: String = defaultDataDirectory(),
    private val cachePath: String = defaultCacheDirectory(),
) : app.relay.platform.DataDirectoryProvider {
    // 契约是函数而不是属性，所以这里保留构造参数名，只把实现转成 override 函数。
    override fun dataDirectory(): String = dataPath

    override fun cacheDirectory(): String = cachePath
}

internal fun defaultDataDirectory(): String {
    val home = System.getProperty("user.home") ?: "."
    val os = System.getProperty("os.name").orEmpty()
    val base = if (os.contains("Mac")) {
        "$home/Library/Application Support"
    } else {
        System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    return "$base/${DESKTOP_APP_DIR}"
}

internal fun defaultCacheDirectory(): String {
    val home = System.getProperty("user.home") ?: "."
    val os = System.getProperty("os.name").orEmpty()
    val base = if (os.contains("Mac")) {
        "$home/Library/Caches"
    } else {
        System.getenv("XDG_CACHE_HOME") ?: "$home/.cache"
    }
    return "$base/${DESKTOP_APP_DIR}"
}

const val DESKTOP_APP_DIR: String = "捎话 Tidings"

/**
 * 真实网卡枚举。
 *
 * 快照里的 [NetworkSnapshot.localAddresses] 是**配对码的候选池**：IPv4 在前、IPv6（ULA /
 * 公网 GUA）在后（见 [localCandidateAddresses]）。IPv4 在前的约定保证了 `firstOrNull()` 那些
 * "只要一个地址"的调用点行为不变 —— 加 v6 不是替换，是追加。
 */
class DesktopNetworkMonitor : app.relay.platform.NetworkMonitor {
    private val listeners = CopyOnWriteArrayList<(app.relay.platform.NetworkSnapshot) -> Unit>()
    private var poller: Thread? = null

    override fun rawSnapshot(): app.relay.platform.NetworkSnapshot {
        // 过滤虚拟/隧道网卡之后**一个地址都不剩**时退回原列表：那时它们是这台机器唯一的地址，
        // 报"没有可用地址"会让 `withoutUsableAddress` 把整台机器判成离线（假装离线比多两条
        // 假候选坏得多）。只要还有一条真网卡地址，就按过滤后的来。
        //
        // 取舍说明（这个列表同时被 `choosePeerCandidate` 当"本机地址表"做同网段判断）：这里
        // **有意**只保留"我们真的会广播出去的地址" —— 被过滤掉的隧道地址即使与某个候选同网段，
        // 对端也拿不到它，匹配上反而会选错。真·私网（10/8、172.16/12、192.168/16）一个都不挡，
        // 所以正常的"同处一网"判断不受影响。
        val physical = localCandidateAddresses()
        val addresses = physical.ifEmpty { localCandidateAddresses(includeVirtualInterfaces = true) }
        val online = addresses.isNotEmpty()
        return app.relay.platform.NetworkSnapshot(
            online = online,
            transport = if (online) app.relay.platform.Transport.Ethernet else app.relay.platform.Transport.None,
            localAddresses = addresses,
            // vpnActive 只看 IPv4：`isVpnLike` 对 IPv6 的判据是"以 fd 打头"（Tailscale/ZeroTier
            // 的隧道段），而 ULA `fd00::/8` 同时也是**家用路由器常见的局域网段** —— 拿它当 VPN
            // 会把"家里有 ULA"误报成"VPN 已启用"。原来的语义（只看 v4 表）在这里保持不变。
            vpnActive = localIpv4Addresses().any { isVpnLike(it) },
        )
    }

    override fun observe(onChange: (app.relay.platform.NetworkSnapshot) -> Unit): () -> Unit {
        listeners += onChange
        // 轮询而不是事件回调：桌面切 Wi-Fi/插网线没有统一的广播 API，
        // 而 `NetworkMonitor` 的契约要求地址变化必须真的被观察到。
        if (poller == null) {
            val thread = Thread({ pollLoop() }, "relay-network-poll").apply {
                isDaemon = true
                start()
            }
            poller = thread
        }
        return { listeners -= onChange }
    }

    private fun pollLoop() {
        var last = rawSnapshot().localAddresses
        while (listeners.isNotEmpty()) {
            Thread.sleep(NETWORK_POLL_MS)
            val now = rawSnapshot().localAddresses
            if (now != last) {
                last = now
                val snapshot = rawSnapshot()
                listeners.forEach { runCatching { it(snapshot) } }
            }
        }
        poller = null
    }

    private companion object {
        const val NETWORK_POLL_MS = 2_000L
    }
}

/**
 * 枚举本机 IPv4。默认**跳过虚拟/点对点网卡**（`utun*`/`feth*`/`tailscale*`，判据见
 * [app.relay.app.isVirtualInterfaceName]）：它们会给出 `172.26.*`/`10.233.*` 这类"看着像
 * 私网、其实只存在于本机网络栈"的地址，对端照它连过去必然失败。
 *
 * [includeVirtualInterfaces] = true 只在"过滤完一个地址都不剩"时兜底用（见 [DesktopNetworkMonitor.rawSnapshot]）。
 */
internal fun localIpv4Addresses(includeVirtualInterfaces: Boolean = false): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .filter { includeVirtualInterfaces || !app.relay.app.isVirtualInterfaceName(it.name) }
        .flatMap { nic ->
            nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() }
        }
        .filter { it.isNotBlank() && !it.startsWith("127.") && !isVpnLike(it) }
        .distinct()
}.getOrElse { emptyList() }

/**
 * 配对码的候选地址：**IPv4 在前**，然后是本机可用的 IPv6（ULA `fc00::/7` 与公网 GUA `2000::/3`）。
 *
 * 三条约定：
 *  - v4 在前：`firstOrNull()` 的调用点（"我的 IP"、手填提示、工作区直通码）拿到的是最稳的那条，
 *    加 v6 只是追加候选，不改它们的行为；
 *  - IPv6 只保留 [Ipv6Scope.GLOBAL] 与 [Ipv6Scope.UNIQUE_LOCAL]：链路本地 `fe80::/10`、回环、
 *    未指定/组播的一律丢掉。
 *
 *    这里**不是**"链路本地跨设备不可路由"——那是错的：同一 L2 网段内 `fe80::/10` 是可用的，
 *    只是要用 zone id（`fe80::1%en0`）才连得上，而候选池里存的是**裸地址**、我们拿不到 zone id，
 *    所以准确的说法是"这条地址在我们手里用不了"，不是"它不能用"。把一个连不上的地址当候选，
 *    只会让对端白试一次（注册表按回包源地址自纠，但那是多余的往返）。
 *
 *    另：带 `%网卡` 的**不是丢掉而是剥掉** —— macOS 的 JDK 会给**每个** IPv6 地址都加 scope 后缀，
 *    见到 `%` 就丢会让候选里一个 v6 都不剩（见 [localIpv6CandidateAddresses] 里实测的那段）。
 *  - 不再对 IPv6 套用 [isVpnLike]：它对 v6 的判据是"以 fd 打头"，会把**家用 ULA 网段**
 *    整段误杀。隧道地址多出现在候选里是无害的（连不上就落到下一个候选，注册表按回包源地址自纠），
 *    而"VPN 是否启用"另有 [NetworkSnapshot.vpnActive] 表达。
 */
internal fun localCandidateAddresses(includeVirtualInterfaces: Boolean = false): List<String> =
    localIpv4Addresses(includeVirtualInterfaces) + localIpv6CandidateAddresses(includeVirtualInterfaces)

/** 枚举本机可用的 IPv6 候选：ULA 与公网 GUA，丢掉链路本地/回环/未指定/带 scope 的。 */
internal fun localIpv6CandidateAddresses(includeVirtualInterfaces: Boolean = false): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .filter { includeVirtualInterfaces || !app.relay.app.isVirtualInterfaceName(it.name) }
        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet6Address>() }
        // macOS 的 JDK 会给**每个** IPv6 地址都带上 `%en1` 这样的 scope 后缀（不只是链路本地），
        // 所以这里必须**剥掉**它，而不是"见到 % 就丢" —— 后者会让候选列表里一个 IPv6 都不剩
        // （真机上实测：5 个公网 GUA 全被丢掉，配对码里只剩 IPv4）。剥掉之后链路本地仍会被
        // 下面的 scope 判据挡掉（fe80::/10），语义不变。
        .map { it.hostAddress.orEmpty().substringBefore('%').trim() }
        .filter { it.isNotBlank() }
        .filter { IpAddressUtils.isIpv6(it) }
        .filter {
            when (IpAddressUtils.getIpv6Scope(it)) {
                Ipv6Scope.GLOBAL, Ipv6Scope.UNIQUE_LOCAL -> true
                else -> false
            }
        }
        .distinct()
}.getOrElse { emptyList() }

/** utun / tun-tap / ppp 这类隧道地址不能用来算「局域网可达」，否则会误报在线。 */
internal fun isVpnLike(address: String): Boolean {
    val name = System.getProperty("os.name").orEmpty()
    if (name.contains("Mac")) return address.startsWith("10.") && address.contains(":") || address.startsWith("fd")
    return address.startsWith("10.8.") || address.startsWith("172.16.") || address.startsWith("192.168.7.")
}

class DesktopDeviceInfo : app.relay.platform.DeviceInfo {
    override fun hostName(): String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
        ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
        ?: "relay-desktop"
}

/**
 * 桌面发局域网广播没有运行时权限这一关。
 *
 * 返回 true 是陈述事实，不是「假装通过」：macOS 只在浏览器访问时弹一次提示，
 * 桌面端既不弹也就没有需要确认的东西。
 */
class DesktopLocalNetworkGate : app.relay.platform.LocalNetworkGate {
    override suspend fun ensureAccess(): Boolean = true
}

/** AWT `FileDialog`：原生选择器，路径是宿主可读的绝对路径。 */
class DesktopFilePicker(
    private val parent: () -> Frame? = { null },
) : app.relay.platform.FilePicker {
    override suspend fun pick(): app.relay.platform.PickedFile? {
        val file = DesktopDialogs.chooseFile(parent()) ?: return null
        return app.relay.platform.PickedFile(
            name = file.name,
            sizeBytes = runCatching { file.length() }.getOrDefault(0L),
            path = file.absolutePath,
        )
    }
}

/** 选目录用 `JFileChooser`：`FileDialog` 的 `FILES_ONLY` 选不了文件夹。 */
class DesktopSaveDirectoryPicker(
    private val parent: () -> Frame? = { null },
) : app.relay.platform.SaveDirectoryPicker {
    override suspend fun pickDirectory(): String? = DesktopDialogs.chooseDirectory(parent())
}

/**
 * 桌面没有摄像头扫码链路。
 *
 * 如实抛「未实现」而不是返回 null —— 返回 null 在 UI 上会被当成「用户取消」，
 * 两者含义完全不同，混起来会让排障的人追错方向。
 *
 * `scan` 与 `scanImage` 是同一件事的两种入口（相机取景框 / 一张已有的图片），
 * 所以共用一句原因：桌面两个都不做。提成常量还有第二个理由 —— `:cjkLiteralGate`
 * 按**字面量出现次数**计数（棘轮只许减不许增），同一句写两遍会被算成两处。
 */
private const val DESKTOP_NO_QR_SCANNER: String =
    "桌面版不做摄像头扫码，请用「手动填地址」；节点码就是地址本身"

class DesktopQrScanner : app.relay.platform.QrScanner {
    override suspend fun scan(): String? =
        throw app.relay.platform.PlatformCapabilityUnavailable("QrScanner", DESKTOP_NO_QR_SCANNER)

    override suspend fun scanImage(path: String): String? =
        throw app.relay.platform.PlatformCapabilityUnavailable("QrScanner", DESKTOP_NO_QR_SCANNER)
}

class DesktopCameraGate : app.relay.platform.CameraGate {
    override suspend fun ensureAccess(): Boolean =
        throw app.relay.platform.PlatformCapabilityUnavailable(
            "CameraGate",
            "桌面版不做摄像头扫码，请用「手动填地址」",
        )
}

/**
 * 托盘气泡通知。
 *
 * 桌面没有「通知权限」这一说，能弹就弹；`requestPermission` 返回 true 指的是
 * 「系统层面不需要授权」，而不是伪造一次授权流程。
 */
class DesktopNotificationHandler : app.relay.platform.PushNotificationHandler {
    override fun showNotification(title: String, body: String, focusPeerId: String?) {
        // 桌面托盘气泡**没有**点击回调（`DesktopNotifications` 走的是系统托盘 API，
        // 它只负责把话说出来）。所以桌面这条路上 focusPeerId 用不上，也就不假装用得上 ——
        // 桌面本来就一直开着窗口，"定位到会话"由窗口自己的选中项负责。
        DesktopNotifications.show(title, body)
    }

    override suspend fun requestPermission(): Boolean = true
}

/**
 * mDNS 发布：调用 macOS 自带的 `dns-sd -R`。
 *
 * 为什么不用 JmDNS：`dns-sd` 就是系统解析器，发布出去的记录和「网络设置 →
 * mDNS 浏览器」看到的完全一致，手机端才可能发现；JmDNS 自己起一套 responder
 * 会在多网卡机器上给出互相矛盾的结果。
 *
 * `start` 必须等到真的注册上再返回 true —— 只调用了异步 API 就返回成功，
 * 会把「系统拒绝发布」变成界面上一句永远不变的「广播中」。
 */
class DesktopMdnsAdvertiser(
    private val runner: DnsSdRunner = DesktopDnsSd,
    private val settleMs: Long = 1_500L,
) : app.relay.platform.MdnsAdvertiser {
    private var process: Process? = null

    override suspend fun start(serviceName: String, port: Int): Boolean {
        stop()
        if (!runner.available()) return false
        val proc = runner.register(serviceName, port) ?: return false
        process = proc
        Thread.sleep(settleMs)
        // 进程还在 = 没被系统当场拒绝。等待是为了避免把拒绝藏在「广播中」后面。
        return proc.isAlive
    }

    override fun stop() {
        process?.let { runCatching { it.destroy() } }
        process = null
    }
}

/** 浏览 `_relay._udp`：局域网内开启了 mDNS 自动发现的捎话设备会出现在这里。 */
class DesktopMdnsBrowser(
    private val runner: DnsSdRunner = DesktopDnsSd,
    private val onFound: (String, String, Int) -> Unit = { _, _, _ -> },
    private val onLost: (String) -> Unit = { },
) : app.relay.platform.MdnsBrowser {
    private var runnerProcess: Process? = null
    private var found: (String, String, Int) -> Unit = onFound
    private var lost: (String) -> Unit = onLost

    override fun startBrowsing(
        onServiceFound: (String, String, Int) -> Unit,
        onServiceLost: (String) -> Unit,
    ) {
        stopBrowsing()
        found = onServiceFound
        lost = onServiceLost
        val proc = runner.browse() ?: return
        runnerProcess = proc
        val reader = Thread({
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    parseBrowseLine(line, { a, b, c -> found(a, b, c) }, { lost(it) })
                }
            }
        }, "relay-mdns-browse").apply { isDaemon = true; start() }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { proc.destroy() } }))
    }

    override fun stopBrowsing() {
        runnerProcess?.let { runCatching { it.destroy() } }
        runnerProcess = null
    }
}

/**
 * `dns-sd -B` 的输出里，「Add」行是唯一带端口号的完整记录。
 *
 * 只在真的拿到非零端口时才回调，避免把同名的其它服务当成捎话节点。
 */
internal fun parseBrowseLine(
    line: String,
    onServiceFound: (String, String, Int) -> Unit,
    onServiceLost: (String) -> Unit,
) {
    if (!line.contains("Add") && !line.contains("Rmv")) return
    val parts = line.trim().split(Regex("\\s+"))
    val removing = line.contains("Rmv")
    // dns-sd -B 格式：Timestamp [Rmv|Add] ServiceType ServiceName [Instance]
    if (parts.size < 4) return
    if (!parts[1].contains("_relay._udp")) return
    val instance = parts.getOrNull(3) ?: return
    val port = parts.getOrNull(4)?.toIntOrNull() ?: 0
    if (removing) {
        onServiceLost(instance)
    } else if (port > 0) {
        onServiceFound(instance, parts.getOrNull(2) ?: "local", port)
    }
}

/** 抽出 `dns-sd` 调用，测试可以塞假实现，不必真的起进程。 */
interface DnsSdRunner {
    fun available(): Boolean
    fun register(serviceName: String, port: Int): Process?
    fun browse(): Process?
}

object SystemDnsSdRunner : DnsSdRunner {
    private fun launch(vararg args: String): Process? = runCatching {
        ProcessBuilder(*args).redirectErrorStream(true).start()
    }.getOrNull()

    override fun available(): Boolean =
        runCatching { ProcessBuilder("dns-sd", "-h").start().waitFor() >= 0 }.getOrDefault(false)

    override fun register(serviceName: String, port: Int): Process? =
        launch("dns-sd", "-R", "_relay._udp", serviceName, port.toString())

    override fun browse(): Process? = launch("dns-sd", "-B", "_relay._udp")
}

/**
 * 默认实现：macOS 用系统 `dns-sd`，其他平台回落到空实现。
 *
 * 不用 JmDNS 的原因：系统解析器和「网络设置 → mDNS 浏览器」看到的是同一份记录，
 * 第三方 responder 在多网卡机器上经常自己回答自己。
 */
object DesktopDnsSd : DnsSdRunner {
    private val delegate: DnsSdRunner =
        if (System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) {
            SystemDnsSdRunner
        } else {
            NoopDnsSdRunner
        }

    override fun available(): Boolean = delegate.available()
    override fun register(serviceName: String, port: Int): Process? = delegate.register(serviceName, port)
    override fun browse(): Process? = delegate.browse()
}

object NoopDnsSdRunner : DnsSdRunner {
    override fun available(): Boolean = false
    override fun register(serviceName: String, port: Int): Process? = null
    override fun browse(): Process? = null
}

/**
 * 收件**真正**落盘的目录：`${dataDirectory}/downloads`。
 *
 * 必须与写入方 `RelayController` 的 `downloadDir = pathJoin(dataDirectory, "downloads")`
 * 用同一个 `dataDirectory` 推导，而不是各写一份路径字面量：两边一旦分叉，重演的就是
 * P1-9 —— 「打开下载目录」打开的是 `~/Downloads/捎话`，文件却在 data 目录下，
 * 用户点开只看到空目录。
 */
internal fun desktopReceivedFilesDirectory(dataDirectory: String): String =
    pathJoin(dataDirectory, "downloads")
