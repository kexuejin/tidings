// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.ScanRangeSummary
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.kapp.tidings.core.testutil.awaitAfterSend
import kotlinx.coroutines.cancel
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
            // 重发节奏与硬超时统一交给 testutil 的 awaitAfterSend（不再自己数次数）。
            awaitAfterSend(
                what = "发现层没把入站 scan-ranges 递上来（应用层那段处理代码就是死的）",
                send = {
                    send(
                        MsgTypes.SCAN_RANGES,
                        Codec.scanRangesPayload(listOf(ScanRangeSummary(cidr = "10.99.195.0/24", addedAt = 1L))),
                    )
                },
            ) { got.any { it.type == MsgTypes.SCAN_RANGES } }

            // 递上来的那一帧必须是应用层**能用**的：载荷能解回同一条网段。
            val envelope = got.first { it.type == MsgTypes.SCAN_RANGES }
            assertEquals("node-b", envelope.from)
            assertEquals(
                listOf("10.99.195.0/24"),
                Codec.decodeScanRanges(envelope.payload)?.map { it.cidr },
                "递上来的 scan-ranges 载荷解不出网段 —— 投递对了、形状不对",
            )

            // 负控：`peers` 仍然是有意不处理的（我们不做节点表散播）。
            // **不用 sleep 400ms 赌它已经处理完**：在 PEERS 之后再发一帧我们确定会被递上来的
            // scan-ranges，等它到达 —— 同一条 UDP 流是有序的，那一帧到了就说明 PEERS 早已被
            // 处理过（被丢掉也该被丢掉了）。这样"没有 peers"才是确定的结论，而不是"还没到"。
            val scanRangesBefore = got.count { it.type == MsgTypes.SCAN_RANGES }
            send(MsgTypes.PEERS, JsonObject(emptyMap()))
            awaitAfterSend(
                what = "后续 scan-ranges 没被递上来，无法证明 peers 那一帧已被处理",
                send = {
                    send(
                        MsgTypes.SCAN_RANGES,
                        Codec.scanRangesPayload(listOf(ScanRangeSummary(cidr = "10.99.195.0/24", addedAt = 1L))),
                    )
                },
            ) { got.count { it.type == MsgTypes.SCAN_RANGES } > scanRangesBefore }
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
}
