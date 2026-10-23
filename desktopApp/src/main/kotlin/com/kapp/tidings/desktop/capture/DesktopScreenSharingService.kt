// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop.capture

import com.kapp.tidings.core.protocol.Limits
import com.kapp.tidings.platform.ScreenSharingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import kotlin.math.min

/**
 * 桌面端屏幕共享服务实现。
 *
 * 基于 AWT Robot 采集主屏幕画面，按 [Limits.SCREEN_MAX_EDGE] 缩放并输出渐进/压缩 JPEG 字节流（上限 [Limits.SCREEN_MAX_FRAME_BYTES]）。
 */
class DesktopScreenSharingService : ScreenSharingService {

    private var robot: Robot? = null
    private var screenBounds: Rectangle? = null

    override fun isShareSupported(): Boolean = runCatching {
        !GraphicsEnvironment.isHeadless()
    }.getOrDefault(false)

    override suspend fun prepareCapture(): Boolean = withContext(Dispatchers.IO) {
        if (!isShareSupported()) return@withContext false
        runCatching {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            screenBounds = ge.defaultScreenDevice.defaultConfiguration.bounds
            robot = Robot()
            true
        }.getOrDefault(false)
    }

    override suspend fun captureFrame(seq: Long): ByteArray? = withContext(Dispatchers.IO) {
        val r = robot ?: return@withContext null
        val bounds = screenBounds ?: return@withContext null

        runCatching {
            val raw = r.createScreenCapture(bounds)
            compressToJpegBytes(raw)
        }.getOrNull()
    }

    override fun stopCapture() {
        robot = null
        screenBounds = null
    }

    private fun compressToJpegBytes(source: BufferedImage): ByteArray? {
        val maxEdge = Limits.SCREEN_MAX_EDGE
        val w = source.width
        val h = source.height

        val scaledImage = if (w > maxEdge || h > maxEdge) {
            val scale = min(maxEdge.toDouble() / w, maxEdge.toDouble() / h)
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)
            val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.drawImage(source.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH), 0, 0, null)
            g.dispose()
            scaled
        } else if (source.type != BufferedImage.TYPE_INT_RGB) {
            val converted = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = converted.createGraphics()
            g.drawImage(source, 0, 0, null)
            g.dispose()
            converted
        } else {
            source
        }

        val writer: ImageWriter = ImageIO.getImageWritersByFormatName("jpg").next() ?: return null
        val params: ImageWriteParam = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.65f // 标准适中画质
        }

        val baos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(baos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(scaledImage, null, null), params)
        }
        writer.dispose()

        val bytes = baos.toByteArray()
        return if (bytes.size <= Limits.SCREEN_MAX_FRAME_BYTES) {
            bytes
        } else {
            // 二次降质兜底
            val fallbackBaos = ByteArrayOutputStream()
            val fallbackWriter: ImageWriter = ImageIO.getImageWritersByFormatName("jpg").next() ?: return null
            val fallbackParams: ImageWriteParam = fallbackWriter.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.40f
            }
            ImageIO.createImageOutputStream(fallbackBaos).use { ios ->
                fallbackWriter.output = ios
                fallbackWriter.write(null, IIOImage(scaledImage, null, null), fallbackParams)
            }
            fallbackWriter.dispose()
            fallbackBaos.toByteArray().takeIf { it.size <= Limits.SCREEN_MAX_FRAME_BYTES }
        }
    }
}
