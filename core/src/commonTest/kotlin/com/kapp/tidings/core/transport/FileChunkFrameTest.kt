// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 文件数据帧:往返、坏帧一律拒绝(不许"尽力解析"出半截数据)。 */
class FileChunkFrameTest {

    @Test
    fun roundTripsIdOffsetAndPayload() {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        val frame = FileChunkFrame.encode("t-1234", 987654321L, payload)
        val decoded = FileChunkFrame.decode(frame) ?: error("must decode")
        assertEquals("t-1234", decoded.transferId)
        assertEquals(987654321L, decoded.offset)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun rejectsBadMagicVersionAndTruncation() {
        val good = FileChunkFrame.encode("t", 0, ByteArray(16))
        val badMagic = good.copyOf().also { it[0] = 0 }
        assertNull(FileChunkFrame.decode(badMagic))
        val badVersion = good.copyOf().also { it[4] = 9 }
        assertNull(FileChunkFrame.decode(badVersion))
        assertNull(FileChunkFrame.decode(good.copyOfRange(0, good.size - 1)), "缺 1 字节必须拒绝")
        assertNull(FileChunkFrame.decode(ByteArray(4)))
    }

    @Test
    fun ackFrameRoundTripsAndIsStrict() {
        val ack = FileChunkAck.encode("t-9", 123456789L)
        val decoded = FileChunkAck.decode(ack) ?: error("ack must decode")
        assertEquals("t-9", decoded.transferId)
        assertEquals(123456789L, decoded.ackedUpTo)
        assertTrue(FileChunkAck.isAck(ack))
        assertNull(FileChunkAck.decode(ack.copyOfRange(0, ack.size - 1)), "缺字节必须拒绝")
        assertNull(FileChunkAck.decode(ack.copyOf().also { it[3] = 0 }), "坏 magic 必须拒绝")
    }

    @Test
    fun rejectsOversizedPayloadAndEmptyId() {
        assertNull(runCatching { FileChunkFrame.encode("t", 0, ByteArray(FileChunkFrame.MAX_PAYLOAD_BYTES + 1)) }.getOrNull())
        assertNull(runCatching { FileChunkFrame.encode("", 0, ByteArray(1)) }.getOrNull())
    }
}
