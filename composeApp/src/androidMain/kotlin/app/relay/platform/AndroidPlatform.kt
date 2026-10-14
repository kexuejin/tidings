// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.File
import java.net.Inet4Address
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
     * 首次运行时这台设备的默认显示名，由 Android 宿主从自己的资源里取（本地化文案）。
     *
     * 传空串是测试与纯逻辑宿主的走法：那时退回 [deviceInfo] 报的机型名，不编造。
     */
    private val defaultNick: String = "",
) : AppPlatform {

    override val dataDirectory: DataDirectoryProvider = object : DataDirectoryProvider {
        override fun dataDirectory(): String =
            File(context.filesDir, "relay").apply { mkdirs() }.absolutePath

        override fun cacheDirectory(): String =
            File(context.cacheDir, "relay").apply { mkdirs() }.absolutePath
    }

    override val deviceDefaultNick: String get() = defaultNick

    /** 对端节点表里显示的 `host`。用机型而不是应用名。 */
    override val deviceInfo: DeviceInfo = object : DeviceInfo {
        override fun hostName(): String =
            listOfNotNull(Build.MANUFACTURER, Build.MODEL)
                .joinToString(" ")
                .ifBlank { "Android" }
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

            return NetworkSnapshot(
                online = online,
                transport = transport,
                localAddresses = localIpv4Addresses(),
                vpnActive = vpnActive,
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
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange(snapshot())
                override fun onLost(network: Network) = onChange(snapshot())
                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                    onChange(snapshot())
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

    override val filePicker: FilePicker = AndroidFilePicker(context, pickerBridge)
    override val saveDirectoryPicker: SaveDirectoryPicker =
        AndroidSaveDirectoryPicker(context, pickerBridge)
    private val androidQrScanner = AndroidQrScanner(context)
    override val qrScanner: QrScanner = androidQrScanner

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
 */
internal fun localIpv4Addresses(): List<String> {
    val all = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
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
