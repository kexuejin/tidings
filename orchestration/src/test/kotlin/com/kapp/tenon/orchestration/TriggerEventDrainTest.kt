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
        val rows = ConcurrentHashMap<String, TriggerEventRecord>()
        private val taskStates = ConcurrentHashMap<String, TaskRunState>()

        override fun taskRunStates(): List<TaskRunState> = taskStates.values.toList()

        override fun saveTaskRunState(state: TaskRunState) {
            taskStates[state.taskId] = state
        }

        override fun enqueueEvent(event: TriggerEventRecord): Boolean =
            rows.putIfAbsent(event.eventId, event) == null

        override fun pendingEvents(limit: Int): List<TriggerEventRecord> =
            rows.values.filter { it.state == TriggerEventStates.PENDING }
                .sortedBy { it.createdAt }
                .take(limit)

        override fun updateEventState(eventId: String, state: String, error: String?) {
            rows.computeIfPresent(eventId) { _, old -> old.copy(state = state, error = error, updatedAt = 0L) }
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

    /** 阻塞版轮询（JUnit 是阻塞式）：等到 probe >= size 或超时抛 AssertionError。 */
    private fun await(size: Int, probe: () -> Int, what: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (probe() >= size) return
            Thread.sleep(25)
        }
        throw AssertionError("10000ms 内没有等到：$what")
    }

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
            await(1, { handled.size }, "handler 恰好消费一次")
            assertEquals(
                TriggerEventStates.DONE,
                store.rows.getValue("evt-1").state,
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
            await(1, { handled.size }, "handler 消费")
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(2_000) {
                    while (handled.size > 1) kotlinx.coroutines.delay(25)
                    kotlinx.coroutines.delay(200)
                }
            }
            assertEquals(1, handled.size, "重放投递不许二次唤醒执行通道")
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
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (store.rows.getValue("evt-err").state != TriggerEventStates.FAILED) {
                        kotlinx.coroutines.delay(25)
                    }
                }
            }
            val row = store.rows.getValue("evt-err")
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
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (store.rows.getValue("evt-throw").state != TriggerEventStates.FAILED) {
                        kotlinx.coroutines.delay(25)
                    }
                }
            }
            assertTrue(
                store.rows.getValue("evt-throw").error!!.contains("boom"),
                "异常信息应进 error 列：" + store.rows.getValue("evt-throw").error,
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
            await(2, { handled.size }, "两个遗留事件按 created_at 顺序恢复消费")
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
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (store.rows.getValue("evt-orphan").state != TriggerEventStates.FAILED) {
                        kotlinx.coroutines.delay(25)
                    }
                }
            }
            assertTrue(
                store.rows.getValue("evt-orphan").error!!.contains("no trigger handler"),
                "无 handler 必须如实翻失败而不是无声吞事件：" + store.rows.getValue("evt-orphan").error,
            )
        } finally {
            drain.stop()
        }
    }
}
