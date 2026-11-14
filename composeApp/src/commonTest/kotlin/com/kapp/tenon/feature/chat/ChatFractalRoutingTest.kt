// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.ui.components.DEFAULT_PERSONA_ROSTER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分形会话路由与名册预设测试 (对齐 LLD §6.1 / Spec §4.3)。
 */
class ChatFractalRoutingTest {

    @Test
    fun testChatScreenArgsFractalLayers() {
        // 第 0 层主线
        val mainArgs = ChatScreenArgs(convId = ConvId.ASSISTANT)
        assertEquals("assistant", mainArgs.convId)
        assertNull(mainArgs.questId)
        assertNull(mainArgs.subagentId)

        // 第 1 层支线作战室
        val questArgs = ChatScreenArgs(convId = ConvId.ASSISTANT, questId = "quest-101")
        assertEquals("quest-101", questArgs.questId)
        assertNull(questArgs.subagentId)

        // 第 2 层 Subagent 白盒会话
        val subagentArgs = ChatScreenArgs(
            convId = ConvId.ASSISTANT,
            questId = "quest-101",
            subagentId = "worker-1",
        )
        assertEquals("quest-101", subagentArgs.questId)
        assertEquals("worker-1", subagentArgs.subagentId)
    }

    @Test
    fun testDefaultPersonaRosterPresets() {
        assertEquals(4, DEFAULT_PERSONA_ROSTER.size)
        val keys = DEFAULT_PERSONA_ROSTER.map { it.key }
        assertTrue(keys.contains("leader"))
        assertTrue(keys.contains("architect"))
        assertTrue(keys.contains("tester"))
        assertTrue(keys.contains("docs"))
    }
}
