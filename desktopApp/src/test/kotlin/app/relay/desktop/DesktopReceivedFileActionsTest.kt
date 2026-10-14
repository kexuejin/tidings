// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DesktopReceivedFileActions] 的行为单测。
 *
 * 全部走 [DesktopFileHost] 的假实现：这些用例要回答的是"点了按钮之后到底请求了什么"，
 * 而不是"这台机器上 Finder 有没有弹出来" —— 后者没法断言，而且会在 CI 上真的开窗口。
 */
class DesktopReceivedFileActionsTest {

    /** 记录被请求了哪些动作的假宿主；`failOpen` 用来验"宿主抛异常也不许穿到界面层"。 */
    private class FakeHost(
        private val openable: Boolean = true,
        private val revealable: Boolean = true,
        private val browsable: Boolean = true,
        private val failOpen: Boolean = false,
    ) : DesktopFileHost {
        val opened = mutableListOf<File>()
        val revealed = mutableListOf<File>()
        val browsed = mutableListOf<File>()

        override fun canOpen(): Boolean = openable

        override fun open(file: File) {
            if (failOpen) throw IllegalStateException("假宿主：打开失败")
            opened += file
        }

        override fun canRevealFile(): Boolean = revealable

        override fun revealFile(file: File) {
            revealed += file
        }

        override fun canBrowseDirectory(): Boolean = browsable

        override fun browseDirectory(directory: File) {
            browsed += directory
        }
    }

    private fun tempFile(name: String = "收到的东西.txt"): File =
        Files.createTempFile("relay-received", "-$name").toFile().also { it.deleteOnExit() }

    // ------------------------------------------------------------ 文件不在了

    @Test
    fun 文件不在了就什么都不请求() {
        val host = FakeHost()
        val actions = DesktopReceivedFileActions(host)
        val missing = File("/definitely/not/here/relay-nope.txt")

        actions.open(missing.path)
        actions.share(missing.path)

        assertEquals(emptyList(), host.opened)
        assertEquals(emptyList(), host.revealed)
        assertEquals(emptyList(), host.browsed)
    }

    // ------------------------------------------------------------ 打开

    @Test
    fun 打开把文件交给系统默认应用() {
        val host = FakeHost()
        val file = tempFile()

        DesktopReceivedFileActions(host).open(file.path)

        assertEquals(listOf(file), host.opened)
    }

    @Test
    fun 宿主说不支持打开就不调用它() {
        val host = FakeHost(openable = false)
        val file = tempFile()

        DesktopReceivedFileActions(host).open(file.path)

        assertEquals(emptyList(), host.opened)
    }

    @Test
    fun 宿主抛异常也不穿到调用方() {
        val host = FakeHost(failOpen = true)
        val file = tempFile()

        // 契约：用户点一下按钮不该把应用掀掉。这里断言"没抛出来"。
        DesktopReceivedFileActions(host).open(file.path)

        assertEquals(emptyList(), host.opened)
    }

    // ------------------------------------------------------------ 分享

    @Test
    fun 分享优先在文件管理器里定位文件() {
        val host = FakeHost()
        val file = tempFile()

        DesktopReceivedFileActions(host).share(file.path)

        assertEquals(listOf(file), host.revealed)
        assertEquals(emptyList(), host.browsed, "能定位到文件本身时不该退到打开整个目录")
    }

    @Test
    fun 不能定位文件时退到打开所在目录() {
        val host = FakeHost(revealable = false)
        val file = tempFile()

        DesktopReceivedFileActions(host).share(file.path)

        assertEquals(emptyList(), host.revealed)
        assertEquals(listOf(file.parentFile), host.browsed)
    }

    @Test
    fun 两条路都不支持时什么都不做也不抛异常() {
        val host = FakeHost(revealable = false, browsable = false)
        val file = tempFile()

        DesktopReceivedFileActions(host).share(file.path)

        assertEquals(emptyList(), host.revealed)
        assertEquals(emptyList(), host.browsed)
    }

    // ------------------------------------------------------------ 没有桌面环境

    @Test
    fun 没有桌面环境时不抛异常也不请求任何动作() {
        val file = tempFile()
        // host = null 表示这台机器是 headless：是设备事实，不是"这次失败"。
        val actions = DesktopReceivedFileActions(host = null)

        actions.open(file.path)
        actions.share(file.path)

        assertTrue(file.isFile, "只是没有出口，文件本身不该被动过")
    }

    @Test
    fun 没有桌面环境时连不存在的文件也不抛异常() {
        val actions = DesktopReceivedFileActions(host = null)

        // 两条路径都不许抛：headless 运行时这个能力就是没有，界面据此不画按钮。
        actions.open("/definitely/not/here/relay-nope.txt")
        actions.share("/definitely/not/here/relay-nope.txt")
    }
}
