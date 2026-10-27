// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

/**
 * 把一段工作的耗时按 10s 窗口打一行的量具（微秒级，整数算）。
 *
 * 为什么不用现成的 [ScreenStats]：那个统计的是"每一帧的端到端"，而这里要的是
 * **一段代码自己的耗时**（例如"加密 + JNA + TCP 写"），混进别的就失去意义。
 *
 * 两种用法：
 *  - [onSegment]：一次投递的 **拷贝 + 发送** 两段（txseg）；
 *  - [onSplit]：一次等待分成 **前段 / 后段**（rxseg：等帧头 vs 等 JPEG）。
 *    拆开才能判断那 20ms 究竟是"等对端采样/节流"还是"传字节"。
 */
class SegmentStats(private val tag: String) {
    private var count = 0
    private var totalUs = 0L
    private var maxUs = 0L
    private var bytes = 0L
    private var firstUs = 0L
    private var secondUs = 0L
    private var split = false
    private var windowStart = com.kapp.tidings.core.nowMillis()

    fun onSegment(byteCount: Long, us: Long, sendUs: Long) {
        split = false
        record(byteCount, us + sendUs)
    }

    /** 一次等待拆成两段（毫秒进，微秒出）。 */
    fun onSplit(firstMs: Long, secondMs: Long) {
        split = true
        firstUs += firstMs * 1_000
        secondUs += secondMs * 1_000
        count += 1
        val us = firstMs * 1_000 + secondMs * 1_000
        if (us > maxUs) maxUs = us
        flushIfDue()
    }

    private fun record(byteCount: Long, us: Long) {
        count += 1
        bytes += byteCount
        totalUs += us
        if (us > maxUs) maxUs = us
        flushIfDue()
    }

    private fun flushIfDue() {
        val now = com.kapp.tidings.core.nowMillis()
        val elapsed = now - windowStart
        if (elapsed < WINDOW_MS || count == 0) return
        val head = "[screen] " + tag + " n=" + count
        println(
            if (split) {
                head +
                    " firstAvgMs=" + (firstUs / count / 1_000) +
                    " secondAvgMs=" + (secondUs / count / 1_000) +
                    " maxMs=" + (maxUs / 1_000)
            } else {
                head +
                    " avgUs=" + (totalUs / count) +
                    " maxUs=" + maxUs +
                    " kbps=" + (bytes * 8 / maxOf(elapsed, 1L)) +
                    " bytes=" + bytes
            },
        )
        count = 0
        totalUs = 0
        maxUs = 0
        bytes = 0
        firstUs = 0
        secondUs = 0
        windowStart = now
    }

    private companion object {
        const val WINDOW_MS = 10_000L
    }
}
