// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 裸路径的**墙钟止损**：CLI 挂住时，driver 必须自己收场。
 *
 * 以前这条路径只有 `proc.waitFor()`（无参 = 永久等待）：CLI 不关 stdout、也不退出，
 * 读行永远阻塞，工单永远 busy。沙箱路径却有 30 min 上限 —— 两条路的差别没有任何一处
 * 会显示出来，所以这里钉两件事：**会超时**、**超时后不重试**。
 *
 * 变异探针：把看门狗那段删掉（退回裸 `proc.waitFor()`），本用例会在读行上挂住直到
 * `sleep` 自己结束，`elapsed` 断言与「只报一条 Error」同时红。
 */
class CliSubprocessTimeoutTest {

    @Test
    fun aHungCliIsKilledAndReportedWithoutRetrying() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<DriverEvent>()
        val driver = CliSubprocessDriver(
            driverId = "timeout-probe",
            displayName = "Timeout Probe",
            cliPath = "/bin/sleep",
            argSpec = { _, _ -> listOf("30") },
            // 三跳都配满：若超时被当成瞬时失败，报错会变成 3 条、耗时变成 3 个窗口。
            maxExtraAttempts = 2,
            retryBackoff = { },
            timeoutMs = 400,
        )
        val handle = driver.openSession("/tmp", null)
        val started = System.nanoTime()
        driver.executeTask(handle.sessionId, "probe") { events += it }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val errors = events.filterIsInstance<DriverEvent.Error>()
        assertEquals(1, errors.size, "超时必须是一次终局，不许重试；events=" + events.joinToString { it.toString() })
        assertTrue(
            errors.single().message.contains("still running after 400ms"),
            "报错必须如实说是超时、并带上窗口长度，实际：" + errors.single().message,
        )
        assertTrue(events.none { it is DriverEvent.Finished }, "被杀掉的进程绝不能报完成")
        assertTrue(elapsedMs < 10_000, "止损必须真的发生，实际等了 " + elapsedMs + "ms")
    }

    /** 反向：正常退出的进程不受看门狗影响（别把「有超时」写成「动不动就超时」）。 */
    @Test
    fun aNormalExitStillFinishes() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<DriverEvent>()
        val driver = CliSubprocessDriver(
            driverId = "quick-probe",
            displayName = "Quick Probe",
            cliPath = "/bin/sh",
            argSpec = { _, _ -> listOf("-c", "true") },
            timeoutMs = 10_000,
        )
        val handle = driver.openSession("/tmp", null)
        driver.executeTask(handle.sessionId, "probe") { events += it }
        assertTrue(events.any { it is DriverEvent.Finished }, "正常退出必须报 Finished")
        assertTrue(events.none { it is DriverEvent.Error }, "正常退出不许有 Error")
    }
}
