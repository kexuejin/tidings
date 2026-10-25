// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 自启项迁移的判据：重注册**恰好发生一次**（老在、新不在时），老 plist 必须删掉
 * （否则登录时两个 label 各起一个进程）。只断言「文件存在」区分不了
 * 「迁移成功」与「什么都没做」。
 */
class LegacyLoginItemMigrationTest {
    private fun tempHome(): File = Files.createTempDirectory("tidings-home").toFile()

    private fun writePlist(home: File, label: String) {
        val agents = File(home, "Library/LaunchAgents").apply { mkdirs() }
        File(agents, "$label.plist").writeText("<plist>old</plist>")
    }

    @Test
    fun `老 plist 在且新 plist 不在时重注册一次并删老`() {
        val home = tempHome()
        writePlist(home, DesktopLoginItem.LEGACY_MACOS_LABEL)
        var registerCalls = 0

        DesktopLoginItem.migrateLegacyMacosEntry(
            homeDir = home.absolutePath,
            reRegister = { registerCalls++; Result.success(Unit) },
        )

        assertEquals(1, registerCalls, "用户此前开着自启，必须按当前命令行重注册恰好一次")
        assertFalse(
            File(DesktopLoginItem.legacyMacosEntryPath(home.absolutePath)).isFile,
            "老 plist 必须删掉，否则登录时两个 label 各起一个进程",
        )
    }

    @Test
    fun `新 plist 已在时只删老的不重注册`() {
        val home = tempHome()
        writePlist(home, DesktopLoginItem.LEGACY_MACOS_LABEL)
        writePlist(home, DesktopLoginItem.MACOS_LABEL)
        var registerCalls = 0

        DesktopLoginItem.migrateLegacyMacosEntry(
            homeDir = home.absolutePath,
            reRegister = { registerCalls++; Result.success(Unit) },
        )

        assertEquals(0, registerCalls, "新项已在，重注册会把用户的开关状态顶掉")
        assertTrue(
            File(home, "Library/LaunchAgents/${DesktopLoginItem.MACOS_LABEL}.plist").isFile,
            "新 plist 原样保留",
        )
        assertFalse(File(DesktopLoginItem.legacyMacosEntryPath(home.absolutePath)).isFile)
    }

    @Test
    fun `没有老 plist 时什么都不动`() {
        val home = tempHome()
        File(home, "Library/LaunchAgents").mkdirs()
        var registerCalls = 0

        DesktopLoginItem.migrateLegacyMacosEntry(
            homeDir = home.absolutePath,
            reRegister = { registerCalls++; Result.success(Unit) },
        )

        assertEquals(0, registerCalls)
    }
}
