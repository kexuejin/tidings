// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PBKDF2-HMAC-SHA256 的公开测试向量（draft-josefsson-pbkdf2-test-vectors 的 SHA-256 组，
 * 也是社区引用最广的一组）。恢复口令的 KDF 跨端必须逐字节一致，向量钉死是底线。
 */
class Pbkdf2VectorTest {

    private fun assertVector(password: String, salt: String, iterations: Int, dkLen: Int, expectedHex: String) {
        val derived = pbkdf2HmacSha256(
            passwordUtf8 = password.encodeToByteArray(),
            salt = salt.encodeToByteArray(),
            iterations = iterations,
            keyLengthBytes = dkLen,
        )
        assertEquals(dkLen, derived.size)
        assertEquals(expectedHex, derived.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    }

    @Test
    fun oneIteration() {
        assertVector(
            password = "password",
            salt = "salt",
            iterations = 1,
            dkLen = 32,
            expectedHex = "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
        )
    }

    @Test
    fun twoIterations() {
        assertVector(
            password = "password",
            salt = "salt",
            iterations = 2,
            dkLen = 32,
            expectedHex = "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
        )
    }

    @Test
    fun fourThousandNinetySixIterations() {
        assertVector(
            password = "password",
            salt = "salt",
            iterations = 4096,
            dkLen = 32,
            expectedHex = "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
        )
    }

    @Test
    fun multiBlockFortyBytes() {
        assertVector(
            password = "passwordPASSWORDpassword",
            salt = "saltSALTsaltSALTsaltSALTsaltSALTsalt",
            iterations = 4096,
            dkLen = 40,
            expectedHex = "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
        )
    }
}
