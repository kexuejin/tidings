// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.SchedulerStore
import com.kapp.tenon.domain.TriggerEventRecord
import com.kapp.tenon.domain.TriggerEventStates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * - **认领是原子的**（[SchedulerStore.claimEvent]：`WHERE state = 'pending'` 的条件
 *   UPDATE + `changes()` 读回）。「读到 pending」与「翻成 running」是两步，中间别的
 *   消费者可能先到；抢不到就必须放弃派发，而不是照样派；
 * - **先拿许可再认领**：反过来的话，行会在「等并发额度」期间就处于 running，
 *   这段时间进程被 kill 就成了一条既拉不到、又永不 prune 的死行；
 * - **启动时做崩溃恢复**（[SchedulerStore.reclaimRunningEvents]）：上一进程被 kill
 *   时来不及翻终态的 running 行放回 pending 重放（at-least-once，见该方法 KDoc）；
 * - 派发并发有界（[dispatchParallelism]，同 §2.3 工作池口径）；
 * - handler 缺席或抛错都如实翻 FAILED 带原因——失败可见，绝不静默吞事件。
 *   但**协程取消不算失败**：取消时把行留在 running，交给下次启动的崩溃恢复重放，
 *   否则每次正常关停都会把在飞事件误记成 failed。
 * - 单轮失败不杀循环（[drainOnce] 外面那层 catch）：库偶发 BUSY / 盘满 / 库被关
 *   都只让这一轮白跑，循环还在，下一次踢动继续。
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

        /** 日志 tag：沿用编排层既有字面量风格（见 [RelayLog] 的 KDoc）。 */
        private const val TAG = "RELAY-TRIGGER"

        /** 单轮失败的退避：既不能静默停放，也不能在库持续不可用时空转烧 CPU。 */
        const val CYCLE_RETRY_MS: Long = 250L
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
        // 崩溃恢复必须在启动循环**之前**、且只在这里做一次。
        // 上一进程被 kill 时来不及翻终态的行会停在 running：它们既不在 pending 里
        // （拉不到），也不在终态里（永不 prune）—— 不捞就是永久丢失，且没有任何
        // 日志会提到它们。反过来，把这一步放进扫描循环会把真正在跑的行重新派发。
        try {
            val reclaimed = store.reclaimRunningEvents()
            if (reclaimed > 0) {
                RelayLog.info(TAG, "crash recovery: " + reclaimed + " orphaned running event(s) returned to pending")
            }
        } catch (t: Throwable) {
            // 恢复失败不该拦住消费：新事件照常处理，遗留行留在 running（下次启动再试）。
            RelayLog.info(TAG, "crash recovery failed; orphaned running rows stay stuck: " + t.toString())
        }
        drainJob = scope.launch {
            for (unit in kick) {
                try {
                    drainOnce()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // 单轮失败不杀循环。旧实现没有这层，一次 SQLITE_BUSY 就让
                    // `for (unit in kick)` 整个退出，此后事件只进不出且静默。
                    //
                    // **失败那一轮也必须自己再踢一次**：`drainOnce` 抛在批次中途时，
                    // 剩下的 pending 行还躺在表里，而循环此时回到 `for (unit in kick)`
                    // 等下一个事件——没有下一个事件就永远等下去。症状是「第一次库抖
                    // 一下之后，队列再也不动了」。退避把持续故障的重试频率钉在常数级。
                    RelayLog.info(TAG, "drain cycle failed; retrying: " + t.toString())
                    delay(CYCLE_RETRY_MS)
                    kick.trySend(Unit)
                }
            }
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
            var claimed = 0
            for (event in batch) {
                if (dispatch(event)) claimed++
            }
            // 整批一条都没抢到：这些行已经被别的消费者认领（state 不再是 pending），
            // 再拉还是同一批。不退出就是原地死循环 —— 旧实现没有这个判据。
            if (claimed == 0) break
        }
        store.pruneTerminalEventsBefore(nowMillis() - TERMINAL_TTL_MS)
    }

    /**
     * 认领并派发一条事件。返回 false = 没抢到（已被别处认领 / 已到终态）。
     *
     * 三步的顺序都有理由：
     * 1. **先拿许可**：先翻 running 再 `acquire()` 会让行在等额度期间就处于 running，
     *    这段时间进程被 kill，它就成了一条死行（拉不到、不 prune、恢复逻辑又把它当
     *    遗留重放 —— 等于多跑一次）。
     * 2. **认领失败即放弃**：`claimEvent` 返回 false 说明这次「读到的 pending」已经过期。
     *    旧接口返回 `Unit`，这个失败在类型上不可观测，于是照样派发 —— 双派。
     * 3. **许可挂在 job 的完成回调上**，而不是协程体里的 `finally`：作用域已取消时
     *    协程体根本不会执行，`finally` 也就不会跑，许可被永久吃掉，并发额度越用越少。
     */
    private suspend fun dispatch(event: TriggerEventRecord): Boolean {
        permits.acquire()
        // **拿许可之后、把许可交给 job 完成回调之前，任何退出路径都要归还**。
        // 旧实现只在 claimEvent 返回 false 时归还：库偶发 BUSY / 盘满 / 库被关时
        // claimEvent 会抛，许可被永久吃掉。并发额度是 2，连抛两次之后
        // permits.acquire() 就再也过不去 —— 循环还活着、表里还有 pending，
        // 但一条都派不出去，日志里只有那两行异常。
        val claimed = try {
            store.claimEvent(event.sourceId, event.eventId)
        } catch (t: Throwable) {
            permits.release()
            throw t
        }
        if (!claimed) {
            permits.release()
            return false
        }
        val job = scope.launch {
            try {
                val (state, error) = when (val verdict = runHandler(event)) {
                    is TriggerVerdict.Done -> TriggerEventStates.DONE to null
                    is TriggerVerdict.Skip -> TriggerEventStates.SKIPPED to verdict.reason
                    is TriggerVerdict.Fail -> TriggerEventStates.FAILED to verdict.reason
                }
                if (!store.updateEventState(event.sourceId, event.eventId, state, error)) {
                    RelayLog.info(TAG, "event " + event.eventId + " -> " + state + " but the row is gone (pruned?)")
                }
            } catch (c: CancellationException) {
                // 取消不是失败：行留在 running，下次启动的崩溃恢复会重放它。
                // 旧实现把取消也翻成 FAILED —— 每次正常关停都误记一批失败。
                throw c
            } catch (t: Throwable) {
                // 落终态本身失败（库坏 / 被关）。行会卡在 running，交给崩溃恢复。
                RelayLog.info(TAG, "event " + event.eventId + " failed to reach a terminal state: " + t.toString())
            }
        }
        job.invokeOnCompletion { permits.release() }
        return true
    }

    /**
     * 调 handler 并把任何抛出收敛成 [TriggerVerdict.Fail]。
     *
     * `handler == null` 单独判：把「没装 handler」和「handler 正常返回」合并成 null
     * 判定，会让每一次成功消费都被翻成失败。
     */
    private suspend fun runHandler(event: TriggerEventRecord): TriggerVerdict {
        val h = handler
            ?: return TriggerVerdict.Fail("no trigger handler registered (executor not wired)")
        return try {
            h.invoke(event)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            TriggerVerdict.Fail("trigger handler failed: " + (t.message ?: t.javaClass.name))
        }
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
