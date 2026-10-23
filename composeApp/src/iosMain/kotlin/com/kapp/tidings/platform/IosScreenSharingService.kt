// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import com.kapp.tidings.core.protocol.ScreenImageMetadata
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import kotlin.concurrent.Volatile
import platform.UIKit.UIApplication
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.posix.memcpy

/**
 * iOS 投屏帧源（Sharer 侧）：读 ReplayKit **广播扩展**写进 App Group 共享容器的帧槽。
 *
 * ## 为什么是广播扩展而不是应用内采集
 * `RPScreenRecorder` 的应用内采集只能采到**本 App 自己的窗口**，镜像不出桌面与其他 App。
 * 全屏镜像的唯一合法来源是 `RPBroadcastSampleHandler`（Broadcast Upload Extension，
 * 独立进程），它的产物只能经 App Group 传回主 App。跨进程契约就一条，两侧逐字一致：
 *
 *   扩展（Swift，`iosApp/RelayBroadcastUpload`）：CMSampleBuffer → JPEG
 *   （长边 ≤1920、≤512KB，与 Limits 一致）→ **双槽交替 + 先 .tmp 再 rename**（原子写，
 *   读侧永远读到完整文件）写进 `<容器>/screen-share/frame-<0|1>.jpg`。
 *   主 App（本类）：prepareCapture 弹 `RPSystemBroadcastPickerView` 让用户开广播 →
 *   轮询等首个帧文件出现 → captureFrame 读"更新的那一槽"，mtime 没前进 = 没有新帧。
 *
 * ## 为什么是轮询而不是 darwin notify
 * 帧泵的节拍本来就由观看端 `screen-next` 驱动（每秒 3~10 次采样），"通知"在这个节奏里
 * 只是把"发现新帧"提前几十毫秒；而 K/N 里 `CFNotificationCenterAddObserver` 需要
 * 精确的 `CFunction` 签名与进程级回调表，脆且不可单测。**跨进程状态只有文件**这一个
 * 事实来源，比信号 + 文件两份状态可靠。代价如实写在下条。
 *
 * ## 已知取舍（不粉饰）
 * - 用户从控制中心停广播后，主 App 只能靠**帧静默**发现：观看端 5 秒帧超时 /
 *   发送泵 10 秒空闲超时结束会话。没有即时的 `broadcast-ended` 通道。
 * - 主 App 切后台即被冻结，推流停 —— 前台限制见 `IosAppPlatform` 文件头，验收全程前台。
 * - 模拟器不支持广播扩展；未装扩展的宿主里 [isShareSupported] 如实回答 false（caps 不虚报）。
 *   `canOpenURL` 要求 `Info.plist` 的 `LSApplicationQueriesSchemes` 声明了扩展 scheme，
 *   缺声明时恒为 false —— 症状恰好是"投屏入口消失"，与真不支持同形，所以 plist 那条必须同步。
 */
@OptIn(ExperimentalForeignApi::class)
class IosScreenSharingService : ScreenSharingService {

    @Volatile private var sessionActive = false

    /** 本会话开始时刻（epoch 秒）：早于它的残留帧不算"首帧已到"。 */
    @Volatile private var sessionStart: Double = 0.0

    /** 上一次交付帧的 (mtime, slot)；新读到的与之相同 = 这一拍没有新帧。 */
    @Volatile private var lastDeliveredMtime: Double = -1.0
    @Volatile private var lastDeliveredSlot = -1

    private val fm = NSFileManager.defaultManager

    override fun isShareSupported(): Boolean {
        val url = NSURL.URLWithString(IosScreenShare.EXTENSION_URL) ?: return false
        return UIApplication.sharedApplication.canOpenURL(url)
    }

    override suspend fun prepareCapture(): Boolean {
        if (!isShareSupported()) return false
        sessionActive = true
        sessionStart = nowEpochSeconds()
        lastDeliveredMtime = -1.0
        lastDeliveredSlot = -1
        presentPicker()

        // 等首帧，有界：用户在系统面板里没开广播/取消 → 超时返回 false →
        // 控制器 reject(capture-failed)，而不是挂着一个"连接中"。
        val deadline = nowMillis() + PREPARE_TIMEOUT_MS
        while (newestFrame() == null) {
            if (!sessionActive) return false
            kotlinx.coroutines.delay(80)
            if (nowMillis() >= deadline) {
                sessionActive = false
                return false
            }
        }
        return true
    }

    override suspend fun captureFrame(seq: Long): ByteArray? {
        if (!sessionActive) return null
        val (slot, mtime, path) = newestFrame() ?: return null
        if (mtime == lastDeliveredMtime && slot == lastDeliveredSlot) return null

        val data = NSData.create(contentsOfFile = path) ?: return null
        val len = data.length.toInt()
        if (len == 0 || len > MAX_BYTES) return null
        val bytes = ByteArray(len)
        val src = data.bytes ?: return null
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), src, data.length)
        }
        // 上限在这里把死（观看端对超限帧是整帧拒收，症状是"播几张后卡到超时"，最难归因）。
        // 坏帧（撕裂/非 JPEG，rename 原子写下理论上不该出现）按"这一拍没新帧"处理，自愈。
        val valid = runCatching { ScreenImageMetadata.screenImageSize(bytes) }.getOrNull()
        if (valid == null) return null
        lastDeliveredMtime = mtime
        lastDeliveredSlot = slot
        return bytes
    }

    override fun stopCapture() {
        sessionActive = false
    }

    // ---------------------------------------------------------------- 会合点

    /** 更新的那一槽：(槽号, mtime, 路径)；两槽都还没有会话后的新文件时 null。 */
    private fun newestFrame(): Triple<Int, Double, String>? {
        val dir = frameDir() ?: return null
        var best: Triple<Int, Double, String>? = null
        for (slot in 0..1) {
            val path = "$dir/frame-$slot.jpg"
            // NSFileModificationDate 这个键常量没进 K/N 的 Foundation 面（2.4.0 实测），
            // 但字典里的键就是这个字符串（ObjC 常量值 == 名字），按字符串取再转 NSDate。
            val attrs = fm.attributesOfItemAtPath(path, error = null) ?: continue
            val mtime = (attrs["NSFileModificationDate"] as? NSDate)?.timeIntervalSince1970 ?: continue
            if (mtime < sessionStart) continue
            if (best == null || mtime > best!!.second) best = Triple(slot, mtime, path)
        }
        return best
    }

    private fun frameDir(): String? {
        val base = fm.containerURLForSecurityApplicationGroupIdentifier(IosScreenShare.APP_GROUP)?.path
            ?: return null
        // 目录由扩展创建（谁写谁建）；主 App 只读，路径规则两侧逐字一致。
        return "$base/${IosScreenShare.FRAME_DIR}"
    }

    private fun presentPicker() {
        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
            val picker = platform.ReplayKit.RPSystemBroadcastPickerView.new() ?: return@dispatch_async
            picker.preferredExtension = IosScreenShare.EXTENSION_BUNDLE_ID
            val host = keyWindow()?.rootViewController?.view ?: return@dispatch_async
            // 选择器的实体是它内部那颗 UIControl；程序化触发等价于用户点它
            // （系统面板没有公开的"程序化弹出"入口，这是苹果示例的走法）。
            // 先挂进窗口层级再发车：不在层级里的控件 sendActions 不会被系统受理。
            host.addSubview(picker)
            for (sub in picker.subviews) {
                if (sub is platform.UIKit.UIControl) {
                    sub.sendActionsForControlEvents(platform.UIKit.UIControlEventTouchUpInside)
                    break
                }
            }
        }
    }

    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.windows
            .filterIsInstance<UIView>()
            .filterIsInstance<UIWindow>()
            .firstOrNull { it.isKeyWindow() }

    companion object {
        private const val PREPARE_TIMEOUT_MS = 20_000L
        private const val MAX_BYTES = 512 * 1024

        private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
        private fun nowEpochSeconds(): Double = NSDate().timeIntervalSince1970
    }
}

/** 与 Swift 扩展共享的会合常量 —— 两侧必须逐字一致，集中在这里声明一次。 */
object IosScreenShare {
    const val APP_GROUP = "group.com.kapp.tidings"
    const val FRAME_DIR = "screen-share"
    const val EXTENSION_BUNDLE_ID = "com.kapp.tidings.RelayBroadcastUpload"
    // `app-<扩展 bundleID>://` —— 广播扩展注册的私有 scheme；探测"这台设备装了本 App 的扩展"。
    // 依赖 Info.plist 的 LSApplicationQueriesSchemes 里有 `app-com.kapp.tidings.RelayBroadcastUpload`。
    const val EXTENSION_URL = "app-$EXTENSION_BUNDLE_ID://"
}
