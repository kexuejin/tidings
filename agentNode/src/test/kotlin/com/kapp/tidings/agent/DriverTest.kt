// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.agent.drivers.DriverEvent
import com.kapp.tidings.agent.drivers.DriverRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriverTest {

    /**
     * 能力 ACL 收归宿主（M2）后的治理语义不变：宿主禁用 bash ⇒ native 驱动
     * 对 bash 类任务回报 ACL 错误，而不是静默执行。名单经 [DriverRegistry] 写入
     * （原先是直接调驱动上的 updateCapabilityAcl —— 该成员已从接口移除）。
     */
    @Test
    fun testNativeRelayDriverAclAndExecution() = runBlocking {
        val registry = DriverRegistry()
        val driver = registry.getDriver("native")!!
        assertTrue(registry.isCapabilityAllowed("native", "bash"))

        // Disable bash on the host side
        registry.updateCapabilityAcl("native", setOf("fs_read", "fs_write"))
        assertEquals(false, registry.isCapabilityAllowed("native", "bash"))

        val events = mutableListOf<DriverEvent>()
        driver.executeTask("sess-1", "please list files", onEvent = { events.add(it) })

        // Because bash was disabled, an Error event is dispatched
        val hasAclError = events.any { it is DriverEvent.Error && it.message.contains("bash") }
        assertTrue(hasAclError, "Disabling bash should trigger an ACL violation error")
    }

    @Test
    fun testDriverRegistryRouting() = runBlocking {
        val registry = DriverRegistry()
        val nativeDriver = registry.getDriver("native")

        assertEquals("native", nativeDriver?.driverId)
        // 未知 id 回落到默认驱动（native），而不是空指针。
        assertEquals("native", registry.getDriver("no-such-driver")?.driverId)
        assertEquals("native", registry.getDriver(null)?.driverId)

        val caps = registry.listAllCapabilities()
        assertTrue(caps.isNotEmpty())
    }
}
