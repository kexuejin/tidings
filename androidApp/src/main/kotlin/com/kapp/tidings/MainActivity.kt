// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
// 本类原来在 `package com.kapp.tidings.domain` 下 —— 那是 :app 领域层的包名，
// 于是这些符号靠**同包隐式可见**，一个 import 都不用写。
// 包名改到 `com.kapp.tidings`（与 applicationId 一致）后，隐式通路断了，必须显式声明。
// 这也说明：原来的「写法奇怪」背后藏着这些从来没被写出来的依赖。
import com.kapp.tidings.RelayApp
import com.kapp.tidings.domain.ExternalPairingPreview
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.platform.AndroidNotificationIntent
import com.kapp.tidings.platform.AndroidScreenShareBridge
import com.kapp.tidings.platform.AndroidSaveDirectoryPicker
import com.kapp.tidings.ui.components.ExternalPairingConfirmDialog

/**
 * Android 壳：只负责把 Application 里那个长生命周期的控制器接到 Compose 上。
 * 界面本身与 iOS/JVM 完全共用。
 *
 * 这里额外干三件事：**让窗口铺到系统栏后面**、**注册 SAF 启动器**、**注册通知权限启动器**。
 * 系统选择器与权限弹窗都只能由 Activity/Compose 持有，而 `AndroidAppPlatform` 构造在
 * Application 里，所以后两件事由两根显式的桥把两者接起来 —— 平台层 `pick()` /
 * `requestPermission()` 挂起等结果，这里把用户选完的 URI、以及系统给出的授权结果回填过去。
 *
 * **系统栏归属**（详见 `composeApp` 的 `App.kt` 与 `platform/CareSystemBars.kt`）：
 * 这个 Activity 只负责"窗口铺满"这一半，[enableEdgeToEdge] 就是那一半的全部；
 * 图标明暗不在这里写死，因为那要跟**应用**主题走而不是系统设置，由 Compose 侧的
 * [com.kapp.tidings.platform.CareSystemBars] 在每次提交后刷。
 *
 * 不写 `values/themes.xml`：minSdk 24 上 `Theme.DeviceDefault.DayNight` 要 API 29 才有，
 * 为了一个跨版本的 DayNight 抬高 minSdk 不划算；窗口底色交给 Compose 的根 [androidx.compose.material3.Surface]。
 */
class MainActivity : ComponentActivity() {

    /**
     * 外部来源配对的闸门（task-54）：deep link 与 debug 注入都先登记在这里，
     * **用户点「允许」之后**才把载荷交给 `handleQrCodeAsync`（那条路会写节点/候选池/pin）。
     */
    private val externalPairingGate = ExternalPairingGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前、setContent 之前：它决定窗口是否画到状态栏/导航栏
        // 后面。不开这一句，内容就只能画在系统栏下面那一小块，顶部于是留出一条空白 ——
        // 那不是布局写错，是窗口压根没铺过去。
        //
        // 两个 SystemBarStyle 都显式给"透明"：enableEdgeToEdge 的导航栏默认值是
        // `SystemBarStyle.auto(0xE6 白, 0x80 黑)`，也就是**浅色主题下系统自己给导航栏
        // 盖一层 90% 白纱**。根 Surface 已经把主题底色铺满整窗了，再叠这层白纱，
        // 三键导航区就会浮出一条与应用无关的白条 —— 和顶部那条空白是同一种毛病，
        // 只是发生在下面。图标明暗由 CareSystemBars 按应用主题刷，这里只管背景透明。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val app = application as RelayApplication
        // 冷启动点通知进来时，extra 就在这个 intent 上。
        handleNotificationFocus(intent)
        handleDeepLink(intent)
        // 测试注入口**只在可调试构建**里注册（理由见 shouldRegisterTestQrReceiver 的 KDoc）：
        // 它是导出广播，收到就把字符串送进配对流程 —— 那条路会改写手动节点、写入候选池、
        // 并落下对端的 TLS pin，全程没有二次确认。留在发布版里等于：设备上任意应用都能让
        // 本机与一个它控制的节点配对，并把它的 pin 固定成信任根。
        if (shouldRegisterTestQrReceiver(applicationInfo.flags)) {
            ContextCompat.registerReceiver(
                this,
                qrInjectionReceiver,
                IntentFilter(ACTION_TEST_INJECT_QR),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        val bridge = app.platform.pickerBridge
        val permissionBridge = app.platform.permissionBridge
        setContent {
            // 选文件：可多选 mime，结果是一个 content:// URI。
            val fileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> bridge.deliver(uri?.toString()) }

            // 选多个文件：结果是 List<Uri>。
            val multipleFileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments(),
            ) { uris -> bridge.deliverMultiple(uris.map { it.toString() }) }

            // 选目录：输入是 Uri?（起始位置），这里没有"上次去过的目录"，给 null。
            val directoryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri -> bridge.deliver(uri?.toString()) }

            // 运行时权限（Android 13+ 的 POST_NOTIFICATIONS、相机 CAMERA）：结果必须如实回填，
            // 否则平台层只能猜 —— 通知那一版就是直接 `return true`，于是通知从没出现过。
            // 同一个启动器接两种权限：`RequestPermission()` 的输入本来就是权限字符串。
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> permissionBridge.deliver(granted) }

            LaunchedEffect(bridge) {
                bridge.attach { mimeTypes ->
                    val wantsDirectory =
                        mimeTypes.size == 1 &&
                            mimeTypes[0] == AndroidSaveDirectoryPicker.DIRECTORY_MIME
                    if (wantsDirectory) {
                        directoryLauncher.launch(null)
                    } else {
                        fileLauncher.launch(mimeTypes)
                    }
                }
                bridge.attachMultiple { mimeTypes ->
                    multipleFileLauncher.launch(mimeTypes)
                }
            }

            LaunchedEffect(permissionBridge) {
                permissionBridge.attach { permission -> permissionLauncher.launch(permission) }
            }

            // 投屏授权：系统的"立即开始/取消"确认框。结果（resultCode + data）经桥
            // 原样带回平台层 —— 与权限桥同一纪律：拿不到就说拿不到，不伪造一份授权。
            val screenBridge = app.platform.screenShareBridge
            val projectionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                screenBridge.deliver(
                    // 短名引用：这里的 `app` 是上面那个 RelayApplication 局部量，
                    // 写全限定名 com.kapp.tidings.platform… 会先在它身上找 .relay 然后失败。
                    AndroidScreenShareBridge.Consent(
                        resultCode = result.resultCode,
                        data = result.data,
                    ),
                )
            }
            LaunchedEffect(screenBridge) {
                screenBridge.attach {
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as? MediaProjectionManager
                    val captureIntent = manager?.createScreenCaptureIntent()
                    if (captureIntent == null) {
                        // 系统没有投屏组件（部分定制 ROM）：如实回填"没拿到"，
                        // 挂起的 prepareCapture 会 reject(unsupported)，而不是空挂到超时。
                        screenBridge.deliver(null)
                    } else {
                        projectionLauncher.launch(captureIntent)
                    }
                }
            }

            // Activity 没了就摘掉回调：否则一个挂起的 pick() 会一直等到超时，
            // 而它等的结果永远不会来。权限请求同理（结束后返回 false，不是 true）。
            DisposableEffect(bridge, permissionBridge) {
                onDispose {
                    bridge.detach()
                    permissionBridge.detach()
                }
            }
            DisposableEffect(screenBridge) {
                onDispose { screenBridge.detach() }
            }

            RelayApp(controller = app.controller)

            // task-54：外部来源配对（deep link / debug 注入）的二次确认。
            // 拒绝 = 什么都不做（`deny()` 只清 pending，不碰任何存储）。
            externalPairingGate.pending?.let { pending ->
                ExternalPairingConfirmDialog(
                    preview = pending.preview,
                    onAllow = {
                        externalPairingGate.allow { payload ->
                            app.controller.handleQrCodeAsync(payload)
                        }
                    },
                    onDeny = { externalPairingGate.deny() },
                )
            }
        }
    }

    /**
     * 应用**已经在跑**时点通知走这里（清单里 `launchMode="singleTop"`），不会再叠一个实例。
     *
     * 不叠实例是有意的：新实例的 Compose 状态是空的，用户看到的是"点了通知，应用重启了"。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须换掉 getIntent() 手里那一份，否则下一次读到的还是最初那个 intent。
        setIntent(intent)
        handleNotificationFocus(intent)
        handleDeepLink(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(qrInjectionReceiver) }
    }

    /**
     * 深链入口：**只登记，不落盘**（task-54）。
     *
     * 这两个 scheme 的 intent-filter 是 `exported=true` + BROWSABLE，任意应用、或一条被诱导
     * 点击的链接都能打进来；而它最终走的 `handleQrCode` 会新增/改写手动节点、写候选池、
     * 并落下对端 TLS pin（公网 fail-closed 的信任根）。所以这里只把载荷交给闸门，
     * 由用户在自己的屏幕上确认。
     */
    private fun handleDeepLink(intent: Intent?) {
        val payload = externalPairingPayloadOf(
            action = intent?.action,
            dataString = intent?.dataString,
            testQrExtra = null,
        ) ?: return
        requestExternalPairing(payload)
    }

    /** 把一条外部载荷交给闸门：解析出**只读预览**（只读、不落盘），等用户确认。 */
    private fun requestExternalPairing(payload: String) {
        val controller = (application as RelayApplication).controller
        externalPairingGate.request(payload, controller.previewExternalPairing(payload))
    }

    /**
     * 把「这条通知说的是哪个对端」变成一次定位请求。
     *
     * 界面那边不在这里切页：`App.kt` 有一个 `LaunchedEffect(state.focusRequest)` 消费它，
     * 消费完清空（一次性）。壳只负责把 intent 里的值交给控制器 —— 这样冷启动与
     * 「已经在跑」两条路走的是同一段逻辑。
     *
     * 缺这个 extra 就什么都不做：不是每条通知都关于某个对端。
     */
    private fun handleNotificationFocus(intent: Intent?) {
        val peerId = intent
            ?.getStringExtra(AndroidNotificationIntent.EXTRA_FOCUS_PEER)
            ?.takeIf { it.isNotBlank() }
            ?: return
        (application as RelayApplication).controller.requestFocus(peerId)
    }

    companion object {
        const val ACTION_TEST_INJECT_QR = "com.kapp.tidings.TEST_INJECT_QR"
        const val EXTRA_QR = "qr"
    }

    private val qrInjectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val payload = externalPairingPayloadOf(
                action = intent?.action,
                dataString = intent?.dataString,
                testQrExtra = intent?.getStringExtra(EXTRA_QR),
            ) ?: return
            // **任何**外部注入的配对都过闸门，包括「用户此刻正停在扫码取景页」那种情况。
            //
            // 这里以前有一条 `injectQrResultForTesting` 分叉：在扫码页就把载荷当成「相机扫到了」
            // 喂给 scan() 协程，于是同一条导出广播的行为**取决于用户当时停在哪个界面** ——
            // 在扫码页就绕过确认。状态相关的洞比固定洞更难推理，也更容易被以后的人忽略，
            // 所以统一成一条规则（lead 裁决 a）：外部注入一律先登记、等用户确认。
            requestExternalPairing(payload)
        }
    }
}

/**
 * 测试用二维码注入口（[MainActivity.ACTION_TEST_INJECT_QR]）要不要注册。
 *
 * ## 为什么要有这道闸
 *
 * 那条广播是**导出**的（截图与真机测试要经 adb 送一段二维码进去），而它的处理是把字符串
 * 交给 `RelayController.handleQrCodeAsync` —— 那条路会**新增/改写手动节点、写入候选池、
 * 并落下对端的 TLS pin**，全程没有二次确认（见 `RelayController.handleQrCode` 的 LanPeer 分支）。
 * 也就是说，把它留在发布版里，设备上**任意应用**都能让本机与一个它控制的节点配对，
 * 并把它的 pin 固定成本机信任根（公网 TLS 的 fail-closed 判定就建在这枚 pin 上）。
 *
 * 所以闸门设在"注册"这一步，而不是"收到后忽略"：发布版里这条广播**根本不存在**
 * （不注册 = 没有入口，而不是收到再判断）。
 *
 * 只在 `ApplicationInfo.FLAG_DEBUGGABLE` 时注册：debug 构建与 adb 驱动的测试包都带上这一位，
 * 正式发布包不带。
 */
internal fun shouldRegisterTestQrReceiver(applicationFlags: Int): Boolean =
    (applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * 把一条 intent 判成「外部来源的配对载荷」（task-54）。null = 这条 intent 不是外部配对入口。
 *
 * 两个入口都在应用自身的 UI 之外：
 *  · `com.kapp.tidings.TEST_INJECT_QR`（**debug 包**的导出广播，adb/任意应用都能发）；
 *  · `relay://` / `relay-pair://` 深链（`exported=true` + BROWSABLE）。
 *
 * 参数全是**字符串**（不接 `Intent`）是刻意的：这样它能在纯 JVM 单测里被钉住，
 * 不需要 Robolectric，也不需要设备。
 */
internal fun externalPairingPayloadOf(
    action: String?,
    dataString: String?,
    testQrExtra: String?,
): String? = when {
    // 广播注入：载荷优先取 extra，其次才是 data（历史脚本两种都发过）。
    action == MainActivity.ACTION_TEST_INJECT_QR ->
        testQrExtra?.takeIf { it.isNotBlank() } ?: dataString?.takeIf { it.isNotBlank() }

    dataString?.startsWith("relay://") == true ||
        dataString?.startsWith("relay-pair://") == true -> dataString

    else -> null
}

/** 等待用户确认的一条外部配对（载荷原文 + 只读预览，预览可能为 null = 解析不出）。 */
internal data class PendingExternalPairing(
    val payload: String,
    val preview: ExternalPairingPreview?,
)

/**
 * 外部来源配对的**闸门**（task-54）。
 *
 * 为什么要有它：外部入口（深链/广播）最终都走 `handleQrCode` —— 那条路会新增/改写手动节点、
 * 写候选池、并落下对端 TLS pin（公网 fail-closed 的信任根）。**用户点了一条链接 ≠ 同意固定
 * 一枚密钥**，所以「收到载荷」与「应用载荷」必须是两步：
 *
 *  · [request]：只登记（附带只读预览），**不产生任何副作用**；
 *  · [allow]：把载荷交出去**恰好一次**（第二次调用不会再交）；
 *  · [deny]：清空登记，**一个字节都不落盘**。
 *
 * [pending] 用 `mutableStateOf` 持有：Activity 的非 Compose 入口（`onNewIntent` / 广播接收器）
 * 写它，Compose 侧读它来画确认框 —— 一处状态、两个世界都能看见。
 */
internal class ExternalPairingGate {

    var pending: PendingExternalPairing? by mutableStateOf(null)
        private set

    /** 登记一条待确认载荷。空白载荷直接拒绝（返回 false = 什么都没发生）。 */
    fun request(payload: String, preview: ExternalPairingPreview?): Boolean {
        if (payload.isBlank()) return false
        pending = PendingExternalPairing(payload, preview)
        return true
    }

    /**
     * 用户点「允许」：把载荷交给 [apply] **恰好一次**。没有待确认时返回 false 且不调 [apply]。
     *
     * 先清 pending 再调 [apply] 是刻意的：即使 [apply] 抛异常，也不会留下一个"看起来还
     * 在等确认"的状态被再点一次。
     */
    fun allow(apply: (String) -> Unit): Boolean {
        val current = pending ?: return false
        pending = null
        apply(current.payload)
        return true
    }

    /** 用户点「取消」（或系统 dismiss）：清空登记，不碰任何存储。 */
    fun deny() {
        pending = null
    }
}
