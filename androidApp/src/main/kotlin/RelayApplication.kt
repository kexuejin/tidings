// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import android.app.Application
import app.relay.platform.AndroidAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 节点活到进程结束，而不是活到某个 Activity 结束。
 *
 * 理由很实际：把控制器挂在 Activity 上，转屏就会走 `onDestroy` → 停掉节点 →
 * 立刻重新 bind 同一个 UDP 端口，而上一次的 socket 未必已经释放，
 * 用户看到的是"转个屏就启动失败"。信使的监听本来就该和进程同寿命。
 */
class RelayApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 平台对象要单独留一手，因为 SAF 的启动器只能在 Activity/Compose 里注册，
     * 而它构造在 Application 里 —— [pickerBridge] 就是这两层之间那根线。
     *
     * 投屏前台服务类同样在这里递进去：清单组件属于 APK，`composeApp` 的平台层
     * 不认识它，只经 `ScreenShareServiceGate` 会合（见 `AndroidScreenSharingService` 类注释）。
     */
    val platform: AndroidAppPlatform by lazy {
        AndroidAppPlatform(
            context = this,
            screenShareServiceClass = ScreenShareForegroundService::class.java,
            // 首次运行时的默认显示名：一句要本地化的文案，所以从 Android 资源读，
            // 而不是让共享代码里写死一个词（桌面端就是被那个词害得撞名的）。
            defaultNick = getString(R.string.device_default_nick),
        )
    }

    val controller: RelayController by lazy {
        RelayController(
            platform = platform,
            store = createSettingsStore(platform),
            scope = scope,
            // 设置里那个「消息落盘」开关背后必须是真的文件存储，
            // 否则打开开关也只能活到进程结束 —— 界面在骗人。
            messageStore = createMessageStore(platform),
            agentStore = createAgentStore(platform),
        )
    }
}
