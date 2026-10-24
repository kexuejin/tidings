// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.testutil

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 测试里**唯一**的轮询助手（:app 模块内共享，不进生产源集）。
 *
 * 为什么只有一份：AGENTS §4 要求「并发/时序类测试用轮询 + withTimeout，不要用固定 delay
 * 赌时间」。此前每个测试文件各抄一份私有实现（口径还不完全一样），于是"改超时策略要改十处"，
 * 而且新写测试的人会再抄第 N+1 份。收敛到这一份之后，超时、轮询间隔、失败信息只有一处来源。
 *
 * 失败时的报错要**说清在等什么**：只抛 TimeoutCancellationException 时，红的那一行是
 * withTimeout 的 lambda，读的人不知道是哪个条件没等到。
 *
 * @param what 期望发生的事（写进失败消息，例如"对端进节点表"）。
 * @param timeoutMs 上限；到点还没满足就判红。默认 5 秒 —— 真机握手往返远小于它，
 *   慢 CI 也不会因为差几百毫秒就偶发红。
 * @param condition 幂等、无副作用的条件（会被反复调）。
 */
suspend fun awaitUntil(
    what: String,
    timeoutMs: Long = AWAIT_TIMEOUT_MS,
    condition: () -> Boolean,
) {
    val done = withTimeoutOrNull(timeoutMs) {
        while (!condition()) delay(AWAIT_POLL_MS)
        true
    }
    if (done == null) error("等了 ${timeoutMs}ms 仍未发生：$what")
}

/** 轮询间隔：足够细（不拖慢成功路径），又不会把一个 5 秒等待变成十万次读状态。 */
private const val AWAIT_POLL_MS: Long = 10

/** 默认上限：见 [awaitUntil] 的参数说明。 */
const val AWAIT_TIMEOUT_MS: Long = 10_000
