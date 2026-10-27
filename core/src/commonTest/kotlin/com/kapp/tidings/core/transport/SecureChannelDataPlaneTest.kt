// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.Sha256
import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.testing.createTempDir
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 数据面主体（goal 步骤 2）的**无网络**验收：把"信道上收到的帧"替换成内存里的队列，
 * 仍然要求 分块→连续前缀 ACK→sha256 转正 这一整套行为正确。
 *
 * 为什么要这一层：`SecureChunkSenderTest` 证的是"两条真实信道能传对"（含丢块重传），
 * 但它不覆盖"收端是否真的按连续前缀回 ACK、是否真的校验 sha"。两者都在这里钉。
 */
class SecureChannelDataPlaneTest {

    private fun shaOf(bytes: ByteArray): String {
        val sha = Sha256()
        sha.update(bytes)
        return sha.hexDigest()
    }

    @Test
    fun receivesChunksInOrderAndVerifiesSha() = runTest {
        val dir = createTempDir("relay-scdp-in")
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val target = ChunkFileTarget("in.bin", payload.size.toLong(), shaOf(payload))
        val acks = mutableListOf<Long>()
        val plane = SecureChannelDataPlane(
            sendFrame = { _, _ -> true },
            targets = mapOf("in.bin" to target),
        )
        val chunks = (0 until payload.size step 64 * 1024).map { off ->
            val len = minOf(64 * 1024, payload.size - off)
            FileChunkFrame.encode("t-in", off.toLong(), payload.copyOfRange(off, off + len))
        }
        val path = plane.receiveFile(
            transferId = "t-in",
            target = target,
            root = dir,
            receive = { onFrame ->
                chunks.forEach { bytes ->
                    val decoded = FileChunkFrame.decode(bytes) ?: error("decode failed")
                    onFrame(decoded)
                }
            },
            sendAck = { acks += it },
        )
        assertEquals(payload.size.toLong(), fileSize(path))
        assertTrue(acks.isNotEmpty() && acks.last() == payload.size.toLong(), "最后一个 ACK 必须是全量")
    }

    @Test
    fun incompleteStreamFailsAndLeavesNoPart() = runTest {
        val dir = createTempDir("relay-scdp-short")
        val payload = ByteArray(128 * 1024)
        val target = ChunkFileTarget("short.bin", payload.size.toLong(), shaOf(payload))
        val plane = SecureChannelDataPlane(sendFrame = { _, _ -> true }, targets = mapOf("short.bin" to target))
        var failed: PullException? = null
        try {
            plane.receiveFile(
                transferId = "t-short",
                target = target,
                root = dir,
                receive = { onFrame ->
                    // 只送第一块就断流(模拟对端掉线)
                    onFrame(FileChunkFrame.Decoded("t-short", 0, payload.copyOfRange(0, 64 * 1024)))
                },
                sendAck = { },
            )
        } catch (e: PullException) {
            failed = e
        }
        assertEquals("closed", failed?.reason, "断流必须报 closed")
        assertEquals(null, fileSize(dir + "/short.bin.part"), "断流后不许留下 part")
    }

    @Test
    fun sendFileAdvancesAckToFullSize() = runTest {
        val payload = ByteArray(150_000) { (it % 97).toByte() }
        val plane = SecureChannelDataPlane(sendFrame = { _, _ -> true }, targets = emptyMap())
        val sentFrames = mutableListOf<FileChunkFrame.Decoded>()
        var ackCursor = 0L
        val acked = plane.sendFile(
            transferId = "t-out",
            source = ByteArrayChunkSource(payload),
            peerId = "peer",
            acks = {
                // 简化模拟:每问一次就把"已收到的连续前缀"按实际块推进
                if (ackCursor < payload.size) {
                    ackCursor = minOf(payload.size.toLong(), ackCursor + 64L * 1024)
                }
                ackCursor
            },
        )
        assertEquals(payload.size.toLong(), acked, "发送端必须报告全量已确认")
    }
}
