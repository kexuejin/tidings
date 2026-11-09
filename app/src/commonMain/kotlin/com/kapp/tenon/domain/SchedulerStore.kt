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
 *   消费完翻终态。`event_id` 主键即持久幂等键，内存幂等表（SourceWebhookRegistry）
 *   重启丢失后由它兜底。
 *
 * 落点纪律：本接口住在 `:app`（`:agentNode` 物理上不依赖 `:app`，不直接持库），
 * 消费方是 `:orchestration`（已依赖 `:app`）。`:orchestration` 不 import SQLDelight——
 * 依赖方向有守卫测试压着。
 */
interface SchedulerStore {

    fun taskRunStates(): List<TaskRunState>

    fun saveTaskRunState(state: TaskRunState)

    /**
     * 触发事件入队。返回 false = 幂等命中（同 event_id 已有行——重放投递被
     * 持久键吸收，不再入队）。并发同 id 入队时 PK 兜底只会留一行。
     */
    fun enqueueEvent(event: TriggerEventRecord): Boolean

    /** 按 created_at 顺序取待消费事件；消费方取走后应自行翻 running。 */
    fun pendingEvents(limit: Int): List<TriggerEventRecord>

    /** 翻状态；[error] 仅 failed/skipped 有意义，其余传 null。 */
    fun updateEventState(eventId: String, state: String, error: String?)

    /** 终态行清理：台账不是归档库，消费完的事件按 TTL 让位。 */
    fun pruneTerminalEventsBefore(epochMs: Long)
}

/** 调度任务运行态。 */
data class TaskRunState(
    /** pluginId + "/" + trigger.id，同清单内唯一。 */
    val taskId: String,
    /** epoch ms；null = 从未跑过。 */
    val lastRunAt: Long?,
    /** epoch ms；调度扫描判据。 */
    val nextRunAt: Long,
    /** 逐任务固定 Jitter 偏移（秒，可为负）；同任务重启不漂移（提案 §2.3）。 */
    val jitterOffsetSec: Int,
    /** 错过而未补跑的窗口数：RUN_ONCE 只补 1 次，余数如实记账。 */
    val missedRuns: Int,
    val updatedAt: Long,
)

/** 触发事件（StandardSourceEvent 同形字段 + 队列态）。取值域见 [TriggerEventStates]。 */
data class TriggerEventRecord(
    /** 幂等键：发送方投递 id / 载荷 id / body 哈希（SourceWebhookRegistry 同源）。 */
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
    /** 时钟注入：prune/翻态时间戳的测试钉子。 */
    private val nowMillis: () -> Long = System::currentTimeMillis,
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
        // 先查后插 + PK 兜底：并发同 id 入队时 INSERT OR IGNORE 只留一行，
        // 两个调用方最多各 kick 一次 drain（drain 幂等），语义无碍。
        val existedBefore = queries.selectTriggerEventExists(event.eventId).executeAsOneOrNull()
        if (existedBefore != null) return false
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
        return queries.selectTriggerEventExists(event.eventId).executeAsOneOrNull() != null
    }

    override fun pendingEvents(limit: Int): List<TriggerEventRecord> =
        queries.selectPendingTriggerEvents(limit = limit.toLong()).executeAsList().map { it.toRecord() }

    override fun updateEventState(eventId: String, state: String, error: String?) {
        require(TriggerEventStates.isKnown(state)) { "unknown trigger event state: $state" }
        queries.updateTriggerEventState(state, error, nowMillis(), eventId)
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
