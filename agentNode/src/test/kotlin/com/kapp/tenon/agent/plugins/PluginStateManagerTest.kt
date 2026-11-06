// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginStateManagerTest {

    private fun tempDir(): File {
        val dir = File.createTempFile("state-mgr-test-", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        return dir
    }

    @Test
    fun pluginsAreEnabledByDefault() {
        val dir = tempDir()
        try {
            val mgr = PluginStateManager(dir)
            assertTrue(mgr.isPluginEnabled("claude-code"))
            assertTrue(mgr.listDisabledPlugins().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun disablingAndEnablingPluginsPersistsAcrossInstances() {
        val dir = tempDir()
        try {
            val mgr1 = PluginStateManager(dir)
            mgr1.setPluginEnabled("claude-code", false)
            mgr1.setPluginEnabled("custom-script", false)

            assertFalse(mgr1.isPluginEnabled("claude-code"))
            assertFalse(mgr1.isPluginEnabled("custom-script"))
            assertEquals(setOf("claude-code", "custom-script"), mgr1.listDisabledPlugins())

            // Re-open with new manager instance reading from same disk file
            val mgr2 = PluginStateManager(dir)
            assertFalse(mgr2.isPluginEnabled("claude-code"))
            assertFalse(mgr2.isPluginEnabled("custom-script"))
            assertTrue(mgr2.isPluginEnabled("other-plugin"))

            // Re-enable
            mgr2.setPluginEnabled("claude-code", true)
            assertTrue(mgr2.isPluginEnabled("claude-code"))
            assertEquals(setOf("custom-script"), mgr2.listDisabledPlugins())

            val mgr3 = PluginStateManager(dir)
            assertTrue(mgr3.isPluginEnabled("claude-code"))
            assertFalse(mgr3.isPluginEnabled("custom-script"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pluginSettingsPersistAcrossInstances() {
        val dir = tempDir()
        try {
            val mgr1 = PluginStateManager(dir)
            mgr1.updatePluginSettings("deepseek", mapOf("model" to "deepseek-coder", "apiKey" to "sk-12345"))

            assertEquals("deepseek-coder", mgr1.getPluginSettings("deepseek")["model"])
            assertEquals("sk-12345", mgr1.getPluginSettings("deepseek")["apiKey"])

            val mgr2 = PluginStateManager(dir)
            assertEquals("deepseek-coder", mgr2.getPluginSettings("deepseek")["model"])
            assertEquals("sk-12345", mgr2.getPluginSettings("deepseek")["apiKey"])

            mgr2.clearPluginSettings("deepseek")
            assertTrue(mgr2.getPluginSettings("deepseek").isEmpty())

            val mgr3 = PluginStateManager(dir)
            assertTrue(mgr3.getPluginSettings("deepseek").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptedStateFileRecoversCleanly() {
        val dir = tempDir()
        try {
            val file = File(dir, PluginStateManager.STATE_FILE_NAME)
            file.writeText("corrupted json {{!! invalid")

            val mgr = PluginStateManager(dir)
            assertTrue(mgr.isPluginEnabled("any-plugin"))
            assertTrue(mgr.listDisabledPlugins().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 桌面端 `DesktopExtension` 传的是 `File(dataDir, "plugins-state.json")`（一个以 .json
     * 结尾的**文件**）。旧实现会对它 `mkdirs()`，把它变成空目录，之后写入异常被吞掉，
     * 于是控制面 HTTP 全 200、内存即时生效、重启全丢（2026-11-05 真机实测）。
     */
    @Test
    fun jsonFilePathStaysARegularFileAndPersistsAcrossInstances() {
        val dir = tempDir()
        try {
            val statePath = File(dir, "plugins-state.json")
            val mgr1 = PluginStateManager(statePath)
            mgr1.setPluginEnabled("claude-cli", false)
            mgr1.updatePluginSettings("deepseek", mapOf("persist" to "proof"))

            assertTrue(statePath.isFile, "状态路径必须是普通 JSON 文件，不能被 mkdirs() 建成目录")
            assertFalse(statePath.isDirectory)
            assertEquals(null, mgr1.lastSaveError(), "成功写入不许留下失败记录")

            val mgr2 = PluginStateManager(statePath)
            assertFalse(mgr2.isPluginEnabled("claude-cli"))
            assertEquals("proof", mgr2.getPluginSettings("deepseek")["persist"])
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 旧版本留下的现场：`plugins-state.json` 已经是个空目录 —— 新版本必须自愈，而不是继续丢状态。 */
    @Test
    fun staleDirectoryAtStatePathIsRepairedOnFirstSave() {
        val dir = tempDir()
        try {
            val statePath = File(dir, "plugins-state.json")
            assertTrue(statePath.mkdirs(), "夹具：先造出旧版本留下的空目录")

            val mgr = PluginStateManager(statePath)
            mgr.setPluginEnabled("echo-mock", false)

            assertTrue(statePath.isFile, "空目录应被拆掉并换成普通文件")
            assertEquals(null, mgr.lastSaveError())
            assertTrue(statePath.readText().contains("echo-mock"))

            val reopened = PluginStateManager(statePath)
            assertFalse(reopened.isPluginEnabled("echo-mock"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 写盘失败不许再被 `catch (_: Throwable)` 静默吞掉：必须留下可查的异常。 */
    @Test
    fun saveFailureIsRecordedInsteadOfSilentlySwallowed() {
        val dir = tempDir()
        try {
            val blocked = File(dir, "blocked")
            blocked.writeText("i am a file, not a directory")
            val statePath = File(blocked, "plugins-state.json")

            val mgr = PluginStateManager(statePath)
            mgr.setPluginEnabled("claude-cli", false)

            assertTrue(mgr.lastSaveError() != null, "父路径不可建时必须有失败记录")
            assertFalse(statePath.isFile, "写盘失败不许凭空造出状态文件")
        } finally {
            dir.deleteRecursively()
        }
    }
}
