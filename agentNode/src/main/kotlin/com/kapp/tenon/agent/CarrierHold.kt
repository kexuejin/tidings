// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlinx.coroutines.runBlocking

/**
 * 控制面里**唯一**允许「按住载体线程等协程」的地方。
 *
 * ## 为什么需要它
 *
 * `ControlPlane` 的每个 HTTP 处理器都是同步函数（`com.sun.net.httpserver` 的形状），
 * 而它要调的东西（发帧、握手、插件往返）全是 `suspend`。于是每个处理器都得在某处
 * `runBlocking`。这不是可以靠"改成挂起"一笔抹掉的 —— 处理器签名本身不是挂起的。
 *
 * 真机事故（task-35）：手机切到移动网后本机 `127.0.0.1:8788` 连续 5 次 HTTP 000、
 * 每次卡满 8.00 s。8.00 s 正是"给一个不可达对端发一帧"的完整退避（UDP 1+2+4 s +
 * TCP 兜底）。固定 4 条处理器线程被这种请求按住 ⇒ 连 `/v1/peers` 都打不开，
 * 而它正是观测跨网状态的唯一手段。
 *
 * 第一步修法是**加线程**（16 条 + 虚拟线程优先，见 `start()` 的 KDoc）。那解决了
 * 饿死，但没解决"没有人知道某一处按了多久"：慢下来的时候现场只剩「控制面卡」这个
 * 症状，没有读数能指出是哪一处。这里补的就是这个读数。
 *
 * ## 为什么**不**加超时（与计划书原话的偏离，明写在此）
 *
 * 计划书 ARP-2 ② 写的是「最坏路径（发不可达对端 8s）加超时包裹」。实现时判断不该加，
 * 理由是**加了会更坏**：
 *
 * 1. 8 s 不是异常值，是**退化路径的正常值**。超时设 8 s 会在对端不可达（最常见的情形）
 *    时正常触发；设 12 s 则只在更糟的时候触发，而那正是需要把帧留在队列里的时刻。
 * 2. 一旦超时，这一帧**是否已进待发队列是不可知的**：`sendText` 的 `SendOutcome`
 *    只有 `SENT` / `QUEUED` 两档，"超时"落不进任何一档。硬塞一个进去，就是在
 *    「发送侧一切正常、接收侧什么都没有」这条本仓最贵的缺陷族上再开一个口子。
 * 3. 协程被外部取消时退避循环会停在中间，`requeue` 那一跳可能没跑到 —— 那才是真的丢帧。
 *
 * 所以这里只做**带预算的看门狗**：超预算打一行 `[RELAY-HOLD]`，不改变任何语义。
 * 预算值是**预期的退化上限**，超过它说明退避链变长了（回归），而不是"这一次网络不好"。
 *
 * 反过来说：如果哪天真的要在这里加超时，必须同时给 `SendOutcome` 加一档"结果未知"，
 * 并让调用方（手机、CLI、脚本）都能如实转述它 —— 不是在这个文件里加一行 `withTimeout`。
 *
 * ## 用法
 *
 * ```
 * val outcome = CarrierHold.hold("sendText", CarrierHold.SEND_MS) { runtime.sendText(nodeId, text) }
 * ```
 *
 * **不许**把这里的 `runBlocking` 改成"投后台立即返回"：若干处理器依赖返回值做判定
 * （发失败要回 502、解析不到要回 404），投后台就把"如实回包"变成了"假装成功"。
 */
internal object CarrierHold {

    /** 一帧可靠发送的最坏退避：UDP 1+2+4 s + TCP 兜底，实测 ≈ 8 s。 */
    const val SEND_MS: Long = 8_000

    /** 按主机名/IP 找对端的一次握手窗口 —— `resolvePeerByHost` 自己就是 3 s。 */
    const val RESOLVE_MS: Long = 3_000

    /** 只读本地内存快照（台账、投递表）：不碰网络，超预算就是有锁被抢了。 */
    const val LOCAL_MS: Long = 50

    /** 到插件的往返（派单 / 动作）：插件那边的 HTTP 超时 + 一次序列化。 */
    const val PLUGIN_MS: Long = 30_000

    /** 整段插件任务：与派单租约同档（10 min），超了由租约机制处理，不在这里杀。 */
    const val PLUGIN_TASK_MS: Long = 10L * 60_000

    /**
     * 按住当前（虚拟）线程等 [block] 跑完，并在超过 [budgetMs] 时打一行读数。
     *
     * [what] 是给人看的标签，出现在 `[RELAY-HOLD]` 日志里 —— 写"路由 + 动作"，
     * 不要写变量名（`"sendText"` 好过 `"send"`，`"agentChannel.invoke"` 更好）。
     *
     * 返回值与异常**原样透传**：这是个透明的看门狗，不是策略点。
     */
    fun <T> hold(what: String, budgetMs: Long, block: suspend () -> T): T = runBlocking {
        val startedNanos = System.nanoTime()
        try {
            block()
        } finally {
            // 放在 finally 里：抛异常那条路同样是"按住了线程 X 毫秒"，也值得记。
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
            relayHoldLine(what, elapsedMs, budgetMs)?.let(::println)
        }
    }

    /**
     * 超预算那一行的**措辞与判定**，抽成纯函数：判据不依赖真实耗时（合成输入就能测），
     * 也就不必在测试里 `Thread.sleep` 赌时序（`envDependentTestGate` 的 B 类）。
     *
     * 边界是 `>`，不是 `>=`：预算就是「按契约最多按这么久」，正好按到预算还属于按契约办事。
     * 这条边界有判据（[com.kapp.tenon.agent.CarrierHoldTest]）—— 否则把 8 s 的退化路径
     * 每天刷成日志，"超预算"这个信号第一次出现时就没人看它了。
     *
     * @return 超预算时返回要打印的那行；未超预算返回 `null`（调用方据此**什么都不打**）。
     */
    fun relayHoldLine(what: String, elapsedMs: Long, budgetMs: Long): String? {
        if (elapsedMs <= budgetMs) return null
        return "[RELAY-HOLD] " + what + " held a handler thread for " + elapsedMs +
            "ms (budget " + budgetMs + "ms)"
    }
}
