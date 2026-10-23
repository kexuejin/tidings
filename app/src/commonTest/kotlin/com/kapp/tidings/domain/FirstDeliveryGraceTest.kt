// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.transport.InMemoryQueueStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **首次投递窗口：只在群成员表里的对端，超过 24 小时就不再为它排队。**
 *
 * ## 真机读数
 *
 * `05607a4e` 不在 `peers`、也不在 `known-peers`，却还留在 `deepseek-harness 协同群` 的
 * 成员表里 —— 每一轮群广播都给它添一条。它的条目**最旧 2.77 天、最新十几分钟**，
 * 所以 TTL（3 天）永远碰不到，`pruneVanishedPeers` 也删不掉（它要求「超时」且「消失」，
 * 而这里永远有新鲜条目）。
 *
 * ## 为什么不能一刀切拒掉
 *
 * 群主刚拉进来、还没握过手的第三台设备**只满足**「在成员表里」这一条。它的第一条消息
 * 必须留着等它。所以判据是**等了多久**，不是「满不满足成员表」。
 */
class FirstDeliveryGraceTest {

    private val hour = 3_600_000L
    private val grace = 24 * hour

    /**
     * 「是否已超出宽限」的判据：取队列里**最旧**那条的入队时刻。
     *
     * 与 `RelayController.hasWaitedBeyond` 同一形状（那边是私有方法，这里复刻判据本身）。
     */
    private fun waitedBeyond(store: InMemoryQueueStore, peerId: String, now: Long): Boolean {
        val oldest = store.listByPeer(peerId).minOfOrNull { it.created } ?: return false
        return now - oldest > grace
    }

    /**
     * **从没为它排过队 ⇒ 不超过宽限 ⇒ 允许首次投递。**
     *
     * 这正是「群主刚拉进来、还没握过手」的形态。
     */
    @Test
    fun aNeverQueuedPeerIsWithinGrace() {
        val store = InMemoryQueueStore()
        assertFalse(
            waitedBeyond(store, "fresh-member", now = 1_000_000_000L),
            "一条都没排过 ⇒ 首次投递，必须放行",
        )
    }

    /** 排过队、但最旧那条还在 24 小时内 ⇒ 仍允许。 */
    @Test
    fun aPeerWithinTheWindowIsStillAllowed() {
        val store = InMemoryQueueStore()
        val now = 1_000_000_000L
        store.enqueue("m-1", "member", "{}", now - 2 * hour)   // 两小时前
        assertFalse(waitedBeyond(store, "member", now), "窗口内还要留着")
    }

    /**
     * **最旧那条超过 24 小时 ⇒ 不再为它排队。**
     *
     * 这条对应真机上的 `05607a4e`：最旧一条 2.77 天，早该放弃。
     */
    @Test
    fun aPeerBeyondTheWindowIsRejected() {
        val store = InMemoryQueueStore()
        val now = 1_000_000_000L
        store.enqueue("m-old", "gone", "{}", now - 3 * 24 * hour)   // 三天前
        store.enqueue("m-new", "gone", "{}", now - 60_000)          // 一分钟前
        assertTrue(
            waitedBeyond(store, "gone", now),
            "判据取**最旧**那条 —— 有新鲜条目不代表没等过",
        )
    }

    /**
     * **只看最旧一条，不看最新。**
     *
     * 真机上那批条目每轮都在刷新（最新十几分钟），若用「最新」判会永远放行 ——
     * 那正是 TTL 碰不到它们的原因。
     */
    @Test
    fun theNewestEntryDoesNotResetTheWindow() {
        val store = InMemoryQueueStore()
        val now = 1_000_000_000L
        store.enqueue("m-old", "gone", "{}", now - 30 * 24 * hour)
        repeat(5) { i -> store.enqueue("m-$i", "gone", "{}", now - i * 1000) }
        assertTrue(
            waitedBeyond(store, "gone", now),
            "最新条目再新，也不能把「已经等了 30 天」抹掉",
        )
    }
}
