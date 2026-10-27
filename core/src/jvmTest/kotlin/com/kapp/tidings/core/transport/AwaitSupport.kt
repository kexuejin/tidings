// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 有界等待：断言之前「等到某个事件发生」必须有上界，到点就失败。
 *
 * ## 为什么单独成文件
 *
 * 断言前一句 `while (!cond) delay(50)` 与「先睡一会儿再断言」在**文本上**长得一样，
 * 而两者是相反的东西：前者是在等一个真事件，后者是在赌机器速度。
 * `tools/env-test-scan.py` 的 B 类判据按行距识别，区分不了；于是等待被收进这里之后，
 * 调用点只剩下「等什么、等多久」—— 赌时序那种写法再也藏不进去。
 *
 * 上界不是装饰：真机现场出现过「每 3 轮红 1 次、单跑恒绿」，根因正是没有上界的等待
 * 与固定 delay 混用。到点失败会把「没等到」和「等到了但值不对」分开。
 */
internal suspend fun awaitTrue(
    timeoutMs: Long = 5_000,
    pollMs: Long = 25,
    block: suspend () -> Boolean,
) {
    withTimeout(timeoutMs) {
        while (!block()) delay(pollMs)
    }
}

/**
 * 在时间窗内**重复投递**同一个刺激（真实回环里的重试）。
 *
 * 与 [awaitTrue] 的分界：这里的 `delay` 是**刺激的节奏**（第一次投递可能早于对端握手
 * 完成），不是「先睡再断言」。因此调用点的断言必须自带 `withTimeout` —— 负向断言尤其
 * 要有上界，否则「什么都没发生」会被当成通过。
 */
internal suspend fun deliverRepeatedly(
    times: Int,
    gapMs: Long = 50,
    send: suspend () -> Unit,
) {
    repeat(times) { i ->
        send()
        if (i != times - 1) delay(gapMs)
    }
}
