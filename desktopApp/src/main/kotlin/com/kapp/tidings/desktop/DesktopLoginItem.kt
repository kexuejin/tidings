// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File

/** 桌面端要区别对待的三个系统；其余一律 [Other]（不注册、开关禁用并说明原因）。 */
internal enum class DesktopOs { Macos, Windows, Linux, Other }

internal fun desktopOs(osName: String): DesktopOs = when {
    osName.contains("mac", ignoreCase = true) -> DesktopOs.Macos
    osName.contains("win", ignoreCase = true) -> DesktopOs.Windows
    osName.contains("linux", ignoreCase = true) -> DesktopOs.Linux
    else -> DesktopOs.Other
}

/**
 * 开机自启的注册与反注册。
 *
 * ## 为什么不用现成库
 * 三个系统各有一份**纯文本/一条命令**的注册方式，加一个 Maven 依赖来生成这三段
 * 文本不划算（桌面端硬约束是零新增依赖）：
 * - macOS：`~/Library/LaunchAgents/<label>.plist`（`RunAtLoad`）。写文件即可，
 *   **不调用** `launchctl load`（该子命令已被弃用，而且它需要用户会话，写文件下次
 *   登录同样生效）。
 * - Linux：`~/.config/autostart/<name>.desktop`（freedesktop autostart 规范）。
 * - Windows：`HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 下一个 REG_SZ。
 *   这是唯一需要起子进程的一条（`reg.exe`）。
 *
 * ## 默认关闭
 * 关窗已经改成收托盘，托盘的价值变高了，但"默认开机自启"会把用户「主动不装」的
 * 意愿一起推翻。所以开关默认关，由用户在桌面设置里打开。
 *
 * ## 纯函数与 IO 分开
 * 三段文本的生成、注册文件的位置都是纯函数，能在单测里对着临时目录验证；
 * 只有 [setEnabled] 落盘/写注册表。
 */
internal object DesktopLoginItem {

    /** macOS LaunchAgent 的 Label，与 `com.kapp.tidings.desktop` 的 bundleID 同名便于排查。 */
    const val MACOS_LABEL = "com.kapp.tidings.desktop"

    /** Windows 注册表值名。 */
    const val WINDOWS_VALUE_NAME = "捎话 Tidings"

    /** Linux autostart 文件名。 */
    const val LINUX_FILE_NAME = "relay-desktop.desktop"

    /** Java 入口类名：需要重造命令行时用（见 [currentCommand]）。 */
    const val MAIN_CLASS = "com.kapp.tidings.desktop.MainKt"

    /**
     * 登录自启时追加的开关：静默启动，只驻留托盘、不弹主窗。
     *
     * 没有它，自启就只能等价于「开机自动弹一个窗口」，用户第二天早上会看到一个
     * 他自己没打开的界面 —— 而设置里的原话是「收进托盘，不弹窗」。解析在
     * `Main.kt`，两处必须用同一个常量。
     */
    const val BACKGROUND_FLAG = "--background"

    /**
     * 启动参数里是否有 [BACKGROUND_FLAG]。
     *
     * 单独抽出来是为了**可测**：这个常量的写入方（本文件）和读取方（`Main.kt`）
     * 是两个文件，只靠常量共享，没有编译器帮忙对齐；以前这行判断只存在于 `main()`
     * 里，谁改了参数名都不会有测试变红。
     */
    fun wantsBackground(args: List<String>): Boolean = args.any { it == BACKGROUND_FLAG }

    /**
     * 自启注册文件的位置。Windows 与其它系统走注册表/不支持，返回 `null`。
     *
     * 抽成接收 `homeDir` 的函数是为了能在单测里指向临时目录，不去碰真实的主目录。
     */
    fun entryPath(os: DesktopOs, homeDir: String): String? = when (os) {
        DesktopOs.Macos -> "$homeDir/Library/LaunchAgents/$MACOS_LABEL.plist"
        DesktopOs.Linux -> "$homeDir/.config/autostart/$LINUX_FILE_NAME"
        else -> null
    }

    /** 该平台是否支持自启开关。 */
    fun isSupported(os: DesktopOs): Boolean =
        os == DesktopOs.Macos || os == DesktopOs.Linux || os == DesktopOs.Windows

    // ------------------------------------------------------------ 文本生成（纯）

    /**
     * XML 文本转义。
     *
     * 路径里出现 `&` 是常态（`/Users/a&b/…`），一个没转义的 `&` 会让整个 plist
     * 无法解析 —— 而 macOS 对坏 plist 的处理是**静默忽略**，表现为"开关打开了但
     * 开机不自启"，极难查。
     */
    fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** macOS LaunchAgent plist。每个参数各占一个 `<string>`（不拼成一个 shell 串）。 */
    fun launchAgentPlist(command: List<String>): String {
        val args = command.joinToString("\n") { "        <string>${xmlEscape(it)}</string>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$MACOS_LABEL</string>
    <key>ProgramArguments</key>
    <array>
$args
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>ProcessType</key>
    <string>Interactive</string>
</dict>
</plist>
"""
    }

    /**
     * freedesktop 桌面项。
     *
     * `X-GNOME-Autostart-enabled=true` 是 GNOME 认的开关；缺了它部分桌面环境会
     * 把文件当成"已禁用"。
     */
    fun linuxDesktopEntry(command: List<String>): String = """[Desktop Entry]
Type=Application
Name=捎话 Tidings
Comment=局域网直连的消息与文件传输
Exec=${execLine(command)}
Terminal=false
X-GNOME-Autostart-enabled=true
"""

    /**
     * `Exec=` 的转义：freedesktop 规范里双引号内的 `"`、`` ` ``、`$`、`\` 必须反斜杠转义。
     * 路径含空格很常见（macOS 的 `/Applications/捎话 Tidings.app/...` 就是这样）。
     */
    fun execLine(command: List<String>): String = command.joinToString(" ") { arg ->
        "\"" + arg
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("`", "\\`")
            .replace("$", "\\$") + "\""
    }

    /** Windows `Run` 值的数据：整条命令行，路径含空格时用引号包住。 */
    fun windowsCommandLine(command: List<String>): String = command.joinToString(" ") { arg ->
        if (arg.isEmpty() || arg.any { it == ' ' || it == '\t' }) {
            "\"" + arg.replace("\"", "\\\"") + "\""
        } else {
            arg
        }
    }

    /**
     * 当前进程的启动命令行，用来在自启时把同一个应用再拉起来。
     *
     * 两种情况：
     * - 打包后（jpackage）`command()` 是应用自己的启动器，直接用它；
     * - 开发期（`gradlew :desktopApp:run`）它是 `java`，此时必须补上
     *   `-cp <classpath> com.kapp.tidings.desktop.MainKt`，否则自启只是开了一个空 JVM。
     *
     * 两种都追加 [BACKGROUND_FLAG]：自启的语义是「后台待命」，不是「帮我开个窗口」。
     *
     * 取不到就返回 `null`：宁可不注册，也不能写一条会打开空 JVM 的自启项。
     */
    fun currentCommand(
        exe: String? = ProcessHandle.current().info().command().orElse(null),
        classpath: String? = System.getProperty("java.class.path"),
    ): List<String>? {
        val exePath = exe?.takeIf { it.isNotBlank() } ?: return null
        val looksLikeJava = File(exePath).name.startsWith("java", ignoreCase = true)
        if (!looksLikeJava) return listOf(exePath, BACKGROUND_FLAG)
        val cp = classpath?.takeIf { it.isNotBlank() } ?: return null
        return listOf(exePath, "-cp", cp, MAIN_CLASS, BACKGROUND_FLAG)
    }

    // ------------------------------------------------------------------ IO 层

    /** 自启是否已注册。Windows 走 `reg query`，其余看注册文件在不在。 */
    fun isEnabled(
        os: DesktopOs = currentOs(),
        homeDir: String = System.getProperty("user.home") ?: ".",
    ): Boolean = when (os) {
        DesktopOs.Windows -> windowsValueExists()
        else -> entryPath(os, homeDir)?.let { File(it).isFile } ?: false
    }

    /**
     * 打开/关闭自启。
     *
     * 返回 [Result] 而不是抛：设置界面的开关要能"弹回去并说明原因"，
     * 而不是把整个设置窗带崩。失败时**不留半个注册项** —— 先写临时文件再 rename，
     * 避免写到一半断电留下一个坏 plist（macOS 会静默忽略坏 plist）。
     */
    fun setEnabled(
        enabled: Boolean,
        os: DesktopOs = currentOs(),
        homeDir: String = System.getProperty("user.home") ?: ".",
        command: List<String>? = currentCommand(),
    ): Result<Unit> {
        if (!isSupported(os)) return Result.failure(IllegalStateException("当前系统（$os）不支持开机自启"))
        if (os == DesktopOs.Windows) {
            val cmd = command ?: return Result.failure(IllegalStateException("取不到本应用的启动路径"))
            return windowsSet(enabled, cmd)
        }

        val path = entryPath(os, homeDir) ?: return Result.failure(IllegalStateException("没有可用的自启注册路径"))
        val file = File(path)
        return runCatching {
            if (!enabled) {
                if (file.exists() && !file.delete()) error("删除自启注册文件失败：$path")
                return@runCatching
            }
            val cmd = command ?: error("取不到本应用的启动路径")
            val text = when (os) {
                DesktopOs.Macos -> launchAgentPlist(cmd)
                else -> linuxDesktopEntry(cmd)
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // rename 在同目录内几乎不会失败；真失败时别把临时文件留在 autostart 目录里，
                // 否则下次登录可能被当成第二个自启项。
                tmp.delete()
                error("写入自启注册文件失败：$path")
            }
        }
    }

    private fun currentOs(): DesktopOs = desktopOs(System.getProperty("os.name").orEmpty())

    private fun windowsValueExists(): Boolean = runCatching {
        val p = ProcessBuilder(
            "reg", "query", WINDOWS_RUN_KEY, "/v", WINDOWS_VALUE_NAME,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor() == 0
    }.getOrDefault(false)

    private fun windowsSet(enabled: Boolean, command: List<String>): Result<Unit> = runCatching {
        val args = if (enabled) {
            listOf(
                "reg", "add", WINDOWS_RUN_KEY, "/v", WINDOWS_VALUE_NAME,
                "/t", "REG_SZ", "/d", windowsCommandLine(command), "/f",
            )
        } else {
            listOf("reg", "delete", WINDOWS_RUN_KEY, "/v", WINDOWS_VALUE_NAME, "/f")
        }
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = p.inputStream.readBytes().decodeToString()
        if (p.waitFor() != 0) error("reg 命令失败：${output.trim()}")
    }

    const val WINDOWS_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
}