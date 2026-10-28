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
 * @param timeoutMs 上限；到点还没满足就判红。默认见 [AWAIT_TIMEOUT_MS]。
 *   **这是"观测预算"，不是断言**：断言的是"这件事最终会发生"，预算只决定等多久。
 *   定得太紧时，红的不是产品行为而是调度延迟 —— 真机上把 10s 卡在两节点 UDP 握手 +
 *   群元数据传播上，在跑满 700+ 用例的 JVM 里会偶发超时（实测 3 轮里红 1 轮，
 *   单跑该类 5/5 全绿）。
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

/**
 * 默认上限。
 *
 * 为什么是 30 秒而不是 5/10 秒：这些用例跑的是**真 UDP 回环**（两个 RelayController
 * 互拨、Agent 节点握手、群元数据扇出）。在本机单跑时全都在 1 秒内完成，但当整个
 * `:app:jvmTest`（700+ 用例）串行跑、或与别的构建共用 CPU 时，握手会明显变慢。
 * 观测预算给到 30 秒，仍然远小于"真出问题"的量级（真卡死会稳定红），
 * 而调度抖动不再被误判成产品缺陷。
 *
 * **不要**用它来掩盖"条件永远不会成立"：那种用例单跑也红，与预算无关。
 */
const val AWAIT_TIMEOUT_MS: Long = 30_000
