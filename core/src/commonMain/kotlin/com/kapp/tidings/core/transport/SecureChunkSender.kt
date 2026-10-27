// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.use
import com.kapp.tidings.core.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 文件字节的**数据源**：按 offset 读一块，而不是把整个文件塞进内存。
 *
 * 为什么要这一层：`SecureChunkSender` 原先吃整块 [ByteArray]（≤50MB 够用，但这是内存
 * 里除了两次拷贝就没有别的代价）。真机 ≥50MB 验收时，50MB 的 ByteArray + 每 64KiB 的
 * `copyOfRange` 会让一次传输常驻两份字节；接进 `TransferManager` 后文件从磁盘按需读，
 * 峰值内存 = 窗口大小（默认 2MiB）。
 */
interface FileChunkSource {
    /** 总字节数。 */
    val size: Long

    /** 读 [offset] 起、长度不超过 [maxBytes] 的一段；到末尾时返回短块。 */
    suspend fun read(offset: Long, maxBytes: Int): ByteArray
}

/** 内存实现（测试/小文件）。 */
class ByteArrayChunkSource(private val bytes: ByteArray) : FileChunkSource {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(offset: Long, maxBytes: Int): ByteArray {
        require(offset >= 0)
        if (offset >= bytes.size) return ByteArray(0)
        val len = minOf(maxBytes.toLong(), bytes.size - offset).toInt()
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + len)
    }
}

/**
 * 磁盘实现：每次 [read] 现开文件、读一段、关掉。
 *
 * 为什么每次开关而不是持有一个句柄：这条路径**可能被取消/重连打断**（数据面在
 * 断线后会从已确认位置重发），持句柄就要处理"谁来关、什么时候关、重连后句柄是否还有效"；
 * 而每块现开一次只多几次 syscall（64KiB 一块，50MB 也只有 800 次），换来的是
 * **无状态**——取消后不会留下悬空 fd。真机大文件若发现 syscall 成为瓶颈，
 * 再改成持句柄（协议不变）。
 */
class DiskFileChunkSource(private val path: String) : FileChunkSource {
    /**
     * 文件消失时**抛错而不是报 0**：size=0 会让上层以为"这是个空文件、传完了"，
     * 而真相是待传的字节不见了 —— 那必须是一次可归因的失败，不是一次成功的空传输。
     */
    override val size: Long
        get() = fileSize(path) ?: throw IllegalStateException("chunk source vanished: " + path)

    override suspend fun read(offset: Long, maxBytes: Int): ByteArray = withContext(ioDispatcher) {
        val file = openRandomAccess(path, forWrite = false)
        try {
            val buffer = ByteArray(maxBytes)
            val n = file.read(buffer, offset, maxBytes)
            if (n <= 0) ByteArray(0) else buffer.copyOf(n)
        } finally {
            file.use { }
        }
    }
}

/**
 * 在加密信道上按窗口发送文件字节。
 *
 * 语义：
 *  - 每块 [chunkSize]，窗口 [windowChunks] 块未确认上限（64KiB × 32 = 2MiB in-flight）；
 *  - 只认**连续前缀**确认（[FileChunkAck.ackedUpTo]）；超时从已确认位置重传；
 *  - 发送面返回 false（信道队列满）时退避，不自旋；
 *  - 数据从 [FileChunkSource] 按 offset 读，峰值内存 = 一个窗口。
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
     * @param send 发一帧到 (host, port)；false = 队列满/未连接，调用方退避重试。
     * @param acks 取"当前最新确认"(ackedUpTo, 单调不减)；超时返回 null 视为没有前进。
     * @return 已确认送达的字节数；等于 [source].size 表示全部送达。
     */
    suspend fun send(
        transferId: String,
        source: FileChunkSource,
        host: String,
        port: Int,
        send: suspend (ByteArray, String, Int) -> Boolean,
        acks: suspend () -> Long?,
    ): Long {
        val total = source.size
        var acked = 0L
        val windowBytes = chunkSize.toLong() * windowChunks
        while (acked < total) {
            // 1) 从已确认位置起填满一个窗口
            val windowEnd = minOf(total, acked + windowBytes)
            var offset = acked
            while (offset < windowEnd) {
                val len = minOf(chunkSize.toLong(), total - offset).toInt()
                val chunk = source.read(offset, len)
                if (chunk.isEmpty()) break
                val frame = FileChunkFrame.encode(transferId, offset, chunk)
                while (!send(frame, host, port)) {
                    kotlinx.coroutines.delay(backoffMs)
                }
                offset += chunk.size
            }
            // 2) 等确认前进；没前进就从 acked 整窗重发（接收端按 offset 写，幂等）
            var progressed = false
            while (true) {
                val upTo = withTimeoutOrNull(ackTimeoutMs) { acks() } ?: break
                if (upTo > acked) {
                    acked = minOf(upTo, total)
                    progressed = true
                    break
                }
            }
            if (!progressed) kotlinx.coroutines.delay(backoffMs)
        }
        return acked
    }

    /** 便捷重载：内存里的整块字节。 */
    suspend fun send(
        transferId: String,
        payload: ByteArray,
        host: String,
        port: Int,
        send: suspend (ByteArray, String, Int) -> Boolean,
        acks: suspend () -> Long?,
    ): Long = send(transferId, ByteArrayChunkSource(payload), host, port, send, acks)
}

/**
 * 接收端：按 offset 幂等落盘 + **只确认连续前缀**。
 *
 * 为什么必须是类而不是散在调用方：ACK 的判据（连续前缀、不跳空洞）与"重复块不重写"
 * 是同一条不变量的两面，散着写迟早有一处只做一半 —— 那会让发送端以为整段到了。
 */
class SecureChunkReceiver(
    private val transferId: String,
    private val totalSize: Long,
    private val onAck: suspend (Long) -> Unit,
) {
    private val received = BooleanArray(if (totalSize > Int.MAX_VALUE) Int.MAX_VALUE else totalSize.toInt())
    private var contiguous: Long = 0

    /** 当前已连续确认到的字节数。 */
    val ackedUpTo: Long get() = contiguous

    /** 是否已收齐。 */
    val isComplete: Boolean get() = contiguous >= totalSize

    /**
     * 接收一块。返回 true = 这是新数据（调用方需要落盘）；false = 重复块（直接丢）。
     * 重复块**也要**触发一次 ACK —— 否则对端重传后会一直等不到确认。
     */
    suspend fun onChunk(frame: FileChunkFrame.Decoded): Boolean {
        if (frame.transferId != transferId) return false
        val start = frame.offset
        val end = start + frame.payload.size
        if (start < 0 || end > totalSize) return false
        var fresh = false
        for (i in frame.payload.indices) {
            val idx = (start + i).toInt()
            if (idx in received.indices && !received[idx]) {
                received[idx] = true
                fresh = true
            }
        }
        var advanced = false
        while (contiguous < totalSize && contiguous.toInt() in received.indices && received[contiguous.toInt()]) {
            contiguous++
            advanced = true
        }
        if (advanced || !fresh) onAck(contiguous)
        return fresh
    }
}
