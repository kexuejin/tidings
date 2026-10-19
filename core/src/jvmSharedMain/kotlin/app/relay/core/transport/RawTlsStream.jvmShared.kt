// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.net.ssl.SSLSocket

/**
 * F1：TLS 之上的**原始字节流**连接（task-39）。
 *
 * 读：直接把 `SSLSocket.inputStream` 包成 ktor 的 [ByteReadChannel]（解密后的字节）。
 * 写：拿一个 autoFlush 的 [ByteChannel]，用一条泵协程把字节灌进 `outputStream`
 * —— ktor 3.3.1 的 jvm javaio 只提供 `InputStream.toByteReadChannel()`，没有
 * `OutputStream.toByteWriteChannel()`，所以写这一侧自己泵。
 *
 * ## 关闭必须**先排空再关 socket**（这是实测踩出来的）
 *
 * 第一版是「关通道 → 取消泵 → 关 socket」，结果跨网那条路在数据阶段的尾部丢字节：
 * 服务端 `servePull` 已经返回、写进通道的尾部还没被泵写出去，socket 就被关了 ——
 * 客户端读到一半拿到 `PullException: closed`（真机上的表现就是"大文件总是差最后一段"）。
 * 所以顺序是：`flush` → `channel.close()`（让泵读到 EOF 并把剩余字节写完）→ **等泵退出** → 关 socket。
 *
 * 等待带上限（[CLOSE_DRAIN_MS]）：取消路径上不能为了排空把调用方卡住。
 */
internal class RawTlsConnection(
    private val socket: SSLSocket,
    override val peerPin: TlsPin?,
) : RawPullConnection {

    private val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val read: ByteReadChannel = socket.inputStream.toByteReadChannel(Dispatchers.IO)

    private val channel = ByteChannel(autoFlush = true)

    private val pump = pumpScope.launch {
        runCatching { channel.copyTo(socket.outputStream) }
    }

    override val write: ByteWriteChannel = channel

    override fun close() {
        runCatching { runBlocking { channel.flush() } }
        // 关通道 ⇒ 泵读到 EOF、把剩余字节写完并退出；等它退出再关 socket，字节才不丢。
        runCatching { channel.close() }
        runCatching { runBlocking { withTimeoutOrNull(CLOSE_DRAIN_MS) { pump.join() } } }
        runCatching { pump.cancel() }
        runCatching { pumpScope.cancel() }
        runCatching { socket.close() }
    }

    private companion object {
        /** 关连接时最多等多久把写缓冲排空。 */
        const val CLOSE_DRAIN_MS: Long = 2_000
    }
}