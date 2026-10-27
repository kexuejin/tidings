// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 有界等待（与 `core` 测试里的同名工具同一条纪律，跨模块不能共享测试源码，故各一份）。
 *
 * 断言之前「等到某个真事件」必须有上界；`while (!cond) delay(N)` 与「先睡再断言」
 * 在文本上一样，`tools/env-test-scan.py` 的 B 类判据区分不了，所以等待收进具名函数，
 * 调用点只写「等什么、等多久」。
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

/** 在时间窗内重复投递同一个刺激（真实回环重试）；断言需自带 `withTimeout`。 */
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
