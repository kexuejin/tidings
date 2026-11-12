// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 内置 CLI 从「常驻插件」退场：不再种，且老机器上已经种下的必须被拆掉。
 *
 * 两个判据都是**行为级**的，不看日记：
 *  - 退役之后 `PluginHost.start()` 一次都不许调用 spawner —— 这是「不再秒退」的正向证据；
 *  - 只置 disabled 不够，清单必须改名，否则状态文件一丢又复活。
 *
 * 反向探针：把 [BuiltinPluginSeeder.retire] 改成直接 `return emptyList()`，
 * [retiredCliManifestIsNotSpawnedAnymore] 立刻变红（spawner 被调用 1 次）。
 */
class BuiltinPluginSeederTest {

    /** 不真起进程：只记「被调用了几次」，秒退与否与本用例无关。 */
    private class FakeProcess : Process() {
        @Volatile private var alive = true
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun isAlive(): Boolean = alive
        override fun exitValue(): Int = 0
        override fun destroy() { alive = false }
        override fun destroyForcibly(): Process { alive = false; return this }
        override fun waitFor(): Int { alive = false; return 0 }
        override fun pid(): Long = 4343L
        override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
        override fun getInputStream(): InputStream = throw UnsupportedOperationException()
        override fun getErrorStream(): InputStream = throw UnsupportedOperationException()
    }

    private fun writeManifest(dataDir: File, dirName: String, pluginId: String, entrypoint: String): File {
        val pluginDir = File(dataDir, "plugins/$dirName").apply { mkdirs() }
        File(pluginDir, PluginHost.MANIFEST_FILE).writeText(
            buildJsonObject {
                put("pluginId", pluginId)
                put("name", pluginId)
                put("version", "1.0.0")
                put("type", "single")
                put("entrypoint", entrypoint)
                // 真实清单都有 leader（注册必填），这里补齐，免得解析路径与线上不同。
                put("leader", buildJsonObject {
                    put("agentId", pluginId)
                    put("name", pluginId)
                    put("role", "worker")
                })
            }.toString(),
        )
        return pluginDir
    }

    @Test
    fun retiredCliManifestIsNotSpawnedAnymore() {
        val dataDir = Files.createTempDirectory("seeder-retire-").toFile()
        val searchDir = Files.createTempDirectory("seeder-search-").toFile()
        try {
            File(searchDir, "claude").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
            val claudeCli = BuiltinPluginSeeder.RETIRED_PLUGIN_IDS.first { it == "claude-cli" }
            val pluginDir = writeManifest(dataDir, claudeCli, claudeCli, "claude")
            val spawns = AtomicInteger()
            val state = PluginStateManager(dataDir)

            val retired = BuiltinPluginSeeder.retire(File(dataDir, "plugins"), state)
            assertEquals(listOf(claudeCli), retired, "存量内置清单应被退役")

            val host = PluginHost(
                dataDirectory = dataDir,
                controlBaseUrl = { "http://127.0.0.1:8788" },
                plugins = PluginRegistry(),
                spawner = { _, _, _, _ -> spawns.incrementAndGet(); FakeProcess() },
                searchDirectories = listOf(searchDir.absolutePath),
                stateManager = state,
            )
            val reports = host.start()
            assertEquals(0, spawns.get(), "退役之后不许再拉起 CLI 进程（那正是每次启动秒退的来源）")
            assertTrue(reports.none { it.started }, "一个都不许拉起：" + reports)
            assertTrue(
                reports.all { (it.reason ?: "").contains(PluginHost.MANIFEST_FILE) },
                "目录还在，跳过原因必须指向被搬走的清单：" + reports,
            )

            // 清单改名 + 留痕：只置 disabled 时状态文件一丢就复活。
            assertFalse(File(pluginDir, PluginHost.MANIFEST_FILE).exists(), "manifest.json 应已改名")
            assertTrue(File(pluginDir, BuiltinPluginSeeder.RETIRED_MANIFEST).isFile)
            val log = File(pluginDir, PluginHost.PROCESS_LOG_FILE).readText()
            assertTrue(log.contains("retired:"), log)
            assertTrue(log.contains("spawned per task"), "诊断要写清它为什么不该常驻：" + log)
        } finally {
            dataDir.deleteRecursively()
            searchDir.deleteRecursively()
        }
    }

    @Test
    fun retirementIsIdempotentAndLogsOnce() {
        val dataDir = Files.createTempDirectory("seeder-retire-twice-").toFile()
        try {
            val pluginDir = writeManifest(dataDir, "dsh-cli", "dsh-cli", "dsh")
            val pluginsDir = File(dataDir, "plugins")

            assertEquals(listOf("dsh-cli"), BuiltinPluginSeeder.retire(pluginsDir, null))
            assertEquals(emptyList(), BuiltinPluginSeeder.retire(pluginsDir, null), "第二次应无事可做")

            val log = File(pluginDir, PluginHost.PROCESS_LOG_FILE).readText()
            assertEquals(1, log.windowed("retired:".length) { it }.count { it == "retired:" }, log)
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun retirementMatchesTheLegacyDirectorySpelling() {
        val dataDir = Files.createTempDirectory("seeder-legacy-dir-").toFile()
        try {
            // 老机器上确实存在这种错位：目录 `cli-dsh`，清单里写 `dsh-cli`。
            writeManifest(dataDir, "cli-dsh", "dsh-cli", "dsh")
            val retired = BuiltinPluginSeeder.retire(File(dataDir, "plugins"), null)
            assertEquals(listOf("dsh-cli"), retired, "目录名命中也要退役")
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun genuinePluginsAreLeftUntouched() {
        val dataDir = Files.createTempDirectory("seeder-real-plugin-").toFile()
        val searchDir = Files.createTempDirectory("seeder-search-").toFile()
        try {
            File(searchDir, "bridge").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
            val pluginDir = writeManifest(dataDir, "dsh-tenon", "dsh-tenon", "bridge")
            val state = PluginStateManager(dataDir)

            assertEquals(emptyList(), BuiltinPluginSeeder.retire(File(dataDir, "plugins"), state))
            assertTrue(File(pluginDir, PluginHost.MANIFEST_FILE).isFile, "真插件不许被动")
            assertTrue(state.isPluginEnabled("dsh-tenon"), "真插件不许被停用")

            val spawns = AtomicInteger()
            PluginHost(
                dataDirectory = dataDir,
                controlBaseUrl = { "http://127.0.0.1:8788" },
                plugins = PluginRegistry(),
                spawner = { _, _, _, _ -> spawns.incrementAndGet(); FakeProcess() },
                searchDirectories = listOf(searchDir.absolutePath),
                stateManager = state,
            ).start()
            assertEquals(1, spawns.get(), "真插件照旧拉起（证明上一条不是「全都没拉起」的空集假绿）")
        } finally {
            dataDir.deleteRecursively()
            searchDir.deleteRecursively()
        }
    }

    @Test
    fun retiredPluginIsRecordedAsDisabledInTheStateFile() {
        val dataDir = Files.createTempDirectory("seeder-state-").toFile()
        try {
            writeManifest(dataDir, "agy-cli", "agy-cli", "agy")
            val state = PluginStateManager(dataDir)
            BuiltinPluginSeeder.retire(File(dataDir, "plugins"), state)

            assertFalse(state.isPluginEnabled("agy-cli"), "插件管理页要能看出它被停用了")
            // 跨重启仍生效：重新打开同一份状态文件。
            assertFalse(PluginStateManager(dataDir).isPluginEnabled("agy-cli"))
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun retiredIdSetCoversBothHistoricalSpellings() {
        // 两种写法都在历史里出现过；漏一种就等于漏掉一批机器。
        for (key in listOf("claude", "codex", "agy", "dsh", "opencode")) {
            assertTrue(key + "-cli" in BuiltinPluginSeeder.RETIRED_PLUGIN_IDS, key + "-cli")
            assertTrue("cli-" + key in BuiltinPluginSeeder.RETIRED_PLUGIN_IDS, "cli-" + key)
        }
        assertTrue("echo-mock" in BuiltinPluginSeeder.RETIRED_PLUGIN_IDS)
        assertFalse("dsh-tenon" in BuiltinPluginSeeder.RETIRED_PLUGIN_IDS, "真桥不是内置伪插件")
    }
}
