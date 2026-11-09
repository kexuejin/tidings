// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.SchedulerStore
import com.kapp.tenon.domain.TriggerEventRecord
import com.kapp.tenon.domain.TriggerEventStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * 触发事件的**持久化**消费链路（触发式编排提案 §9 阶段二）。
 *
 * 分工与既有结构的关系：
 * - `SourceWebhookRegistry`（:agentNode）的内存队列只负责「验签后 ACK 与执行解耦」
 *   ——它不可持久；本类才是提案说的「阶段二换成持久化 Job Table」：
 *   事件落 [SchedulerStore]（event_id 主键 = 持久幂等键），消费循环从**表**拉
 *   pending 行翻终态，重启后未消费的事件照常被捞起（这正是内存 fan-out 做不到的）；
 * - `listeners` 同步 fan-out 从此只有一个生产消费者：编排侧的持久化桥
 *   （[DesktopExtension] 注册的 listener 调 [ingest]）。编排流消费**不**走 listeners。
 *
 * 消费纪律：
 * - 单消费者循环（CONFLATED kick 合并并发踢动），事件状态机 pending → running →
 *   done/failed 由**表**承载——两条同 id 事件永远只有一行，两个消费循环也不会双派；
 * - 派发并发有界（[dispatchParallelism]，同 §2.3 工作池口径）；
 * - handler 缺席或抛错都如实翻 FAILED 带原因——失败可见，绝不静默吞事件。
 */
class TriggerEventDrain(
    private val store: SchedulerStore,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 派发并发上限。 */
    private val dispatchParallelism: Int = 2,
    /** 单轮拉取的批大小。 */
    private val batchSize: Int = 8,
) {

    companion object {
        /** 终态行存活时长（提案 §6.1：台账不是归档库，prune 让位）。 */
        const val TERMINAL_TTL_MS: Long = 24 * 60 * 60_000L
    }

    /**
     * 事件执行出口：三态 verdict 见 [TriggerVerdict]。C8 落地为只读触发通道；
     * 测试用 fake。
     */
    var handler: (suspend (TriggerEventRecord) -> TriggerVerdict)? = null

    private val permits = Semaphore(dispatchParallelism)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var drainJob: Job? = null

    /** 启动消费循环，并先把上一轮遗留的 pending 行捞起来（重启恢复）。 */
    fun start() {
        check(drainJob == null) { "TriggerEventDrain already started" }
        drainJob = scope.launch {
            for (unit in kick) drainOnce()
        }
        kick.trySend(Unit)
    }

    fun stop() {
        drainJob?.cancel()
        drainJob = null
    }

    /**
     * 持久化入队（幂等键见 store）。返回 false = 同 id 已在队列（重放被主键吸收）。
     * 运行在 webhook 派发协程（IO）上，SQLite 插入毫秒级，同步执行。
     */
    fun ingest(record: TriggerEventRecord): Boolean {
        val enqueued = store.enqueueEvent(record)
        if (enqueued) kick.trySend(Unit)
        return enqueued
    }

    private suspend fun drainOnce() {
        while (true) {
            val batch = store.pendingEvents(batchSize)
            if (batch.isEmpty()) break
            for (event in batch) {
                // 先翻 running 再派发：派发期间再来一轮 drain 不会重复拉到同一行。
                store.updateEventState(event.eventId, TriggerEventStates.RUNNING, null)
                permits.acquire()
                scope.launch {
                    // 区分「没有 handler」与「handler 正常返回」：null 判定合并两者
                    // 会让每一次成功消费都被翻成失败。
                    val h = handler
                    val verdict: TriggerVerdict = if (h == null) {
                        TriggerVerdict.Fail("no trigger handler registered (executor not wired)")
                    } else {
                        try {
                            h.invoke(event)
                        } catch (t: Throwable) {
                            TriggerVerdict.Fail("trigger handler failed: " + (t.message ?: t.javaClass.name))
                        }
                    }
                    val (state, error) = when (verdict) {
                        is TriggerVerdict.Done -> TriggerEventStates.DONE to null
                        is TriggerVerdict.Skip -> TriggerEventStates.SKIPPED to verdict.reason
                        is TriggerVerdict.Fail -> TriggerEventStates.FAILED to verdict.reason
                    }
                    store.updateEventState(event.eventId, state, error)
                    permits.release()
                }
            }
        }
        store.pruneTerminalEventsBefore(nowMillis() - TERMINAL_TTL_MS)
    }
}

/**
 * 事件处理结果三态。Skip 服务 §7.2 的 filter 前置过滤：未命中规则的事件如实
 * 记账为 skipped（0 Token、不唤醒模型），不是丢失。
 */
sealed interface TriggerVerdict {
    data object Done : TriggerVerdict

    /** 被 filter 规则链前置过滤；[reason] 进 error 列供审计。 */
    data class Skip(val reason: String) : TriggerVerdict

    data class Fail(val reason: String) : TriggerVerdict
}
