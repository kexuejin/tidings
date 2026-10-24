// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 受限广播（`255.255.255.255`）**真的发得出去**吗。
 *
 * 守的是 2026-10-07 在 Pixel 6 上抓出来的静默故障：`UdpTransport` 起初用
 * `aSocket(selector).udp().bind(...)` 建套接字、没有打开 `SO_BROADCAST`，于是每 30 秒
 * 那一轮心跳广播被内核直接拒发（`BindException: Permission denied`），而
 * `Discovery.sendRaw` 是 best-effort 的 `runCatching` —— 异常被吞，发送侧看起来一切正常。
 * 真机症状：两端**永远互相「离线」**，只有手填地址的单播还通。
 *
 * ## 三种结局，必须能区分（否则这条判据会说谎）
 *
 * 1. **送达** ⇒ 通过。
 * 2. **内核拒绝发广播**（`Permission denied` / `BindException`）⇒ **失败**。这正是原始缺陷。
 * 3. **本机没有可用的广播路由**（`No route to host`）⇒ 打印 `[env-skip]` 并跳过。
 *    实测这台 macOS 上 JDK 的 UDP 通道发 `255.255.255.255` 会拿到 NoRouteToHost，
 *    而同一时刻**原生** `DatagramSocket` 发同一条广播能送达（下面用 `osCanBroadcast`
 *    现场核对）。两者报的是不同错误：没开 SO_BROADCAST 报 Permission denied，
 *    所以跳过不会掩盖原始缺陷；而在这种机器上硬断言只会恒红。
 *
 * 为什么接收端用原生 `DatagramSocket`：判据只关心「发送端真的把包交出去了」，
 * 原生接收端更严格，也不依赖 `UdpTransport` 的接收循环（同 JVM 里别的真实 socket
 * 用例会拖慢它，见 `UdpTransportLifecycleTest`）。
 */
class UdpTransportBroadcastTest {

    @Test
    fun aRestrictedBroadcastIsActuallyDeliveredToALocalListener() = runBlocking {
        // 收广播必须绑通配地址（0.0.0.0）；绑到 127.0.0.1 收不到广播。
        val receiver = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
        receiver.soTimeout = 100
        val sender = UdpTransport(port = 0)
        sender.start()
        val payload = byteArrayOf(0x52, 0x4C, 0x01)
        var received: ByteArray? = null
        try {
            repeat(40) {
                sender.send(payload, "255.255.255.255", receiver.localPort)
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)
                if (runCatching { receiver.receive(packet) }.isSuccess) {
                    received = buffer.copyOf(packet.length)
                    return@repeat
                }
            }
            if (received != null) {
                assertEquals(payload.toList(), received?.toList())
                return@runBlocking
            }

            val error = sender.lastSendError.orEmpty()
            val osCanBroadcast = osCanBroadcast()
            if (error.contains("No route to host")) {
                println(
                    "[env-skip] 本机 JDK UDP 通道发不了受限广播（$error）；" +
                        "同一时刻 OS 原生 socket 能否发送=$osCanBroadcast。" +
                        "这不是 SO_BROADCAST 缺失（那种情况会报 Permission denied）。",
                )
                return@runBlocking
            }
            fail("受限广播没有送达：$error（OS 原生 socket 能否发送=$osCanBroadcast）")
        } finally {
            sender.stop()
            receiver.close()
        }
    }

    /** 现场核对：同一时刻**原生** socket 能不能发受限广播（用来区分「环境没有路由」与「产品缺陷」）。 */
    private fun osCanBroadcast(): Boolean = runCatching {
        DatagramSocket(0, InetAddress.getByName("0.0.0.0")).use { probe ->
            probe.broadcast = true
            probe.send(
                DatagramPacket(
                    byteArrayOf(0x52),
                    1,
                    InetAddress.getByName("255.255.255.255"),
                    9,
                ),
            )
        }
        true
    }.getOrDefault(false)
}
