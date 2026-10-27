// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

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
    )

    private val lock = Any()
    private val inbound = HashMap<String, Inbound>()
    private val outboundAcks = HashMap<String, Long>()

    /** 登记一个待收文件；返回 false = 路径越界等,调用方应报 path-escape。 */
    fun registerInbound(
        peerId: String,
        transferId: String,
        fileId: String,
        target: ChunkFileTarget,
        root: String,
    ): Boolean {
        val receiver = ChunkFileReceiver(target, root)
        if (!receiver.valid) return false
        val key = keyOf(transferId, fileId)
        val chunks = SecureChunkReceiver(transferIdKey(transferId, fileId), target.expectedSize) { upTo ->
            // ACK 只回**连续前缀**；帧里的键用组合键，对端能路由回这个文件。
            sendToPeer(peerId, FileChunkAck.encode(transferIdKey(transferId, fileId), upTo))
        }
        synchronized(lock) {
            inbound[key] = Inbound(peerId, transferId, fileId, receiver, chunks)
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
            val entry = synchronized(lock) { inbound[keyOf(transferId, fileId)] } ?: return true
            if (entry.chunks.onChunk(FileChunkFrame.Decoded(transferIdKey(transferId, fileId), frame.offset, frame.payload))) {
                entry.receiver.append(frame.payload)
            }
            if (entry.chunks.isComplete) {
                synchronized(lock) { inbound.remove(keyOf(transferId, fileId)) }
                entry.receiver.finish()
            }
            return true
        }
        FileChunkAck.decode(bytes)?.let { ack ->
            synchronized(lock) { outboundAcks[ack.transferId] = ack.ackedUpTo }
            return true
        }
        // 既不是数据帧也不是 ACK:交给上层(它会按 Envelope 处理或丢弃)。
        return false
    }

    /** 查询某次出站传输的最新确认（发送端窗口用）。 */
    fun ackedUpTo(transferId: String, fileId: String): Long =
        synchronized(lock) { outboundAcks[transferIdKey(transferId, fileId)] ?: 0L }

    /** 推一个文件到对端；返回已确认字节数。 */
    suspend fun sendFile(
        peerId: String,
        transferId: String,
        fileId: String,
        source: FileChunkSource,
    ): Long {
        val frameKey = transferIdKey(transferId, fileId)
        return SecureChunkSender(chunkSize).send(
            transferId = frameKey,
            source = source,
            host = "",
            port = 0,
            send = { bytes, _, _ -> sendToPeer(peerId, bytes) },
            acks = { ackedUpTo(transferId, fileId) },
        )
    }

    /** 清理一次传输的全部在途状态（取消/完成后调用）。 */
    fun forget(transferId: String) {
        synchronized(lock) {
            inbound.keys.filter { it.startsWith(transferId + "#") }.forEach { inbound.remove(it) }
            outboundAcks.keys.filter { it.startsWith(transferId + "#") }.forEach { outboundAcks.remove(it) }
        }
    }

    private fun keyOf(transferId: String, fileId: String): String = transferId + "#" + fileId

    /**
     * 帧内的键：`"<transferId>#<fileId>"`，超出 128 字节就用哈希兜底。
     * 兜底必须**稳定**（同一输入同一输出），否则 ACK 路由不上。
     */
    private fun transferIdKey(transferId: String, fileId: String): String {
        val raw = keyOf(transferId, fileId)
        if (raw.toByteArray(Charsets.UTF_8).size <= FileChunkFrame.MAX_TID_BYTES) return raw
        val digest = com.kapp.tidings.core.crypto.Sha256()
        digest.update(raw.toByteArray(Charsets.UTF_8))
        return "#" + digest.hexDigest().take(48)
    }

    private fun splitKey(key: String): Pair<String, String>? {
        if (key.startsWith("#")) return null // 哈希兜底形态:只用于发端寻址,不收
        val idx = key.indexOf('#')
        if (idx <= 0 || idx == key.length - 1) return null
        return key.substring(0, idx) to key.substring(idx + 1)
    }
}
