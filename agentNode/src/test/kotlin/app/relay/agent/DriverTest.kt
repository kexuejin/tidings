// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.agent.drivers.DriverEvent
import app.relay.agent.drivers.DriverRegistry
import app.relay.agent.drivers.DsBotDriver
import app.relay.agent.drivers.NativeRelayDriver
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
        val hasTool = events.any { it is DriverEvent.ToolCall && it.tool == "websearch" }
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
}
