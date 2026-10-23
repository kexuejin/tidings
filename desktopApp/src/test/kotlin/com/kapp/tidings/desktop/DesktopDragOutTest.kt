// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.desktop

import com.kapp.tidings.ui.desktop.*

import androidx.compose.ui.geometry.Offset
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 桌面文件拖出（把已落盘文件拖进 Finder / 资源管理器 / 编辑器）的纯逻辑单测。
 *
 * 只覆盖能离屏验证的部分：路径筛选与去重、拖拽载荷、坐标换算、拖影绘制。
 * 真正的跨进程拖拽由 AWT 承担，必须在有桌面的机器上手工验收。
 */
class DesktopDragOutTest {

    private fun tempDir(): java.io.File =
        Files.createTempDirectory("relay-dragout").toFile().also { it.deleteOnExit() }

    private fun tempFile(dir: java.io.File, name: String): java.io.File =
        java.io.File(dir, name).apply { writeText(name) }

    @Test
    fun 空路径与不存在路径都会被剔除() {
        val dir = tempDir()
        val real = tempFile(dir, "a.txt")
        val missing = java.io.File(dir, "missing.txt")
        // 输入里必须有一个**真实存在**的文件，否则这个断言只是在测「空集合等于空集合」，
        // 「剔除」这件事根本没被验证。
        assertEquals(
            listOf(real.absolutePath),
            DesktopDragOut.resolveFiles(listOf("", "   ", missing.path, real.path))
                .map { it.absolutePath },
        )
    }

    @Test
    fun 目录不算可拖出文件() {
        val dir = tempDir()
        assertTrue(DesktopDragOut.resolveFiles(listOf(dir.path)).isEmpty())
    }

    @Test
    fun 重复路径按绝对路径去重并保序() {
        val dir = tempDir()
        val one = tempFile(dir, "1.txt")
        val two = tempFile(dir, "2.txt")
        val resolved = DesktopDragOut.resolveFiles(
            listOf(one.path, two.path, " ${one.path} ", two.absolutePath),
        )
        assertEquals(listOf(one.absolutePath, two.absolutePath), resolved.map { it.absolutePath })
    }

    @Test
    fun 载荷同时提供文件列表与纯文本路径() {
        val dir = tempDir()
        val one = tempFile(dir, "1.txt")
        val two = tempFile(dir, "2.txt")
        val transferable = DesktopDragOut.transferable(listOf(one, two))

        val flavors = transferable.transferDataFlavors.toList()
        assertTrue(DataFlavor.javaFileListFlavor in flavors)
        assertTrue(DataFlavor.stringFlavor in flavors)

        @Suppress("UNCHECKED_CAST")
        val list = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
        assertEquals(listOf(one.absolutePath, two.absolutePath), list.map { it.absolutePath })

        val text = transferable.getTransferData(DataFlavor.stringFlavor) as String
        assertEquals("${one.absolutePath}\n${two.absolutePath}", text)
    }

    @Test
    fun 不支持的数据格式直接拒绝() {
        val transferable = DesktopDragOut.transferable(emptyList())
        // 只声明了 javaFileListFlavor 与 stringFlavor；imageFlavor 必须走拒绝分支，
        // 不能让上层以为「拖了一张图」却拿到 null。
        val error = assertFailsWith<IllegalArgumentException> {
            transferable.getTransferData(DataFlavor.imageFlavor)
        }
        assertTrue(error.message?.contains("不支持的数据格式") == true)
    }

    @Test
    fun 屏幕坐标要加上窗口位置与卡片起点() {
        val screen = DesktopDragOut.screenOrigin(
            originInWindow = Offset(120f, 40f),
            pointerInWindow = Offset(30f, 15f),
            windowLocationOnScreen = Point(1000, 600),
        )
        assertEquals(Offset(1150f, 655f), screen)
    }

    @Test
    fun 像素坐标取整交给AWT() {
        // AWT 只收整数像素；负半数的取整方向也要钉住，避免拖影落点飘。
        assertEquals(Point(11, 11), DesktopDragOut.toPixels(Offset(10.6f, 11.4f)))
        assertEquals(Point(-2, 4), DesktopDragOut.toPixels(Offset(-2.5f, 4.2f)))
    }

    @Test
    fun 拖影在有图形环境时按文件名绘制() {
        if (DesktopDragOut.isHeadless() || !DesktopDragOut.imageIoUsable()) return
        val image = assertNotNull(DesktopDragOut.dragImageOrNull(listOf("图纸终稿-v4.pdf")))
        assertEquals(160, image.width)
        assertEquals(24, image.height)
        assertNotNull(DesktopDragOut.dragImageOrNull(listOf("a.pdf", "b.pdf", "c.pdf")))
    }

    @Test
    fun 没有可用文件时不挂拖拽手势() {
        // 组合期的守卫：路径全空时修饰符原样返回，不会造一个拖不出东西的手柄。
        assertTrue(DesktopDragOut.resolveFiles(emptyList()).isEmpty())
        assertTrue(DesktopDragOut.resolveFiles(listOf("  ", "/no/such/path")).isEmpty())
    }
}
