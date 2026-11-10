// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.Timings
import com.kapp.tenon.core.session.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 心跳的停摆判据只数**可送达**目标。
 *
 * 安全信道对未配对端点 deny-by-default，`send` 返回 false 是准入事实、不是传输泵坏了。
 * 把这些历史地址算进 targets 时，一台只剩离线旧身份的节点每两轮就 restart 一次传输面，
 * 把已经连入的会话全断掉。反之，把自愈整个关掉也是错的：真停摆仍必须重建。
 * 两个方向各钉一条行为判据。
 */
class PresenceHeartbeatTargetsTest {

    private class FakeTransport(
        private val reachable: Boolean,
        private val sendable: Boolean,
    ) : DatagramTransport {
        private val _incoming = MutableSharedFlow<ReceivedDatagram>(extraBufferCapacity = 16)
        override val incoming: SharedFlow<ReceivedDatagram> = _incoming
        override val boundPort: Int get() = 17878

        @Volatile var restarts = 0
        @Volatile var reachChecks = 0
        @Volatile var sends = 0

        override suspend fun start() = Unit
        override fun stop() = Unit
        override suspend fun restart() { restarts++ }
        override fun canReach(host: String, port: Int): Boolean {
            reachChecks++
            return reachable
        }
        override suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean {
            sends++
            return sendable
        }
    }

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_MOBILE_TEXT),
    )

    private suspend fun await(what: String, condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }

    private fun runtimeWith(transport: FakeTransport) = NodeRuntime(
        messengerTransportFactory = { _ -> transport },
        profile = profile("node-a"),
        udpPort = 0,
        bindHost = "127.0.0.1",
        tcpPort = 0,
        manualPeers = listOf(ManualPeer("127.0.0.1", 17878)),
        timings = Timings(presenceInterval = 50, sweepInterval = 60_000),
    )

    /** 只剩未配对端点：心跳轮次照跑，但不许把传输面判成停摆。 */
    @Test
    fun unpairedEndpointsDoNotTriggerTransportRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport(reachable = false, sendable = false)
        val host = runtimeWith(transport)
        host.start(scope)
        try {
            await("heartbeat rounds never evaluated the endpoint") { transport.reachChecks >= 4 }
            // 再多等两轮，覆盖自愈所需的连续判据轮数。
            val seen = transport.reachChecks
            await("heartbeat loop stalled") { transport.reachChecks >= seen + 2 }
            assertEquals(0, transport.restarts, "unpaired endpoints must not rebuild the transport")
            assertEquals(0, transport.sends, "unpaired endpoints must not even be dialed")
        } finally {
            host.stop()
            scope.cancel()
        }
    }

    /** 对照：有可送达目标却一个包都发不出去，自愈必须仍然发生。 */
    @Test
    fun aRealStallStillRestartsTheTransport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport(reachable = true, sendable = false)
        val host = runtimeWith(transport)
        host.start(scope)
        try {
            await("a stalled transport was never rebuilt") { transport.restarts >= 1 }
            assertTrue(transport.sends > 0, "the heartbeat must really have tried to send")
        } finally {
            host.stop()
            scope.cancel()
        }
    }

    /** 安全信道的准入判据：只认配对过的端点，不按端口数字猜。 */
    @Test
    fun secureChannelMarksOnlyPinnedEndpointsReachable() {
        val transport = SecureChannelTransport(
            identitySeed = ByteArray(32),
            peerKey = { host, port ->
                if (host == "192.0.2.1" && port == 17878) ByteArray(32) else null
            },
        )
        assertTrue(transport.canReach("192.0.2.1", 17878), "pinned endpoint must count as reachable")
        assertFalse(transport.canReach("192.0.2.9", 52213), "unpaired ephemeral port must not")
        assertFalse(transport.canReach("192.0.2.1", 52213), "same host on another port is a different endpoint")
    }

    /** legacy 形态没有准入概念：默认实现必须把所有地址都算目标，自愈语义不变。 */
    @Test
    fun transportsWithoutAdmissionStillCountEveryEndpoint() {
        val legacy = object : DatagramTransport {
            override val incoming: SharedFlow<ReceivedDatagram> = MutableSharedFlow()
            override val boundPort: Int get() = 17878
            override suspend fun start() = Unit
            override fun stop() = Unit
            override suspend fun restart() = Unit
            override suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean = true
        }
        assertTrue(legacy.canReach("192.0.2.9", 52213))
    }
}
