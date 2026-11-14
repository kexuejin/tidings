// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConvIdAssistantGuardTest {

    @Test
    fun assistantConvIdDoesNotContainColon() {
        assertEquals("assistant", ConvId.ASSISTANT)
        assertFalse(ConvId.ASSISTANT.contains(':'), "ASSISTANT must not contain colon to avoid projection filter drops")
        assertTrue(ConvId.isAssistant(ConvId.ASSISTANT))
        assertFalse(ConvId.isGroup(ConvId.ASSISTANT))
        assertFalse(ConvId.isExternalAgent(ConvId.ASSISTANT))
    }

    @Test
    fun externalAgentConvIdFormattingAndRecognition() {
        val externalId = ConvId.externalAgent("node-12345678", "coder")
        assertEquals("agent:node-12345678/coder", externalId)
        assertTrue(ConvId.isExternalAgent(externalId))
        assertFalse(ConvId.isGroup(externalId))
        assertFalse(ConvId.isAssistant(externalId))
        assertFalse(ConvId.isQuest(externalId))
    }

    @Test
    fun questConvIdFormattingAndRecognition() {
        val questWarRoom = ConvId.quest("quest-99")
        assertEquals("quest:quest-99", questWarRoom)
        assertTrue(ConvId.isQuest(questWarRoom))
        assertFalse(ConvId.isAssistant(questWarRoom))
        assertFalse(ConvId.isGroup(questWarRoom))
        assertFalse(ConvId.isExternalAgent(questWarRoom))

        val subagentSession = ConvId.quest("quest-99", "worker-1")
        assertEquals("quest:quest-99/worker-1", subagentSession)
        assertTrue(ConvId.isQuest(subagentSession))
    }
}
