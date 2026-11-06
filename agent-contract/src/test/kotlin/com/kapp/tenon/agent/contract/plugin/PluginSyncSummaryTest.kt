// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.contract.plugin

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginSyncSummaryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun serializesAndDeserializesPluginSyncSummary() {
        val sample = AgentPluginSyncPayload(
            plugins = listOf(
                PluginSummaryItem(id = "claude-code", name = "Claude Code", type = "agent", ready = true),
                PluginSummaryItem(id = "code-autofix", name = "Code Auto Fix", type = "workflow", ready = true),
            ),
        )

        val encoded = json.encodeToString(sample)
        val decoded = json.decodeFromString<AgentPluginSyncPayload>(encoded)
        assertEquals(sample, decoded)

        // Ensure frame budget is strictly within 1 KiB
        val bytes = encoded.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes <= AgentPluginSyncPayload.MAX_SYNC_BYTES, "Sync frame $bytes bytes must be <= 1 KiB")
    }
}
