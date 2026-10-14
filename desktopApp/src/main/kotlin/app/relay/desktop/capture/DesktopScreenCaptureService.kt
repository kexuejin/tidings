// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.relay.platform.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage

class DesktopScreenCaptureService(
    private val scope: CoroutineScope,
    private val cacheDirProvider: () -> String,
    private val onCaptureWindowToggle: (Boolean) -> Unit,
) : ScreenCaptureService {

    private var activeCaptureImage: BufferedImage? = null
    private var completionCallback: ((String?) -> Unit)? = null

    /**
     * 待确认的截图（`null` = 没有正在进行的截图），**必须可观察**。
     *
     * 它是在 Composition 里被读的（`DesktopWindow.kt` 的
     * `val capture = host.screenCaptureService?.captureState` 决定要不要开那颗
     * 全屏置顶标注窗），所以普通 `var` 不行：值写进去了，读的一方不会重组，
     * 覆盖窗就永远不出现 —— 而调用方（`RelayController`）与截图服务都以为
     * 自己成功了，用户这边看到的是"点了截图，什么都没发生"。
     */
    var captureState: CaptureState? by mutableStateOf(null)
        private set

    data class CaptureState(
        val image: BufferedImage,
        val bounds: Rectangle,
        val onConfirm: (BufferedImage) -> Unit,
        val onCopy: (BufferedImage) -> Unit,
        val onCancel: () -> Unit,
    )

    override fun isSupported(): Boolean = runCatching {
        !GraphicsEnvironment.isHeadless()
    }.getOrDefault(false)

    override fun startCapture(onComplete: (filePath: String?) -> Unit) {
        if (!isSupported()) {
            onComplete(null)
            return
        }
        completionCallback = onComplete

        scope.launch(Dispatchers.Main) {
            // 隐藏主窗口防穿帮
            onCaptureWindowToggle(false)
            delay(180) // 稍作等待以便操作系统完全清退窗口绘制

            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val screenBounds = ge.defaultScreenDevice.defaultConfiguration.bounds
            val robot = Robot()
            val fullScreen = robot.createScreenCapture(screenBounds)

            captureState = CaptureState(
                image = fullScreen,
                bounds = screenBounds,
                onConfirm = { edited ->
                    val path = CaptureExportHelper.saveToTempFile(edited, cacheDirProvider())
                    closeCapture()
                    completionCallback?.invoke(path)
                    completionCallback = null
                },
                onCopy = { edited ->
                    CaptureExportHelper.copyToClipboard(edited)
                    closeCapture()
                    completionCallback?.invoke(null)
                    completionCallback = null
                },
                onCancel = {
                    closeCapture()
                    completionCallback?.invoke(null)
                    completionCallback = null
                }
            )
        }
    }

    private fun closeCapture() {
        captureState = null
        onCaptureWindowToggle(true)
    }
}
