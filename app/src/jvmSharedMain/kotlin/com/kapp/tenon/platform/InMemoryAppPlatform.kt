// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.platform

/**
 * 测试与 Compose 预览用：不碰真实文件系统与网卡。
 *
 * [mdnsAdvertiser] / [mdnsBrowser] 可注入是有意的：这两个能力曾经"两端都实现、commonMain
 * 一次都不调"，而那种缺陷**只有让测试看到调用发生**才拦得住（见
 * `RelayControllerMdnsTest`）。默认值仍是会抛错的 [UnavailableMdnsAdvertiser]，所以
 * 谁真去启动节点而不给假实现，会立刻知道。
 *
 * [qrScanner] / [cameraGate] 同理可注入：扫码这条路上"什么算失败"（尤其是**取消不算失败**）
 * 只能靠一个假扫描器真的挂起、真的被取消来钉住（见 `RelayControllerScanQrTest`）。
 * 默认值仍是会抛错的 [UnavailableQrScanner] / [UnavailableCameraGate]。
 */
class InMemoryAppPlatform(
    override val dataDirectory: DataDirectoryProvider = object : DataDirectoryProvider {
        override fun dataDirectory(): String = "/tmp/relay-preview"
        override fun cacheDirectory(): String = "/tmp/relay-preview/cache"
    },
    override val networkMonitor: NetworkMonitor = object : NetworkMonitor {
        override fun rawSnapshot(): NetworkSnapshot = NetworkSnapshot(
            online = true,
            transport = Transport.Wifi,
            localAddresses = listOf("192.168.1.42"),
            vpnActive = false,
        )
    },
    override val deviceInfo: DeviceInfo = object : DeviceInfo {
        override fun hostName(): String = "preview-device"
    },
    override val localNetworkGate: LocalNetworkGate = object : LocalNetworkGate {
        override suspend fun ensureAccess(): Boolean = true
    },
    override val mdnsAdvertiser: MdnsAdvertiser = UnavailableMdnsAdvertiser,
    override val mdnsBrowser: MdnsBrowser = UnavailableMdnsBrowser,
    override val pushNotificationHandler: PushNotificationHandler = UnavailablePushNotificationHandler,
    override val qrScanner: QrScanner = UnavailableQrScanner,
    override val cameraGate: CameraGate = UnavailableCameraGate,
    /**
     * 可注入是为了能测「从图片导入二维码」那条路 —— 它要真的走一次选图。
     * 默认值与别处一样，仍是会抛错的 [UnavailableFilePicker]。
     */
    override val filePicker: FilePicker = UnavailableFilePicker,
    /**
     * 内存版 luciola 宿主。**默认不是 null**：luciola 已经是唯一的传输形态，给 null
     * 等于让这台"设备"与任何人都通不了 —— 而 `InMemoryAppPlatform` 的用途是让领域层
     * 在无 UI 环境下跑**真实链路**（`TwoNodeDeliveryTest`），不是模拟一台断网设备。
     *
     * 需要"通信不可用"那种场景的用例，显式传 `luciolaHost = null` 即可。
     */
    override val luciolaHost: LuciolaHost? = InMemoryLuciolaHost(),
    override val httpClient: InMemorySimpleHttpClient = InMemorySimpleHttpClient(),
    override val urlOpener: UrlOpener = InMemoryUrlOpener(),
) : AppPlatform {
    override val saveDirectoryPicker: SaveDirectoryPicker = UnavailableSaveDirectoryPicker
}

/**
 * 内存版 luciola 宿主：**真实 UDP 回环**，没有身份/配对/加密。
 *
 * ## 它替的是 luciola 的哪一半
 *
 * 只替传输面（`transportFactory`）。发现面返回 null —— 测试靠 `addManualPeer` 登记对端，
 * 入站帧由 `PresenceIngest` 处理，不需要信标。真引擎的加密、配对、指纹由
 * `core` 的 `LuciolaSecureChannelTest` 在原生库可用时覆盖。
 *
 * ## 为什么端口取自 `configure(udpPort)`
 *
 * 控制器给的是设置里的 `udpPort`（测试用随机空闲端口）。用 0 会让两个节点拿到各自的
 * 随机端口，但 `addManualPeer` 登记的是**调用方以为的**端口，两者对不上就永远握手失败。
 */
class InMemoryLuciolaHost : LuciolaHost {
    private var factory: ((com.kapp.tenon.core.transport.PeerRegistry) -> com.kapp.tenon.core.transport.DatagramTransport)? = null

    override fun configure(profile: com.kapp.tenon.core.protocol.Profile, udpPort: Int) {
        if (factory != null) return
        factory = { _ -> com.kapp.tenon.core.transport.UdpTransport(udpPort, "127.0.0.1") }
    }

    override fun pairingInfo(): com.kapp.tenon.domain.LuciolaQrInfo? = null

    override fun pairPeer(host: String, port: Int, info: com.kapp.tenon.domain.LuciolaQrInfo): Boolean = false

    override fun transportFactory(): ((com.kapp.tenon.core.transport.PeerRegistry) -> com.kapp.tenon.core.transport.DatagramTransport)? = factory

    /** 没有信标引擎：发现靠手动登记 + 定向心跳。 */
    override fun discoveryFactory(): ((com.kapp.tenon.core.transport.PeerRegistry, () -> com.kapp.tenon.core.protocol.Profile) -> com.kapp.tenon.core.transport.DiscoveryEngine)? = null

    override fun screenChannelHostFactory(
        scope: kotlinx.coroutines.CoroutineScope,
        listenPort: Int?,
    ): ((com.kapp.tenon.core.transport.PeerRegistry) -> com.kapp.tenon.core.transport.ScreenChannelHost)? = null
}
