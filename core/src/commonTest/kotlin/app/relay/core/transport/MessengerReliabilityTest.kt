// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.Timings
import app.relay.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Messenger] 的重试回退、离线入队与上线保序补发单元测试。
 */
class MessengerReliabilityTest {

    private fun testProfile(nodeId: String) = Profile(
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

    private class MockTcpFallback : TcpFallback {
        var calls = 0
        var shouldSucceed = false
        var lastEnvelope: Envelope? = null

        override suspend fun sendAndAwaitAck(host: String, port: Int, envelope: Envelope): Boolean {
            calls++
            lastEnvelope = envelope
            return shouldSucceed
        }
    }

    @Test
    fun udpRetriesExhaustedFallsBackToTcp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self-node")
        val queueStore = InMemoryQueueStore()
        val dedupStore = InMemoryDedupStore()
        val mockTcp = MockTcpFallback().apply { shouldSucceed = true }

        val fastTimings = Timings(
            ackRetrySchedule = listOf(50L, 50L), // 快速重试
            tcpAckTimeout = 100L,
        )

        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = queueStore,
            dedupStore = dedupStore,
            timings = fastTimings,
            tcpFallback = mockTcp,
        )

        transport.start()
        messenger.start(scope)

        // 注册一个对端（在线，但没人回 UDP ACK）
        registry.touch("peer-1", "127.0.0.1", 17999, testProfile("peer-1"), profileTs = 1000L, confirmed = true)

        val env = makeEnvelope(
            from = "self-node",
            type = MsgTypes.MSG,
            payload = Codec.textPayload("hello tcp fallback"),
        )

        val outcome = messenger.sendUserMessage("peer-1", env)
        assertEquals(SendOutcome.SENT, outcome, "TCP 成功应返回 SENT")
        assertEquals(1, mockTcp.calls, "UDP 重试失败后应回退一次 TCP")
        assertEquals(env.id, mockTcp.lastEnvelope?.id)

        transport.stop()
        messenger.stop()
        scope.cancel()
    }

    @Test
    fun peerOfflineOrAllAttemptsFailEnqueuesAndFlushesInOrderOnOnline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self-node")
        val queueStore = InMemoryQueueStore()
        val dedupStore = InMemoryDedupStore()
        val mockTcp = MockTcpFallback().apply { shouldSucceed = false }

        val fastTimings = Timings(
            ackRetrySchedule = listOf(50L),
            tcpAckTimeout = 50L,
        )

        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = queueStore,
            dedupStore = dedupStore,
            timings = fastTimings,
            tcpFallback = mockTcp,
        )

        transport.start()
        messenger.start(scope)

        registry.touch("peer-1", "127.0.0.1", 17999, testProfile("peer-1"), profileTs = 1000L, confirmed = true)

        val env1 = makeEnvelope(
            from = "self-node",
            type = MsgTypes.MSG,
            payload = Codec.textPayload("message 1"),
        )
        val env2 = makeEnvelope(
            from = "self-node",
            type = MsgTypes.MSG,
            payload = Codec.textPayload("message 2"),
        )

        // 第一次发送失败 -> 入队并标记离线
        val outcome1 = messenger.sendUserMessage("peer-1", env1)
        assertEquals(SendOutcome.QUEUED, outcome1)
        assertFalse(registry.get("peer-1")!!.online, "失败后应标记对端离线")

        // 第二次对端已处于离线态 -> 直接入队
        val outcome2 = messenger.sendUserMessage("peer-1", env2)
        assertEquals(SendOutcome.QUEUED, outcome2)

        val queuedItems = queueStore.listByPeer("peer-1")
        assertEquals(2, queuedItems.size)
        assertEquals(env1.id, queuedItems[0].msgId)
        assertEquals(env2.id, queuedItems[1].msgId)

        // 对端上线，TCP 恢复
        mockTcp.shouldSucceed = true
        registry.touch("peer-1", "127.0.0.1", 17999, testProfile("peer-1"), profileTs = 2000L, confirmed = true)
        assertTrue(registry.get("peer-1")!!.online)

        messenger.flushQueue("peer-1")

        // 补发成功后队列应被清空
        val remaining = queueStore.listByPeer("peer-1")
        assertEquals(0, remaining.size, "补发成功后队列应清空")

        transport.stop()
        messenger.stop()
        scope.cancel()
    }
}
