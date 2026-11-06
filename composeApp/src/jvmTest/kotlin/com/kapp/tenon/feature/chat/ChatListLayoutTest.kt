// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.model.MessageUi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 一次派生的**逐项等价**（轮 15 / WP2）。
 *
 * `ChatListLayout` 是纯装配，读数必须与改造前那五个独立派生逐字相同。
 * 反向验证：把 `chatListLayoutOf` 里 positions 的下标写成 `index + 1`，
 * [fiveDerivationsMatchTheLegacyOnes] 变红。
 */
class ChatListLayoutTest {

    private fun msg(
        id: String,
        at: Long,
        sessionId: String? = null,
        cardId: String? = null,
        rev: Int? = null,
    ) = MessageUi(
        id = id,
        peerId = "p",
        mine = true,
        text = "t",
        at = at,
        agentSessionId = sessionId,
        card = if (cardId != null && rev != null) {
            JsonObject(
                mapOf(
                    "cardId" to JsonPrimitive(cardId),
                    "rev" to JsonPrimitive(rev.toString()),
                ),
            )
        } else null,
    )

    @Test
    fun fiveDerivationsMatchTheLegacyOnes() {
        val messages = listOf(
            msg("a", 0L, sessionId = "s1"),
            msg("b", 6 * 60_000L, sessionId = "s1", cardId = "c1", rev = 1),
            msg("c", 7 * 60_000L, sessionId = "s2", cardId = "c1", rev = 2),
            msg("d", 86_400_000L + 1_000L, sessionId = "s2"),
            msg("e", 86_400_000L + 2_000L),
        )
        val layout = chatListLayoutOf(messages, ::localDayKey)

        assertEquals(sessionTransitionIds(messages), layout.sessionTransitions)
        assertEquals(timelineSeparatorIds(messages, ::localDayKey), layout.timelineSeparators)
        assertEquals(messages.associateBy { it.id }, layout.messagesById)
        assertEquals(messages.withIndex().associate { (i, m) -> m.id to i }, layout.messagePositions)
        assertEquals(latestRevCardMessageIds(messages), layout.visibleCardIds)
    }

    @Test
    fun duplicateIdsKeepTheLastEntryLikeAssociateByDid() {
        val messages = listOf(msg("dup", 0L), msg("dup", 10L), msg("other", 20L))
        val layout = chatListLayoutOf(messages, ::localDayKey)
        assertEquals(messages.associateBy { it.id }, layout.messagesById)
        assertEquals(messages.withIndex().associate { (i, m) -> m.id to i }, layout.messagePositions)
        assertEquals(1, layout.messagePositions["dup"])
    }

    @Test
    fun emptyListDerivesEmptyCollections() {
        val layout = chatListLayoutOf(emptyList(), ::localDayKey)
        assertEquals(emptySet<String>(), layout.sessionTransitions)
        assertEquals(emptySet<String>(), layout.timelineSeparators)
        assertEquals(emptyMap<String, MessageUi>(), layout.messagesById)
        assertEquals(emptyMap<String, Int>(), layout.messagePositions)
        assertEquals(emptySet<String>(), layout.visibleCardIds)
    }
}
