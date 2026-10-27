// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * 加密信道数据面第 2/3 步的验收：窗口发送 + 连续前缀 ACK + 丢块重传。
 *
 * 两条真实 SecureChannelTransport(回环)，接收端**故意丢掉一个块**，
 * 发送端必须靠 ACK 超时从已确认位置重传，最终字节完全一致。
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class SecureChunkSenderTest {

    private fun profile(id: String) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "jvm", tcpPort = 0, ver = "1", caps = emptyList(),
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun windowedSendSurvivesOneLostChunk() = runBlocking {
        val hostPort = freePort()
        val phonePort = freePort()
        val host = LuciolaTransportFactory(profile("host"), hostPort, seedPersist = { "11".repeat(32) })
        val phone = LuciolaTransportFactory(profile("phone"), phonePort, seedPersist = { "22".repeat(32) })
        phone.pairPeer(
            "127.0.0.1", hostPort, host.publicKey,
            secret = kotlin.io.encoding.Base64.UrlSafe.decode(host.pairSecretBase64()),
        )
        val hostRegistry = PeerRegistry("host").apply { touch("phone", "127.0.0.1", phonePort, profile("phone")) }
        val phoneRegistry = PeerRegistry("phone").apply { touch("host", "127.0.0.1", hostPort, profile("host")) }
        val hostTransport = host.createTransport(hostRegistry)
        val phoneTransport = phone.createTransport(phoneRegistry)
        hostTransport.start()
        phoneTransport.start()

        val transferId = "t-lost-chunk"
        val chunkSize = 64 * 1024
        val payload = ByteArray(chunkSize * 5 + 1234) { (it * 7 % 251).toByte() }
        val received = ByteArray(payload.size)
        val seen = BooleanArray(payload.size)
        var contiguous = 0L
        val ackChannel = Channel<Long>(Channel.CONFLATED)
        val dropOnce = AtomicBoolean(false)

        try {
            // 握手+配对是异步的:全量并发跑时 10s 会偶发不够(实测过一次)。
            // 这里用"一直重发到主机信任"的判据,而不是单次调用 + 固定等待。
            withTimeout(25_000) {
                while (!phoneTransport.requestPairing("127.0.0.1", hostPort, "phone")) delay(50)
            }
            withTimeout(25_000) { phoneTransport.pairingResults.first() }

            // 接收端:按 offset 落盘 + 连续前缀 ACK;故意丢一块(offset = chunkSize)的第一次。
            val receiver = launch {
                hostTransport.incoming.collect { datagram ->
                    val chunk = FileChunkFrame.decode(datagram.bytes) ?: return@collect
                    if (chunk.transferId != transferId) return@collect
                    if (chunk.offset == chunkSize.toLong() && dropOnce.compareAndSet(false, true)) return@collect
                    System.arraycopy(chunk.payload, 0, received, chunk.offset.toInt(), chunk.payload.size)
                    chunk.payload.indices.forEach { k -> seen[chunk.offset.toInt() + k] = true }
                    var advanced = false
                    while (contiguous < payload.size && seen[contiguous.toInt()]) {
                        contiguous++
                        advanced = true
                    }
                    if (advanced) {
                        val ack = FileChunkAck.encode(transferId, contiguous)
                        hostTransport.send(ack, "127.0.0.1", phonePort)
                        ackChannel.trySend(contiguous)
                    }
                }
            }
            // 手机侧:把 ACK 解码后转给发送器。
            val ackCollector = launch {
                phoneTransport.incoming.collect { datagram ->
                    val ack = FileChunkAck.decode(datagram.bytes) ?: return@collect
                    if (ack.transferId == transferId) ackChannel.trySend(ack.ackedUpTo)
                }
            }

            val sent = SecureChunkSender(
                chunkSize = chunkSize,
                windowChunks = 8,
                ackTimeoutMs = 700,
            ).send(
                transferId = transferId,
                payload = payload,
                host = "127.0.0.1",
                port = hostPort,
                send = { bytes, h, p -> phoneTransport.send(bytes, h, p) },
                acks = { withTimeoutOrNull(2_000) { ackChannel.receive() } },
            )
            assertTrue(sent >= payload.size, "必须全部送达,实际 " + sent)
            assertContentEquals(payload, received, "丢块后必须靠重传补齐且顺序正确")
            assertTrue(dropOnce.get(), "测试必须真的丢过一次块")

            receiver.cancel()
            ackCollector.cancel()
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }
}
