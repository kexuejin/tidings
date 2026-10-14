// SPDX-License-Identifier: GPL-3.0-only
package app.relay.desktop

import app.relay.ui.desktop.*

import app.relay.core.protocol.DEFAULT_TIMINGS
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 省电后台档 + 开机自启的纯逻辑单测。
 *
 * 自启的落盘路径全部走**临时 home**，不碰真实主目录；Windows 那条走 `reg.exe`，
 * 单测只验证命令文本，真注册需要 Windows 机器手工验收。
 */
class DesktopBackgroundTest {

    private fun tempHome(): java.io.File =
        Files.createTempDirectory("relay-home").toFile().also { it.deleteOnExit() }

    // ------------------------------------------------------------ 省电档

    @Test
    fun 省电档只放慢空闲周期() {
        val slow = DesktopPowerSaver.timings()
        assertTrue(slow.presenceInterval > DEFAULT_TIMINGS.presenceInterval)
        assertTrue(slow.sweepInterval > DEFAULT_TIMINGS.sweepInterval)
        assertTrue(slow.profileProbeInterval > DEFAULT_TIMINGS.profileProbeInterval)
        assertTrue(slow.offlineAfter > DEFAULT_TIMINGS.offlineAfter)
    }

    @Test
    fun 省电档不碰真实传输的时间参数() {
        val slow = DesktopPowerSaver.timings()
        // 这些决定一次真实发送的快慢与正确性，放慢它们只会让用户等更久 / 出错。
        assertEquals(DEFAULT_TIMINGS.ackRetrySchedule, slow.ackRetrySchedule)
        assertEquals(DEFAULT_TIMINGS.tcpAckTimeout, slow.tcpAckTimeout)
        assertEquals(DEFAULT_TIMINGS.directedReplyInterval, slow.directedReplyInterval)
        assertEquals(DEFAULT_TIMINGS.entryReplyJitterBase, slow.entryReplyJitterBase)
        assertEquals(DEFAULT_TIMINGS.entryReplyJitterMax, slow.entryReplyJitterMax)
        assertEquals(DEFAULT_TIMINGS.queueTtl, slow.queueTtl)
        assertEquals(DEFAULT_TIMINGS.dedupTtl, slow.dedupTtl)
        assertEquals(DEFAULT_TIMINGS.recallWindow, slow.recallWindow)
    }

    @Test
    fun 两档都保持离线判定远大于心跳周期() {
        // offlineAfter 必须显著大于 presenceInterval，否则节点会在列表里反复闪进闪出。
        for (timings in listOf(DEFAULT_TIMINGS, DesktopPowerSaver.timings())) {
            assertTrue(
                timings.offlineAfter >= timings.presenceInterval * 2,
                "offlineAfter=${timings.offlineAfter} presenceInterval=${timings.presenceInterval}",
            )
        }
    }

    // ------------------------------------------------------------ 平台判定

    @Test
    fun 系统名映射() {
        assertEquals(DesktopOs.Macos, desktopOs("Mac OS X"))
        assertEquals(DesktopOs.Macos, desktopOs("macOS"))
        assertEquals(DesktopOs.Windows, desktopOs("Windows 11"))
        assertEquals(DesktopOs.Linux, desktopOs("Linux"))
        assertEquals(DesktopOs.Other, desktopOs("FreeBSD"))
        assertEquals(DesktopOs.Other, desktopOs(""))
        assertTrue(DesktopLoginItem.isSupported(DesktopOs.Macos))
        assertTrue(DesktopLoginItem.isSupported(DesktopOs.Windows))
        assertTrue(DesktopLoginItem.isSupported(DesktopOs.Linux))
        assertFalse(DesktopLoginItem.isSupported(DesktopOs.Other))
    }

    @Test
    fun 自启注册文件位置() {
        assertEquals(
            "/home/me/Library/LaunchAgents/app.relay.desktop.plist",
            DesktopLoginItem.entryPath(DesktopOs.Macos, "/home/me"),
        )
        assertEquals(
            "/home/me/.config/autostart/relay-desktop.desktop",
            DesktopLoginItem.entryPath(DesktopOs.Linux, "/home/me"),
        )
        // Windows 走注册表，没有文件。
        assertEquals(null, DesktopLoginItem.entryPath(DesktopOs.Windows, "/home/me"))
        assertEquals(null, DesktopLoginItem.entryPath(DesktopOs.Other, "/home/me"))
    }

    // ------------------------------------------------------------ 文本生成

    @Test
    fun XML转义覆盖plist会用到的字符() {
        // 一个没转义的 & 会让整个 plist 解析失败，而 macOS 对坏 plist 是静默忽略 ——
        // 表现为「开关开了但开机不自启」，极难查。
        assertEquals("a&amp;b", DesktopLoginItem.xmlEscape("a&b"))
        assertEquals("&lt;x&gt;", DesktopLoginItem.xmlEscape("<x>"))
        assertEquals("a&quot;b&apos;c", DesktopLoginItem.xmlEscape("a\"b'c"))
        // & 必须最先替换，否则会把后面生成的实体再转一遍（&amp;lt;）。
        assertEquals("&amp;lt;", DesktopLoginItem.xmlEscape("&lt;"))
    }

    @Test
    fun LaunchAgent清单含必要键且逐参数转义() {
        val plist = DesktopLoginItem.launchAgentPlist(
            listOf("/Applications/捎话 & Tidings.app/Contents/MacOS/relay", "-x"),
        )
        assertTrue(plist.contains("<key>Label</key>"))
        assertTrue(plist.contains("<string>${DesktopLoginItem.MACOS_LABEL}</string>"))
        assertTrue(plist.contains("<key>RunAtLoad</key>"))
        assertTrue(plist.contains("<true/>"))
        assertTrue(plist.contains("<key>ProgramArguments</key>"))
        // 每个参数各占一个 <string>，路径里的 & 必须已转义。
        assertTrue(plist.contains("<string>/Applications/捎话 &amp; Tidings.app/Contents/MacOS/relay</string>"))
        assertTrue(plist.contains("<string>-x</string>"))
        assertFalse(plist.contains("& Tidings"))
    }

    @Test
    fun Linux桌面项含必要键与转义后的Exec() {
        val entry = DesktopLoginItem.linuxDesktopEntry(listOf("/opt/捎话 Tidings/relay"))
        assertTrue(entry.startsWith("[Desktop Entry]"))
        assertTrue(entry.contains("Type=Application"))
        assertTrue(entry.contains("X-GNOME-Autostart-enabled=true"))
        assertTrue(entry.contains("Exec=\"/opt/捎话 Tidings/relay\""))
        assertTrue(entry.contains("Terminal=false"))
    }

    @Test
    fun Exec转义保留反斜杠与美元() {
        val line = DesktopLoginItem.execLine(listOf("/a b/\$HOME", "x`y", "q\\r"))
        assertEquals("\"/a b/\\\$HOME\" \"x\\`y\" \"q\\\\r\"", line)
    }

    @Test
    fun Windows命令行按需加引号() {
        assertEquals(
            "\"C:\\Program Files\\Tidings\\relay.exe\" -x",
            DesktopLoginItem.windowsCommandLine(listOf("C:\\Program Files\\Tidings\\relay.exe", "-x")),
        )
        assertEquals("relay.exe -x", DesktopLoginItem.windowsCommandLine(listOf("relay.exe", "-x")))
    }

    // ------------------------------------------------------------ 命令行自造

    @Test
    fun 打包后直接用启动器路径() {
        assertEquals(
            listOf(
                "/Applications/捎话 Tidings.app/Contents/MacOS/捎话 Tidings",
                DesktopLoginItem.BACKGROUND_FLAG,
            ),
            DesktopLoginItem.currentCommand(exe = "/Applications/捎话 Tidings.app/Contents/MacOS/捎话 Tidings", classpath = "ignored"),
        )
    }

    @Test
    fun 开发期java启动器必须补上类路径与主类() {
        // 只写 java 路径会自启一个空 JVM。
        assertEquals(
            listOf("/jdk/bin/java", "-cp", "/a:/b", DesktopLoginItem.MAIN_CLASS, DesktopLoginItem.BACKGROUND_FLAG),
            DesktopLoginItem.currentCommand(exe = "/jdk/bin/java", classpath = "/a:/b"),
        )
    }

    @Test
    fun 自启命令一律带后台开关() {
        // 不带就是「开机自动弹窗」，与设置页写的「收进托盘，不弹窗」相反。
        val packaged = DesktopLoginItem.currentCommand(exe = "/opt/relay/bin/relay", classpath = null)!!
        assertTrue(DesktopLoginItem.BACKGROUND_FLAG in packaged)
        val java = DesktopLoginItem.currentCommand(exe = "/jdk/bin/java", classpath = "/a")!!
        assertTrue(DesktopLoginItem.BACKGROUND_FLAG in java)
        // 开关必须在**最后**：前面是 -cp <path> <mainClass>，插在中间会破坏 java 的参数顺序。
        assertEquals(DesktopLoginItem.BACKGROUND_FLAG, java.last())
    }

    @Test
    fun 启动参数识别后台开关而不误伤深链() {
        // 写入方是 currentCommand，读取方是 main()；两边只靠这个常量对齐，
        // 所以这里既钉「认得出」，也钉「不把外部打开请求当成开关」。
        assertTrue(DesktopLoginItem.wantsBackground(listOf(DesktopLoginItem.BACKGROUND_FLAG)))
        assertTrue(
            DesktopLoginItem.wantsBackground(
                listOf("relay://192.168.1.5:8788", DesktopLoginItem.BACKGROUND_FLAG),
            ),
        )
        assertFalse(DesktopLoginItem.wantsBackground(emptyList()))
        assertFalse(DesktopLoginItem.wantsBackground(listOf("relay://192.168.1.5:8788")))
        // macOS 从 Finder 启动会塞 -psn_0_12345：单横杠不等于后台开关。
        assertFalse(DesktopLoginItem.wantsBackground(listOf("-psn_0_12345")))
    }

    @Test
    fun 后台开关本身不会被解析成节点地址() {
        // main() 用同一个常量把开关从外部打开循环里排除；如果哪天它变成
        // 一个「能被 parse 出来」的串，这里会先红。
        assertEquals(null, DesktopDeepLink.parse(DesktopLoginItem.BACKGROUND_FLAG))
        assertFalse(DesktopDeepLink.isMessageFile(DesktopLoginItem.BACKGROUND_FLAG))
    }

    @Test
    fun 取不到启动路径或类路径时不生成命令() {
        assertEquals(null, DesktopLoginItem.currentCommand(exe = null, classpath = "/a"))
        assertEquals(null, DesktopLoginItem.currentCommand(exe = "  ", classpath = "/a"))
        // java 起步但没有 classpath：宁可不注册，也不能写一条会打开空 JVM 的自启项。
        assertEquals(null, DesktopLoginItem.currentCommand(exe = "/jdk/bin/java", classpath = null))
        assertEquals(null, DesktopLoginItem.currentCommand(exe = "/jdk/bin/java", classpath = "  "))
    }

    // ------------------------------------------------------------ 落盘 / 反注册

    @Test
    fun 注册与反注册macOS自启只碰临时home() {
        val home = tempHome()
        val path = DesktopLoginItem.entryPath(DesktopOs.Macos, home.absolutePath)!!
        val file = java.io.File(path)

        assertFalse(DesktopLoginItem.isEnabled(DesktopOs.Macos, home.absolutePath))

        val enabled = DesktopLoginItem.setEnabled(
            enabled = true,
            os = DesktopOs.Macos,
            homeDir = home.absolutePath,
            command = listOf("/opt/relay"),
        )
        assertTrue(enabled.isSuccess, enabled.exceptionOrNull()?.message)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("<string>/opt/relay</string>"))
        assertTrue(DesktopLoginItem.isEnabled(DesktopOs.Macos, home.absolutePath))

        val disabled = DesktopLoginItem.setEnabled(
            enabled = false,
            os = DesktopOs.Macos,
            homeDir = home.absolutePath,
        )
        assertTrue(disabled.isSuccess, disabled.exceptionOrNull()?.message)
        assertFalse(file.exists())
        assertFalse(DesktopLoginItem.isEnabled(DesktopOs.Macos, home.absolutePath))
    }

    @Test
    fun 注册与反注册Linux自启() {
        val home = tempHome()
        val path = DesktopLoginItem.entryPath(DesktopOs.Linux, home.absolutePath)!!
        val file = java.io.File(path)

        val ok = DesktopLoginItem.setEnabled(
            enabled = true,
            os = DesktopOs.Linux,
            homeDir = home.absolutePath,
            command = listOf("/opt/relay"),
        )
        assertTrue(ok.isSuccess, ok.exceptionOrNull()?.message)
        assertTrue(file.readText().contains("Exec=\"/opt/relay\""))

        assertTrue(DesktopLoginItem.setEnabled(false, DesktopOs.Linux, home.absolutePath).isSuccess)
        assertFalse(file.exists())
    }

    @Test
    fun 不支持的系统上注册失败且不留文件() {
        val home = tempHome()
        val result = DesktopLoginItem.setEnabled(
            enabled = true,
            os = DesktopOs.Other,
            homeDir = home.absolutePath,
            command = listOf("/opt/relay"),
        )
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        // 失败必须是干净的：不能留下半个注册项。
        assertFalse(java.io.File(home, "Library/LaunchAgents").exists())
    }

    @Test
    fun 没有启动路径时不会写出半个注册项() {
        val home = tempHome()
        val result = DesktopLoginItem.setEnabled(
            enabled = true,
            os = DesktopOs.Macos,
            homeDir = home.absolutePath,
            command = null,
        )
        assertTrue(result.isFailure)
        assertFalse(java.io.File(DesktopLoginItem.entryPath(DesktopOs.Macos, home.absolutePath)!!).exists())
    }

    // ------------------------------------------------------------ 偏好持久化

    @Test
    fun 偏好写入一个键不会抹掉其它键() {
        // 只有主题那一版直接 store() 整个文件；加了第二个开关之后，那样写会
        // 让「改主题」把自启开关抹掉，表现为重启后自启设置莫名丢失。
        val dir = Files.createTempDirectory("relay-prefs").toFile().also { it.deleteOnExit() }
        DesktopUiPrefs.saveDarkTheme(dir.absolutePath, false)
        DesktopUiPrefs.savePowerSaver(dir.absolutePath, true)
        DesktopUiPrefs.saveLaunchAtLogin(dir.absolutePath, true)

        assertFalse(DesktopUiPrefs.loadDarkTheme(dir.absolutePath))
        assertTrue(DesktopUiPrefs.loadPowerSaver(dir.absolutePath))
        assertTrue(DesktopUiPrefs.loadLaunchAtLogin(dir.absolutePath))

        // 再改一次主题，另外两个必须还在。
        DesktopUiPrefs.saveDarkTheme(dir.absolutePath, true)
        assertTrue(DesktopUiPrefs.loadDarkTheme(dir.absolutePath))
        assertTrue(DesktopUiPrefs.loadPowerSaver(dir.absolutePath))
        assertTrue(DesktopUiPrefs.loadLaunchAtLogin(dir.absolutePath))
    }

    @Test
    fun 偏好缺失时取默认值且两个开关默认关() {
        val dir = Files.createTempDirectory("relay-prefs-empty").toFile().also { it.deleteOnExit() }
        assertTrue(DesktopUiPrefs.loadDarkTheme(dir.absolutePath))
        // 默认关：省电档与开机自启都不该在用户没点过的情况下生效。
        assertFalse(DesktopUiPrefs.loadPowerSaver(dir.absolutePath))
        assertFalse(DesktopUiPrefs.loadLaunchAtLogin(dir.absolutePath))
    }

    @Test
    fun 偏好文件损坏时退回默认值() {
        val dir = Files.createTempDirectory("relay-prefs-broken").toFile().also { it.deleteOnExit() }
        java.io.File(dir, "ui-prefs.properties").writeText("\u0000\u0001 不是 properties \\u00xx")
        assertFalse(DesktopUiPrefs.loadPowerSaver(dir.absolutePath))
        assertFalse(DesktopUiPrefs.loadLaunchAtLogin(dir.absolutePath))
    }
}
