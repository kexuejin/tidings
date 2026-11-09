// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.schedule.MissedFirePolicies
import com.kapp.tenon.domain.SchedulerStore
import com.kapp.tenon.domain.TaskRunState
import com.kapp.tenon.domain.TriggerEventRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 桌面宿主调度中枢引擎（提案 §2.2 / §2.3）。
 *
 * 判据全部落在「可观测事实」上：派发计数、运行态台账的推进值、并发峰值——
 * 不赌派发协程的时序（latch + 轮询，与 agentNode 的 Await 同一纪律）。
 */
class DesktopHostSchedulerTest {

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

    /** 内存版台账 fake：只镜像接口语义，SQLDelight 落地已在 :app 的 SchedulerStoreTest 钉过。 */
    private class InMemorySchedulerStore : SchedulerStore {
        val taskStates = LinkedHashMap<String, TaskRunState>()

        override fun taskRunStates(): List<TaskRunState> = taskStates.values.toList()

        override fun saveTaskRunState(state: TaskRunState) {
            taskStates[state.taskId] = state
        }

        override fun enqueueEvent(event: TriggerEventRecord): Boolean = true

        override fun pendingEvents(limit: Int): List<TriggerEventRecord> = emptyList()

        override fun updateEventState(eventId: String, state: String, error: String?) = Unit

        override fun pruneTerminalEventsBefore(epochMs: Long) = Unit
    }

    private fun task(
        taskId: String = "mail-assistant/morning",
        schedule: String = "* * * * *",
        policy: String = MissedFirePolicies.RUN_ONCE,
        jitterSeconds: Int = 0,
    ) = CronTaskDef(
        taskId = taskId,
        schedule = schedule,
        timezoneId = "Asia/Shanghai",
        jitterSeconds = jitterSeconds,
        missedFirePolicy = policy,
        prompt = "巡检并汇报",
        targetConvId = null,
        agentKey = "codex",
    )

    /** 对齐到整分钟，避免 cron 判定吃进秒位噪声。 */
    private fun aligned(ms: Long): Long {
        val zoned = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Asia/Shanghai"))
        val aligned = zoned.withSecond(0).withNano(0).plusMinutes(1)
        return aligned.toInstant().toEpochMilli()
    }

    @Test
    fun jitterOffsetIsStableAcrossRestartsAndInRange() {
        for (amplitude in listOf(0, 5, 30)) {
            val first = DesktopHostScheduler.jitterOffsetFor("mail-assistant/morning", amplitude)
            val second = DesktopHostScheduler.jitterOffsetFor("mail-assistant/morning", amplitude)
            assertEquals(first, second, "同一任务同一偏移（重启不漂移，§2.3）")
            assertTrue(first in -amplitude..amplitude, "偏移必须在 [-N, +N] 内：$first vs ±$amplitude")
        }
        assertEquals(0, DesktopHostScheduler.jitterOffsetFor("x", 0), "幅度 0 = 无扰动")
    }

    @Test
    fun dueTaskDispatchesOnceAndAdvancesNextRunAt() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(store, scope, executeCronTask = { dispatches.incrementAndGet() }, nowMillis = { now })

        scheduler.registerCronTasks(listOf(task()))
        val initial = store.taskStates.getValue("mail-assistant/morning")
        // 人为把 nextRunAt 拨到已到期（一分钟粒度的每分钟任务）。
        val dueAt = aligned(now - 60_000)
        store.saveTaskRunState(initial.copy(nextRunAt = dueAt, jitterOffsetSec = 0))

        kotlinx.coroutines.runBlocking { scheduler.scanTick() }

        val awaited = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (dispatches.get() < 1) kotlinx.coroutines.delay(25)
                dispatches.get()
            }
        }
        assertEquals(1, awaited, "到期任务应恰好派发一次")

        val after = store.taskStates.getValue("mail-assistant/morning")
        assertTrue(after.nextRunAt > now, "nextRunAt 必须推进到未来，否则下一拍会重复派发")
        assertEquals(0, after.missedRuns, "只错过这一个窗口且已补跑，missedRuns 不计")
        assertTrue(after.lastRunAt == now, "lastRunAt 应落账为本次补跑时刻")
    }

    @Test
    fun missedFireRunOnceCountsAbandonedWindows() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(store, scope, executeCronTask = { dispatches.incrementAndGet() }, nowMillis = { now })

        scheduler.registerCronTasks(listOf(task(policy = MissedFirePolicies.RUN_ONCE)))
        val initial = store.taskStates.getValue("mail-assistant/morning")
        // 合盖 6 分钟后醒来：6 个窗口全部到期。
        val dueAt = aligned(now - 6 * 60_000)
        store.saveTaskRunState(initial.copy(nextRunAt = dueAt, jitterOffsetSec = 0))

        kotlinx.coroutines.runBlocking { scheduler.scanTick() }

        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (dispatches.get() < 1) kotlinx.coroutines.delay(25)
            }
        }
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (dispatches.get() > 1) kotlinx.coroutines.delay(25)
                kotlinx.coroutines.delay(200)
            }
        }
        assertEquals(1, dispatches.get(), "RUN_ONCE 无论错过多少窗口只补跑 1 次（严禁并发补发）")
        val after = store.taskStates.getValue("mail-assistant/morning")
        assertEquals(5, after.missedRuns, "6 个到期窗口补跑 1 个，其余 5 个如实记账")
        assertTrue(after.nextRunAt > now)
    }

    @Test
    fun missedFireSkipAbandonsAllWindowsWithoutDispatch() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(store, scope, executeCronTask = { dispatches.incrementAndGet() }, nowMillis = { now })

        scheduler.registerCronTasks(listOf(task(policy = MissedFirePolicies.SKIP)))
        val initial = store.taskStates.getValue("mail-assistant/morning")
        val dueAt = aligned(now - 6 * 60_000)
        store.saveTaskRunState(initial.copy(nextRunAt = dueAt, jitterOffsetSec = 0))

        kotlinx.coroutines.runBlocking { scheduler.scanTick() }
        Thread.sleep(200)
        assertEquals(0, dispatches.get(), "SKIP 放弃错过的批次，不派发")

        val after = store.taskStates.getValue("mail-assistant/morning")
        assertEquals(6, after.missedRuns, "SKIP 下全部到期窗口都算未补跑")
        assertTrue(after.nextRunAt > now, "nextRunAt 照样推进，等待下一次自然触发")
        assertTrue(after.lastRunAt == null, "从未跑过，lastRunAt 保持 null")
    }

    @Test
    fun notDueTaskIsNotDispatched() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(store, scope, executeCronTask = { dispatches.incrementAndGet() }, nowMillis = { now })

        scheduler.registerCronTasks(listOf(task()))
        val initial = store.taskStates.getValue("mail-assistant/morning")
        store.saveTaskRunState(initial.copy(nextRunAt = aligned(now + 3600_000)))

        kotlinx.coroutines.runBlocking { scheduler.scanTick() }
        Thread.sleep(200)
        assertEquals(0, dispatches.get(), "未到期的任务不许派发")
        assertEquals(0, store.taskStates.getValue("mail-assistant/morning").missedRuns)
    }

    @Test
    fun workerPoolBoundsConcurrentDispatches() {
        val store = InMemorySchedulerStore()
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val release = CountDownLatch(1)
        val started = CountDownLatch(2)

        val scheduler = DesktopHostScheduler(
            store, scope,
            executeCronTask = {
                val current = inFlight.incrementAndGet()
                peak.updateAndGet { old -> maxOf(old, current) }
                started.countDown()
                release.await(15, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
            },
            nowMillis = { now },
            workerPoolSize = 2,
        )

        val taskIds = (1..4).map { "mail-assistant/task-$it" }
        scheduler.registerCronTasks(taskIds.map { task(taskId = it) })
        taskIds.forEach { id ->
            val state = store.taskStates.getValue(id)
            store.saveTaskRunState(state.copy(nextRunAt = aligned(now - 60_000)))
        }

        kotlinx.coroutines.runBlocking { scheduler.scanTick() }
        // 4 个任务全部到期，工作池只有 2 个许可：前 2 个先跑起来，其余排队。
        assertTrue(started.await(10, TimeUnit.SECONDS), "至少 2 个并发派发应已开始")
        Thread.sleep(300)
        assertEquals(2, peak.get(), "并发峰值必须等于工作池上限 2（多余任务排队而不是同时跑）")

        release.countDown()
        // 释放后全部排空。
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(15_000) {
                while (inFlight.get() > 0) kotlinx.coroutines.delay(25)
            }
        }
    }

    @Test
    fun restartKeepsPersistedRunState() {
        val store = InMemorySchedulerStore()
        val scheduler = DesktopHostScheduler(store, scope, executeCronTask = {}, nowMillis = { now })
        scheduler.registerCronTasks(listOf(task()))
        val first = store.taskStates.getValue("mail-assistant/morning")

        // 「重启」：同一定义再次注册（新进程读到同一台账）。
        val scheduler2 = DesktopHostScheduler(store, scope, executeCronTask = {}, nowMillis = { now })
        scheduler2.registerCronTasks(listOf(task()))

        val kept = store.taskStates.getValue("mail-assistant/morning")
        assertEquals(first, kept, "已有运行态不许被注册覆盖——missed-fire 判据靠它跨重启存活")
    }
}
