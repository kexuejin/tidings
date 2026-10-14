// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 受限广播（`255.255.255.255`）**真的发得出去**吗。
 *
 * 这条测试守的是一个 2026-10-07 在 Pixel 6 上抓出来的静默故障：`UdpTransport` 起初
 * 用 `aSocket(selector).udp().bind(...)` 建套接字，没有打开 `SO_BROADCAST`。于是
 * `Discovery` 每 30 秒的那一轮心跳广播被内核**直接拒发**（JVM 的 `DatagramChannel`
 * 抛 `BindException: Permission denied`），而 `Discovery.sendRaw` 是 best-effort
 * 的 `runCatching` —— 异常被吞掉，发送侧看起来一切正常。
 *
 * 真机症状：手机与 Mac 上的节点**永远互相「离线」**，只有手动填过地址的单播还通；
 * 双方的名字都退化成 nodeId 前 8 位。用 Python 手工发同一条广播给手机，手机立刻
 * 回了一帧 `entry` —— 收得到，是发不出去。
 *
 * 为什么不用假传输：这件事只有真套接字才暴露。`SO_BROADCAST` 缺失时发送**不报错**
 * （被吞）也**不送达**，任何在内存里跑的假传输都会绿。
 *
 * 反向对照（改回不打开 `SO_BROADCAST` 后必须红）：见提交信息里的负向对照记录。
 */
class UdpTransportBroadcastTest {

    @Test
    fun aRestrictedBroadcastIsActuallyDeliveredToALocalListener() = runBlocking {
        // 收广播必须绑通配地址（`0.0.0.0`）——绑到 `127.0.0.1` 收不到广播，这一点
        // 与 `DiscoveryProfileRefreshTest` 里的单播夹具正好相反。
        val receiver = UdpTransport(port = 0)
        val sender = UdpTransport(port = 0)
        receiver.start()
        sender.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = mutableListOf<ByteArray>()
        val collect = scope.launch {
            receiver.incoming.collect { seen += it.bytes }
        }

        try {
            // SharedFlow 没有 replay：订阅要先挂上，否则早发的报文会被丢掉。
            delay(200)
            val payload = byteArrayOf(0x52, 0x4C, 0x01)
            sender.send(payload, "255.255.255.255", receiver.boundPort)
            awaitTrue(what = "受限广播没有送达：SO_BROADCAST 没打开时内核直接拒发，而发送侧被 runCatching 吞掉") {
                seen.isNotEmpty()
            }
            assertEquals(payload.toList(), seen.first().toList())
        } finally {
            collect.cancel()
            scope.cancel()
            sender.stop()
            receiver.stop()
        }
    }

    /** 轮询到条件成立为止；超时就带着 [what] 断言失败（比挂死或裸超时好读）。 */
    private suspend fun awaitTrue(timeoutMs: Long = 5_000, what: String, condition: () -> Boolean) {
        repeat((timeoutMs / 20).toInt()) {
            if (condition()) return
            delay(20)
        }
        assertTrue(condition(), what)
    }
}
