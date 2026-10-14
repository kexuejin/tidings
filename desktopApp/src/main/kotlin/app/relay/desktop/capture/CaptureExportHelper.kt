// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop.capture

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 截图输出助手：写入系统剪贴板与保存至临时目录。
 */
object CaptureExportHelper {

    fun copyToClipboard(image: BufferedImage): Boolean = runCatching {
        val transferable = TransferableImage(image)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        true
    }.getOrDefault(false)

    fun saveToTempFile(image: BufferedImage, dir: String): String? = runCatching {
        val targetDir = File(dir, "screenshots").apply { if (!exists()) mkdirs() }
        val file = File(targetDir, "screenshot_${System.currentTimeMillis()}.png")
        ImageIO.write(image, "PNG", file)
        file.absolutePath
    }.getOrNull()

    private class TransferableImage(private val image: BufferedImage) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
