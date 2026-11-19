// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ProbeProcess] 的判据：**超时真的会杀进程**，而不是「读完才判超时」。
 *
 * 旧写法（`readText()` 之后 `waitFor(timeout)`）在这里必红：一个不肯关 stdout 的进程会
 * 永远阻塞在读上，用例跑不完。所以下面那条超时用例本身就是回归钉子 —— 它挂住 = 缺陷回来了。
 */
class ProbeProcessTest {

    @Test
    fun readsUntilEofAndCarriesTheExitCode() = kotlinx.coroutines.runBlocking {
        val result = ProbeProcess.run(listOf("/bin/sh", "-c", "echo probe-ok"), timeoutMs = 5_000)
        assertFalse(result.timedOut, "正常退出的进程不该被判超时")
        assertEquals(0, result.exitCode)
        assertTrue(result.text.contains("probe-ok"), "输出必须收全，实际：" + result.text)
    }

    @Test
    fun killsAProcessThatNeverClosesItsOutput() = kotlinx.coroutines.runBlocking {
        val started = System.nanoTime()
        val result = ProbeProcess.run(listOf("/bin/sleep", "30"), timeoutMs = 400)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(result.timedOut, "挂住的进程必须被判超时（读完之后才判 = 等于没超时）")
        assertEquals(ProbeProcess.KILLED_EXIT, result.exitCode)
        assertTrue(elapsedMs < 10_000, "看门狗必须真的杀掉它，实际等了 " + elapsedMs + "ms")
    }

    @Test
    fun runsInTheGivenDirectory() = kotlinx.coroutines.runBlocking {
        val dir = Files.createTempDirectory("probe-cwd").toRealPath().toFile()
        val result = ProbeProcess.run(listOf("/bin/pwd"), timeoutMs = 5_000, dir = dir)
        assertEquals(
            dir.absolutePath,
            result.text.trim(),
            "探测的 cwd 必须受控：各家的凭证配置在用户目录，别让调用方的临时 cwd 影响判定",
        )
    }
}
