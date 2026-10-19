// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android QR 扫描器：CameraX 预览 + ML Kit 条码检测。
 *
 * 使用方式：在 Compose 里放 [QrScannerPreview]，然后调用 [scan] 等结果。
 * [scan] 在拿到一个码时返回它的文本；返回 `null` 表示"这次没拿到结果"。
 * **取消不是一个返回值**：调用方的协程被取消时，这里以 `CancellationException` 结束
 * （契约见 `QrScanner`），不 `resume(null)`。
 *
 * 三条此前踩过的坑，都写在这里免得后人重踩：
 *
 * 1. **预览必须已经组合**。检测发生在预览那条 `ImageAnalysis` 的分析器里，
 *    没有预览就没有帧、没有帧就永远扫不到 —— 页面在 [scan] 挂起期间不能让预览消失。
 * 2. **ML Kit 客户端是进程级共享的**，`BarcodeScanning.getClient()` 每次拿到的都是同一个。
 *    在协程取消或页面销毁时 `close()` 它，会让**后续所有**扫描静默失效（第二张码永远扫不出来）。
 *    所以这里一次都不关：它随进程结束释放。
 * 3. **相机绑定失败要说出来**。上一版把异常静默吞掉，症状是"点了扫码什么都没发生"；
 *    现在失败会被记住，[scan] 直接以这个真实原因抛出去，由调用方展示。
 * 4. **授权之前不要绑**。预览是 Compose 决定什么时候组合的，与授权无关：首次进扫码页时
 *    权限框还盖在上面，那时 `bindToLifecycle` 必然失败。把那次失败记成"这台设备给不出画面"
 *    是错的（真实原因只是"还没问过"），用户点完「允许」还得再点一次「再试一次」才扫得上。
 *    所以 [QrScannerPreview] 只在**已经授权**时才绑，[scan] 开头再补一次绑定（授权发生在
 *    预览组合之后时，那次组合不会自己重来）。
 */
class AndroidQrScanner(
    private val context: Context,
) : QrScanner {

    private val scanner = BarcodeScanning.getClient()

    // 分析器线程自己命名并设成守护线程：这个执行器随进程活到底，不关（见类注释第 2 条
    // 同一条理由 —— 这些资源是进程级共享的）。守护属性保证它不会成为进程退出的阻碍。
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "relay-qr-analyzer").apply { isDaemon = true }
    }
    private val isScanning = AtomicBoolean(false)

    @Volatile
    private var pending: CancellableContinuation<String?>? = null

    /** 上一次相机绑定的失败原因；[scan] 一进来就把它抛出去，而不是假装在等。 */
    @Volatile
    private var bindFailure: Throwable? = null

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    /** 已经绑过相机的那个预览视图；同一个视图不重复绑（见 [QrScannerPreview] 的 update）。 */
    @Volatile
    private var boundPreview: PreviewView? = null

    /** 最近组合过的那块预览视图与它的生命周期宿主；[scan] 用它补一次绑定（类注释第 4 条）。 */
    @Volatile
    private var lastPreview: PreviewView? = null

    @Volatile
    private var lastLifecycleOwner: LifecycleOwner? = null

    override suspend fun scan(): String? = suspendCancellableCoroutine { cont ->
        bindFailure?.let { failure ->
            cont.resumeWithException(failure)
            return@suspendCancellableCoroutine
        }
        // 授权可能是在预览已经组合之后才拿到的（权限框盖在取景框上）：那次组合没有绑成功，
        // 也不会自己重来 —— 这里补一次，否则 scan() 会一直等一个永远不会跑起来的检测器。
        bindPendingPreview()
        isScanning.set(true)
        pending = cont
        cont.invokeOnCancellation {
            isScanning.set(false)
            pending = null
            // 刻意不 close()：见类注释第 2 条。也不 resume(null)：那时续体已经取消，
            // resume 是空操作，取消会以 CancellationException 正常传出去（契约见 QrScanner）。
        }
    }

    // 这里曾有 `injectScanResultForTesting`：把内容直接喂给等着的 [scan] 续体，等于"假装相机扫到了"。
    // 它是一条**绕过外部配对二次确认**的现成旁路（task-54 裁决删除），留着的唯一后果就是给下一个人
    // 一个 footgun。将来若再需要测试钩子：必须走 MainActivity 的外部配对闸门（登记 → 用户确认 →
    // 才落盘），不许再形成第二条直达配对的分支。

    /** Compose 组件：显示相机预览，并在 [scan] 挂起期间把检测到的码交给它。 */
    @Composable
    fun QrScannerPreview(modifier: Modifier = Modifier) {
        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(Unit) {
            onDispose {
                // 页面走了必须解绑：`bindToLifecycle` 挂的是 Activity 的生命周期，
                // 不主动解绑的话用户关掉扫码页之后相机还开着（隐私指示灯 + 耗电）。
                cameraProvider?.unbindAll()
                cameraProvider = null
                boundPreview = null
                lastPreview = null
                lastLifecycleOwner = null
                isScanning.set(false)
                // 万一还有人在等（页面被别的路径拆掉时），按契约给 null 结束等待，
                // 而不是让它挂到进程结束。
                pending?.let { waiting ->
                    pending = null
                    waiting.resume(null)
                }
            }
        }

        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }.also { previewView ->
                    lastPreview = previewView
                    lastLifecycleOwner = lifecycleOwner
                }
            },
            update = { previewView ->
                // `update` 每次重组都会跑，而它里面是 `unbindAll()` + 重新绑定 ——
                // 不挡住的话，扫码页每重组一次（比如"正在启动相机…"换成提示语）
                // 相机就要重开一遍，正在跑的识别也被拆掉。只在视图换了之后绑：
                // 点「再试一次」时 ScanScreen 用 `key(attempt)` 重建预览，那时才需要重绑。
                //
                // 还要先看权限（类注释第 4 条）：没授权时绑定必然失败，而那次失败会被
                // 记成"这台设备给不出画面"，把真实原因（还没问过）盖掉。授权后这次重组
                // 会再来一遍，那时才绑。
                if (boundPreview !== previewView && hasPermission()) {
                    boundPreview = previewView
                    startCamera(previewView, lifecycleOwner)
                }
            },
        )
    }

    /**
     * 授权发生在预览组合之后时补一次绑定。没组合过预览就什么都不做 ——
     * 检测跑在预览那条分析器里，没有预览时 [scan] 本来也扫不到（类注释第 1 条）。
     */
    private fun bindPendingPreview() {
        val previewView = lastPreview ?: return
        val lifecycleOwner = lastLifecycleOwner ?: return
        if (boundPreview === previewView) return
        if (!hasPermission()) return
        boundPreview = previewView
        startCamera(previewView, lifecycleOwner)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        bindFailure = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = try {
                cameraProviderFuture.get()
            } catch (t: Throwable) {
                failScan(t)
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(executor) { imageProxy ->
                        if (!isScanning.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees,
                            )
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    val qr = barcodes.firstOrNull {
                                        it.valueType == Barcode.TYPE_URL || it.rawValue != null
                                    }
                                    qr?.rawValue?.let { value ->
                                        if (isScanning.compareAndSet(true, false)) {
                                            val waiting = pending
                                            pending = null
                                            waiting?.resume(value)
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (t: Throwable) {
                // 权限没给、相机被别的应用占着、设备没有后置相机……都是真实原因，
                // 必须让它走到界面上，而不是留在日志里。
                failScan(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun failScan(t: Throwable) {
        bindFailure = t
        isScanning.set(false)
        val waiting = pending
        pending = null
        waiting?.resumeWithException(t)
    }

    /**
     * 从一张已有的图片里读码。
     *
     * 走的是同一个 ML Kit 客户端（[scanner]），只是换成 `InputImage.fromFilePath`：
     * 不碰 CameraX、不碰预览，因此**不需要相机权限**，也不需要页面先把取景框组合出来。
     *
     * 两条与 [scan] 不同的地方：
     * - 这里**可以**并发调（进程级客户端一次处理一张图），但仍然只认第一个码 —— 一张图里
     *   印着两个码时选哪个都是猜，不如返回第一个（ML Kit 的返回顺序是屏幕位置序）。
     * - 读不出来返回 `null`，**不抛**：图里没有码是用户的日常（拿错图了），不是故障。
     *   只有 ML Kit 自己失败（文件读不了、解码器挂了）才抛，由调用方展示原因。
     *
     * 不复用 [pending]/[isScanning]：那套状态属于"预览正在扫"这件事，两者同时发生是合法的
     * （用户可以在取景框开着的时候去导入一张图）。
     */
    override suspend fun scanImage(path: String): String? = suspendCancellableCoroutine { cont ->
        val image = try {
            InputImage.fromFilePath(context, Uri.fromFile(File(path)))
        } catch (t: Throwable) {
            cont.resumeWithException(t)
            return@suspendCancellableCoroutine
        }
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                cont.resume(barcodes.firstOrNull()?.rawValue)
            }
            .addOnFailureListener { t ->
                cont.resumeWithException(t)
            }
    }
}
