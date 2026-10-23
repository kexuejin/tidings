// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilitySettingsScreenTest {

    @Test
    fun testCapabilityItemStateToggle() {
        val activeAcl = mutableMapOf(
            "bash" to true,
            "filesystem" to true,
            "codesearch" to true,
            "websearch" to true,
        )

        fun onToggle(id: String, enabled: Boolean) {
            activeAcl[id] = enabled
        }

        // Initially bash is allowed
        assertTrue(activeAcl["bash"] == true)

        // Remote toggle to disable bash
        onToggle("bash", false)
        assertEquals(false, activeAcl["bash"])

        // Remote toggle to re-enable
        onToggle("bash", true)
        assertEquals(true, activeAcl["bash"])
    }
}
