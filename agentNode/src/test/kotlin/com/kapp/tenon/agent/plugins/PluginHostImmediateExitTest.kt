// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「拉起即死」的宿主侧观测：秒退必须留下可诊断的一行，长驻进程不得多出噪音。
 *
 * 用假进程走可注入的 spawner，零真进程依赖；观察器的收敛用 [PluginHost.awaitExitObservers]
 * 而不是固定等待 —— 断言「没有落行」之前必须先确定观察器真的跑过。
 */
class PluginHostImmediateExitTest {

    /** 按构造参数决定「窗口内退出」还是「窗口结束时仍活着」。 */
    private class FakeProcess(
        private val exitCode: Int,
        private val exitsInsideWindow: Boolean,
    ) : Process() {
        @Volatile private var alive = true

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!exitsInsideWindow) return false
            alive = false
            return true
        }

        override fun isAlive(): Boolean = alive

        override fun exitValue(): Int {
            check(!alive) { "process is still running" }
            return exitCode
        }

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            alive = false
            return this
        }

        override fun waitFor(): Int {
            alive = false
            return exitCode
        }

        override fun pid(): Long = 4242L
        override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
        override fun getInputStream(): InputStream = throw UnsupportedOperationException()
        override fun getErrorStream(): InputStream = throw UnsupportedOperationException()
    }

    private fun writePlugin(dataDir: File, pluginId: String, entrypoint: String): File {
        val pluginDir = File(dataDir, "plugins/$pluginId").apply { mkdirs() }
        File(pluginDir, PluginHost.MANIFEST_FILE).writeText(
            buildJsonObject {
                put("pluginId", pluginId)
                put("name", pluginId)
                put("version", "1.0.0")
                put("type", "single")
                put("entrypoint", entrypoint)
            }.toString(),
        )
        return pluginDir
    }

    /** 临时目录只装本用例创建的文件；walkFileTree 不跟随符号链接。 */
    private fun deleteTree(root: File) {
        if (!root.exists()) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    @Test
    fun aProcessThatDiesInsideTheWindowGetsAHostSideDiagnostic() {
        val dataDir = Files.createTempDirectory("relay-plugin-immediate-").toFile()
        val searchDir = Files.createTempDirectory("relay-plugin-search-").toFile()
        try {
            File(searchDir, "fake-entrypoint").apply {
                writeText("#!/bin/sh\nexit 3\n")
                setExecutable(true)
            }
            val pluginDir = writePlugin(dataDir, "immediate-exit", "fake-entrypoint")
            val process = FakeProcess(exitCode = 3, exitsInsideWindow = true)
            val host = PluginHost(
                dataDirectory = dataDir,
                controlBaseUrl = { "http://127.0.0.1:8788" },
                plugins = PluginRegistry(),
                spawner = { _, _, _, _ -> process },
                searchDirectories = listOf(searchDir.absolutePath),
                immediateExitWindowMillis = 1_000L,
            )

            assertTrue(host.start().single().started, "进程已拉起，秒退是拉起之后才发生的事")
            assertTrue(host.awaitExitObservers(5_000L), "观察器应在超时前收敛")

            val log = File(pluginDir, PluginHost.PROCESS_LOG_FILE).readText()
            assertTrue(log.contains("exited immediately"), log)
            assertTrue(log.contains("exit=3"), "诊断必须带退出码：" + log)
            assertTrue(log.contains("entrypoint"), "诊断要指向 entrypoint 配置：" + log)
            assertTrue(log.contains(PluginHost.MANIFEST_FILE), "诊断要告诉用户改哪个文件：" + log)
        } finally {
            deleteTree(dataDir)
            deleteTree(searchDir)
        }
    }

    @Test
    fun aLongRunningProcessAddsNoImmediateExitNoise() {
        val dataDir = Files.createTempDirectory("relay-plugin-longrun-").toFile()
        val searchDir = Files.createTempDirectory("relay-plugin-search-").toFile()
        try {
            File(searchDir, "fake-entrypoint").apply {
                writeText("#!/bin/sh\nexit 0\n")
                setExecutable(true)
            }
            val pluginDir = writePlugin(dataDir, "long-running", "fake-entrypoint")
            val process = FakeProcess(exitCode = 0, exitsInsideWindow = false)
            val host = PluginHost(
                dataDirectory = dataDir,
                controlBaseUrl = { "http://127.0.0.1:8788" },
                plugins = PluginRegistry(),
                spawner = { _, _, _, _ -> process },
                searchDirectories = listOf(searchDir.absolutePath),
                immediateExitWindowMillis = 50L,
            )

            assertTrue(host.start().single().started)
            assertTrue(host.awaitExitObservers(5_000L), "观察器在窗口结束后应自行收敛")
            assertTrue(process.isAlive, "长驻进程不该被观察器动过")

            val logFile = File(pluginDir, PluginHost.PROCESS_LOG_FILE)
            assertFalse(
                logFile.exists() && logFile.readText().contains("exited immediately"),
                "长驻进程不新增噪音：" + (if (logFile.exists()) logFile.readText() else "<no log>"),
            )
            assertEquals(false, logFile.exists(), "没有任何诊断时不该创建 process.log")
        } finally {
            deleteTree(dataDir)
            deleteTree(searchDir)
        }
    }
}
