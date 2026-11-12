// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D18：群里点名的 token（席位全名）→ 在位档案键的**唯一解析口**。
 *
 * 触发判据按 `virtualAgents[].name` 认人，执行侧按档案键找驱动；这中间少一层归位，
 * 症状是「@ 了、帧到了、零工单卡」——两侧各自都"看起来对"。
 */
class AgentProfilesResolveKeyTest {

    private val roster = setOf("dsh", "codex", "opencode")

    @Test
    fun plainKeysAndCaseVariantsResolveExactly() {
        assertEquals("dsh", AgentProfiles.resolveKey("dsh", roster))
        assertEquals("dsh", AgentProfiles.resolveKey("DSH", roster))
        assertEquals("codex", AgentProfiles.resolveKey("codex-cli", roster))
    }

    @Test
    fun seatStyleNamesResolveToTheirLongestInRosterPrefix() {
        assertEquals("dsh", AgentProfiles.resolveKey("dsh-a030882e", roster))
        assertEquals("dsh", AgentProfiles.resolveKey("dsh-a030882e-default-workspace", roster))
        assertEquals("codex", AgentProfiles.resolveKey("codex-7f-teahouse", roster))
    }

    @Test
    fun unknownKeysNeverGuess() {
        assertNull(AgentProfiles.resolveKey("zygote-123", roster))
        assertNull(AgentProfiles.resolveKey("dsh_extra", roster))
        assertNull(AgentProfiles.resolveKey("dshx", roster))
        assertNull(AgentProfiles.resolveKey("", roster))
        assertNull(AgentProfiles.resolveKey(null, roster))
        assertNull(AgentProfiles.resolveKey("dsh", emptySet()), "不在位就不许解析出来")
    }

    @Test
    fun dynamicRegisteredProfilesResolveAndProvideVendor() {
        val customProfile = AgentProfiles.Profile(
            key = "my-custom-agent",
            vendor = "custom-vendor",
            displayName = "Custom AI",
            defaultRole = AgentProfiles.Role.BOTH,
        )
        AgentProfiles.registerProfile(customProfile)
        try {
            assertEquals("my-custom-agent", AgentProfiles.normalizeKey("my-custom-agent"))
            assertEquals("custom-vendor", AgentProfiles.vendorOf("my-custom-agent"))
            assertEquals("my-custom-agent", AgentProfiles.resolveKey("my-custom-agent", setOf("my-custom-agent")))
        } finally {
            AgentProfiles.unregisterProfile("my-custom-agent")
        }
        assertNull(AgentProfiles.normalizeKey("my-custom-agent"))
    }
}
