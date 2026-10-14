// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import app.relay.core.protocol.ScreenImageMetadata
import app.relay.generated.resources.Res
import app.relay.generated.resources.screen_remote_view_sharing_notification
import app.relay.generated.resources.screen_remote_view_sharing_notification_desc
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString

/**
 * Android 投屏帧源（Sharer 侧）：把「同意对方查看我的屏幕」变成一条真的能出帧的链路。
 *
 * ## 三步各归各位
 * 1. **授权**（[prepareCapture]）：`MediaProjectionManager.createScreenCaptureIntent()`
 *    的系统确认框只能由 Activity 发车，所以经 [AndroidScreenShareBridge] 把用户按下的
 *    "立即开始/取消"带回来（拿不到桥 = 没授权，返回 false，不猜）。
 * 2. **服务**：授权后的 `MediaProjection` 必须挂在带 `mediaProjection` 类型的
 *    **前台服务**上 —— Android 14 起不用前台服务直接 `createVirtualDisplay` 会抛
 *    `IllegalStateException`，Android 12 起后台起该服务被系统拒。服务类在宿主
 *    `androidApp` 里（清单组件属于 APK），双方经 [ScreenShareServiceGate] 会合。
 * 3. **取帧**（[captureFrame]）：服务里的 VirtualDisplay → ImageReader → JPEG，
 *    写进 gate 的信箱；这里取走并**校验上限**（长边 ≤ [Limits.SCREEN_MAX_EDGE]、
 *    ≤ [Limits.SCREEN_MAX_FRAME_BYTES]，对齐观看端 `validateScreenFrame`）。
 *    服务侧编码已按上限缩放；这里校验失败按"这一拍没新帧"处理 —— 宁可不发也不能
 *    让对端静默丢帧后判超时。
 *
 * ## 静止画面
 * `ImageReader` 画面静止时**没有新帧**，这会被 [app.relay.core.transport.ScreenSender]
 * 的 null 契约当成"采集结束"。重发上一帧由控制器侧的 `ScreenFrameSource` 统一处理，
 * 这里只如实报告"这一拍有没有新帧"。
 *
 * ## 外部终止
 * 用户在系统通知里点"停止投放"→ projection.onStop → 服务调
 * [ScreenShareServiceGate.notifyCaptureEnded] → [onCaptureEnded] 登记的回调 →
 * 控制器结束会话（capture-ended）。
 */
class AndroidScreenSharingService(
    private val context: Context,
    private val consentBridge: AndroidScreenShareBridge,
    /**
     * 宿主注册的投屏前台服务类（`androidApp` 清单里那个）。null = 宿主没有这个组件
     * （比如纯测试宿主），一律不支持 —— isShareSupported 因此如实回答，caps 不虚报。
     */
    private val serviceClass: Class<*>?,
) : ScreenSharingService {

    @Volatile
    private var mySessionActive = false

    override fun isShareSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && serviceClass != null

    override suspend fun prepareCapture(): Boolean {
        if (!isShareSupported()) return false
        val consent = consentBridge.requestConsent() ?: return false
        if (consent.resultCode != Activity.RESULT_OK || consent.data == null) return false

        // 授权只认这一次：先寄存，再起服务领取。服务起不来（ROM 杀后台等）时
        // 清掉寄存的授权，避免下一会话误用一个过期的 Intent。
        ScreenShareServiceGate.pendingResult = consent.resultCode to consent.data
        ScreenShareServiceGate.captureReady = false
        ScreenShareServiceGate.captureTerminated = false
        ScreenShareServiceGate.latestFrame = null
        mySessionActive = true

        // 通知文案在这里读好递进去：服务在宿主 APK 里，Compose 资源对它不可见。
        val (channel, body) = serviceNotificationTexts()
        runCatching { ScreenShareServiceGate.startService(context, serviceClass!!, channel, body) }
            .onFailure {
                ScreenShareServiceGate.pendingResult = null
                mySessionActive = false
                return false
            }

        // startForegroundService 到 captureReady 之间是异步的（建 projection + display）。
        // 等待有界：超时就报采集失败，让控制器发 reject，而不是挂着一个"连接中"。
        val deadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS
        while (!ScreenShareServiceGate.captureReady) {
            if (ScreenShareServiceGate.captureTerminated) {
                mySessionActive = false
                return false
            }
            if (System.currentTimeMillis() >= deadline) {
                ScreenShareServiceGate.stopService(context, serviceClass!!)
                ScreenShareServiceGate.pendingResult = null
                mySessionActive = false
                return false
            }
            delay(50)
        }
        return true
    }

    override suspend fun captureFrame(seq: Long): ByteArray? {
        if (!mySessionActive || ScreenShareServiceGate.captureTerminated) return null
        val frame = ScreenShareServiceGate.takeFrame() ?: return null
        // 上限在这里把死：观看端 validateScreenFrame 超限是**整帧拒收**，
        // 发送侧不校验的失败形态是"画面播几张然后卡死直到超时"，最难归因。
        // screenImageSize 内部含字节与尺寸两道闸（Limits.SCREEN_MAX_*），不必重复判。
        return runCatching { ScreenImageMetadata.screenImageSize(frame) }.getOrNull()?.let { frame }
    }

    override fun stopCapture() {
        mySessionActive = false
        val cls = serviceClass ?: return
        ScreenShareServiceGate.captureReady = false
        ScreenShareServiceGate.latestFrame = null
        runCatching { ScreenShareServiceGate.stopService(context, cls) }
    }

    override fun onCaptureEnded(callback: (() -> Unit)?) {
        // 服务的 onStop 只进 gate；这里做"会话相关性"过滤后再转给控制器 ——
        // 上一场会话的迟到 onStop 不该把刚开的新会话杀掉。
        ScreenShareServiceGate.endedHook = callback?.let { cb ->
            { if (mySessionActive) cb() }
        }
    }

    /**
     * 前台服务通知的渠道名 + 正文，取自 Compose 资源。
     *
     * 服务类在宿主 `androidApp`、资源在 `composeApp`，而 AAPT2 看不见 Compose 资源
     * （`values/strings.xml` 的文件头写过这条边界），所以文案在这里读好、经 intent
     * extra 递给服务 —— 两边都不写死字面量。渠道名只在创建时被系统采用，读不到时
     * 兜底用包名（系统按语言给出的形状），不是硬编码中文。
     */
    suspend fun serviceNotificationTexts(): Pair<String, String> {
        val channel = runCatching { getString(Res.string.screen_remote_view_sharing_notification) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: context.packageName
        val body = runCatching { getString(Res.string.screen_remote_view_sharing_notification_desc) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: channel
        return channel to body
    }

    companion object {
        const val EXTRA_CHANNEL = "app.relay.extra.SCREEN_CHANNEL"
        const val EXTRA_BODY = "app.relay.extra.SCREEN_BODY"
        const val NOTIFICATION_ID = 4201
        private const val PREPARE_TIMEOUT_MS = 8_000L
    }
}
