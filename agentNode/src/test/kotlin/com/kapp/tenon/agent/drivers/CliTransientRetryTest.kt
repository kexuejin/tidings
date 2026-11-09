// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 瞬时失败自动重试的全组用例：**全程假脚本替代真 CLI**（与 `CliSubprocessDriverTest`
 * 同一纪律，不依赖本机装没装任何 CLI）。
 *
 * 钉的五件事：
 * 1. 瞬时失败（provider 断流标记）自动重试，且 dsh 续跑形态把 `--session-id` 带上重放跳；
 * 2. 连续瞬时失败到上限后终局，Error 带跳数台账，**绝不补 Finished**；
 * 3. 非瞬时失败（业务失败 / 配额耗尽）一次终局，不重试 —— 重试白名单的纪律；
 * 4. spawn 失败按瞬时判，重试到上限后终局；
 * 5. 默认判别函数本身的取值表（配额不在瞬时名单里）。
 */
class CliTransientRetryTest {

    private fun tempDir(): java.io.File = Files.createTempDirectory("cli-retry-test").toFile()

    /** 假脚本（可执行 sh），返回绝对路径。 */
    private fun script(dir: java.io.File, name: String, body: String): String {
        val f = java.io.File(dir, name)
        f.writeText("#!/bin/sh\n$body\n")
        assertTrue(f.setExecutable(true), "假 CLI 必须可执行")
        return f.absolutePath
    }

    /** 裸驱动（重试全默认档 + 注入零退避）：生产判别与上限都走默认值。 */
    private fun driver(cliPath: String, resumeSpec: CliResumeSpec? = null): CliSubprocessDriver =
        CliSubprocessDriver(
            driverId = "test-cli",
            displayName = "Test CLI",
            cliPath = cliPath,
            argSpec = { prompt -> listOf("--json", prompt) },
            retryBackoff = { },
            resumeSpec = resumeSpec,
        )

    private fun runCount(counter: java.io.File): Int =
        counter.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0

    private suspend fun collect(driver: CliSubprocessDriver, prompt: String): Pair<List<DriverEvent>, String> {
        val events = mutableListOf<DriverEvent>()
        val handle = driver.openSession("", null)
        driver.executeTask(handle.sessionId, prompt) { events += it }
        return events to ""
    }

    @Test
    fun transientFailureIsRetriedWithResumeAndFinishes() = runBlocking {
        val dir = tempDir()
        val counter = java.io.File(dir, "count")
        val argsFile = java.io.File(dir, "args.txt")
        val cli = script(
            dir,
            "flaky",
            """
            N=$(cat ${counter.absolutePath} 2>/dev/null || echo 0); N=$((N+1)); echo ${'$'}N > ${counter.absolutePath}
            if [ ${'$'}N -eq 1 ]; then
              echo '{"type":"session","sessionId":"sess-9"}'
              echo 'dsh: STREAM_CLOSED: DeepSeek Messages stream ended before message_stop' >&2
              exit 1
            fi
            printf '%s\n' "${'$'}@" > ${argsFile.absolutePath}
            exit 0
            """.trimIndent(),
        )
        val events = mutableListOf<DriverEvent>()
        val d = driver(cli, resumeSpec = CliSubprocessDriver.DSH_RESUME)
        val handle = d.openSession("", null)
        d.executeTask(handle.sessionId, "do work") { events += it }

        assertTrue(events.filterIsInstance<DriverEvent.Finished>().isNotEmpty(), "重试成功必须 Finished")
        assertFalse(events.filterIsInstance<DriverEvent.Error>().isNotEmpty(), "重试成功不得再报 Error")
        assertEquals(2, runCount(counter), "首跳失败 + 一跳续跑成功")
        val argv = argsFile.readText()
        assertTrue(argv.contains("--session-id"), "续跑跳必须带 --session-id")
        assertTrue(argv.contains("sess-9"), "续跑 id 来自首跳 NDJSON 的 session 事件")
    }

    @Test
    fun transientFailureExhaustsRetriesAndReportsLedger() = runBlocking {
        val dir = tempDir()
        val counter = java.io.File(dir, "count")
        val cli = script(
            dir,
            "always-stream-closed",
            """
            N=$(cat ${counter.absolutePath} 2>/dev/null || echo 0); N=$((N+1)); echo ${'$'}N > ${counter.absolutePath}
            echo 'dsh: STREAM_CLOSED: DeepSeek Messages stream ended before message_stop' >&2
            exit 1
            """.trimIndent(),
        )
        val (events, _) = collect(driver(cli), "do work")

        val errors = events.filterIsInstance<DriverEvent.Error>()
        assertEquals(1, errors.size, "终局只报一次 Error")
        assertTrue(errors[0].message.contains("gave up after 3 attempts"), "Error 必须带跳数台账")
        assertFalse(events.filterIsInstance<DriverEvent.Finished>().isNotEmpty(), "没干成的活绝不补 Finished")
        assertEquals(3, runCount(counter), "首跳 + 2 跳重试 = 3 次")
    }

    @Test
    fun nonTransientFailureIsTerminalWithoutRetry() = runBlocking {
        val dir = tempDir()
        val counter = java.io.File(dir, "count")
        val cli = script(
            dir,
            "real-failure",
            """
            N=$(cat ${counter.absolutePath} 2>/dev/null || echo 0); N=$((N+1)); echo ${'$'}N > ${counter.absolutePath}
            echo 'fatal: bad configuration' >&2
            exit 1
            """.trimIndent(),
        )
        val (events, _) = collect(driver(cli), "do work")

        val errors = events.filterIsInstance<DriverEvent.Error>()
        assertEquals(1, errors.size)
        assertTrue(
            errors[0].message.startsWith("cli exited with code 1"),
            "非瞬时失败的 Error 以退出码开头（实际：${errors[0].message}）",
        )
        assertTrue(
            errors[0].message.contains("fatal: bad configuration"),
            "终局错误必须携带 CLI 自己的 stderr 尾巴——根因可见性",
        )
        assertEquals(1, runCount(counter), "非瞬时失败一次终局，不重试")
    }

    @Test
    fun spawnFailureIsRetriedUntilExhausted() = runBlocking {
        val (events, _) = collect(driver("/nonexistent/cli/path"), "do work")

        val errors = events.filterIsInstance<DriverEvent.Error>()
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("gave up after 3 attempts"), "spawn 失败按瞬时判，重试到上限")
        assertTrue(events.filterIsInstance<DriverEvent.Finished>().isEmpty())
    }

    @Test
    fun defaultTransientPredicateValueTable() {
        // 瞬时白名单内的形态
        assertTrue(
            CliSubprocessDriver.defaultTransientOf(1, "dsh: STREAM_CLOSED: DeepSeek Messages stream ended"),
        )
        assertTrue(CliSubprocessDriver.defaultTransientOf(1, "upstream: Internal Server Error (request_id: x)"))
        assertTrue(CliSubprocessDriver.defaultTransientOf(1, "model is overloaded, try later"))
        // spawn 失败恒判瞬时
        assertTrue(CliSubprocessDriver.defaultTransientOf(CliSubprocessDriver.SPAWN_FAILED_EXIT, ""))
        // 确定性失败绝不重试：配额耗尽是「再试也白试还要花钱」
        assertFalse(
            CliSubprocessDriver.defaultTransientOf(1, "dsh: QUOTA: Insufficient Balance (request_id: x)"),
        )
        assertFalse(CliSubprocessDriver.defaultTransientOf(1, "fatal: bad configuration"))
    }
}
