// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessAgentDriverTest {

    private class MockProcess(
        val stdoutText: String = "",
        val stderrText: String = "",
        val exitCode: Int = 0,
        val sleepMs: Long = 0L,
    ) : Process() {
        @Volatile var killed = false

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutText.toByteArray(StandardCharsets.UTF_8))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(stderrText.toByteArray(StandardCharsets.UTF_8))

        override fun waitFor(): Int {
            if (sleepMs > 0) Thread.sleep(sleepMs)
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (sleepMs > 0) {
                Thread.sleep(minOf(sleepMs, unit.toMillis(timeout)))
                return sleepMs <= unit.toMillis(timeout)
            }
            return true
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            killed = true
        }

        override fun destroyForcibly(): Process {
            killed = true
            return this
        }
    }

    @Test
    fun placeholderAndEnvironmentAreCorrectlyInjected() {
        runBlocking {
            var capturedCommand: List<String>? = null
            var capturedWorkDir: File? = null
            var capturedEnv: Map<String, String>? = null

            val tempWs = File.createTempFile("ws-test-", "").let { f ->
                f.delete()
                f.mkdirs()
                f
            }

            try {
                val driver = ProcessAgentDriver(
                    driverId = "custom-agent",
                    displayName = "Custom Agent",
                    commandTemplate = listOf("python3", "main.py", "--prompt", "{prompt}", "--sid", "{session}"),
                    workingDirTemplate = "{workspace}",
                    envOverrides = mapOf("api_key" to "my-super-secret-token"),
                    spawner = { cmd, workDir, env ->
                        capturedCommand = cmd
                        capturedWorkDir = workDir
                        capturedEnv = env
                        MockProcess(stdoutText = "{\"type\":\"message\",\"text\":\"ok\"}\n")
                    },
                )

                val handle = driver.openSession(tempWs.absolutePath, "sess-100")
                assertEquals("sess-100", handle.sessionId)

                val events = mutableListOf<DriverEvent>()
                driver.executeTask("sess-100", "hello world") { events.add(it) }

                // Assert placeholders substituted
                assertEquals(
                    listOf("python3", "main.py", "--prompt", "hello world", "--sid", "sess-100"),
                    capturedCommand,
                )
                // Assert working dir
                assertEquals(tempWs.canonicalPath, capturedWorkDir?.canonicalPath)

                // Assert secret injected via environment, not command args
                assertEquals("my-super-secret-token", capturedEnv?.get("TENON_SECRET_API_KEY"))
                assertTrue(capturedCommand?.none { it.contains("my-super-secret-token") } == true)

                // Events assert
                assertTrue(events.any { it is DriverEvent.TokenDelta && it.delta == "ok" })
                assertTrue(events.any { it is DriverEvent.Finished })
            } finally {
                tempWs.deleteRecursively()
            }
        }
    }

    @Test
    fun tenonJsonLinesEventsAreParsedIntoSevenEventVocabulary() {
        runBlocking {
            val jsonLines = """
                {"type":"thought","text":"analyzing repository"}
                {"type":"message","text":"found bug in module"}
                {"type":"tool","tool":"read_file","callId":"call-99","input":"{\"path\":\"a.kt\"}"}
                {"type":"card","cardJson":"{\"kind\":\"ask\"}"}
                {"type":"artifact","path":"build/out.diff","name":"out.diff","bytes":1024,"mime":"text/plain"}
                {"type":"end"}
            """.trimIndent()

            val driver = ProcessAgentDriver(
                driverId = "event-agent",
                displayName = "Event Agent",
                commandTemplate = listOf("agent"),
                streamFormat = ProcessAgentDriver.FORMAT_TENON_EVENT_STREAM,
                spawner = { _, _, _ -> MockProcess(stdoutText = jsonLines) },
            )

            val handle = driver.openSession("/tmp/ws", "sess-2")
            val events = mutableListOf<DriverEvent>()
            driver.executeTask(handle.sessionId, "run") { events.add(it) }

            assertEquals(6, events.size)

            val thought = assertIs<DriverEvent.ThoughtDelta>(events[0])
            assertEquals("analyzing repository", thought.delta)

            val token = assertIs<DriverEvent.TokenDelta>(events[1])
            assertEquals("found bug in module", token.delta)

            val tool = assertIs<DriverEvent.ToolCall>(events[2])
            assertEquals("read_file", tool.tool)
            assertEquals("call-99", tool.toolCallId)

            val card = assertIs<DriverEvent.Card>(events[3])
            assertEquals("{\"kind\":\"ask\"}", card.cardJson)

            val artifact = assertIs<DriverEvent.Artifact>(events[4])
            assertEquals("build/out.diff", artifact.path)
            assertEquals(1024L, artifact.bytes)

            assertIs<DriverEvent.Finished>(events[5])
        }
    }

    @Test
    fun rawTextFormatStreamsLinesAsTokenDeltas() {
        runBlocking {
            val rawLines = "First line\nSecond line\n"
            val driver = ProcessAgentDriver(
                driverId = "raw-agent",
                displayName = "Raw Agent",
                commandTemplate = listOf("echo"),
                streamFormat = ProcessAgentDriver.FORMAT_RAW_TEXT,
                spawner = { _, _, _ -> MockProcess(stdoutText = rawLines) },
            )

            val handle = driver.openSession("/tmp/ws", "sess-3")
            val events = mutableListOf<DriverEvent>()
            driver.executeTask(handle.sessionId, "run") { events.add(it) }

            assertTrue(events.any { it is DriverEvent.TokenDelta && it.delta.contains("First line") })
            assertTrue(events.any { it is DriverEvent.TokenDelta && it.delta.contains("Second line") })
            assertTrue(events.any { it is DriverEvent.Finished })
        }
    }

    @Test
    fun nonZeroExitCodeReportsErrorWithStderrTail() {
        runBlocking {
            val driver = ProcessAgentDriver(
                driverId = "failing-agent",
                displayName = "Failing Agent",
                commandTemplate = listOf("fail"),
                spawner = { _, _, _ ->
                    MockProcess(
                        stderrText = "Error: module not found on line 42",
                        exitCode = 127,
                    )
                },
            )

            val handle = driver.openSession("/tmp/ws", "sess-4")
            val events = mutableListOf<DriverEvent>()
            driver.executeTask(handle.sessionId, "run") { events.add(it) }

            val error = assertIs<DriverEvent.Error>(events.last())
            assertTrue(error.message.contains("127"))
            assertTrue(error.message.contains("module not found"))
        }
    }

    @Test
    fun timeoutKillsProcessAndReportsError() {
        runBlocking {
            var killed = false
            val driver = ProcessAgentDriver(
                driverId = "slow-agent",
                displayName = "Slow Agent",
                commandTemplate = listOf("sleep"),
                timeoutSec = 1L, // 1 second timeout
                spawner = { _, _, _ ->
                    val p = MockProcess(sleepMs = 5000L) // 5s sleep exceeds 1s timeout
                    killed = p.killed
                    p
                },
            )

            val handle = driver.openSession("/tmp/ws", "sess-5")
            val events = mutableListOf<DriverEvent>()
            driver.executeTask(handle.sessionId, "run") { events.add(it) }

            val error = assertIs<DriverEvent.Error>(events.last())
            assertTrue(error.message.contains("timed out after 1s"))
        }
    }
}
