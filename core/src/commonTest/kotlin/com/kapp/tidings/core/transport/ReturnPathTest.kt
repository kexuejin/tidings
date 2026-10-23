// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.DecodeResult
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.Timings
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task-28 的**回程**：对端换回局域网之后，本机必须能自己爬回来。
 *
 * 现场是：主机停在手机的运营商地址上、online=false，而反向的 entry→alive（UDP 单播）
 * 那一跳没接上，于是队列只涨不降、永远不自愈。修法不是放宽漂移规则，而是
 * **把「刚从这个地址收到过它的报文」记进候选池**，让出站重试（离线但有候选也试）打过去 ——
 * 对方的 ACK 再把当前地址翻正。下面两条分别钉住这两半。
 */
class ReturnPathTest {

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

    private suspend fun awaitTrue(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(20) }
    }

    @Test
    fun aBarePresenceFromANewAddressIsRememberedWithoutChangingTheCurrentAddress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hostTransport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val phoneTransport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        hostTransport.start()
        phoneTransport.start()
        val registry = PeerRegistry("host-node")
        // 现场：registry 停在运营商地址、且已判离线。
        registry.touch("phone-node", "2001:db8:2020:8494::9", 17878, profile("phone-node"), 1_000L, confirmed = true)
        registry.markOffline("phone-node")
        val discovery = Discovery(
            transport = hostTransport,
            registry = registry,
            selfProfile = { profile("host-node") },
            timings = Timings(sweepInterval = 60_000),
            broadcastHost = "127.0.0.1",
            broadcastPort = 65534,
        )
        discovery.start(scope)
        try {
            // 手机从**新地址**发一条不带资料的 presence（漂移规则会拒绝改写当前地址）。
            val presence = makeEnvelope(MsgTypes.PRESENCE, "phone-node", Codec.presencePayload(1, 0))
            phoneTransport.send(Codec.encodeToBytes(presence), "127.0.0.1", hostTransport.boundPort)

            awaitTrue("源地址必须被记进候选池") {
                registry.candidatesOf("phone-node").any { it.host == "127.0.0.1" && it.port == phoneTransport.boundPort }
            }
            // 而当前地址**不变**：漂移规则没有被放宽。
            assertEquals("2001:db8:2020:8494::9", registry.get("phone-node")!!.ip)
        } finally {
            discovery.stop()
            hostTransport.stop()
            phoneTransport.stop()
            scope.cancel()
        }
    }

    @Test
    fun anOfflinePeerWithARememberedSourceDrainsItsQueueOverUdp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hostTransport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val phoneTransport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        hostTransport.start()
        phoneTransport.start()
        val registry = PeerRegistry("host-node")
        val queue = InMemoryQueueStore()
        val messenger = Messenger(
            transport = hostTransport,
            registry = registry,
            selfNodeId = "host-node",
            queueStore = queue,
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(1_000L), tcpAckTimeout = 200L),
            queueRetryIntervalMs = 60_000L,
        )
        messenger.start(scope)

        // 现场：主机停在手机的运营商地址、判离线。
        registry.touch("phone-node", "2001:db8:2020:8494::9", 17878, profile("phone-node"), 1_000L, confirmed = true)
        registry.markOffline("phone-node")
        // 手机的 presence 已经到达（Discovery.onDatagram 会做这件事），源地址进了候选池。
        registry.rememberSource("phone-node", "127.0.0.1", phoneTransport.boundPort)

        // 手机侧：收到 msg 就回 ACK（"对方活着"的信号）。
        val phone = scope.launch {
            phoneTransport.incoming.collect { datagram ->
                val decoded = Codec.decode(datagram.bytes)
                if (decoded is DecodeResult.Ok && decoded.env.type == MsgTypes.MSG) {
                    val ack = makeEnvelope(MsgTypes.ACK, "phone-node", Codec.ackPayload(decoded.env.id))
                    phoneTransport.send(Codec.encodeToBytes(ack), "127.0.0.1", hostTransport.boundPort)
                }
            }
        }
        try {
            val envelope = makeEnvelope(MsgTypes.MSG, "host-node", Codec.textPayload("回程自愈"))
            assertEquals(SendOutcome.SENT, messenger.sendUserMessage("phone-node", envelope))

            // ACK 之后当前地址被翻正 —— 验收里的「ip 变回局域网、online=true」。
            awaitTrue("ACK 之后当前地址必须翻正") {
                registry.get("phone-node")!!.ip == "127.0.0.1" && registry.get("phone-node")!!.online
            }
            assertTrue(queue.listByPeer("phone-node").isEmpty(), "队列必须清空")
        } finally {
            phone.cancel()
            messenger.stop()
            hostTransport.stop()
            phoneTransport.stop()
            scope.cancel()
        }
    }
}
