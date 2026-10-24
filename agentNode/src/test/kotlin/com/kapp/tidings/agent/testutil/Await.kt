// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.testutil

/**
 * agentNode 测试的**唯一**一份轮询助手（本模块不许再出现第二份 deadline 循环）。
 *
 * ## 为什么要有它
 *
 * 「等某个副作用真的发生」在测试里只有两种写法：睡一段固定时间再断言
 * （`Thread.sleep(800)`），或者轮询到**可观测信号**为止并带超时。前者在慢机器上是
 * 假绿（还没准备好就断言，恰好断言到旧值）、在快机器上白等；失败时的症状还只是
 * "断言拿到旧值"，与真正的缺陷混在一起分不开。本仓的对应纪律是 coroutine 版的
 * `withTimeout + delay`；本模块的测试是阻塞式 JUnit（真 HTTP / 真 socket），
 * 等价物就是**单调时钟 deadline + 抛错**。
 *
 * ## 三条约定
 *
 * 1. 截止时刻用 [System.nanoTime]：墙钟被 NTP 回拨时用 `currentTimeMillis` 算的
 *    deadline 会瞬间过期（偶发红），前拨时又会等很久 —— 都与被测行为无关。
 * 2. 超时**抛 [AssertionError]** 并写清"在等什么"：静默返回空/旧值会把失败伪装成
 *    断言不符，读的人看不出是链路没通还是判据写错。
 * 3. 轮询间隔固定 [POLL_MS]：回环调用足够便宜，不需要退避。
 *
 * ## 生命周期
 *
 * 只被 `:agentNode` 的 `test` 源集引用（不是生产源集，也不跨模块复用 ——
 * 跨模块复用会让一份助手同时承担两套时序假设）。
 */
object Await {

    /** 轮询间隔：够密以抓住瞬时状态，又不至于把回环端口打满。 */
    const val POLL_MS: Long = 25L

    /** 单调毫秒（不受墙钟跳变影响）。 */
    fun nowMillis(): Long = System.nanoTime() / 1_000_000

    /**
     * 轮询到 [probe] 返回非 null 为止并返回该值。
     *
     * @param what 在等什么 —— 会出现在超时消息里，所以写"哪个可观测事实"，不要写"等一会"。
     */
    fun <T : Any> value(what: String, timeoutMs: Long = 5_000L, probe: () -> T?): T {
        val deadline = nowMillis() + timeoutMs
        while (nowMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(timeoutMs.toString() + "ms 内没有等到：" + what)
    }

    /** 轮询到 [probe] 为真为止；超时抛 [AssertionError]。 */
    fun condition(what: String, timeoutMs: Long = 5_000L, probe: () -> Boolean) {
        value(what, timeoutMs) { if (probe()) Unit else null }
    }
}
