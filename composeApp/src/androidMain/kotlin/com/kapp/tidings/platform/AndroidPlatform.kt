// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.core.transport.NetworkBinderHolder
import com.kapp.tidings.core.transport.NetworkWatch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kapp.tidings.domain.isVirtualInterfaceName
import com.kapp.tidings.core.protocol.IpAddressUtils
import com.kapp.tidings.core.protocol.Ipv6Scope
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Android 平台实现。
 *
 * 与脚手架的 `AndroidPlatformAdapters` 的区别：那里八个类都是
 * `override suspend fun invoke(id: String): Boolean = false`，什么都不做；
 * 这里每个成员都真的去问系统要那个值。
 *
 * **权限**：`AndroidManifest.xml` 必须声明 `INTERNET`（开 UDP/TCP socket）、
 * `ACCESS_NETWORK_STATE`（读传输类型）与 `POST_NOTIFICATIONS`（13+ 发本地通知）。
 * 少了第一个，`DatagramSocket` 会直接抛 `SocketException: Permission denied`；
 * 少了第三个，通知**永远不出现**且没有任何报错 —— 两者构建都全绿，只有真机跑才暴露。
 * 我们**不**声明 `CHANGE_WIFI_MULTICAST_STATE`：它管的是**接收**组播，
 * 而手机只做发送侧广播（见 `Discovery`）。
 *
 * **文件选择走 SAF**：`filePicker` / `saveDirectoryPicker` 用系统的
 * `ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`，所以不需要任何存储权限 ——
 * 用户选中的那一刻，授权就只覆盖他选的那一个 URI。
 *
 * **为什么旁边要挂一根 [pickerBridge]**：启动器必须在 Activity/Compose 里注册，而
 * 这个类是在 `Application` 里构造的 —— 一个是进程级、一个是界面级，生命周期对不上。
 * 与其让平台层去猜当前是哪个 Activity（那会拖进内存泄漏和 Activity 重建的坑），
 * 不如让 Compose 层把启动器显式交进来：选择结果经 `deliver` 回填，挂起的 `pick()`
 * 才拿得到。源码里同一时刻只有一根桥，也就没有"选到一半换了界面"的歧义。
 *
 * **八个契约现在全部有真实实现**（见本文件底部的 override）：`qrScanner` 用 CameraX +
 * ML Kit barcode-scanning，`mdnsAdvertiser` / `mdnsBrowser` 用 `NsdManager`，
 * `pushNotificationHandler` 用 `NotificationManager` 发**本地**通知 ——
 * 远程推送（FCM / UnifiedPush）需要服务端，还没接（计划里的 P5）。
 */
class AndroidAppPlatform(
    val context: Context,
    /**
     * 投屏前台服务类（宿主 `androidApp` 清单里那个）。**平台层不认识它**——清单组件
     * 属于 APK，只能由 Application 在构造平台时递进来；传 null（纯测试宿主）时
     * [screenSharing] 的 isShareSupported 如实回答不支持，caps 不虚报。
     */
    private val screenShareServiceClass: Class<*>? = null,
    /**
     * 「后台保持连接」前台服务类（宿主 `androidApp` 清单里那个）。与
     * [screenShareServiceClass] 同一条边界：清单组件属于 APK，只能由 Application
     * 在构造平台时递进来。传 null（纯测试宿主）时 [backgroundConnection] 的
     * isSupported 如实回答 false，设置页据此不画那个开关。
     */
    private val keepAliveServiceClass: Class<*>? = null,
    /**
     * 首次运行时这台设备的默认显示名，由 Android 宿主从自己的资源里取（本地化文案）。
     *
     * 传空串是测试与纯逻辑宿主的走法：那时退回 [deviceInfo] 报的机型名，不编造。
     */
    private val defaultNick: String = "",
) : AppPlatform, QrPreviewProvider, AndroidDatabaseHost {

    /**
     * 开 SQLite 库用的 Context（见 [AndroidDatabaseHost]）。
     *
     * 用 `applicationContext` 而不是传入的那个 `context`：库驱动的生命周期与进程一致，
     * 而 Activity context 会把它拴在一个已经销毁的界面上。
     */
    override val databaseContext: Context
        get() = context.applicationContext

    /** 实验 luciola 传输(默认关,由设置项门控;AAR 的 jniLibs 提供原生库)。 */
    override val luciolaHost: LuciolaHost = AndroidLuciolaHost(context)

    override val dataDirectory: DataDirectoryProvider = object : DataDirectoryProvider {
        override fun dataDirectory(): String =
            File(context.filesDir, "relay").apply { mkdirs() }.absolutePath

        override fun cacheDirectory(): String =
            File(context.cacheDir, "relay").apply { mkdirs() }.absolutePath
    }

    override val deviceDefaultNick: String get() {
        val systemDeviceName = try {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        } catch (_: Throwable) {
            null
        } ?: try {
            android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        } catch (_: Throwable) {
            null
        }
        return systemDeviceName?.trim()?.ifBlank { null }
            ?: Build.MODEL?.trim()?.ifBlank { null }
            ?: defaultNick.ifBlank { "Android" }
    }

    override fun isDefaultNick(nick: String): Boolean = isDefaultNickExact(nick, defaultNick)

    /** 对端节点表里显示的 `host`。用机型而不是应用名。 */
    override val deviceInfo: DeviceInfo = object : DeviceInfo {
        override fun hostName(): String =
            listOfNotNull(Build.MANUFACTURER, Build.MODEL)
                .joinToString(" ")
                .ifBlank { "Android" }
    }

    init {
        // K3 ③：把「现在是不是省电/空闲窗口」这一问句装进 core 的会合点 ——
        // 长连接（LinkClient）据此在 Doze 里**跳过**那一轮心跳（降频，不是停发）。
        // isDeviceIdleMode 需要 API 23；minSdk 24，所以不必写版本判断。
        // 不装的话 NetworkWatch.isIdleNow() 恒 false，行为与 K3 之前一字不差。
        NetworkWatch.installIdleProvider {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.isDeviceIdleMode == true
        }
        // task-85：把「用哪张网发」装进 core 的会合点。
        //
        // 不装的话，跨网长连接在「WiFi 是默认网络、而它的 v6 出不去」时就发不出包 ——
        // 真机现场 `nc -6` 报 `Network is unreachable`，而蜂窝那条 v6 明明通。
        // 探测端口取 TLS 长连接端口（17880 = udp + 2，与 Profile.mobile 的布局一致）。
        NetworkBinderHolder.install(
            AndroidNetworkBinder(context, probePort = { Protocol.DEFAULT_UDP_PORT + 2 }),
        )
    }

    override val networkMonitor: NetworkMonitor = object : NetworkMonitor {
        override fun rawSnapshot(): NetworkSnapshot {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = manager?.activeNetwork
            val caps = active?.let { manager.getNetworkCapabilities(it) }

            val transport = when {
                caps == null -> Transport.None
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.Vpn
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.Wifi
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
                else -> Transport.None
            }
            val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            // 有 VPN 时，局域网广播到不了对端（Tailscale/ZeroTier 是 L3/L2 覆盖网），
            // UI 要据此提醒用户改用单播直连。
            val vpnActive = manager?.allNetworks?.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } ?: false

            // 候选池 = v4 + v6（v4 在前，`firstOrNull()` 的调用点行为不变）。过滤掉虚拟/隧道
            // 网卡（`tun*` 就是 Android 的 VPN 口）与代理 fake-IP 段之后一个地址都不剩时退回原
            // 列表：那时它们是唯一的地址，报"没有可用地址"会让 `withoutUsableAddress` 把手机判成
            // 离线。取舍与桌面端同一条（见 `DesktopNetworkMonitor.rawSnapshot`）。
            val physical = localIpv4Addresses() + localIpv6CandidateAddresses()
            val localAddresses = physical.ifEmpty {
                localIpv4Addresses(includeVirtualInterfaces = true) +
                    localIpv6CandidateAddresses(includeVirtualInterfaces = true)
            }

            return NetworkSnapshot(
                online = online,
                transport = transport,
                localAddresses = localAddresses,
                vpnActive = vpnActive,
                // **宣告地址必须含隧道口**（`tun*` 就是 Android 的 VPN 口）。
                //
                // 上面那份 [localAddresses] 服务「广播 / 配对码 / 同网段判断」，刻意窄；
                // 而 `Profile.addresses` 是**单播**告诉已配对的对端「该往哪儿拨」——
                // 手机只留 VPN 时，那条隧道地址是它**唯一**还能被拨到的路径。
                //
                // 不覆盖会走接口默认值（= localAddresses，窄）。真机上曾经看起来「能用」，
                // 只是因为当时 `tun*` 的接口名没被 `isVirtualInterfaceName` 匹配上 ——
                // 那是巧合，不是设计：换成 ZeroTier 的 `feth` 或改个接口名就静默失效。
                advertisedAddresses = (
                    localIpv4Addresses(includeVirtualInterfaces = true) +
                        localIpv6CandidateAddresses(includeVirtualInterfaces = true)
                    ).ifEmpty { localAddresses },
            )
        }

        /**
         * 用 `registerDefaultNetworkCallback` 订阅"默认网络变了"。
         *
         * 只认这三个回调，**不认 `onCapabilitiesChanged`** —— 后者在信号强弱变化时也会
         * 反复触发，而 `snapshot()` 要枚举网卡，跟着它跑等于拿电池换一堆没人看的状态。
         * `onLinkPropertiesChanged` 才带 IP 变化（换网段时本机地址会变）。
         *
         * `minSdk = 24`，所以这个 API 不需要版本判断。
         */
        override fun observe(onChange: (NetworkSnapshot) -> Unit): () -> Unit {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return { }
            // K3 ①：先通知 core 的会合点（长连接要**立刻**清退避重拨，不等 60s 上限），
            // 再刷新 UI 快照。顺序是刻意的：重拨最急，快照慢一拍没人会因此收不到消息。
            fun report() {
                NetworkWatch.notifyChanged()
                onChange(snapshot())
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = report()
                override fun onLost(network: Network) = report()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                    report()
            }
            return try {
                manager.registerDefaultNetworkCallback(callback)
                val cancel: () -> Unit = { runCatching { manager.unregisterNetworkCallback(callback) } }
                cancel
            } catch (t: Throwable) {
                // 订阅不上不该让节点起不来：手动填 IP 那条路完全不依赖它。
                val noop: () -> Unit = { }
                noop
            }
        }
    }

    /**
     * Android 上"访问局域网"不是运行时权限，系统不会弹窗，所以这里是 `true`。
     * 保留这个契约是为了 iOS —— 那边首次会真的弹本地网络授权。
     */
    override val localNetworkGate: LocalNetworkGate = object : LocalNetworkGate {
        override suspend fun ensureAccess(): Boolean = true
    }

    /**
     * Compose 层把 SAF 启动器注册到这里。
     *
     * 公开是为了让 `androidApp` 的 Compose 根节点能拿到它：平台对象在 Application 里
     * 就建好了，而启动器只能等界面层起来才有。
     */
    val pickerBridge: AndroidPickerBridge = AndroidPickerBridge()

    /**
     * Compose 层把「请求某个运行时权限」的启动器注册到这里。
     *
     * 与 [pickerBridge] 同一个理由：启动器只能在 Activity/Compose 里注册。
     * 目前有两处用它：通知（`POST_NOTIFICATIONS`）与相机（`CAMERA`）。
     */
    val permissionBridge: AndroidPermissionBridge = AndroidPermissionBridge()

    /**
     * 投屏授权的启动器注册点（系统"立即开始/取消"确认框只能由 Activity 发车）。
     * 与 [pickerBridge] / [permissionBridge] 同一个理由、同一套 attach/deliver/detach 纪律。
     */
    val screenShareBridge: AndroidScreenShareBridge = AndroidScreenShareBridge()

    override val screenSharing: ScreenSharingService? = AndroidScreenSharingService(
        context = context,
        consentBridge = screenShareBridge,
        serviceClass = screenShareServiceClass,
    )

    /**
     * 「后台保持连接」：退到后台时用一条常驻通知的前台服务把进程留在前台状态，
     * 免得被系统打成 cached 后收紧网络（真机实测：约 90 秒后对端就判我们离线）。
     *
     * 只有 serviceClass 非空时才真的有这条能力；null（纯测试宿主）时
     * [BackgroundConnectionService.isSupported] 为 false。
     */
    override val backgroundConnection: BackgroundConnectionService =
        AndroidBackgroundConnectionService(
            context = context,
            serviceClass = keepAliveServiceClass,
        )

    override val filePicker: FilePicker = AndroidFilePicker(context, pickerBridge)
    override val saveDirectoryPicker: SaveDirectoryPicker =
        AndroidSaveDirectoryPicker(context, pickerBridge)
    val androidQrScanner = AndroidQrScanner(context)
    override val qrScanner: QrScanner = androidQrScanner

    // 这里曾有 `injectQrResultForTesting`：一条能直接把字符串送进配对流程、**绕过二次确认闸门**的
    // 测试钩子（task-54 裁决删除）。外部来源的配对只有一条路：MainActivity 的 ExternalPairingGate
    // （登记 → 用户确认 → 才调 handleQrCodeAsync）。将来加测试钩子也不许形成第二条分支。

    /**
     * 相机预览绑在**这一个**扫描器实例上：检测跑在预览那条 `ImageAnalysis` 里，
     * 换一个实例就等于换一台相机，检测结果不会回到等在 [QrScanner.scan] 里的那个人手上。
     */
    override val qrScannerPreview: (@Composable (Modifier) -> Unit)? = { modifier ->
        androidQrScanner.QrScannerPreview(modifier)
    }
    override val cameraGate: CameraGate = AndroidCameraGate(context, permissionBridge)
    override val mdnsAdvertiser: MdnsAdvertiser = AndroidMdnsAdvertiser(context)
    override val mdnsBrowser: MdnsBrowser = AndroidMdnsBrowser(context)
    override val pushNotificationHandler: PushNotificationHandler =
        AndroidPushNotificationHandler(context, permissionBridge)

    /**
     * 收到的文件落在 `filesDir/relay/downloads`（见 [AndroidFilePicker] 的 `FALLBACK_DIR`）——
     * 那是应用内部目录，文件管理器看不见。没有这条出口，用户拿到了路径也打不开、分享不出去。
     */
    override val receivedFileActions: ReceivedFileActions = AndroidReceivedFileActions(context)

    /**
     * 「去系统设置」：打开**本应用**的权限/通知详情页。
     *
     * 为什么它必须是一个"能不能"而不是"直接打开"：Android 上设置页也可能打不开
     * （被托管的设备、极简 ROM），而界面据此决定**画不画那颗按钮** —— 点了没反应的
     * 按钮比没有按钮更糟。所以失败如实返回 false。
     */
    override val canOpenAppSettings: Boolean = true

    override fun openAppSettings(): Boolean = try {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    override val urlOpener: UrlOpener = object : UrlOpener {
        override fun openUrl(url: String): Boolean {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * 本机所有可用的 IPv4 地址（排除回环与未启用的接口）。
 *
 * 站点本地地址（192.168./10./172.16-31.）排在最前：那是用户真正要填给对方的那个 IP。
 *
 * 默认跳过虚拟/隧道网卡（[isVirtualInterfaceName]，Android 上是 `tun*`）：VPN 的 10.x 地址
 * 只有同在那一张覆盖网里的设备能连，不该进配对候选。
 */
internal fun localIpv4Addresses(includeVirtualInterfaces: Boolean = false): List<String> {
    val all = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { includeVirtualInterfaces || !isVirtualInterfaceName(it.name) }
            .flatMap { nic -> nic.inetAddresses.toList().map { nic to it } }
            .filter { (_, address) -> address is Inet4Address && !address.isLoopbackAddress }
            .map { (_, address) -> address.hostAddress?.substringBefore('%') ?: "" }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    return all.distinct().sortedByDescending { it.isSiteLocalAddress() }
}

private fun String.isSiteLocalAddress(): Boolean =
    startsWith("192.168.") || startsWith("10.") ||
        (startsWith("172.") && (substringAfter("172.").substringBefore('.').toIntOrNull() ?: 0) in 16..31)

/**
 * 本机可用的 IPv6 候选：ULA `fc00::/7` 与公网 GUA `2000::/3`。
 *
 * 为什么手机也要枚举 v6：跨网络时对端只能靠**公网 v6** 找到这台手机，而「我的二维码」印的
 * 就是这张候选表（`pairingQrPayloadFor`）。没有这一条，手机的码里永远只有它的局域网 IPv4。
 *
 * 丢掉链路本地 `fe80::/10`、回环、未指定/组播（`Ipv6Scope` 判据），以及虚拟/隧道网卡上的地址。
 */
internal fun localIpv6CandidateAddresses(includeVirtualInterfaces: Boolean = false): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { includeVirtualInterfaces || !isVirtualInterfaceName(it.name) }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet6Address>() }
            // Android/Linux 的 hostAddress 对链路本地带 `%wlan0`，剥掉再判作用域。
            .map { it.hostAddress.orEmpty().substringBefore('%').trim() }
            .filter { it.isNotBlank() && IpAddressUtils.isIpv6(it) }
            .filter {
                when (IpAddressUtils.getIpv6Scope(it)) {
                    Ipv6Scope.GLOBAL, Ipv6Scope.UNIQUE_LOCAL -> true
                    else -> false
                }
            }
            .distinct()
    }.getOrDefault(emptyList())

/**
 * 默认名判定（纯函数，便于 androidUnitTest 直接跑 —— Android 实现住在 androidMain，
 * jvmTest 看不到它）。
 *
 * ## 它修的真机缺陷（workspace-ia 审查发现）
 *
 * 原来这里（AndroidPlatform.isDefaultNick）写的是：
 *
 *     nick.isBlank() || nick == defaultNick || nick.startsWith(defaultNick)
 *         || nick == "My Phone" || nick.startsWith("My Phone")
 *
 * 两处 startsWith 的后果是**静默改名**：机型名（或宿主传进来的本地化默认名）是
 * 「Sample Phone 12 Pro」时，用户把它改成「Sample Phone 12 Pro 工作机」这类**以默认名开头**
 * 的自定义名，下次启动 RelayController 的补默认名逻辑（isDefaultNick 命中就
 * copy(nick = fallback)，而且**落盘**）会把它改回机型名 —— 用户改过的名字没了，
 * 界面上没有任何提示。
 *
 * 判据只能是「与默认名 / 旧共享默认名**逐字相等**」：前缀相同**不等于**没改过。
 * 破坏性动作（改用户的名字并落盘）必须保守；接口默认实现（AppPlatform.isDefaultNick）
 * 是 nick.isBlank()，同样保守 —— 这条之前是全仓唯一的例外。
 */
internal fun isDefaultNickExact(nick: String, defaultNick: String): Boolean =
    nick.isBlank() || nick == defaultNick || nick == LEGACY_SHARED_DEFAULT_NICK

/** 旧的共享默认名（跨端共用一个词的时代留下的值，只按**逐字相等**认）。 */
internal const val LEGACY_SHARED_DEFAULT_NICK = "My Phone"
