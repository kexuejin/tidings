// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.TcpFrame
import com.kapp.tidings.core.protocol.TcpFrameTypes
import com.kapp.tidings.core.protocol.TcpFrames
import com.kapp.tidings.core.protocol.makeEnvelope
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TCP 长文本（> [com.kapp.tidings.core.protocol.Protocol.UDP_MAX_PAYLOAD] 字节）这一条路的两个契约。
 *
 * 为什么单独立一个测试：这条路的失败**在两端都是静默的**。发送方等不到 `msg-ack` 就
 * 判定失败并把消息塞进队列，UI 上只是「已排队」——看起来像对端离线；接收方
 * `receiveMessage` 返回 false 时只回一个 `err`，不写日志。2026-10-04 真机上就发生过：
 * 对端明明在线、TCP 端口也对，长消息却全部进了队列。
 *
 * 两个断言各钉一件事：
 * 1. **收到 `msg` 帧必须回 `msg-ack`**，否则发送方永远认为没送到；
 * 2. **回调拿到的地址是裸主机名**，因为 `PeerRegistry` 里存的就是这个形状
 *    （`record.ip == "192.168.2.22"`）。这里若递过去 `socket.remoteAddress.toString()`
 *    那种带斜杠与端口的写法，`Messenger.acceptTcpMessage` 的地址比对必然不等，
 *    于是每一帧都被当成「地址漂移」拒收。
 */
class TcpMessageAckTest {

    private class CapturingLookup : OutgoingLookup {
        val seenAddresses = mutableListOf<String>()

        override fun resolve(transferId: String, fileId: String): OutgoingFile? = null

        override suspend fun receiveMessage(envelope: Envelope, remoteAddress: String): Boolean {
            seenAddresses += remoteAddress
            return true
        }
    }

    @Test
    fun tcpMessageIsAckedAndTheCallbackSeesABareHost() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = CapturingLookup()
        val server = TransferServer(scope, lookup, bindAddress = "127.0.0.1")
        val selector = SelectorManager(Dispatchers.Default)
        try {
            assertTrue(server.start(0), "监听器没绑上 127.0.0.1:0")
            val port = server.boundPort ?: error("TransferServer 已启动却没有 boundPort")

            val envelope = makeEnvelope(
                type = MsgTypes.MSG,
                from = PEER_ID,
                // 2000 字节：超过 UDP 上限（1200，走 TCP），又低于 TCP 文本上限（4096）。
                payload = Codec.textPayload("x".repeat(2_000)),
                id = "msg-long-1",
            )

            // 别忘了 4 字节长度前缀：少了它，服务端会把 JSON 头四个字节当成帧长。
            val frame = TcpFrame.encode(TcpFrames.encodeMsgFrame(envelope).encodeToByteArray())
            val reply = exchange(selector, port, frame)
            assertNotNull(reply, "对端没有回帧：发送方会判为失败并把消息塞进队列（真机症状是「已排队」）")
            assertTrue(
                reply.contains("\"${TcpFrameTypes.MSG_ACK}\""),
                "回的帧不是 msg-ack，发送方照样会判失败：$reply",
            )
            assertTrue(reply.contains("msg-long-1"), "msg-ack 里没有 ackFor：$reply")

            assertEquals(
                listOf("127.0.0.1"),
                lookup.seenAddresses,
                "receiveMessage 应当拿到裸主机名 —— PeerRegistry 里存的就是这个形状，" +
                    "带斜杠/端口的写法会让地址比对必然不等，每一帧都被当成地址漂移拒收",
            )
        } finally {
            server.stop()
            selector.close()
            scope.cancel()
        }
    }

    /** 连上去、发一帧、把对端回的第一帧读回来（没有回帧就返回 null）。 */
    private suspend fun exchange(selector: SelectorManager, port: Int, frameBytes: ByteArray): String? {
        val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", port))
        try {
            val write = socket.openWriteChannel(autoFlush = true)
            val read = socket.openReadChannel()
            write.writeFully(frameBytes)

            val header = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
            val gotHeader = withTimeoutOrNull(5_000L) {
                read.readFully(header, 0, TcpFrame.LENGTH_PREFIX_BYTES)
                true
            } ?: return null
            if (!gotHeader) return null

            val len = TcpFrame.decodeLength(header)
            if (len <= 0) return null
            val frame = ByteArray(len)
            withTimeoutOrNull(5_000L) { read.readFully(frame, 0, len) } ?: return null
            return frame.decodeToString()
        } finally {
            socket.close()
        }
    }

    private companion object {
        const val PEER_ID = "22222222-2222-4222-8222-222222222222"
        const val SELF_ID = "11111111-1111-4111-8111-111111111111"
    }

    // ------------------------------------------------- TransferManager 的接线

    /**
     * 这一条钉的是 [TransferManager] 里那个匿名 `OutgoingLookup` 的**接线**，不是监听器本身。
     *
     * 它值得单独存在，因为那里的错误形态是「重写体里不加限定地调用同名成员」——
     * 名字解析到重写自己 → 无限递归 → `StackOverflowError` → 被 `TransferServer` 的
     * `runCatching` 咽掉 → 回 `err bad-msg`。整个链条上**没有任何一处会报错**，
     * 单看监听器或单看 Messenger 都测不出来，只有"真的发一帧进去看有没有回执"能钉住。
     */
    @Test
    fun transferManagerForwardsTcpMessagesInsteadOfRecursingIntoItself() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selector = SelectorManager(Dispatchers.Default)
        val delivered = mutableListOf<String>()
        val manager = TransferManager(
            selfId = SELF_ID,
            scope = scope,
            tcpPort = 0,
            downloadDir = { "/tmp/relay-ack-test" },
            sendReliable = { _, _ -> false },
            peerAddress = { null },
            receiveMessage = { envelope, remoteAddress ->
                delivered += "$remoteAddress|${envelope.id}"
                true
            },
            bindAddress = "127.0.0.1",
        )
        try {
            assertTrue(manager.start(), "TransferManager 没绑上 127.0.0.1:0")
            val port = manager.boundPort ?: error("TransferManager 已启动却没有 boundPort")

            val envelope = makeEnvelope(
                type = MsgTypes.MSG,
                from = PEER_ID,
                payload = Codec.textPayload("y".repeat(2_000)),
                id = "msg-wired-1",
            )
            val frame = TcpFrame.encode(TcpFrames.encodeMsgFrame(envelope).encodeToByteArray())
            val reply = exchange(selector, port, frame)

            assertNotNull(reply, "没有回帧：说明这一帧在 TransferManager 内部就死了")
            assertTrue(
                reply.contains("\"${TcpFrameTypes.MSG_ACK}\""),
                "回的帧不是 msg-ack（多半是 `err bad-msg`，即回调抛异常被咽掉）：$reply",
            )
            assertEquals(listOf("127.0.0.1|msg-wired-1"), delivered)
        } finally {
            manager.stop()
            selector.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------- 地址形状（纯函数，不开 socket）

    /**
     * 形状全部取自真实 `SocketAddress.toString()` 家族。第一行是 2026-10-04 在
     * `127.0.0.1:55106` 上实测到的原文。
     */
    @Test
    fun remoteAddressShapesAreReducedToABareHost() {
        val cases = listOf(
            "/127.0.0.1:55106" to "127.0.0.1",
            "127.0.0.1:55106" to "127.0.0.1",
            "/192.168.2.22" to "192.168.2.22",
            "192.168.2.22" to "192.168.2.22",
            "/desktop-node.local/192.168.2.22:55106" to "192.168.2.22",
            "/[::1]:55106" to "::1",
            "/[::ffff:192.168.2.22]:55106" to "192.168.2.22",
            " /10.0.0.7:1 " to "10.0.0.7",
            // Kotlin/Native 的形状。**这两条是 iOS 测试目标逼出来的**：只处理 JVM 的
            // `/1.2.3.4:5678` 时，iOS 上每一帧 TCP 文本仍然会被当成「地址漂移」拒收 ——
            // 正是这个函数要修的缺陷，在另一个平台上原样复现。
            "InetSocketAddress(127.0.0.1:55106)" to "127.0.0.1",
            "InetSocketAddress(192.168.2.22:55106)" to "192.168.2.22",
            "InetSocketAddress(/192.168.2.22:55106)" to "192.168.2.22",
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, hostOfRemoteAddress(raw), "地址形状：$raw")
        }
    }
}
