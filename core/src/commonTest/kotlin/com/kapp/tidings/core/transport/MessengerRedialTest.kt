// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.Timings
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **链路半死时重拨 17880，而不是回退 17879**（task-64）。
 *
 * 真机判据（都已实测）：蜂窝侧 **17880（TLS 长连接）可达**、**17879（明文 TCP 回退）不可达**
 * （最可能是路由器上针对 17879 的规则把流量引到了别的设备）。于是「链路半死」时唯一可能成功的
 * 路是**重拨一条新长连接** —— 而不是回退那条死路。
 *
 * 三条要钉住的边界：
 *  1. 公网对端 + 冷却期 ⇒ 重拨（**不回退 17879**）；
 *  2. **每次冷却窗口最多重拨一次**（别把「半死」变成重拨风暴）；重拨成功后冷却要清掉
 *     （否则会把一条**好的**链路继续挡在外面）；
 *  3. **局域网对端保留 17879 回退**（那里它是通的）—— 不把 17879 硬编码成禁用。
 */
class MessengerRedialTest {

    /** 半死链路：前 [failFirst] 次 send 失败，之后成功。 */
    private class FakeLink(private val failFirst: Int) : LinkDownlink {
        var sends = 0
        override fun isConnected(peerId: String): Boolean = true
        override suspend fun send(peerId: String, envelope: Envelope): Boolean {
            sends++
            return sends > failFirst
        }
    }

    private class FakeTcp(private val succeed: Boolean) : TcpFallback {
        var calls = 0
        override suspend fun sendAndAwaitAck(host: String, port: Int, envelope: Envelope): Boolean {
            calls++
            return succeed
        }
    }

    private fun profile(id: String): Profile = Profile.mobile(nodeId = id, nick = "对端", host = "phone", ver = "0.61.1")

    private suspend fun newMessenger(
        link: FakeLink,
        tcp: FakeTcp,
        public: Boolean,
        registry: PeerRegistry,
        preferTransport: Boolean = false,
    ): Pair<Messenger, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = InMemoryQueueStore(),
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(30L), tcpAckTimeout = 30L),
            tcpFallback = tcp,
            preferTransport = preferTransport,
        )
        transport.start()
        messenger.start(scope)
        messenger.linkDownlink = link
        messenger.peerIsPublic = { public }
        return messenger to scope
    }

    /**
     * luciola 模式（preferTransport）必须**不先走 legacy K1**：
     * 真机归因发现过"发现换成 luciola、消息仍走 LINK"。这里用一个"如果被调用就成功"的
     * 假链路钉住：开启后链路调用次数必须为 0（传输面失败则入队）。
     */
    @Test
    fun preferTransportSkipsLegacyLink() = runBlocking {
        val link = FakeLink(failFirst = 0)
        val registry = PeerRegistry("self-node")
        val (messenger, scope) = newMessenger(
            link = link, tcp = FakeTcp(false), public = true, registry = registry, preferTransport = true,
        )
        try {
            messenger.peerIsPublic = { true }
            val envelope = makeEnvelope(MsgTypes.MSG, "self-node", Codec.textPayload("hi"))
            val outcome = messenger.sendUserMessage("unknown-peer", envelope)
            assertEquals(com.kapp.tidings.core.transport.SendOutcome.QUEUED, outcome, "未知对端无法 ACK,应入队")
            assertEquals(0, link.sends, "luciola 模式下不许先走 legacy 链路")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun legacyModeStillUsesLinkFirst() = runBlocking {
        val link = FakeLink(failFirst = 0)
        val registry = PeerRegistry("self-node")
        val (messenger, scope) = newMessenger(
            link = link, tcp = FakeTcp(false), public = true, registry = registry, preferTransport = false,
        )
        try {
            messenger.peerIsPublic = { true }
            val envelope = makeEnvelope(MsgTypes.MSG, "self-node", Codec.textPayload("hi"))
            val outcome = messenger.sendUserMessage("unknown-peer", envelope)
            assertEquals(com.kapp.tidings.core.transport.SendOutcome.SENT, outcome)
            assertEquals(1, link.sends, "legacy 模式仍是链路优先(对照组)")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aHalfDeadLinkOnAPublicPeerIsRedialledInsteadOfFallingBackTo17879() = runBlocking {
        val link = FakeLink(failFirst = 1)
        val tcp = FakeTcp(succeed = false)
        val registry = PeerRegistry("self-node")
        val (messenger, scope) = newMessenger(link, tcp, public = true, registry = registry)
        try {
            var redials = 0
            messenger.linkRedial = { _ ->
                redials++
                true
            }
            registry.touch(
                "peer-1",
                "2001:db8:2020:cb44::1",
                17999,
                profile("peer-1"),
                profileTs = 1000L,
                confirmed = true,
            )

            val first = messenger.sendUserMessage(
                "peer-1",
                makeEnvelope(from = "self-node", type = MsgTypes.MSG, payload = Codec.textPayload("hi")),
            )
            assertEquals(SendOutcome.SENT, first, "重拨成功后应当送出去")
            val diag = messenger.lastReliableSend()
            assertEquals("sent-via-redial", diag?.outcome, "诊断必须写明这次是重拨链路")
            assertEquals(1, redials, "公网对端在半死时应当重拨一次")
            assertEquals(0, tcp.calls, "重拨这条路**不该**再回退 17879（蜂窝上它是死的）")
            assertTrue(diag?.redialTried == true)
            assertTrue((diag?.redialMs ?: -1L) >= 0L, "要记下重拨耗时")

            // 重拨成功 ⇒ 冷却清掉 ⇒ 下一条直接走链路，且**不再**重拨。
            val second = messenger.sendUserMessage(
                "peer-1",
                makeEnvelope(from = "self-node", type = MsgTypes.MSG, payload = Codec.textPayload("again")),
            )
            assertEquals(SendOutcome.SENT, second)
            assertEquals("sent-via-link", messenger.lastReliableSend()?.outcome, "冷却必须被清掉，否则好链路也被挡")
            assertEquals(1, redials, "每次冷却窗口最多重拨一次")
            assertEquals(0, tcp.calls)
        } finally {
            messenger.stop()
            scope.cancel()
        }
    }

    @Test
    fun aLanPeerKeepsThe17879Fallback() = runBlocking {
        val link = FakeLink(failFirst = 1)
        val tcp = FakeTcp(succeed = true)
        val registry = PeerRegistry("self-node")
        val (messenger, scope) = newMessenger(link, tcp, public = false, registry = registry)
        try {
            var redials = 0
            messenger.linkRedial = { _ ->
                redials++
                true
            }
            registry.touch("peer-1", "192.168.2.82", 17999, profile("peer-1"), profileTs = 1000L, confirmed = true)

            val outcome = messenger.sendUserMessage(
                "peer-1",
                makeEnvelope(from = "self-node", type = MsgTypes.MSG, payload = Codec.textPayload("lan")),
            )
            assertEquals(SendOutcome.SENT, outcome)
            assertEquals("sent-via-tcp", messenger.lastReliableSend()?.outcome, messenger.lastReliableSend()?.summary())
            assertEquals(1, tcp.calls, "局域网对端必须保留 17879 回退（那里它是通的）")
            assertEquals(0, redials, "局域网不该重拨")
        } finally {
            messenger.stop()
            scope.cancel()
        }
    }
}
