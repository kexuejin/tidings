// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.schedule.CronExpression
import com.kapp.tenon.agent.schedule.MissedFirePolicies
import com.kapp.tenon.domain.SchedulerStore
import com.kapp.tenon.domain.TaskRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * cron 触发任务的定义（调度中枢的输入）。阶段三由插件 manifest 的 `triggers`
 * 映射而来；阶段二先以注册 API 供引擎测试与宿主内建任务使用。
 */
data class CronTaskDef(
    /** 全局唯一：pluginId + "/" + trigger.id。 */
    val taskId: String,
    /** 5 字段 cron（分 时 日 月 周），解析器唯一来源 [CronExpression]。 */
    val schedule: String,
    /** IANA 时区名；null = 宿主本地时区（按墙钟触发）。 */
    val timezoneId: String?,
    /** Jitter 幅度（秒）；宿主在 [-N, +N] 内为任务派生**固定**偏移，重启不漂移。 */
    val jitterSeconds: Int,
    /** [MissedFirePolicies] 两态之一。 */
    val missedFirePolicy: String,
    /** 唤醒派单的 prompt（触发式任务走独立只读执行通道，见 TriggerExecutor）。 */
    val prompt: String,
    /** 回投会话；null = 不回卡（结果去哪由执行出口决定）。 */
    val targetConvId: String?,
    /** 执行体标识（CLI 驱动 key 或插件 leader agentId）。 */
    val agentKey: String,
)

/**
 * 桌面宿主中心化调度中枢（触发式编排提案 §2）——全网唯一的时钟 Owner。
 *
 * 三条铁律都在这个类里兑现：
 * 1. **时钟权威属于宿主**：插件只声明「我希望 0 8 * * 1-5 被唤醒」，绝不允许插件
 *    自持 `while(true) { sleep }`——本类是唯一扫表与派单的地方；
 * 2. **missed-fire 补偿**（[MissedFirePolicies]）：合盖休眠/断电重启后，运行态在
 *    [SchedulerStore]（持久化 Job Table）里活着，扫描时按策略补跑或放弃，并如实
 *    记账错过的窗口数——严禁并发补发；
 * 3. **削峰**：逐任务固定 Jitter 偏移 + 有界工作池，多任务同刻到期不雪崩。
 *
 * 并发纪律：扫描是单循环（[start] 起一个协程），到期的任务先把运行态推进
 * **再**派发——推进先于派发，同一触发窗口不会派两次。
 */
class DesktopHostScheduler(
    private val store: SchedulerStore,
    private val scope: CoroutineScope,
    /** cron 任务执行出口（TriggerExecutor 落地只读通道；测试用 fake）。 */
    private val executeCronTask: suspend (CronTaskDef) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 扫描节拍（毫秒）；真实负载下每拍做的是一次内存比对，15s 足够贴刻度。 */
    private val scanTickMs: Long = 15_000L,
    /** 并发工作池上限（提案 §2.3：桌面默认 2~4）。 */
    private val workerPoolSize: Int = 2,
) {

    companion object {
        /** 单轮扫描最多补计的错过窗口数：正常机器醒不来这么久；到顶即饱和记账。 */
        const val MAX_MISSED_WINDOWS_PER_SCAN: Int = 10_000

        /**
         * 逐任务固定 Jitter 偏移：taskId 哈希派生，同一任务每天同一偏移（§2.3），
         * 重启后不漂移——这也是不用随机数的原因。
         */
        fun jitterOffsetFor(taskId: String, amplitudeSec: Int): Int {
            if (amplitudeSec <= 0) return 0
            val span = amplitudeSec * 2 + 1
            return Math.floorMod(taskId.hashCode(), span) - amplitudeSec
        }
    }

    private val tasks = ConcurrentHashMap<String, CronTaskDef>()
    private val crons = ConcurrentHashMap<String, CronExpression>()
    private val dispatchPermits = Semaphore(workerPoolSize)
    private var scanJob: Job? = null

    /** 注册（或刷新）cron 任务。清单校验已在注册入口做过；这里再解析一遍是同一份实现。 */
    fun registerCronTasks(definitions: List<CronTaskDef>) {
        val now = nowMillis()
        definitions.forEach { task ->
            registerOne(task, now)
        }
    }

    /**
     * 以给定清单为准对账：新增/刷新注册，清单里已经没有的任务移除（插件注销后
     * 不残响——否则注销掉的插件的 cron 任务会一直扫表派发）。运行态台账行保留：
     * missedRuns 是审计史，不是任务注册表。
     */
    fun syncCronTasks(definitions: List<CronTaskDef>) {
        val now = nowMillis()
        definitions.forEach { registerOne(it, now) }
        val keep = definitions.map { it.taskId }.toSet()
        tasks.keys.filter { it !in keep }.forEach { id ->
            tasks.remove(id)
            crons.remove(id)
        }
    }

    private fun registerOne(task: CronTaskDef, now: Long) {
        val cron = CronExpression.parse(task.schedule)
        val zone = zoneOf(task)
        tasks[task.taskId] = task
        crons[task.taskId] = cron
        val existing = store.taskRunStates().firstOrNull { it.taskId == task.taskId }
        if (existing == null) {
            store.saveTaskRunState(
                TaskRunState(
                    taskId = task.taskId,
                    lastRunAt = null,
                    // 从「现在」起算下一个触发点；错过的历史窗口不追（首装语义）。
                    nextRunAt = cron.nextAfter(now, zone) ?: (now + 86_400_000L),
                    jitterOffsetSec = jitterOffsetFor(task.taskId, task.jitterSeconds),
                    missedRuns = 0,
                    updatedAt = now,
                ),
            )
        }
        // 已有运行态（重启 / 重复注册）：原样保留——这正是持久化台账存在的意义。
    }

    fun start() {
        check(scanJob == null) { "DesktopHostScheduler already started" }
        scanJob = scope.launch {
            while (isActive) {
                runCatching { scanTick() }
                delay(scanTickMs)
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
    }

    /** 单轮扫描：判到期 → missed-fire 补偿 → 推进运行态 → 有界派发。测试直接驱动。 */
    internal suspend fun scanTick() {
        val now = nowMillis()
        val states = store.taskRunStates().associateBy { it.taskId }
        for (task in tasks.values) {
            val cron = crons.getValue(task.taskId)
            val zone = zoneOf(task)
            val state = states[task.taskId] ?: run {
                // 注册后状态行被外部清掉（新库/手清）：按首装语义重建，不追历史。
                val fresh = TaskRunState(
                    taskId = task.taskId,
                    lastRunAt = null,
                    nextRunAt = cron.nextAfter(now, zone) ?: (now + 86_400_000L),
                    jitterOffsetSec = jitterOffsetFor(task.taskId, task.jitterSeconds),
                    missedRuns = 0,
                    updatedAt = now,
                )
                store.saveTaskRunState(fresh)
                fresh
            }

            val jitterMs = state.jitterOffsetSec * 1000L
            if (state.nextRunAt + jitterMs > now) continue

            // 到期窗口计数：从旧 nextRunAt 起所有 fire + jitter <= now 的触发点。
            // 逐窗口推进，封顶饱和——十年不眠不休的宿主不该拖死一次扫描。
            var fire = state.nextRunAt
            var dueWindows = 0
            while (fire + jitterMs <= now) {
                dueWindows++
                val next = cron.nextAfter(fire, zone) ?: break
                fire = next
                if (dueWindows >= MAX_MISSED_WINDOWS_PER_SCAN) break
            }

            val runOnce = task.missedFirePolicy == MissedFirePolicies.RUN_ONCE
            // missedRuns = 未补跑的窗口数：RUN_ONCE 补跑最后一个到期窗口（其余全算
            // 错过）；SKIP 一个都不跑（全部算错过）。补跑仅 1 次，严禁并发补发（§2.2）。
            val missed = if (runOnce) (dueWindows - 1).coerceAtLeast(0) else dueWindows
            if (runOnce) {
                dispatchBounded(task)
            }

            val nextRunAt = cron.nextAfter(now, zone) ?: (now + 86_400_000L)
            store.saveTaskRunState(
                state.copy(
                    lastRunAt = if (runOnce) now else state.lastRunAt,
                    nextRunAt = nextRunAt,
                    missedRuns = state.missedRuns + missed,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun dispatchBounded(task: CronTaskDef) {
        scope.launch {
            dispatchPermits.acquire()
            try {
                executeCronTask(task)
            } finally {
                dispatchPermits.release()
            }
        }
    }

    private fun zoneOf(task: CronTaskDef): ZoneId =
        task.timezoneId?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
}
