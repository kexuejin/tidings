// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.plugins

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 托管模式插件宿主（M2-T7）的扫描与拉起判据 —— **零进程**（假 spawner 只记录），
 * 真进程语义由桌面冒烟以 echo 插件真进程验（envDependentTestGate 纪律）。
 *
 * 钉住的行为：
 * 1. 插件目录扫描：没有插件目录是常态（安静返回）；每个子目录一份处置报告；
 * 2. 坏目录不拖垮宿主：manifest 缺失 / JSON 坏 / entrypoint 空 —— 记原因、跳过、继续；
 * 3. 注入契约：子进程环境里必须有 `TIDINGS_CONTROL_URL` 与 `TIDINGS_PLUGIN_TOKEN`
 *    （方案 §2.3 的两把钥匙），工作目录是插件目录本身；
 * 4. 目录名与 pluginId 必须一致（目录是插件身份的载体，对不上账就不拉起）；
 * 5. 已注册/已拉起的 pluginId 不重复拉起；token 文件缺失时由宿主生成。
 */
class PluginHostTest {

    private class RecordingSpawner {
        data class Call(val command: List<String>, val workDir: File, val env: Map<String, String>)

        val calls = mutableListOf<Call>()
        val fakeProcesses = mutableListOf<FakeProcess>()

        fun spawn(command: List<String>, workDir: File, env: Map<String, String>, logFile: File): FakeProcess {
            calls += Call(command, workDir, env)
            val p = FakeProcess()
            fakeProcesses += p
            return p
        }
    }

    /** 够用的假进程：stop() 翻状态，供 PluginHost.stop 的收尾路径走一遍。 */
    private class FakeProcess : Process() {
        @Volatile var alive = true

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
        override fun exitValue(): Int = 0
        override fun getOutputStream() = throw UnsupportedOperationException()
        override fun getInputStream() = throw UnsupportedOperationException()
        override fun getErrorStream() = throw UnsupportedOperationException()
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
    }

    private fun tempPluginData(): File {
        val dir = File.createTempFile("relay-pluginhost-", "").let { f ->
            f.delete()
            f
        }
        dir.mkdirs()
        return dir
    }

    private fun writePlugin(dataDir: File, dirName: String, manifestJson: String): File {
        val d = File(dataDir, "plugins").resolve(dirName).apply { mkdirs() }
        File(d, "manifest.json").writeText(manifestJson)
        return d
    }

    private fun manifestJson(pluginId: String, entrypoint: String?): String =
        buildJsonObject {
            put("pluginId", pluginId)
            put("name", pluginId)
            put("version", "1.0.0")
            put("type", "single")
            if (entrypoint != null) put("entrypoint", entrypoint)
            put("leader", buildJsonObject {
                put("agentId", "lead")
                put("name", "lead")
                put("role", "Lead")
            })
        }.toString()

    @Test
    fun aDirectoryWithoutPluginsIsQuietNotAnError() {
        val dataDir = tempPluginData()
        try {
            val spawner = RecordingSpawner()
            val host = PluginHost(dataDir, { "http://127.0.0.1:8788" }, PluginRegistry(), spawner::spawn)
            val reports = host.start()
            assertEquals(0, reports.size)
            assertTrue(pluginsTokenFile(dataDir).isFile, "没有插件也要把 token 受控文件备好（守护进程模式要读它）")
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun brokenPluginDirsAreReportedAndSkippedWithoutTakingTheHostDown() {
        val dataDir = tempPluginData()
        try {
            writePlugin(dataDir, "no-manifest", "{}").resolve("manifest.json").delete()
            writePlugin(dataDir, "bad-json", "{ not json")
            writePlugin(dataDir, "no-entrypoint", manifestJson("no-entrypoint", null))
            writePlugin(dataDir, "blank-entrypoint", manifestJson("blank-entrypoint", "   "))
            writePlugin(dataDir, "name-mismatch", manifestJson("declared-id", "python3 main.py"))

            val spawner = RecordingSpawner()
            val host = PluginHost(dataDir, { "http://127.0.0.1:8788" }, PluginRegistry(), spawner::spawn)
            val reports = host.start().sortedBy { it.dirName }

            assertEquals(5, reports.size, "五个目录各一份报告")
            assertTrue(reports.none { it.started }, "坏的都不该被拉起：" + reports)
            val reasons = reports.joinToString("\n") { (it.reason ?: "") }
            assertTrue(reasons.contains("no manifest.json"), reasons)
            assertTrue(reasons.contains("manifest unreadable"), reasons)
            // 缺 entrypoint 与空白 entrypoint 是同一个答案（守护进程模式自己管进程）。
            assertEquals(2, reasons.split("no entrypoint").size - 1, reasons)
            assertTrue(reasons.contains("must match"), reasons)
            assertEquals(0, spawner.calls.size, "一个进程都不该起")
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun aValidPluginIsSpawnedInTheItsOwnDirectoryWithBothKeysInjected() {
        val dataDir = tempPluginData()
        // 可执行文件解析的搜索目录由测试注入：夹一个本机必然存在的假命令，
        // 判据就不依赖这台机器装没装 python（envDependentTestGate 纪律）。
        val searchDir = java.nio.file.Files.createTempDirectory("relay-pluginhost-search-").toFile()
        val fakeCmd = File(searchDir, "fakecmd").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        try {
            val dir = writePlugin(dataDir, "echo-team", manifestJson("echo-team", "fakecmd main.py"))
            val spawner = RecordingSpawner()
            val host = PluginHost(
                dataDir, { "http://127.0.0.1:8788" }, PluginRegistry(), spawner::spawn,
                searchDirectories = listOf(searchDir.absolutePath),
            )
            val reports = host.start()

            assertEquals(1, reports.size)
            val report = reports.single()
            assertEquals(true, report.started, report.reason ?: "")
            assertEquals(listOf(fakeCmd.absolutePath, "main.py"), report.command, "裸命令解析成绝对路径后交给进程")
            val call = spawner.calls.single()
            assertEquals(dir, call.workDir, "工作目录是插件目录本身（相对路径的 entrypoint 才找得到自己的文件）")
            assertEquals("http://127.0.0.1:8788", call.env[PluginHost.ENV_CONTROL_URL])
            val token = call.env[PluginHost.ENV_PLUGIN_TOKEN]
            assertNotNull(token)
            assertEquals(64, token.length, "注入的 token 就是宿主生成的那把（64 位十六进制）")
            assertEquals(token, pluginsTokenFile(dataDir).readText().trim(), "env 与受控文件是同一把 —— 两种模式互认")
            assertEquals(1, host.managedPluginIdsSnapshot().size)

            host.stop()
            assertTrue(!spawner.fakeProcesses.single().alive, "停机要终结子进程（先 SIGTERM 再兜底强杀）")
        } finally {
            dataDir.deleteRecursively()
            searchDir.deleteRecursively()
        }
    }

    @Test
    fun anUnresolvableBareCommandIsRejectedWithTheSearchListAndLoggedNextToThePlugin() {
        val dataDir = tempPluginData()
        // 搜索目录指向一个空目录：裸命令必然解析不到（不依赖机器装了什么）。
        val searchDir = java.nio.file.Files.createTempDirectory("relay-pluginhost-search-empty-").toFile()
        try {
            val dir = writePlugin(dataDir, "echo-team", manifestJson("echo-team", "definitely-not-a-command main.py"))
            val spawner = RecordingSpawner()
            val host = PluginHost(
                dataDir, { "http://127.0.0.1:8788" }, PluginRegistry(), spawner::spawn,
                searchDirectories = listOf(searchDir.absolutePath),
            )
            val reports = host.start()
            val report = reports.single()
            assertEquals(false, report.started)
            assertTrue(
                (report.reason ?: "").contains("definitely-not-a-command") &&
                    (report.reason ?: "").contains(searchDir.absolutePath),
                "拒绝理由要给出可操作的方向（命令名 + 搜索过的目录）：" + report.reason,
            )
            assertEquals(0, spawner.calls.size)
            assertTrue(
                File(dir, PluginHost.PROCESS_LOG_FILE).readText().contains("definitely-not-a-command"),
                "解析失败也要落进插件旁的 process.log（图形界面上唯一可诊断的地方）",
            )
        } finally {
            dataDir.deleteRecursively()
            searchDir.deleteRecursively()
        }
    }

    @Test
    fun alreadyRegisteredPluginIdsAreNotSpawnedAgain() {
        val dataDir = tempPluginData()
        try {
            writePlugin(dataDir, "echo-team", manifestJson("echo-team", "python3 main.py"))
            // 守护进程模式先自己注册进来了。
            val plugins = PluginRegistry()
            plugins.register(
                PluginManifestJson.parse(manifestJson("echo-team", "python3 main.py")),
                "http://127.0.0.1:1",
                1,
                listOf("agent:x:lead"),
            )
            val spawner = RecordingSpawner()
            val host = PluginHost(dataDir, { "http://127.0.0.1:8788" }, plugins, spawner::spawn)
            val reports = host.start()
            assertTrue(reports.single().let { !it.started }, "已注册的不重复拉起：" + reports)
            assertEquals(0, spawner.calls.size)
        } finally {
            dataDir.deleteRecursively()
        }
    }

    private fun pluginsTokenFile(dataDir: File): File =
        File(File(dataDir, "plugins"), PluginTokenGate.TOKEN_FILE_NAME)
}
