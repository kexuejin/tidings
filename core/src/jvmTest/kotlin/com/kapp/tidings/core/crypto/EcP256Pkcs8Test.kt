// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 标量 ↔ PKCS#8 互转用**真 provider 的钥匙**验证：
 *  · 提取路径必须吃下 provider 生成的标准编码（SunEC 的 PKCS#8 带完整参数与公钥字段）；
 *  · 构建路径的最小模板必须能被 provider 解析回同一把钥匙。
 * 两侧都过关，恢复包里的标量才能在 JVM 系两端（桌面 / Android）落地。
 */
class EcP256Pkcs8Test {

    private fun newP256KeyPair(): ECPrivateKey {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair().private as ECPrivateKey
    }

    private fun fixed32(s: java.math.BigInteger): ByteArray {
        // BigInteger.toByteArray() 在最高位是 1 时会补一个符号 0 字节（33 字节），剥掉。
        val raw = s.toByteArray()
        val trimmed = if (raw.size > 32 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        return ByteArray(32).also { trimmed.copyInto(it, 32 - trimmed.size) }
    }

    @Test
    fun extractsTheScalarFromAProviderEncodedKey() {
        val key = newP256KeyPair()
        val scalar = EcP256Pkcs8.pkcs8ToScalar(key.encoded)
        assertContentEquals(fixed32(key.s), scalar, "提取出的标量必须等于 provider 的 s（定宽 32 字节）")
    }

    @Test
    fun minimalTemplateParsesBackToTheSameKey() {
        val key = newP256KeyPair()
        val scalar = EcP256Pkcs8.pkcs8ToScalar(key.encoded)
        val template = EcP256Pkcs8.scalarToPkcs8(scalar)
        val parsed = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(template)) as ECPrivateKey
        assertEquals(key.s, parsed.s, "最小模板必须能被 KeyFactory 解析回同一把钥匙")
    }

    @Test
    fun templateRoundTripsThroughOurOwnParser() {
        val scalar = ByteArray(32) { (it * 11 + 2).toByte() }
        val bytes = EcP256Pkcs8.scalarToPkcs8(scalar)
        assertContentEquals(scalar, EcP256Pkcs8.pkcs8ToScalar(bytes))
    }

    @Test
    fun shortScalarIsLeftPaddedToFixedWidth() {
        val short = byteArrayOf(0x01, 0x02, 0x03)
        val bytes = EcP256Pkcs8.scalarToPkcs8(short)
        val parsed = EcP256Pkcs8.pkcs8ToScalar(bytes)
        assertEquals(32, parsed.size)
        assertEquals(0, parsed[0].toInt())
        assertEquals(0x01, parsed[29].toInt())
        assertEquals(0x03, parsed[31].toInt())
    }
}
