// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.crypto.TlsPin
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.testutil.awaitTrue
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 长连接的私网拨号目标：**luciola 监听端不能拨 17879**。
 *
 * 信道模式下主机有意不建 17879 明文监听（`legacyTcpListener=false`），档案里的
 * tcpPort 只是协议必填位的占位；真机读数里手机连续 85 次拨它全 connect-timeout，
 * 同时主机 17880 的 TLS 接受面在 LISTEN、配对码里的 pin 也在。旧裁决「私网⇒明文」
 * 的前提是那个端口真的在听，前提没了就该改判据，而不是继续拨一个不存在的端口。
 *
 * 只用对端档案里已有的 `luciolaPort` 判形态：老端（没有这个键）行为一字不变。
 */
class LuciolaLanLinkTargetTest {

    private data class Call(val tag: String, val host: String, val port: Int, val pin: String?)

    /**
     * 记录拨号尝试（替代原来的 `synchronized(calls)`）。
     *
     * `synchronized` 是 JVM 专有的（`java.lang` 的静态方法，JVM 上自动可见），commonTest
     * 用了它 ⇒ **iOS 测试目标根本编译不过**（只是没人编过）。这里改用 `Mutex`：
     * 假传输由 `LinkClient` 的协程在 `Dispatchers.Default` 上调用，加锁语义保持不变。
     */
    private class CallLog {
        private val mutex = Mutex()
        private val items = mutableListOf<Call>()
        suspend fun record(call: Call) {
            mutex.withLock { items += call }
        }

        suspend fun isEmpty(): Boolean = mutex.withLock { items.isEmpty() }

        suspend fun snapshot(): List<Call> = mutex.withLock { items.toList() }
    }

    private class RecordingTransport(
        override val isSecure: Boolean,
        private val tag: String,
        private val calls: CallLog,
    ) : LinkTransport {
        override val lastError: String? get() = "connect-failed"

        override suspend fun connect(
            host: String,
            port: Int,
            expectedPin: TlsPin?,
            timeoutMs: Long,
        ): LinkConnection? {
            calls.record(Call(tag, host, port, expectedPin?.wire))
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

    private fun profile(
        nodeId: String,
        luciolaPort: Int? = null,
        tlsPort: Int? = 17880,
    ) = Profile(
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
        luciolaPort = luciolaPort,
    )

    private fun evaluate(
        peer: Profile,
        host: String = LAN_HOST,
        pin: String? = PIN,
        secure: Boolean = true,
        check: (List<Call>, LinkDialStats?) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = CallLog()
        val registry = PeerRegistry("self")
        registry.touch(peer.nodeId, host, 17878, peer, 1L, confirmed = true)
        if (pin != null) registry.setPin(peer.nodeId, pin)
        val client = LinkClient(
            registry = registry,
            transport = RecordingTransport(secure, "tls", calls),
            plaintextTransport = RecordingTransport(false, "plain", calls),
            scope = scope,
            selfNodeId = { "self" },
            enabled = { true },
            localAddresses = { listOf(LOCAL_HOST) },
            tickMs = 20,
            connectTimeoutMs = 50,
        )
        client.start()
        try {
            awaitTrue("link client did not evaluate the peer") {
                client.dialStats()[peer.nodeId]?.skipReason != null || !calls.isEmpty()
            }
        } finally {
            client.stop()
            scope.cancel()
        }
        val snapshot = calls.snapshot()
        check(snapshot, client.dialStats()[peer.nodeId])
    }

    /** 老端：档案没有 luciolaPort，私网仍拨 17879 明文。 */
    @Test
    fun legacyLanPeerKeepsThePlaintextTarget() = evaluate(profile("legacy")) { calls, _ ->
        assertTrue(calls.any { it.tag == "plain" && it.port == 17879 }, "legacy LAN must still dial plaintext 17879: $calls")
        assertTrue(calls.none { it.tag == "tls" }, "legacy LAN must not switch to TLS: $calls")
    }

    /** 核心判据：luciola 监听端在私网改走已存在的 TLS 入口，不再拨被关闭的明文端口。 */
    @Test
    fun luciolaLanPeerDialsTlsInsteadOfTheClosedPlaintextPort() =
        evaluate(profile("luciola", luciolaPort = 17878)) { calls, _ ->
            val tls = calls.filter { it.tag == "tls" }
            assertTrue(tls.isNotEmpty(), "luciola LAN peer must dial the TLS listener: $calls")
            assertTrue(tls.all { it.port == 17880 }, "TLS target must be tlsPort 17880: $calls")
            assertEquals(PIN, tls.first().pin, "TLS target must carry the pairing pin")
            assertTrue(calls.none { it.port == 17879 }, "no dial may target the closed 17879 port: $calls")
            assertTrue(calls.none { it.tag == "plain" }, "no plaintext dial for a luciola peer: $calls")
        }

    /** 没 pin 就一个目标都不拨（fail-closed），并如实说明原因。 */
    @Test
    fun luciolaLanPeerWithoutPinDoesNotDial() =
        evaluate(profile("no-pin", luciolaPort = 17878), pin = null) { calls, stats ->
            assertTrue(calls.isEmpty(), "no pin means no dial: $calls")
            assertEquals("no-pin", stats?.skipReason, "diagnostics must say why nothing was dialed")
        }

    /** 没 tlsPort 同样不拨，不能悄悄退回明文。 */
    @Test
    fun luciolaLanPeerWithoutTlsPortDoesNotDial() =
        evaluate(profile("no-tls", luciolaPort = 17878, tlsPort = null)) { calls, stats ->
            assertTrue(calls.isEmpty(), "no TLS listener means no dial: $calls")
            assertEquals("peer-has-no-tls-port", stats?.skipReason, "diagnostics must name the missing port")
        }

    /** 公网 luciola 对端行为不变：仍走 TLS + pin。 */
    @Test
    fun publicLuciolaPeerStillDialsTls() =
        evaluate(profile("public", luciolaPort = 17878), host = PUBLIC_HOST) { calls, _ ->
            assertTrue(calls.any { it.tag == "tls" && it.port == 17880 && it.pin == PIN }, "public target unchanged: $calls")
            assertTrue(calls.none { it.tag == "plain" }, "public must never fall back to plaintext: $calls")
        }

    /** 本端没有 TLS 连接层时说清「私网 luciola 需要 TLS」，而不是含糊的 no-target。 */
    @Test
    fun luciolaLanPeerOnPlaintextOnlyTransportSaysWhy() =
        evaluate(profile("plain-only", luciolaPort = 17878), secure = false) { calls, stats ->
            assertTrue(calls.isEmpty(), "plaintext-only transport must not dial: $calls")
            assertEquals("lan-luciola-needs-tls", stats?.skipReason, "skip reason must be recorded")
        }

    private companion object {
        const val LOCAL_HOST = "192.168.2.82"
        const val LAN_HOST = "192.168.2.22"
        const val PUBLIC_HOST = "2001:db8:2020:8494:d46b:34ff:fe9c:069b"
        val PIN = "sha256:" + "a".repeat(64)
    }
}
