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
}
