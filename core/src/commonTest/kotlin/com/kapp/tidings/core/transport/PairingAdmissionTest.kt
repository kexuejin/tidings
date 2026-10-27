// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 配对帧与配对准入的守门测试。
 *
 * 为什么加这一组：`PairingAdmission` 原来在 `jvmSharedMain`（只有 JVM 能编），
 * 而仓库里**没有任何一个用例覆盖它** —— 也就是说"密钥比较"这段安全关键逻辑
 * 一直没有回归保护。搬到 commonMain 时把 `MessageDigest.isEqual` 换成了自带的
 * `constantTimeEquals`，那是一次**语义敏感**的替换：换成 `contentEquals` 同样能
 * 通过功能测试，却会退化成可被时序攻击利用的比较。所以这里必须显式钉住。
 */
class PairingAdmissionTest {

    // ---------------------------------------------------------------- 常量时间比较

    @Test
    fun constantTimeEqualsAcceptsSameBytes() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun constantTimeEqualsRejectsDifferentLength() {
        // 长度不同直接 false：这本身不泄露"前几位对不对"。
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(ByteArray(0), byteArrayOf(0)))
    }

    @Test
    fun constantTimeEqualsRejectsFirstByteDifference() {
        assertFalse(constantTimeEquals(byteArrayOf(9, 2, 3), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun constantTimeEqualsRejectsLastByteDifference() {
        // 关键：差异在最后一位也必须 false —— 这正是"不能提前返回"要保证的。
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 9), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun constantTimeEqualsHandlesHighBitBytes() {
        // Kotlin 的 Byte 是有符号的：0xFF.toInt() == -1。用 xor 比较必须不受符号影响，
        // 否则 0xFF 与 0x7F 这类高位差异会被误判成相等。
        assertFalse(constantTimeEquals(byteArrayOf(0xFF.toByte()), byteArrayOf(0x7F)))
        assertTrue(constantTimeEquals(byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte())))
    }

    // ---------------------------------------------------------------- 配对帧往返

    @Test
    fun pairingRequestRoundTrips() {
        val pub = ByteArray(32) { (it % 251).toByte() }
        val secret = ByteArray(16) { (it * 7 % 251).toByte() }
        val frame = PairingFrame.encodeRequest("node-1", pub, secret, name = "桌面")
        assertTrue(PairingFrame.looksLikePairingFrame(frame), "自编码的帧应被识别为配对帧")
        val decoded = PairingFrame.decodeRequest(frame)
        assertEquals("node-1", decoded?.nodeId)
        assertEquals(pub.toList(), decoded?.publicKey?.toList())
        assertEquals(secret.toList(), decoded?.secret?.toList())
    }

    @Test
    fun pairingReplyRoundTrips() {
        val ok = PairingFrame.encodeReply(true)
        assertEquals(true to null, PairingFrame.decodeReply(ok))
        val bad = PairingFrame.encodeReply(false, "fingerprint_mismatch")
        assertEquals(false to "fingerprint_mismatch", PairingFrame.decodeReply(bad))
    }

    @Test
    fun nonPairingBytesAreNotDecodedAsPairing() {
        // 普通协议帧（Envelope）绝不能走进配对通道：判据错了会把业务帧整条吞掉。
        val envelopeLike = byteArrayOf(0x7B, 0x22, 0x74, 0x22, 0x3A, 0x22) // {"t":"
        assertFalse(PairingFrame.looksLikePairingFrame(envelopeLike))
        assertNull(PairingFrame.decodeRequest(envelopeLike))
        assertNull(PairingFrame.decodeReply(envelopeLike))
    }
}
