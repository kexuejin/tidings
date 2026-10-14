// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

@file:OptIn(ExperimentalForeignApi::class)

package app.relay.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/**
 * iOS QR 扫描器：`AVCaptureSession` + `AVCaptureMetadataOutput`。
 *
 * 上一版是**空壳**：`captureSession` 从不创建，`scan()` 只是登记一个回调，而没有任何
 * 东西会去调 `handleScannedCode` —— 于是它在真机上会**永久挂起**（界面上就是"点了扫码
 * 一直转圈，什么都不发生"）。`Info.plist` 那时也没有 `NSCameraUsageDescription`，
 * 真发请求会被系统直接杀进程。
 *
 * 现在三个环节都在：会话由 [QrScannerPreview] 建（预览层就是它的数据源）、
 * 元数据回调交给一个真正实现了 `AVCaptureMetadataOutputObjectsDelegateProtocol` 的
 * `NSObject` 子类、扫到的字符串回到等在 [scan] 里的那个协程。
 *
 * 与 Android 侧同一条规矩：**取景框必须已经组合**。没有预览层就没有会话，没有会话就
 * 永远扫不到 —— 所以扫码页是页面级的常驻取景框，而不是弹层里的一块空框。
 *
 * `startRunning()` 会阻塞，必须丢到后台队列（在主线程上跑它会让界面卡住一拍）。
 *
 * 还有一条与 Android 同源的坑：**授权之前组合的那块预览拿不到会话**
 * （`deviceInputWithDevice` 那时返回 nil），而 `UIKitView` 的 factory 只跑一次、
 * 没有 `update` 可以用来重试 —— 所以那样的视图先登记进 [waitingForSession]，
 * 会话建起来时再把预览层补挂上去。不补的话症状是"能扫到码，但取景框一直黑"。
 */
class IosQrScanner : QrScanner {

    private var captureSession: AVCaptureSession? = null

    /** 输出对 delegate 是**弱引用**，所以这里必须自己拿住它。 */
    private val metadataDelegate = QrMetadataDelegate()

    /** 等在 [scan] 里的那个人；扫到一次就清掉，避免同一条码重复回调。 */
    private var onCode: ((String) -> Unit)? = null

    /**
     * 已经组合、但组合时还拿不到会话的预览视图。
     *
     * 首次进扫码页时权限框还盖在上面，`deviceInputWithDevice` 那时给的是 nil ——
     * 视图先记在这里，[ensureSession] 建起会话（授权之后）时把预览层补挂上去。
     * 少了这一步，取景框会一直黑着：码其实扫得到，但用户看不见自己在对着哪里。
     */
    private val waitingForSession = mutableListOf<CameraPreviewView>()

    /**
     * 还活着的预览视图数。会话由**最后一个**离开的预览关，而不是"谁建的谁关"：
     * `key(attempt)` 换预览时新旧会短暂并存，先退出的那个若把会话关掉，
     * 正在显示的新预览就黑着、扫描也停了。
     *
     * 这三个字段都只在主线程上读写（Compose 组合与 [scan] 的调用点都是主线程，
     * 元数据回调也丢在主队列上），所以不需要额外同步。
     */
    private var liveViews = 0

    override suspend fun scan(): String? = suspendCancellableCoroutine { cont ->
        val session = ensureSession()
        if (session == null) {
            // 不带文案：用户看到的那句话由控制器挑资源键（`banner_camera_unavailable`）。
            cont.resumeWithException(CameraUnavailableException())
            return@suspendCancellableCoroutine
        }
        onCode = { code ->
            onCode = null
            if (cont.isActive) cont.resume(code)
        }
        cont.invokeOnCancellation {
            onCode = null
            // 不 resume(null)：那时续体已经取消，resume 是空操作。取消会以
            // CancellationException 正常传出去（契约见 QrScanner），控制器据此不留横幅。
            // 会话留给预览的 onRelease 关 —— 预览还在，用户可能只是取消了这一次尝试。
        }
    }

    /**
     * 从一张已有的图片里读码。
     *
     * 用 `CIDetector`（CoreImage 自带，系统级，不引第三方库，**也不需要相机权限**）。
     * 它与 `ImageDecoding.ios.kt` 走 skiko 解图是两件事：那边要的是"能画出来的位图"，
     * 这边要的是"图里有没有码"，`CIDetector` 直接吃文件。
     *
     * `CIDetector` 无状态、可在任意线程调用（与 `NSNetService` 不同 —— 那个挂 run loop，
     * 见 `IosMdnsAdvertiser` 的教训），所以这里**故意不**搬主队列：解一张 4000×3000 的图
     * 是百毫秒级的，放主队列会卡住界面。
     *
     * 读不出来返回 `null`，不抛：图里没有码是日常（拿错图了）。
     */
    override suspend fun scanImage(path: String): String? {
        val image = CIImage.imageWithContentsOfURL(NSURL.fileURLWithPath(path)) ?: return null
        val detector = CIDetector.detectorOfType(
            CIDetectorTypeQRCode,
            null,
            mapOf(CIDetectorAccuracy to CIDetectorAccuracyHigh),
        ) ?: return null
        // 绑定给的是非空 List（认不出东西时是空列表，不是 nil），所以这里不判空。
        val features = detector.featuresInImage(image)
        return features.filterIsInstance<CIQRCodeFeature>().firstOrNull()?.messageString
    }

    /** 相机预览。**必须**放在扫码页里组合着 —— 它就是 [scan] 能收到结果的前提。 */
    @Composable
    fun QrScannerPreview(modifier: Modifier = Modifier) {
        // 这块视图自己。`onRelease` 的回调签名在不同 CMP 版本间变过（带不带视图参数），
        // 用 remember 的槽自己拿住比依赖回调参数稳。
        val self = remember { arrayOfNulls<CameraPreviewView>(1) }
        UIKitView(
            factory = {
                val view = CameraPreviewView()
                self[0] = view
                liveViews += 1
                val session = ensureSession()
                if (session != null) {
                    view.attachPreviewLayer(session)
                } else {
                    waitingForSession += view
                }
                view
            },
            modifier = modifier,
            // 页面走了就关会话：不关的话相机一直开着（隐私指示灯 + 耗电）。
            onRelease = {
                liveViews -= 1
                self[0]?.let { waitingForSession -= it }
                if (liveViews <= 0) {
                    liveViews = 0
                    waitingForSession.clear()
                    stopSession()
                }
            },
        )
    }

    /**
     * 建会话（幂等）。预览与 [scan] 都会调它：谁先到谁建，另一个直接用。
     *
     * 返回 null = 这台设备给不出摄像头输入，此时**不要**假装在等 —— [scan] 会带着这个
     * 真实原因失败，由扫码页显示出来。
     */
    private fun ensureSession(): AVCaptureSession? {
        captureSession?.let { return it }
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return null
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null) ?: return null

        val session = AVCaptureSession()
        session.beginConfiguration()
        if (session.canAddInput(input)) session.addInput(input)
        val output = AVCaptureMetadataOutput()
        if (session.canAddOutput(output)) {
            session.addOutput(output)
            // 回调丢主队列：扫到的字符串要交给一个在 Compose 主线程上挂起的协程。
            output.setMetadataObjectsDelegate(metadataDelegate, queue = dispatch_get_main_queue())
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }
        session.commitConfiguration()

        metadataDelegate.onCode = { code ->
            onCode?.invoke(code)
        }
        captureSession = session
        // 组合时还没授权、因而没能挂上预览层的那些视图，现在补挂（见 waitingForSession）。
        if (waitingForSession.isNotEmpty()) {
            val waiting = waitingForSession.toList()
            waitingForSession.clear()
            waiting.forEach { it.attachPreviewLayer(session) }
        }
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0uL)) {
            session.startRunning()
        }
        return session
    }

    private fun stopSession() {
        val session = captureSession ?: return
        captureSession = null
        onCode = null
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0uL)) {
            if (session.running) session.stopRunning()
        }
    }
}

/** `DISPATCH_QUEUE_PRIORITY_DEFAULT` 在 dispatch.h 里就是 0，但这里写明来历。 */
private const val DISPATCH_QUEUE_PRIORITY_DEFAULT = 0L

/**
 * 装得下 `AVCaptureVideoPreviewLayer` 的 `UIView`。
 *
 * 预览层是**子层**，不是这个视图的 backing layer —— 子层的 frame 不会跟着视图自己变，
 * 所以每次布局都要把它的 frame 拉回 `bounds`。少了这一步，横竖屏一转（或者键盘一弹）
 * 预览就只剩一小块、或者被裁掉一半。
 */
private class CameraPreviewView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {

    private var previewLayer: AVCaptureVideoPreviewLayer? = null

    /**
     * 把会话的画面挂到这个视图上。幂等：已经挂过的视图不再挂第二层
     * （[IosQrScanner.ensureSession] 补挂时可能碰到刚挂好的那一块）。
     */
    fun attachPreviewLayer(session: AVCaptureSession) {
        if (previewLayer != null) return
        val layer = AVCaptureVideoPreviewLayer(session = session)
        layer.videoGravity = AVLayerVideoGravityResizeAspectFill
        previewLayer = layer
        layer.setFrame(bounds)
        this.layer.addSublayer(layer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.setFrame(bounds)
    }
}

/**
 * 元数据回调。`AVCaptureMetadataOutputObjectsDelegateProtocol` 是一个 ObjC 协议，
 * Kotlin 侧只能通过 `NSObject` 子类实现它 —— 函数式接口在这里不管用。
 */
private class QrMetadataDelegate : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    var onCode: ((String) -> Unit)? = null

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val code = (didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject)
            ?.stringValue
            ?: return
        onCode?.invoke(code)
    }
}
