// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.db.RelayDatabase
import com.kapp.tenon.db.SchedulerTaskState
import com.kapp.tenon.db.TriggerEvent

/**
 * 桌面宿主调度中枢的持久化台账接口（触发式编排提案 §9 阶段二）。
 *
 * 两个半边：
 * - **任务运行态**（[TaskRunState]）：manifest triggers 是「声明什么」，这里是
 *   「什么时候跑过 / 下次什么时候该跑」。调度器重启后的 missed-fire 补偿判定
 *   依赖它——内存台账做不到这一点。
 * - **触发事件队列**（[TriggerEventRecord]）：webhook 事件验签 ACK 后落一行，
 *   消费完翻终态。持久幂等键是 **(source_id, event_id)**：内存幂等表
 *   （SourceWebhookRegistry）用的也是 sourceId + event.id，两层必须同域。
 *   只用 event_id 会把「两个源各自投递同 id」的后一条静默吞掉。
 *
 * 落点纪律：本接口住在 `:app`（`:agentNode` 物理上不依赖 `:app`，不直接持库），
 * 消费方是 `:orchestration`（已依赖 `:app`）。`:orchestration` 不 import SQLDelight——
 * 依赖方向有守卫测试压着。
 */
interface SchedulerStore {

    fun taskRunStates(): List<TaskRunState>

    fun saveTaskRunState(state: TaskRunState)

    /**
     * 触发事件入队。返回 false = 幂等命中（同 (source_id, event_id) 已有行——重放
     * 投递被持久键吸收，不再入队）。并发同键入队时 PK 兜底只会留一行。
     */
    fun enqueueEvent(event: TriggerEventRecord): Boolean

    /**
     * 台账里是否已有这一行（不论状态）。
     *
     * 存在的理由：[enqueueEvent] 的 false 同时代表「重放」与「插入失败」，两者在
     * 类型上不可区分。webhook 的 ACK 需要的是「事件是否已经持久化」——进程重启后
     * 内存幂等表已失，迟到的重投必须算已受理，而不是被当成落库失败再回一次 503。
     */
    fun eventExists(sourceId: String, eventId: String): Boolean

    /** 按 created_at 顺序取待消费事件。**取走不等于认领**——必须再走 [claimEvent]。 */
    fun pendingEvents(limit: Int): List<TriggerEventRecord>

    /**
     * 原子认领：仅当行**仍然是 `pending`** 时翻成 `running`。
     *
     * 返回值不是装饰：[pendingEvents] 是「读」、这里才是「写」，两步之间别的消费者
     * 可能已经认领同一行。返回 false 表示本次没抢到，调用方**必须放弃派发**——
     * 旧接口返回 `Unit` 时这个竞态在类型上不可观测，只能靠注释提醒，结果是双派。
     *
     * [sourceId] 与 [eventId] 一起定位行：主键是复合键，只给 eventId 会在两个源
     * 用了同一个投递 id 时认领错行。
     */
    fun claimEvent(sourceId: String, eventId: String): Boolean

    /**
     * 崩溃恢复：把 `running` 行放回 `pending`，返回放回的行数。
     *
     * 只应在消费循环**启动时**调用一次：那一刻本进程不可能有在飞的事件，所以
     * 「看到 running 就是上一进程遗留」。放进扫描循环会把真正在跑的行重新派发。
     */
    fun reclaimRunningEvents(): Int

    /**
     * 翻状态；[error] 仅 failed/skipped 有意义，其余传 null。
     *
     * [sourceId] 与 [eventId] 一起定位行（复合主键）。
     *
     * @return false = 该行不存在（已被 prune 或被外部删掉）。
     */
    fun updateEventState(sourceId: String, eventId: String, state: String, error: String?): Boolean

    /** 终态行清理：台账不是归档库，消费完的事件按 TTL 让位。 */
    fun pruneTerminalEventsBefore(epochMs: Long)
}

/** 调度任务运行态。 */
data class TaskRunState(
    /** pluginId + "/" + trigger.id，同清单内唯一。 */
    val taskId: String,
    /** epoch ms；null = 从未跑过。 */
    val lastRunAt: Long?,
    /** epoch ms；调度扫描判据。`Long.MAX_VALUE` = 这条 cron 再也不会触发（见 `DesktopHostScheduler.NEVER_FIRES`）。 */
    val nextRunAt: Long,
    /** 逐任务固定 Jitter 偏移（秒，可为负）；同任务重启不漂移（提案 §2.3）。 */
    val jitterOffsetSec: Int,
    /** 错过而未补跑的窗口数：RUN_ONCE 只补 1 次，余数如实记账。 */
    val missedRuns: Int,
    val updatedAt: Long,
)

/** 触发事件（StandardSourceEvent 同形字段 + 队列态）。取值域见 [TriggerEventStates]。 */
data class TriggerEventRecord(
    /**
     * 发送方投递 id / 载荷 id / body 哈希（SourceWebhookRegistry 同源）。
     * 只在 [sourceId] 域内唯一——持久幂等键是二者组成的复合主键，不是本字段单独一个。
     * 这也是模板 `{{id}}` 与不可信数据块呈现给模型的那个 id，不许换成内部拼接键。
     */
    val eventId: String,
    /** 事件源（webhook sourceId）。 */
    val sourceId: String,
    val type: String,
    val subject: String,
    val summary: String,
    val detail: String,
    val link: String?,
    val state: String,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 触发事件取值域唯一来源（AGENTS §3 #7：键 + 取值域只能有一份定义，编解码校验
 * 与所有消费方共同引用）。
 */
object TriggerEventStates {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    /** 被 filter 规则链前置过滤掉的事件：如实记账，不是丢失。 */
    const val SKIPPED = "skipped"

    val VALUES: Set<String> = setOf(PENDING, RUNNING, DONE, FAILED, SKIPPED)
    fun isKnown(state: String): Boolean = state in VALUES
}

/** SQLDelight 落地。表与具名查询的真身在 RelayDatabase.sq（21.sqm 迁移补到老库）。 */
class SqlDelightSchedulerStore(
    database: RelayDatabase,
    /**
     * 时钟注入：prune/翻态时间戳的测试钉子。
     *
     * 默认值走 [com.kapp.tenon.core.nowMillis]（三端都有 actual），**不能**写
     * `System::currentTimeMillis` —— 本文件在 `commonMain`，`java.lang.System`
     * 在 Kotlin/Native 上不存在，iOS 目标会直接编不过；而 CI 只跑 JVM/Android
     * 任务（`.github/workflows/ci.yml`），这条错误永远不会被任何门禁看见。
     * 见 `core/.../Platform.kt` 的「commonMain 里禁止使用 JVM 专属 API」。
     *
     * 写成 lambda 而非方法引用，是为了避开与同名参数的自引用歧义。
     */
    private val nowMillis: () -> Long = { com.kapp.tenon.core.nowMillis() },
) : SchedulerStore {

    private val queries = database.relayDatabaseQueries

    override fun taskRunStates(): List<TaskRunState> =
        queries.selectSchedulerTaskStates().executeAsList().map { it.toRunState() }

    override fun saveTaskRunState(state: TaskRunState) {
        queries.upsertSchedulerTaskState(
            task_id = state.taskId,
            last_run_at = state.lastRunAt,
            next_run_at = state.nextRunAt,
            jitter_offset_sec = state.jitterOffsetSec.toLong(),
            missed_runs = state.missedRuns.toLong(),
            updated_at = state.updatedAt,
        )
    }

    override fun enqueueEvent(event: TriggerEventRecord): Boolean {
        // 先查后插 + 复合主键兜底：并发同 (source_id, event_id) 入队时
        // INSERT OR IGNORE 只留一行，两个调用方最多各 kick 一次 drain（drain 幂等）。
        if (eventExists(event.sourceId, event.eventId)) return false
        queries.insertTriggerEventIgnore(
            event_id = event.eventId,
            source_id = event.sourceId,
            type = event.type,
            subject = event.subject,
            summary = event.summary,
            detail = event.detail,
            link = event.link,
            state = event.state,
            error = event.error,
            created_at = event.createdAt,
            updated_at = event.updatedAt,
        )
        return eventExists(event.sourceId, event.eventId)
    }

    override fun eventExists(sourceId: String, eventId: String): Boolean =
        queries.selectTriggerEventExists(source_id = sourceId, event_id = eventId)
            .executeAsOneOrNull() != null

    override fun pendingEvents(limit: Int): List<TriggerEventRecord> =
        queries.selectPendingTriggerEvents(limit = limit.toLong()).executeAsList().map { it.toRecord() }

    /**
     * 条件 UPDATE + `changes()` 读回，两步在**同一个事务**里。
     *
     * 为什么必须同事务：`changes()` 是连接级状态（「本连接最近一条写语句影响了几行」）。
     * `SerializedAccessDriver.newTransaction()` 在事务期间持锁、同线程复用同一条连接，
     * 所以事务内紧跟 UPDATE 的 `changes()` 一定读到的是那条 UPDATE；跨事务读则是
     * 另一条连接上的陈旧值，判据会变成随机数。
     */
    override fun claimEvent(sourceId: String, eventId: String): Boolean = queries.transactionWithResult {
        queries.claimTriggerEvent(
            updated_at = nowMillis(),
            source_id = sourceId,
            event_id = eventId,
        )
        queries.selectChanges().executeAsOne() > 0L
    }

    override fun reclaimRunningEvents(): Int = queries.transactionWithResult {
        queries.reclaimRunningTriggerEvents(updated_at = nowMillis())
        queries.selectChanges().executeAsOne().toInt()
    }

    override fun updateEventState(
        sourceId: String,
        eventId: String,
        state: String,
        error: String?,
    ): Boolean {
        require(TriggerEventStates.isKnown(state)) { "unknown trigger event state: $state" }
        return queries.transactionWithResult {
            queries.updateTriggerEventState(
                state = state,
                error = error,
                updated_at = nowMillis(),
                source_id = sourceId,
                event_id = eventId,
            )
            queries.selectChanges().executeAsOne() > 0L
        }
    }

    override fun pruneTerminalEventsBefore(epochMs: Long) {
        queries.deleteTriggerEventsTerminalBefore(epochMs)
    }
}

private fun SchedulerTaskState.toRunState(): TaskRunState = TaskRunState(
    taskId = task_id,
    lastRunAt = last_run_at,
    nextRunAt = next_run_at,
    jitterOffsetSec = jitter_offset_sec.toInt(),
    missedRuns = missed_runs.toInt(),
    updatedAt = updated_at,
)

private fun TriggerEvent.toRecord(): TriggerEventRecord = TriggerEventRecord(
    eventId = event_id,
    sourceId = source_id,
    type = type,
    subject = subject,
    summary = summary,
    detail = detail,
    link = link,
    state = state,
    error = error,
    createdAt = created_at,
    updatedAt = updated_at,
)
