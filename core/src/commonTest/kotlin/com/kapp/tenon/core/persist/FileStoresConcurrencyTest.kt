// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.persist

import com.kapp.tenon.core.fs.pathJoin
import com.kapp.tenon.core.testing.createTempDir
import com.kapp.tenon.core.testing.removeRecursively
import com.kapp.tenon.core.transport.FileDedupStore
import com.kapp.tenon.core.transport.dedupLogPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * File\* 生产存储的**并发判据**（ARP-16）。
 *
 * 缺陷形态与 InMemory 三件套同族（AGENTS §2.1 记过：低频、轮换、单跑恒绿）——
 * fanout requeue 在 `Dispatchers.Default`、flushQueue 在 runtime scope、agentNode
 * HTTP 线程并发读，而 `items`/`seen`/`rows` 是裸集合。判据用**真实多线程**
 * （`Dispatchers.Default` 的独立线程，不是协程单线程轮转：那永远撞不上裸
 * LinkedHashMap 的 ConcurrentModification 与丢失更新）互怼后断言精确不变式。
 *
 * 反向验证：摘掉 store 里的锁 ⇒ 本测试高概率红（并发红是概率性的，所以每轮
 * 压足量操作放大撞窗）；锁在 ⇒ 稳定绿。
 */
class FileStoresConcurrencyTest {

    private val root = createTempDir("relay-stores-conc")

    @AfterTest
    fun clean() {
        removeRecursively(root)
    }

    @Test
    fun `queue store survives interleaved enqueue-remove-read from many threads`() = runBlocking {
        val store = FileQueueStore(path = pathJoin(root, "queue.log"))
        val threads = 8
        val perThread = 300

        coroutineScope {
            (0 until threads).map { t ->
            async(Dispatchers.Default) {
                for (i in 0 until perThread) {
                    val msgId = "m-$t-$i"
                    store.enqueue(msgId, peerId = "peer-$t", envelopeJson = """{"id":"$msgId"}""", created = i.toLong())
                    if (i % 3 == 0) store.remove(msgId, peerId = "peer-$t")
                    if (i % 7 == 0) store.pendingAll() // 读路径与写路径互怼
                    if (i % 11 == 0) store.countByPeer()
                }
            }
            }.awaitAll()
        }

        // 不变式：每线程留下 2/3（300 − 每 3 条删 1 = 200），一条不多、一条不少；
        // 裸集合在并发下要么抛 ConcurrentModification、要么丢行 —— 两种都过不了。
        val expected = threads * (perThread - perThread / 3)
        assertEquals(expected, store.pendingAll().size, "并发写入后行数必须精确（丢行=静默丢失）")
        val counts = store.countByPeer()
        assertEquals(threads, counts.size, "每个 peer 都在")
        counts.forEach { (peer, n) -> assertEquals(perThread - perThread / 3, n, "peer=$peer 行数") }

        // 重启读回：文件日志与内存表一致（锁必须盖住文件写入的交错序，不只护表）。
        val reopened = FileQueueStore(path = pathJoin(root, "queue.log"))
        assertEquals(expected, reopened.pendingAll().size, "重启读回行数一致")
    }

    @Test
    fun `dedup store survives interleaved add-has-prune from many threads`() = runBlocking {
        val store = FileDedupStore(path = dedupLogPath(root))
        val threads = 8
        val perThread = 300

        coroutineScope {
            (0 until threads).map { t ->
            async(Dispatchers.Default) {
                for (i in 0 until perThread) {
                    val msgId = "m-$t-$i"
                    store.add(msgId, peerId = "peer-$t", at = i * 100L) // at ∈ [0, 29_900]
                    // 「立即可见」只对 TTL 之上的键成立：i<200 的键会被并发 prune 合法删掉，
                    // 那是设计行为不是丢失更新。
                    if (i >= 200) assertTrue(store.has(msgId, "peer-$t"), "TTL 之上的键刚 add 必须立即可见（丢失更新=重复投递）")
                    if (i % 5 == 0) store.prune(now = 30_000, ttl = 10_000) // 删 at < 20_000 ⇒ i < 200
                }
            }
            }.awaitAll()
        }

        // 不变式：i ≥ 200 的（at ≥ 20_000）全部存活，i < 200 的全部被 TTL 剪掉。
        // 多删 = 这条消息将来会重复投递；少删/错删 = 表腐坏。
        for (t in 0 until threads) {
            for (i in 0 until perThread) {
                val alive = store.has("m-$t-$i", "peer-$t")
                if (i >= 200) {
                    assertTrue(alive, "peer=$t i=$i 应存活（at=${i * 100} ≥ 20000）")
                } else {
                    assertFalse(alive, "peer=$t i=$i 应被 TTL 剪掉")
                }
            }
        }
        // 重启读回与内存一致（prune 真删过 ⇒ 文件被重写）。
        val reopened = FileDedupStore(path = dedupLogPath(root))
        for (t in 0 until threads) {
            assertTrue(reopened.has("m-$t-${perThread - 1}", "peer-$t"), "重启后最新条目存活")
            assertFalse(reopened.has("m-$t-0", "peer-$t"), "重启后被剪条目不复活")
        }
    }

    @Test
    fun `message store survives interleaved append-read from many threads`() = runBlocking {
        val store = FileMessageStore(path = pathJoin(root, "messages.log"))
        val threads = 8
        val perThread = 200

        coroutineScope {
            (0 until threads).map { t ->
            async(Dispatchers.Default) {
                for (i in 0 until perThread) {
                    store.append(peerId = "peer-$t", direction = "in", envelopeJson = """{"n":$t-$i}""", at = i.toLong())
                    if (i % 4 == 0) store.since("peer-$t", sinceSeq = 0, limit = 50)
                    if (i % 9 == 0) store.sinceAll(sinceAt = 0, limit = 50)
                }
            }
            }.awaitAll()
        }

        assertEquals(threads * perThread, store.sinceAll(sinceAt = -1, limit = Int.MAX_VALUE).size, "并发追加后行数精确")
        // 重启读回：索引 offset 与文件内容一致（append 的偏移计算在锁内才成立）。
        val reopened = FileMessageStore(path = pathJoin(root, "messages.log"))
        assertEquals(threads * perThread, reopened.sinceAll(sinceAt = -1, limit = Int.MAX_VALUE).size, "重启读回行数一致")
    }
}
