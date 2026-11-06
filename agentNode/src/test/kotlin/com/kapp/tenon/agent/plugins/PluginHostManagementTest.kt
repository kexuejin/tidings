// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginHostManagementTest {

    private class RecordingSpawner {
        val calls = mutableListOf<List<String>>()
        val fakeProcesses = mutableListOf<FakeProcess>()

        fun spawn(command: List<String>, workDir: File, env: Map<String, String>, logFile: File): FakeProcess {
            calls += command
            val p = FakeProcess()
            fakeProcesses += p
            return p
        }
    }

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
        override fun pid(): Long = 999999L
        override fun getOutputStream() = throw UnsupportedOperationException()
        override fun getInputStream() = throw UnsupportedOperationException()
        override fun getErrorStream() = throw UnsupportedOperationException()
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
    }

    private fun tempPluginData(): File {
        val dir = File.createTempFile("relay-plugin-mgmt-", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        return dir
    }

    private fun writeManifest(dataDir: File, pluginId: String, entrypoint: String) {
        val pDir = File(dataDir, "plugins/$pluginId")
        pDir.mkdirs()
        val manifest = buildJsonObject {
            put("pluginId", pluginId)
            put("name", pluginId)
            put("entrypoint", entrypoint)
        }.toString()
        File(pDir, "manifest.json").writeText(manifest)
    }

    @Test
    fun disabledPluginIsSkippedOnScan() {
        val dir = tempPluginData()
        try {
            writeManifest(dir, "plugin-a", "/bin/echo")
            writeManifest(dir, "plugin-b", "/bin/echo")

            val stateMgr = PluginStateManager(dir)
            stateMgr.setPluginEnabled("plugin-b", false)

            val registry = PluginRegistry(probeIntervalMs = 60_000L)
            val spawner = RecordingSpawner()
            val host = PluginHost(
                dataDirectory = dir,
                controlBaseUrl = { "http://127.0.0.1:9090" },
                plugins = registry,
                spawner = spawner::spawn,
                stateManager = stateMgr,
            )

            val reports = host.start()
            assertEquals(2, reports.size)

            val reportA = reports.first { it.pluginId == "plugin-a" }
            assertTrue(reportA.started)

            val reportB = reports.first { it.pluginId == "plugin-b" }
            assertFalse(reportB.started)
            assertEquals("disabled by state manager", reportB.reason)

            assertEquals(1, spawner.calls.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun dynamicStopAndSpawnCycle() {
        val dir = tempPluginData()
        try {
            writeManifest(dir, "plugin-dynamic", "/bin/echo")

            val stateMgr = PluginStateManager(dir)
            val registry = PluginRegistry(probeIntervalMs = 60_000L)
            val spawner = RecordingSpawner()
            val host = PluginHost(
                dataDirectory = dir,
                controlBaseUrl = { "http://127.0.0.1:9090" },
                plugins = registry,
                spawner = spawner::spawn,
                stateManager = stateMgr,
            )

            // Start normally
            val initialReports = host.start()
            assertTrue(initialReports.first().started)
            assertEquals(1, spawner.fakeProcesses.size)
            val proc = spawner.fakeProcesses.first()
            assertTrue(proc.alive)

            // Stop dynamically
            val stopped = host.stopPlugin("plugin-dynamic")
            assertTrue(stopped)
            assertFalse(proc.alive)

            // Disable in state manager
            stateMgr.setPluginEnabled("plugin-dynamic", false)

            // Attempt to spawn while disabled -> skipped
            val skippedReport = host.spawnPlugin("plugin-dynamic")
            assertFalse(skippedReport.started)
            assertEquals("disabled by state manager", skippedReport.reason)

            // Re-enable in state manager and spawn
            stateMgr.setPluginEnabled("plugin-dynamic", true)
            val respawnReport = host.spawnPlugin("plugin-dynamic")
            assertTrue(respawnReport.started)
            assertEquals(2, spawner.fakeProcesses.size)
            assertTrue(spawner.fakeProcesses.last().alive)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun orphanPidFileIsCleanedUpOnStartup() {
        val dir = tempPluginData()
        try {
            writeManifest(dir, "plugin-c", "/bin/echo")
            val pluginDir = File(dir, "plugins/plugin-c")
            val pidFile = File(pluginDir, "process.pid")
            // Write a non-existent dead PID
            pidFile.writeText("999999999")
            assertTrue(pidFile.exists())

            val registry = PluginRegistry(probeIntervalMs = 60_000L)
            val spawner = RecordingSpawner()
            val host = PluginHost(
                dataDirectory = dir,
                controlBaseUrl = { "http://127.0.0.1:9090" },
                plugins = registry,
                spawner = spawner::spawn,
            )

            // Starting the host cleans up orphan PID file
            host.start()
            // New PID from spawned process is written
            assertTrue(pidFile.exists())
            assertEquals("999999", pidFile.readText().trim())

            // Stopping removes PID file
            host.stop()
            assertFalse(pidFile.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
