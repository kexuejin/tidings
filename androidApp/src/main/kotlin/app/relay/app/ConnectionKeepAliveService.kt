// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.relay.platform.ConnectionKeepAliveGate

/**
 * 「后台保持连接」前台服务：**只做一件事** —— 让进程在应用退到后台时不被系统打成
 * cached 并收紧网络，这样节点仍能被局域网里的设备发现、仍能收发消息。
 *
 * ## 为什么必须是前台服务（真机实测的根因）
 *
 * 真机实测：应用退到后台后进程活着、UDP 17878 / TCP 17879 仍绑着、
 * dumpsys activity processes 显示 isFrozen=false，但它处于 cached 状态
 * （state: cur=CAC、mCurrentFocus=null）—— 对端约 90 秒后按离线判定把它摘出 peer 表，
 * 消息全部积压在队列里（实测 40 条）。把它切回前台，对端**立刻**重新发现它、
 * 新消息当场到达。所以缺的不是 socket，而是"这个进程还在前台做事"这一句系统听得懂的话。
 *
 * ## 为什么选 remoteMessaging 类型
 *
 * 见清单里该服务那一节的注释（类型选择与 Play 政策的完整理由写在 AndroidManifest.xml）。
 * 一句话：官方 FGS 类型表里它的运行时前置条件为空，没有 dataSync 的 Android 15
 * 每天 6 小时上限，也不需要 specialUse 的 Play 控制台用途申报。
 *
 * ## 它不搬运消息，也不假装是消息
 *
 * 常驻通知上写的是「正在保持连接 / 以便接收消息」—— 如实说明这是"为了收消息而占着前台"，
 * 不是伪装成一条新消息骗用户点开。用户关掉设置里的开关就立刻停。
 *
 * ## 它和长连接（K1/K2）的关系（task-37 ②）
 *
 * 长连接（LinkClient / LinkServer）**不在这个服务里** —— 它活在同一个进程的
 * RelayController / NodeRuntime 里。这个服务不搬运任何字节，它只做一件事：
 * 让系统把本进程从 cached 状态里拿出去，从而**不收紧它的网络、不冻结它**。
 * 所以「长连接的生命周期挂在保活服务上」这句话的准确含义是：
 *
 * - 用户开着「后台保持连接」（默认开）⇒ 服务在跑 ⇒ 进程不被 cached ⇒ 长连接的心跳、
 *   重连、下行都还能走（真机 K1/K2 验收就是这么过的）；
 * - 用户关掉它 ⇒ 服务停 ⇒ 退到后台后进程被 cached，长连接会被系统连人带网一起冻住；
 *   设置页那句「关掉立即停止」说的就是这个，不是隐藏行为；
 * - 服务**启动失败**（Android 12+ 的后台启动限制、缺权限）时 gate.running=false，
 *   常驻通知不会出现 —— 这时长连接同样会在后台失效，而用户能从「没有常驻通知」看出来。
 *
 * 这里刻意**不**申请 WakeLock：前台服务已经让进程免于被冻结，网络收发的唤醒由内核
 * 网络栈负责；再加一把 CPU 锁只会白耗电，而不会让心跳更可靠。
 *
 * ## 划掉最近任务**不会**停掉它（刻意，不是漏了 stopWithTask）
 *
 * 与投屏服务不同，这里刻意**不设** android:stopWithTask="true"：对信使类应用，
 * "从最近任务划掉"不等于"退出"—— 用户划掉它，恰恰是希望它继续在后台收消息
 * （原始诉求就是"手机没推送 / 收不到"）。而且行为不是隐藏的：通知栏里那条常驻通知
 * 一直在，正文写明"关掉「后台保持连接」即可停止"，设置页也有同名开关 ——
 * 停止入口是明确且可见的。若反过来把划掉当停止，就与本功能的初衷相反。
 *
 * 服务类住在宿主（清单组件属于 APK），平台层在 composeApp 不认识它，
 * 双方只经 [ConnectionKeepAliveGate] 会合；通知文案由平台层从 Compose 资源读好
 * 经 intent extra 递进来 —— 这里不写死任何字面量（与投屏服务同一条边界）。
 */
class ConnectionKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ConnectionKeepAliveGate.ACTION_STOP) {
            stopAsForeground()
            stopSelf()
            return START_NOT_STICKY
        }

        val channel = intent?.getStringExtra(ConnectionKeepAliveGate.EXTRA_CHANNEL)
            .orEmpty()
            .ifBlank { packageName }
        val title = intent?.getStringExtra(ConnectionKeepAliveGate.EXTRA_TITLE)
            .orEmpty()
            .ifBlank { channel }
        val body = intent?.getStringExtra(ConnectionKeepAliveGate.EXTRA_BODY).orEmpty()

        // 前台化是**无条件第一步**：从 startForegroundService 起算，10 秒内不调
        // startForeground 系统就抛 "did not then call Service.startForeground()"。
        // 失败时（Android 12+ 的后台启动限制、缺权限）不崩，而是如实收摊：
        // 拉不起来就不该有常驻通知，gate.running 也就不会变成 true。
        val started = runCatching { startAsForeground(channel, title, body) }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        ConnectionKeepAliveGate.running = true

        // START_NOT_STICKY 是**刻意**的，不是省事：这个服务不持有任何可恢复的东西 ——
        // 真正在收发消息的是 Application 里的控制器，服务只是"别把我冻住"的一块牌子。
        // 系统在内存压力下杀掉进程后把它粘性重启，会得到一个"有常驻通知、但没人收消息"
        // 的谎；不重启反而诚实：通知消失 = 保活真的没了，用户能看见。
        return START_NOT_STICKY
    }

    private fun startAsForeground(channel: String, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = body
                        setShowBadge(false)
                    },
            )
        }
        // 点通知 = 回到应用（不结束保活）：这条通知是"应用还在后台收消息"的入口，
        // 不是"结束"按钮。launchMode=singleTop 让已有实例走 onNewIntent，不重启界面。
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ConnectionKeepAliveGate.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(ConnectionKeepAliveGate.NOTIFICATION_ID, notification)
        }
    }

    private fun stopAsForeground() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        // 通知没了 = 保活没了：这一位必须跟着服务走，不能留在 true 上。
        ConnectionKeepAliveGate.running = false
        super.onDestroy()
    }
}
