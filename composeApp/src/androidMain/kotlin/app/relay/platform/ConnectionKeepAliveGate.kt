// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 「后台保持连接」前台服务与平台层之间的**会合点**。
 *
 * 为什么需要它：服务类住在宿主 `androidApp`（清单组件属于 APK），`composeApp` 的平台层
 * 不认识它，两边只经这个 object 会合 —— 与 [ScreenShareServiceGate] 同一条规矩，
 * 不用反射、不用全局查找。这里比投屏那个简单得多：保活服务**没有**需要跨边界传递的
 * 一次性授权，只有"在不在跑"这一个布尔 + 三句通知文案。
 *
 * [running] 由服务自己在 `startForeground` 成功之后置 true、在 `onDestroy` 置 false；
 * 平台层据此如实回答"服务当前开没开"（而不是回答"用户上次拨到哪"）——
 * 常驻通知就是这条事实的可见证据，两者不许对不上。
 */
object ConnectionKeepAliveGate {

    /** 服务当前是否在跑。只在服务侧写、其它地方只读。 */
    @Volatile
    var running: Boolean = false

    const val ACTION_START = "app.relay.action.START_KEEP_ALIVE"
    const val ACTION_STOP = "app.relay.action.STOP_KEEP_ALIVE"

    const val EXTRA_CHANNEL = "app.relay.extra.KEEP_ALIVE_CHANNEL"
    const val EXTRA_TITLE = "app.relay.extra.KEEP_ALIVE_TITLE"
    const val EXTRA_BODY = "app.relay.extra.KEEP_ALIVE_BODY"

    /**
     * 与投屏通知的 4201 错开：同 id 的两条通知会互相覆盖，
     * 投屏期间开关保活就会把其中一条悄悄顶掉。
     */
    const val NOTIFICATION_ID = 4202

    fun start(context: Context, serviceClass: Class<*>, channel: String, title: String, body: String) {
        val intent = Intent(context, serviceClass)
            .setAction(ACTION_START)
            .putExtra(EXTRA_CHANNEL, channel)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_BODY, body)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * 停服务用 `stopService`，**不是** `startService(ACTION_STOP)`。
     *
     * 差别是实质性的：`startService` 会把一个没在跑的服务先拉起来再让它自己收摊 ——
     * 用户关掉开关后系统里会**闪一下**前台服务（Android 12+ 从后台调 `startService`
     * 还可能直接抛 `IllegalStateException`）。投屏那边必须走 ACTION_STOP，是因为它要
     * 在服务里释放 MediaProjection；保活服务没有任何资源要释放，直接停即可。
     */
    fun stop(context: Context, serviceClass: Class<*>) {
        context.stopService(Intent(context, serviceClass))
    }
}
