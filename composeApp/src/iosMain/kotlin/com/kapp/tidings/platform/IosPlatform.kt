// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.UIKit.UIDevice
import platform.darwin.dispatch_get_main_queue
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.sockaddr_in

/**
 * iOS 平台实现。
 *
 * ## 与 Android 的关键差异
 *
 * - **本地网络授权**：iOS 14 起访问局域网要用户同意。Kotlin/Native 侧没有"申请"的 API，
 *   系统在第一次真的碰局域网（Bonjour 浏览/发布、socket）时才弹窗 —— 而弹窗的**前提**是
 *   `Info.plist` 里声明了 `NSLocalNetworkUsageDescription` 与 `NSBonjourServices`；
 *   缺了它们，访问被系统直接拒绝且不弹窗。所以
 *   [LocalNetworkGate.ensureAccess] 只能返回 `true`（真正的门在 plist 里），
 *   调用它的意义是让"启动节点 → plist 已声明 → 首次接触局域网"这条顺序成立。
 * - **后台**：iOS 不允许应用在后台保持裸 socket，所以节点只在前台活着。
 *   真正的后台到达需要 APNs（P5），这是平台限制而不是实现偷懒。
 */
class IosAppPlatform : AppPlatform, QrPreviewProvider {

    override val dataDirectory: DataDirectoryProvider = object : DataDirectoryProvider {
        // 容器内两条**真实**目录，路径每次现算、顺便建出来 —— 与 Android 的
        // `File(filesDir, "relay").apply { mkdirs() }` 是同一语义（见 AndroidPlatform 的 dataDirectory）。
        //
        // 这里以前返回的是 `"NSUserDefaults://com.kapp.tidings"` 这样的占位串，注释说它"只给界面显示"。
        // 但它同时被 `RelayController` 的 `downloadDir` 拿去拼下载根目录，于是：
        //   pathJoin 把 `//` 折成 `/`（`core/src/iosMain/…/fs/FileIo.ios.kt` 的 actual fun pathJoin）
        //   → `NSUserDefaults:/com.kapp.tidings/downloads`
        // 那是一个**相对路径**。`core/src/iosMain/…/fs/FileIo.ios.kt` 的 actual 实现 拿到它直接交给 `NSFileManager` /
        // `NSFileHandle`，两者都按进程当前目录解析 —— 那不在沙盒容器里，建目录、建文件都会失败，
        // `openRandomAccess` 于是抛「打开文件失败」，接收到的文件根本落不了盘。
        // （这是对代码的判读，本机没有 iOS 设备，未做真机验证。）
        // 显示串和落盘路径必须是**同一个**真实路径，这就是现在这版。
        //
        // 选 Application Support 而不是 Documents：与桌面端一致（DesktopPlatform 的 dataDirectory
        // 就是 `~/Library/Application Support/...`），也与 MessageStore.ios.kt 的聊天记录同根，
        // 且享受 iCloud 备份。Documents 只在对"用户要在「文件」App 里看见"时才更合适，那需要
        // 额外的 `UIFileSharingEnabled`，不在本次范围。
        override fun dataDirectory(): String = containerDirectory(NSApplicationSupportDirectory, RELAY_DIR)

        /** 可被系统清理的那一份（归档暂存 / 日志）。`NSCachesDirectory` = 容器内 `Library/Caches`。 */
        override fun cacheDirectory(): String = containerDirectory(NSCachesDirectory, RELAY_DIR)
    }

    override val deviceInfo: DeviceInfo = object : DeviceInfo {
        override fun hostName(): String = UIDevice.currentDevice.name.ifBlank { "iPhone" }
    }

    override val networkMonitor: NetworkMonitor = object : NetworkMonitor {
        override fun rawSnapshot(): NetworkSnapshot {
            val addresses = localIpv4Addresses()
            return NetworkSnapshot(
                online = addresses.isNotEmpty(),
                transport = if (addresses.isEmpty()) Transport.None else Transport.Wifi,
                localAddresses = addresses,
                // 是否走 VPN 在 iOS 上没有公开 API；Tailscale 会以 utun 接口出现，
                // 但把它当"VPN 生效"会误报（utun 也用于普通隧道）。宁可不说。
                vpnActive = false,
            )
        }

        /**
         * 用 `NWPathMonitor` 订阅网络路径变化（换 Wi-Fi、切蜂窝、网段变了）。
         *
         * 用的是 Network 框架的 **C API**：`platform.Network` 这个 klib 只导出了
         * `nw_path_monitor_*` 系列函数，ObjC 的 `NWPathMonitor` 类不在里面。
         * 回调必须挂在主队列上 —— `snapshot()` 会读网卡列表，放别的队列上跑
         * 是在没有同步保证的情况下碰系统接口。
         */
        override fun observe(onChange: (NetworkSnapshot) -> Unit): () -> Unit {
            val monitor = nw_path_monitor_create()
            nw_path_monitor_set_update_handler(monitor) { _ -> onChange(snapshot()) }
            nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
            nw_path_monitor_start(monitor)
            return { nw_path_monitor_cancel(monitor) }
        }
    }

    override val localNetworkGate: LocalNetworkGate = object : LocalNetworkGate {
        override suspend fun ensureAccess(): Boolean = true
    }

    // 文件选择走系统文稿选择器（见 IosFilePicker.kt）。选择器给回来的是 security-scoped URL
    // —— 那是沙箱外那份文件的一张临时通行证，不是能反复读的路径；所以选中的内容会先被拷进
    // 应用临时目录，交出去的 path 是那个副本。目录选择器不拷贝：它是"以后要往这里写"，
    // 拷一份没有意义，返回的就是用户选的那个真实目录。
    override val filePicker: FilePicker = IosFilePicker()
    override val saveDirectoryPicker: SaveDirectoryPicker = IosSaveDirectoryPicker()
    // 预览必须绑在**这一个**扫描器实例上：会话是它的字段，预览层就是那个会话的数据源。
    // 所以这里留一份引用，而不是 `IosQrScanner()` 就地新建一个（那样预览和 scan() 会各
    // 建一个会话，谁也扫不到）。
    private val iosQrScanner = IosQrScanner()
    override val qrScanner: QrScanner = iosQrScanner
    override val qrScannerPreview: (@Composable (Modifier) -> Unit)? =
        { modifier -> iosQrScanner.QrScannerPreview(modifier) }
    override val cameraGate: CameraGate = IosCameraGate()
    override val mdnsAdvertiser: MdnsAdvertiser = IosMdnsAdvertiser()
    override val mdnsBrowser: MdnsBrowser = IosMdnsBrowser()
    override val pushNotificationHandler: PushNotificationHandler = IosPushNotificationHandler()

    /**
     * iOS 收到的文件**有出口了**。
     *
     * 曾经这里是继承来的 `null`（界面据此不画按钮），于是真机验收 I5 的结论是
     * 「收得到，取不走」。实现见 [IosReceivedFileActions]。
     */
    override val receivedFileActions: ReceivedFileActions = IosReceivedFileActions()

    /**
     * 投屏帧源：读 ReplayKit 广播扩展经 App Group 递回的帧（实现在 [IosScreenSharingService]）。
     * 没装广播扩展的宿主里它如实回答不支持 —— caps 不虚报（`RelayController.start()` 按
     * isShareSupported 决定 rvs1）。
     */
    override val screenSharing: ScreenSharingService = IosScreenSharingService()
}

/** 应用在容器内的子目录名。与 `MessageStore.ios.kt` 的聊天记录同根。 */
private const val RELAY_DIR = "relay"

/**
 * 取容器内的标准目录并**立刻建出来**，返回绝对路径。
 *
 * 两个必须守住的点：
 * 1. 路径每次现算，不缓存 —— iOS 会在系统升级或容量不足时搬动整个容器，
 *    缓存下来的绝对路径会指向一个已经不存在的地方（`MessageStore.ios.kt` 的类文档里
 *    记着同一条教训）。
 * 2. 兜底也必须是**真实存在的容器内目录**。`NSSearchPathForDirectoriesInDomains`
 *    在 iOS 上对 `NSUserDomainMask` 永远返回至少一项，所以这个 `?:` 是防御性的；
 *    真遇上时用 `NSHomeDirectory()`（容器根），而不是再编一个看起来像路径的字符串 ——
 *    编出来的相对路径正是这条 bug 的成因。
 *
 * 建目录的返回值**刻意不检查**：目录已存在时 `createDirectoryAtPath` 返回 false，那不是失败。
 * 真的建不出来（沙盒异常）时，下游 `openRandomAccess` 会带着完整路径抛「打开文件失败」，
 * 比在这里吞掉更好定位。
 */
@OptIn(ExperimentalForeignApi::class)
private fun containerDirectory(directory: NSSearchPathDirectory, sub: String): String {
    // `NSSearchPathForDirectoriesInDomains` 在 Kotlin/Native 里已经是 `List<*>`，
    // 不需要再 `as? List<*>`；空列表（理论上不会）退回主目录。
    val base = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true)
        .firstOrNull() as? String
        ?: NSHomeDirectory()
    val path = "$base/$sub"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

/** 本机 IPv4 地址（排除回环）。站点本地地址排前面，那是要填给对方的那个。 */
@OptIn(ExperimentalForeignApi::class)
internal fun localIpv4Addresses(): List<String> = memScoped {
    val head = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(head.ptr) != 0) return@memScoped emptyList()

    val found = mutableListOf<String>()
    try {
        var cursor = head.value
        while (cursor != null) {
            val entry = cursor.pointed
            val address = entry.ifa_addr
            val name = entry.ifa_name?.toKString().orEmpty()
            if (address != null && address.pointed.sa_family.toInt() == AF_INET && name != "lo0") {
                // 刻意不调 inet_ntop：它要一个 C 缓冲区和 UInt 长度，签名在 Kotlin/Native
                // 各版本间会变。IPv4 地址就 4 个字节，按网络字节序自己拼更稳。
                val raw = address.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr
                found += buildString {
                    for (shift in intArrayOf(0, 8, 16, 24)) {
                        if (isNotEmpty()) append('.')
                        append((raw shr shift) and 0xFFu)
                    }
                }
            }
            cursor = entry.ifa_next
        }
    } finally {
        freeifaddrs(head.value)
    }
    found.distinct().sortedByDescending {
        it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")
    }
}
