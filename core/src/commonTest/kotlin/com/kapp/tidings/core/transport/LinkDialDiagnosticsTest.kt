// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.protocol.LinkFrame
import com.kapp.tidings.core.protocol.LinkFrames
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.TcpFrame
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.kapp.tidings.core.testutil.awaitTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        // 「没拨」也要有确定的证据：等**诊断里出现 skipReason**（说明这一轮真的评估过它），
        // 而不是 sleep 200ms 之后假设评估已经发生。
        awaitTrue(what = "等不到 peer 的 skipReason：调度循环没评估过它，这条负控无法成立") {
            client.dialStats()["peer"]?.skipReason != null
        }
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
        awaitTrue(what = "同 /64 的 GUA 没被拨，且真公网也没有 skipReason —— 这一轮没评估完") {
            (client.dialStats()["peer"]?.attempts ?: 0) >= 1 &&
                client.dialStats()["other"]?.skipReason != null
        }
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
        awaitTrue(what = "等不到一次失败拨号：有 pin 就该真的拨一次") {
            (client.dialStats()["peer"]?.failures ?: 0) > 0
        }
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
        awaitTrue(what = "等不到 peer-has-no-lnk1 这条跳过原因：诊断没把它记下来") {
            client.dialStats()["peer"]?.skipReason == "peer-has-no-lnk1"
        }
        client.stop()
        scope.cancel()

        val stats = client.dialStats()["peer"]
        assertEquals("peer-has-no-lnk1", stats?.skipReason, "task-31 那类静默必须能从诊断里一眼看出")
    }

    /** 先失败一次、再成功；成功之后连接立刻可用（读一帧 Ok 就交给 pump）。 */
    private class FlakyTransport(override val isSecure: Boolean) : LinkTransport {
        @Volatile
        var succeed = false
        override val lastError: String? get() = "tls-handshake-failed"
        override suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): LinkConnection? =
            if (succeed) FakeConnection(host, port) else null
        override suspend fun accept(
            socket: Socket,
            read: ByteReadChannel,
            write: ByteWriteChannel,
            remoteHost: String,
            remotePort: Int,
            expectedPin: TlsPin?,
        ): LinkConnection? = null
    }

    private class FakeConnection(override val remoteHost: String, override val remotePort: Int) : LinkConnection {
        override val isSecure: Boolean get() = true
        private var reads = 0
        override suspend fun send(frame: ByteArray) = Unit
        override suspend fun receiveFrame(): ByteArray? {
            reads++
            // 第一帧 = 握手应答 Ok；之后返回 null = 连接结束，让 pump 正常退出。
            //
            // 注意剥掉长度前缀：`receiveFrame` 的契约是**帧体**（前缀由通道层吃掉），
            // 直接把 `LinkFrames.encode` 的整帧丢回去会被解成坏帧 ⇒ 变成 hello-rejected。
            if (reads > 1) return null
            val framed = LinkFrames.encode(LinkFrame.Ok)
            val len = TcpFrame.decodeLength(framed)
            return framed.copyOfRange(TcpFrame.LENGTH_PREFIX_BYTES, TcpFrame.LENGTH_PREFIX_BYTES + len)
        }
        override fun close() = Unit
    }

    /**
     * 建连成功必须**清掉上一次的失败原因**。
     *
     * 真机形态（换网重连之后）：`linkConnected=true` 与 `lastError=connect-timeout`
     * **同时**出现 —— 诊断自相矛盾，看的人只会以为链路还在坏。
     * 历史并不因此丢掉：失败**次数**留在 `dialFailures` 里，那才是累计量。
     */
    @Test
    fun aSuccessfulDialClearsThePreviousFailureReason() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("self")
        registry.touch("peer", publicAddress, 17878, profile("peer"), 1L, confirmed = true)
        registry.setPin("peer", "sha256:" + "a".repeat(64))
        val transport = FlakyTransport(isSecure = true)
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
        // ① 先失败一次（否则后面「变 null」什么也证明不了）。
        withTimeout(5_000) {
            while ((client.dialStats()["peer"]?.failures ?: 0) == 0) delay(10)
        }
        // 停掉 tick：每 20ms 一次的失败会把退避推到 60s 上限，
        // 那样「让它连上」就得等一分钟 —— 不是这条测试要证明的东西。
        client.stop()
        assertEquals("tls-handshake-failed", client.dialStats()["peer"]?.lastError, "失败原因必须先出现")
        // ② 让它连上：`redialNow` 会清掉退避并立刻拨一次，失败原因必须随之消失。
        transport.succeed = true
        client.redialNow("peer")
        assertNull(client.dialStats()["peer"]?.lastError, "建连成功必须把上一次的失败原因清掉")
        scope.cancel()
    }
}
