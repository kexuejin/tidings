// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.persist

import app.relay.core.transport.InMemoryQueueStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **「目标设备已消失」是 `prune` 拦不住的一类积压。**
 *
 * ## 真机读数（2026-10-22）
 *
 * `pending: 476`，其中 **201 条的目标 `05607a4e` 既不在 `peers`、也不在 `known-peers`
 * 里** —— 那台设备早就不用了。
 *
 * 它没被现有 `prune` 删掉，因为 `prune` 只看两件事：
 *  · 时间（7 天 → 现改为 3 天）：那批是 3 天内的，没到期；
 *  · 每 peer 条数（上限 200）：它**恰好卡在 200**，没有超。
 *
 * 于是它永远留在队列里，每次启动都被重扫一遍，一直占着磁盘。
 */
class QueueVanishedPeerPruneTest {

    private val now = 10_000_000_000L   // 固定时钟
    private val threeDays = 3L * 24 * 3_600_000

    private fun store(): FileQueueStore {
        // 临时路径：每个用例一个干净文件
        val p = "build/tmp/queue-test-" + kotlin.random.Random.nextLong() + ".log"
        return FileQueueStore(p)
    }

    /**
     * **超时 + 目标不在已知集合 ⇒ 删。**
     *
     * 这条对应真机上那 201 条：目标早就没了，过期后不该再占队列。
     */
    @Test
    fun aVanishedPeerBeyondTtlIsPruned() {
        val s = store()
        s.enqueue("m-1", "gone-peer", "{}", now - threeDays - 1)
        s.enqueue("m-2", "gone-peer", "{}", now - threeDays - 1)
        assertEquals(2, s.countByPeer()["gone-peer"])

        val removed = s.pruneVanishedPeers(now, threeDays, knownPeer = { false })

        assertEquals(2, removed)
        assertEquals(null, s.countByPeer()["gone-peer"], "目标没了且过期 ⇒ 该删干净")
    }

    /**
     * **目标不在已知集合、但还没过期 ⇒ 不删。**
     *
     * 这是「刚配对、还没见过面」的对端：那是**合法待发**，删了就丢用户的字。
     */
    @Test
    fun aVanishedPeerWithinTtlIsKept() {
        val s = store()
        s.enqueue("m-1", "new-peer", "{}", now - 60_000)   // 一分钟前

        val removed = s.pruneVanishedPeers(now, threeDays, knownPeer = { false })

        assertEquals(0, removed, "还没过期就不删 —— 那条消息是合法的")
        assertEquals(1, s.countByPeer()["new-peer"])
    }

    /**
     * **目标在已知集合里、即使很久没送出去也不删。**
     *
     * 那台设备只是长期离线；它回来时，用户那条消息本该还在。
     */
    @Test
    fun aKnownPeerIsNeverPrunedByAge() {
        val s = store()
        s.enqueue("m-1", "offline-but-known", "{}", now - threeDays * 10)   // 30 天前

        val removed = s.pruneVanishedPeers(now, threeDays, knownPeer = { it == "offline-but-known" })

        assertEquals(0, removed, "它只是离线，不是没了 —— 删了等于丢用户的消息")
        assertEquals(1, s.countByPeer()["offline-but-known"])
    }

    /** 接口默认实现是 no-op：别的 QueueStore 实现行为不变。 */
    @Test
    fun theDefaultImplementationDoesNothing() {
        val s = InMemoryQueueStore()
        s.enqueue("m-1", "gone", "{}", 1L)
        assertEquals(0, s.pruneVanishedPeers(now, threeDays, { false }))
        assertEquals(1, s.countByPeer()["gone"])
    }

    /**
     * **总量硬上限**：`queueMaxPerPeer` 只管每台设备，管不住「设备数 × 每台上限」。
     *
     * 真机形态就是「一堆早已消失的设备各占 200 条」—— 每台都没超，总量却失控。
     * 这条兜底不依赖任何人喂进来的「已知对端」信息，所以它总能生效。
     */
    @Test
    fun theTotalCapEvictsOldestWhenExceeded() {
        val s = FileQueueStore(
            "build/tmp/queue-cap-" + kotlin.random.Random.nextLong() + ".log",
            retention = QueueRetention(ttlMillis = threeDays, maxItems = 3),
        )
        // 5 条、分属 5 个不同 peer —— 每台都没超 perPeer 上限，只有总量超。
        for (i in 1..5) s.enqueue("m-$i", "peer-$i", "{}", now - (10 - i) * 1000L)
        assertEquals(5, s.pendingAll().size)

        // knownPeer 全放行（模拟「都认识」）：① 一条都不该删；② 上限仍然生效。
        val removed = s.pruneVanishedPeers(now, threeDays, knownPeer = { true })

        assertEquals(2, removed, "超过 3 条就砍最旧的 2 条")
        val left = s.pendingAll().map { it.msgId }.toSet()
        assertEquals(setOf("m-3", "m-4", "m-5"), left, "留下的是最新三条，砍的是最旧两条")
    }

    /** 没超上限时这条不该动任何东西。 */
    @Test
    fun theTotalCapDoesNothingWhenUnderLimit() {
        val s = FileQueueStore(
            "build/tmp/queue-cap2-" + kotlin.random.Random.nextLong() + ".log",
            retention = QueueRetention(ttlMillis = threeDays, maxItems = 10),
        )
        for (i in 1..3) s.enqueue("m-$i", "peer-$i", "{}", now - 1000L)
        assertEquals(0, s.pruneVanishedPeers(now, threeDays, knownPeer = { true }))
        assertEquals(3, s.pendingAll().size)
    }
}
