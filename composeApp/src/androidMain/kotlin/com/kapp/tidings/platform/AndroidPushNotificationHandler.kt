// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.notification_channel_messages
import com.kapp.tidings.generated.resources.notification_channel_messages_desc
import org.jetbrains.compose.resources.getString

/**
 * Android 推送通知处理器。
 *
 * 用 `NotificationManager` 发**本地**通知；远程推送（FCM / UnifiedPush）需要服务端，
 * 还没接（计划里的 P5）。
 *
 * **权限这件事必须如实回答**：Android 13+ 的 `POST_NOTIFICATIONS` 是运行时权限、默认拒绝。
 * 上一版这里是 `override suspend fun requestPermission(): Boolean = true`，于是通知**从来没有
 * 出现过**，而调用方一路以为"权限已给"—— `showNotification` 里那个
 * `catch (e: SecurityException) {}` 把唯一能说话的异常也咽掉了。
 * 现在 [requestPermission] 返回系统弹窗的真实结果（[NotificationManagerCompat.areNotificationsEnabled]），
 * 拿不到就返回 `false`；[showNotification] 发不出去时会写一条 `Log.w`。
 *
 * **渠道文案不再写死在代码里**：渠道名/描述原来硬编码成中文字面量（"捎话消息" /
 * "来自对端的消息"），非中文系统上也只能看到中文。现在取自 composeResources
 * （`values/strings.xml` 的 `notification_channel_messages*`，见 [refreshChannelLabel]）。
 * 注意渠道名**被系统按 channel id 缓存**：光改文案，老用户看到的还是旧名字，所以这次
 * 同时换了 [CHANNEL_ID]（`relay_messages` → `relay_messages_v2`）并删掉旧渠道。
 *
 * **前台不抑制**：本类不区分前台/后台，应用在前台时收到消息也会发通知。这是已知的粗糙之处
 * （见 `RelayController` 的"仍未覆盖"清单），不是有意设计 —— 上一版 `RelayController`
 * 的 KDoc 写着"当应用在前台时不显示通知"，但代码里从来没有这个判断，那句话是错的。
 */
class AndroidPushNotificationHandler(
    private val context: Context,
    private val permissionBridge: AndroidPermissionBridge = AndroidPermissionBridge(),
) : PushNotificationHandler {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * 渠道文案的缓存。
     *
     * CMP 资源的读取是**挂起**函数（`getString`），而 [showNotification] 不是挂起函数，
     * 所以只能在有挂起上下文的地方读一次、存下来给后面用。`null` = 还没读到（或读不到），
     * 那时用系统给出的应用名顶上，而不是退回一个写死的字面量。
     */
    @Volatile
    private var channelLabel: ChannelLabel? = null

    init {
        retireLegacyChannel()
    }

    /**
     * 读本地化的渠道文案并写进渠道。只在挂起上下文里调用 —— CMP 的 `getString` 没有同步版本。
     *
     * [requestPermission] 是应用启动协程里最早的那次挂起调用（`RelayController.start()`），
     * 所以正常情况下渠道是**带着正确文案**被创建的，早于第一条通知。
     * 读不到就保持现状（`runCatching`），不让一次资源读取失败把权限流程带崩。
     */
    private suspend fun refreshChannelLabel() {
        val name = runCatching { getString(Res.string.notification_channel_messages) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val description = runCatching { getString(Res.string.notification_channel_messages_desc) }
            .getOrNull()
            .orEmpty()
        val label = ChannelLabel(name, description)
        channelLabel = label
        // 已存在的渠道只有 name/description 会被更新（重要级别是用户的地盘，系统不让我们改）。
        createNotificationChannel(label)
    }

    private fun createNotificationChannel(label: ChannelLabel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                label.name,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = label.description
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 保证渠道存在。
     *
     * 渠道必须在发第一条通知之前建好 —— 名字只有在创建的那一刻会被系统采用。
     * 文案还没读到（[channelLabel] 为 `null`）时先用**系统给出的应用名**顶上：那是系统按
     * 当前语言给出的，不写死任何语言的字面量；等挂起的那次 [refreshChannelLabel] 落地后会更新。
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        createNotificationChannel(channelLabel ?: ChannelLabel(systemAppLabel(), ""))
    }

    /** 系统按当前语言给出的应用名；取不到就退回包名。 */
    private fun systemAppLabel(): String =
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: context.packageName

    /**
     * 删掉改名前的旧渠道。
     *
     * 渠道名被系统按 id 缓存：只改显示文案，老用户看到的还是旧名字。所以改名必须**换 id**
     * （见 [CHANNEL_ID]），旧 id 顺手删掉 —— 留着只会在系统设置里多一条永远不会响的渠道。
     */
    private fun retireLegacyChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
    }

    override fun showNotification(title: String, body: String, focusPeerId: String?) {
        ensureNotificationChannel()
        if (!notificationsEnabled()) {
            // 不静默：这条日志是"通知为什么不出现"唯一的线索。
            // 用户可以在系统设置里关掉通知，也可以根本没答过 13+ 的权限弹窗。
            Log.w(TAG, "通知未发出：通知权限未授予（系统设置里可改）")
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(focusPeerId))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 上面刚查过权限，这里还抛只可能是竞态（用户在弹通知的同一刻关了权限）。
            Log.w(TAG, "通知未发出：${e.message}")
        }
    }

    /**
     * 点通知要真的有用。
     *
     * 上一版**完全没有** `setContentIntent`：`dumpsys notification` 里 `contentIntent=null`，
     * 真机上点通知卡片只会让它展开/收起，应用一动不动 —— 而验收里写的是"点开应用后直接
     * 落在与那个对端的会话里"。这里把"打开应用"连同对端 id 一起交给系统。
     *
     * 目标 Activity **不写死类名**：`getLaunchIntentForPackage` 拿到的就是清单里那个
     * LAUNCHER Activity（本仓是 `MainActivity`）；写死的话，改个类名这条就悄悄失效了。
     * 取不到就返回 `null`（`setContentIntent(null)` 是合法的，只是点通知不打开任何东西）。
     */
    private fun openAppIntent(focusPeerId: String?): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        // SINGLE_TOP + 清单里的 launchMode="singleTop"：应用已经在跑时走 onNewIntent，
        // 而不是又叠一个实例（叠上去的话新实例的 Compose 状态是空的，看起来像"重启了"）。
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (focusPeerId != null) {
            intent.putExtra(AndroidNotificationIntent.EXTRA_FOCUS_PEER, focusPeerId)
        }
        // 全程只有一条通知 id（[NOTIFICATION_ID]），所以请求码固定就够。
        // FLAG_IMMUTABLE 不是可选项：Android 12+ 不声明可变性会直接抛。
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 请求通知权限，返回**系统实际给出的答案**。
     *
     * 已经给了就直接返回 true，不再打扰用户；13 以下没有这个运行时权限，
     * `areNotificationsEnabled()` 就是全部真相。
     */
    override suspend fun requestPermission(): Boolean {
        // 渠道文案是本地化字符串，只有挂起上下文读得到；这是最早的那次挂起调用，顺手落地。
        refreshChannelLabel()
        if (notificationsEnabled()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // 13 以下没有 POST_NOTIFICATIONS：通知被关掉就是被关掉，问系统也问不出弹窗。
            // 如实返回"现在发不出去"，让上层给用户一条可走的路（系统设置），而不是假装成功。
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return permissionBridge.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private data class ChannelLabel(val name: String, val description: String)

    companion object {
        /**
         * 通知渠道 id。
         *
         * **改渠道的显示名字必须同时改这里**：系统按 id 缓存渠道名，只动文案的话老用户
         * 看到的还是旧名字（这正是这次换成 `_v2` 的原因 —— 原名 `relay_messages` 带的是
         * 硬编码的中文名）。
         */
        private const val CHANNEL_ID = "relay_messages_v2"

        /** 改名前的渠道 id，只在启动时删掉它。 */
        private const val LEGACY_CHANNEL_ID = "relay_messages"

        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AndroidPushNotification"
    }
}
