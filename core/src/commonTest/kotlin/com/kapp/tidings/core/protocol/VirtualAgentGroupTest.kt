// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualAgentGroupTest {

    @Test
    fun testVirtualAgentGroupMetaSerialization() {
        val virtualAgent = VirtualAgentMember(
            agentId = "coder-01",
            hostNodeId = "node-macbook",
            name = "Code Assistant",
        )
        val group = GroupMeta(
            groupId = "group-100",
            name = "Project Team",
            members = listOf("node-macbook", "node-phone"),
            rev = 1,
            updatedBy = "node-macbook",
            updatedTs = 1000L,
            virtualAgents = listOf(virtualAgent),
        )

        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val serialized = json.encodeToString(GroupMeta.serializer(), group)
        val decoded = json.decodeFromString(GroupMeta.serializer(), serialized)

        assertEquals("Project Team", decoded.name)
        assertEquals(1, decoded.virtualAgents?.size)
        assertEquals("coder-01", decoded.virtualAgents?.first()?.agentId)
        assertEquals("node-macbook", decoded.virtualAgents?.first()?.hostNodeId)

        assertTrue(decoded.isVirtualAgent("coder-01"))
        assertTrue(decoded.isVirtualAgent("agent:node-macbook:coder-01"))
        assertFalse(decoded.isVirtualAgent("node-phone"))
        assertEquals("node-macbook", decoded.hostNodeOf("coder-01"))
    }
}
