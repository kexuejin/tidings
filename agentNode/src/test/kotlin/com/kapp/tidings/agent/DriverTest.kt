// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.agent.drivers.DriverEvent
import com.kapp.tidings.agent.drivers.DriverRegistry
import com.kapp.tidings.agent.drivers.DsBotDriver
import com.kapp.tidings.agent.drivers.NativeRelayDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriverTest {

    @Test
    fun testNativeRelayDriverAclAndExecution() = runBlocking {
        val driver = NativeRelayDriver()
        assertTrue(driver.isCapabilityAllowed("bash"))

        // Disable bash
        driver.updateCapabilityAcl(setOf("fs_read", "fs_write"))
        assertEquals(false, driver.isCapabilityAllowed("bash"))

        val events = mutableListOf<DriverEvent>()
        driver.executeTask("sess-1", "please list files", onEvent = { events.add(it) })

        // Because bash was disabled, an Error event is dispatched
        val hasAclError = events.any { it is DriverEvent.Error && it.message.contains("bash") }
        assertTrue(hasAclError, "Disabling bash should trigger an ACL violation error")
    }

    @Test
    fun testDsBotDriverThinkingDecouplingAndToolCall() = runBlocking {
        val driver = DsBotDriver()
        val events = mutableListOf<DriverEvent>()
        driver.executeTask("sess-2", "please search Kotlin P2P", onEvent = { events.add(it) })

        val hasThought = events.any { it is DriverEvent.ThoughtDelta }
        // task-58：工具名上必须带「示例·未执行」——这条断言原来钉的是"发一张 websearch 卡片"，
        // 那等于要求模拟器继续假装执行。现在钉的是**如实标注**。
        val hasTool = events.any { it is DriverEvent.ToolCall && it.tool.startsWith("websearch") && it.tool.contains("未执行") }
        val hasToken = events.any { it is DriverEvent.TokenDelta }
        val hasFinish = events.any { it is DriverEvent.Finished }

        assertTrue(hasThought, "DsBotDriver must emit ThoughtDelta")
        assertTrue(hasTool, "DsBotDriver must emit ToolCall")
        assertTrue(hasToken, "DsBotDriver must emit TokenDelta")
        assertTrue(hasFinish, "DsBotDriver must emit Finished")
    }

    @Test
    fun testDriverRegistryRouting() = runBlocking {
        val registry = DriverRegistry()
        val nativeDriver = registry.getDriver("native")
        val dsDriver = registry.getDriver("ds-bot")

        assertEquals("native", nativeDriver?.driverId)
        assertEquals("ds-bot", dsDriver?.driverId)

        val caps = registry.listAllCapabilities()
        assertTrue(caps.isNotEmpty())
    }
    /**
     * 演示驱动的输出**不许**出现完成断言（task-58 驱动诚信）。
     *
     * 这条与 DriverHonestyTest 是一对：那条钉「默认不选它」，这条钉「选了也不许说干完了」。
     */
    @Test
    fun theDemoDriverNeverClaimsCompletion() = runBlocking {
        val texts = StringBuilder()
        DsBotDriver().executeTask("s-honesty", "search 一下") { event ->
            when (event) {
                is DriverEvent.TokenDelta -> texts.append(event.delta)
                is DriverEvent.ThoughtDelta -> texts.append(event.delta)
                else -> Unit
            }
        }
        val all = texts.toString()
        assertTrue(!all.contains("完成"), "演示输出不许断言完成：" + all)
        assertTrue(!all.contains("正常执行完毕"), "这句原话必须删掉：" + all)
        assertTrue(all.contains("演示"), "必须如实说明是演示：" + all)
    }
}
