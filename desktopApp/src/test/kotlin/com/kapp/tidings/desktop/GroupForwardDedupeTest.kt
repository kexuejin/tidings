// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.domain.model.MessageUi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **群里已经有这条了，就别再发一条。**
 *
 * ## 真机现场
 *
 * 用户只发了一次 `@dsh-teahouse-mobile test2`，群里出现两条：
 * 一条带 token（他自己发的），一条是 `test2`（agent 事件回传的副本）。
 * 后一条 `mine = isUserPrompt = true`，看起来就是他发了两遍。
 *
 * ## 根因
 *
 * relay 注入前剥掉了 mention（agent 该看到指令，不该看到 `@token`）。
 * 去重却拿**群里的原文**和**剥离后的正文**做字符串相等 —— 只要带 @ 就永远不等。
 */
class GroupForwardDedupeTest {

    private fun msg(text: String, at: Long, mine: Boolean = true) = MessageUi(
        id = "m-$at",
        peerId = "group:g-1",
        mine = mine,
        text = text,
        at = at,
    )

    /** **核心场景**：群里那条带 token，回写的正文不带 —— 必须判成重复。 */
    @Test
    fun theInjectedCopyOfAMentionedMessageCountsAsAlreadyForwarded() {
        val existing = listOf(msg("@dsh-teahouse-mobile test2", at = 1_000L))
        assertTrue(
            alreadyForwardedToGroup(
                isUserAuthoredPrompt = true,
                existingMessages = existing,
                finalAnswer = "test2",          // ← relay 剥离后的正文
                eventAt = 1_500L,
                agentKind = "dsh",
            ),
            "剥掉 @token 之后内容相同 ⇒ 群里已经有了，不能再发一条",
        )
    }

    /** 真不同的两条不能被误吞。 */
    @Test
    fun aDifferentInstructionIsStillForwarded() {
        val existing = listOf(msg("@dsh-teahouse-mobile test2", at = 1_000L))
        assertFalse(
            alreadyForwardedToGroup(
                isUserAuthoredPrompt = true,
                existingMessages = existing,
                finalAnswer = "部署到测试机",
                eventAt = 1_500L,
                agentKind = "dsh",
            ),
            "内容不同就该正常转发",
        )
    }

    /**
     * **「DSH 里打字、手机能看到」必须保住。**
     *
     * 那不是群消息来源（群里没有同一条），所以必须转发 —— 别为了去重把这个真功能砍掉。
     */
    @Test
    fun aPromptTypedInDshItselfIsStillForwarded() {
        assertFalse(
            alreadyForwardedToGroup(
                isUserAuthoredPrompt = true,
                existingMessages = emptyList(),
                finalAnswer = "帮我看下构建",
                eventAt = 1_000L,
                agentKind = "dsh",
            ),
            "用户在 DSH 里打的字群里没有 ⇒ 必须转发，否则手机端永远看不到",
        )
    }

    /** agent 自己的回答不受去重影响（那不是用户 prompt）。 */
    @Test
    fun anAgentAnswerIsNeverDedupedAsAUserPrompt() {
        assertFalse(
            alreadyForwardedToGroup(
                isUserAuthoredPrompt = false,
                existingMessages = listOf(msg("test2", at = 1_000L)),
                finalAnswer = "test2",
                eventAt = 1_500L,
                agentKind = "dsh",
            ),
            "agent 的回答走正常转发，不参与这条去重",
        )
    }

    /** 时间窗外的同一条指令是「重新执行」，不能被吞。 */
    @Test
    fun theSameInstructionLongAfterwardsIsForwardedAgain() {
        val existing = listOf(msg("@dsh-teahouse-mobile test2", at = 1_000L))
        assertFalse(
            alreadyForwardedToGroup(
                isUserAuthoredPrompt = true,
                existingMessages = existing,
                finalAnswer = "test2",
                eventAt = 1_000L + 200_000L,     // 200s 后
                agentKind = "dsh",
            ),
            "隔几分钟重新执行同一条是合理的，不该被当重复吞掉",
        )
    }
}
