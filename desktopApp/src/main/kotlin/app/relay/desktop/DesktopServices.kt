// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import androidx.compose.ui.graphics.toArgb
import java.awt.Color
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import app.relay.ui.theme.RelayPrimary
import app.relay.ui.theme.RelayLightColors
import java.io.File
import java.time.Instant
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 桌面宿主能力：AWT 对话框、系统托盘、通知、落盘与诊断包。
 *
 * 这些都在 AWT 线程上同步执行。Compose Desktop 的 UI 线程不是 AWT 事件派发线程，
 * 所以弹窗可能会比预期晚一拍出现 —— 这是 AWT 的模型限制，不是死锁。
 */
internal object DesktopDialogs {

    /** 单文件选择。返回 null 表示用户取消，不是「没有文件」。 */
    fun chooseFile(parent: Frame?): File? {
        val dialog = FileDialog(parent, "选择要发送的文件", FileDialog.LOAD)
        dialog.isMultipleMode = false
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(dir, name).takeIf { it.exists() }
    }

    /** 目录选择。AWT `FileDialog` 只能选文件，所以目录走 Swing。 */
    fun chooseDirectory(parent: Frame?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择保存目录"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        parent?.let { chooser.dialogTitle = it.title }
        return when (chooser.showDialog(null, "选择")) {
            JFileChooser.APPROVE_OPTION -> chooser.selectedFile?.absolutePath
            else -> null
        }
    }

    fun filterFor(name: String, extensions: List<String>): FileNameExtensionFilter =
        FileNameExtensionFilter(name, *extensions.toTypedArray())
}

/** 通知走 AWT 系统托盘气泡；没有托盘（部分 Linux/远程会话）时静默跳过，不抛异常。 */
internal object DesktopNotifications {

    fun show(title: String, body: String) {
        val tray = runCatching { SystemTray.getSystemTray() }.getOrNull() ?: return
        val image = trayIconImage()
        runCatching {
            val icon = TrayIcon(image, title)
            icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
            tray.add(icon)
            // 气泡是一次性的，托盘里不留死图标。
            javax.swing.Timer(6_000) { runCatching { tray.remove(icon) } }.apply {
                isRepeats = false
                start()
            }
        }
    }

    /** 16×16 实心圆点，支持绘制未读红点角标。 */
    fun trayIconImage(hasUnread: Boolean = false): BufferedImage {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )
        // 托盘图标底板
        g.color = Color(RelayPrimary.toArgb(), true)
        g.fillOval(1, 1, size - 2, size - 2)
        g.color = Color(RelayLightColors.primaryContainer.toArgb(), true)
        g.fillOval(4, 4, size - 8, size - 8)
        if (hasUnread) {
            g.color = Color(0xFFE53935.toInt())
            g.fillOval(size - 6, 0, 6, 6)
        }
        g.dispose()
        return image
    }

    val trayAvailable: Boolean
        get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)
}

/**
 * 诊断包：一份人可读的文本，包含设置、网络、节点与最近错误。
 *
 * 不是二进制 zip —— 出问题时用户要把它发出来，文本能直接贴进聊天。
 */
internal object DesktopDiagnostics {

    data class Report(
        val file: File,
        val summary: String,
    )

    fun write(
        directory: String,
        nodeId: String,
        profileRev: Int,
        settingsLines: List<String>,
        networkLines: List<String>,
        peerLines: List<String>,
    ): Report? {
        val stamp = Instant.now().toString().replace(':', '-')
        val file = File(directory, "relay-diagnostics-$stamp.txt")
        val body = buildString {
            appendLine("捎话 Tidings 诊断包")
            appendLine("生成时间: ${Instant.now()}")
            appendLine("nodeId: $nodeId")
            appendLine("profileRev: $profileRev")
            appendLine()
            appendLine("== 设置 ==")
            settingsLines.forEach { appendLine(it) }
            appendLine()
            appendLine("== 网络 ==")
            networkLines.forEach { appendLine(it) }
            appendLine()
            appendLine("== 节点 ==")
            peerLines.forEach { appendLine(it) }
        }
        val ok = runCatching {
            directory.let { File(it) }.takeIf { !it.exists() }?.mkdirs()
            file.writeText(body)
        }.isSuccess
        return if (ok) Report(file, "已导出 ${file.name}") else null
    }
}