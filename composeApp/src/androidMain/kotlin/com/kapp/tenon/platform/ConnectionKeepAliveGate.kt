// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.platform

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

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

    /**
     * 有一条「被后台启动限制拦下」的启动请求在等进前台。
     *
     * 只有 [start] 被时机判定拦下（或发了请求仍被系统拒绝）才有值：Android 12+ 从后台
     * 起 FGS 被系统拒绝时，那个请求连同它带的文案就废了 —— 而文案（频道/标题/正文）是
     * 调用方从资源里读好递进来的，Gate 自己读不到。所以登记的时候把整套参数存下来，
     * 进前台时原样再发一次。@Volatile 单槽就够：并发竞争的最坏结果是保留其中一次请求。
     */
    @Volatile
    private var pendingStart: PendingStart? = null

    /** 一次被拦下的启动请求：服务类与通知文案，进前台后原样重发。 */
    internal data class PendingStart(
        val serviceClass: Class<*>,
        val channel: String,
        val title: String,
        val body: String,
    )

    const val ACTION_START = "com.kapp.tenon.action.START_KEEP_ALIVE"
    const val ACTION_STOP = "com.kapp.tenon.action.STOP_KEEP_ALIVE"

    const val EXTRA_CHANNEL = "com.kapp.tenon.extra.KEEP_ALIVE_CHANNEL"
    const val EXTRA_TITLE = "com.kapp.tenon.extra.KEEP_ALIVE_TITLE"
    const val EXTRA_BODY = "com.kapp.tenon.extra.KEEP_ALIVE_BODY"

    /**
     * 与投屏通知的 4201 错开：同 id 的两条通知会互相覆盖，
     * 投屏期间开关保活就会把其中一条悄悄顶掉。
     */
    const val NOTIFICATION_ID = 4202

    fun start(context: Context, serviceClass: Class<*>, channel: String, title: String, body: String): Boolean {
        // 时机不允许（Android 12+ 的进程自启、锁屏拉起：进程在栈顶但屏灭着，系统不认
        // 它在前台）就不发这个注定被拒的调用 —— 「不产出注定被拒的请求」与协议帧预算
        // 是同一条不变量。登记下来等进前台再发；请求本身不带任何副作用。
        if (!canStartForegroundServiceNow(
                sdkInt = Build.VERSION.SDK_INT,
                hasSystemAlertWindow = canDrawOverlays(context),
                processForeground = isSelfForeground(context),
            )
        ) {
            pendingStart = PendingStart(serviceClass, channel, title, body)
            return false
        }
        val started = runCatching {
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
        }.isSuccess
        // 预判放行但系统仍拒绝（ROM 差异、状态窗口期）时兜底登记：宁可多等一次
        // promote，也不让用户开着的开关悄悄缺席。老系统没有后台启动限制，不会走到这条。
        if (!started) {
            pendingStart = PendingStart(serviceClass, channel, title, body)
        }
        return started
    }

    /**
     * 时机判定（可注入纯函数）：**现在**允许起前台服务吗。
     *
     * Android 12+ 的后台启动豁免名单里有 SYSTEM_ALERT_WINDOW 与「应用恰好在前台」两种
     * 本应用用得上的情形。三个输入全部由调用方递进来（sdkInt / 悬浮窗授权 / 进程前台态），
     * 判定本身不读系统 —— 规则才可能被单测钉住。
     */
    internal fun canStartForegroundServiceNow(
        sdkInt: Int,
        hasSystemAlertWindow: Boolean,
        processForeground: Boolean,
    ): Boolean = sdkInt < Build.VERSION_CODES.S ||
        hasSystemAlertWindow ||
        processForeground

    /** 有没有悬浮窗授权（豁免之一）。没有授权或读不到时如实回报 false。 */
    private fun canDrawOverlays(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    /**
     * 本进程此刻是不是系统眼里的前台。「锁屏拉起」现场的进程在栈顶却屏灭着 ——
     * 那是 TOP_SLEEPING，importance 高于前台档，如实返回 false；解锁获焦后回到
     * 前台档，返回 true。读不到进程列表（罕见的 ROM 限制）按"不在前台"处理：
     * 代价只是这次启动延后到 promote，不是错误状态。
     */
    private fun isSelfForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val procs = runCatching { am.runningAppProcesses }.getOrNull().orEmpty()
        return procs.any {
            it.pid == Process.myPid() &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    /**
     * 进前台后调用：把被拦下的那一次启动**原样重发**。
     *
     * 重发仍走 [start]（runCatching 兜底不变）—— 若此刻仍被拒（理论不该发生：
     * Activity resumed 且窗口获焦时调用方就是前台），登记会再次建立，等下一个前台时机。
     * 成功或已无 pending 都会清登记：重发只做一次，别在每次 onWindowFocusChanged
     * 里反复拉起一个用户已经关掉的开关。
     */
    fun promote(context: Context): Boolean {
        val pending = pendingStart ?: return false
        pendingStart = null
        return start(context, pending.serviceClass, pending.channel, pending.title, pending.body)
    }

    /** 清掉登记。宿主退出前台（不再可能立刻 promote）时调用，避免陈旧文案活过整个后台期。 */
    fun clearPendingStart() {
        pendingStart = null
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
