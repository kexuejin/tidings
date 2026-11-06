// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import androidx.compose.ui.graphics.toArgb
import java.awt.Color
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import com.kapp.tenon.ui.theme.RelayPrimary
import com.kapp.tenon.ui.theme.RelayError
import com.kapp.tenon.ui.theme.RelayLightColors
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
        val dialog = FileDialog(parent, DesktopCopy.value.dialogChooseFile, FileDialog.LOAD)
        dialog.isMultipleMode = false
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(dir, name).takeIf { it.exists() }
    }

    /** 多文件选择。返回空列表表示用户取消。 */
    fun chooseFiles(parent: Frame?): List<File> {
        val dialog = FileDialog(parent, DesktopCopy.value.dialogChooseFile, FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        val files = dialog.files
        if (!files.isNullOrEmpty()) {
            return files.filter { it.exists() }
        }
        val dir = dialog.directory ?: return emptyList()
        val name = dialog.file ?: return emptyList()
        return listOfNotNull(File(dir, name).takeIf { it.exists() })
    }

    /** 目录选择。AWT `FileDialog` 只能选文件，所以目录走 Swing。 */
    fun chooseDirectory(parent: Frame?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = DesktopCopy.value.dialogChooseDir
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        parent?.let { chooser.dialogTitle = it.title }
        return when (chooser.showDialog(null, DesktopCopy.value.dialogChoose)) {
            JFileChooser.APPROVE_OPTION -> chooser.selectedFile?.absolutePath
            else -> null
        }
    }

    fun filterFor(name: String, extensions: List<String>): FileNameExtensionFilter =
        FileNameExtensionFilter(name, *extensions.toTypedArray())
}

/**
 * 通知走 AWT 系统托盘气泡；没有托盘（部分 Linux/远程会话）时静默跳过，不抛异常。
 *
 * ## 为什么必须打在**常驻图标**上
 *
 * 一条气泡不是「画个框」那么简单：AWT 的实现是往系统托盘里 **add 一个 TrayIcon**，
 * 弹完再定时 **remove**。而 macOS 在移除状态项时走的是 WindowServer 的
 * `RemovePendingStatusItems` → **状态栏全量重建**（`PKGSystemStatusBarFullRebuild` →
 * `RepositionAndClipStatusItemList` → `CGXWindow::set_shape_internal`），
 * 主线程被拖住超过看门狗阈值就是一次系统级卡死。
 *
 * 所以「每条消息弹一次 = 每收一条消息就建/删一次状态项」这条路径必须停掉：
 * 有常驻图标时，气泡直接打在它上面（[DesktopTrayHandle.showMessage]），
 * 整个过程没有 add/remove。
 *
 * 只有**常驻图标还没装好**的那一小段时间（启动瞬间）才退回临时图标 ——
 * 那时一次进程生命周期最多几次，与「每条消息一次」不是同一个量级。
 */
internal object DesktopNotifications {

    fun show(title: String, body: String) {
        // 首选：复用常驻图标，全程不碰状态项的生命周期。
        val mounted = DesktopTray.current()
        if (mounted != null && mounted.showMessage(title, body)) return

        // 退路：托盘还没挂上。建一个一次性图标，弹完摘掉。
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
            // RelayError（#E63946）：0xFFE53935 是 error 的第二份真相，差一个色号 ——
            // 同一次重绘里底板已经走了 RelayPrimary.toArgb()，点没有理由抄字面量。
            g.color = Color(RelayError.toArgb(), true)
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
        /**
         * 编排层最近的日志行（[com.kapp.tenon.orchestration.RelayLog.lines]），旧 → 新。
         *
         * 为什么放在诊断包里：桌面版是双击启动的 `.app`，用户没有控制台，
         * 而「卡上传输失败」「patch merge 失败」这类现场**只在**那里留过痕（ARP-8）。
         */
        recentLogLines: List<String> = emptyList(),
    ): Report? {
        val stamp = Instant.now().toString().replace(':', '-')
        val file = File(directory, "relay-diagnostics-$stamp.txt")
        // 标题与分节名也要翻译：这份文件是给人读的（支持/排障），不是机器格式。
        val diag = DesktopCopy.value.diag
        val body = buildString {
            appendLine(diag.title)
            appendLine(desktopFormat(diag.generatedAt, Instant.now()))
            appendLine("nodeId: $nodeId")
            appendLine("profileRev: $profileRev")
            appendLine()
            appendLine(diag.sectionSettings)
            settingsLines.forEach { appendLine(it) }
            appendLine()
            appendLine(diag.sectionNetwork)
            networkLines.forEach { appendLine(it) }
            appendLine()
            appendLine(diag.sectionPeers)
            peerLines.forEach { appendLine(it) }
            appendLine()
            // 最近日志放最后：前三节是**状态**，这一节是**过程**。用户贴出来时
            // 上面的状态给上下文，下面的日志给"刚刚发生了什么"的时序。
            appendLine(diag.sectionRecentLog)
            if (recentLogLines.isEmpty()) {
                appendLine(diag.none)
            } else {
                recentLogLines.forEach { appendLine(it) }
            }
        }
        val ok = runCatching {
            directory.let { File(it) }.takeIf { !it.exists() }?.mkdirs()
            file.writeText(body)
        }.isSuccess
        return if (ok) Report(file, desktopFormat(diag.exported, file.name)) else null
    }
}