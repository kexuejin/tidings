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

    private companion object {
        /** 等确认时的轮询间隔：既要让出线程，也别把 CPU 空转掉。 */
        const val LAST_MILE_POLL_MS: Long = 5

        /** 有过推进、但 ACK 停这么久就等于「这一窗到这儿为止」（大概率有洞）。 */
        const val ACK_QUIET_MS: Long = 120L
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
        // **诊断计数器**（临时；定位「每块 500ms」用）。不改行为。
        var windows = 0
        var timeouts = 0
        var chunksSent = 0L
        var ackAdvances = 0L
        var readNs = 0L
        var sendNs = 0L
        var waitNs = 0L
        while (acked < total) {
            windows++
            // 1) 从已确认位置起填满一个窗口
            val windowEnd = minOf(total, acked + windowBytes)
            var offset = acked
            while (offset < windowEnd) {
                val len = minOf(chunkSize.toLong(), total - offset).toInt()
                val rt0 = System.nanoTime()
                val chunk = source.read(offset, len)
                readNs += System.nanoTime() - rt0
                if (chunk.isEmpty()) break
                val frame = FileChunkFrame.encode(transferId, offset, chunk)
                val st0 = System.nanoTime()
                while (!send(frame, host, port)) {
                    kotlinx.coroutines.delay(backoffMs)
                }
                sendNs += System.nanoTime() - st0
                chunksSent++
                offset += chunk.size
            }
            // 2) 等确认前进；没前进就从 acked 整窗重发（接收端按 offset 写，幂等）
            //
            // **必须真的让出线程**:acks 一般是"读一下当前确认值"这种非挂起调用,
            // 放在 withTimeoutOrNull {} 里会立刻返回 —— 循环不休眠就成了热自旋,
            // 单线程调度器上会把收帧的协程饿死,ACK 永远进不来。
            // 真机/回环 50MB 验收撞过:180s 超时,一个字节都没确认。
            // 2) **把这一窗的确认收干净，而不是「看见第一块就走」**。
            //
            // 原来这里一看到 upTo > acked 就 break，于是每轮只前进**一块**、
            // 却把整窗（32 块）重发一遍 —— 实测 50MB 传输里
            // chunksSent=12768 而真正需要的只有 **399** 块（32 倍放大），
            // waitMs 占掉 180s 里的 163s，吞吐掉到 0.16 MiB/s。
            //
            // 正确语义：ACK 是**逐块**到的，所以要一直轮询到
            //   · 整窗都被确认（最好情况，立刻开下一窗），或
            //   · 进度停止前进超过 ACK_QUIET_MS（说明有洞，交给下一轮重传），或
            //   · 到了 ackTimeoutMs 硬上限。
            val deadlineAt = System.currentTimeMillis() + ackTimeoutMs
            var progressed = false
            var best = acked
            var lastAdvanceAt = System.currentTimeMillis()
            val wt0 = System.nanoTime()
            while (System.currentTimeMillis() < deadlineAt) {
                val upTo = acks() ?: best
                if (upTo > best) {
                    best = upTo
                    progressed = true
                    lastAdvanceAt = System.currentTimeMillis()
                    if (best >= windowEnd) break // 整窗确认，立刻下一窗
                } else if (
                    progressed && System.currentTimeMillis() - lastAdvanceAt >= ACK_QUIET_MS
                ) {
                    // 有推进但停了：大概率有洞（对端某些帧根本没收到），别把 3s 干等完。
                    //
                    // 实测对照：**保留这个提前退出**时 180s 能传 23-27MB；
                    // 去掉它（干等满 3s）只剩 3.7MB —— 因为 ACK 经常不再前进。
                    break
                }
                kotlinx.coroutines.delay(LAST_MILE_POLL_MS)
            }
            ackAdvances += best - acked
            acked = minOf(best, total)
            waitNs += System.nanoTime() - wt0
            if (!progressed) {
                timeouts++
                kotlinx.coroutines.delay(backoffMs)
            }
        }
        println(
            "[chunk] total=" + total + " windows=" + windows + " timeouts=" + timeouts +
                " chunksSent=" + chunksSent + " ackAdvanceSum=" + ackAdvances +
                " readMs=" + (readNs / 1_000_000) + " sendMs=" + (sendNs / 1_000_000) +
                " waitMs=" + (waitNs / 1_000_000),
        )
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
    /**
     * 逐块收齐标记。
     *
     * **按块而不是按字节**：原来用 `BooleanArray(totalSize.toInt())` —— 50MB 文件就是
     * 50MB 的布尔数组，而且是**每个在途文件一份**。真机 ≥50MB 验收立刻会撞上这个：
     * 手机上一次多文件传输能吃掉几百 MB。按块记只占 `ceil(size/chunk)` 位，
     * 64MiB 的文件约 1024 个元素。
     */
    private val receivedChunks = BooleanArray(((totalSize + CHUNK) / CHUNK).toInt().coerceAtLeast(1))

    /**
     * 只对**部分到达**的块分配位图（整块一次到齐是常态，所以这个 map 通常是空的）。
     * 不能省：分包/部分重传会把一块拆成两帧，不记"哪半到了"就永远凑不成整块。
     */
    private val partialCoverage = HashMap<Int, BooleanArray>()
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
        // 只记"这一块已到"：块必须是已知尺寸的整块，否则连续前缀算不准 ——
        // 所以不足一块的尾块按它真实长度处理(尾块也是唯一合法的不整块)。
        val firstIdx = (start / CHUNK).toInt()
        val lastIdx = ((end - 1) / CHUNK).toInt()
        var fresh = false
        for (idx in firstIdx..lastIdx) {
            if (idx !in receivedChunks.indices) continue
            val needStart = idx.toLong() * CHUNK
            val needEnd = minOf(totalSize, needStart + CHUNK)
            if (start <= needStart && end >= needEnd) {
                if (!receivedChunks[idx]) {
                    receivedChunks[idx] = true
                    fresh = true
                }
            } else {
                // 半块:数据是新的(要落盘),但要记"哪半到了" —— 不记的话第二半到达时
                // 仍凑不成整块,连续前缀会永远停住(实测:半块用例卡在 0)。
                val covered = partialCoverage.getOrPut(idx) { BooleanArray(CHUNK) }
                val from = maxOf(start, needStart).toInt()
                val to = minOf(end, needEnd).toInt()
                val base = needStart.toInt()
                for (pos in from until to) {
                    val i = pos - base
                    if (!covered[i]) {
                        covered[i] = true
                        fresh = true
                    }
                }
                var full = true
                for (pos in base until needEnd.toInt()) {
                    if (!covered[pos - base]) { full = false; break }
                }
                if (full) receivedChunks[idx] = true
            }
        }
        var advanced = false
        while (true) {
            val idx = (contiguous / CHUNK).toInt()
            if (idx !in receivedChunks.indices || !receivedChunks[idx]) break
            // 这一块到了，把连续前缀推过整块；最后一块只推到 totalSize。
            val nextBoundary = minOf(totalSize, (idx + 1).toLong() * CHUNK)
            if (contiguous >= nextBoundary) break
            contiguous = nextBoundary
            advanced = true
        }
        if (advanced || !fresh) onAck(contiguous)
        return fresh
    }

    companion object {
        /** 收块粒度,必须与发送端 SecureChunkSender.chunkSize 一致(默认都是 64KiB)。 */
        const val CHUNK: Int = 64 * 1024
    }
}
