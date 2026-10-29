// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.DecodeResult
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgKinds
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.core.protocol.Timings
import com.kapp.tidings.core.transport.InMemoryDedupStore
import com.kapp.tidings.core.transport.InMemoryMessageStore
import com.kapp.tidings.core.transport.InMemoryQueueStore
import com.kapp.tidings.core.transport.Messenger
import com.kapp.tidings.core.transport.PeerRegistry
import com.kapp.tidings.core.transport.SendOutcome
import com.kapp.tidings.core.transport.TcpFallback
import com.kapp.tidings.core.transport.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.kapp.tidings.core.testutil.awaitTrue
import com.kapp.tidings.core.transport.QueuedMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `group-text` 的扩展键（`senderId` / `reasoning`）必须**按对端能力**（`gta1`）门控。
 *
 * 立这条测试的理由是真机上付出的代价：上游茶话间对这份载荷做**严格字段白名单**，多一个键
 * 就整帧丢弃 —— 于是「agent 转发进群的消息」在对方那边一条都没有，而本端 SENT、本地落库、
 * 单测全绿。所以这里的判据不是"我们能不能编码出来"，而是**legacy 成员收到的那一帧到底长什么样**。
 *
 * 覆盖：
 *  1. 模拟上游白名单：带扩展键的帧被拒、裁剪后的同一帧被接受（说明门控不是可选项）；
 *  2. 两份变体共用**同一个 id / ts**，只差那两个键；
 *  3. 走真实 [Messenger] 扇出：legacy 成员拿到的帧里没有那两个键、gta1 成员有；
 *  4. 队列里按成员各存一份 JSON（补发时不会把带键的那份塞给 legacy 成员）；
 *  5. 「重发」（[NodeOutbound.replayOutgoing]）走同一条门控，且不会因此产生新 id。
 */
class GroupTextCapabilityGatingTest {

    // ---------------------------------------------------------------- 上游协议的模拟

    /**
     * 上游茶话间 0.61.2 对 `group-text` 的严格字段白名单（`validatePayload`）。
     *
     * 这里**刻意手写**而不是引用本仓常量：本仓的 `GROUP_TEXT_KEYS` 是"我们接受什么"，
     * 这条是"上游接受什么" —— 两者混在一起，这个测试就永远绿了。
     */
    private val upstreamGroupTextKeys = setOf(
        "kind", "text", "groupId", "groupRev", "mentions", "resend", "replyTo",
    )

    private fun upstreamAccepts(payload: JsonObject): Boolean {
        val kind = (payload["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (kind != MsgKinds.GROUP_TEXT) return false
        return payload.keys.all { it in upstreamGroupTextKeys }
    }

    @Test
    fun theUpstreamValidatorDropsTheAttributedFrameAndKeepsThePlainOne() {
        val attributed = Codec.groupTextPayload(
            text = "hi",
            groupId = "grp-1",
            groupRev = 1,
            senderId = "agent:dsh",
            reasoning = "先看 A",
        ).jsonObject
        assertTrue(
            attributed.keys.any { it !in upstreamGroupTextKeys },
            "前提：带扩展键的载荷里真的有白名单外的键，否则这条测试在空转",
        )
        assertFalse(
            upstreamAccepts(attributed),
            "带 senderId/reasoning 的群文本在上游是**整帧丢弃** —— 这正是问题 8 的根因",
        )

        val plain = Codec.groupTextPayload(text = "hi", groupId = "grp-1", groupRev = 1).jsonObject
        assertTrue(upstreamAccepts(plain), "去掉扩展键之后必须在白名单里：$plain")
        // 本端自己的校验两版都接受（我们认识这两个键）—— 所以"本地能收"不能证明"对方能收"。
        assertTrue(Codec.validatePayload(MsgTypes.MSG, attributed, Protocol.TEXT_TCP_LIMIT))
        assertTrue(Codec.validatePayload(MsgTypes.MSG, plain, Protocol.TEXT_TCP_LIMIT))
    }

    @Test
    fun theTwoVariantsShareTheEnvelopeIdAndDifferOnlyInTheExtensionKeys() {
        val variants = groupTextVariants(
            from = "self",
            text = "hi",
            groupId = "grp-1",
            groupRev = 3,
            senderId = "agent:dsh",
            reasoning = "先看 A",
        )
        assertTrue(variants.hasExtensions)
        assertEquals(variants.plain.id, variants.attributed.id, "两个变体必须是同一条消息")
        assertEquals(variants.plain.ts, variants.attributed.ts, "ts 也要一致，否则对端排序会把它当成两条")
        val plain = variants.plain.payload.jsonObject
        val attributed = variants.attributed.payload.jsonObject
        assertEquals(
            setOf("senderId", "reasoning"),
            attributed.keys - plain.keys,
            "两份载荷只许差这两个键；正文/群引用/mentions/replyTo 一个都不能少",
        )
        assertNull(plain["senderId"])
        assertNull(plain["reasoning"])
        assertEquals("hi", (plain["text"] as JsonPrimitive).content)
        assertEquals("grp-1", (plain["groupId"] as JsonPrimitive).content)
    }

    @Test
    fun withoutExtensionKeysBothMembersGetTheVerySameEnvelope() {
        val variants = groupTextVariants(from = "self", text = "hi", groupId = "grp-1", groupRev = 1)
        assertFalse(variants.hasExtensions)
        assertTrue(
            variants.forMember(supportsAttributed = true) === variants.forMember(supportsAttributed = false),
            "没有扩展键时不许凭空造第二份载荷：字节必须完全一样",
        )
    }

    /**
     * 新增的 agent 键（`agentIntent` / `agentState` / `card`）与 senderId/reasoning
     * 走**同一条** `gta1` 门：legacy 成员的帧里一个都不能出现，否则上游整帧拒收；
     * gta1 成员拿到的全量帧则三键齐全。判据是「legacy 收到的字节」，不是「能不能编码」。
     */
    @Test
    fun theNewAgentKeysAreGatedLikeTheOldOnes() = runBlocking {
        val rig = rig(
            mapOf(
                "legacy" to listOf(MsgTypes.CAP_MOBILE_TEXT),
                "modern" to listOf(MsgTypes.CAP_MOBILE_TEXT, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            ),
        )
        rig.start()
        try {
            rig.registry.touch("legacy", "127.0.0.1", 17991, profile("legacy", emptyList(), 20001), profileTs = 1L, confirmed = true)
            rig.registry.touch("modern", "127.0.0.1", 17992, profile("modern", emptyList(), 20002), profileTs = 1L, confirmed = true)

            val intent = kotlinx.serialization.json.buildJsonObject {
                put("agentId", kotlinx.serialization.json.JsonPrimitive("dev-lead"))
            }
            val card = kotlinx.serialization.json.buildJsonObject {
                put("cardId", kotlinx.serialization.json.JsonPrimitive("task-89"))
            }
            val outcome = rig.outbound.sendGroupText(
                text = "派单正文",
                groupId = "grp-1",
                groupRev = 7,
                members = listOf("self", "legacy", "modern"),
                agentIntent = intent,
                agentState = "accept",
                card = card,
            )
            assertEquals(SendOutcome.SENT, outcome)

            val byPort = rig.tcp.frames.associate { (port, envelope) -> port to envelope }
            val legacy = assertNotNull(byPort[20001], "legacy 成员那一帧没发出去")
            val modern = assertNotNull(byPort[20002], "modern 成员那一帧没发出去")
            assertEquals(legacy.id, modern.id, "两个成员收到的必须是同一个信封 id")

            val legacyPayload = legacy.payload.jsonObject
            for (key in listOf("agentIntent", "agentState", "card")) {
                assertNull(legacyPayload[key], "legacy 成员的帧带了 $key ⇒ 上游整帧丢弃")
            }
            assertTrue(upstreamAccepts(legacyPayload), "legacy 成员收到的必须是上游能收的那份：$legacyPayload")

            val modernPayload = modern.payload.jsonObject
            assertTrue(modernPayload.containsKey("agentIntent"), "gta1 成员必须拿到 agentIntent")
            assertEquals("accept", (modernPayload["agentState"] as JsonPrimitive).content)
            assertTrue(modernPayload.containsKey("card"), "gta1 成员必须拿到 card")
        } finally {
            rig.close()
        }
    }

    // ---------------------------------------------------------------- 真实 Messenger 扇出

    private fun profile(nodeId: String, caps: List<String>, tcpPort: Int = 20000) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = tcpPort,
        ver = "relay-test",
        caps = caps,
    )

    /** 记录每个成员的帧：UDP 没人听 ⇒ 一定走到这条兜底，于是能确定"这个端口的成员收到了什么"。 */
    private class RecordingTcpFallback : TcpFallback {
    /**
     * **必须线程安全**：`sendGroupText` 是并发发给每个成员的（fan-out），
     * 而 `mutableListOf` 的 `+=` 在并发下会丢元素 ——
     * 真实现象：同一个用例连跑两次，一次丢 `legacy` 的帧、一次丢 `modern` 的，
     * 失败信息还写着「那一帧没发出去」，看起来像产品缺陷，其实只是夹具丢了。
     *
     * commonTest 不能碰 java.*（iOS 目标也要编译），用协程 Mutex 守住并发追加；
     * 读取都发生在协程 join 之后，happens-before 由 Mutex 与 Job 完成语义保证。
     */
    private val frameMutex = Mutex()
    val frames = mutableListOf<Pair<Int, Envelope>>()

    override suspend fun sendAndAwaitAck(host: String, port: Int, envelope: Envelope): Boolean {
        frameMutex.withLock { frames += port to envelope }
        return true
    }
    }

    private class Rig(
        val outbound: NodeOutbound,
        val messenger: Messenger,
        val registry: PeerRegistry,
        val messageStore: InMemoryMessageStore,
        val queueStore: InMemoryQueueStore,
        val tcp: RecordingTcpFallback,
        val transport: UdpTransport,
        val scope: CoroutineScope,
    ) {
        fun close() {
            messenger.stop()
            transport.stop()
            scope.cancel()
        }
    }

    private fun rig(capsByPeer: Map<String, List<String>>): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self")
        val messageStore = InMemoryMessageStore()
        val queueStore = InMemoryQueueStore()
        val tcp = RecordingTcpFallback()
        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self",
            queueStore = queueStore,
            dedupStore = InMemoryDedupStore(),
            // 快退避：本测试只用 TCP 兜底那条路，UDP 一侧注定没人 ACK。
            timings = Timings(ackRetrySchedule = listOf(10L), tcpAckTimeout = 50L),
            tcpFallback = tcp,
        )
        val outbound = NodeOutbound(
            messenger = messenger,
            messageStore = messageStore,
            selfId = { "self" },
            selfProfile = { profile("self", MsgTypes.MOBILE_CAPS) },
            peerDeclares = { peerId, cap -> capsByPeer[peerId]?.contains(cap) == true },
        )
        return Rig(outbound, messenger, registry, messageStore, queueStore, tcp, transport, scope)
    }

    private suspend fun Rig.start() {
        transport.start()
        messenger.start(scope)
    }

    @Test
    fun theLegacyMemberGetsNoExtensionKeysAndEveryoneSharesOneId() = runBlocking {
        val rig = rig(
            mapOf(
                "legacy" to listOf(MsgTypes.CAP_MOBILE_TEXT),
                "modern" to listOf(MsgTypes.CAP_MOBILE_TEXT, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            ),
        )
        rig.start()
        try {
            // 两个对端都"在线"：UDP 没人听 ⇒ 走 RecordingTcpFallback 记下来。
            rig.registry.touch("legacy", "127.0.0.1", 17991, profile("legacy", emptyList(), 20001), profileTs = 1L, confirmed = true)
            rig.registry.touch("modern", "127.0.0.1", 17992, profile("modern", emptyList(), 20002), profileTs = 1L, confirmed = true)

            val outcome = rig.outbound.sendGroupText(
                text = "agent 转发的正文",
                groupId = "grp-1",
                groupRev = 7,
                members = listOf("self", "legacy", "modern"),
                senderId = "agent:dsh",
                reasoning = "先看 A 再看 B",
            )
            assertEquals(SendOutcome.SENT, outcome, "两个成员都 ACK 了才算 SENT")

            val byPort = rig.tcp.frames.associate { (port, envelope) -> port to envelope }
            val legacy = assertNotNull(byPort[20001], "legacy 成员那一帧没发出去：${rig.tcp.frames.map { it.first }}")
            val modern = assertNotNull(byPort[20002], "modern 成员那一帧没发出去")

            assertEquals(legacy.id, modern.id, "两个成员收到的必须是**同一个信封 id**")

            val legacyPayload = legacy.payload.jsonObject
            assertNull(legacyPayload["senderId"], "legacy 成员的帧带了 senderId ⇒ 茶话间整帧丢弃")
            assertNull(legacyPayload["reasoning"], "legacy 成员的帧带了 reasoning ⇒ 茶话间整帧丢弃")
            assertTrue(upstreamAccepts(legacyPayload), "legacy 成员收到的必须是上游能收的那份：$legacyPayload")

            val modernPayload = modern.payload.jsonObject
            assertEquals("agent:dsh", (modernPayload["senderId"] as JsonPrimitive).content)
            assertEquals("先看 A 再看 B", (modernPayload["reasoning"] as JsonPrimitive).content)

            // 正文/群引用在两个变体里都不许少。
            for (payload in listOf(legacyPayload, modernPayload)) {
                assertEquals("agent 转发的正文", (payload["text"] as JsonPrimitive).content)
                assertEquals("grp-1", (payload["groupId"] as JsonPrimitive).content)
                assertEquals(7, (payload["groupRev"] as JsonPrimitive).content.toInt())
            }

            // 本地只落一条，而且是带扩展键的那份（本地读者是本构建）。
            val rows = rig.messageStore.since("group:grp-1", 0, 10)
            assertEquals(1, rows.size, "群消息在本地历史里只能有一条（按会话键存）")
            assertTrue(rows.single().envelopeJson.contains("senderId"))
        } finally {
            rig.close()
        }
    }

    @Test
    fun theQueueKeepsEachMembersOwnVariant() = runBlocking {
        val rig = rig(
            mapOf(
                "modern" to listOf(MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
                "legacy" to emptyList(),
            ),
        )
        rig.start()
        try {
            // 两个对端都**离线且没有候选端点** ⇒ 不尝试投递，直接入队（真实离线场景）。
            rig.registry.restoreOffline(profile("legacy", emptyList(), 20001), "127.0.0.1", 17991, 1L)
            rig.registry.restoreOffline(profile("modern", emptyList(), 20002), "127.0.0.1", 17992, 1L)

            val outcome = rig.outbound.sendGroupText(
                text = "hi",
                groupId = "grp-1",
                groupRev = 2,
                members = listOf("self", "legacy", "modern"),
                senderId = "agent:dsh",
                reasoning = "think",
            )
            assertEquals(SendOutcome.QUEUED, outcome, "两个成员都不在线 ⇒ QUEUED（入队等补发）")

            // 入队是**每成员各写一行**，慢机器上可能还没写完就读 —— 直接断言 size==2 会在
            // 全量并发时偶发 expected 2 but was 1（实测只在 7 模块并行时出现，单跑恒绿）。
            // 等「两行都到」再断言；超时仍按失败处理，不做静默跳过。
            // 断言必须落在**同一份快照**上：后台重试会成功、随后把行取走，
            // 若等完再读一次，慢机器上就可能读到 1 行（实测全量偶发 expected 2 but was 1）。
            var rows = emptyList<QueuedMessage>()
            awaitTrue(what = "两个成员的队列行没在超时内落齐") {
                rows = rig.queueStore.pendingAll()
                rows.size == 2
            }
            assertEquals(2, rows.size, "队列按 (peerId, msgId) 分行：两个成员两行")
            assertEquals(1, rows.map { it.msgId }.distinct().size, "同一条群消息只能有一个 msgId")
            val byPeer = rows.associateBy { it.peerId }

            val legacyJson = assertNotNull(byPeer["legacy"], "legacy 成员那一行不在队列里：${rows.map { it.peerId }}")
            val modernJson = assertNotNull(byPeer["modern"], "modern 成员那一行不在队列里")
            assertTrue(!legacyJson.envelopeJson.contains("senderId"), "legacy 成员的补发素材必须是裁剪过的：${legacyJson.envelopeJson}")
            assertTrue(modernJson.envelopeJson.contains("agent:dsh"), "gta1 成员的补发素材要保留 senderId")

            // 两份都必须还能被重放路径解码（decodeTcp），否则补发时会被当坏帧删掉。
            assertTrue(Codec.decodeTcp(legacyJson.envelopeJson) is DecodeResult.Ok)
            assertTrue(Codec.decodeTcp(modernJson.envelopeJson) is DecodeResult.Ok)
        } finally {
            rig.close()
        }
    }

    @Test
    fun replayingAGroupMessageKeepsTheGateAndTheOriginalId() = runBlocking {
        val rig = rig(
            mapOf(
                "legacy" to emptyList(),
                "modern" to listOf(MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            ),
        )
        rig.start()
        try {
            rig.registry.touch("legacy", "127.0.0.1", 17991, profile("legacy", emptyList(), 20001), profileTs = 1L, confirmed = true)
            rig.registry.touch("modern", "127.0.0.1", 17992, profile("modern", emptyList(), 20002), profileTs = 1L, confirmed = true)

            val originalId = "0f8fad5b-d9cb-469f-a165-70867728950e"
            val outcome = rig.outbound.replayOutgoing(
                OutgoingReplay(
                    peerId = "group:grp-1",
                    msgId = originalId,
                    text = "重发的正文",
                    groupId = "grp-1",
                    groupRev = 9,
                    members = listOf("self", "legacy", "modern"),
                    senderId = "agent:dsh",
                    reasoning = "think",
                ),
            )
            assertEquals(SendOutcome.SENT, outcome)

            val byPort = rig.tcp.frames.associate { (port, envelope) -> port to envelope }
            val legacy = assertNotNull(byPort[20001])
            val modern = assertNotNull(byPort[20002])
            assertEquals(originalId, legacy.id, "重发必须沿用原 id，新 id = 对方多一条")
            assertEquals(originalId, modern.id)
            assertNull(legacy.payload.jsonObject["senderId"], "重发时 legacy 成员同样不能带扩展键")
            assertEquals("agent:dsh", (modern.payload.jsonObject["senderId"] as JsonPrimitive).content)
            for (envelope in listOf(legacy, modern)) {
                assertEquals(true, (envelope.payload.jsonObject["resend"] as JsonPrimitive).content.toBoolean())
            }
            assertEquals(
                0,
                rig.messageStore.since("group:grp-1", 0, 10).size,
                "重发不许再写一条本地历史：那条消息当初已经写过（再写就是本地多一条）",
            )
        } finally {
            rig.close()
        }
    }

    /** 重放路径与发送路径同一条门：卡片随原 id 重放，legacy 成员拿到的仍是无键那份。 */
    @Test
    fun theReplayCarriesTheCardThroughTheSameGate() = runBlocking {
        val rig = rig(
            mapOf(
                "legacy" to emptyList(),
                "modern" to listOf(MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED),
            ),
        )
        rig.start()
        try {
            rig.registry.touch("legacy", "127.0.0.1", 17991, profile("legacy", emptyList(), 20001), profileTs = 1L, confirmed = true)
            rig.registry.touch("modern", "127.0.0.1", 17992, profile("modern", emptyList(), 20002), profileTs = 1L, confirmed = true)

            val originalId = "0f8fad5b-d9cb-469f-a165-70867728950f"
            val card = kotlinx.serialization.json.buildJsonObject {
                put("cardId", kotlinx.serialization.json.JsonPrimitive("task-89"))
                put("rev", kotlinx.serialization.json.JsonPrimitive(4))
            }
            val outcome = rig.outbound.replayOutgoing(
                OutgoingReplay(
                    peerId = "group:grp-1",
                    msgId = originalId,
                    text = "补发的卡片消息",
                    groupId = "grp-1",
                    groupRev = 9,
                    members = listOf("self", "legacy", "modern"),
                    card = card,
                    agentState = "completed",
                ),
            )
            assertEquals(SendOutcome.SENT, outcome)

            val byPort = rig.tcp.frames.associate { (port, envelope) -> port to envelope }
            val legacy = assertNotNull(byPort[20001])
            val modern = assertNotNull(byPort[20002])
            assertEquals(originalId, legacy.id, "重放必须沿用原 id")
            assertEquals(originalId, modern.id)
            assertNull(legacy.payload.jsonObject["card"], "legacy 成员的重放帧同样不能带卡片")
            assertNull(legacy.payload.jsonObject["agentState"], "legacy 成员的重放帧同样不能带状态")
            assertEquals("completed", (modern.payload.jsonObject["agentState"] as JsonPrimitive).content)
            assertTrue(modern.payload.jsonObject.containsKey("card"), "gta1 成员的重放帧必须带卡片")
        } finally {
            rig.close()
        }
    }
}
