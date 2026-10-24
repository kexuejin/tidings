// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.testutil

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertTrue

/**
 * 测试里的**唯一**等待策略：轮询 + 硬超时，不用固定 delay 赌"那个操作差不多完成了"。
 *
 * ## 为什么必须有这一份
 *
 * AGENTS §4 的纪律是「并发/时序类测试不要用固定 delay 赌时间：用轮询 + `withTimeout`」。
 * 但这条纪律此前是靠**每个测试文件各自抄一份** `awaitTrue` / `sendUntil` 落实的 ——
 * 于是出现了三个版本（`repeat(timeoutMs/20)`、`while (nowMillis() < deadline)`、
 * `sendUntil`），每个都带自己的超时口径与失败文案习惯；新写的测试想等一个条件时，
 * 最自然的动作还是 `delay(200)` 然后断言。
 *
 * 现在真正的语义只有一条：**条件成立即返回；到点仍不成立则带着原因断言失败**。
 * 超时是硬超时（`withTimeoutOrNull` 单调计时），不是"数够 N 次 delay"；后者在慢机器上
 * 会退化成不同的实际超时，正是「一台机器绿、另一台红」的来源。
 *
 * 失败时会**再查一次条件**：轮询与超时之间的边界上条件可能刚好成立，
 * 那属于"等到了"，不该报成超时。
 *
 * ## 两种形态
 *
 * - [awaitTrue]：只等条件（被测对象自己会推进，例如连接建立、状态迁移）。
 * - [awaitAfterSend]：每次轮询前**先发一帧**再等。UDP + `SharedFlow`（没有 replay）的
 *   夹具必须这样写：订阅之前的帧会被直接丢掉，只发一次靠调度碰运气 —— 真机上本来就是
 *   周期性重发的，按真实节奏重发才是对的（见 `Discovery` 的 scan 帧）。
 *
 * 这两个函数住在 `testutil`（文件名不带 `Test`），所以 `tools/env-test-scan.py` 的
 * B 类信号（断言前的固定 `delay`）不会命中它们 —— 那份 delay 是**轮询间隔**，
 * 不是"赌某个操作已经完成"。
 */

/** 轮询间隔：足够密（不拖慢断言）又足够稀（不让轮询本身烧 CPU）。 */
private const val POLL_MS = 20L

/** 默认硬超时：本仓真套接字/协程夹具的经验上界，超过它就是真有东西没跑起来。 */
const val DEFAULT_AWAIT_MS = 5_000L

/**
 * 轮询直到 [condition] 成立；[timeoutMs] 内不成立则用 [what] 断言失败。
 *
 * [what] 必须写**用户/产品视角的后果**（"受限广播没有送达：SO_BROADCAST 没打开时内核直接拒发"），
 * 不要写"等待超时" —— 后者等于把"测试挂了"当结论，读的人还得回去查代码。
 */
internal suspend fun awaitTrue(
    what: String,
    timeoutMs: Long = DEFAULT_AWAIT_MS,
    condition: suspend () -> Boolean,
) {
    val reached = withTimeoutOrNull(timeoutMs) {
        while (!condition()) delay(POLL_MS)
        true
    } ?: false
    assertTrue(reached || condition(), what)
}

/**
 * 每次轮询前先 [send] 一帧，直到 [condition] 成立。
 *
 * [resendMs] 是**重发节奏**（对应真机上周期性的 scan/entry 帧），不是"等它完成"的猜测；
 * 整段仍受 [timeoutMs] 硬超时约束。
 */
internal suspend fun awaitAfterSend(
    what: String,
    timeoutMs: Long = DEFAULT_AWAIT_MS,
    resendMs: Long = POLL_MS,
    send: suspend () -> Unit,
    condition: suspend () -> Boolean,
) {
    val reached = withTimeoutOrNull(timeoutMs) {
        while (!condition()) {
            send()
            delay(resendMs)
        }
        true
    } ?: false
    assertTrue(reached || condition(), what)
}
