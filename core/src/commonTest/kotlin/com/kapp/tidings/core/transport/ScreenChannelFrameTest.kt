// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 投屏封套：魔数认帧、长度严格相等、上限之外一律不猜。 */
class ScreenChannelFrameTest {

    @Test
    fun wrapThenUnwrapRoundTrips() {
        val payload = ByteArray(1234) { (it % 251).toByte() }
        val frame = ScreenChannelFrame.wrap(payload)
        assertTrue(ScreenChannelFrame.isScreenFrame(frame))
        assertContentEquals(payload, ScreenChannelFrame.unwrap(frame))
    }

    @Test
    fun emptyPayloadIsLegalFrame() {
        val frame = ScreenChannelFrame.wrap(ByteArray(0))
        assertTrue(ScreenChannelFrame.isScreenFrame(frame))
        assertContentEquals(ByteArray(0), ScreenChannelFrame.unwrap(frame))
    }

    @Test
    fun envelopeAndFileFramesAreNotScreenFrames() {
        // 消息 Envelope 的起始是 '{'，文件块起始是 "TDCH" —— 都不许被认成画面帧。
        assertFalse(ScreenChannelFrame.isScreenFrame("{\"v\":1}".encodeToByteArray()))
        assertFalse(ScreenChannelFrame.isScreenFrame(FileChunkFrame.encode("t", 0, ByteArray(8))))
        assertFalse(ScreenChannelFrame.isScreenFrame(ByteArray(0)))
        assertFalse(ScreenChannelFrame.isScreenFrame(byteArrayOf('T'.code.toByte())))
    }

    @Test
    fun truncatedOrOverlongFrameIsRejected() {
        val payload = ByteArray(64) { 7 }
        val frame = ScreenChannelFrame.wrap(payload)
        // 少一个字节：长度对不上 ⇒ 坏帧（本层不负责重组，绝不"尽力取前面那截"）。
        assertNull(ScreenChannelFrame.unwrap(frame.copyOfRange(0, frame.size - 1)))
        // 多一个字节同理。
        assertNull(ScreenChannelFrame.unwrap(frame + byteArrayOf(0)))
    }

    @Test
    fun declaredLengthBeyondPayloadIsRejected() {
        val frame = ScreenChannelFrame.wrap(ByteArray(4))
        // 把声明长度改大，但字节没跟上。
        frame[7] = (frame[7] + 1).toByte()
        assertNull(ScreenChannelFrame.unwrap(frame))
    }

    @Test
    fun oversizedPayloadIsRefusedAtWrap() {
        var failed = false
        try {
            ScreenChannelFrame.wrap(ByteArray(ScreenChannelFrame.MAX_PAYLOAD + 1))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed, "超过上限必须当场拒绝，而不是封出一帧收端读不了的字节")
    }
}
