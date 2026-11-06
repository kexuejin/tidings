// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「这条消息点了谁的名」—— 认知研讨流按它决定唤醒几个人。
 *
 * 与 `isMentioningAgent` 共用同一个正则构造器：判真却找不出是谁，讨论流就会
 * 要么丢回合、要么唤醒错人。
 */
class AgentMentionKeysTest {

    private val kinds = setOf("claude", "dsh", "codex")

    @Test
    fun listsEveryMentionedKindInAStableOrder() {
        assertEquals(listOf("claude", "dsh"), RelayController.mentionedAgentKeys("@claude @dsh 调研下", kinds))
        assertEquals(listOf("dsh"), RelayController.mentionedAgentKeys("@DSH 看一下", kinds))
        assertEquals(listOf("claude"), RelayController.mentionedAgentKeys("@claude-teahouse 看一下", kinds))
    }

    @Test
    fun wordBoundaryStillHolds() {
        assertTrue(RelayController.mentionedAgentKeys("@dshx 这不是点名", kinds).isEmpty(), "@dshx 不是 @dsh")
        assertTrue(RelayController.mentionedAgentKeys("dsh 没有 @", kinds).isEmpty())
    }

    @Test
    fun noMentionYieldsEmptyList() {
        assertTrue(RelayController.mentionedAgentKeys("", kinds).isEmpty())
        assertTrue(RelayController.mentionedAgentKeys("这是个普通问题", emptySet()).isEmpty())
    }

    @Test
    fun everyMentionedKeyIsAlsoJudgedAsMentioning() {
        val text = "@claude @codex 你俩分析下"
        val keys = RelayController.mentionedAgentKeys(text, kinds)
        assertEquals(listOf("claude", "codex"), keys)
        keys.forEach { key ->
            assertTrue(
                RelayController.isMentioningAgent(text, setOf(key)),
                "$key 被判为点名，就必须与 isMentioningAgent 同判（判据同源）",
            )
        }
    }
}
