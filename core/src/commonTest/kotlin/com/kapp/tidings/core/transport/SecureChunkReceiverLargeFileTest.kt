// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 大文件的两条硬约束（真机 ≥50MB 验收前必须钉住）：
 *
 *  1. **收块标记不许按字节**：50MB 文件用 `BooleanArray(size)` 就是 50MB 内存，
 *     而且是每个在途文件一份 —— 手机一次多文件传输能吃掉几百 MB。按块记只占
 *     `ceil(size/64KiB)` 个元素。
 *  2. **半块不许推进 ACK**：只有整块到齐才记完成，否则发送端会以为整块到了、
 *     跳过剩下的字节（那是丢数据）。
 */
class SecureChunkReceiverLargeFileTest {

    private val chunk = SecureChunkReceiver.CHUNK

    @Test
    fun tracksLargeFileByChunkNotByByte() = runTest {
        val total = 64L * 1024 * 1024 // 64MiB
        val receiver = SecureChunkReceiver("t-big", total) { }
        // 只喂最后一块:如果实现是按字节的 BooleanArray,这里会先分配 64M 个元素。
        val lastOffset = total - chunk
        assertTrue(receiver.onChunk(FileChunkFrame.Decoded("t-big", lastOffset, ByteArray(chunk))))
        assertEquals(0L, receiver.ackedUpTo, "中间没到,连续前缀必须还是 0")
        assertFalse(receiver.isComplete)
    }

    @Test
    fun halfChunkDoesNotAdvanceAckButDoesCountAsFresh() = runTest {
        val total = 3L * chunk
        val acks = mutableListOf<Long>()
        val receiver = SecureChunkReceiver("t-half", total) { acks += it }
        // 只盖住第一块的前半:是新数据(要落盘),但不能算"这块完成"。
        assertTrue(receiver.onChunk(FileChunkFrame.Decoded("t-half", 0, ByteArray(chunk / 2))), "半块也是新数据")
        assertEquals(0L, receiver.ackedUpTo, "半块绝不许推进连续前缀")
        assertTrue(acks.isEmpty(), "没有前进不该发 ACK")

        // 补齐第一块的剩余部分:这时整块到齐,连续前缀必须前进一整块。
        assertTrue(receiver.onChunk(FileChunkFrame.Decoded("t-half", (chunk / 2).toLong(), ByteArray(chunk / 2))))
        assertEquals(chunk.toLong(), receiver.ackedUpTo)
    }

    @Test
    fun tailChunkSmallerThanChunkStillCompletes() = runTest {
        // 文件不是块大小整数倍:尾块按真实长度收齐后必须完成。
        val total = 2L * chunk + 1234
        val acks = mutableListOf<Long>()
        val receiver = SecureChunkReceiver("t-tail", total) { acks += it }
        receiver.onChunk(FileChunkFrame.Decoded("t-tail", 0, ByteArray(chunk)))
        receiver.onChunk(FileChunkFrame.Decoded("t-tail", chunk.toLong(), ByteArray(chunk)))
        receiver.onChunk(FileChunkFrame.Decoded("t-tail", 2L * chunk, ByteArray(1234)))
        assertTrue(receiver.isComplete)
        assertEquals(total, receiver.ackedUpTo)
        assertEquals(total, acks.last())
    }

    @Test
    fun outOfOrderChunksAssembleThenAdvanceAtOnce() = runTest {
        val total = 4L * chunk
        val acks = mutableListOf<Long>()
        val receiver = SecureChunkReceiver("t-order", total) { acks += it }
        receiver.onChunk(FileChunkFrame.Decoded("t-order", 2L * chunk, ByteArray(chunk)))
        receiver.onChunk(FileChunkFrame.Decoded("t-order", 1L * chunk, ByteArray(chunk)))
        assertEquals(0L, receiver.ackedUpTo, "第 0 块没到,前两块先到也不能前进")
        receiver.onChunk(FileChunkFrame.Decoded("t-order", 0, ByteArray(chunk)))
        assertEquals(3L * chunk, receiver.ackedUpTo, "补上第 0 块后必须一次推进到第 3 块边界")
        receiver.onChunk(FileChunkFrame.Decoded("t-order", 3L * chunk, ByteArray(chunk)))
        assertTrue(receiver.isComplete)
    }
}
