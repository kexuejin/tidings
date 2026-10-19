// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.testing.createTempDir
import app.relay.core.testing.removeRecursively
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * 报价状态的**并发**读写（task-69）。
 *
 * 真实现场：`recordDelivery` 曾经**裸写** `deliveries`（普通 `LinkedHashMap`），而
 * `offerDeliveries()` 在锁里**迭代**它 ⇒ 满量并行下 jvmTest 报
 * `ConcurrentModificationException: LinkedHashIterator`（实测出现率 ~1/3 ⇒ **跑一次绿说明不了
 * 任何事**）。这与 task-67 那条不同：那条是 Kotlin/Native 的 `Map.Entry` 视图（同线程），
 * 这条是**跨线程真竞争**，与平台无关。
 *
 * 这条测试**故意**把读（`/v1/transfers` 走的那条）与写（投递状态变化 + 退避重试）并发起来，
 * 多轮跑：修之前几乎每轮都抛，修之后稳定通过。它是**压力测试**（不是确定性证明）——
 * 目的是让这类竞争在单测里也能现形，而不是只在满量门禁里偶发。
 */
class OfferDeliveryRaceTest {

    private val dirs = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dirs.forEach { removeRecursively(it) }
        dirs.clear()
    }

    @Test
    fun concurrentReadsAndWritesOfDeliveryStateDoNotThrow() = runBlocking {
        val manager = TransferManager(
            selfId = "self",
            scope = scope,
            tcpPort = 0,
            downloadDir = { createTempDir("relay-offer-race").also { dirs += it } },
            // 一律投递失败 ⇒ 走「入队 + 退避重试」那条**会反复写**状态的路。
            sendReliable = { _, _ -> false },
            sendReliableQuick = { _, _ -> false },
            offerRetryBackoffMs = 1L,
            offerDeliveryBudgetMs = 200L,
            peerAddress = { null },
        )
        repeat(100) { i ->
            val writer = launch {
                manager.offerFilesAsync("peer-$i", listOf(LocalFile("/tmp/a.bin", "a.bin", 16L)))
            }
            // 读方就是只读出口那条路（控制面的 /v1/transfers）。
            val reader = launch { repeat(20) { manager.offerDeliveries() } }
            writer.join()
            reader.join()
        }
    }
}
