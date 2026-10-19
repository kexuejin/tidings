// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task-32 ③ 的只读拨号诊断：必须能区分**没拨**（策略跳过）与**拨了连不上**。
 *
 * 真机上 `linkConnected=false` 只有这两种成因，而它们的修法完全不同（改策略 vs 查网络/TLS），
 * 所以这两种情况各钉一条断言。
 */
class LinkDialDiagnosticsTest {

    private class FailingTransport(override val isSecure: Boolean) : LinkTransport {
        var error: String? = "tls-handshake-failed"
        override val lastError: String? get() = error
        override suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): LinkConnection? = null
        override suspend fun accept(
            socket: Socket,
            read: ByteReadChannel,
            write: ByteWriteChannel,
            remoteHost: String,
            remotePort: Int,
            expectedPin: TlsPin?,
        ): LinkConnection? = null
    }

    private fun profile(nodeId: String, tlsPort: Int? = 17880, caps: List<String> = listOf(MsgTypes.CAP_LINK)) = Profile(
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
        caps = caps,
    )

    private val publicAddress = "2001:db8:2020:8494:d46b:34ff:fe9c:069b"

    @Test
    fun aPeerWeNeverDialReportsWhyInsteadOfJustZero() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        registry.touch("peer", publicAddress, 17878, profile("peer"), 1L, confirmed = true)
        val transport = FailingTransport(isSecure = true)
        val client = LinkClient(
            registry = registry,
            transport = transport,
            plaintextTransport = transport,
            scope = scope,
            selfNodeId = { "self" },
            enabled = { true },
            tickMs = 20,
            connectTimeoutMs = 50,
        )
        client.start()
        delay(200)
        client.stop()
        scope.cancel()

        val stats = client.dialStats()["peer"]
        assertEquals(0, stats?.attempts ?: -1, "公网 + 没 pin：一个拨号都不该发")
        assertEquals("no-pin", stats?.skipReason, "必须说清是**没拨**，而不是让调用方猜")
    }

    /**
     * task-53：**同 /64 的 GUA 算局域网** ⇒ 明文目标必须拨；**真公网**（不同 /64）⇒ 仍拒。
     *
     * 中国宽带的家庭 LAN 常是 GUA-/64（`2001:db8:2020:cb44::/64`）。只认 `isPrivateOrLocal` 时，
     * 这类地址会被当成公网 ⇒ K1 一个目标都不拨 = **K1 被静默关掉**（审查发现的三处判据不一致之一）。
     */
    @Test
    fun aSameLanGuaPeerGetsAPlaintextTargetWhileAForeignPublicAddressDoesNot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lanGua = "2001:db8:2020:cb44:31f4:ca9a:d4e2:a54a"
        val foreignGua = "2001:db8:2020:8494:d46b:34ff:fe9c:069b"
        val registry = PeerRegistry("self")
        registry.touch("peer", lanGua, 17878, profile("peer", tlsPort = null), 1L, confirmed = true)
        registry.touch("other", foreignGua, 17878, profile("other", tlsPort = null), 1L, confirmed = true)
        val transport = FailingTransport(isSecure = false)
        val client = LinkClient(
            registry = registry,
            transport = transport,
            plaintextTransport = transport,
            scope = scope,
            selfNodeId = { "self" },
            enabled = { true },
            // 本机在同一 /64 里 ⇒ 对端算局域网（真机就是这个形状）。
            localAddresses = { listOf("2001:db8:2020:cb44:1400:fdbb:f0d0:7679", "192.168.2.82") },
            tickMs = 20,
            connectTimeoutMs = 50,
        )
        client.start()
        delay(200)
        client.stop()
        scope.cancel()

        val lanStats = client.dialStats()["peer"]
        assertTrue(
            (lanStats?.attempts ?: 0) >= 1,
            "同 /64 的 GUA 必须算局域网 ⇒ 必须拨（K1 不许被静默关掉）：$lanStats",
        )
        val foreignStats = client.dialStats()["other"]
        assertEquals(0, foreignStats?.attempts ?: -1, "真公网 + 明文：一个拨号都不许发")
        assertEquals(
            "public-address-needs-tls",
            foreignStats?.skipReason,
            "真公网必须如实说是\"公网地址需要 TLS\"",
        )
    }

    @Test
    fun aFailedDialReportsAttemptsFailuresAndTheReason() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        registry.touch("peer", publicAddress, 17878, profile("peer"), 1L, confirmed = true)
        registry.setPin("peer", "sha256:" + "a".repeat(64))
        val transport = FailingTransport(isSecure = true)
        val client = LinkClient(
            registry = registry,
            transport = transport,
            plaintextTransport = transport,
            scope = scope,
            selfNodeId = { "self" },
            enabled = { true },
            tickMs = 20,
            connectTimeoutMs = 50,
        )
        client.start()
        delay(300)
        client.stop()
        scope.cancel()

        val stats = client.dialStats()["peer"]
        assertTrue((stats?.attempts ?: 0) > 0, "有 pin 就该真的拨")
        assertTrue((stats?.failures ?: 0) > 0, "拨了失败必须计数")
        assertEquals("tls-handshake-failed", stats?.lastError, "失败原因必须带上来")
        assertEquals(publicAddress + ":17880", stats?.lastTarget, "最后拨的 host:port 必须带上来")
        assertEquals(null, stats?.skipReason, "拨过之后不该再报 skipReason")
    }

    @Test
    fun aPeerThatDoesNotAdvertiseTheCapabilitySaysSo() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        registry.touch("peer", publicAddress, 17878, profile("peer", caps = emptyList()), 1L, confirmed = true)
        val transport = FailingTransport(isSecure = true)
        val client = LinkClient(
            registry = registry,
            transport = transport,
            plaintextTransport = transport,
            scope = scope,
            selfNodeId = { "self" },
            enabled = { true },
            tickMs = 20,
            connectTimeoutMs = 50,
        )
        client.start()
        delay(200)
        client.stop()
        scope.cancel()

        val stats = client.dialStats()["peer"]
        assertEquals("peer-has-no-lnk1", stats?.skipReason, "task-31 那类静默必须能从诊断里一眼看出")
    }
}
