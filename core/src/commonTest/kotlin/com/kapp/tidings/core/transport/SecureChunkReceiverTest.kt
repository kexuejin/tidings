// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 接收端的**不变量的单测**（不依赖网络）：连续前缀 ACK、重复块、空洞、越界。
 *
 * 为什么单独立在这里：`SecureChunkSenderTest` 是端到端（真实信道），它证明"能传对"；
 * 但"重复块不重写"、"有空洞绝不报全收"这两条只有构造式用例能钉死。
 */
class SecureChunkReceiverTest {

    private val tid = "t-1"
    private val total = 64 * 1024 * 3L

    private fun chunk(offset: Long, len: Int = 64 * 1024, payload: ByteArray? = null): FileChunkFrame.Decoded =
        FileChunkFrame.Decoded(tid, offset, payload ?: ByteArray(len) { (it % 251).toByte() })

    @Test
    fun contiguousChunksAdvanceAckAndComplete() = runTest {
        val acks = mutableListOf<Long>()
        val receiver = SecureChunkReceiver(tid, total) { acks += it }
        assertTrue(receiver.onChunk(chunk(0)))
        assertEquals(64 * 1024L, receiver.ackedUpTo)
        assertTrue(receiver.onChunk(chunk(64 * 1024L)))
        assertEquals(128 * 1024L, receiver.ackedUpTo)
        assertTrue(receiver.onChunk(chunk(128 * 1024L)))
        assertTrue(receiver.isComplete)
        assertEquals(listOf(65536L, 131072L, 196608L), acks)
    }

    @Test
    fun holeDoesNotAdvanceAck() = runTest {
        val acks = mutableListOf<Long>()
        val receiver = SecureChunkReceiver(tid, total) { acks += it }
        assertTrue(receiver.onChunk(chunk(64 * 1024L)), "第二块是新数据")
        assertEquals(0L, receiver.ackedUpTo, "第一块没到,连续前缀必须还是 0")
        assertFalse(receiver.isComplete)
        assertTrue(acks.isEmpty(), "没有前进时不该发 ACK")
        // 补上空洞后必须一次性前进到 128KiB
        assertTrue(receiver.onChunk(chunk(0)))
        assertEquals(128 * 1024L, receiver.ackedUpTo, "补洞后连续前缀要跟上来")
        assertEquals(listOf(131072L), acks)
    }

    @Test
    fun duplicateChunkIsNotFreshButStillAcks() = runTest {
        val acks = mutableListOf<Long>()
        val receiver = SecureChunkReceiver(tid, total) { acks += it }
        assertTrue(receiver.onChunk(chunk(0)))
        assertFalse(receiver.onChunk(chunk(0)), "重复块不算新数据")
        assertEquals(2, acks.size, "重复块也必须回 ACK,否则对端重传后一直等")
        assertEquals(receiver.ackedUpTo, acks.last())
    }

    @Test
    fun wrongTransferIdAndOutOfRangeAreIgnored() = runTest {
        val acks = mutableListOf<Long>()
        val receiver = SecureChunkReceiver(tid, total) { acks += it }
        assertFalse(receiver.onChunk(FileChunkFrame.Decoded("other", 0, ByteArray(16))))
        assertFalse(receiver.onChunk(chunk(total, 16)), "越过总长的块必须丢")
        assertFalse(receiver.onChunk(chunk(-1, 16)), "负 offset 必须丢")
        assertEquals(0L, receiver.ackedUpTo)
        assertTrue(acks.isEmpty())
    }

    @Test
    fun byteArraySourceReadsAtOffset() = runTest {
        val payload = ByteArray(1000) { it.toByte() }
        val source = ByteArrayChunkSource(payload)
        assertEquals(1000L, source.size)
        assertContentEquals(payload.copyOfRange(100, 164), source.read(100, 64))
        assertEquals(36, source.read(964, 64).size, "末尾可以是短块")
        assertEquals(0, source.read(1000, 64).size, "越界返回空,不抛")
    }
}
