// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

/**
 * 加密信道的文件数据面（goal 步骤 2 的主体）。
 *
 * ## 它做什么
 *
 * 收端：把对端经加密信道送来的 `FileChunkFrame` 写入 `.part`，**只按连续前缀回 ACK**，
 * 收齐后走 sha256 校验并转正（[ChunkFileReceiver]）。
 * 发端：把 [FileChunkSource] 按窗口切块送出，等 ACK 前进，超时整窗重传
 * （[SecureChunkSender]）。
 *
 * ## 它**不**做什么（刻意）
 *
 * 不解析 `file-ctl`、不决定"收不收"、不写聊天消息 —— 那些仍由 [TransferManager] 的
 * 现有状态机负责。这一层只搬字节，所以可以单独测、也可以被 `fd1` 协商整体切掉。
 */
class SecureChannelDataPlane(
    /** 发一帧到对端（就是 `DatagramTransport.send`，这里只要形状）。 */
    private val sendFrame: suspend (peerId: String, bytes: ByteArray) -> Boolean,
    /** 收端：对端在 `done` 里给的 sha256 与总大小（键 = relPath）。 */
    private val targets: Map<String, ChunkFileTarget>,
    private val chunkSize: Int = 64 * 1024,
    private val windowChunks: Int = 32,
) {
    /**
     * 接收一个文件：喂块 → 回 ACK → 收齐后校验转正。
     *
     * @param receive 挂起直到没有更多块（由调用方从信道收帧后推进来）。
     * @return 落盘后的最终路径；失败抛 [PullException]。
     */
    suspend fun receiveFile(
        transferId: String,
        target: ChunkFileTarget,
        root: String,
        receive: suspend (onFrame: suspend (FileChunkFrame.Decoded) -> Unit) -> Unit,
        sendAck: suspend (Long) -> Unit,
    ): String {
        val receiver = ChunkFileReceiver(target, root)
        if (!receiver.valid) throw PullException("path-escape", PullStage.Write)
        val chunkReceiver = SecureChunkReceiver(transferId, target.expectedSize) { upTo ->
            sendAck(upTo)
        }
        receive { frame ->
            if (chunkReceiver.onChunk(frame)) receiver.append(frame.payload)
        }
        if (!chunkReceiver.isComplete) {
            receiver.discard()
            throw PullException("closed", PullStage.Receive)
        }
        return receiver.finish()
    }

    /** 发送一个文件：按窗口发块、等 ACK，返回已确认字节数。 */
    suspend fun sendFile(
        transferId: String,
        source: FileChunkSource,
        peerId: String,
        acks: suspend () -> Long?,
    ): Long = SecureChunkSender(chunkSize, windowChunks).send(
        transferId = transferId,
        source = source,
        host = "",
        port = 0,
        send = { bytes, _, _ -> sendFrame(peerId, bytes) },
        acks = acks,
    )
}
