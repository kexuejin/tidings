// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.schedule.CronExpression
import com.kapp.tenon.agent.schedule.MissedFirePolicies
import com.kapp.tenon.domain.SchedulerStore
import com.kapp.tenon.domain.TaskRunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
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
 * **再**派发——推进先于派发，同一触发窗口不会派两次。这条顺序不是风格问题：
 * 反过来（先派后记）时，进程在「派完还没落账」之间被 kill，重启后 nextRunAt 还是
 * 旧值，同一窗口会被再派一次；而先落账只会在同样时机漏跑一次，下个窗口照常。
 * 重复执行的代价是双倍 Token 与双份副作用，漏跑的代价是晚一个周期 —— 选后者。
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
    /**
     * 单次 cron 任务执行的上限。到点即取消。
     *
     * 没有这个上限时，一个挂死的执行体会永久占住一个许可；工作池只有 2~4 个许可，
     * 两次挂死就等于整个调度停摆，而表面上什么错都没有。
     */
    private val dispatchTimeoutMs: Long = 10 * 60_000L,
) {

    companion object {
        /** 单轮扫描最多补计的错过窗口数：正常机器醒不来这么久；到顶即饱和记账。 */
        const val MAX_MISSED_WINDOWS_PER_SCAN: Int = 10_000

        /**
         * 「这条 cron 再也不会触发」的哨兵值（例：`0 0 30 2 *` —— 2 月没有 30 号）。
         *
         * 旧实现在 `nextAfter(...) == null` 时填 `now + 86_400_000`：于是一条**永远
         * 不该跑**的任务每天被派发一次，既没有日志也没有任何东西能发现它是假的。
         * 扫描时用 `nextRunAt == NEVER_FIRES` 显式跳过，且必须在算 `+ jitter` 之前判
         * （`Long.MAX_VALUE + 正数` 会溢出成负数，反而变成「早就到期」）。
         */
        const val NEVER_FIRES: Long = Long.MAX_VALUE

        private const val TAG = "RELAY-SCHEDULER"

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

    /**
     * 上一次注册时看到的 cron 表达式。
     *
     * 用来判断「清单里的 schedule 改了」：`TaskRunState` 里只存 nextRunAt，不存
     * 它是按哪条表达式算的，所以表达式一变，台账里的 nextRunAt 就是旧口径的陈旧值，
     * 不重算的话新 schedule 要等旧窗口到期才生效（可能是一天后）。
     *
     * 已知局限：这张表在内存里，进程重启后为空 —— 「宿主关着的时候插件改了
     * schedule」这种情况仍会沿用一次旧 nextRunAt。彻底解决要把 schedule 一起落进
     * 台账（加列 + 迁移），本轮不做。
     */
    private val registeredSchedules = ConcurrentHashMap<String, String>()

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
            registeredSchedules.remove(id)
        }
    }

    private fun registerOne(task: CronTaskDef, now: Long) {
        val cron = CronExpression.parse(task.schedule)
        val zone = zoneOf(task)
        tasks[task.taskId] = task
        crons[task.taskId] = cron
        val previousSchedule = registeredSchedules.put(task.taskId, task.schedule)
        val existing = store.taskRunStates().firstOrNull { it.taskId == task.taskId }
        // 首装（无台账行）或 schedule 改了，都要按**当前**表达式重算下次触发点。
        // 只判 existing == null 是不够的：schedule 变更时台账里的 nextRunAt 是按旧
        // 表达式算的，不重算就等旧窗口到期才生效。
        val scheduleChanged = previousSchedule != null && previousSchedule != task.schedule
        if (existing == null || scheduleChanged) {
            val nextRunAt = cron.nextAfter(now, zone)
            if (nextRunAt == null) {
                // 语法合法但永远不会触发（如 2 月 30 日）。留日志：否则用户只会看到
                // 「我配的定时任务从来不跑」，而没有任何线索指向 schedule 本身。
                RelayLog.info(TAG, "task " + task.taskId + " schedule never fires: " + task.schedule)
            }
            store.saveTaskRunState(
                TaskRunState(
                    taskId = task.taskId,
                    lastRunAt = existing?.lastRunAt,
                    nextRunAt = nextRunAt ?: NEVER_FIRES,
                    jitterOffsetSec = jitterOffsetFor(task.taskId, task.jitterSeconds),
                    missedRuns = existing?.missedRuns ?: 0,
                    updatedAt = now,
                ),
            )
            if (scheduleChanged) {
                RelayLog.info(TAG, "task " + task.taskId + " schedule changed (" + previousSchedule + " -> " + task.schedule + "); next run recomputed")
            }
        }
        // 已有运行态且 schedule 没变（重启 / 重复注册）：原样保留——这正是持久化台账存在的意义。
    }

    fun start() {
        check(scanJob == null) { "DesktopHostScheduler already started" }
        scanJob = scope.launch {
            while (isActive) {
                try {
                    scanTick()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // 旧实现是 `runCatching { scanTick() }`：异常被静默吞掉，循环照转，
                    // 但每一拍都在同一个地方失败，用户侧只看到「定时任务再也不跑了」。
                    RelayLog.info(TAG, "scheduler scan tick failed: " + t.toString())
                }
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
                    nextRunAt = cron.nextAfter(now, zone) ?: NEVER_FIRES,
                    jitterOffsetSec = jitterOffsetFor(task.taskId, task.jitterSeconds),
                    missedRuns = 0,
                    updatedAt = now,
                )
                store.saveTaskRunState(fresh)
                fresh
            }

            // 永不触发的任务不参与扫描。必须在算 `+ jitterMs` **之前**返回：
            // NEVER_FIRES = Long.MAX_VALUE，加上正偏移会溢出成负数，判据反转成「早已到期」。
            if (state.nextRunAt == NEVER_FIRES) continue

            val jitterMs = state.jitterOffsetSec * 1000L
            if (state.nextRunAt + jitterMs > now) continue

            // 到期窗口计数：从旧 nextRunAt 起所有 fire + jitter <= now 的触发点。
            // 逐窗口推进，封顶饱和——十年不眠不休的宿主不该拖死一次扫描。
            //
            // 循环结束时 cursor 指向**第一个尚未到期的窗口**，它就是新的 nextRunAt。
            // 不能用 `cron.nextAfter(now, zone)` 代替：偏移为负时（jitter < 0），
            // now 可能还没到窗口本体的时刻（例如 8:00 的任务偏移 -5min，现在 7:56），
            // nextAfter(now) 会返回**同一个** 8:00 —— 下一拍它又「到期」，同一窗口派两次。
            var cursor = state.nextRunAt
            var dueWindows = 0
            var exhausted = false
            while (cursor + jitterMs <= now) {
                dueWindows++
                val next = cron.nextAfter(cursor, zone)
                if (next == null) {
                    // 再也不会触发：本窗口跑完后把台账钉在哨兵值上。
                    exhausted = true
                    break
                }
                cursor = next
                if (dueWindows >= MAX_MISSED_WINDOWS_PER_SCAN) break
            }

            val runOnce = task.missedFirePolicy == MissedFirePolicies.RUN_ONCE
            // **派发判据与补偿判据是两件事**，不能拿 runOnce 一个布尔兼任：
            //  - RUN_ONCE：到期就补跑一次，无论积压了多少窗口；
            //  - SKIP：放弃的是「积压批次」（dueWindows > 1，宿主合盖/断电醒晚了），
            //    按 §2.2 等下一个自然窗口；dueWindows == 1 是**正常到点**的自然触发，
            //    必须照常派发。
            //
            // 旧实现把 runOnce 直接当派发开关 ⇒ SKIP 任务永远不跑：扫描节拍 15s，
            // 正常运行时每拍恰好只看到 1 个到期窗口，于是「放弃错过的批次」被误读成
            // 「放弃每一次触发」。症状是配了 cron 从来不执行，且台账 missedRuns 一直涨。
            //
            // 边界如实记在这里：宿主只睡过**恰好一个**窗口时，单窗口判据分不出「准点」
            // 与「晚到」，会跟 RUN_ONCE 一样跑一次；两态的差异在积压 ≥2 个窗口时才
            // 显现。要严格区分得把「检测时刻是否偏离 fire 时刻」也落进台账，本轮不做。
            val shouldDispatch = runOnce || dueWindows == 1
            // missedRuns = 未补跑的窗口数：派发时补跑最后一个到期窗口（其余全算
            // 错过）；放弃时一个都不跑（全部算错过）。补跑仅 1 次，严禁并发补发（§2.2）。
            val missed = if (shouldDispatch) (dueWindows - 1).coerceAtLeast(0) else dueWindows

            val nextRunAt = when {
                exhausted -> NEVER_FIRES
                cursor + jitterMs > now -> cursor
                // 只在封顶饱和时走到：窗口太多，用 now 重算（可能仍有残余，下一拍继续消化）。
                else -> cron.nextAfter(now, zone) ?: NEVER_FIRES
            }

            // 先落账，再派发。见类 KDoc 的「并发纪律」。
            store.saveTaskRunState(
                state.copy(
                    lastRunAt = if (shouldDispatch) now else state.lastRunAt,
                    nextRunAt = nextRunAt,
                    missedRuns = state.missedRuns + missed,
                    updatedAt = now,
                ),
            )
            if (shouldDispatch) {
                dispatchBounded(task)
            }
        }
    }

    /**
     * 有界派发。许可在**调用方**（扫描协程）里拿，而不是在 `launch` 里面拿：
     * 后者会给每个到期任务起一个协程去排队，池子越小积压的协程越多，且没有任何上限
     * ——旧实现就是这样，且 `acquire()` 没有超时。许可先拿，扫描自然被压回执行速度，
     * 在飞协程数恒等于池大小。
     */
    private suspend fun dispatchBounded(task: CronTaskDef) {
        dispatchPermits.acquire()
        val job = scope.launch {
            try {
                withTimeout(dispatchTimeoutMs) { executeCronTask(task) }
            } catch (timeout: TimeoutCancellationException) {
                // TimeoutCancellationException 是 CancellationException 的子类，
                // 必须先于它捕获，否则超时会被当成「正常取消」静默上抛。
                RelayLog.info(TAG, "cron task " + task.taskId + " timed out after " + dispatchTimeoutMs + "ms; cancelled")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                RelayLog.info(TAG, "cron task " + task.taskId + " failed: " + t.toString())
            }
        }
        // 许可挂在 job 完成回调上：作用域已取消时协程体不会执行，写在协程体里的
        // finally 也就不会跑，许可会被永久吃掉。
        job.invokeOnCompletion { dispatchPermits.release() }
    }

    private fun zoneOf(task: CronTaskDef): ZoneId =
        task.timezoneId?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
}
