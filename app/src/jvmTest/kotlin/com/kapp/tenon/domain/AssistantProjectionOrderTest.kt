// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantProjectionOrderTest {

    @Test
    fun assistantIsAlwaysTopPinnedAndPureFunctionWithZeroTimestamp() {
        val chatSlice = ChatSlice()
        val agentSlice = AgentSlice()
        val nodeSlice = NodeSlice()
        val settingsSlice = SettingsSlice(settings = RelaySettings(nodeId = "self-node-1", assistantEnabled = true))

        val convList = conversations(
            chat = chatSlice,
            agent = agentSlice,
            node = nodeSlice,
            settingsSlice = settingsSlice,
        )

        assertTrue(convList.isNotEmpty(), "Conversations list must not be empty")
        val topConv = convList.first()

        assertEquals(ConvId.ASSISTANT, topConv.convId)
        assertTrue(topConv.isAssistant)
        assertTrue(topConv.isPinned)
        assertTrue(topConv.isAgent)
        assertEquals(0L, topConv.at, "Timestamp must be 0L when no messages exist, preserving pure function determinism")
    }

    @Test
    fun assistantDisabledHidesAssistantRowEvenWithCustomNameOrRole() {
        val chatSlice = ChatSlice()
        val agentSlice = AgentSlice()
        val nodeSlice = NodeSlice()
        val settingsSlice = SettingsSlice(
            settings = RelaySettings(
                nodeId = "self-node-1",
                assistantEnabled = false,
                assistantCustomName = "Ghost Assistant",
                assistantRoleKey = "researcher",
            ),
        )

        val convList = conversations(
            chat = chatSlice,
            agent = agentSlice,
            node = nodeSlice,
            settingsSlice = settingsSlice,
        )

        assertFalse(
            convList.any { it.isAssistant || it.convId == ConvId.ASSISTANT },
            "When assistantEnabled = false, assistant row must never appear even if customName or role is set",
        )
    }

    @Test
    fun assistantEnabledPlacesAssistantAtTopAboveOtherConversations() {
        val chatSlice = ChatSlice(
            conversationSummaries = listOf(
                ConversationSummaryRow(
                    convId = "peer-1",
                    lastMessageId = "msg-1",
                    lastTextPreview = "Recent message",
                    lastMessageAt = 10_000L,
                    lastMessageIsMine = false,
                ),
            ),
        )
        val agentSlice = AgentSlice()
        val nodeSlice = NodeSlice(
            peers = listOf(
                com.kapp.tenon.domain.model.PeerUi(
                    nodeId = "peer-1",
                    nick = "Alice",
                    platform = "macos",
                    caps = emptyList(),
                    online = true,
                ),
            ),
        )
        val settingsSlice = SettingsSlice(
            settings = RelaySettings(
                nodeId = "self-node-1",
                assistantEnabled = true,
                assistantCustomName = "My Helper",
            ),
        )

        val convList = conversations(
            chat = chatSlice,
            agent = agentSlice,
            node = nodeSlice,
            settingsSlice = settingsSlice,
        )

        assertEquals(2, convList.size)
        val top = convList[0]
        val second = convList[1]

        assertEquals(ConvId.ASSISTANT, top.convId)
        assertEquals("My Helper", top.title)
        assertTrue(top.isAssistant)
        assertTrue(top.isPinned)

        assertEquals("peer-1", second.convId)
        assertEquals("Alice", second.title)
    }
}
