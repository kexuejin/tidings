// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.testutil.awaitTrue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `UdpTransport` 起停之后，**它的 selector 线程必须被还回去**。
 *
 * ## 为什么要单测这件事
 *
 * `UdpTransportBroadcastTest`（依赖真广播）在整包跑时必红、单跑恒绿。二分到「只要有
 * 任意其它 transport 用例先跑就会红」，而改成同步订阅也无效 —— 说明不是订阅竞态。
 * 最像的解释是：每次 `UdpTransport` 都 new 一个 `SelectorManager`，`stop()` 没把它关掉，
 * 于是同 JVM 里越跑越多 selector 线程，把后来者的接收循环拖死。
 *
 * 这条用例就是那个假设的判据：连续起停 N 个 transport，看 selector 线程数会不会回落。
 * 它不测时序、不依赖网卡，只数线程 —— 机器忙不忙都一样。
 *
 * 若它红了：泄漏成立，修 `UdpTransport.stop()`（关 selector）而不是改测试。
 * 若它绿：泄漏不成立，广播那条另有原因（那就把 D 类用例隔离到独立任务）。
 */
class UdpTransportLifecycleTest {

    @Test
    fun stoppingATransportReleasesItsSelectorThreads() = runBlocking {
        // 预热：第一轮会把线程池/类加载的开销付掉，避免把初始化算成泄漏。
        repeat(5) {
            val t = UdpTransport(port = 0)
            t.start()
            t.stop()
        }
        // 基线取**当前**线程数，不做绝对值断言：同包其它用例可能各自留有 selector
        // （它们的生命周期归它们自己），这条只回答一个问题 ——「我们起停 50 次的这 50 个
        // 有没有还回去」。用相对增量判断，测试顺序就不会影响结论。
        val baseline = selectorThreads()

        repeat(50) {
            val t = UdpTransport(port = 0)
            t.start()
            t.stop()
        }

        val settled = runCatching {
            awaitTrue(what = "起停 50 个 UdpTransport 后 selector 线程没有回落", timeoutMs = 10_000) {
                selectorThreads() <= baseline + 1
            }
        }.isSuccess
        assertTrue(
            settled,
            "起停 50 个 UdpTransport 后 selector 线程仍在增长（基线 $baseline，现在 ${selectorThreads()}）" +
                " —— stop() 没有关闭它的 SelectorManager；同 JVM 里后续用例的接收循环会被拖死" +
                "（UdpTransportBroadcastTest 整包必红就是这么来的）",
        )
    }

    /** 只数「栈里有 io.ktor.network.selector」的线程：ktor 的线程名是 pool-N-thread-M，不能按名认。 */
    private fun selectorThreads(): Int = Thread.getAllStackTraces().entries.count { (_, stack) ->
        stack.any { it.className.startsWith("io.ktor.network.selector") }
    }
}
