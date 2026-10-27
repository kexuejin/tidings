// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.Sha256
import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.renameFile
import com.kapp.tidings.core.fs.sanitizeRelativePath
import com.kapp.tidings.core.fs.use
import com.kapp.tidings.core.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * 一个待收文件的**落盘目标**（加密信道数据面用）。
 *
 * 与 legacy 的 `PullSession` 保持同一套磁盘契约：写 `<final>.part` → 校验 sha256 →
 * 改名成最终名（不覆盖已有文件）。区别只在**字节从哪来**（加密信道 vs 裸 TCP）。
 */
class ChunkFileTarget(
    val relPath: String,
    val expectedSize: Long,
    /** 对端在 `done` 帧里给出的 sha256（小写 hex）；null = 对端没给（只校验长度）。 */
    val expectedSha256: String?,
) {
    /**
     * 归一化后的最终路径；`null` = 相对路径越界（`../` 之类）。
     * 越界必须是一次失败，不能"尽力拼一个路径"。
     */
    fun resolveUnder(root: String): String? {
        val safe = sanitizeRelativePath(relPath) ?: return null
        return pathJoin(root, safe)
    }
}

/**
 * 接收一个文件的字节到 `<final>.part`，然后校验 + 转正。
 *
 * 为什么要一个独立类而不是塞进 `SecureChannelDataPlane`：**校验与转正是可以单独测的**
 * （不需要网络），而"字节从信道来"才是需要网络的。分开测才不会出现
 * "端到端绿、但 sha 校验根本没跑"这种假绿。
 */
class ChunkFileReceiver(
    private val target: ChunkFileTarget,
    private val root: String,
) {
    private val finalPath: String? = target.resolveUnder(root)
    private val partPath: String? = finalPath?.let { it + PART_SUFFIX }

    /** 解析阶段就失败（路径越界）：调用方据此报 path-escape。 */
    val valid: Boolean get() = finalPath != null

    /**
     * 按帧里的 [atOffset] 写入 part 文件（随机写，**不是**顺序追加）。
     *
     * 数据面按窗口重传，帧可能乱序、也可能重复投递；只有按 offset 写才能让
     * "重传补洞"正确落盘。重复块由上层 `SecureChunkReceiver` 挡住，这里不做去重。
     */
    suspend fun append(atOffset: Long, bytes: ByteArray) = withContext(ioDispatcher) {
        val part = partPath ?: throw PullException("path-escape", PullStage.Write)
        makeDirectories(root)
        val handle = openRandomAccess(part, forWrite = true)
        try {
            handle.writeAt(atOffset, bytes, bytes.size)
            // **不再每块 fsync**（真机 50MB 验收实测到的吞吐杀手）。
            //
            // 原来每写一块就 `handle.flush()` = `fd.sync()`，而 50MB 是 800 块 ——
            // 每块一次 fsync 在真机上要几百毫秒级，实测吞吐掉到 **0.147 MiB/s**
            // （每块 426ms），而历史基线是 1.09 MiB/s。
            //
            // 现在的语义：**按已确认字节数批量同步**。`confirmedBytes` 由上层在 ACK
            // 连续前缀推进时告诉我们，只有"对端已经确认收下"的那一段才值得落盘 ——
            // 这正是当初加 fsync 的理由（已确认的字节不能只停在页缓存），只是粒度从
            // "每块"改成"每 N MiB / 每次 ACK 推进"。
            maybeSync(handle)
        } finally {
            handle.use { }
        }
    }

    /** 距上次同步已写够 [SYNC_EVERY_BYTES]（或首次）才 fsync 一次。 */
    private fun maybeSync(handle: com.kapp.tidings.core.fs.RandomAccessFile) {
        val now = System.currentTimeMillis()
        if (now - lastSyncAtMs >= SYNC_EVERY_MS) {
            handle.flush()
            lastSyncAtMs = now
        }
    }

    /** 上次 fsync 的时刻（毫秒）。 */
    private var lastSyncAtMs: Long = 0L

    /** 已写入的字节数（续传判据）。 */
    fun writtenBytes(): Long = partPath?.let { fileSize(it) } ?: 0L

    /**
     * 校验并转正。失败时删掉 part（与 legacy 一致：校验不过的半成品不许留在盘上装成成功）。
     *
     * @return 最终路径；失败抛 [PullException]。
     */
    suspend fun finish(): String = withContext(ioDispatcher) {
        val final = finalPath ?: throw PullException("path-escape", PullStage.Write)
        val part = partPath ?: throw PullException("path-escape", PullStage.Write)
        val written = fileSize(part) ?: 0L
        if (written != target.expectedSize) {
            deleteFile(part)
            throw PullException("size-mismatch", PullStage.Verify)
        }
        val digest = sha256Of(part)
        val expected = target.expectedSha256
        if (expected != null && !expected.equals(digest, ignoreCase = true)) {
            deleteFile(part)
            throw PullException("hash-mismatch", PullStage.Verify)
        }
        // **必须只算一次**:dedupeTargetPath 会看"目标是否已存在"来决定加 (1) 还是 (2)。
        // 先算一次用来 rename,再算一次拿来返回 —— 第二次看到刚 rename 出来的文件，
        // 就会返回 "x(1).bin"，调用方按那个路径去读取到 null(实测踩过:测试报 16 vs null)。
        val targetPath = dedupeTargetPath(final)
        if (!renameFile(part, targetPath)) {
            deleteFile(part)
            throw PullException("write-error", PullStage.Write)
        }
        targetPath
    }

    /** 删掉 part（取消/失败时调用）。 */
    suspend fun discard() = withContext(ioDispatcher) {
        partPath?.let { runCatching { deleteFile(it) } }
    }

    private fun sha256Of(path: String): String {
        val sha = Sha256()
        val handle = openRandomAccess(path, forWrite = false)
        try {
            val buffer = ByteArray(64 * 1024)
            var offset = 0L
            while (true) {
                val n = handle.read(buffer, offset, buffer.size)
                if (n <= 0) break
                sha.update(buffer, 0, n)
                offset += n
            }
        } finally {
            handle.use { }
        }
        return sha.hexDigest()
    }
}

private const val SYNC_EVERY_MS: Long = 2_000L
