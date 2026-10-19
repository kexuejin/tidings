// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.DEFAULT_TIMINGS
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.TcpFrame
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import app.relay.core.ioDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * TCP 回退通道：把超过 UDP 单包上限的长文本走 TCP 17879 发出去并等回执。
 *
 * 逐条对齐 TS `src/main/net/messenger.ts` 的 `sendTcpAwaitAck`：
 * - 连的是 `record.ip` + `record.profile.tcpPort`（不是 UDP 端口）；
 * - 发出的帧是 `{"type":"msg","envelope":…}`，**不是裸信封** —— 这是最容易写错的一点，
 *   裸信封会被对端 `decodeTcpFrameObject` 的白名单直接判为非法帧；
 * - 只接受 `{"type":"msg-ack","ackFor":<env.id>}`，`ackFor` 必须等于本信封 id；
 * - 超时精确 7000 ms，与 TS 常量一致。
 *
 * 帧体是 4 字节大端长度前缀 + UTF-8 JSON（[TcpFrame]）。
 *
 * 这里刻意只实现 msg / msg-ack 两种帧 —— 文件传输、屏幕共享、pull 系列都不在 P1 文本范围里。
 * 对端若在这条连接上发来别的帧，本实现直接判失败（宁可失败，不可误判成功）。
 *
 * 自持 [SelectorManager]（懒建），由持有者调 [close] 释放。
 */
class TcpFramedChannel(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : TcpFallback {

    private var selector: SelectorManager? = null

    private fun selector(): SelectorManager =
        selector ?: SelectorManager(ioDispatcher).also { selector = it }

    /**
     * 上一次失败的原因（task-63）：`connect-failed: …` / `closed-before-ack` / `timeout`。
     *
     * 真机上「回退 TCP 连不上」（端口/地址在蜂窝侧不可达）与「连上了但对端不回」在诊断里
     * 都只显示 `no-ack`，而这两件事的修法完全不同 ⇒ 必须分开说。
     */
    override var lastError: String? = null
        private set

    override suspend fun sendAndAwaitAck(host: String, port: Int, envelope: Envelope): Boolean {
        lastError = null
        val ok = withTimeoutOrNull(timeoutMs) {
            // `connect` **必须在 try 里**。它曾经在 try 之外，于是"对端 TCP 端口没开"这种
            // 再普通不过的情况（`java.net.ConnectException: Connection refused`）会一路穿过
            // `Messenger.sendViaTcp` 抛到调用方的 `scope.launch` 里 —— 真机上（见 docs/adr/ADR-014）
            // 上实测三次 `FATAL EXCEPTION: DefaultDispatcher-worker-N`，整个 app 被打死
            // （现场：手机点「屏幕协助」→ UDP 重传耗尽 → 回退 TCP 连夹具自报的 17921 → 崩）。
            // 这个类的契约是"宁可失败，不可误判成功"，抛异常不是失败，是崩溃。
            var socket: Socket? = null
            try {
                socket = aSocket(selector()).tcp().connect(InetSocketAddress(host, port))
                val write = socket.openWriteChannel(autoFlush = true)
                write.writeFully(TcpFrame.encode(encodeMsgFrame(envelope)))

                val read = socket.openReadChannel()
                while (true) {
                    val text = readFrame(read)
                    if (text == null) {
                        lastError = "closed-before-ack"
                        return@withTimeoutOrNull false
                    }
                    if (ackFor(text) == envelope.id) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } catch (e: Exception) {
                // 连接被拒 / 对端提前关闭 / 帧非法：一律当作"没送到"，
                // 交给上层入队，而不是抛出去中断发送流程。
                // 但**原因要留下**（task-63）：蜂窝侧 17879 不可达时，这里会是
                // `ConnectException: Connection refused` 或 connect 超时 —— 与"连上了不回"完全不同。
                lastError = "connect-failed: " + (e.message ?: e::class.simpleName ?: "?")
                false
            } finally {
                runCatching { socket?.close() }
            }
        }
        // withTimeoutOrNull 返回 null = 整段超时（没连上也没读到 ACK）。
        if (ok == null) lastError = "timeout"
        return ok == true
    }

    /** 读一帧；长度前缀非法或对端提前关闭都返回 null。 */
    private suspend fun readFrame(read: io.ktor.utils.io.ByteReadChannel): String? {
        val header = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        read.readFully(header, 0, TcpFrame.LENGTH_PREFIX_BYTES)
        val len = TcpFrame.decodeLength(header)
        if (len <= 0 || len > MAX_FRAME) return null
        val body = ByteArray(len)
        read.readFully(body, 0, len)
        return body.decodeToString()
    }

    fun close() {
        runCatching { selector?.close() }
        selector = null
    }

    private companion object {
        /** TS `sendTcpAwaitAck` 的精确超时，来自 DEFAULT_TIMINGS.tcpAckTimeout。 */
        val DEFAULT_TIMEOUT_MS: Long get() = DEFAULT_TIMINGS.tcpAckTimeout

        /** TS `MAX_FRAME = 64 * 1024`。 */
        const val MAX_FRAME: Int = 64 * 1024

        /**
         * 手工拼 `{"type":"msg","envelope":…}`，键序与 TS `JSON.stringify` 完全一致。
         * 用序列化器也能得到同样结果，但这里少一层 plumbing，键序一眼可查。
         */
        fun encodeMsgFrame(envelope: Envelope): ByteArray =
            """{"type":"msg","envelope":${Codec.encode(envelope)}}""".encodeToByteArray()

        /** 取出 msg-ack 的 `ackFor`；不是 msg-ack 帧就返回 null。 */
        fun ackFor(text: String): String? {
            val obj = runCatching { Codec.wireJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "msg-ack") return null
            return obj["ackFor"]?.jsonPrimitive?.contentOrNull
        }
    }
}
