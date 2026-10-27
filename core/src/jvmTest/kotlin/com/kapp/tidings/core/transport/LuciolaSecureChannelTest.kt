// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeoutOrNull as timeoutOrNull
import java.net.ServerSocket
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 阶段三验收:配对 → SecureChannelTransport 的双向收发 + 重启后 pin 仍生效。
 *
 * **全部走真实引擎与真实 TCP 回环** —— 不 mock 信道;端口取自系统临时端口,
 * 不依赖本机是否装了 VPN/代理(见「测试不许依赖用户环境」的教训)。
 */
@OptIn(ExperimentalEncodingApi::class)
class LuciolaSecureChannelTest {

    // ------------------------------------------------------------------ 夹具

    private fun profile(id: String) = Profile(
        nodeId = id, nick = "nick-$id", company = "", dept = "", team = "",
        avatar = 1, profileRev = 1, host = "h-$id", platform = "jvm-test",
        tcpPort = 0, ver = "1", caps = emptyList(),
    )

    private fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

    /** 每个 offset 一个确定性的 32 字节 seed(hex),同一 offset ⇒ 同一身份。 */
    private fun seedHex(offset: Int): String =
        (0 until 32).joinToString("") { i -> "%02x".format((offset * 31 + i * 7) and 0xff) }

    private fun factory(
        port: Int,
        seedOffset: Int,
        store: SecureStateStore = InMemorySecureStateStore(),
    ): LuciolaTransportFactory = LuciolaTransportFactory(
        profile = profile("factory-$seedOffset"),
        udpPort = port,
        seedPersist = { seedHex(seedOffset) },
        stateStore = store,
    )

    /** registry 里先有对端(等价 luciola 发现 found):入站帧归属要靠它。 */
    private fun registryWith(self: String, peerId: String, host: String, port: Int): PeerRegistry =
        PeerRegistry(self).apply { touch(peerId, host, port, profile(peerId)) }

    private fun frame(from: String, text: String): ByteArray =
        Codec.encodeToBytes(makeEnvelope(MsgTypes.MSG, from, Codec.textPayload(text)))

    /**
     * 一直重发到对端收到(或超时)。单次 send 返回 true 只代表"进了信道队列",
     * 不等于对端已交付 —— 全量测试并发时更需要这条兜底。
     */
    private suspend fun sendUntil(
        transport: DatagramTransport,
        bytes: ByteArray,
        host: String,
        port: Int,
        at: kotlinx.coroutines.Deferred<*>,
    ) {
        // 轮询间隔与改动前一致（100ms）：每轮都会真投一帧，密集轮询只会无谓地刷帧。
        awaitTrue(timeoutMs = 20_000, pollMs = 100) {
            if (at.isCompleted) true else { transport.send(bytes, host, port); false }
        }
    }

    /** 握手是异步的:send 在信道没连上时返回 false —— 与 Messenger 一样退避重试。 */
    private suspend fun sendEventually(
        transport: DatagramTransport,
        bytes: ByteArray,
        host: String,
        port: Int,
    ) {
        awaitTrue(timeoutMs = 15_000, pollMs = 50) { transport.send(bytes, host, port) }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun pairedPeersExchangeFramesBothWays() = runBlocking {
        val portA = freeTcpPort()
        val portB = freeTcpPort()
        val a = factory(portA, 1)
        val b = factory(portB, 2)
        a.pairPeer("127.0.0.1", portB, b.publicKey)
        b.pairPeer("127.0.0.1", portA, a.publicKey)

        val transportA = a.createTransport(registryWith("node-a", "node-b", "127.0.0.1", portB))
        val transportB = b.createTransport(registryWith("node-b", "node-a", "127.0.0.1", portA))
        transportA.start()
        transportB.start()
        try {
            // A → B:对端监听面接受 + 按 from 归到 registry 地址。
            val request = frame("node-a", "hello-from-a")
            val atB = async { withTimeout(20_000) { transportB.incoming.first() } }
            sendUntil(transportA, request, "127.0.0.1", portB, atB)
            val receivedByB = atB.await()
            assertContentEquals(request, receivedByB.bytes)
            assertEquals("127.0.0.1", receivedByB.host)
            assertEquals(portA, receivedByB.port, "入站帧必须带对端在 registry 里的地址")

            // B → A:回复走同一条入站信道(移动端不监听,拨回去拨不通)。
            val reply = frame("node-b", "reply-from-b")
            val atA = async { withTimeout(20_000) { transportA.incoming.first() } }
            sendUntil(transportB, reply, "127.0.0.1", portA, atA)
            val receivedByA = atA.await()
            assertContentEquals(reply, receivedByA.bytes)
            assertEquals(portB, receivedByA.port, "A 的外拨信道按拨号目标地址上报")
        } finally {
            transportA.stop()
            transportB.stop()
        }
    }

    @Test
    fun unpairedPeerIsDeniedByDefault() = runBlocking {
        val port = freeTcpPort()
        val stranger = factory(port, 3)
        val transport = stranger.createTransport(PeerRegistry("node-x"))
        transport.start()
        try {
            var everSent = false
            deliverRepeatedly(times = 20) {
                if (transport.send(frame("node-x", "nope"), "127.0.0.1", port)) everSent = true
            }
            assertFalse(everSent, "peerKey 查不到 ⇒ 必须拒绝建连(deny-by-default)")
        } finally {
            transport.stop()
        }
    }

    @Test
    fun untrustedInboundFramesAreNotDelivered() = runBlocking {
        val portB = freeTcpPort()
        val portC = freeTcpPort()
        val host = factory(portB, 4)
        val stranger = factory(portC, 5)
        // 陌生人知道 host 的公钥(它可以拨号),host 不认识它。
        stranger.pairPeer("127.0.0.1", portB, host.publicKey)

        val transportB = host.createTransport(registryWith("node-host", "node-stranger", "127.0.0.1", portC))
        val transportC = stranger.createTransport(PeerRegistry("node-stranger"))
        transportB.start()
        transportC.start()
        try {
            val payload = frame("node-stranger", "untrusted")
            deliverRepeatedly(times = 30) { transportC.send(payload, "127.0.0.1", portB) }
            val leak = withTimeoutOrNull(2_000) { transportB.incoming.first() }
            assertTrue(leak == null, "未通过信任手续的入站帧一条都不许进应用层")
            assertFalse(host.isTrustedFingerprint(stranger.fingerprint), "陌生对端不许进信任库")
        } finally {
            transportB.stop()
            transportC.stop()
        }
    }

    @Test
    fun pairingSurvivesRestart() = runBlocking {
        val portA = freeTcpPort()
        val portB = freeTcpPort()
        val storeA = InMemorySecureStateStore()
        val firstA = factory(portA, 6, storeA)
        val b = factory(portB, 7)
        firstA.pairPeer("127.0.0.1", portB, b.publicKey)
        b.pairPeer("127.0.0.1", portA, firstA.publicKey)
        firstA.untrustInboundFingerprint("sha256:never-added") // 不存在的撤销不应破坏状态

        // 模拟进程重启:同一个 seed + 同一个 stateStore ⇒ 同一身份、同一张 pin 表。
        val restartedA = factory(portA, 6, storeA)
        assertTrue(restartedA.isPaired("127.0.0.1", portB), "重启后 pin 必须还在")
        assertEquals(firstA.fingerprint, restartedA.fingerprint, "seed 不变 ⇒ 身份不变")

        val transportA = restartedA.createTransport(registryWith("node-a", "node-b", "127.0.0.1", portB))
        val transportB = b.createTransport(registryWith("node-b", "node-a", "127.0.0.1", portA))
        transportA.start()
        transportB.start()
        try {
            val payload = frame("node-a", "after-restart")
            val atB = async { withTimeout(15_000) { transportB.incoming.first() } }
            sendEventually(transportA, payload, "127.0.0.1", portB)
            assertContentEquals(payload, atB.await().bytes)
        } finally {
            transportA.stop()
            transportB.stop()
        }
    }

    /**
     * 主机的入站配对:手机带着二维码里的 secret + 自己的公钥发配对帧,
     * 主机核对「公钥指纹 == 会话指纹」后落信任,随后的业务帧才放行。
     */
    @Test
    fun phonePairsOverSecureChannelThenFramesFlow() = runBlocking {
        val portHost = freeTcpPort()
        val portPhone = freeTcpPort()
        val host = factory(portHost, 10)
        val phone = factory(portPhone, 11)
        phone.pairPeer("127.0.0.1", portHost, host.publicKey) // 手机扫主机码:信任主机

        val hostTransport = host.createTransport(registryWith("host-node", "phone-node", "127.0.0.1", portPhone))
        val phoneTransport = phone.createTransport(PeerRegistry("phone-node"))
        hostTransport.start()
        phoneTransport.start()
        try {
            val secret = Base64.UrlSafe.decode(host.pairSecretBase64())
            val request = PairingFrame.encodeRequest("phone-node", phone.publicKey, secret, "Phone")

            val accepted = async { withTimeout(10_000) { phoneTransport.pairingResults.first() } }
            sendEventually(phoneTransport, request, "127.0.0.1", portHost)
            assertTrue(accepted.await().accepted, "主机必须接受带正确 secret 且指纹自证的配对")

            awaitTrue(timeoutMs = 5_000) { host.isTrustedFingerprint(phone.fingerprint) }

            val payload = frame("phone-node", "paired-and-talking")
            val atHost = async { withTimeout(20_000) { hostTransport.incoming.first() } }
            sendUntil(phoneTransport, payload, "127.0.0.1", portHost, atHost)
            val received = atHost.await()
            assertContentEquals(payload, received.bytes)
            assertEquals(portPhone, received.port, "配对后的入站帧按 registry 地址归属")
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    @Test
    fun wrongPairSecretIsDenied() = runBlocking {
        val portHost = freeTcpPort()
        val portPhone = freeTcpPort()
        val host = factory(portHost, 12)
        val phone = factory(portPhone, 13)
        phone.pairPeer("127.0.0.1", portHost, host.publicKey)

        val hostTransport = host.createTransport(registryWith("host-node", "phone-node-2", "127.0.0.1", portPhone))
        val phoneTransport = phone.createTransport(PeerRegistry("phone-node-2"))
        hostTransport.start()
        phoneTransport.start()
        try {
            val wrong = ByteArray(16) { 0x42 }
            val request = PairingFrame.encodeRequest("phone-node-2", phone.publicKey, wrong)
            val result = async { timeoutOrNull(10_000) { phoneTransport.pairingResults.first() } }
            deliverRepeatedly(times = 40) { phoneTransport.send(request, "127.0.0.1", portHost) }
            val reply = result.await()
            assertTrue(reply != null && !reply.accepted, "secret 不对必须被拒绝,且要有明说的应答")
            assertFalse(host.isTrustedFingerprint(phone.fingerprint), "被拒绝的对端不许进信任库")
            assertTrue(hostTransport.rejectedInbound >= 1, "拒绝必须计数")
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    @Test
    fun pairFrameClaimingSomeoneElsesKeyIsRejected() = runBlocking {
        val portHost = freeTcpPort()
        val portPhone = freeTcpPort()
        val host = factory(portHost, 14)
        val phone = factory(portPhone, 15)
        val other = factory(0, 16)
        phone.pairPeer("127.0.0.1", portHost, host.publicKey)

        val hostTransport = host.createTransport(registryWith("host-node", "phone-node-3", "127.0.0.1", portPhone))
        val phoneTransport = phone.createTransport(PeerRegistry("phone-node-3"))
        hostTransport.start()
        phoneTransport.start()
        try {
            val secret = Base64.UrlSafe.decode(host.pairSecretBase64())
            // 会话证明的是 phone 的私钥,却声称另一把公钥 ⇒ 指纹绑定必须拦下。
            val forged = PairingFrame.encodeRequest("phone-node-3", other.publicKey, secret)
            deliverRepeatedly(times = 40) { phoneTransport.send(forged, "127.0.0.1", portHost) }
            // 断言是负向的（「没进信任库」）：先等到主机真的处理过一帧再断言，
            // 否则「什么都没发生」也会通过 —— 这类用例最容易假绿的地方就在这里。
            awaitTrue(timeoutMs = 5_000) { hostTransport.rejectedInbound >= 1 }
            assertFalse(host.isTrustedFingerprint(other.fingerprint), "冒名公钥不许进信任库")
            assertFalse(host.isTrustedFingerprint(phone.fingerprint), "会话身份也不该被顺带信任")
            assertTrue(hostTransport.rejectedInbound >= 1)
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    /**
     * 陌生连接会占住 accept 循环(Rust 一次只接一条) ⇒ 必须有时限:
     * 窗口内没完成配对就断开并计数,不能把一个 accept 位无限占着。
     */
    @Test
    fun pendingPairingSessionTimesOut() = runBlocking {
        val portHost = freeTcpPort()
        val portStranger = freeTcpPort()
        val host = factory(portHost, 17)
        val stranger = factory(portStranger, 18)
        stranger.pairPeer("127.0.0.1", portHost, host.publicKey)

        val hostTransport = SecureChannelTransport(
            identitySeed = host.identity.seed,
            peerKey = { _, _ -> null },
            listenPort = portHost,
            isTrustedFingerprint = { false },
            endpointOf = { null },
            pairing = host.pairingAdmission(),
            pendingPairingTimeoutMs = 600,
        )
        val strangerTransport = stranger.createTransport(PeerRegistry("node-stranger"))
        hostTransport.start()
        strangerTransport.start()
        try {
            val garbage = frame("node-stranger", "not-a-pairing-frame")
            deliverRepeatedly(times = 20) { strangerTransport.send(garbage, "127.0.0.1", portHost) }
            awaitTrue(timeoutMs = 8_000) { hostTransport.rejectedInbound >= 1 }
            assertTrue(hostTransport.rejectedInbound >= 1, "超时未配对必须断开并计数")
        } finally {
            hostTransport.stop()
            strangerTransport.stop()
        }
    }

    /** 手机侧:扫过码 ⇒ 记住 secret ⇒ requestPairing 自证 → 主机放行 → 业务帧通。 */
    @Test
    fun scanningPhoneInitiatesPairingThenFramesFlow() = runBlocking {
        val portHost = freeTcpPort()
        val portPhone = freeTcpPort()
        val host = factory(portHost, 20)
        val phone = factory(portPhone, 21)
        phone.pairPeer(
            "127.0.0.1", portHost, host.publicKey,
            secret = Base64.UrlSafe.decode(host.pairSecretBase64()),
        )
        val hostTransport = host.createTransport(registryWith("host-node", "phone-node", "127.0.0.1", portPhone))
        val phoneTransport = phone.createTransport(PeerRegistry("phone-node"))
        hostTransport.start()
        phoneTransport.start()
        try {
            val accepted = async { withTimeout(10_000) { phoneTransport.pairingResults.first() } }
            // 轮询间隔与改动前一致（50ms）：`requestPairing` 有副作用（每次调用都排一帧），
            // 这里要的是「它成功」，不是刷得越快越好 —— 20ms 会让同一轮多排一倍帧。
            awaitTrue(timeoutMs = 10_000, pollMs = 50) {
                phoneTransport.requestPairing("127.0.0.1", portHost, "phone-node")
            }
            assertTrue(accepted.await().accepted, "带正确 secret 的配对帧必须被接受")
            awaitTrue(timeoutMs = 5_000) { host.isTrustedFingerprint(phone.fingerprint) }

            val payload = frame("phone-node", "paired-by-scan")
            val atHost = async { withTimeout(20_000) { hostTransport.incoming.first() } }
            sendUntil(phoneTransport, payload, "127.0.0.1", portHost, atHost)
            assertContentEquals(payload, atHost.await().bytes)
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    /**
     * 回归:扫码后**只发一次**配对帧(不做重试)也必须送达。
     * 真机缺陷(2026-10-26):首次 send 早于握手完成 ⇒ 帧被丢,主机永远不信任本机。
     * 修法是信道把帧存到 "connected" 再补发;本用例用单次调用钉住它。
     */
    @Test
    fun pairingRequestSentBeforeHandshakeStillArrives() = runBlocking {
        val portHost = freeTcpPort()
        val portPhone = freeTcpPort()
        val host = factory(portHost, 30)
        val phone = factory(portPhone, 31)
        phone.pairPeer(
            "127.0.0.1", portHost, host.publicKey,
            secret = Base64.UrlSafe.decode(host.pairSecretBase64()),
        )
        val hostTransport = host.createTransport(registryWith("host-node", "phone-node", "127.0.0.1", portPhone))
        val phoneTransport = phone.createTransport(PeerRegistry("phone-node"))
        hostTransport.start()
        phoneTransport.start()
        try {
            // 只调一次,不看返回值 —— 这正是扫码路径的行为。
            phoneTransport.requestPairing("127.0.0.1", portHost, "phone-node")
            val accepted = withTimeout(10_000) { phoneTransport.pairingResults.first() }
            assertTrue(accepted.accepted, "握手前发出的配对帧也必须在连上后补发并被接受")

            val payload = frame("phone-node", "paired-once")
            val atHost = async { withTimeout(15_000) { hostTransport.incoming.first() } }
            sendUntil(phoneTransport, payload, "127.0.0.1", portHost, atHost)
            assertContentEquals(payload, atHost.await().bytes)
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    @Test
    fun initiatorWithoutSecretCannotPair() = runBlocking {
        val phone = factory(freeTcpPort(), 22)
        val transport = phone.createTransport(PeerRegistry("phone-node"))
        try {
            assertFalse(
                transport.requestPairing("127.0.0.1", 1, "phone-node"),
                "没扫过码就没有 secret,不许发起配对",
            )
        } finally {
            transport.stop()
        }
    }

    /**
     * 反向拨号:主机通过入站配对 (TLPAIR1) 只拿到 nodeId+公钥,没有端点映射。
     * 主机要回消息时必须能靠「registry 端点 → nodeId → TrustStore」找回 pin,
     * 否则真实症状是"手机发得进来、主机发不出去"(真机实测 no-ack → 退回 legacy TCP)。
     */
    @Test
    fun hostCanDialBackPeerThatPairedInbound() = runBlocking {
        val portHost = freeTcpPort()
        val portPhone = freeTcpPort()
        val host = factory(portHost, 40)
        val phone = factory(portPhone, 41)
        phone.pairPeer(
            "127.0.0.1", portHost, host.publicKey,
            secret = Base64.UrlSafe.decode(host.pairSecretBase64()),
        )
        val hostTransport = host.createTransport(registryWith("host-node", "phone-node", "127.0.0.1", portPhone))
        val phoneTransport = phone.createTransport(registryWith("phone-node", "host-node", "127.0.0.1", portHost))
        hostTransport.start()
        phoneTransport.start()
        try {
            withTimeout(10_000) {
                while (!phoneTransport.requestPairing("127.0.0.1", portHost, "phone-node")) delay(100)
            }
            withTimeout(10_000) { phoneTransport.pairingResults.first() }

            val payload = frame("host-node", "dial-back-after-pairing")
            val atPhone = async { withTimeout(15_000) { phoneTransport.incoming.first() } }
            withTimeout(15_000) {
                while (!hostTransport.send(payload, "127.0.0.1", portPhone)) delay(100)
            }
            assertContentEquals(payload, atPhone.await().bytes)
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    /**
     * 文件/投屏的二进制数据帧没有 Envelope,不能靠帧里的 from 归属 ——
     * 必须靠会话指纹反查 (TrustStore → nodeId → registry 端点) 后才能进业务层。
     */
    @Test
    fun binaryChunkFrameFromTrustedPeerCarriesPeerEndpoint() = runBlocking {
        val portHost = freeTcpPort()
        val portPhone = freeTcpPort()
        val host = factory(portHost, 50)
        val phone = factory(portPhone, 51)
        phone.pairPeer(
            "127.0.0.1", portHost, host.publicKey,
            secret = Base64.UrlSafe.decode(host.pairSecretBase64()),
        )
        val hostTransport = host.createTransport(registryWith("host-node", "phone-node", "127.0.0.1", portPhone))
        val phoneTransport = phone.createTransport(registryWith("phone-node", "host-node", "127.0.0.1", portHost))
        hostTransport.start()
        phoneTransport.start()
        try {
            withTimeout(10_000) {
                while (!phoneTransport.requestPairing("127.0.0.1", portHost, "phone-node")) delay(100)
            }
            withTimeout(10_000) { phoneTransport.pairingResults.first() }

            val chunk = FileChunkFrame.encode("tf-1", 0, ByteArray(256) { it.toByte() })
            // 全量并发跑时握手会慢一些:收端等待给足 30s,发送端"重发到真收到"而不是只看一次返回值。
            val atHost = async { withTimeout(30_000) { hostTransport.incoming.first() } }
            withTimeout(30_000) {
                while (!atHost.isCompleted) {
                    phoneTransport.send(chunk, "127.0.0.1", portHost)
                    delay(50)
                }
            }
            val received = atHost.await()
            assertContentEquals(chunk, received.bytes)
            assertEquals("127.0.0.1", received.host)
            assertEquals(portPhone, received.port, "二进制帧必须带对端 registry 地址")
            assertEquals("tf-1", FileChunkFrame.decode(received.bytes)?.transferId)
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    @Test
    fun binaryChunkFrameFromTrustedPeerWithoutRegistryEndpointIsDropped() = runBlocking {
        val portHost = freeTcpPort()
        val portPhone = freeTcpPort()
        val host = factory(portHost, 52)
        val phone = factory(portPhone, 53)
        phone.pairPeer(
            "127.0.0.1", portHost, host.publicKey,
            secret = Base64.UrlSafe.decode(host.pairSecretBase64()),
        )
        // 主机 registry **故意不认识**手机(发现还没到)。
        val hostTransport = host.createTransport(PeerRegistry("host-node"))
        val phoneTransport = phone.createTransport(PeerRegistry("phone-node"))
        hostTransport.start()
        phoneTransport.start()
        try {
            withTimeout(10_000) {
                while (!phoneTransport.requestPairing("127.0.0.1", portHost, "phone-node")) delay(100)
            }
            withTimeout(10_000) { phoneTransport.pairingResults.first() }

            val chunk = FileChunkFrame.encode("tf-2", 0, ByteArray(64))
            repeat(10) {
                phoneTransport.send(chunk, "127.0.0.1", portHost)
                delay(50)
            }
            val leak = withTimeoutOrNull(1_500) { hostTransport.incoming.first() }
            assertTrue(leak == null, "查不到对端地址时不许把二进制帧交上去")
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    @Test
    fun inboundFingerprintTrustIsPersisted() {
        val store = InMemorySecureStateStore()
        val first = factory(0, 8, store)
        first.trustInboundFingerprint("sha256:trusted-phone")
        assertTrue(first.isTrustedFingerprint("sha256:trusted-phone"))
        assertFalse(first.isTrustedFingerprint("sha256:someone-else"))

        val restarted = factory(0, 8, store)
        assertTrue(restarted.isTrustedFingerprint("sha256:trusted-phone"), "只按指纹的信任也要落盘")
        assertNotNull(restarted.entries())
    }
}
