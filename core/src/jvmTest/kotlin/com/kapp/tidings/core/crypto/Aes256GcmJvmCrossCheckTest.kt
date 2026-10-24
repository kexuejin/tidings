// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * 纯 Kotlin GCM 与 JVM 平台实现（javax.crypto）的随机交叉验证：
 * 同一批随机输入，密文与 tag 必须逐字节一致 —— 这是"wire 字节与任何平台实现一致"的
 * 直接证据，也是公开向量之外的第二道独立校验。
 */
class Aes256GcmJvmCrossCheckTest {

    @Test
    fun matchesJavaxCryptoOnRandomInputs() {
        val random = java.security.SecureRandom()
        repeat(16) { round ->
            val key = ByteArray(32).also { random.nextBytes(it) }
            val nonce = ByteArray(12).also { random.nextBytes(it) }
            val aad = ByteArray(random.nextInt(64)).also { random.nextBytes(it) }
            val plaintext = ByteArray(random.nextInt(4096)).also { random.nextBytes(it) }

            val expected = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                if (aad.isNotEmpty()) updateAAD(aad)
                doFinal(plaintext)
            }
            assertContentEquals(expected, aes256GcmSeal(key, nonce, plaintext, aad), "round $round: seal")

            assertContentEquals(plaintext, aes256GcmOpen(key, nonce, expected, aad), "round $round: open")
        }
    }
}
