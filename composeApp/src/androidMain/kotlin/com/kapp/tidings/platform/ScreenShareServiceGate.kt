// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.content.Context
import android.content.Intent

/**
 * 投屏前台服务与平台层之间的**进程内协调点**。
 *
 * 为什么需要它：`MediaProjection` 的授权 Intent 只能交给 `getMediaProjection()` 用**一次**，
 * 而创建 `VirtualDisplay` 之后的取帧必须与那个 projection 同属一个持有者；服务由
 * `startForegroundService` 拉起，创建路径跨进程组件边界，`AndroidScreenSharingService`
 * 拿不到服务的实例引用。与 [AndroidScreenShareBridge] 同一个理由 —— 两边生命周期
 * 对不上，就立一个显式的会合点，而不是反射或全局查找。
 *
 * 服务类本身在宿主 `androidApp` 里（清单注册的组件必须属于应用 APK），所以这里收
 * `Class<*>`；composeApp 的平台层不需要认识它，只需要往同一只信箱投递。
 *
 * 规矩：
 * - [pendingResult] 一次性消费，服务重复 start 拿不到第二份授权；
 * - 服务在 `projection.onStop` / 被杀时 [notifyCaptureEnded]，让控制器知道
 *   "用户在系统通知里点了停止" —— 那是真终止，不能伪装成一次 null 采样
 *   （静止画面的缓存重发会把它吞掉）。
 */
object ScreenShareServiceGate {

    /** 用户刚批准的授权（resultCode + data Intent），等待服务领取；领取后置空。 */
    @Volatile
    var pendingResult: Pair<Int, Intent?>? = null

    /** 服务里 projection + ImageReader 是否已就绪（帧可取的前提）。 */
    @Volatile
    var captureReady: Boolean = false

    /** 采集已被系统侧终止（onStop / 服务销毁）。只有新一次采集会复位。 */
    @Volatile
    var captureTerminated: Boolean = false

    /** 帧数据出口：服务写最新一帧的 JPEG 字节，`slot` 是它的代际号。 */
    @Volatile
    var latestFrame: ByteArray? = null

    @Volatile
    var frameSlot: Long = 0

    /** 平台层登记的「外部终止」回调；由控制器在 accept 后注册、finish 时注销。 */
    @Volatile
    var endedHook: (() -> Unit)? = null

    /** 一次性领取授权结果：取走即空，保证一份 consent 只建一个 projection。 */
    fun takePendingResult(): Pair<Int, Intent?>? {
        val value = pendingResult
        pendingResult = null
        return value
    }

    /**
     * 服务侧发布一帧。**不能"取走即空"**：写入方是 handler 线程、读取方是采集协程，
     * 单字段的 take-and-clear 会在两线程之间丢帧 —— 静止时缓存重发旧帧，看起来一切正常，
     * 一旦画面动起来就丢帧（接线时按"写入代际 + 读取已消费代际"改成槽号协议）。
     */
    fun publishFrame(bytes: ByteArray) {
        latestFrame = bytes
        frameSlot += 1
    }

    /** 读取方上次消费的代际（只被采集协程写）。 */
    private var consumedSlot: Long = 0

    /** 没有比 consumedSlot 更新的帧时返回 null —— "这一拍没新帧"，由 ScreenFrameSource 重发上一帧。 */
    fun takeFrame(): ByteArray? {
        val slot = frameSlot
        if (slot == consumedSlot) return null
        consumedSlot = slot
        return latestFrame
    }

    fun startService(context: Context, serviceClass: Class<*>, channel: String, body: String) {
        val intent = Intent(context, serviceClass)
            .setAction(ACTION_START)
            .putExtra(AndroidScreenSharingService.EXTRA_CHANNEL, channel)
            .putExtra(AndroidScreenSharingService.EXTRA_BODY, body)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService(context: Context, serviceClass: Class<*>) {
        context.startService(Intent(context, serviceClass).setAction(ACTION_STOP))
    }

    /** 复位一次新采集的状态（服务在建好 projection 后调）。 */
    fun beginCapture() {
        captureTerminated = false
        latestFrame = null
        // 代际清零：新会话从"谁都没读过"起算。
        frameSlot = 0
        consumedSlot = 0
        captureReady = true
    }

    fun notifyCaptureEnded() {
        captureReady = false
        captureTerminated = true
        latestFrame = null
        runCatching { endedHook?.invoke() }
    }

    const val ACTION_START = "com.kapp.tidings.action.START_SCREEN_SHARE"
    const val ACTION_STOP = "com.kapp.tidings.action.STOP_SCREEN_SHARE"
}
