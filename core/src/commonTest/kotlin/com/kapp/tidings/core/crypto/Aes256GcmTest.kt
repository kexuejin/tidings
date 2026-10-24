// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * AES-256-GCM：GCM 规范（draft-mcgrew-gcm）Test Case 14 钉死互操作口径，
 * 再补认证失败的三条边界（改密文 / 改 AAD / 错钥匙 —— 在 tag 上是同一件事）。
 */
class Aes256GcmTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    /** gcm-spec.pdf Test Case 15：无 AAD，明文 64 字节（整块）。 */
    @Test
    fun gcmSpecTestCase15() {
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val nonce = hex("cafebabefacedbaddecaf888")
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
        )
        val expectedCiphertext = hex(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad",
        )
        val expectedTag = hex("b094dac5d93471bdec1a502270e3cc6c")

        val sealed = aes256GcmSeal(key, nonce, plaintext, ByteArray(0))
        assertContentEquals(expectedCiphertext, sealed.copyOfRange(0, sealed.size - AES_GCM_TAG_BYTES), "密文")
        assertContentEquals(expectedTag, sealed.copyOfRange(sealed.size - AES_GCM_TAG_BYTES, sealed.size), "tag")

        val opened = aes256GcmOpen(key, nonce, sealed, ByteArray(0))
        assertContentEquals(plaintext, opened, "开封回明文")
    }

    /** gcm-spec.pdf Test Case 16：有 AAD（20 字节），明文 60 字节。 */
    @Test
    fun gcmSpecTestCase16() {
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val nonce = hex("cafebabefacedbaddecaf888")
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
        )
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val expectedCiphertext = hex(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662",
        )
        val expectedTag = hex("76fc6ece0f4e1768cddf8853bb2d551b")

        val sealed = aes256GcmSeal(key, nonce, plaintext, aad)
        assertContentEquals(expectedCiphertext, sealed.copyOfRange(0, sealed.size - AES_GCM_TAG_BYTES), "密文")
        assertContentEquals(expectedTag, sealed.copyOfRange(sealed.size - AES_GCM_TAG_BYTES, sealed.size), "tag")

        val opened = aes256GcmOpen(key, nonce, sealed, aad)
        assertContentEquals(plaintext, opened, "开封回明文")
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val sealed = aes256GcmSeal(key, nonce, ByteArray(0), ByteArray(0))
        assertEquals(AES_GCM_TAG_BYTES, sealed.size, "空明文只剩 tag")
        assertContentEquals(ByteArray(0), aes256GcmOpen(key, nonce, sealed, ByteArray(0)))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12)
        val sealed = aes256GcmSeal(key, nonce, "hello".encodeToByteArray(), "aad".encodeToByteArray())
        sealed[0] = (sealed[0].toInt() xor 0x01).toByte()
        assertFailsWith<AeadException> { aes256GcmOpen(key, nonce, sealed, "aad".encodeToByteArray()) }
    }

    @Test
    fun tamperedAadIsRejected() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12)
        val sealed = aes256GcmSeal(key, nonce, "hello".encodeToByteArray(), "aad".encodeToByteArray())
        assertFailsWith<AeadException> { aes256GcmOpen(key, nonce, sealed, "aad-x".encodeToByteArray()) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val key = ByteArray(32) { it.toByte() }
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12)
        val sealed = aes256GcmSeal(key, nonce, "hello".encodeToByteArray(), ByteArray(0))
        assertFailsWith<AeadException> { aes256GcmOpen(otherKey, nonce, sealed, ByteArray(0)) }
    }

    @Test
    fun truncatedSealedIsRejected() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12)
        assertFailsWith<AeadException> {
            aes256GcmOpen(key, nonce, ByteArray(AES_GCM_TAG_BYTES), ByteArray(0))
        }
    }
}
