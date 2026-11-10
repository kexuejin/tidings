// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.Timings
import com.kapp.tenon.core.protocol.makeEnvelope
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
 * 安全信道下「可送达」= 此刻有已连上的会话：`send` 对未连上的端点一律返回 false，
 * 那是连接事实、不是传输泵坏了。把这类端点算进 targets 时，一台留着若干历史配对
 * 身份的节点每两轮就 restart 一次传输面，把已经连入的会话全断掉。反之，把自愈整个
 * 关掉也是错的：真停摆仍必须重建。两个方向各钉一条行为判据。
 */
class PresenceHeartbeatTargetsTest {

    private class FakeTransport(
        private val reachable: Boolean,
        private val sendable: Boolean,
        /** 手动地址的「值得拨一次」判据（默认与 [reachable] 同义）。 */
        private val dialable: Boolean = reachable,
    ) : DatagramTransport {
        // replay = 1：允许在 `runtime.start()` **之前**塞入站帧（订阅者一上线就收到），
        // 不用"定时重发 + 赌同步"去凑那个登记窗口。
        private val _incoming = MutableSharedFlow<ReceivedDatagram>(replay = 1, extraBufferCapacity = 16)
        override val incoming: SharedFlow<ReceivedDatagram> = _incoming
        override val boundPort: Int get() = 17878

        @Volatile var restarts = 0
        @Volatile var reachChecks = 0
        @Volatile var dialableChecks = 0
        @Volatile var sends = 0

        override suspend fun start() = Unit
        override fun stop() = Unit
        override suspend fun restart() { restarts++ }
        override fun canReach(host: String, port: Int): Boolean {
            reachChecks++
            return reachable
        }
        override fun dialable(host: String, port: Int): Boolean {
            dialableChecks++
            return dialable
        }
        override suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean {
            sends++
            return sendable
        }

        /** 造一条入站帧（等价对端真的发过来了）：用真实编码，别手搓 JSON。 */
        suspend fun deliver(bytes: ByteArray, host: String, port: Int) {
            _incoming.emit(ReceivedDatagram(bytes, host, port))
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

    /** 只剩「连拨都不该拨」的端点：心跳轮次照跑，但不许把传输面判成停摆。 */
    @Test
    fun unpairedEndpointsDoNotTriggerTransportRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport(reachable = false, sendable = false, dialable = false)
        val host = runtimeWith(transport)
        host.start(scope)
        try {
            await("heartbeat rounds never evaluated the endpoint") { transport.dialableChecks >= 4 }
            // 再多等两轮，覆盖自愈所需的连续判据轮数。
            val seen = transport.dialableChecks
            await("heartbeat loop stalled") { transport.dialableChecks >= seen + 2 }
            assertEquals(0, transport.restarts, "unpaired endpoints must not rebuild the transport")
            assertEquals(0, transport.sends, "unpaired endpoints must not even be dialed")
        } finally {
            host.stop()
            scope.cancel()
        }
    }

    /**
     * 手动地址：**照拨**，但不许把它算成停摆证据。
     *
     * 用户把 host:port 填进设置，就是要求「哪怕现在没有会话也试一次」—— 所以在
     * `canReach == false`、`send` 也失败时，心跳仍必须真的去拨（`dialable` 为真）。
     * 但这类地址长期不通报的是「那个地址不通」，不是本机泵坏了；一旦计入 targets，
     * 一台留着旧地址的节点就会每两轮重启一次传输面。
     */
    @Test
    fun manualAddressesAreDialedButNeverCountAsAStall() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport(reachable = false, sendable = false, dialable = true)
        val host = runtimeWith(transport)
        host.start(scope)
        try {
            await("the manual address was never dialed") { transport.sends >= 2 }
            // 必须跨过**自愈所需的连续轮数**再看：只等到「拨过两次」时，判据正处在第二轮
            // 边界上，重启可能还没落地 —— 那样反向探针会假绿（实测踩过一次）。
            val seen = transport.sends
            await("heartbeat loop stalled") { transport.sends >= seen + 3 }
            assertEquals(0, transport.restarts, "手动地址拨不通不许重建传输面")
        } finally {
            host.stop()
            scope.cancel()
        }
    }

    /**
     * 对照：有**已连上的会话**却一个包都发不出去，自愈必须仍然发生。
     *
     * 目标必须是 registry 里有会话的对端，不能拿手动地址凑数 —— 手动地址按设计不进
     * targets（见上一条），拿它做对照会测出「自愈被关掉了」的假红。
     */
    @Test
    fun aRealStallStillRestartsTheTransport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport(reachable = true, sendable = false, dialable = false)
        val host = runtimeWith(transport)
        // 先把一条入站 entry 放进 replay 缓冲：`start()` 之后 PresenceIngest 一订阅就收到，
        // registry 里因此出现一个「有会话」的对端，心跳才会把它算成 targets。
        val peer = profile("node-b")
        transport.deliver(
            Codec.encodeToBytes(makeEnvelope(MsgTypes.ENTRY, peer.nodeId, Codec.profilePayload(peer))),
            "192.0.2.7",
            17878,
        )
        host.start(scope)
        try {
            await("the peer never entered the registry") { transport.reachChecks > 0 }
            await("a stalled transport was never rebuilt") { transport.restarts >= 1 }
            assertTrue(transport.sends > 0, "the heartbeat must really have tried to send")
        } finally {
            host.stop()
            scope.cancel()
        }
    }

    /**
     * 安全信道的「可送达」= **有已连上的会话**，不是「配对库里有 pin」。
     *
     * 这一条是 A31 复验的直接判据：真机上 `targets=2 sent=0` 恒定不变，那 2 个目标
     * 全是「配过对、对端却不监听」的历史端点。只要 pin 还能算成可送达，自愈就会每
     * 两轮重建一次传输面（把连着的会话全断掉），而问题根本不在传输面。
     */
    @Test
    fun secureChannelCountsOnlyLiveSessionsNotPairingPins() {
        val transport = SecureChannelTransport(
            identitySeed = ByteArray(32),
            peerKey = { host, port ->
                if (host == "192.0.2.1" && port == 17878) ByteArray(32) else null
            },
        )
        assertFalse(
            transport.canReach("192.0.2.1", 17878),
            "配对库有 pin 只说明「可以拨」：此刻没有会话，一帧也交不出去",
        )
        assertFalse(transport.canReach("192.0.2.9", 52213), "未配对端点不是目标")
        assertFalse(transport.canReach("192.0.2.1", 52213), "同一主机的另一个端口是另一个端点")
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
