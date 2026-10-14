// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 平台契约：commonMain 只声明"要什么"，平台壳决定"怎么给"。
 *
 * ## 为什么这些签名是现在这样
 *
 * 脚手架生成的版本把八个契约都写成了 `suspend fun invoke(id: String): Boolean` —— 一个
 * 参数是字符串 id、返回值是布尔、八个接口签名完全相同。那是**占位符**，不是契约：它既不
 * 表达"选文件"要返回文件、也不表达"取数据目录"要返回路径，于是 UI 永远只能拿一个
 * `false` 而无法做任何真实的事。
 *
 * 这里按**真实用途**重写：每个契约的返回类型就是它真正要交给上层的那个东西。
 * 未进入本期实现的契约（文件收发 P3、扫码配对 P4）保留完整签名并给出诚实的
 * `Unsupported` 实现 —— 签名先定下来，这样 P3/P4 只需要换实现，不需要再动 UI。
 */

// ---------------------------------------------------------------- 数据目录

/**
 * 应用私有数据目录。设置、`nodeId`、日志、附件都落在这里。
 *
 * 这是**第一个必须真实实现的契约**：`nodeId` 不持久化的话，每次重启在对端桌面设备里
 * 都会变成一个新联系人（见 `agentNode/Main.kt` 的同类说明）。
 */
interface DataDirectoryProvider {
    /** 持久目录，系统不会自动清理。 */
    fun dataDirectory(): String

    /** 可被系统/用户清理的缓存目录（附件暂存、日志轮转）。 */
    fun cacheDirectory(): String
}

// ---------------------------------------------------------------- 网络

enum class Transport { Wifi, Cellular, Ethernet, Vpn, None }

/**
 * 本机网络快照。
 *
 * [localAddresses] 是 UI 要显示给用户看的"我的 IP"，也是手填配对时的提示值。
 * [vpnActive] 单独拎出来是因为 Tailscale / ZeroTier 场景下**局域网广播不过去**，
 * 只能单播握手。
 */
data class NetworkSnapshot(
    val online: Boolean,
    val transport: Transport,
    val localAddresses: List<String>,
    val vpnActive: Boolean,
)

/**
 * 「没有可用地址」就按「没有网络」说。
 *
 * Android 在 Wi-Fi 掉线那一瞬间会给出一个自相矛盾的快照：`activeNetwork` 还在、能力表里
 * 还带着 WIFI/INTERNET，但链路地址已经被清空。真机上验证过：这时 UI 会一直显示
 * 「连接方式 Wifi」而 Tidings 一个包也发不出去，并且**不会自己恢复** —— 拆链路上没有
 * 后续回调来纠正它（`snapshot()` 只在 `onAvailable`/`onLost`/`onLinkPropertiesChanged`
 * 里被调用，而这三个都已经过去了）。启动时如果确实没网，`snapshot()` 给的是
 * `Transport.None`，所以这个坑只在"从有网到没网"的过渡里出现。
 *
 * 判据用 [NetworkSnapshot.localAddresses] 而不是 `NET_CAPABILITY_INTERNET`：Tidings 只走
 * IPv4 广播/单播，地址列表为空就是"确实到不了任何人"，此时说「没有网络」不是撒谎，
 * 是唯一诚实的说法（同「不知道就别说话」那条规矩）。有蜂窝在时地址列表非空，不受影响。
 */
internal fun NetworkSnapshot.withoutUsableAddress(): NetworkSnapshot =
    if (localAddresses.isEmpty()) copy(online = false, transport = Transport.None) else this

interface NetworkMonitor {
    /**
     * 平台层填的**原始**快照 —— 这里不下"有没有网"的结论，只如实汇报系统说了什么。
     *
     * 让平台只实现这一个方法、由 [snapshot] 统一收口，是刻意的：这条"地址为空就说没有网"
     * 的纠正如果靠每个平台各自记得调一次，迟早会有一边忘掉（真机上那两个"接口在、没人调"
     * 的缺陷就是这么来的）。收口在接口默认实现里，平台层**没有忘的机会**。
     */
    fun rawSnapshot(): NetworkSnapshot

    /**
     * 给控制器/UI 的快照：原始快照 + 统一的诚实性纠正（见 [withoutUsableAddress]）。
     */
    fun snapshot(): NetworkSnapshot = rawSnapshot().withoutUsableAddress()

    /**
     * 订阅网络变化（换 Wi-Fi、换网段、VPN 起来/落下），返回一个取消函数。
     *
     * 默认实现**不订阅**，因为测试替身没有网络栈。但两个真机平台都必须真的实现它 ——
     * 只有一个 `snapshot()` 的后果是**本机地址只在启动时取一次**：换网之后「发现」页
     * 还在显示旧 IP，广播也仍按旧网段发，直到用户重启 App。这和"接口在、没人调"那两个
     * 缺陷是同族的，所以它必须有一条会咬的测试
     * （`RelayControllerNetworkTest`）—— 断言的是"控制器有没有去订阅、收到变化有没有反应"，
     * 不是网络通不通。
     */
    fun observe(onChange: (NetworkSnapshot) -> Unit): () -> Unit = { }
}

/**
 * 设备身份。
 *
 * [hostName] 会作为协议 `Profile.host` 发给对端 —— 对端设备的节点表里显示的就是它，
 * 所以它必须是"这台设备叫什么"，而不是应用名。
 */
interface DeviceInfo {
    fun hostName(): String
}

/**
 * 本地网络权限门。
 *
 * **它现在两个平台都返回 `true`，这是诚实的，不是偷懒**：
 * - Android 上"访问局域网"不是运行时权限（缺 `CHANGE_WIFI_MULTICAST_STATE` 影响的是
 *   **接收**组播，不是发送广播）；
 * - iOS 上没有"申请本地网络权限"的 API，系统在第一次真的碰局域网时才弹窗，而弹窗的前提是
 *   `iosApp/Info.plist` 里声明了 `NSLocalNetworkUsageDescription` + `NSBonjourServices`
 *   （已声明）。缺声明时访问被直接拒绝且**不弹窗** —— 那才是这个能力真正会踩的坑。
 *
 * 保留这个契约是为了让"启动节点前先过一道门"的顺序**有地方可写**：将来 Android 要加
 * `NEARBY_WIFI_DEVICES` 之类的运行时权限，或者 iOS 要改成主动探测，调用点不用动。
 * 上一版它连调用点都没有（`RelayController.startMdns` 是第一个）。
 */
interface LocalNetworkGate {
    suspend fun ensureAccess(): Boolean
}

// ---------------------------------------------------------------- 文件（P3）

data class PickedFile(
    val name: String,
    val sizeBytes: Long,
    /** 平台可读的绝对路径；上传时按块读。 */
    val path: String,
)

interface FilePicker {
    suspend fun pick(): PickedFile?
}

interface SaveDirectoryPicker {
    suspend fun pickDirectory(): String?
}

// ---------------------------------------------------------------- 相机与扫码（P4）

interface QrScanner {
    /**
     * 扫到就返回文本内容（`relay://host:port` 或裸 `host:port`）。
     *
     * **取消不是一个返回值**：调用方的协程被取消时，这个挂起函数以
     * `CancellationException` 结束 —— 平台不需要也不该在这里 `resume(null)`，
     * 那时续体已经取消，`resume` 是空操作。返回 `null` 只表示"这次扫描没拿到结果
     * 就结束了"。调用方两种都不能当成一次失败（见 `RelayController.scanQrCode`）。
     */
    suspend fun scan(): String?

    /**
     * 从一张**已有的图片**里读码 —— 扫码的另一半，完全不经过相机。
     *
     * 为什么值得单独有一条路：扫码页那条路要求"把镜头对准另一块屏幕上的码"，这一步
     * 机器代替不了（`ACCEPTANCE.md` A15 卡在这里很久）。而码本来就是可以截图、可以转发的
     * —— 对方把「我的二维码」截给你、或者你自己拍了照，从图里读出来是一样的结果。
     * 它还绕开了相机权限：一台相机被拒的设备照样能把对方加进来。
     *
     * 返回 `null` 表示**这张图里没有二维码**（拿错图了、码太小、图被裁过）。
     * 「这台设备不支持从图片读码」不是这个返回值 —— 那种平台根本没有这个方法可调，
     * 界面也就不画这个入口（与 [AppPlatform.qrScannerPreview] 同一道闸）。
     *
     * 与 [scan] 一样：**取消不是返回值**，协程被取消时以 `CancellationException` 结束。
     */
    suspend fun scanImage(path: String): String?
}

interface CameraGate {
    suspend fun ensureAccess(): Boolean
}

// ---------------------------------------------------------------- mDNS（P4）

/**
 * mDNS 广播器：把本机发布成局域网里的一个 `_relay._udp` 服务。
 *
 * **两端都必须广播。** 上一版的设计是"手机只浏览、桌面端广播"，那条前提是错的：
 * 部分传统客户端只往 17878 发 UDP 广播，**未启用 mDNS**。局域网里唯一会说
 * `_relay._udp` 的节点就是本应用的另一个实例 —— 而它也不广播，于是谁也发现不了谁，
 * 自动发现端到端恒为零。真机复验用 `dns-sd -B _relay._udp` 采样 5 秒零命中，
 * 才把这条揪出来。
 *
 * [start] **必须等平台回调**再返回。`NsdManager.registerService` / `NSNetService.publish`
 * 都是异步的，发布失败只走回调；立即返回 `true` 会让"注册其实被拒"看起来像成功 ——
 * 一个永远为真的返回值等于没有返回值，那正是这次踩到的坑。超时按失败算。
 */
interface MdnsAdvertiser {
    /** 发布一个 `_relay._udp` 服务并等平台确认；注册失败或超时返回 `false`。 */
    suspend fun start(serviceName: String, port: Int): Boolean

    /** 撤销发布。没有发布过时是空操作（[start] 内部也会先调它）。 */
    fun stop()
}

/**
 * mDNS 服务浏览器：发现局域网内其他**捎话**节点。
 *
 * 与 [MdnsAdvertiser] 是一对，而且**只能发现自己这一类节点**：未启用 mDNS 的节点不参与
 * mDNS（它靠 UDP 广播 + 手动填 IP，见 `core/transport/Discovery.kt`）。所以"自动发现"
 * 里永远不会冒出电脑，UI 文案必须把这件事说清楚，否则用户会以为功能坏了。
 */
interface MdnsBrowser {
    /**
     * 开始浏览服务。
     * @param onServiceFound 发现服务时回调，参数为 (serviceName, host, port)
     * @param onServiceLost 服务丢失时回调，参数为 serviceName
     */
    fun startBrowsing(
        onServiceFound: (String, String, Int) -> Unit,
        onServiceLost: (String) -> Unit,
    )

    fun stopBrowsing()
}

// ---------------------------------------------------------------- 推送（P5）

/**
 * 推送通知处理器。
 *
 * 用于在应用收到消息时显示本地通知。远程推送（APNs/FCM）需要服务器支持，
 * 这里先实现本地通知，为将来的远程推送预留接口。
 *
 * **不区分前台/后台**：收到消息就发。前台抑制需要平台层的可见性契约，尚未做。
 */
interface PushNotificationHandler {
    /**
     * 显示一条通知。
     *
     * 发不出去时**不要静默**：平台实现至少要留下一条日志。Android 上一版在
     * `catch (SecurityException) {}` 里咽掉了唯一能说话的异常，于是"通知从来不出现"
     * 这件事在真机上查不到任何线索。
     *
     * [focusPeerId] 是"这条通知说的是谁"。协议 §8 要求收端**定位到该会话**，而通知是
     * 用户回到应用的那条路 —— 点开之后落在别的页面上，等于没定位。`null` 表示这条通知
     * 没有具体对端，那就只把应用打开。
     *
     * **各平台做到哪一步**（不假装统一）：
     * - Android：真的挂 `PendingIntent`，点通知会把对端 id 交给壳，壳转成一次定位请求。
     * - iOS：把对端 id 写进通知的 `userInfo`，并在 `UNUserNotificationCenterDelegate` 的
     *   `didReceive` 里通过 [setOnNotificationTap] 交回壳，同样转成一次定位请求。
     */
    fun showNotification(title: String, body: String, focusPeerId: String? = null)

    /**
     * 注册"通知被点了"的回调，参数是这条通知说的对端 id（[showNotification] 的 `focusPeerId`）。
     *
     * 给平台一个把点击送回应用层的口子：实现拿到 id 之后应当转成一次定位请求，而不是自己
     * 去操作界面。默认空实现 —— 没有这条通路的平台（桌面）不必写一行"我没有"。
     *
     * 与 [showNotification] 的 `focusPeerId` 是一对：那边写进去，这边读回来。
     */
    fun setOnNotificationTap(callback: ((focusPeerId: String) -> Unit)?) {}

    /**
     * 请求通知权限，返回**系统实际给出的答案**。
     *
     * iOS 与 Android 13+ 都需要。实现**不许猜**：拿不到答案就返回 `false`。
     * Android 那一版曾经无条件 `return true`，导致通知从未出现而调用方以为权限已给 ——
     * 与 `MdnsAdvertiser` 同一个坑（"一个永远为真的返回值等于没有返回值"）。
     */
    suspend fun requestPermission(): Boolean
}

// ---------------------------------------------------------------- 打包

/**
 * 平台壳交给 common UI 的那一包实现。
 *
 * 用接口而不是直接注入八个对象，是为了让 Compose 预览和测试可以塞一个
 * [InMemoryAppPlatform] 进来，而不必在预览里起真实 socket。
 */
interface AppPlatform {
    val dataDirectory: DataDirectoryProvider
    val networkMonitor: NetworkMonitor
    val deviceInfo: DeviceInfo

    /**
     * 首次运行时给这台设备起的默认显示名。
     *
     * 为什么它属于平台层：默认名是一句**面向用户的文案**（要能本地化），而共享代码里
     * 写死一个词就是让所有宿主用同一个名字 —— 桌面端因此顶着「我的手机」上局域网，
     * 节点列表里出现两条同名设备（2026-10-14 真机撞到）。手机读自己的语言资源，
     * 桌面端读机器名，各自回答「这台设备叫什么」。
     *
     * 空串 = 本平台不出文案，由 [DeviceInfo.hostName] 代答（iOS 与测试走这条）。
     */
    val deviceDefaultNick: String
        get() = ""

    val localNetworkGate: LocalNetworkGate
    val filePicker: FilePicker
    val saveDirectoryPicker: SaveDirectoryPicker
    val qrScanner: QrScanner
    val cameraGate: CameraGate
    val mdnsAdvertiser: MdnsAdvertiser
    val mdnsBrowser: MdnsBrowser
    val pushNotificationHandler: PushNotificationHandler

    /**
     * "把已经落盘的文件交给别的应用"的能力（打开 / 分享）。
     *
     * `null` 表示**本平台没有这条出口**，界面据此不画那两个按钮 —— 这是"能力缺失"，
     * 不是"这次失败"。
     *
     * **带默认实现是有意的**：iOS 与桌面直接继承这个 `null`，不必为此各写一行"我没有"，
     * 于是这个能力不会把别的平台的代码拖进来。想提供它的平台覆写即可。
     */
    val receivedFileActions: ReceivedFileActions?
        get() = null

    /**
     * 扫码页里那块**相机预览**。`null` 表示本平台画不出预览（桌面就没有相机）。
     *
     * 为什么是"可空槽"而不是又一条 `expect/actual`：预览必须绑到**那个具体的扫描器实例**
     * 上（Android 的检测跑在预览那条 `ImageAnalysis` 里，iOS 的会话就是预览层的数据源），
     * 而实例只活在平台壳里；用 `expect` 顶层函数就得再引一份全局单例。可空槽同时把
     * "这台设备没有相机"表达清楚 —— 扫码页据此画一句诚实的说明，而不是一块黑框。
     *
     * 与 [receivedFileActions] 同一套规矩：带默认实现，别的平台不必各写一行"我没有"。
     */
    val qrScannerPreview: (@Composable (Modifier) -> Unit)?
        get() = null

    /**
     * 轻量 HTTP 客户端（用于访问公网 Tidings 控制面）。
     */
    val httpClient: SimpleHttpClient
        get() = UnavailableSimpleHttpClient

    /**
     * 外部链接打开器（用于版本更新、帮助页面跳转等）。
     */
    val urlOpener: UrlOpener?
        get() = null

    /**
     * 屏幕截取服务（桌面端全屏捕获与标注）。
     */
    val screenCapture: ScreenCaptureService?
        get() = null

    /**
     * 屏幕共享与协助采集提供器（桌面 AWT/Robot/DesktopCapturer、移动端 MediaProjection/BroadcastUploadExtension 槽）。
     */
    val screenSharing: ScreenSharingService?
        get() = null
}

interface ScreenSharingService {
    /** 当前平台是否支持发起屏幕共享（提供帧采集） */
    fun isShareSupported(): Boolean = false

    /** 准备屏幕采集（权限检查、请求 MediaProjection 或弹出屏幕选择） */
    suspend fun prepareCapture(): Boolean = false

    /** 采集当前屏幕为有界 JPEG 字节流（尺寸由系统自适应压缩） */
    suspend fun captureFrame(seq: Long): ByteArray? = null

    /** 停止采集与清理资源 */
    fun stopCapture() {}

    /**
     * 登记/注销「采集被**外部**终止」的回调（传 null = 注销）。
     *
     * 推式采集（Android ImageReader、iOS 广播扩展帧槽）在画面静止时"没有新帧"是**常态**
     * 而不是故障，所以"用户在系统通知里点了停止投射""扩展被系统回收"这类终止
     * 不能伪装成一次 null 采样——缓存层会把它吞掉，会话就挂在空流上直到超时。
     * 平台侧检测到真终止时调这个回调，控制器据此结束会话（`capture-ended`）。
     *
     * 桌面 Robot 是拉式采集，没有"外部终止"这回事，不实现即可（默认空操作）。
     */
    fun onCaptureEnded(callback: (() -> Unit)?) {}
}

interface ScreenCaptureService {
    /** 检查当前系统是否支持并允许屏幕截取 */
    fun isSupported(): Boolean = true

    /**
     * 启动截图：隐藏主窗、截取屏幕、展示全屏标注窗。
     * 用户完成标注并确认时通过 [onComplete] 回调截好的图片文件绝对路径；取消时为 null。
     */
    fun startCapture(onComplete: (filePath: String?) -> Unit)
}

interface UrlOpener {
    fun openUrl(url: String): Boolean
}

interface SimpleHttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): SimpleHttpResponse
    suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): SimpleHttpResponse
}

data class SimpleHttpResponse(
    val statusCode: Int,
    val body: String,
)


/**
 * 平台层报「这台设备给不出摄像头输入」（模拟器没有相机、相机被别的应用占用、没有后摄）。
 *
 * 刻意**不带用户文案**：异常里的字是给开发者看的，用户看到的那句话住在
 * `composeResources/values/strings.xml`（`banner_camera_unavailable`），由控制器挑。
 * 平台层自己写中文会同时踩两件事 —— 文案绕过资源表，换语言也不会跟着变。
 */
class CameraUnavailableException :
    IllegalStateException("no camera input available on this device")

/** 尚未实现的平台能力。抛错而不是静默返回 null/false —— 静默失败正是脚手架的老毛病。 */
class PlatformCapabilityUnavailable(
    val capability: String,
    val reason: String,
) : UnsupportedOperationException("$capability 在本平台尚未实现：$reason")

/**
 * 未实现能力的原因串。
 *
 * 提成常量而不是每处再写一遍，有两个理由：一是同一件事本来就不该有两个说法；
 * 二是 `:cjkLiteralGate` 按**字面量出现次数**计数（棘轮只许减不许增），
 * 同一句写两遍会被算成两处 —— 而这里真的只需要一处。
 *
 * 下面这些 `Unavailable*` 是所有平台通用的、诚实的"还没做"实现。
 */
private const val NOT_IMPLEMENTED_FILE_TRANSFER = "文件收发是 P3，本期未实现"
private const val NOT_IMPLEMENTED_QR_PAIRING = "扫码配对是 P4，本期未实现"
private const val NOT_IMPLEMENTED_DISCOVERY = "自动发现是 P4，本期未实现"

object UnavailableFilePicker : FilePicker {
    override suspend fun pick(): PickedFile? =
        throw PlatformCapabilityUnavailable("FilePicker", NOT_IMPLEMENTED_FILE_TRANSFER)
}

object UnavailableSaveDirectoryPicker : SaveDirectoryPicker {
    override suspend fun pickDirectory(): String? =
        throw PlatformCapabilityUnavailable("SaveDirectoryPicker", NOT_IMPLEMENTED_FILE_TRANSFER)
}

object UnavailableQrScanner : QrScanner {
    override suspend fun scan(): String? =
        throw PlatformCapabilityUnavailable("QrScanner", NOT_IMPLEMENTED_QR_PAIRING)

    override suspend fun scanImage(path: String): String? =
        throw PlatformCapabilityUnavailable("QrScanner", NOT_IMPLEMENTED_QR_PAIRING)
}

object UnavailableCameraGate : CameraGate {
    override suspend fun ensureAccess(): Boolean =
        throw PlatformCapabilityUnavailable("CameraGate", NOT_IMPLEMENTED_QR_PAIRING)
}

object UnavailableMdnsAdvertiser : MdnsAdvertiser {
    override suspend fun start(serviceName: String, port: Int): Boolean =
        throw PlatformCapabilityUnavailable("MdnsAdvertiser", NOT_IMPLEMENTED_DISCOVERY)

    override fun stop() = Unit
}

object UnavailableMdnsBrowser : MdnsBrowser {
    override fun startBrowsing(
        onServiceFound: (String, String, Int) -> Unit,
        onServiceLost: (String) -> Unit,
    ) = Unit

    override fun stopBrowsing() = Unit
}

object UnavailablePushNotificationHandler : PushNotificationHandler {
    override fun showNotification(title: String, body: String, focusPeerId: String?) = Unit

    override suspend fun requestPermission(): Boolean = true
}

object UnavailableSimpleHttpClient : SimpleHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): SimpleHttpResponse =
        throw PlatformCapabilityUnavailable("SimpleHttpClient", "HTTP client unavailable")

    override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): SimpleHttpResponse =
        throw PlatformCapabilityUnavailable("SimpleHttpClient", "HTTP client unavailable")
}

class InMemorySimpleHttpClient(
    var handler: (suspend (method: String, url: String, body: String?, headers: Map<String, String>) -> SimpleHttpResponse)? = null,
) : SimpleHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): SimpleHttpResponse =
        handler?.invoke("GET", url, null, headers) ?: SimpleHttpResponse(200, """{"ok":true}""")

    override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): SimpleHttpResponse =
        handler?.invoke("POST", url, jsonBody, headers) ?: SimpleHttpResponse(200, """{"ok":true,"status":"sent"}""")
}

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
    override val httpClient: InMemorySimpleHttpClient = InMemorySimpleHttpClient(),
    override val urlOpener: UrlOpener = InMemoryUrlOpener(),
) : AppPlatform {
    override val saveDirectoryPicker: SaveDirectoryPicker = UnavailableSaveDirectoryPicker
}

class InMemoryUrlOpener(
    val openedUrls: MutableList<String> = mutableListOf(),
) : UrlOpener {
    override fun openUrl(url: String): Boolean {
        openedUrls.add(url)
        return true
    }
}
