// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task-26 第 4 步的判据本体：**对端 SPKI ↔ 期望 pin**。
 *
 * 真机/TLS 层能验的是"握手成不成"；这条钉的是"判据本身对不对"——
 * 而它恰恰是安全边界（判错了就是"随便谁都能连进来"）。
 */
class LinkPinVerifyTest {

    private val spki = ByteArray(91) { (it + 1).toByte() }
    private val pin = TlsPin.ofSpki(spki)

    @Test
    fun theMatchingPinIsAccepted() {
        assertTrue(peerPinAccepted(spki, pin), "同一份 SPKI 必须通过")
    }

    @Test
    fun aDifferentCertificateIsRejected() {
        val other = ByteArray(91) { (it + 2).toByte() }
        assertFalse(peerPinAccepted(other, pin), "换一张证书必须拒")
    }

    @Test
    fun aMissingPeerCertificateIsRejected() {
        assertFalse(peerPinAccepted(null, pin), "对端没给证书必须拒（fail-closed）")
    }

    @Test
    fun aMissingExpectedPinIsRejected() {
        assertFalse(peerPinAccepted(spki, null), "我们没有期望 pin 时必须拒 —— 没验不等于验过")
        assertFalse(peerPinAccepted(null, null), "两边都没有更必须拒")
    }

    @Test
    fun garbageSpkiIsRejectedInsteadOfCrashing() {
        assertFalse(peerPinAccepted(ByteArray(3), pin), "形状不对的 SPKI 必须拒，而不是抛出去")
    }
}
