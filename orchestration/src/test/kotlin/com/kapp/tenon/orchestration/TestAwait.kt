// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * orchestration 测试的**唯一**一份阻塞轮询助手（本模块不许再出现第二份
 * deadline 循环）。
 *
 * 与 agentNode 的 `Await` 同一套设计（那是它自己模块内的唯一份，两份不共享
 * 代码——跨模块复用会让一份助手同时承担两套时序假设，见其 KDoc）：
 * - 阻塞式 JUnit + 轮询到**可观测信号**为止并带超时；不用固定 `delay` 赌同步
 *   （本仓的 envDependentTestGate 把测试体内的 `delay` 判为 B 类）；
 * - 截止时刻用 `System.nanoTime`（墙钟回拨不影响）；
 * - 超时抛 AssertionError 并写清「在等什么」。
 */
object TestAwait {

    private const val POLL_MS: Long = 25L

    fun nowMillis(): Long = System.nanoTime() / 1_000_000

    /** 轮询到 [probe] 为真为止；超时抛 [AssertionError]。 */
    fun condition(what: String, timeoutMs: Long = 10_000L, probe: () -> Boolean) {
        val deadline = nowMillis() + timeoutMs
        while (nowMillis() < deadline) {
            if (probe()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(timeoutMs.toString() + "ms 内没有等到：" + what)
    }

    /** 轮询到 [probe] 返回非 null 为止并返回该值。 */
    fun <T : Any> value(what: String, timeoutMs: Long = 10_000L, probe: () -> T?): T {
        var captured: T? = null
        condition(what, timeoutMs) { (probe()?.also { captured = it }) != null }
        return captured!!
    }

    /**
     * 有界观测窗：在 [windowMs] 内持续确认 [forbidden] **一直不成立**，一旦成立立即失败。
     *
     * 与 [condition] 的区别：condition 等「信号出现」，这里等「信号始终不出现」——
     * 「证明没有第二条」的固有形态（同 core 的 OutgoingReplayTest 口径）。它不是
     * 固定 delay 盲等：窗口内每 [POLL_MS] 都判一次，晚到的重复派发只要落在窗口里
     * 就会被抓到；窗口长度是**上界**而非同步假设，不参与任何「等就绪」的判据。
     */
    fun quiet(what: String, windowMs: Long = 200L, forbidden: () -> Boolean) {
        val deadline = nowMillis() + windowMs
        while (nowMillis() < deadline) {
            if (forbidden()) throw AssertionError("观测窗内出现了不该发生的事：" + what)
            Thread.sleep(POLL_MS)
        }
        if (forbidden()) throw AssertionError("观测窗内出现了不该发生的事：" + what)
    }
}
