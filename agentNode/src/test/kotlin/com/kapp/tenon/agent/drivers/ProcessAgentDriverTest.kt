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

        /** 最近一次 `waitFor(timeout, unit)` 要的预算（ms）。用来钉「没有在等 300 秒」。 */
        @Volatile var waitForTimeoutMs: Long? = null

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutText.toByteArray(StandardCharsets.UTF_8))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(stderrText.toByteArray(StandardCharsets.UTF_8))

        override fun waitFor(): Int {
            if (sleepMs > 0) Thread.sleep(sleepMs)
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitForTimeoutMs = unit.toMillis(timeout)
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

    /** 会话对齐键（§9 阶段三）：宿主派起的 CLI 能在 hook 脚本里报出自己的 sessionId。 */
    @Test
    fun hookSessionIdEnvIsInjectedForAlignment() {
        runBlocking {
            var capturedEnv: Map<String, String>? = null
            val driver = ProcessAgentDriver(
                driverId = "env-agent",
                displayName = "Env",
                commandTemplate = listOf("agent"),
                streamFormat = ProcessAgentDriver.FORMAT_TENON_EVENT_STREAM,
                spawner = { _, _, env ->
                    capturedEnv = env
                    MockProcess(stdoutText = "{\"type\":\"end\"}")
                },
            )
            val handle = driver.openSession("/tmp/ws", "sess-env-9")
            driver.executeTask(handle.sessionId, "run") { }
            assertEquals(
                "sess-env-9",
                capturedEnv?.get(DriverKeys.ENV_HOOK_SESSION_ID),
                "TENON_HOOK_SESSION_ID 必须注入子进程环境（hook 脚本靠它透传对齐键）",
            )
        }
    }

    /** 单行熔断（§8.2 传输防火墙）：超限行响亮失败并停止消费，不整行进堆。 */
    @Test
    fun anOversizedLineCircuitBreaksWithAnErrorEvent() {
        runBlocking {
            // 256KiB + 1 字符的单行——没有熔断时它会被 readLine 整行读进宿主堆。
            val oversized = "x".repeat(DriverLineLimits.MAX_LINE_CHARS + 1)
            val driver = ProcessAgentDriver(
                driverId = "flood-agent",
                displayName = "Flood",
                commandTemplate = listOf("agent"),
                streamFormat = ProcessAgentDriver.FORMAT_TENON_EVENT_STREAM,
                spawner = { _, _, _ -> MockProcess(stdoutText = oversized) },
            )

            val handle = driver.openSession("/tmp/ws", "sess-flood")
            val events = mutableListOf<DriverEvent>()
            driver.executeTask(handle.sessionId, "run") { events.add(it) }

            // 熔断错误先于合成 Finished（进程自然退出后生命周期照常收口）。
            val error = assertIs<DriverEvent.Error>(events.first())
            assertTrue(error.message.contains("flood-agent"), "失败信息要点名失控的驱动：" + error.message)
            assertTrue(
                error.message.contains(DriverLineLimits.MAX_LINE_CHARS.toString()),
                "失败信息要给出上限数值：" + error.message,
            )
            assertTrue(events.count { it is DriverEvent.Error } == 1, "一次熔断只报一次错")
        }
    }

    /**
     * 反向钉子：超限必须**真的**收场，不能「break 之后等 300 秒」。
     *
     * 旧实现的注释写着 "process will be reaped"，实际只 `break` 出读循环，紧接着落到
     * `waitFor(timeoutSec = 300)` —— 一条超长行换来 **5 分钟静默挂起**，而且进程还活着。
     * 判据不看耗时（耗时判据在慢机器上会假绿），只看它**向进程要了多长的预算**：
     * 旧实现是 300_000ms，新实现是杀进程后的 2_000ms 收尸等待。
     */
    @Test
    fun anOversizedLineKillsTheProcessInsteadOfWaitingForTheTimeout() {
        runBlocking {
            val oversized = "x".repeat(DriverLineLimits.MAX_LINE_CHARS + 1)
            lateinit var proc: MockProcess
            val driver = ProcessAgentDriver(
                driverId = "flood-agent",
                displayName = "Flood",
                commandTemplate = listOf("agent"),
                streamFormat = ProcessAgentDriver.FORMAT_TENON_EVENT_STREAM,
                spawner = { _, _, _ -> MockProcess(stdoutText = oversized).also { proc = it } },
            )

            val handle = driver.openSession("/tmp/ws", "sess-flood-kill")
            val events = mutableListOf<DriverEvent>()
            driver.executeTask(handle.sessionId, "run") { events.add(it) }

            assertTrue(proc.killed, "超限必须真的杀进程——旧实现只 break，注释里的 reaped 是假的")
            assertEquals(
                2_000L,
                proc.waitForTimeoutMs,
                "杀进程后只允许等 2 秒收尸；等到 300 秒就是旧缺陷（一条超长行换来 5 分钟挂起）",
            )
            val error = assertIs<DriverEvent.Error>(events.first())
            assertTrue(error.message.contains("killed"), "失败信息要说明进程已被杀：" + error.message)
        }
    }

    /**
     * stderr 超长行**不**该杀进程：诊断输出不能决定任务生死。
     *
     * 但同样不能整行进堆 —— 一个往 stderr 刷 1 GB 单行的进程照样能 OOM 宿主。
     * 判据：进程不被杀、任务照常 Finished、stderr 里留下一条「已丢弃」的痕迹。
     */
    @Test
    fun anOversizedStderrLineIsDroppedButDoesNotKillTheProcess() {
        runBlocking {
            val oversized = "e".repeat(DriverLineLimits.MAX_LINE_CHARS + 1)
            lateinit var proc: MockProcess
            val driver = ProcessAgentDriver(
                driverId = "noisy-agent",
                displayName = "Noisy",
                commandTemplate = listOf("agent"),
                streamFormat = ProcessAgentDriver.FORMAT_TENON_EVENT_STREAM,
                spawner = { _, _, _ ->
                    MockProcess(stdoutText = "", stderrText = oversized + "\n", exitCode = 1).also { proc = it }
                },
            )

            val handle = driver.openSession("/tmp/ws", "sess-noisy")
            val events = mutableListOf<DriverEvent>()
            driver.executeTask(handle.sessionId, "run") { events.add(it) }

            assertTrue(!proc.killed, "stderr 超长只丢内容，不杀进程")
            val error = assertIs<DriverEvent.Error>(events.last())
            assertTrue(
                error.message.contains("dropped"),
                "stderr 尾部要如实说明那一行被丢弃（而不是装作没发生）：" + error.message,
            )
        }
    }
}
