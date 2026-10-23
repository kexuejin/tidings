// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.core.protocol.Timings
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

        /** 只对某个 host 成功：用来验证"按候选顺序换地址"。null = 用 [shouldSucceed]。 */
        var succeedForHost: String? = null
        var lastEnvelope: Envelope? = null
        val hosts = mutableListOf<String>()

        override suspend fun sendAndAwaitAck(host: String, port: Int, envelope: Envelope): Boolean {
            calls++
            lastEnvelope = envelope
            hosts += host
            return succeedForHost?.let { host == it } ?: shouldSucceed
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

    /**
     * 离线排队的**长群消息**必须补得出去，不能被队列的 decode 悄悄删掉。
     *
     * 回归背景：`flushQueue` 原来用 `Codec.decode`（UDP 那套 800/4096）读落盘信封，
     * 于是一条 4239 字节的群消息重放时被判成坏帧，紧接着 `queueStore.remove` ——
     * 不是「发不出去」，而是**被删了**。真机上 140 条超 4096 字节的群消息就是这么消失的。
     */
    @Test
    fun anOverlongQueuedGroupMessageIsStillFlushed() = runBlocking {
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

        // 4239 字节：正是真机上被丢掉的那条「全部完成。」的量级。
        val head = "全部完成。"
        val body = head + "汉".repeat((4239 - Codec.utf8ByteLength(head)) / 3)
        assertTrue(Codec.utf8ByteLength(body) > Protocol.TEXT_TCP_LIMIT)
        val env = makeEnvelope(
            from = "self-node",
            type = MsgTypes.MSG,
            payload = Codec.groupTextPayload(body, "grp-1", 1),
        )

        assertEquals(SendOutcome.QUEUED, messenger.sendUserMessage("peer-1", env))
        assertEquals(1, queueStore.listByPeer("peer-1").size)

        // TCP 恢复。
        //
        // 这里**刻意不调 `registry.touch`**：`touch` 会触发一次 `scope.launch { flushQueue }`
        // 的**异步**补发，跟下面的显式调用抢同一把 `flushing` 锁 —— 谁先跑完是随机的，
        // 断言就会变成时绿时红。直接显式 flush 才钉得住这条路径。
        //
        // **清掉 lastEnvelope**：上面那次 `sendUserMessage` 的 TCP 回退已经记过一次，
        // 不清的话下面的断言在「这条被 decode 删掉、什么都没补发」时也会通过 —— 假绿。
        mockTcp.lastEnvelope = null
        mockTcp.shouldSucceed = true
        messenger.flushQueue("peer-1")

        // 队列被清空有两种原因：**补发成功**，或**被判成坏帧删掉**。
        // 只有「补发了同一条信封」才能区分它们 —— 这才是这条测试真正要钉的东西。
        awaitUntil("长群消息必须被真的补发") { mockTcp.lastEnvelope?.id == env.id }
        assertEquals(0, queueStore.listByPeer("peer-1").size, "补发成功后队列应清空")

        transport.stop()
        messenger.stop()
        scope.cancel()
    }

    // ---- 真机反馈：积压卡在「对端显示在线」的那段时间里不补发 ----
    //
    // 现场：/v1/peers 显示 REDMI K80 online=true，queue.log 却压着 28 条刚产生的消息，
    // 十几分钟没送出去。根因是 flush 只有「离线→在线」这一个事件触发点，
    // 而 flush 失败时**不改在线状态** ⇒ 在线标志一直留在 true ⇒ 再也不会有转变 ⇒ 没人重试。
    // 下面两条分别钉住修复的两个半边。

    @Test
    fun aQueueStuckWhileThePeerStaysOnlineIsDrainedByThePeriodicRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self-node")
        val queueStore = InMemoryQueueStore()
        val mockTcp = MockTcpFallback().apply { shouldSucceed = true }

        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = queueStore,
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(30L), tcpAckTimeout = 50L),
            tcpFallback = mockTcp,
            queueRetryIntervalMs = 40L,
        )
        transport.start()
        messenger.start(scope)

        // 对端**一直在线**，而且后面不会发生任何「离线→在线」转变。
        registry.touch("peer-1", "127.0.0.1", 17999, testProfile("peer-1"), profileTs = 1000L, confirmed = true)

        // 队列里有积压（直接入队，模拟「积压 + registry 在线」这个现场状态）。
        val env = makeEnvelope(from = "self-node", type = MsgTypes.MSG, payload = Codec.textPayload("卡住的那条"))
        queueStore.enqueue(env.id, "peer-1", Codec.encode(env), 1L)
        assertTrue(registry.get("peer-1")!!.online, "前提：对端在 registry 里是在线")

        // 唯一能救它的是定时重试：这里**不**调用 flushQueue，也不制造任何 Online 转变。
        awaitUntil("定时重试把积压清掉") { queueStore.listByPeer("peer-1").isEmpty() }
        assertTrue(mockTcp.calls >= 1, "补发应该走过一次 TCP 回退（UDP 没人回 ACK）")

        transport.stop()
        messenger.stop()
        scope.cancel()
    }

    @Test
    fun aFailedFlushMarksThePeerOfflineSoTheNextPresenceRetriesAndDrains() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self-node")
        val queueStore = InMemoryQueueStore()
        val mockTcp = MockTcpFallback().apply { shouldSucceed = false }

        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = queueStore,
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(30L), tcpAckTimeout = 50L),
            tcpFallback = mockTcp,
            // 定时器调大，确保这一条只走「事件触发」那条路。
            queueRetryIntervalMs = 60_000L,
        )
        transport.start()
        messenger.start(scope)

        registry.touch("peer-1", "127.0.0.1", 17999, testProfile("peer-1"), profileTs = 1000L, confirmed = true)
        val env = makeEnvelope(from = "self-node", type = MsgTypes.MSG, payload = Codec.textPayload("补发失败的那条"))
        queueStore.enqueue(env.id, "peer-1", Codec.encode(env), 1L)

        // 路径不通：补发失败。**关键断言**：对端必须被标离线 —— 否则在线标志永远为 true，
        // 下一次 presence 不会产生「离线→在线」转变，积压就再也没人补发。
        //
        // 为什么要**按节奏重发这次 flush**（task-70）：`flushQueue` 在 `pendingLock` 的
        // `flushing` 集合里 add 失败时**静默 return** —— 负载下若已有一次 flush 在飞
        // （例如 touch 触发的 Online 监听器那次），这一次调用就什么都没做，`online` 仍是 true。
        // 这条测试要验的是「**失败会把对端标离线**」这个最终状态，不是「这一次调用栈里就标了」，
        // 所以等到状态成立为止（与 task-68 的 sendUntil 同手法：重发刺激，而不是调大超时）。
        val deadline = nowMillis() + 5_000
        while (nowMillis() < deadline && registry.get("peer-1")!!.online) {
            messenger.flushQueue("peer-1")
            delay(20)
        }
        assertEquals(1, queueStore.listByPeer("peer-1").size, "失败时不能把消息从队列里丢掉")
        assertFalse(registry.get("peer-1")!!.online, "补发失败必须把对端标离线（在线状态要诚实）")

        // 恢复 + 对端 presence：这一次是真正的「离线→在线」，Online 监听器会自己补发。
        mockTcp.shouldSucceed = true
        registry.touch("peer-1", "127.0.0.1", 17999, testProfile("peer-1"), profileTs = 2000L, confirmed = true)

        awaitUntil("对端上线后积压被补齐") { queueStore.listByPeer("peer-1").isEmpty() }
        assertTrue(registry.get("peer-1")!!.online)

        transport.stop()
        messenger.stop()
        scope.cancel()
    }

    @Test
    fun aDeadActiveAddressFallsBackToAStoredCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self-node")
        val queueStore = InMemoryQueueStore()
        // 只有候选那个公网地址"能通"（真机上就是"局域网地址过期、公网 IPv6 可达"）。
        val mockTcp = MockTcpFallback().apply { succeedForHost = "2001:db8::1" }

        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = queueStore,
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(20L), tcpAckTimeout = 20L),
            tcpFallback = mockTcp,
            queueRetryIntervalMs = 60_000L,
        )
        transport.start()
        messenger.start(scope)

        // 当前地址是死的（127.0.0.1:1 没人回 ACK），候选池里存着配对那些地址。
        registry.touch("peer-1", "127.0.0.1", 1, testProfile("peer-1"), profileTs = 1000L, confirmed = true)
        registry.setCandidates("peer-1", listOf(PeerEndpoint("2001:db8::1", 17878)))

        val env = makeEnvelope(from = "self-node", type = MsgTypes.MSG, payload = Codec.textPayload("跨网也要发出去"))
        val outcome = messenger.sendUserMessage("peer-1", env)

        assertEquals(SendOutcome.SENT, outcome, "当前地址发不出去时必须按候选顺序兜底")
        // 下面三条是**最终状态**：`sendUserMessage` 已经 await 完，但它们依赖 registry/队列的
        // 后续写入，满量并行下可能还在路上 ⇒ 用有界等待观察状态，而不是紧接一次调用就断言
        // （task-70：与 task-66 ③ / task-68 同族 —— 别把「观察」建立在一次时序假设上）。
        awaitUntil("候选地址必须被试过：${mockTcp.hosts}") { mockTcp.hosts.contains("2001:db8::1") }
        awaitUntil("试通了就把它提升为当前地址") { registry.get("peer-1")?.ip == "2001:db8::1" }
        awaitUntil("成功就不该入队") { queueStore.listByPeer("peer-1").isEmpty() }

        transport.stop()
        messenger.stop()
        scope.cancel()
    }

    @Test
    fun anOfflinePeerWithCandidatesIsStillTriedAndComesBackOnline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self-node")
        val queueStore = InMemoryQueueStore()
        val mockTcp = MockTcpFallback().apply { succeedForHost = "2001:db8::1" }
        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = queueStore,
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(20L), tcpAckTimeout = 20L),
            tcpFallback = mockTcp,
            queueRetryIntervalMs = 60_000L,
        )
        transport.start()
        messenger.start(scope)

        // 现场（task-21）：当前地址是死地址、peer 被上一次失败补发标成 offline，
        // 但候选池里的公网地址其实是通的。
        registry.touch("peer-1", "127.0.0.1", 1, testProfile("peer-1"), profileTs = 1000L, confirmed = true)
        registry.setCandidates("peer-1", listOf(PeerEndpoint("2001:db8::1", 17878)))
        registry.markOffline("peer-1")
        assertFalse(registry.get("peer-1")!!.online, "前提：对端当前是离线")

        val env = makeEnvelope(from = "self-node", type = MsgTypes.MSG, payload = Codec.textPayload("离线也要试候选"))
        val outcome = messenger.sendUserMessage("peer-1", env)

        assertEquals(SendOutcome.SENT, outcome, "有候选地址时，offline 不该直接跳过发送")
        assertTrue(registry.get("peer-1")!!.online, "候选试通了必须把对端翻回在线")
        assertEquals("2001:db8::1", registry.get("peer-1")!!.ip)

        transport.stop()
        messenger.stop()
        scope.cancel()
    }

    @Test
    fun thePeriodicRetryAlsoDrainsAnOfflinePeerThatHasCandidates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self-node")
        val queueStore = InMemoryQueueStore()
        val mockTcp = MockTcpFallback().apply { succeedForHost = "2001:db8::1" }
        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = queueStore,
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(20L), tcpAckTimeout = 20L),
            tcpFallback = mockTcp,
            queueRetryIntervalMs = 40L,
        )
        transport.start()
        messenger.start(scope)

        registry.touch("peer-1", "127.0.0.1", 1, testProfile("peer-1"), profileTs = 1000L, confirmed = true)
        registry.setCandidates("peer-1", listOf(PeerEndpoint("2001:db8::1", 17878)))
        registry.markOffline("peer-1")

        val env = makeEnvelope(from = "self-node", type = MsgTypes.MSG, payload = Codec.textPayload("被标离线后没人翻回来"))
        queueStore.enqueue(env.id, "peer-1", Codec.encode(env), 1L)

        // 没有任何 Online 事件、也没有人手动 flush：只有定时重试能救。
        awaitUntil("离线 + 有候选的对端，定时重试也必须把它补出去") {
            queueStore.listByPeer("peer-1").isEmpty()
        }
        assertTrue(registry.get("peer-1")!!.online)

        transport.stop()
        messenger.stop()
        scope.cancel()
    }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
