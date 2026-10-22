// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.session

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.Timings
import app.relay.core.transport.InMemoryDedupStore
import app.relay.core.transport.InMemoryMessageStore
import app.relay.core.transport.InMemoryQueueStore
import app.relay.core.transport.Messenger
import app.relay.core.transport.PeerRegistry
import app.relay.core.transport.SendOutcome
import app.relay.core.transport.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「重发」：按**原信封 id** 重放，而不是把同样的话当新消息再发一遍。
 *
 * 真机代价（桌面库读数）：同一句话在 `MessageEntity` 里出现两条不同 id ——
 * `f93a27cf…` 与 `0137bff5…`，因为重发走的是"以原文本再发一条"。
 * 收端按 `(id, from)` 去重，两个 id 就是两条，用户看到"我只按了一次重发，群里多了一句话"。
 *
 * 这条测试的判据必须能**区分两种行为**（AGENTS §4「当心假绿」）：
 *  · 同一个 id 重放 N 次 ⇒ 收端只落一条；
 *  · 换个 id 再发一次 ⇒ 收端落两条。
 * 只断言"收端收到了一条"是分不出对错的 —— 那两种实现都会满足。
 */
class OutgoingReplayTest {

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
        tcpPort = 20000,
        ver = "relay-test",
        caps = MsgTypes.MOBILE_CAPS,
    )

    private class Receiver(
        val messenger: Messenger,
        val transport: UdpTransport,
        val received: MutableList<Envelope>,
        val port: Int,
    )

    /**
     * 收端节点的 nodeId 必须与发送端认定的 peerId 一致（都是 `peer-1`）：ACK 的 `from`
     * 就是它，发送端按 `peerId|msgId` 匹配挂起的 ACK —— 两个名字不一样就永远等不到 ACK，
     * 症状是"对端明明收到了，我们这边还是 QUEUED"。
     */
    private suspend fun startReceiver(scope: CoroutineScope): Receiver {
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val messenger = Messenger(
            transport = transport,
            registry = PeerRegistry("peer-1"),
            selfNodeId = "peer-1",
            queueStore = InMemoryQueueStore(),
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(300L), tcpAckTimeout = 50L),
        )
        transport.start()
        messenger.start(scope)
        val received = mutableListOf<Envelope>()
        scope.launch { messenger.incoming.collect { received += it } }
        // 订阅先于发送：没有订阅者时 SharedFlow 的 tryEmit 会把帧丢掉，测试就会假绿。
        // `Messenger.incoming` 只暴露 SharedFlow（没有 subscriptionCount），所以这里等一小会儿。
        delay(100)
        return Receiver(messenger, transport, received, transport.boundPort)
    }

    private class Sender(
        val outbound: NodeOutbound,
        val messenger: Messenger,
        val registry: PeerRegistry,
        val queueStore: InMemoryQueueStore,
        val transport: UdpTransport,
    )

    private suspend fun startSender(): Sender {
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self")
        val queueStore = InMemoryQueueStore()
        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self",
            queueStore = queueStore,
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(300L), tcpAckTimeout = 50L),
        )
        transport.start()
        messenger.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val outbound = NodeOutbound(
            messenger = messenger,
            messageStore = InMemoryMessageStore(),
            selfId = { "self" },
            selfProfile = { profile("self") },
            peerDeclares = { _, _ -> false },
        )
        return Sender(outbound, messenger, registry, queueStore, transport)
    }

    private suspend fun awaitCount(what: String, expected: Int, list: MutableList<Envelope>, timeoutMs: Long = 5_000) {
        withTimeout(timeoutMs) {
            while (list.size < expected) delay(5)
        }
    }

    @Test
    fun replayingTheSameIdTwiceLeavesOneMessageWhileANewIdLeavesTwo() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receiver = startReceiver(scope)
        val sender = startSender()
        try {
            sender.registry.touch(
                "peer-1", "127.0.0.1", receiver.port, profile("peer-1"),
                profileTs = 1L, confirmed = true,
            )
            val originalId = "f93a27cf-1111-4222-8333-944455556666"
            val request = OutgoingReplay(peerId = "peer-1", msgId = originalId, text = "同一句话")

            val first = sender.outbound.replayOutgoing(request)
            assertEquals(SendOutcome.SENT, first, "对端在线且回 ACK ⇒ 重放应当直接 SENT")
            awaitCount("第一次重放没送达", 1, receiver.received)

            // 第二次：同一个 id。收端按 (id, from) 去重 ⇒ 只 ACK，不再入库第二条。
            val second = sender.outbound.replayOutgoing(request)
            assertEquals(SendOutcome.SENT, second, "重复帧也要回 ACK，否则发送端会一直重试")
            delay(150) // 给去重路径一点时间；这里断言的是"没有新增"，等一小会儿是必要的
            assertEquals(1, receiver.received.size, "同一个 id 重放两次，收端只能有一条")

            val delivered = receiver.received.single()
            assertEquals(originalId, delivered.id, "重放必须沿用原信封 id")
            assertEquals("同一句话", (delivered.payload.jsonObject["text"] as JsonPrimitive).content)
            assertEquals(
                true,
                (delivered.payload.jsonObject["resend"] as JsonPrimitive).content.toBoolean(),
                "重建出来的那份必须带 resend=true：收端据此知道这是补发而不是新消息",
            )

            // 旧行为（换新 id 再发一条）的对照：同样的正文、不同的 id ⇒ 收端落第二条。
            sender.outbound.sendText("peer-1", "同一句话")
            awaitCount("对照路径（新 id）应当产生第二条", 2, receiver.received)
            assertEquals(2, receiver.received.size, "换 id 重发就是新消息 —— 这正是要修掉的行为")
        } finally {
            sender.messenger.stop()
            sender.transport.stop()
            receiver.messenger.stop()
            receiver.transport.stop()
            scope.cancel()
        }
    }

    @Test
    fun theQueuedOriginalIsReplayedVerbatimInsteadOfRebuildingFromTheCallersFields() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receiver = startReceiver(scope)
        val sender = startSender()
        try {
            // 对端离线：这条发送一个包都不会发，直接入队 —— 队列里留下**原信封**。
            sender.registry.restoreOffline(profile("peer-1"), "127.0.0.1", receiver.port, 1L)
            val first = sender.outbound.sendTextWithEnvelope("peer-1", "原始正文")
            assertEquals(SendOutcome.QUEUED, first.outcome, "对端离线且没有候选 ⇒ 入队等补发")
            assertNotNull(
                sender.queueStore.listByPeer("peer-1").firstOrNull { it.msgId == first.envelopeId },
                "前提：原信封必须还在队列里，否则这条测试测不到「优先用队列那份」",
            )

            // 对端上线（模拟它回来了）。
            sender.registry.touch(
                "peer-1", "127.0.0.1", receiver.port, profile("peer-1"),
                profileTs = 2L, confirmed = true,
            )

            // 调用方交给重发的文本是**改过的**：如果实现走了重建，对端收到的就是这句改过的。
            val outcome = sender.outbound.replayOutgoing(
                OutgoingReplay(peerId = "peer-1", msgId = first.envelopeId, text = "被改过的正文"),
            )
            assertEquals(SendOutcome.SENT, outcome, "队列里那条原样重放，拿到 ACK")
            awaitCount("队列里的那条没有补发出去", 1, receiver.received)

            val delivered = receiver.received.single()
            assertEquals(first.envelopeId, delivered.id, "补发的必须是同一条 id")
            assertEquals(
                "原始正文",
                (delivered.payload.jsonObject["text"] as JsonPrimitive).content,
                "队列里有原信封时不许用调用方的新文本重建 —— 那会改掉用户当初说的话",
            )
            assertEquals(
                true,
                (delivered.payload.jsonObject["resend"] as JsonPrimitive).content.toBoolean(),
                "补发仍然要打 resend 标记",
            )
            assertTrue(
                sender.queueStore.listByPeer("peer-1").isEmpty(),
                "ACK 之后队列里不该还留着这一条：${sender.queueStore.listByPeer("peer-1").map { it.msgId }}",
            )
        } finally {
            sender.messenger.stop()
            sender.transport.stop()
            receiver.messenger.stop()
            receiver.transport.stop()
            scope.cancel()
        }
    }

    @Test
    fun aRebuiltSingleChatReplayUsesTheOriginalIdAndResendFlag() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receiver = startReceiver(scope)
        val sender = startSender()
        try {
            sender.registry.touch(
                "peer-1", "127.0.0.1", receiver.port, profile("peer-1"),
                profileTs = 1L, confirmed = true,
            )
            val originalId = "0137bff5-aaaa-4bbb-8ccc-dddddddddddd"
            // 队列里没有这一条（例如进程重启过）⇒ 走重建：id 原样、payload 带 resend。
            val outcome = sender.outbound.replayOutgoing(
                OutgoingReplay(peerId = "peer-1", msgId = originalId, text = "重建的正文"),
            )
            assertEquals(SendOutcome.SENT, outcome)
            awaitCount("重建的重放没有送达", 1, receiver.received)
            val delivered = receiver.received.single()
            assertEquals(originalId, delivered.id, "重建也不许换 id")
            assertEquals("重建的正文", (delivered.payload.jsonObject["text"] as JsonPrimitive).content)
            assertEquals(
                true,
                (delivered.payload.jsonObject["resend"] as JsonPrimitive).content.toBoolean(),
            )
            assertTrue(
                Codec.decodeTcp(Codec.encode(delivered)) is app.relay.core.protocol.DecodeResult.Ok,
                "重建出来的帧必须是可解码的（否则对端连 ACK 都不会回）",
            )
        } finally {
            sender.messenger.stop()
            sender.transport.stop()
            receiver.messenger.stop()
            receiver.transport.stop()
            scope.cancel()
        }
    }
}
