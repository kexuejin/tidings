// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 在加密信道上按窗口发送文件字节(第 2/3 步的核心件,尚未接进 TransferManager)。
 *
 * 语义:
 *  - 每块 [chunkSize],窗口 [windowChunks] 块未确认上限(64KiB × 32 = 2MiB in-flight);
 *  - 只认**连续前缀**确认([FileChunkAck.ackedUpTo]);超时从已确认位置重传;
 *  - 发送面返回 false(信道队列满)时退避,不自旋。
 *
 * 关于内存:当前按整块 [ByteArray] 发送(≤50MB 可用);生产接线会换成按 offset 读文件
 * 的分块提供者,协议不变。
 */
class SecureChunkSender(
    private val chunkSize: Int = 64 * 1024,
    private val windowChunks: Int = 32,
    private val ackTimeoutMs: Long = 3_000,
    private val backoffMs: Long = 2,
) {
    init {
        require(chunkSize in 1..FileChunkFrame.MAX_PAYLOAD_BYTES)
        require(windowChunks >= 1)
    }

    /**
     * @param send 发一帧到 (host, port);false = 队列满/未连接,调用方退避重试。
     * @param acks 已解码的本传输 ACK(ackedUpTo,单调不减;可由调用方过滤帧得到)。
     */
    suspend fun send(
        transferId: String,
        payload: ByteArray,
        host: String,
        port: Int,
        send: suspend (ByteArray, String, Int) -> Boolean,
        acks: suspend () -> Long?,
    ): Long {
        val total = payload.size.toLong()
        var acked = 0L
        while (acked < total) {
            // 1) 填窗口
            var offset = acked
            val windowEnd = minOf(total, acked + chunkSize.toLong() * windowChunks)
            while (offset < windowEnd) {
                val len = minOf(chunkSize.toLong(), total - offset).toInt()
                val chunk = payload.copyOfRange(offset.toInt(), offset.toInt() + len)
                val frame = FileChunkFrame.encode(transferId, offset, chunk)
                while (!send(frame, host, port)) {
                    kotlinx.coroutines.delay(backoffMs)
                }
                offset += len
            }
            // 2) 等确认前进(超时则从 acked 重传)
            val deadline = acked + chunkSize.toLong() * windowChunks
            var progressed = false
            while (true) {
                val upTo = withTimeoutOrNull(ackTimeoutMs) { acks() }
                if (upTo == null) break
                if (upTo > acked) {
                    acked = minOf(upTo, total)
                    progressed = true
                    break
                }
            }
            if (!progressed) {
                // 超时:下一轮从 acked 重发整个窗口(幂等:接收端按 offset 写)
                kotlinx.coroutines.delay(backoffMs)
            }
            if (acked < deadline && !progressed) continue
        }
        return acked
    }
}
