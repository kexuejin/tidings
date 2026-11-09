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
 * 不赌派发协程的时序：等到确定信号用 [TestAwait.condition]，证明「没有第二条」
 * 用 [TestAwait.quiet] 的有界观测窗，全模块共用这一份轮询助手。
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

        override fun eventExists(sourceId: String, eventId: String): Boolean = false

        override fun pendingEvents(limit: Int): List<TriggerEventRecord> = emptyList()

        /** 本用例集只测调度台账，从不入队事件：认领必然失败、恢复必然 0 行。 */
        override fun claimEvent(sourceId: String, eventId: String): Boolean = false

        override fun reclaimRunningEvents(): Int = 0

        override fun updateEventState(
            sourceId: String,
            eventId: String,
            state: String,
            error: String?,
        ): Boolean = false

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

        TestAwait.condition("到期任务被派发") { dispatches.get() >= 1 }
        TestAwait.quiet("同一触发窗口不许派发第二次") { dispatches.get() > 1 }
        assertEquals(1, dispatches.get(), "到期任务应恰好派发一次")

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

        TestAwait.condition("RUN_ONCE 补跑发生") { dispatches.get() >= 1 }
        TestAwait.quiet("严禁并发补发") { dispatches.get() > 1 }
        assertEquals(1, dispatches.get(), "RUN_ONCE 无论错过多少窗口只补跑 1 次（严禁并发补发）")
        val after = store.taskStates.getValue("mail-assistant/morning")
        assertEquals(5, after.missedRuns, "6 个到期窗口补跑 1 个，其余 5 个如实记账")
        assertTrue(after.nextRunAt > now)
    }

    /**
     * SKIP 的**积压**语义：合盖 6 分钟后醒来，放弃这批窗口、等下一个自然触发。
     * 「准点触发照常派发」由下面两条用例守 —— 两件事不是同一个判据。
     */
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
        TestAwait.quiet("SKIP 不该派发") { dispatches.get() > 0 }
        assertEquals(0, dispatches.get(), "SKIP 放弃错过的批次，不派发")

        val after = store.taskStates.getValue("mail-assistant/morning")
        assertEquals(6, after.missedRuns, "SKIP 下全部到期窗口都算未补跑")
        assertTrue(after.nextRunAt > now, "nextRunAt 照样推进，等待下一次自然触发")
        assertTrue(after.lastRunAt == null, "从未跑过，lastRunAt 保持 null")
    }

    /**
     * P0② 的核心判据：SKIP **不等于不跑**。
     *
     * 旧实现把 `runOnce`（补偿策略）直接当派发开关，于是每次扫描恰好只看到 1 个到期
     * 窗口的正常运行里，SKIP 任务连一次都跑不了 —— 配了 cron 却永远静默。
     * 反向验证：把派发判据改回 runOnce，这条用例红在「自然窗口未派发」。
     */
    @Test
    fun skipStillDispatchesItsNaturalWindow() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(
            store, scope,
            executeCronTask = { dispatches.incrementAndGet() },
            nowMillis = { now },
        )

        scheduler.registerCronTasks(listOf(task(policy = MissedFirePolicies.SKIP)))
        val taskId = "mail-assistant/morning"
        val initial = store.taskStates.getValue(taskId)
        // 单一到期窗口（每分钟任务的正常一拍），没有积压。
        val dueAt = aligned(now - 60_000)
        store.saveTaskRunState(initial.copy(nextRunAt = dueAt, jitterOffsetSec = 0))

        kotlinx.coroutines.runBlocking { scheduler.scanTick() }

        TestAwait.condition("SKIP 任务的自然窗口必须派发") { dispatches.get() >= 1 }
        TestAwait.quiet("同一窗口不许派发第二次") { dispatches.get() > 1 }
        assertEquals(1, dispatches.get(), "SKIP 只放弃积压批次，正常到点的窗口照常跑")

        val after = store.taskStates.getValue(taskId)
        assertEquals(0, after.missedRuns, "准点的自然窗口不算错过")
        assertTrue(after.nextRunAt > now, "nextRunAt 必须推进到未来")
        assertEquals(now, after.lastRunAt, "确实跑过，lastRunAt 要落账")
    }

    /**
     * 放弃积压之后的**恢复**：下一个自然窗口到达时，SKIP 任务必须重新跑起来。
     * 这条守的是「等待下一次自然触发时间到达」不是一句空话 —— 旧实现里它永远等不到。
     */
    @Test
    fun skipRunsAgainOnTheNextNaturalWindowAfterAbandoningABacklog() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(
            store, scope,
            executeCronTask = { dispatches.incrementAndGet() },
            nowMillis = { now },
        )

        scheduler.registerCronTasks(listOf(task(policy = MissedFirePolicies.SKIP)))
        val taskId = "mail-assistant/morning"
        val initial = store.taskStates.getValue(taskId)
        store.saveTaskRunState(
            initial.copy(nextRunAt = aligned(now - 6 * 60_000), jitterOffsetSec = 0),
        )

        // 第一拍：6 个窗口积压，全部放弃。
        kotlinx.coroutines.runBlocking { scheduler.scanTick() }
        TestAwait.quiet("积压批次不许补发") { dispatches.get() > 0 }
        assertEquals(0, dispatches.get(), "先确认这一拍确实没派发")

        // 台账推到的那个值就是「下一个自然触发时间」；走到它之后 5 秒再扫一拍。
        val nextNatural = store.taskStates.getValue(taskId).nextRunAt
        assertTrue(nextNatural > now, "第一拍必须把 nextRunAt 推到未来")
        now = nextNatural + 5_000L
        kotlinx.coroutines.runBlocking { scheduler.scanTick() }

        TestAwait.condition("下一个自然窗口必须派发") { dispatches.get() >= 1 }
        TestAwait.quiet("恢复后同一窗口不许派第二次") { dispatches.get() > 1 }
        assertEquals(1, dispatches.get(), "SKIP 任务不能因一次积压而永久停摆")
        assertEquals(6, store.taskStates.getValue(taskId).missedRuns, "上一拍的 6 个错过如实留账")
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
        TestAwait.quiet("未到期任务不许派发") { dispatches.get() > 0 }
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
        val finished = CountDownLatch(4)

        val scheduler = DesktopHostScheduler(
            store, scope,
            executeCronTask = {
                val current = inFlight.incrementAndGet()
                peak.updateAndGet { old -> maxOf(old, current) }
                started.countDown()
                release.await(15, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
                finished.countDown()
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
        TestAwait.quiet("工作池上限外的任务抢到并发", windowMs = 300) { peak.get() > 2 }
        assertEquals(2, peak.get(), "并发峰值必须等于工作池上限 2（多余任务排队而不是同时跑）")

        release.countDown()
        // 4 个任务各跑完一次是确定事件，不用「inFlight 恰好为 0」当探针——
        // 前两个结束、后两个尚未开始的瞬间它也是 0，会假绿。
        assertTrue(finished.await(15, TimeUnit.SECONDS), "释放后 4 个任务应全部排空")
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

    /** 对账式同步（C10）：清单里已消失的任务不再扫描（插件注销后不残响）。 */
    @Test
    fun syncCronTasksRemovesStaleDefinitions() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(store, scope, executeCronTask = { dispatches.incrementAndGet() }, nowMillis = { now })

        val a = task(taskId = "mail-assistant/a")
        val b = task(taskId = "mail-assistant/b")
        scheduler.registerCronTasks(listOf(a, b))
        listOf(a, b).forEach { def ->
            val state = store.taskStates.getValue(def.taskId)
            store.saveTaskRunState(state.copy(nextRunAt = aligned(now - 60_000)))
        }

        // 插件注销了任务 b：对账后只剩 a。
        scheduler.syncCronTasks(listOf(a))
        kotlinx.coroutines.runBlocking { scheduler.scanTick() }

        TestAwait.condition("保留的任务 a 被派发") { dispatches.get() >= 1 }
        TestAwait.quiet("被移除的任务 b 不许残响") { dispatches.get() > 1 }
        assertEquals(1, dispatches.get(), "被移除的任务 b 不许再派发（残响 = 注销失效）")
        assertTrue(store.taskStates.containsKey("mail-assistant/b"), "运行态台账行保留——missedRuns 是审计史")
    }

    /**
     * **负偏移不许把同一个窗口派两次。**
     *
     * 旧实现把 `nextRunAt` 算成 `cron.nextAfter(now, zone)`。偏移为负时，`now` 可能
     * 还没走到窗口本体的时刻（这里：窗口 8:00，偏移 -30s，现在 7:59:50），
     * `nextAfter(now)` 会返回**同一个** 8:00 —— 下一拍它又满足「到期」，同一窗口
     * 再派一次。任务于是每窗口跑两遍，且没有任何日志。
     *
     * 判据落在 `nextRunAt` 的推进值上（确定、不看时序），再用第二拍证明不派。
     */
    @Test
    fun negativeJitterDoesNotRedispatchTheSameWindow() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(store, scope, executeCronTask = { dispatches.incrementAndGet() }, nowMillis = { now })

        scheduler.registerCronTasks(listOf(task(schedule = "* * * * *", jitterSeconds = 30)))
        val taskId = "mail-assistant/morning"
        val minute = aligned(now) // 下一个整分 = 窗口本体时刻
        store.saveTaskRunState(
            store.taskStates.getValue(taskId).copy(nextRunAt = minute, jitterOffsetSec = -30),
        )

        // 落在「偏移后的触发时刻（整分 -30s）」之后、整分之前：窗口已到期。
        now = minute - 10_000L
        kotlinx.coroutines.runBlocking { scheduler.scanTick() }
        TestAwait.condition("偏移到期后派发一次") { dispatches.get() >= 1 }

        assertEquals(
            minute + 60_000L,
            store.taskStates.getValue(taskId).nextRunAt,
            "下一次必须推进到下一个窗口。旧实现算 nextAfter(now)，而 now 还在整分之前，" +
                "会算回同一个整分 → 下一拍再派一次（同一窗口双派）",
        )

        // 第二拍：整分刚过，仍属于「已经派过」的那个窗口 —— 不许再派。
        now = minute + 5_000L
        kotlinx.coroutines.runBlocking { scheduler.scanTick() }
        TestAwait.quiet("同一窗口不许二次派发", windowMs = 300) { dispatches.get() > 1 }
        assertEquals(1, dispatches.get(), "同一窗口只派一次")
    }

    /**
     * **永不触发的 cron 一次都不许派。**
     *
     * `0 0 30 2 *` = 2 月 30 日：语法合法（日 1-31、月 1-12 都在取值域内），
     * 但 `nextAfter` 永远返回 null。旧实现在这里填 `now + 86_400_000`，于是一条
     * 根本不该跑的任务每天被派发一次 —— 没有日志，也没法从现象反推是 schedule 的错。
     */
    @Test
    fun impossibleCronNeverDispatches() {
        val store = InMemorySchedulerStore()
        val dispatches = AtomicInteger(0)
        val scheduler = DesktopHostScheduler(store, scope, executeCronTask = { dispatches.incrementAndGet() }, nowMillis = { now })

        scheduler.registerCronTasks(listOf(task(schedule = "0 0 30 2 *")))
        val taskId = "mail-assistant/morning"
        assertEquals(
            DesktopHostScheduler.NEVER_FIRES,
            store.taskStates.getValue(taskId).nextRunAt,
            "永不触发的 cron 必须落哨兵值，而不是 now + 1 天（那等于每天假派发一次）",
        )

        // 连扫 400 天：一次都不许派（旧实现会派 400 次）。
        repeat(400) {
            now += 86_400_000L
            kotlinx.coroutines.runBlocking { scheduler.scanTick() }
        }
        assertEquals(0, dispatches.get(), "永不触发的 cron 一次都不许派发")
    }
}
