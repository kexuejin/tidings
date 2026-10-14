// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import android.content.Context
import android.content.Intent
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
import app.relay.RelayApp
import app.relay.platform.AndroidNotificationIntent
import app.relay.platform.AndroidScreenShareBridge
import app.relay.platform.AndroidSaveDirectoryPicker

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
 * [app.relay.platform.CareSystemBars] 在每次提交后刷。
 *
 * 不写 `values/themes.xml`：minSdk 24 上 `Theme.DeviceDefault.DayNight` 要 API 29 才有，
 * 为了一个跨版本的 DayNight 抬高 minSdk 不划算；窗口底色交给 Compose 的根 [androidx.compose.material3.Surface]。
 */
class MainActivity : ComponentActivity() {
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
        val bridge = app.platform.pickerBridge
        val permissionBridge = app.platform.permissionBridge
        setContent {
            // 选文件：可多选 mime，结果是一个 content:// URI。
            val fileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> bridge.deliver(uri?.toString()) }

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
                    // 写全限定名 app.relay.platform… 会先在它身上找 .relay 然后失败。
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
}
