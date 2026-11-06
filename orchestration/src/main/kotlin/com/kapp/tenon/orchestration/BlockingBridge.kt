// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 非挂起回调里的「一次性桥接」：**全仓编排面唯一的 `runBlocking`**（ARP-2）。
 *
 * ## 为什么还需要它（而不是都改成挂起）
 *
 * 这些调用点都在**协议回调**里：`controller.channelOrchestrator`、卡片动作分发、
 * 合并结果落账。它们的签名由领域层定死（`(payload, fromNode) -> Boolean` 这类），
 * 改不成 `suspend`；而它们要做的下一跳（发一帧、取一句文案）偏偏是挂起的。
 *
 * ## 它买到的三件事
 *
 * 1. **有上界**。此前一个不可达的对端能让回调线程按住整整 8 秒（消息退避 + TCP 兜底），
 *    而这条线程还在处理下一条帧。现在超时即放弃并如实记账。
 * 2. **有读数**。每次桥接都记耗时；超过 [SLOW_BRIDGE_MS] 打一行 `[RELAY-BRIDGE]` ——
 *    「宿主卡了一下」从体感变成可复述的事实（本仓最贵的一类缺陷是静默）。
 * 3. **失败不改变调用方的控制流**。给一个显式 [fallback]，由调用点自己决定
 *    「取不到这句话」时该继续还是该报错，而不是让异常穿到帧循环里。
 *
 * ## 顺序纪律（别把它改成异步派发）
 *
 * 卡片相关的桥接（判定帧 / 翻面帧 / 合并结果）与 `cardBroadcastMutex` 的 rev 顺序是
 * 绑在一起的：改成「投后台、立即返回」会让判定帧与终态帧同时读到同一个 rev 快照 ——
 * 真机上出现过的形态是「两张卡同 cardId 同 rev，手机只认严格递增，诚实的那次翻面被
 * 当旧帧丢掉」（来历见 `cardBroadcastMutex` 的 KDoc 与 `BoardCardLocalPersistenceTest`）。
 * 要异步化，必须和广播走同一个互斥域，不能在这里偷偷换。
 *
 * @param what 读数里用的短标签（`[RELAY-BRIDGE] <what>`），不是文案
 * @param fallback 超时或抛错时的返回值；调用点必须自己想清楚这个值意味着什么
 */
internal fun <T> bridgeBlocking(
    what: String,
    fallback: T,
    timeoutMs: Long = BRIDGE_TIMEOUT_MS,
    block: suspend () -> T,
): T {
    val started = System.nanoTime()
    return try {
        val boxed = runBlocking { withTimeoutOrNull(timeoutMs) { Box(block()) } }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        when {
            boxed == null -> {
                RelayLog.info("RELAY-BRIDGE", "$what timed out after ${timeoutMs}ms (gave up, using fallback)")
                fallback
            }
            elapsedMs >= SLOW_BRIDGE_MS -> {
                RelayLog.info("RELAY-BRIDGE", "$what blocked the caller for ${elapsedMs}ms")
                boxed.value
            }
            else -> boxed.value
        }
    } catch (t: Throwable) {
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        RelayLog.info("RELAY-BRIDGE",
            "$what failed in ${elapsedMs}ms: " +
                (t.message ?: (t::class.simpleName ?: "unknown")),
        )
        fallback
    }
}

/** [bridgeBlocking] 的 Unit 版本：调用点只关心「发出去了没有」，不关心返回值。 */
internal fun bridgeBlockingUnit(
    what: String,
    timeoutMs: Long = BRIDGE_TIMEOUT_MS,
    block: suspend () -> Unit,
) {
    bridgeBlocking(what, fallback = Unit, timeoutMs = timeoutMs) { block() }
}

/** 用来区分「超时（null）」与「block 返回了 null」—— 两者含义完全不同。 */
private class Box<T>(val value: T)

/**
 * 桥接的默认上界。取值理由：最慢的合法路径是「发一帧 + 对端不可达时等退避」，
 * 应用层的发送预算本身是 8 秒（`GROUP_FANOUT_BUDGET_MS` 那族），超过它就没有
 * 「等回来还有用」的可能了。
 */
internal const val BRIDGE_TIMEOUT_MS: Long = 8_000

/** 超过这个当量的桥接会留一行读数（正常路径是毫秒级）。 */
internal const val SLOW_BRIDGE_MS: Long = 250
