// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.Timings
import com.kapp.tenon.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **入站帧的源端口不是对端的监听端口。**
 *
 * legacy 时每个节点的 UDP socket 绑固定端口，`rinfo` 的源端口就是监听端口，回包发回去
 * 天经地义。luciola 的会话键是「地址:监听端口」，而发送侧用的是临时端口 —— 真机上把
 * 52213 这类端口写进节点表后，消息、心跳、候选池全部拨到不存在的端点：传输面满屏
 * `no session / unpaired`，拨号侧则是 `no candidate address reachable`。
 *
 * 这里钉住的是**全部入站登记路径**（发现帧、可靠帧、候选池、回包、刷新帧）都必须用
 * 档案声明的 `luciolaPort`；档案没有这个键的老端行为一字不变。任何一条路径漏改，
 * 都会把节点表端口重新污染，而症状只在真机上出现。
 */
class LuciolaSendingPortTest {

    private val self = profile("node-b", 17878)

    private fun profile(nodeId: String, luciolaPort: Int?, addresses: List<String>? = null) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 1,
        host = "192.0.2.7",
        platform = "linux",
        tcpPort = 17879,
        addresses = addresses,
        luciolaPort = luciolaPort,
        ver = "relay-0.1.0",
        caps = emptyList(),
    )

    private fun entryFrame(peer: Profile, probeId: String? = null): ByteArray =
        Codec.encodeToBytes(
            makeEnvelope(MsgTypes.ENTRY, peer.nodeId, Codec.profilePayload(peer, probeId)),
        )

    private fun presenceFrame(peerId: String, rev: Int): ByteArray =
        Codec.encodeToBytes(
            makeEnvelope(MsgTypes.PRESENCE, peerId, Codec.presencePayload(1, rev)),
        )

    private fun ingest(
        scope: CoroutineScope,
        transport: RecordingTransport,
        registry: PeerRegistry,
    ) = PresenceIngest(
        transport = transport,
        registry = registry,
        scope = scope,
        timings = Timings(
            directedReplyInterval = 0,
            entryReplyJitterBase = 0,
            entryReplyJitterMax = 1,
            aliveDedupWindow = 0,
        ),
        selfProfile = { self },
    )

    /** 核心判据：带 luciolaPort 的 entry 不得把临时源端口写进节点表、候选池或回包目标。 */
    @Test
    fun declaredPortWinsOverEphemeralSourceEverywhere() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport()
        val registry = PeerRegistry("node-b")
        val ingest = ingest(scope, transport, registry)
        try {
            val declared = listOf("100.66.238.121", "192.168.2.82")
            val peer = profile("node-a", 17878, declared)
            ingest.onDatagram(ReceivedDatagram(entryFrame(peer, "probe-1"), "192.0.2.7", 52213))

            assertEquals(17878, registry.get("node-a")?.udpPort, "节点表的发送端口必须是档案声明的监听端口")
            val candidates = registry.candidatesOf("node-a")
            assertTrue(candidates.isNotEmpty(), "候选池必须记下入站源地址与档案声明的地址")
            assertTrue(candidates.all { it.port == 17878 }, "候选池里混入了临时源端口：$candidates")
            assertTrue(
                candidates.map { it.host }.containsAll(declared + "192.0.2.7"),
                "档案声明的地址与入站源地址都要在候选池里：$candidates",
            )
            awaitTrue { transport.targets().isNotEmpty() }
            assertTrue(
                transport.targets().all { it.second == 17878 },
                "alive 回包必须发到监听端口，实得=${transport.targets()}",
            )
        } finally {
            scope.cancel()
        }
    }

    /** 老端档案没有这个键：仍按数据报源端口，行为一字不变。 */
    @Test
    fun legacyProfileWithoutDeclaredPortKeepsSourcePort() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport()
        val registry = PeerRegistry("node-b")
        val ingest = ingest(scope, transport, registry)
        try {
            ingest.onDatagram(ReceivedDatagram(entryFrame(profile("node-a", null), "probe-2"), "192.0.2.7", 52213))
            assertEquals(52213, registry.get("node-a")?.udpPort)
            assertTrue(registry.candidatesOf("node-a").all { it.port == 52213 })
            awaitTrue { transport.targets().isNotEmpty() }
            assertTrue(transport.targets().all { it.second == 52213 })
        } finally {
            scope.cancel()
        }
    }

    /**
     * **落盘旧数据自愈**：上一版写进候选池的临时端口，装机后仍会被心跳和消息重试拨到。
     * 归一收在读口，所有消费路径与落盘快照一次改对；资料尚未恢复 / 老端档案保持原样。
     */
    @Test
    fun storedEphemeralCandidatesAreNormalizedOnRead() {
        val registry = PeerRegistry("node-b")
        registry.setCandidates(
            "node-a",
            listOf(PeerEndpoint("192.0.2.7", 52213), PeerEndpoint("100.66.238.121", 49_996)),
        )
        // 启动顺序是候选先于名录恢复：此刻没有资料，不该凭空改端口。
        assertEquals(listOf(52213, 49_996), registry.candidatesOf("node-a").map { it.port })

        registry.touch("node-a", "192.0.2.7", 17878, profile("node-a", 17878), 1_000L, confirmed = true)
        val normalized = registry.candidatesOf("node-a")
        assertTrue(normalized.all { it.port == 17878 }, "读出的候选端点必须归一：$normalized")
        assertEquals(
            setOf("192.0.2.7", "100.66.238.121"),
            normalized.map { it.host }.toSet(),
            "归一不许丢地址",
        )
        assertTrue(
            registry.allCandidates()["node-a"].orEmpty().all { it.port == 17878 },
            "落盘快照同样要归一，否则下次装机又把旧端口装回来",
        )

        val legacy = PeerRegistry("node-b")
        legacy.touch("node-a", "192.0.2.7", 17878, profile("node-a", null), 1_000L, confirmed = true)
        legacy.setCandidates("node-a", listOf(PeerEndpoint("192.0.2.7", 52213)))
        assertEquals(52213, legacy.candidatesOf("node-a").single().port, "老端行为一字不变")
    }

    /** 第三条历史路径：启动恢复的名录把落盘 udpPort 直接写成当前地址。 */
    @Test
    fun restoredKnownPeerNormalizesItsCurrentPort() {
        val registry = PeerRegistry("node-b")
        registry.restoreOffline(profile("node-a", 17878), "192.0.2.7", 52213, 1_000L)
        assertEquals(17878, registry.get("node-a")?.udpPort, "恢复的当前端口必须按档案归一")

        val legacy = PeerRegistry("node-b")
        legacy.restoreOffline(profile("node-a", null), "192.0.2.7", 52213, 1_000L)
        assertEquals(52213, legacy.get("node-a")?.udpPort, "老端行为一字不变")
    }

    /**
     * 归一函数自身的形状：越界声明不许进拨号目标（会被 toUShort() 截断成另一个端口）。
     *
     * 这里直接测函数而不是发一帧 —— `validateProfile` 会把越界值的**整帧**拒收，
     * 走编解码根本到不了登记器；这一层是 native `peer.extra` 那条不校验的路径的兜底。
     */
    @Test
    fun sendingPortForRejectsOutOfRangeAndHonorsLegacyProfiles() {
        assertEquals(17878, sendingPortFor(profile("node-a", 17878), 52213))
        assertEquals(52213, sendingPortFor(profile("node-a", null), 52213))
        assertEquals(52213, sendingPortFor(null, 52213))
        for (bad in listOf(0, -1, 65_536, 70_000)) {
            assertEquals(52213, sendingPortFor(profile("node-a", bad), 52213), "越界值 $bad 必须回退")
        }
    }

    /**
     * 资料刷新**不许把已经知道的信道端口弄丢**。
     *
     * `luciolaPort` 是**运行时注入**的（`selfProfile` 从 `transport.boundPort` 取），
     * 不是对端资料的一部分：绑端口之前的帧、或任何一条没走 `selfProfile` 的路径都会带
     * null，而 `touch` 原来整份替换档案 ⇒ 端口被抹掉 ⇒ 心跳与重试回退到数据报**临时
     * 源端口**，症状与 A30 同形（满屏 `no session / unpaired`），只是成因换成
     * 「知道过的端口被覆盖」。守卫只在 `touch` 这一处，因为入站发现帧、可靠帧、发现
     * 引擎全都经过它。
     */
    @Test
    fun aProfileRefreshWithoutChannelPortKeepsTheKnownOne() {
        val registry = PeerRegistry("node-b")
        registry.touch("node-a", "192.0.2.7", 17878, profile("node-a", 17878), 1_000L, confirmed = true)
        // 对端换网后重发 entry，这一次档案里没有 luciolaPort。
        registry.touch("node-a", "192.0.2.7", 17878, profile("node-a", null), 2_000L, confirmed = true)

        val refreshed = registry.get("node-a")
        assertEquals(17878, refreshed?.profile?.luciolaPort, "已知的信道端口被这次刷新抹掉了")
        assertEquals(17878, refreshed?.udpPort)
        assertEquals(
            17878,
            sendingPortFor(refreshed?.profile, 52213),
            "端口丢了就只剩临时源端口这条回退链 —— 归一必须仍然拿到 17878",
        )
    }

    /** 心跳不带资料，端口得从节点表里已存的档案取；补发 entry 也不许拨临时端口。 */
    @Test
    fun presenceRefreshUsesTheKnownProfilePort() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport()
        val registry = PeerRegistry("node-b")
        val peer = profile("node-a", 17878)
        registry.touch("node-a", "192.0.2.7", 17878, peer, 1_000L, confirmed = true)
        val ingest = ingest(scope, transport, registry)
        try {
            ingest.onDatagram(ReceivedDatagram(presenceFrame("node-a", 99), "192.0.2.7", 52213))
            assertEquals(17878, registry.get("node-a")?.udpPort, "presence 不许把节点表端口改回源端口")
            awaitTrue { transport.targets().isNotEmpty() }
            assertTrue(
                transport.targets().all { it.second == 17878 },
                "资料失配补发的 entry 必须拨监听端口，实得=${transport.targets()}",
            )
        } finally {
            scope.cancel()
        }
    }

    /** Messenger 的可靠帧：ACK 与 touch 同口径，否则对端永远等不到 ACK、节点表又被改坏。 */
    @Test
    fun reliableFrameAckGoesToDeclaredPortWithoutCorruptingRegistry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport()
        val registry = PeerRegistry("node-b")
        registry.touch("node-a", "192.0.2.7", 17878, profile("node-a", 17878), 1_000L, confirmed = true)
        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "node-b",
            queueStore = InMemoryQueueStore(),
            dedupStore = InMemoryDedupStore(),
            queueRetryIntervalMs = 60_000L,
        )
        messenger.start(scope)
        try {
            val frame = Codec.encodeToBytes(
                makeEnvelope(MsgTypes.MSG, "node-a", Codec.textPayload("port-probe")),
            )
            deliverRepeatedly(times = 4, gapMs = 40) {
                transport.deliver(frame, "192.0.2.7", 52213)
            }
            awaitTrue { transport.targets().isNotEmpty() }
            assertEquals(17878, registry.get("node-a")?.udpPort, "可靠帧不许把节点表端口改成源端口")
            assertTrue(
                transport.targets().all { it.second == 17878 },
                "ACK 必须发到监听端口，实得=${transport.targets()}",
            )
        } finally {
            messenger.stop()
            scope.cancel()
        }
    }
}

/** 只记录发送目标，不做真实 socket：端口判据要能精确断言，不能靠「碰巧收到了」。 */
private class RecordingTransport : DatagramTransport {
    private val bus = MutableSharedFlow<ReceivedDatagram>(replay = 8, extraBufferCapacity = 8)
    override val incoming: SharedFlow<ReceivedDatagram> = bus
    override val boundPort: Int = 17878
    private val sent = mutableListOf<Pair<String, Int>>()

    override suspend fun start() {}
    override fun stop() {}
    override suspend fun restart() {}

    override suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean {
        synchronized(sent) { sent += host to port }
        return true
    }

    suspend fun deliver(frame: ByteArray, host: String, port: Int) {
        bus.emit(ReceivedDatagram(frame, host, port))
    }

    fun targets(): List<Pair<String, Int>> = synchronized(sent) { sent.toList() }
}
