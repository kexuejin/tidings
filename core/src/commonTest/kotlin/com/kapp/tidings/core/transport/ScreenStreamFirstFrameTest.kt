// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.PullFrame
import com.kapp.tidings.core.protocol.ScreenTcpFrame
import com.kapp.tidings.core.protocol.TcpFrame
import com.kapp.tidings.core.protocol.TcpFrames
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 跨网 TLS 监听口的**首帧识别**：进来的连接到底是不是一条投屏。
 *
 * ## 为什么这条必须钉
 *
 * 屏幕端口（`tlsPort + 2`）与文件端口（`tlsPort + 1`）是两扇独立的门，而对端在
 * 版本错配 / 端口记错 / 有人手动连上来时会往里送**不是 screen-open** 的字节。
 * 识别错了只有两种结局，都很贵：
 * - 认成投屏 ⇒ 把一个文件拉取/垃圾帧当成投屏交接上去，会话以 `protocol-error` 收场，
 *   用户看到「投屏一连就断」；
 * - 抛异常出界 ⇒ 连接处理协程当场死掉（现在由监听侧的 `runCatching` 兜住，但兜住的是
 *   "这条连接没了"，不是"这条连接被判定为非投屏"）。
 *
 * 所以契约是「识别不了 ⇒ 返回 null」，且**不得**把长度前缀里的数字当真去读一大块内存
 * （0 / 负数 / 超过 64 KiB 都要在**读正文之前**拒掉）。
 *
 * 用真实帧字节喂 [ByteReadChannel]，走的是生产同一条解码路径（`TcpFrames.encode`）。
 */
class ScreenStreamFirstFrameTest {

    private fun channelOf(bytes: ByteArray): ByteReadChannel = ByteReadChannel(bytes)

    private fun rawFrame(body: String): ByteArray = TcpFrame.encode(body.encodeToByteArray())

    /**
     * 只写 4 字节大端长度前缀。
     *
     * `TcpFrames.encode` 的长度取自正文，写不出 0 / 负数 / 越界这类**坏**前缀，
     * 所以坏前缀要在这里手搓 —— 被测函数正是要在读正文之前把这几档拒掉。
     */
    private fun lengthPrefixOf(len: Int): ByteArray = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES).also {
        it[0] = ((len ushr 24) and 0xFF).toByte()
        it[1] = ((len ushr 16) and 0xFF).toByte()
        it[2] = ((len ushr 8) and 0xFF).toByte()
        it[3] = (len and 0xFF).toByte()
    }

    @Test
    fun aRealScreenOpenFrameDecodesToTheSameValues() = runBlocking {
        val frame = ScreenTcpFrame.Open(from = "node-aaaa1111", sessionId = "sess-1", token = "tok-9")
        val open = readFirstScreenOpen(channelOf(TcpFrames.encode(frame)))
        assertEquals(frame, open, "合法的 screen-open 必须原样解出来，否则跨网投屏永远握不上手")
    }

    @Test
    fun aScreenFrameThatIsNotOpenIsRejected() = runBlocking {
        // 同一族、但语义不是「请求开一路投屏」。ready / next / frame 都不该被认成首帧 ——
        // 认了就等于允许对端用一个中间帧把连接"领走"。
        assertNull(readFirstScreenOpen(channelOf(TcpFrames.encode(ScreenTcpFrame.Ready("sess-1")))))
        assertNull(readFirstScreenOpen(channelOf(TcpFrames.encode(ScreenTcpFrame.Next("sess-1", 1)))))
        assertNull(
            readFirstScreenOpen(
                channelOf(TcpFrames.encode(ScreenTcpFrame.Frame("sess-1", 1, 8, 8, 4))),
            ),
        )
    }

    @Test
    fun aNonScreenFrameIsRejected() = runBlocking {
        // 文件拉取帧走的是隔壁那扇门（tlsPort + 1）。串门进来的必须被拒，
        // 而不是被当成「某种 screen 帧」交接到投屏会话里。
        val pull = PullFrame(type = "pull", from = "node-bbbb", transferId = "t-1", fileId = "f-1", offset = 0)
        assertNull(readFirstScreenOpen(channelOf(TcpFrames.encode(pull))))
    }

    @Test
    fun aScreenOpenWithAnExtraKeyIsRejectedByTheStrictWhitelist() = runBlocking {
        // 严格字段白名单：多一个键就整帧拒（本仓的协议纪律）。
        // 若这里能解出来，说明首帧识别这条路绕过了白名单校验 —— 那是比"多一个键"更大的洞。
        val body = """{"type":"screen-open","from":"node-aaaa","sessionId":"s","token":"t","extra":1}"""
        assertNull(readFirstScreenOpen(channelOf(rawFrame(body))))
    }

    @Test
    fun aZeroLengthFrameIsRejectedWithoutReadingABody() = runBlocking {
        // 只有 4 字节前缀、没有正文：必须在读完前缀就返回 null。
        // 若它去读正文，这个 channel（已到 EOF）会抛异常 —— 这条同时也是"不读正文"的证据。
        assertNull(readFirstScreenOpen(channelOf(lengthPrefixOf(0))))
    }

    @Test
    fun anOversizedLengthIsRejectedBeforeAllocating() = runBlocking {
        // 64 KiB 是整帧硬上限。前缀说 64 KiB + 1 时不许去读（更不许按其分配内存）——
        // 这既是协议纪律，也是"对面一开口就能让我们分配任意字节"的攻击面。
        assertNull(readFirstScreenOpen(channelOf(lengthPrefixOf(64 * 1024 + 1))))
        // 负数（最高位为 1）同样必须当场拒，而不是拿着它去 ByteArray(负数)。
        assertNull(readFirstScreenOpen(channelOf(lengthPrefixOf(Int.MIN_VALUE))))
    }

    @Test
    fun aTruncatedBodyNeverYieldsAnOpen() = runBlocking {
        // 半截帧：前缀说 100 字节、实际只给了 10 字节。
        // 契约上这里应当返回 null（"识别不了"）。现状是 ktor 的 readFully 抛 EOF ——
        // 由监听侧的 runCatching 收掉（该连接被关掉，不会把异常带出监听循环）。
        // 这条断言只钉**不会解出一个 Open**，两种实现（返回 null / 抛异常）都成立，
        // 免得把一个"行为比文档更粗糙"的现状写成契约。
        val truncated = lengthPrefixOf(100) + ByteArray(10) { 0x7B.toByte() }
        val result = runCatching { readFirstScreenOpen(channelOf(truncated)) }
        assertNull(result.getOrNull(), "半截帧绝不能解出一个 Open")
    }

    @Test
    fun aGarbageJsonBodyOfLegalLengthIsRejected() = runBlocking {
        assertNull(readFirstScreenOpen(channelOf(rawFrame("not json at all"))))
        assertNull(readFirstScreenOpen(channelOf(rawFrame("""{"type":"no-such-type"}"""))))
        assertNull(readFirstScreenOpen(channelOf(rawFrame("[]"))))
    }
}
