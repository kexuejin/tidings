// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.ScanRangeSummary
import app.relay.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gossip 帧（`scan-ranges`）到底有没有送到应用层。
 *
 * 为什么要单独立一个测试：这条路上有**两个**丢帧点，任何一处生效都会让
 * `RelayControllerMessages` 里那段 `handleOnScanRangesReceived` 变成不可达代码 ——
 * 对端分享过来的网段永远不被吸收、也不再回发：
 *   1. `Messenger.onDatagram` 只投递 `MsgTypes.RELIABLE` 与 ACK，而 `scan-ranges`
 *      是 gossip（不在可靠集里）；
 *   2. `Discovery.onDatagram` 曾把它和 `peers` 一起写成"认得、有意不处理"。
 *
 * 2026-10-10 的真机读数就是这么暴露的：夹具发了 `10.99.195.0/24`，手机落盘的
 * `settings.json` 里始终只有它自己手动加的那一条。所以这条测试直接钉住发现层的投递：
 * `scan-ranges` 必须递上来，`peers` 必须仍然不递（负控 —— 少了它，把两个类型一起放行
 * 也能"通过"）。
 */
class DiscoveryGossipDeliveryTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = "n",
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = emptyList(),
    )

    @Test
    fun scanRangesReachesTheAppLayerWhilePeersStaysDropped() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()

        val got = mutableListOf<Envelope>()
        val discoveryA = Discovery(
            transport = transportA,
            registry = PeerRegistry("node-a"),
            selfProfile = { profile("node-a") },
            onGossip = { got += it },
        )
        discoveryA.start(scope)

        suspend fun send(type: String, payload: kotlinx.serialization.json.JsonElement) {
            transportB.send(
                Codec.encodeToBytes(makeEnvelope(type, "node-b", payload)),
                "127.0.0.1",
                transportA.boundPort,
            )
        }

        try {
            // **重发**而不是只发一次：SharedFlow 没有 replay，订阅之前 emit 的帧会被直接丢掉
            // （不是缓冲），而 Discovery.start 是 scope.launch 异步订阅的 —— 单跑时调度来得及，
            // 全量跑（负载高、launch 晚一步）时会偶发超时（2026-10-18 全量跑红过两次、单跑绿）。
            // 每 20ms 重发一次直到收到，这条断言就变成确定性的，不靠 sleep 猜。
            var delivered = false
            repeat(150) {
                if (delivered) return@repeat
                send(
                    MsgTypes.SCAN_RANGES,
                    Codec.scanRangesPayload(listOf(ScanRangeSummary(cidr = "10.99.195.0/24", addedAt = 1L))),
                )
                delay(20)
                delivered = got.any { it.type == MsgTypes.SCAN_RANGES }
            }
            assertTrue(delivered, "发现层没把入站 scan-ranges 递上来（应用层那段处理代码就是死的）")

            // 递上来的那一帧必须是应用层**能用**的：载荷能解回同一条网段。
            val envelope = got.first { it.type == MsgTypes.SCAN_RANGES }
            assertEquals("node-b", envelope.from)
            assertEquals(
                listOf("10.99.195.0/24"),
                Codec.decodeScanRanges(envelope.payload)?.map { it.cidr },
                "递上来的 scan-ranges 载荷解不出网段 —— 投递对了、形状不对",
            )

            // 负控：`peers` 仍然是有意不处理的（我们不做节点表散播）。
            send(MsgTypes.PEERS, JsonObject(emptyMap()))
            delay(400)
            assertTrue(
                got.none { it.type == MsgTypes.PEERS },
                "peers 被递到应用层了 —— 它属于「认得、有意不处理」，不该出现在入站流里",
            )
        } finally {
            discoveryA.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }

    /** 轮询到条件成立为止；超时就带着 [what] 断言失败（比挂死或裸超时好读）。 */
    private suspend fun awaitTrue(timeoutMs: Long = 3_000, what: String, condition: () -> Boolean) {
        repeat((timeoutMs / 20).toInt()) {
            if (condition()) return
            delay(20)
        }
        assertTrue(condition(), what)
    }
}
