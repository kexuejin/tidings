// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import app.relay.platform.AndroidScreenSharingService
import app.relay.platform.ScreenShareServiceGate
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 投屏采集的**前台服务**：持有 MediaProjection + VirtualDisplay + ImageReader，
 * 把每一拍新帧编码成有界 JPEG 放进 [ScreenShareServiceGate] 信箱。
 *
 * 为什么必须在服务里、且必须是带 `mediaProjection` 类型的**前台**服务：
 * Android 14 起，没有该类型前台服务在跑时 `getMediaProjection`/`createVirtualDisplay`
 * 直接抛异常；Android 12 起后台拉起这个服务被系统拒。两条都是"构建全绿、
 * 真机静默失败"那类缺陷（清单里 INTERNET/POST_NOTIFICATIONS 的注释立的规矩：
 * 这类坑必须写在声明它的文件头上）。
 *
 * 服务类住在宿主（清单组件属于 APK），平台层在 `composeApp` 不认识它，
 * 双方只经 [ScreenShareServiceGate] 会合。通知文案同样由平台层读好
 * （Compose 资源，AAPT2 看不见它）经 intent extra 递进来 —— 这里不写死任何字面量。
 */
class ScreenShareForegroundService : Service() {

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ScreenShareServiceGate.ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY // 重复 start：已经在采集

        // 前台化是**无条件第一步**：从 startForegroundService 起算，10 秒内不调
        // startForeground 系统就抛 "did not then call Service.startForeground()" ——
        // 哪怕后面走的是"没授权、直接收摊"的路径也一样炸。放在所有 stopSelf 分支之前。
        // 它也必须先于 getMediaProjection/createVirtualDisplay（Android 14 的硬前置）。
        startAsForeground(intent)

        val consent = ScreenShareServiceGate.takePendingResult()
        if (consent == null) {
            // 没有可领取的授权（过期重放）。captureReady 保持 false，
            // 等待方（平台层）按超时判采集失败，这里直接收摊。
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val data = consent.second
        val created = if (manager == null || data == null) null
            else runCatching { manager.getMediaProjection(consent.first, data) }.getOrNull()
        if (created == null) {
            failAndStop()
            return START_NOT_STICKY
        }
        projection = created

        val thread = HandlerThread("relay-screen-capture").also { it.start() }
        worker = thread
        val hdl = Handler(thread.looper)

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户在系统通知/控制中心点了"停止投放"：真终止，必须立刻说出去，
                // 不能让会话空挂到帧超时。
                ScreenShareServiceGate.notifyCaptureEnded()
                teardown()
                stopSelf()
            }
        }
        projectionCallback = callback
        runCatching { created.registerCallback(callback, hdl) }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") // defaultDisplay：API 30 起废弃，但 24~29 只有它；minSdk 24 下这是通用写法
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(metrics)
        val sw = metrics.widthPixels.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val sh = metrics.heightPixels.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        val dpi = metrics.densityDpi.takeIf { it > 0 } ?: DEFAULT_DPI

        // 长边超协议上限时把 VirtualDisplay 的分辨率直接缩到上限内：
        // 比按全尺寸采回来再缩 Bitmap 省一整轮像素拷贝（4K 屏上一拍 33MB）。
        val scale = min(1f, MAX_EDGE.toFloat() / max(sw, sh))
        val captureW = (sw * scale).toInt().coerceAtLeast(MIN_CAPTURE_EDGE)
        val captureH = (sh * scale).toInt().coerceAtLeast(MIN_CAPTURE_EDGE)

        val newReader = ImageReader.newInstance(captureW, captureH, android.graphics.PixelFormat.RGBA_8888, 2)
        reader = newReader
        newReader.setOnImageAvailableListener({ r -> onFrame(r) }, hdl)

        val display = runCatching {
            created.createVirtualDisplay(
                "relay-screen-share",
                captureW, captureH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                newReader.surface, null, hdl,
            )
        }.getOrNull()
        if (display == null) {
            failAndStop()
            return START_NOT_STICKY
        }
        virtualDisplay = display
        ScreenShareServiceGate.beginCapture()
        return START_NOT_STICKY
    }

    private fun failAndStop() {
        ScreenShareServiceGate.notifyCaptureEnded()
        teardown()
        stopSelf()
    }

    /**
     * 一帧：取最新 Image → RGBA 拷进 Bitmap → JPEG（先降质量、再降尺寸进 512KB）。
     *
     * 画面静止时 `acquireLatestImage()` 不给新帧 —— 返回 null 是**常态**，
     * 由平台层的 `ScreenFrameSource` 决定"重发上一帧"；这里只如实报告有没有新帧。
     */
    private fun onFrame(source: ImageReader) {
        if (busy || ScreenShareServiceGate.captureTerminated) return
        busy = true
        try {
            val image: Image = source.acquireLatestImage() ?: return
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val padded = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888,
                )
                padded.copyPixelsFromBuffer(plane.buffer)
                var out = if (rowPadding == 0) {
                    padded
                } else {
                    // ImageReader 的行按 4/32/64 对齐补齐，实际画面只有 width 宽；裁掉尾部垃圾列。
                    Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                }

                var quality = DEFAULT_JPEG_QUALITY
                var bytes = compressJpeg(out, quality)
                while (bytes != null && bytes.size > MAX_FRAME_BYTES && quality > MIN_JPEG_QUALITY) {
                    quality -= 10
                    bytes = compressJpeg(out, quality)
                }
                var guard = 0
                while (bytes != null && bytes.size > MAX_FRAME_BYTES && guard < MAX_DOWNSCALE_ROUNDS) {
                    guard++
                    out = scaleBitmap(out, (out.width * 0.8f).toInt().coerceAtLeast(MIN_CAPTURE_EDGE), (out.height * 0.8f).toInt().coerceAtLeast(MIN_CAPTURE_EDGE))
                    bytes = compressJpeg(out, quality.coerceAtLeast(MIN_JPEG_QUALITY))
                }
                // 仍超限 / 编码失败：这一拍不发帧 —— 对端把"没新帧"当静止处理，不当故障。
                if (bytes != null && bytes.size in 1..MAX_FRAME_BYTES) {
                    // 走 publishFrame：连代际号一起涨。直接赋值 latestFrame 的话
                    // 读侧的 takeFrame 永远等不到新代际，症状是「只出第一帧然后静止」。
                    ScreenShareServiceGate.publishFrame(bytes)
                }
            } finally {
                image.close()
            }
        } catch (_: Throwable) {
            // 编码路径的任何异常都不该杀服务：丢一帧远比丢整个会话好。
        } finally {
            busy = false
        }
    }

    private fun compressJpeg(bmp: Bitmap, quality: Int): ByteArray? = runCatching {
        val sink = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, sink)
        sink.toByteArray()
    }.getOrNull()

    private fun scaleBitmap(bmp: Bitmap, w: Int, h: Int): Bitmap {
        val m = Matrix()
        m.setScale(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun startAsForeground(intent: Intent?) {
        val channel = intent?.getStringExtra(AndroidScreenSharingService.EXTRA_CHANNEL)
            ?: DEFAULT_CHANNEL_ID
        val body = intent?.getStringExtra(AndroidScreenSharingService.EXTRA_BODY).orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_LOW)
                    .apply { description = body },
            )
        }
        // 点通知 = 结束共享：PendingIntent 直接指回本服务的 STOP action，不必绕回 Activity。
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenShareForegroundService::class.java).setAction(ScreenShareServiceGate.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, channel)
            .setContentTitle(channel)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AndroidScreenSharingService.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(AndroidScreenSharingService.NOTIFICATION_ID, notification)
        }
    }

    private fun teardown() {
        ScreenShareServiceGate.captureReady = false
        ScreenShareServiceGate.latestFrame = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        val cb = projectionCallback
        val pr = projection
        if (cb != null && pr != null) runCatching { pr.unregisterCallback(cb) }
        projectionCallback = null
        runCatching { pr?.stop() }
        projection = null
        worker?.quitSafely()
        worker = null
    }

    override fun onDestroy() {
        // 服务被系统回收（内存压力、ROM 省电）也要把"采集没了"说出去。
        ScreenShareServiceGate.notifyCaptureEnded()
        teardown()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_CHANNEL_ID = "relay_screen_share"
        private const val DEFAULT_WIDTH = 1080
        private const val DEFAULT_HEIGHT = 1920
        private const val DEFAULT_DPI = 320
        private const val MIN_CAPTURE_EDGE = 16
        // 协议上限（core 的 Limits，implementation 依赖面外带不进来）。
        // 值必须与 `Limits.SCREEN_MAX_EDGE` / `Limits.SCREEN_MAX_FRAME_BYTES` 逐字一致；
        // 就算这里失守，平台层 captureFrame 还会用 core 的校验把超帧拦下——两层都在，
        // 因为观看端对超限帧是整帧拒收，症状是"播几张后卡到超时"，最难归因。
        private const val MAX_EDGE = 1920
        private const val MAX_FRAME_BYTES = 512 * 1024
        private const val DEFAULT_JPEG_QUALITY = 70
        private const val MIN_JPEG_QUALITY = 30
        private const val MAX_DOWNSCALE_ROUNDS = 4
    }
}
