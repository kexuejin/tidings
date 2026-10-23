// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.platform.InMemoryAppPlatform
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.kapp.tidings.domain.model.MessageUi

/**
 * `threads` 的并发回归：**读快照的协程与改结构的协程必须被串行化**。
 *
 * 这个用例存在的理由：`RelayController` 跑在 `Dispatchers.Default`（多线程池）上，
 * `observe()` 的轮询协程每 2 秒 `refreshPeers()` 一次并对 `threads` 做 `mapValues`，
 * 而入站消息会在别的 worker 上 `appendMessage()` 改同一张表。真实设备上踩到过一次
 * `java.util.ConcurrentModificationException`，直接让进程在启动后 104ms 死掉
 * （Pixel 6，`FATAL EXCEPTION: DefaultDispatcher-worker-5`，栈顶是
 * `RelayController.refreshPeers`）。
 *
 * 注意这不是"启动时才会踩到"的竞态：UI 线程的 `publish()` 走的也是同一段遍历，
 * 所以任何一条入站消息都可能让界面刷新崩掉。用例同时压 `publish()` 与
 * `refreshPeers()` 两条读路径，就是为了钉住这一点。
 *
 * 用真线程而不是 `runTest` 的虚拟时间：要复现的是**真的并行**，虚拟时间会把
 * 并发折叠成顺序执行，那样即使没有锁也永远绿 —— 一个不会失败的用例没有价值。
 */
class RelayControllerStateLockTest {

    @Test
    fun concurrentAppendAndPublishNeverThrow() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = RelayController(
                platform = InMemoryAppPlatform(),
                store = MemorySettingsStore(),
                scope = scope,
                version = "relay-test",
            )

            val writers = 6
            val perWriter = 400
            val failures = ConcurrentLinkedQueue<Throwable>()
            val stop = AtomicBoolean(false)
            val start = CountDownLatch(1)

            // 读者：模拟轮询协程与 UI 线程的 publish()，全程不停地遍历 threads。
            val readers = (0 until 2).map {
                Thread {
                    start.await()
                    while (!stop.get()) {
                        runCatching { controller.refreshPeers() }.onFailure { failures += it }
                        runCatching { controller.publish() }.onFailure { failures += it }
                    }
                }
            }

            // 写者：模拟入站消息落到各自的会话上（getOrPut + += 改结构）。
            val writerThreads = (0 until writers).map { w ->
                Thread {
                    start.await()
                    repeat(perWriter) { i ->
                        runCatching {
                            controller.appendMessage(
                                MessageUi(
                                    id = "w$w-$i",
                                    peerId = "peer-$w",
                                    mine = false,
                                    text = "m$i",
                                    at = nowMillis(),
                                ),
                                incoming = true,
                            )
                        }.onFailure { failures += it }
                    }
                }
            }

            (readers + writerThreads).forEach { it.isDaemon = true; it.start() }
            start.countDown()
            writerThreads.forEach { it.join(30_000) }
            stop.set(true)
            readers.forEach { it.join(5_000) }

            val problems = failures.toList()
            assertTrue(
                problems.isEmpty(),
                "并发读写 threads 时抛了 ${problems.size} 次异常，第一次是：${problems.firstOrNull()}",
            )

            // 写进去的消息一条都不能丢：加锁不能以"少记一条"为代价。
            val total = controller.state.value.messages.values.sumOf { it.size }
            assertTrue(
                total == writers * perWriter,
                "期望 $writers×$perWriter 条，实际 $total 条",
            )
        } finally {
            scope.cancel()
        }
    }

    private class MemorySettingsStore : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = "abc12345-0000-4000-8000-000000000000",
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }
}
