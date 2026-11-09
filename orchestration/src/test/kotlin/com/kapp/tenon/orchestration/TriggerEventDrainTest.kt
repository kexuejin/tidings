// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.SchedulerStore
import com.kapp.tenon.domain.TaskRunState
import com.kapp.tenon.domain.TriggerEventRecord
import com.kapp.tenon.domain.TriggerEventStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 触发事件持久化消费链路（提案 §9 阶段二）。
 *
 * SQLDelight 落地已在 :app 的 SchedulerStoreTest 钉过；这里用内存 fake 钉的是
 * 消费循环的行为契约：恰一次消费、幂等吸收、失败可见、重启恢复。
 */
class TriggerEventDrainTest {

    private lateinit var scope: CoroutineScope
    private var now = 1_790_000_000_000L

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** 内存版台账 fake（镜像接口语义；行真身在 :app 测试）。 */
    private class InMemorySchedulerStore : SchedulerStore {
        /** 复合主键 (source_id, event_id) —— 与 :app 的表定义同域。 */
        val rows = ConcurrentHashMap<String, TriggerEventRecord>()
        private val taskStates = ConcurrentHashMap<String, TaskRunState>()

        private fun key(sourceId: String, eventId: String) = sourceId + "\u0000" + eventId

        /** 测试读回：默认源 mail，省掉每个断言都要写一遍复合键。 */
        fun row(eventId: String, sourceId: String = "mail"): TriggerEventRecord =
            rows.getValue(key(sourceId, eventId))

        override fun taskRunStates(): List<TaskRunState> = taskStates.values.toList()

        override fun saveTaskRunState(state: TaskRunState) {
            taskStates[state.taskId] = state
        }

        override fun enqueueEvent(event: TriggerEventRecord): Boolean =
            rows.putIfAbsent(key(event.sourceId, event.eventId), event) == null

        override fun eventExists(sourceId: String, eventId: String): Boolean =
            rows.containsKey(key(sourceId, eventId))

        override fun pendingEvents(limit: Int): List<TriggerEventRecord> =
            rows.values.filter { it.state == TriggerEventStates.PENDING }
                .sortedBy { it.createdAt }
                .take(limit)

        /**
         * 条件翻态必须**真**原子：`computeIfPresent` 对单个 key 是原子的，
         * 与 SQLDelight 那条 `WHERE state = 'pending'` 的条件 UPDATE 同语义。
         * 用「先 get 再 put」写这个 fake，双派用例会因为 fake 自己有竞态而假绿。
         */
        override fun claimEvent(sourceId: String, eventId: String): Boolean {
            var claimed = false
            rows.computeIfPresent(key(sourceId, eventId)) { _, old ->
                if (old.state == TriggerEventStates.PENDING) {
                    claimed = true
                    old.copy(state = TriggerEventStates.RUNNING, error = null, updatedAt = 0L)
                } else {
                    old
                }
            }
            return claimed
        }

        override fun reclaimRunningEvents(): Int {
            var reclaimed = 0
            for (rowKey in rows.keys.toList()) {
                rows.computeIfPresent(rowKey) { _, old ->
                    if (old.state == TriggerEventStates.RUNNING) {
                        reclaimed++
                        old.copy(state = TriggerEventStates.PENDING, updatedAt = 0L)
                    } else {
                        old
                    }
                }
            }
            return reclaimed
        }

        override fun updateEventState(
            sourceId: String,
            eventId: String,
            state: String,
            error: String?,
        ): Boolean {
            var hit = false
            rows.computeIfPresent(key(sourceId, eventId)) { _, old ->
                hit = true
                old.copy(state = state, error = error, updatedAt = 0L)
            }
            return hit
        }

        override fun pruneTerminalEventsBefore(epochMs: Long) {
            rows.entries.removeIf {
                it.value.state in setOf(TriggerEventStates.DONE, TriggerEventStates.FAILED, TriggerEventStates.SKIPPED)
            }
        }
    }

    private fun event(id: String, createdAt: Long = now) = TriggerEventRecord(
        eventId = id,
        sourceId = "mail",
        type = "mail.inbound",
        subject = "urgent: build broken",
        summary = "主分支红了",
        detail = "三连红",
        link = null,
        state = TriggerEventStates.PENDING,
        error = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun ingestPersistsAndHandlerConsumesToDone() {
        val store = InMemorySchedulerStore()
        val drain = TriggerEventDrain(store, scope, nowMillis = { now })
        val handled = CopyOnWriteArrayList<String>()
        drain.handler = { event ->
            handled += event.eventId
            TriggerVerdict.Done
        }
        drain.start()
        try {
            assertTrue(drain.ingest(event("evt-1")), "首次入队应成功")
            TestAwait.condition("handler 恰好消费一次") { handled.size >= 1 }
            assertEquals(
                TriggerEventStates.DONE,
                store.row("evt-1").state,
                "成功消费的事件必须翻 DONE——「收下了但没人记账」是不许的",
            )
        } finally {
            drain.stop()
        }
    }

    @Test
    fun duplicateIngestIsAbsorbedAndConsumedOnce() {
        val store = InMemorySchedulerStore()
        val drain = TriggerEventDrain(store, scope, nowMillis = { now })
        val handled = CopyOnWriteArrayList<String>()
        drain.handler = { event ->
            handled += event.eventId
            TriggerVerdict.Done
        }
        drain.start()
        try {
            assertTrue(drain.ingest(event("evt-1")))
            assertFalse(drain.ingest(event("evt-1")), "同 id 重放必须被持久幂等键吸收")
            TestAwait.condition("handler 消费") { handled.size >= 1 }
            // 观测窗用「确定会发生的事件」收口，不用恒真 probe 也不用固定 delay 赌同步：
            // 再入队一个不同 id 的事件，等它被同一条消费循环处理完。重放若被错误分发，
            // 它必然与 evt-2 抢同一批 pending，等 evt-2 落地时已无处可藏。
            assertTrue(drain.ingest(event("evt-2")), "不同 id 的事件应正常入队")
            TestAwait.condition("观测窗收口：第二个事件被消费") { handled.contains("evt-2") }
            assertEquals(1, handled.count { it == "evt-1" }, "重放投递不许二次唤醒执行通道")
            assertEquals(2, handled.size, "两个不同 id 各消费一次，不多不少")
        } finally {
            drain.stop()
        }
    }

    @Test
    fun handlerErrorIsRecordedAsFailedWithReason() {
        val store = InMemorySchedulerStore()
        val drain = TriggerEventDrain(store, scope, nowMillis = { now })
        drain.handler = { _ -> TriggerVerdict.Fail("no executor for agent key") }
        drain.start()
        try {
            drain.ingest(event("evt-err"))
            TestAwait.condition("失败翻账") { store.row("evt-err").state == TriggerEventStates.FAILED }
            val row = store.row("evt-err")
            assertEquals(TriggerEventStates.FAILED, row.state)
            assertEquals("no executor for agent key", row.error, "失败原因必须落账——失败可见性")
        } finally {
            drain.stop()
        }
    }

    /** handler 抛异常不能让事件卡在 running：如实翻 FAILED。 */
    @Test
    fun handlerThrowIsRecordedAsFailed() {
        val store = InMemorySchedulerStore()
        val drain = TriggerEventDrain(store, scope, nowMillis = { now })
        drain.handler = { _ -> throw IllegalStateException("boom") } // 抛异常 = Fail
        drain.start()
        try {
            drain.ingest(event("evt-throw"))
            TestAwait.condition("异常翻账") { store.row("evt-throw").state == TriggerEventStates.FAILED }
            assertTrue(
                store.row("evt-throw").error!!.contains("boom"),
                "异常信息应进 error 列：" + store.row("evt-throw").error,
            )
        } finally {
            drain.stop()
        }
    }

    /** 重启恢复：上一轮遗留的 pending 行在新进程里照常被捞起消费。 */
    @Test
    fun pendingEventsFromPreviousRunAreRecoveredOnStart() {
        val store = InMemorySchedulerStore()
        // 「上一轮」：只入队，不消费（崩溃现场）。
        store.enqueueEvent(event("evt-left-1", createdAt = 1_000))
        store.enqueueEvent(event("evt-left-2", createdAt = 2_000))

        // 限并发 1：单许可下「完成顺序」才等于「拉取顺序」，FIFO 判据才不赌时序。
        val drain = TriggerEventDrain(store, scope, nowMillis = { now }, dispatchParallelism = 1)
        val handled = CopyOnWriteArrayList<String>()
        drain.handler = { event ->
            handled += event.eventId
            TriggerVerdict.Done
        }
        drain.start()
        try {
            TestAwait.condition("两个遗留事件恢复消费") { handled.size >= 2 }
            assertEquals(listOf("evt-left-1", "evt-left-2"), handled, "先进先出")
        } finally {
            drain.stop()
        }
    }

    @Test
    fun missingHandlerMarksEventFailedHonest() {
        val store = InMemorySchedulerStore()
        val drain = TriggerEventDrain(store, scope, nowMillis = { now })
        // handler 不设（执行通道未装配）。
        drain.start()
        try {
            drain.ingest(event("evt-orphan"))
            TestAwait.condition("无 handler 如实翻失败") { store.row("evt-orphan").state == TriggerEventStates.FAILED }
            assertTrue(
                store.row("evt-orphan").error!!.contains("no trigger handler"),
                "无 handler 必须如实翻失败而不是无声吞事件：" + store.row("evt-orphan").error,
            )
        } finally {
            drain.stop()
        }
    }

    /**
     * **崩溃恢复**：上一进程认领后被杀，行停在 `running` —— 启动时必须捞回来重放。
     *
     * 这类行既不在 pending 里（`pendingEvents` 拉不到），也不在终态里（`prune` 不管），
     * 不捞就是**永久丢失**：事件进了台账、ACK 已经回给发送方、却永远不会被执行，
     * 而且没有任何日志提到它。
     */
    @Test
    fun startReclaimsOrphanedRunningEventsAndReplaysThem() {
        val store = InMemorySchedulerStore()
        store.enqueueEvent(event("evt-orphan"))
        // 模拟「上一进程认领后就被 kill」：行翻到 running 之后没有下文。
        assertTrue(store.claimEvent("mail", "evt-orphan"), "先认领，把行停在 running")
        assertEquals(TriggerEventStates.RUNNING, store.row("evt-orphan").state)

        val handled = CopyOnWriteArrayList<String>()
        val drain = TriggerEventDrain(store, scope, nowMillis = { now })
        drain.handler = { event ->
            handled += event.eventId
            TriggerVerdict.Done
        }
        drain.start()
        try {
            TestAwait.condition("遗留 running 行被放回 pending 并重放") { handled.contains("evt-orphan") }
            TestAwait.condition("重放后翻终态") {
                store.row("evt-orphan").state == TriggerEventStates.DONE
            }
        } finally {
            drain.stop()
        }
    }

    /**
     * **认领不到就必须退出循环**，不许原地空转。
     *
     * 旧实现的 `while (true) { pendingEvents() … }` 没有「本轮有没有进展」的判据：
     * 如果每一行都认领失败（别的消费者抢走 / 行刚被删），下一轮拉到的还是同一批，
     * 循环永不退出 —— 一条卡死的消费协程，看起来像「drain 在跑」。
     *
     * 判据用「读了几轮 pendingEvents」：正常路径是 1 轮，退化路径才会 > 2。
     */
    @Test
    fun drainStopsWhenItCannotClaimAnything() {
        val base = InMemorySchedulerStore()
        base.enqueueEvent(event("evt-1"))
        val pendingReads = java.util.concurrent.atomic.AtomicInteger(0)
        // 永远拉得到、永远认领失败：行留在 pending，下一轮还会被拉到。
        val stuck = object : SchedulerStore by base {
            override fun pendingEvents(limit: Int): List<TriggerEventRecord> {
                pendingReads.incrementAndGet()
                return base.pendingEvents(limit)
            }

            override fun claimEvent(sourceId: String, eventId: String): Boolean = false
        }

        val handled = CopyOnWriteArrayList<String>()
        val drain = TriggerEventDrain(stuck, scope, nowMillis = { now })
        drain.handler = { event ->
            handled += event.eventId
            TriggerVerdict.Done
        }
        drain.start()
        try {
            TestAwait.condition("drain 至少拉了一轮") { pendingReads.get() >= 1 }
            TestAwait.quiet("拉不到就停，不许原地死循环", windowMs = 300) { pendingReads.get() > 2 }
            assertTrue(
                pendingReads.get() <= 2,
                "认领全失败时必须退出循环，实际读了 " + pendingReads.get() + " 轮 pendingEvents",
            )
            assertEquals(emptyList(), handled.toList(), "认领失败的行不许派发（那就是双派）")
        } finally {
            drain.stop()
        }
    }

    /**
     * **单轮失败必须自己再踢一次**：库偶发 BUSY 时 `drainOnce` 抛在批次中途，
     * 剩下的 pending 行还在表里，而循环回到 `for (unit in kick)` 等下一个事件。
     *
     * 事件在 `start()` **之前**入队，所以整条链路里不会再有第二次踢动 —— 旧实现
     * （catch 里只打日志、不重踢）会让这条事件永远停在 pending，这条用例必然超时红。
     */
    @Test
    fun drainRetriesAfterACycleFailureWithoutWaitingForAnotherEvent() {
        val base = InMemorySchedulerStore()
        base.enqueueEvent(event("evt-recover", createdAt = 1_000))
        val failOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        val flaky = object : SchedulerStore by base {
            override fun pendingEvents(limit: Int): List<TriggerEventRecord> {
                if (failOnce.compareAndSet(true, false)) {
                    throw IllegalStateException("SQLITE_BUSY (simulated)")
                }
                return base.pendingEvents(limit)
            }
        }

        val handled = CopyOnWriteArrayList<String>()
        val drain = TriggerEventDrain(flaky, scope, nowMillis = { now }, dispatchParallelism = 1)
        drain.handler = { e ->
            handled += e.eventId
            TriggerVerdict.Done
        }
        drain.start()
        try {
            TestAwait.condition(
                "库抖动之后循环必须自己再跑一轮（不许等下一个事件）",
                timeoutMs = 10_000L,
            ) { handled.contains("evt-recover") }
            assertEquals(TriggerEventStates.DONE, base.row("evt-recover").state)
        } finally {
            drain.stop()
        }
    }

    /**
     * **认领抛错不能吃掉并发许可**：并发额度是 1，一次泄漏就足以让整个消费循环
     * 永远卡在 `permits.acquire()` —— 循环还在、表里还有 pending、日志里只有那行异常。
     *
     * 与上面那条一起构成完整链路：单轮失败后必须重踢，重踢之后必须还有许可可用。
     * 旧实现两条都缺（许可不归还 + 不重踢），这条用例必然超时红。
     */
    @Test
    fun claimExceptionDoesNotLeakTheDispatchPermit() {
        val base = InMemorySchedulerStore()
        base.enqueueEvent(event("evt-a", createdAt = 1_000))
        base.enqueueEvent(event("evt-b", createdAt = 2_000))
        val failOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        val flaky = object : SchedulerStore by base {
            override fun claimEvent(sourceId: String, eventId: String): Boolean {
                if (failOnce.compareAndSet(true, false)) {
                    throw IllegalStateException("disk full (simulated)")
                }
                return base.claimEvent(sourceId, eventId)
            }
        }

        val handled = CopyOnWriteArrayList<String>()
        val drain = TriggerEventDrain(flaky, scope, nowMillis = { now }, dispatchParallelism = 1)
        drain.handler = { e ->
            handled += e.eventId
            TriggerVerdict.Done
        }
        drain.start()
        try {
            TestAwait.condition("认领异常之后两条事件都必须被消费", timeoutMs = 10_000L) {
                handled.size >= 2
            }
            assertEquals(setOf("evt-a", "evt-b"), handled.toSet())
        } finally {
            drain.stop()
        }
    }
}
