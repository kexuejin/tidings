// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport
import com.kapp.tenon.core.withLock

/**
 * 加密信道上**文件数据帧的路由器**：把入站的 `TDCH`/`TDCA` 帧分派到对应的
 * 在途传输，并把出站文件按窗口推出去。
 *
 * ## 为什么需要它（而不是让 TransferManager 直接收字节）
 *
 * 一个 `file-ctl` transfer 可以带**多个文件**，而信道上的帧只有一个 string 键。
 * 这里用 `"<transferId>#<fileId>"` 作为帧内的 `transferId` 字段（≤128 字节足够），
 * 于是**线格式一个字节都不用改**：解码后按 '#' 拆开就能路由到具体文件的接收器。
 *
 * ## 与 TransferManager 的分工
 *
 * - 路由器：只认字节。登记/注销一次 transfer，收块、回 ACK、推文件；
 * - TransferManager：仍负责 offer/accept/取消/进度/消息卡片等业务语义。
 *
 * 这样"字节从哪条路来"与"这个文件收不收"彻底分开，前者可以单独测。
 */
class FileChannelRouter(
    /** 发一帧给对端（通常是 `DatagramTransport.send`）。 */
    private val sendToPeer: suspend (peerId: String, bytes: ByteArray) -> Boolean,
    private val chunkSize: Int = SecureChunkReceiver.CHUNK,
) {
    /** 在途接收：key = "transferId#fileId"。 */
    private class Inbound(
        val peerId: String,
        val transferId: String,
        val fileId: String,
        val receiver: ChunkFileReceiver,
        val chunks: SecureChunkReceiver,
        val onProgress: ((done: Long, total: Long) -> Unit)? = null,
        val onCompleted: ((path: String) -> Unit)? = null,
        val onFailure: ((reason: String) -> Unit)? = null,
    ) {
        /**
         * 转正是否已经做过（只做一次）。
         *
         * 收齐之后**故意不从表里摘掉**：末帧的 ACK 一旦丢在信道上，发送端会重传整窗，
         * 而它只认"连续前缀到 totalSize"。摘掉之后重传帧无人认领、不再回 ACK，
         * 发送端就永远停在最后一窗（回环 50MB 验收实测：acked 50593792/52428800，
         * 文件其实早已转正、part 已改名，两边却都以为没传完）。
         */
        var finished: Boolean = false

        /** 这一路已经进来的字节数（stall 看门狗的探针，见 [hasReceivedBytes]）。 */
        var bytesReceived: Long = 0L
    }

    private val lock = com.kapp.tenon.core.createCoreLock()
    private val inbound = HashMap<String, Inbound>()
    private val outboundAcks = HashMap<String, Long>()

    /**
     * 诊断：每个出站 key 已经打印到哪个 1MiB 档位。
     *
     * 为什么要有它：ACK 是"看不见的成功"。发端窗口一旦收不到 ACK 就整窗重发、
     * 永远不返回，而**日志里一个字都没有** —— 真机 8MiB 推送卡在 2MiB（正好一个
     * 32×64KiB 窗口）时，宿主只留下 `channel push start`，无法区分"对端没发 ACK"
     * 与"发了但我没收到"。按 1MiB 一档打一行，既能看到 ACK 到底有没有进来，
     * 又不会把 50MB 传输刷成上万行。
     */
    private val ackLoggedUpTo = HashMap<String, Long>()

    /** 登记一个待收文件；返回 false = 路径越界等,调用方应报 path-escape。 */
    fun registerInbound(
        peerId: String,
        transferId: String,
        fileId: String,
        target: ChunkFileTarget,
        root: String,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
        onCompleted: ((path: String) -> Unit)? = null,
        onFailure: ((reason: String) -> Unit)? = null,
    ): Boolean {
        val receiver = ChunkFileReceiver(target, root)
        if (!receiver.valid) return false
        val key = keyOf(transferId, fileId)
        var ackLoggedUpTo = -1L
        val chunks = SecureChunkReceiver(transferIdKey(transferId, fileId), target.expectedSize) { upTo ->
            // ACK 只回**连续前缀**；帧里的键用组合键，对端能路由回这个文件。
            val sent = sendToPeer(peerId, FileChunkAck.encode(transferIdKey(transferId, fileId), upTo))
            if (!sent) {
                // 发不出去必须留痕：对端会整窗重发、永远等不到 ACK，而本端看起来
                // "收得很好"（字节全落盘、进度条也在动）—— 两边的日志对不上时，
                // 这一行是唯一能指认"ACK 根本没出去"的证据。
                println("[chunk] ack-out failed peer=" + peerId.take(8) + " upTo=" + upTo)
            } else if (upTo / ACK_LOG_STEP > ackLoggedUpTo) {
                ackLoggedUpTo = upTo / ACK_LOG_STEP
                println("[chunk] ack-out peer=" + peerId.take(8) + " upTo=" + upTo)
            }
        }
        lock.withLock {
            inbound[key] = Inbound(peerId, transferId, fileId, receiver, chunks, onProgress, onCompleted, onFailure)
        }
        return true
    }

    /**
     * 收到一帧二进制数据。返回 true = 已处理（调用方不必再当 Envelope 解）。
     *
     * 未登记的 transfer 一律丢弃：那可能是上一轮已完成的残帧，或对端没登记过的传输。
     */
    suspend fun onBinaryFrame(bytes: ByteArray): Boolean {
        FileChunkFrame.decode(bytes)?.let { frame ->
            val (transferId, fileId) = splitKey(frame.transferId) ?: return true
            val entry = lock.withLock { inbound[keyOf(transferId, fileId)] } ?: return true
            if (entry.chunks.onChunk(FileChunkFrame.Decoded(transferIdKey(transferId, fileId), frame.offset, frame.payload))) {
                entry.receiver.append(frame.offset, frame.payload)
                entry.onProgress?.invoke(entry.chunks.ackedUpTo, entry.receiver.expectedSize)
            }
            // 计数放在判重之外：重复帧也算"信道活着"。只做探针用，不参与校验。
            entry.bytesReceived += frame.payload.size
            if (entry.chunks.isComplete) completeOnce(entry)
            return true
        }
        FileChunkAck.decode(bytes)?.let { ack ->
            lock.withLock { outboundAcks[ack.transferId] = ack.ackedUpTo }
            // 1MiB 一档：ACK 进来这件事必须有痕迹（见 [ackLoggedUpTo] 的 KDoc）。
            //
            // 判据是「这一档是不是第一次见」——**不要**用"返回值是不是 null"当哨兵：
            // 首次（prev==null）与抑制（mark<=prev）都会拿到 null，那样等于每帧都打，
            // 一次 8MiB 传输就是一百多行、64MiB 是八百多行，反而把信号淹掉
            // （第一版真机日志正是这样刷出来的）。
            val mark = ack.ackedUpTo / ACK_LOG_STEP
            val firstTime = lock.withLock {
                val prev = ackLoggedUpTo[ack.transferId]
                if (prev == null || mark > prev) {
                    ackLoggedUpTo[ack.transferId] = mark
                    true
                } else {
                    false
                }
            }
            if (firstTime) {
                println(
                    "[chunk] ack-in key=" + ack.transferId.take(24) +
                        " upTo=" + ack.ackedUpTo,
                )
            }
            return true
        }
        // 既不是数据帧也不是 ACK:交给上层(它会按 Envelope 处理或丢弃)。
        return false
    }

    /**
     * 收齐后转正，**只做一次**；失败就把条目摘掉（不再回 ACK，让发送端如实失败）。
     *
     * 校验失败不回 ACK 是刻意的：ACK 的语义是"字节收齐"，一旦校验不过，就不能再
     * 给出"收齐了"的信号 —— 那会让发送端报成功、而盘上没有这个文件。
     */
    private suspend fun completeOnce(entry: Inbound) {
        lock.withLock {
            if (entry.finished) return
            entry.finished = true
        }
        val finalPath = runCatching { entry.receiver.finish() }
            .onFailure { t ->
                println("[luciola] channel file verify failed: " + t)
                lock.withLock { inbound.remove(keyOf(entry.transferId, entry.fileId)) }
                entry.receiver.discard()
                entry.onFailure?.invoke(t.message ?: "verify-failed")
            }
            .getOrNull()
        if (finalPath != null) {
            entry.onCompleted?.invoke(finalPath)
        }
    }

    /** 查询某次出站传输的最新确认（发送端窗口用）。 */
    fun ackedUpTo(transferId: String, fileId: String): Long =
        lock.withLock { outboundAcks[transferIdKey(transferId, fileId)] ?: 0L }

    /** 推一个文件到对端；返回已确认字节数。 */
    suspend fun sendFile(
        peerId: String,
        transferId: String,
        fileId: String,
        source: FileChunkSource,
        onProgress: ((done: Long) -> Unit)? = null,
    ): Long {
        val frameKey = transferIdKey(transferId, fileId)
        return SecureChunkSender(chunkSize).send(
            transferId = frameKey,
            source = source,
            host = "",
            port = 0,
            send = { bytes, _, _ -> sendToPeer(peerId, bytes) },
            acks = { ackedUpTo(transferId, fileId) },
            onProgress = onProgress,
        )
    }

    /** 清理一次传输的全部在途状态（取消/完成后调用）。 */
    fun forget(transferId: String) {
        lock.withLock {
            inbound.keys.filter { it.startsWith(transferId + "#") }.forEach { inbound.remove(it) }
            outboundAcks.keys.filter { it.startsWith(transferId + "#") }.forEach { outboundAcks.remove(it) }
            ackLoggedUpTo.keys.filter { it.startsWith(transferId + "#") }.forEach { ackLoggedUpTo.remove(it) }
        }
    }

    /**
     * 这次传输的信道入站是否**已经收到过字节**（哪怕一个重复帧）。
     *
     * TransferManager 的 stall 看门狗据此区分"信道活着，只是还没收完"与
     * "登记了却一个字节都不来 —— 声明了 fch1 的对端其实没有信道"。
     * 前者继续等，后者必须回退 legacy 拨号，否则两端互等（真机死锁现场，
     * 见 TransferManager.scheduleChannelStallFallback 的 KDoc）。
     */
    fun hasReceivedBytes(transferId: String): Boolean = lock.withLock {
        inbound.entries.any { it.key.startsWith(transferId + "#") && it.value.bytesReceived > 0L }
    }

    private fun keyOf(transferId: String, fileId: String): String = transferId + "#" + fileId

    /**
     * 帧内的键：`"<transferId>#<fileId>"`，超出 128 字节就用哈希兜底。
     * 兜底必须**稳定**（同一输入同一输出），否则 ACK 路由不上。
     */
    private fun transferIdKey(transferId: String, fileId: String): String {
        val raw = keyOf(transferId, fileId)
        if (raw.encodeToByteArray().size <= FileChunkFrame.MAX_TID_BYTES) return raw
        val digest = com.kapp.tenon.core.crypto.Sha256()
        digest.update(raw.encodeToByteArray())
        return "#" + digest.hexDigest().take(48)
    }

    private fun splitKey(key: String): Pair<String, String>? {
        if (key.startsWith("#")) return null // 哈希兜底形态:只用于发端寻址,不收
        val idx = key.indexOf('#')
        if (idx <= 0 || idx == key.length - 1) return null
        return key.substring(0, idx) to key.substring(idx + 1)
    }

    companion object {
        /** `ack-in` 日志的档位：每 1MiB 打一行。 */
        private const val ACK_LOG_STEP: Long = 1024L * 1024L

        /**
         * 「这一帧属于文件数据面」的**唯一判据** —— 数据块（`TDCH`）**或** 窗口确认（`TDCA`）。
         *
         * ## 为什么必须两族一起认（真机缺陷 B 的根因）
         *
         * 入站二进制帧在 `SecureChannelTransport.InboundListener.onMessage` 里有一道闸门：
         * 认出来的直接喂给本路由器，认不出的继续按 Envelope 解 —— 而 `TDCA` 解不成
         * Envelope，于是**整帧静默丢掉**，一个字节的日志都没有。
         *
         * 原来的默认判据只认 `FileChunkFrame`（`TDCH`），于是出现了一个只在**真机**
         * 出现的单向黑洞：数据块（发端→收端）能过，窗口确认（收端→发端）过不去。
         * 症状是发送端 `acked` 永远是 0 ⇒ 窗口永远停在第一个 2MiB（32×64KiB）：
         *  · ≤2MiB 的文件「看起来能传」，因为整份都落在首窗里，收端已收齐转正，
         *    而发端在无限重传同一窗（真机：`[chunk] stall acked=0/8192` 打了一百多轮）；
         *  · >2MiB 的文件 `.part` 精确卡在 2,097,152 B，二十秒不动。
         *
         * 回环测试一直是绿的，因为 `ChannelFileTransferE2ETest` 直接把两个路由器对接
         * （`hostRouter.onBinaryFrame(it.bytes)`），**绕过了这道闸门** —— 这就是
         * 「单测绿、真机死」的那条缝。
         *
         * 所以判据只能有一份，且必须住在这里：路由器认什么，闸门就放什么。
         */
        fun isChannelFrame(bytes: ByteArray): Boolean =
            FileChunkFrame.isChunkFrame(bytes) || FileChunkAck.isAck(bytes)
    }
}
