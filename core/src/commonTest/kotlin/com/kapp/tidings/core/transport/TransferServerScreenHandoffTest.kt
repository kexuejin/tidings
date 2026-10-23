// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.ScreenTcpFrame
import com.kapp.tidings.core.protocol.TcpFrame
import com.kapp.tidings.core.protocol.TcpFrameTypes
import com.kapp.tidings.core.protocol.TcpFrames
import com.kapp.tidings.core.protocol.makeEnvelope
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `screen-open` 首帧交接这一条路的两个契约。
 *
 * 为什么单独立一个测试：这条路上**两个 bug 都是静默的**，而且叠在一起时症状完全一样
 * ——「对端连上了、却永远等不到 `screen-ready`，最后报连接超时/断开」。2026-10-09 的
 * 真机 E2E 就是这样：茶话间发起协助，手机同意并完成系统授权，TCP 也确实连上了
 * （手机侧能抓到来自 192.168.2.82 的 TIME_WAIT），画面却始终起不来。
 *
 * 两个断言各钉一件事：
 * 1. **首帧 `screen-open` 必须真的调到 [OutgoingLookup.openScreen]**。原实现的
 *    `serve` 先 `firstFrame = false` 再判 `if (firstFrame && …)`，条件恒为 false，
 *    `openScreen` 永远不被调用（TS 参考实现是在判断**之后**才清标志）。
 * 2. **`openScreen` 返回 true 后，[TransferServer] 不得再关这条 socket**。所有权已经
 *    交给投屏处理器；原实现无条件 `socket.close()`，于是 `ScreenSender` 一启动就断。
 *    与此同时**仍要把关掉的 socket 从连接表里摘掉**（否则每个投屏会话泄漏一条死连接，
 *    连接数最终顶到 `maxConnections` 就再也接不进新连接）。
 */
class TransferServerScreenHandoffTest {

    /**
     * 记录 [openScreen] 被调用的次数与参数；`handled` 决定返回值；
     * `onOpenScreen` 让用例扮演投屏处理器（[com.kapp.tidings.core.transport.ScreenSender] 的替身）。
     *
     * `onOpenScreen` 拿到的是监听器交出来的**已绑定**通道 —— 必须直接用它们。
     * 自己再 `socket.openWriteChannel()` 会抛
     * `IllegalStateException("writing channel has already been set")`，
     * 这正是 2026-10-09 真机 E2E 的第三个根因。
     */
    private class ScreenLookup(
        var handled: Boolean = true,
        val onOpenScreen: suspend (Socket, ByteReadChannel, ByteWriteChannel, ScreenTcpFrame.Open) -> Unit =
            { _, _, _, _ -> },
    ) : OutgoingLookup {
        val openCalls = mutableListOf<Triple<String, String, String>>()
        val hosts = mutableListOf<String>()

        override fun resolve(transferId: String, fileId: String): OutgoingFile? = null

        override suspend fun receiveMessage(envelope: Envelope, remoteAddress: String): Boolean = true

        override suspend fun openScreen(
            socket: Socket,
            open: ScreenTcpFrame.Open,
            remoteAddress: String,
            read: ByteReadChannel,
            write: ByteWriteChannel,
        ): Boolean {
            openCalls += Triple(open.from, open.sessionId, open.token)
            hosts += remoteAddress
            if (handled) onOpenScreen(socket, read, write, open)
            return handled
        }
    }

    @Test
    fun firstFrameScreenOpenReachesTheHandlerAndKeepsTheSocket() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selector = SelectorManager(Dispatchers.Default)
        // 处理器在被接管的通道上回一帧 screen-ready —— 这正是 ScreenSender 的起步动作。
        val lookup = ScreenLookup(handled = true) { _, _, write, open ->
            write.writeFully(TcpFrames.encode(ScreenTcpFrame.Ready(open.sessionId)))
        }
        val server = TransferServer(scope, lookup, bindAddress = "127.0.0.1")
        try {
            assertTrue(server.start(0), "监听器没绑上 127.0.0.1:0")
            val port = server.boundPort ?: error("TransferServer 已启动却没有 boundPort")

            val client = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", port))
            try {
                val write = client.openWriteChannel(autoFlush = true)
                val read = client.openReadChannel()
                write.writeFully(TcpFrames.encode(ScreenTcpFrame.Open(from = PEER_ID, sessionId = SID, token = TOKEN)))

                val reply = readFrameOrNull(read)
                assertNotNull(
                    reply,
                    "对端没有回 screen-ready：说明首帧 screen-open 根本没交到 openScreen " +
                        "（原实现先清 firstFrame 再判断，条件恒假），或者 socket 已被服务端关掉",
                )
                assertTrue(reply.contains(TcpFrameTypes.SCREEN_READY), "回的帧不是 screen-ready：$reply")
                assertTrue(reply.contains(SID), "screen-ready 里的 sessionId 不对：$reply")

                assertEquals(
                    listOf(Triple(PEER_ID, SID, TOKEN)),
                    lookup.openCalls,
                    "openScreen 必须恰好被调用一次，且拿到首帧里的 from/sessionId/token",
                )
                assertEquals(listOf("127.0.0.1"), lookup.hosts, "openScreen 应拿到裸主机名")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
            selector.close()
            scope.cancel()
        }
    }

    @Test
    fun unhandledScreenOpenIsRejectedAndTheSocketIsClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selector = SelectorManager(Dispatchers.Default)
        // handled = false 等价于 TS 的 openScreen 返回 null ⇒ 服务端 destroy。
        val lookup = ScreenLookup(handled = false)
        val server = TransferServer(scope, lookup, bindAddress = "127.0.0.1")
        try {
            assertTrue(server.start(0))
            val port = server.boundPort ?: error("no boundPort")

            val client = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", port))
            try {
                val write = client.openWriteChannel(autoFlush = true)
                val read = client.openReadChannel()
                write.writeFully(TcpFrames.encode(ScreenTcpFrame.Open(from = PEER_ID, sessionId = SID, token = TOKEN)))

                // 先等对端断链，再断言 —— 读到 null 就意味着服务端已经走完
                // 「调 openScreen → 返回 false → 关 socket」这条路径，
                // 反过来写就会撞上「断言跑在服务端协程之前」的竞态。
                assertNull(
                    readFrameOrNull(read),
                    "没人接管的 screen-open 必须断链（回 null 表示对端关闭）",
                )
                assertEquals(1, lookup.openCalls.size, "openScreen 至少要被问过一次")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
            selector.close()
            scope.cancel()
        }
    }

    @Test
    fun screenOpenThatIsNotTheFirstFrameIsNeverHandedOff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selector = SelectorManager(Dispatchers.Default)
        val lookup = ScreenLookup(handled = true)
        val server = TransferServer(scope, lookup, bindAddress = "127.0.0.1")
        try {
            assertTrue(server.start(0))
            val port = server.boundPort ?: error("no boundPort")

            val client = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", port))
            try {
                val write = client.openWriteChannel(autoFlush = true)
                val read = client.openReadChannel()

                // 先发一帧普通 msg 把「首帧」用掉（服务端会回 msg-ack）。
                val envelope = makeEnvelope(
                    type = MsgTypes.MSG,
                    from = PEER_ID,
                    payload = Codec.textPayload("x".repeat(2_000)),
                    id = "msg-handoff-1",
                )
                write.writeFully(TcpFrame.encode(TcpFrames.encodeMsgFrame(envelope).encodeToByteArray()))
                val ack = readFrameOrNull(read)
                assertNotNull(ack, "普通 msg 帧没回 ack，前置条件不成立")
                assertTrue(ack.contains(TcpFrameTypes.MSG_ACK), "前置条件不成立：$ack")

                // 第二帧才发 screen-open：它不是首帧，绝不能交给投屏处理器。
                write.writeFully(TcpFrames.encode(ScreenTcpFrame.Open(from = PEER_ID, sessionId = SID, token = TOKEN)))
                assertNull(readFrameOrNull(read), "第二帧 screen-open 应断链")
                assertTrue(
                    lookup.openCalls.isEmpty(),
                    "只有首帧 screen-open 才允许交接，非首帧却调用了 openScreen：${lookup.openCalls}",
                )
            } finally {
                client.close()
            }
        } finally {
            server.stop()
            selector.close()
            scope.cancel()
        }
    }

    @Test
    fun handedOffSocketLeavesTheConnectionTableWhenItCloses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selector = SelectorManager(Dispatchers.Default)
        val lookup = ScreenLookup(handled = true) { socket, read, write, open ->
            // 完整照搬 ScreenSender 的生命周期：先回 screen-ready，再等对端关闭，
            // 一旦读到 EOF 就自己收尾（真机里这是 `end("disconnected")` → `stop()`）。
            // 只写不读的替身会让 socketContext 永远不结束，从而把「关闭后摘出连接表」
            // 这条被测行为挡在门外 —— 那正是本用例要验的东西。
            write.writeFully(TcpFrames.encode(ScreenTcpFrame.Ready(open.sessionId)))
            runCatching {
                while (!read.isClosedForRead) {
                    if (read.awaitContent(1) == false) break
                }
            }
            runCatching { socket.close() }
        }
        // 上限压到 1：这样只要被接管的 socket 还留在连接表里，下一条连接就进不来。
        val server = TransferServer(
            scope,
            lookup,
            bindAddress = "127.0.0.1",
            limits = TransferServerLimits(maxConnections = 1),
        )
        try {
            assertTrue(server.start(0))
            val port = server.boundPort ?: error("no boundPort")

            // 第一条：走投屏交接，然后由客户端关掉。
            val first = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", port))
            val firstWrite = first.openWriteChannel(autoFlush = true)
            val firstRead = first.openReadChannel()
            firstWrite.writeFully(TcpFrames.encode(ScreenTcpFrame.Open(from = PEER_ID, sessionId = SID, token = TOKEN)))
            assertNotNull(readFrameOrNull(firstRead), "第一条连接没能完成投屏交接")
            first.close()

            // 给清理协程一点时间（它在 socketContext 结束后才把 socket 摘出集合）。
            delay(500)

            // 第二条：连接表已经腾空，必须能正常服务。
            val second = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", port))
            try {
                val write = second.openWriteChannel(autoFlush = true)
                val read = second.openReadChannel()
                val envelope = makeEnvelope(
                    type = MsgTypes.MSG,
                    from = PEER_ID,
                    payload = Codec.textPayload("y".repeat(2_000)),
                    id = "msg-after-handoff",
                )
                write.writeFully(TcpFrame.encode(TcpFrames.encodeMsgFrame(envelope).encodeToByteArray()))
                val ack = readFrameOrNull(read)
                assertNotNull(
                    ack,
                    "交接过的连接关闭后仍占着连接表：maxConnections=1 时第二条连接被直接丢弃",
                )
                assertTrue(ack.contains(TcpFrameTypes.MSG_ACK), "第二条连接没被正常服务：$ack")
            } finally {
                second.close()
            }
        } finally {
            server.stop()
            selector.close()
            scope.cancel()
        }
    }

    /**
     * 读一帧；对端关闭或超时就返回 null。
     *
     * `readFully` 在对端关闭时抛的是 `EOFException`，而 `withTimeoutOrNull` 只负责超时、
     * 不负责吞异常 —— 两者必须都归一成 null，否则「应该断链」的用例会以异常冒穿收场
     * 而不是走到断言（2026-10-09 本测试首次运行即踩到）。
     */
    private suspend fun readFrameOrNull(read: io.ktor.utils.io.ByteReadChannel): String? {
        val header = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        val got = withTimeoutOrNull(5_000L) {
            runCatching { read.readFully(header, 0, TcpFrame.LENGTH_PREFIX_BYTES) }.isSuccess
        } ?: return null
        if (!got) return null
        val len = TcpFrame.decodeLength(header)
        if (len <= 0) return null
        val frame = ByteArray(len)
        val body = withTimeoutOrNull(5_000L) {
            runCatching { read.readFully(frame, 0, len) }.isSuccess
        } ?: return null
        if (!body) return null
        return frame.decodeToString()
    }

    private companion object {
        const val PEER_ID = "95d856ac-9974-438c-ab50-4f780cd5b4df"
        const val SID = "1b6fb17d-97e5-44d5-a4b7-814f7719e1d8"
        const val TOKEN = "e6edb0508fc04f0a8030dcc62a2f7e28"
    }
}
