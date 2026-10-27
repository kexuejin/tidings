// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 入站**二进制帧**（文件/画面字节）的归属判据：握手指纹 → 对端端点。
 *
 * 两条来源，缺一条就会出现"会话被信任、但帧被整条丢掉"：
 *  - Rust TrustStore 的指纹账本（**对端来配对**时写入，deviceId 是 nodeId 或 host:port）；
 *  - 本机的「地址 ↔ 公钥」配对表（**我们扫对方的码**时写入，[LuciolaTransportFactory.pairPeer]）。
 *
 * 真机 50MB 验收踩到的就是第二条缺：手机只扫过桌面二维码 ⇒ TrustStore 空 ⇒
 * 文件帧归属不到对端 ⇒ 发送端一路重传、收端一个字节都不落盘，
 * 而两边都不报错（"对方说发了、我这边卡在接收中"）。
 */
class EndpointOfFingerprintTest {

    private fun profile(id: String) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "linux", tcpPort = 17879,
        ver = "test", caps = emptyList(),
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun pairedEndpointAloneIsEnoughToAttributeBinaryFrames() = runBlocking {
        val desktopPort = freePort()
        val peerPort = freePort()
        // 手机只扫了桌面的码：endpoints 有、TrustStore 里没有对方。
        val phone = LuciolaTransportFactory(profile("phone"), 0, seedPersist = { "1a".repeat(32) })
        val desktop = LuciolaTransportFactory(profile("desktop"), 0, seedPersist = { "2b".repeat(32) })
        phone.pairPeer("127.0.0.1", desktopPort, desktop.publicKey)

        val registry = PeerRegistry("phone").apply {
            touch("desktop-node", "127.0.0.1", peerPort, profile("desktop-node"))
        }
        // 桌面在 registry 里的地址是它**声称**的那个（peerPort），而互拨用的端口是 desktopPort；
        // 归属只需把指纹映射回「这台设备」，端口由 registry 决定。
        val endpoint = phone.endpointOfFingerprint(desktop.fingerprint, registry)
        assertNotNull(endpoint, "只扫过码（endpoints 有、TrustStore 空）也必须能归属出端点")
        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(peerPort, endpoint.port)
    }

    @Test
    fun unknownFingerprintIsNotAttributed() = runBlocking {
        val phone = LuciolaTransportFactory(profile("phone"), 0, seedPersist = { "3c".repeat(32) })
        val stranger = LuciolaTransportFactory(profile("stranger"), 0, seedPersist = { "4d".repeat(32) })
        val registry = PeerRegistry("phone").apply {
            touch("desktop-node", "127.0.0.1", freePort(), profile("desktop-node"))
        }
        assertEquals(
            null,
            phone.endpointOfFingerprint(stranger.fingerprint, registry),
            "没配对过的指纹一个端点都不许归属（deny-by-default）",
        )
    }
    /**
     * **同一台设备的其他端口也要能取到 pin**（画面信道在 udpPort+10、跨网在 tlsPort+1）。
     *
     * 配对表里只写了消息/文件那条端口的键。只按 (host, port) 精确查会让画面信道永远
     * 「未配对」—— 真机日志：`pin lookup 192.168.2.22:17888 registry=none pinned=false`，
     * 于是投屏在加密信道上根本拨不通，而 UI 只显示「连接中」。
     */
    @Test
    fun sameHostOtherPortStillResolvesThePin() = runBlocking {
        val phone = LuciolaTransportFactory(profile("phone"), 0, seedPersist = { "5f".repeat(32) })
        val desktop = LuciolaTransportFactory(profile("desktop"), 0, seedPersist = { "6a".repeat(32) })
        phone.pairPeer("192.168.2.82", 17878, desktop.publicKey)
        val channelPin = phone.pinForTest("192.168.2.82", 17878)
        val screenPin = phone.pinForTest("192.168.2.82", 17888)
        assertNotNull(channelPin)
        assertNotNull(screenPin, "同一台设备的画面端口也必须能取到 pin（否则投屏永远拨不通）")
        assertContentEquals(desktop.publicKey, screenPin ?: ByteArray(0))
    }

    /** 同一地址下有**两个不同身份**时必须拒绝（deny-by-default），不许猜。 */
    @Test
    fun ambiguousSameHostIsRefused() = runBlocking {
        val phone = LuciolaTransportFactory(profile("phone"), 0, seedPersist = { "7b".repeat(32) })
        val a = LuciolaTransportFactory(profile("a"), 0, seedPersist = { "8c".repeat(32) })
        val b = LuciolaTransportFactory(profile("b"), 0, seedPersist = { "9d".repeat(32) })
        phone.pairPeer("192.168.2.82", 17878, a.publicKey)
        phone.pairPeer("192.168.2.82", 17880, b.publicKey)
        assertEquals(
            null,
            phone.pinForTest("192.168.2.82", 17888),
            "同一 host 下两个不同身份 ⇒ 不许猜一个去拨",
        )
    }
}