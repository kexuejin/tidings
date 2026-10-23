// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.agent.drivers.DriverEvent
import com.kapp.tidings.agent.drivers.NativeRelayDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 本机 native 驱动的诚信（问题 4 那句话的来源）。
 *
 * 真机现场：群里 `@dsh` 之后收到一条「执行完毕：本地任务已成功处理。」，而
 * `NativeRelayDriver.executeTask` 里**没有任何一行真的执行**（没有进程、没有文件读写）。
 * 那是本仓反复禁止的「为没发生过的活报完成」，也是用户以为"手机在本地 mock 执行"的原因。
 *
 * 这份测试与 `DriverHonestyTest`（演示驱动）、`DriverTest` 是一组：
 * 驱动可以没有运行时，但**不许**把"没干活"说成"完成"。
 */
class NativeRelayDriverHonestyTest {

    private fun run(prompt: String): List<DriverEvent> = runBlocking {
        val events = mutableListOf<DriverEvent>()
        NativeRelayDriver().executeTask("s-honesty", prompt) { events += it }
        events
    }

    private fun visibleText(events: List<DriverEvent>): String =
        events.filterIsInstance<DriverEvent.TokenDelta>().joinToString("") { it.delta } +
            events.filterIsInstance<DriverEvent.ThoughtDelta>().joinToString("") { it.delta }

    @Test
    fun theNativeDriverNeverClaimsCompletion() {
        val text = visibleText(run("帮我看看这个目录"))
        assertFalse(text.contains("执行完毕"), "这句原话就是这次要删掉的假完成：" + text)
        assertFalse(text.contains("已成功处理"), "没有执行运行时，不许说任务成功了：" + text)
        assertTrue(text.contains("没有执行"), "必须如实说明这次没执行任何命令：" + text)
    }

    /**
     * 工具卡片也一样：原来那张 `bash` 卡片看起来像真的跑过 `ls -la`，
     * 现在必须标为示例·未执行（与 DsBotDriver 同一口径）。
     */
    @Test
    fun toolCardsAreMarkedAsExamplesNotExecuted() {
        val calls = run("please list files").filterIsInstance<DriverEvent.ToolCall>()
        assertTrue(calls.isNotEmpty(), "list 这条会发一张示例卡片")
        for (call in calls) {
            assertTrue(call.tool.contains("未执行"), "工具卡片必须标为未执行：" + call.tool)
        }
    }

    /** 没调用模型就不该报 token；完成也要如实标成 no-runtime，而不是一次 succeeded。 */
    @Test
    fun theFinishEventIsHonest() {
        val finished = run("hi").filterIsInstance<DriverEvent.Finished>().single()
        assertEquals(0, finished.totalTokens, "没有调用模型就不该报 token 数")
        assertEquals("no-runtime", finished.finishReason, "这不是一次成功完成，是没有运行时可用")
    }

    /** ACL 仍然是真的约束：bash 被关掉时，list 任务必须报错而不是假装跑过。 */
    @Test
    fun aDisabledCapabilityStillFailsLoudly() {
        val events = runBlocking {
            val driver = NativeRelayDriver()
            driver.updateCapabilityAcl(setOf("fs_read"))
            val out = mutableListOf<DriverEvent>()
            driver.executeTask("s-acl", "please list files") { out += it }
            out
        }
        assertTrue(
            events.any { it is DriverEvent.Error && it.message.contains("bash") },
            "禁用 bash 后必须报 ACL 错误",
        )
    }
}
