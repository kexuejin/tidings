// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.testutil

import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 双节点测试的端口分配器（`:app` jvmTest 共用，单一事实源）。
 *
 * ## 旧写法为什么会在全量跑里轮换着红
 *
 * 四个双节点测试原先各抄一份
 * `private fun freePort(): Int = ServerSocket(0).use { it.localPort }`，它有两个洞：
 *
 * 1. **只证明 TCP 空闲，不证明 UDP 空闲**。`RelaySettings.toProfile` 的端口布局是
 *    `udpPort = n`（UDP 发现口）、`tcpPort = n+1`、`tlsPort = n+2`（再派生 `+3/+4`
 *    给跨网文件与屏幕监听）。`ServerSocket(0)` 探的却是 TCP：同 JVM 里前一条用例
 *    残留的 UDP socket 完全可能占着同一个号，探测照样通过。
 * 2. **端口拿到就放，还连拿两次**。`hostedGroup()` 里 `freePort()` 连调两次，内核
 *    经常把刚释放的号（或紧邻的下一个号）再发给第二次 ⇒ `portB == portA` 或
 *    `portB == portA + 1`，两个节点抢同一组 UDP/TCP/TLS 口。
 *
 * 表现是 `host.start()` 绑不上 UDP、`network.phase` 永不进 `Running`，
 * `awaitUntil("B 起来")` 在满载 883 用例的 JVM 里偶发 30s 超时；单跑该测试类
 * 6/6 全绿（没有别的 socket 跟它抢），所以「调大观测预算」只会把偶发藏得更深。
 *
 * ## 修法
 *
 * - 从**内核临时端口段之下**（21000 起）的固定区间按 `AtomicInteger` 顺序取号：
 *   同 JVM 内永不复用同一块，别的测试用 `ServerSocket(0)` 拿到的系统临时端口
 *   （macOS 49152+、Linux 32768+）落不到这里。
 * - 块宽 8：一个节点最多占 `n..n+4`，相邻两块不重叠。
 * - 取号时**按真实协议逐个 bind 探测**（UDP 用 DatagramSocket、其余用 ServerSocket）；
 *   被本机服务或残留 socket 占住就换下一块，不猜、不睡。
 *
 * 这是修测试装置的线程/资源竞态，不是放宽断言，也不是把观测预算调大。
 */
object TestPorts {

    /** 起点：低于 Linux 临时端口下界 32768，避开所有 `ServerSocket(0)` 的结果。 */
    private const val FIRST = 21_000

    /** 块宽：`udp / tcp / tls / 跨网文件 / 跨网屏幕` 占 `n..n+4`，留 3 个余量。 */
    private const val STRIDE = 8

    /** 最多 1400 块 ⇒ 21000..32192，仍在 32768 之下。 */
    private const val BLOCKS = 1_400

    private val cursor = AtomicInteger(0)

    /**
     * 返回一个节点的 **UDP 基准口**；调用方沿用既有口径 `tcpPort = base + 1`。
     * 每次调用得到的块与同 JVM 内此前所有调用都不重叠。
     */
    fun freeUdpBase(): Int {
        repeat(BLOCKS) {
            val base = FIRST + cursor.getAndIncrement().mod(BLOCKS) * STRIDE
            if (blockIsFree(base)) return base
        }
        error("双节点端口池用尽：试过 $BLOCKS 块都没有可用的 UDP/TCP 端口组")
    }

    /** 按 `RelaySettings.toProfile` 的真实端口布局逐个协议探测，任一被占就整块弃用。 */
    private fun blockIsFree(base: Int): Boolean {
        val udp = runCatching { DatagramSocket(base) }.getOrNull() ?: return false
        udp.close()
        for (offset in 1..4) {
            val tcp = runCatching { ServerSocket(base + offset) }.getOrNull() ?: return false
            tcp.close()
        }
        return true
    }
}
