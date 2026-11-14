// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TicketDedupStoreTest {

    @Test
    fun getOrPutQuestIdReturnsExistingOrCreatesNew() {
        val store = TicketDedupStore()
        var createCount = 0

        val quest1 = store.getOrPutQuestId("token-1") {
            createCount++
            "quest-1"
        }
        assertEquals("quest-1", quest1)
        assertEquals(1, createCount)
        assertTrue(store.has("token-1"))
        assertEquals("quest-1", store.findQuestId("token-1"))

        // 相同 token 再次调用 -> 直接返回已有，不触发 creator
        val quest2 = store.getOrPutQuestId("token-1") {
            createCount++
            "quest-should-not-create"
        }
        assertEquals("quest-1", quest2)
        assertEquals(1, createCount)

        // 不同 token -> 触发 creator
        val quest3 = store.getOrPutQuestId("token-2") {
            createCount++
            "quest-2"
        }
        assertEquals("quest-2", quest3)
        assertEquals(2, createCount)
    }

    @Test
    fun clearRemovesAllEntries() {
        val store = TicketDedupStore()
        store.getOrPutQuestId("token-1") { "quest-1" }
        assertTrue(store.has("token-1"))

        store.clear()
        assertFalse(store.has("token-1"))
        assertNull(store.findQuestId("token-1"))
    }

    @Test
    fun claimIsAtomicAndDistinguishesWinnerFromReplay() {
        val store = TicketDedupStore()
        var createCount = 0

        // 首次认领：返回 null = 调用方赢了这一单，负责把任务跑起来
        val first = store.claim("token-1") {
            createCount++
            "quest-1"
        }
        assertNull(first)
        assertEquals(1, createCount)

        // 重放/并发重入：返回已有 questId（输家必须归并，绝不重复开单）
        val second = store.claim("token-1") {
            createCount++
            "quest-should-not-create"
        }
        assertEquals("quest-1", second)
        assertEquals(1, createCount, "输家不得触发 creator")

        // 不同 token 各自认领成功
        assertNull(store.claim("token-2") { "quest-2" })
        assertEquals("quest-2", store.findQuestId("token-2"))
    }
}
