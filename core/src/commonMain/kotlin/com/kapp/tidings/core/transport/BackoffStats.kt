// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

/**
 * 「发送被退避了几次」的量具（10s 一行）。
 *
 * 为什么要单独一个：`SecureSession.send` 是 Rust 侧 mpsc 的 `try_send`（队列 64 槽），
 * 队列满就返回 false，调用方 `delay(2)` 重试 —— **每次退避至少 2ms**。一帧 49KiB 被
 * 切成 ~7 段、每段近 64KiB，所以约 9 帧就能填满队列。
 *
 * 离线探针（`EncryptionCostProbeTest`）里不等接收端消化，所以只测到 143µs；
 * 真机投屏是持续灌入，退避会频繁触发 —— 这才是"单段 3.5ms"的主要来源。
 * 这个计数器把它从"猜"变成"读"。
 */
class BackoffStats(private val tag: String) {
    private var sends = 0
    private var totalBackoffs = 0L
    private var maxBackoffs = 0
    private var windowStart = com.kapp.tidings.core.nowMillis()

    fun onCount(backoffs: Int) {
        sends += 1
        totalBackoffs += backoffs
        if (backoffs > maxBackoffs) maxBackoffs = backoffs
        val now = com.kapp.tidings.core.nowMillis()
        val elapsed = now - windowStart
        if (elapsed < WINDOW_MS || sends == 0) return
        println(
            "[screen] " + tag + " sends=" + sends +
                " backoffs=" + totalBackoffs +
                " avgPerSend=" + (totalBackoffs / sends) +
                " maxPerSend=" + maxBackoffs,
        )
        sends = 0
        totalBackoffs = 0
        maxBackoffs = 0
        windowStart = now
    }

    private companion object {
        const val WINDOW_MS = 10_000L
    }
}
