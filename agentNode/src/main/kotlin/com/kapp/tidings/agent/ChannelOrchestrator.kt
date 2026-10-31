// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.agent.plugins.PluginRegistry
import com.kapp.tidings.core.protocol.AgentChannelPayload
import com.kapp.tidings.core.protocol.ChannelOps
import com.kapp.tidings.core.protocol.CodecInvoke.InvokeResultStates
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * M7-T2：Channel 委派的**接收宿主侧编排器**（决策包 §1.5，D-016 后半步的消费者）。
 *
 * ## 声明式互斥的执行点
 *
 * 「声明同一资源的任务一个接一个跑」的裁决落在这里：每个资源键一把锁，任务先拿齐
 * 自己声明的**全部**资源锁、跑完统一放掉 —— 多资源要么全拿要么全等（拿到一半放回去
 * 会造出两把半锁之间的循环等待），锁序固定为声明序、逆序释放，避免合成死锁。
 * 资源取值域由 `ChannelResources.isWellFormed` 单一定义，这里不做第二份判。
 *
 * **为什么不用公平锁（对半成品的纠正）**：半成品写的是 `Mutex(true)` —— kotlinx 的
 * 工厂签名是 `Mutex(locked: Boolean)`，这个参数是「**以已锁定状态创建**」而不是
 * fair。原样跑起来每把锁出生即上锁，第一个任务就永远拿不到锁（上一轮基线的
 * ChannelOrchestratorTest 实测红：`log=[]`，c-1 从未开跑）。kotlinx Mutex 的等待
 * 队列本身就是先到先得（挂起顺序即排队顺序），公平性由它保证，不需要也不存在一个
 * fair 参数 —— 真正必须守的是「多资源原子获取」，锁序纪律因此写死在上面。
 *
 * ## 租约（assign 的 deadlineMs）
 *
 * `withTimeoutOrNull(lease)` 包住**排队 + 解析 + 执行**全程：assign 的语义是
 * 「调用方挂起等结果」，调用方等够 `deadlineMs` 就走了，接收侧再跑完也没有人收
 * 结果。超时回 `status=timeout`（复用 [InvokeResultStates]，不另立词汇）并如实
 * 出队，绝不静默吞 —— 这是决策包 §1.5 第 3 条。`transfer` 没有租约（决策包 §1.4：
 * 所有权已整体交出，禁 `deadlineMs`），不设超时。
 *
 * ## 幂等
 *
 * 同一 `channelId` 的重复投递（RELIABLE 重传 / 队列重放）就地忽略 —— 「同一委派跑
 * 两遍」比跑零遍更糟（写入两份产物）。信封 id 去重在传输层，这里按语义键再兜一层
 * （判据仿 M4 审批防重）。台账是内存态，与 `AgentSessionRegistry` 同一条
 * 「重启即清空、如实降级」的取舍。
 *
 * ## 结果回执
 *
 * assign 与 transfer 都回 `agent-channel-result`：transfer 的发起方虽已退出，
 * 一帧如实回执让发起方的台账有终态可记。status 复用 [InvokeResultStates]；
 * tag 路由（T4）落地前 `targetTag` 寻址一律答 no-plugin —— 如实说「这台宿主
 * 没有能按标签找到的承接者」。
 */
class ChannelOrchestrator(
    /** 把 `toAgent` / `targetTag` 解析成本机承接插件；null = 本机没有承接者。 */
    private val resolve: (payload: AgentChannelPayload) -> PluginRegistry.Entry?,
    /** 真正执行（ControlPlane 的 `executePluginTask` 同源实现——两处各写一份迟早漂）。 */
    private val execute: suspend (entry: PluginRegistry.Entry, payload: AgentChannelPayload) -> Outcome,
    /** 结果回执：回给发来 `agent-channel` 的那个对端。 */
    private val sendResult: suspend (
        toNode: String,
        channelId: String,
        ok: Boolean,
        status: String,
        summary: String?,
        artifactIds: List<String>?,
        error: String?,
    ) -> Unit,
    private val scope: CoroutineScope,
    /** 时钟注入：租约计时不能依赖墙钟被 NTP 拨动（Await.kt 同一条纪律）。 */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 一次执行的结论。`ok` = 任务真的做完了（驱动诚信：不为没发生的活报 done）。 */
    data class Outcome(
        val ok: Boolean,
        val summary: String? = null,
        val artifactIds: List<String>? = null,
        val error: String? = null,
    )

    /** 一次受理的中间结论：没有承接者与真的执行过是两种 status，必须分开答。 */
    private sealed interface Attempt {
        data class NoExecutor(val detail: String) : Attempt
        data class Finished(val outcome: Outcome) : Attempt
    }

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** channelId → 受理时刻。putIfAbsent 的原子性就是幂等判据本身。 */
    private val handled = ConcurrentHashMap<String, Long>()

    /**
     * 收一条委派。幂等去重**同步**完成（重复投递在进入协程前就被拒），
     * 执行与回执异步：入站订阅线程不能被一个长任务按住。
     */
    fun offer(payload: AgentChannelPayload, fromNode: String) {
        if (handled.putIfAbsent(payload.channelId, nowMillis()) != null) return
        scope.launch {
            try {
                executeAndReport(payload, fromNode)
            } catch (e: CancellationException) {
                throw e // scope 关停不是执行失败，不回执（此时也对端回不动了）。
            } catch (e: Exception) {
                // 执行期的任何异常都如实回执（诚信），绝不静默死在无 handler 的 Job 里。
                // 回执本身再失败只记日志 —— 放锁在 executeAndReport 的 finally，不受影响。
                runCatching {
                    sendResult(
                        fromNode, payload.channelId, false, InvokeResultStates.ERROR,
                        null, null, e.message ?: e::class.simpleName,
                    )
                }.onFailure { t -> println("[channel] result dispatch failed: " + t.message) }
            }
        }
    }

    private suspend fun executeAndReport(payload: AgentChannelPayload, fromNode: String) {
        // 声明序拿锁、逆序放锁（多资源原子获取，防合成死锁）；distinct 让
        // 「同一帧里重复声明同一资源」退化为一把锁（集合语义，收端容忍重复）。
        val acquired = payload.resources.orEmpty().distinct()
            .map { locks.computeIfAbsent(it) { Mutex() } }
        try {
            val lease = payload.deadlineMs?.takeIf { payload.op == ChannelOps.ASSIGN }
            if (lease == null) {
                acquired.forEach { it.lock() }
                report(attempt(payload), payload, fromNode, null)
            } else {
                val started = nowMillis()
                // 租约包住**排队（锁挂起）+ 解析 + 执行**全程 —— 决策包 §1.5 第 3 条
                // 「排队中的 assign 超过自身 deadlineMs → 回 timeout 并出队」。计时器
                // 必须在拿锁**之前**启动：放在拿锁之后，排队时间就不在租约里，一个被
                // 长任务堵住的资源会让调用方无限白等（本仓第一版实现就红在这，测试抓的）。
                // 取消点若在 lock() 挂起处，锁从未持有；若在执行挂起处，下方 finally 照跑。
                val ran = withTimeoutOrNull(lease) {
                    acquired.forEach { it.lock() }
                    attempt(payload)
                }
                if (ran == null) {
                    sendResult(
                        fromNode, payload.channelId, false, InvokeResultStates.TIMEOUT,
                        null, null,
                        "deadlineMs=" + lease + " exceeded after " + (nowMillis() - started) + "ms",
                    )
                    return
                }
                report(ran, payload, fromNode, "ran " + (nowMillis() - started) + "ms of lease " + lease + "ms")
            }
        } finally {
            // 唯一放锁点：正常完成、租约超时、异常取消三条路径都经过这里。
            // 未持有时的 unlock 会抛 IllegalStateException（超时发生在排队挂起处），
            // runCatching 吞掉 —— 它绝不会放掉别人的锁。
            acquired.reversed().forEach { runCatching { it.unlock() } }
        }
    }

    private suspend fun report(attempt: Attempt, payload: AgentChannelPayload, fromNode: String, leaseNote: String?) {
        when (attempt) {
            is Attempt.NoExecutor -> sendResult(
                fromNode, payload.channelId, false, InvokeResultStates.NO_PLUGIN,
                null, null, attempt.detail,
            )
            is Attempt.Finished -> sendResult(
                fromNode,
                payload.channelId,
                attempt.outcome.ok,
                if (attempt.outcome.ok) InvokeResultStates.DONE else InvokeResultStates.ERROR,
                attempt.outcome.summary,
                attempt.outcome.artifactIds,
                listOfNotNull(attempt.outcome.error, leaseNote.takeIf { !attempt.outcome.ok })
                    .joinToString(" | ").ifEmpty { null },
            )
        }
    }

    private suspend fun attempt(payload: AgentChannelPayload): Attempt {
        val entry = resolve(payload)
        if (entry == null) {
            // resolve 与 execute 分开答两种状态：no-plugin = 本机没有承接者（配置问题），
            // error = 有承接者但没做成（执行问题）—— 都比沉默强。
            return Attempt.NoExecutor("no local executor for " + (payload.toAgent ?: payload.targetTag ?: "unnamed target"))
        }
        return Attempt.Finished(execute(entry, payload))
    }
}
