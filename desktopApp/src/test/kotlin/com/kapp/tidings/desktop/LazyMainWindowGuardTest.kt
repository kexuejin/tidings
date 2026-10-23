// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「主窗是懒建的、托盘不挂在窗口上」这条纪律的源码级守卫。
 *
 * ## 为什么必须在源码层守
 *
 * 这两件事都是**组合结构**，没有 Compose UI 测试基建能断言它们（本仓没有
 * `createComposeRule`，与 [com.kapp.tidings.domain.GuardSourceScan] 那几条同一取舍）。
 * 而它们错了的表现**不是崩溃，是"托盘图标不见了"**：
 *
 * - 托盘住进 `Window` 的内容块 ⇒ 主窗一销毁，托盘图标跟着没 ⇒ 用户再也唤不回窗口；
 * - 主窗不懒建 ⇒ 一个从没显示过的窗口常驻占着 15.8 MB 的 `IOSurface`，
 *   而这件事在界面上**完全看不出来**（窗口是隐藏的）。
 *
 * 所以这两条都会静默回归：改的人只看到"编译过了、界面正常"。
 *
 * ## 判据为什么能区分对错（不是"文件里出现过某标识符"）
 *
 * 1. **托盘安装点必须在 `Window` 之外**：断言 `DesktopTray.install` 出现在
 *    `DesktopTrayChrome` 函数体内、且 `DesktopWindowChrome` 的调用参数里没有
 *    `tray =`。把托盘搬回窗口 chrome 就必然要传 `tray = …`，这一条立刻红。
 * 2. **设置窗 / 截图覆盖窗必须在 `if (mainVisible)` 之外**：按平衡花括号取那一段，
 *    断言里面没有那两个调用。把它们搬回主窗内容块，这一条立刻红。
 * 3. **主窗必须由 `if (mainVisible)` 守着**：断言那一段里有 `DesktopMainWindow(`。
 *    把 `if` 去掉（回到"永远存在、只是隐藏"）这一条立刻红。
 *
 * 反向验证过三条都会红（见提交信息）。
 */
class LazyMainWindowGuardTest {

    private val source: String by lazy { sourcePath().readText() }

    private fun sourcePath(): File {
        var dir: File? = File("").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(
                dir,
                "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWindow.kt",
            )
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 DesktopWindow.kt（工作目录 " + File("").absolutePath + "）")
    }

    /** 剥掉 `//` 行注释与 `/* */` 块注释，保留换行与字符串字面量。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("  ")
                        i += 2
                    }
                }
                c == '"' -> {
                    // 字符串整段照抄：否则串里的 `https://…` 会被当成行注释，
                    // 把那一行后面的**活代码**一起抹掉。
                    out.append(c)
                    i++
                    while (i < text.length) {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            out.append(text[i]).append(text[i + 1])
                            i += 2
                            continue
                        }
                        out.append(text[i])
                        if (text[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 取 `marker` 之后第一对平衡圆括号里的内容（marker 必须以 `(` 结尾）。 */
    private fun parenBlockAfter(text: String, marker: String, from: Int = 0): String {
        val at = text.indexOf(marker, from)
        if (at < 0) throw AssertionError("源码里找不到：$marker")
        val open = at + marker.length - 1
        if (text.getOrNull(open) != '(') throw AssertionError("marker 必须以 '(' 结尾：$marker")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("圆括号没有闭合：$marker")
    }

    /** 取 `marker` 之后第一对平衡花括号里的内容。 */
    private fun bracedBlockAfter(text: String, marker: String, from: Int = 0): String {
        val at = text.indexOf(marker, from)
        if (at < 0) throw AssertionError("源码里找不到：$marker")
        val open = text.indexOf('{', at)
        if (open < 0) throw AssertionError("标记后面没有花括号：$marker")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("花括号没有闭合：$marker")
    }

    @Test
    fun `托盘安装在主窗之外`() {
        val code = stripComments(source)

        // 托盘必须是自己的应用级 composable，而不是窗口 chrome 的一个参数。
        val trayChrome = bracedBlockAfter(code, "private fun DesktopTrayChrome(")
        assertTrue(
            trayChrome.contains("DesktopTray.install("),
            "托盘必须由应用级的 DesktopTrayChrome 安装；搬回窗口 chrome 会让主窗销毁时图标消失",
        )

        // 窗口 chrome 不许再接管托盘：它的调用参数里一旦出现 tray = 就是回归。
        val windowChromeCall = parenBlockAfter(code, "DesktopWindowChrome(")
        assertFalse(
            windowChromeCall.contains("tray ="),
            "DesktopWindowChrome 不该再接收托盘模型；托盘住在窗口里 = 关窗就丢图标",
        )
        assertFalse(
            windowChromeCall.contains("onTrayAction"),
            "窗口 chrome 不该再接管托盘点击回调",
        )

        // 主窗内容块里也不许直接装托盘（防止有人绕过 chrome 直接写进去）。
        val mainWindow = bracedBlockAfter(code, "private fun DesktopMainWindow(")
        assertFalse(
            mainWindow.contains("DesktopTray.install("),
            "主窗里不该安装托盘：那等于把进程级能力绑在一张可销毁的窗上",
        )
    }

    @Test
    fun `设置窗与截图窗在主窗之外`() {
        val code = stripComments(source)
        val gate = bracedBlockAfter(code, "if (mainVisible)")

        assertTrue(
            gate.contains("DesktopMainWindow("),
            "主窗必须由 if (mainVisible) 守着 —— 否则它退回\"永远存在、只是隐藏\"",
        )
        assertFalse(
            gate.contains("DesktopSettingsWindow("),
            "设置窗必须在主窗之外：托盘要能不带主窗直接打开它",
        )
        assertFalse(
            gate.contains("DesktopCaptureWindow("),
            "截图覆盖窗必须在主窗之外：截图期间主窗会被 hideMain() 销毁，放里面会一起消失",
        )
        assertFalse(
            gate.contains("DesktopTrayChrome("),
            "托盘必须在主窗之外：主窗不存在时它也必须活着",
        )
    }

    @Test
    fun `应用级组合里三项都在`() {
        val code = stripComments(source)
        val app = bracedBlockAfter(code, "internal fun DesktopApp(")
        assertTrue(app.contains("DesktopTrayChrome("), "应用级必须装托盘")
        assertTrue(app.contains("DesktopSettingsWindow("), "应用级必须组合设置窗")
        assertTrue(app.contains("DesktopCaptureWindow("), "应用级必须组合截图覆盖窗")
    }
}
