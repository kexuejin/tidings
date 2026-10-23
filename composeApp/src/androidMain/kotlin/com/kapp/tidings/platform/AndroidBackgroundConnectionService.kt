// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.content.Context
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.keep_alive_channel_name
import com.kapp.tidings.generated.resources.keep_alive_notification_body
import com.kapp.tidings.generated.resources.keep_alive_notification_title
import org.jetbrains.compose.resources.getString

/**
 * Android 侧的「后台保持连接」：拉起/停掉宿主里的保活前台服务。
 *
 * 前台服务类型选 `remoteMessaging`（清单里声明），理由见 AndroidManifest.xml 该服务
 * 那一节的注释与本次任务报告 —— 一句话：它是官方给"接收消息"场景的类型，运行时前置
 * 条件为空，既没有 dataSync 的 Android 15 每天 6 小时上限，也不需要 connectedDevice
 * 的附加权限或 specialUse 的 Play 控制台用途申报。
 *
 * 通知文案在这里读好递进服务：服务在宿主 APK、AAPT2 看不见 Compose 资源
 * （与 [AndroidScreenSharingService.serviceNotificationTexts] 同一条边界）。
 */
internal class AndroidBackgroundConnectionService(
    private val context: Context,
    private val serviceClass: Class<*>?,
) : BackgroundConnectionService {

    override val isSupported: Boolean get() = serviceClass != null

    /** 如实回答**服务当前在不在跑**：不读用户偏好，读的是服务自己维护的那一位。 */
    override fun isEnabled(): Boolean = ConnectionKeepAliveGate.running

    override suspend fun setEnabled(enabled: Boolean) {
        val cls = serviceClass ?: return
        if (!enabled) {
            ConnectionKeepAliveGate.stop(context, cls)
            return
        }
        val (channel, title, body) = notificationTexts()
        // 后台启动前台服务在 Android 12+ 会被拒（抛异常）。这里吞掉而不是崩：
        // 拉不起来就不该有常驻通知，isEnabled() 会如实回报 false。
        runCatching { ConnectionKeepAliveGate.start(context, cls, channel, title, body) }
    }

    private suspend fun notificationTexts(): Triple<String, String, String> {
        val channel = runCatching { getString(Res.string.keep_alive_channel_name) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: context.packageName
        val title = runCatching { getString(Res.string.keep_alive_notification_title) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: channel
        val body = runCatching { getString(Res.string.keep_alive_notification_body) }
            .getOrNull()
            .orEmpty()
        return Triple(channel, title, body)
    }
}
