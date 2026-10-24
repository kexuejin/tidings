// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.kapp.tidings.core.testutil.awaitTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * K2 的**策略**（不是握手本身，握手在 TlsLinkTransportTest）：什么情况下该拨号、什么情况下
 * 必须拒绝。
 *
 * 核心那条是 fail-closed：**pin 验不过时绝不能回退明文**。这里用两个计数替身把
 * "TLS 连接层"与"局域网明文层"分开看 —— 断言明文层一次都没被碰过。
 */
class LinkPolicyTest {

    private class CountingTransport(override val isSecure: Boolean) : LinkTransport {
        var connects = 0
        /** 每次拨号的目标地址：负控要断言"这个地址一次都没出现过"，而不只是计数为 0。 */
        val hosts = mutableListOf<String>()
        var lastHost: String? = null
        var lastPort: Int = 0
        var lastPin: TlsPin? = null
        override suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): LinkConnection? {
            connects++
            hosts += host
            lastHost = host
            lastPort = port
            lastPin = expectedPin
            return null
        }

        override suspend fun accept(
            socket: Socket,
            read: ByteReadChannel,
            write: ByteWriteChannel,
            remoteHost: String,
            remotePort: Int,
            expectedPin: TlsPin?,
        ): LinkConnection? = null
    }

    private fun profile(nodeId: String, tlsPort: Int? = null) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "h",
        platform = "linux",
        tcpPort = 17879,
        tlsPort = tlsPort,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_LINK),
    )

    private fun client(
        registry: PeerRegistry,
        secure: CountingTransport,
        plaintext: CountingTransport,
        scope: CoroutineScope,
    ) = LinkClient(
        registry = registry,
        transport = secure,
        plaintextTransport = plaintext,
        scope = scope,
        selfNodeId = { "self" },
        enabled = { true },
        tickMs = 20,
        connectTimeoutMs = 50,
    )

    @Test
    fun aPublicPeerWithoutAPinIsNeverDialed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        registry.touch("peer", "2001:db8:2020:8494::9", 17878, profile("peer", tlsPort = 17880), 1L, confirmed = true)
        val secure = CountingTransport(isSecure = true)
        val plaintext = CountingTransport(isSecure = false)
        // 探针：再注册一台**私网**对端（它一定会走明文路径）。等到它被拨，就证明调度
        // 循环至少完整跑过一轮 —— 公网那台在同一轮里也被评估过。负控因此不再需要
        // "sleep 300ms 赌客户端已经跑过"，结论由顺序决定。
        val publicHost = "2001:db8:2020:8494::9"
        registry.touch("probe", "192.168.9.9", 17878, profile("probe"), 1L, confirmed = true)
        val link = client(registry, secure, plaintext, scope)
        link.start()
        awaitTrue(what = "私网探针对端始终没被拨：调度循环根本没跑，这条负控无法成立") {
            plaintext.connects > 0
        }
        link.stop()
        scope.cancel()

        assertEquals(0, secure.connects, "公网 + 没 pin：一个 TLS 连接都不许拨")
        assertTrue(publicHost !in plaintext.hosts, "公网更不能退回明文：明文层从没碰过这个地址")
    }

    @Test
    fun aPinnedPublicPeerUsesTlsAndNeverFallsBackToPlaintext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        registry.touch("peer", "2001:db8:2020:8494::9", 17878, profile("peer", tlsPort = 17880), 1L, confirmed = true)
        registry.setPin("peer", "sha256:" + "a".repeat(64))
        val secure = CountingTransport(isSecure = true)
        val plaintext = CountingTransport(isSecure = false)
        val link = client(registry, secure, plaintext, scope)
        link.start()
        awaitTrue(what = "有 pin 就该拨 TLS（等不到任何 TLS 拨号）") { secure.connects > 0 }
        link.stop()
        scope.cancel()

        assertTrue(secure.connects > 0, "有 pin 就该拨 TLS")
        assertEquals("sha256:" + "a".repeat(64), secure.lastPin?.wire, "拨号时必须把 pin 交给连接层去验")
        assertEquals(0, plaintext.connects, "TLS 没连上（pin 验不过）也**绝不**回退明文 —— fail-closed")
    }

    @Test
    fun aPublicCandidateIsDialedWithTlsEvenWhenTheCurrentAddressIsLan() = runBlocking {
        // task-27 真机现场：registry 的"当前地址"是上一次学到的局域网 IPv4（移动网不可达），
        // 而配对码存下来的公网 IPv6 才是通的。这里断言：候选池会被遍历到，并且**公网候选走 TLS**。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        registry.touch("peer", "192.168.2.9", 17878, profile("peer", tlsPort = 17880), 1L, confirmed = true)
        registry.setCandidates("peer", listOf(PeerEndpoint("2001:db8:2020:8494::9", 17878)))
        registry.setPin("peer", "sha256:" + "a".repeat(64))
        val secure = CountingTransport(isSecure = true)
        val plaintext = CountingTransport(isSecure = false)
        val link = client(registry, secure, plaintext, scope)
        link.start()
        awaitTrue(what = "局域网明文与公网 TLS 两条路都没走全（等不到两者都拨过）") {
            plaintext.connects > 0 && secure.connects > 0
        }
        link.stop()
        scope.cancel()

        assertTrue(plaintext.connects > 0, "当前地址（局域网）先试一次明文，这是 K1 的路径")
        assertTrue(secure.connects > 0, "局域网试不通后必须轮到公网候选，并且走 TLS")
        assertEquals("2001:db8:2020:8494::9", secure.lastHost, "TLS 拨的是候选里的公网地址")
        assertEquals(17880, secure.lastPort, "公网候选必须用 tlsPort，不是 tcpPort")
        assertEquals("sha256:" + "a".repeat(64), secure.lastPin?.wire, "候选拨号同样要带上 pin 去验")
    }

    @Test
    fun aPrivatePeerStillUsesThePlaintextLanPath() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        registry.touch("peer", "192.168.2.9", 17878, profile("peer"), 1L, confirmed = true)
        val secure = CountingTransport(isSecure = true)
        val plaintext = CountingTransport(isSecure = false)
        val link = client(registry, secure, plaintext, scope)
        link.start()
        awaitTrue(what = "局域网明文路径（K1）没被拨：这条路径不得失效") { plaintext.connects > 0 }
        link.stop()
        scope.cancel()

        assertTrue(plaintext.connects > 0, "局域网明文路径（K1）必须并存、不得失效")
        assertEquals(0, secure.connects, "没 pin 时不走 TLS")
    }
}
