// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

/**
 * P-256 私钥标量 ↔ PKCS#8（DER）互转。
 *
 * 为什么要有它：恢复包里的私钥必须是**平台无关**的 32 字节标量 —— 桌面/Android
 * 的 `link-key.pk8` 是 PKCS#8，iOS 的 `link-key.x963` 本身就是标量。只有标量，
 * 同一份身份包才能在任意两端导入。
 *
 * 两个方向都是纯 DER 操作、不经过 provider：提取路径不依赖 provider 是否愿意
 * 暴露 `ECPrivateKey` 接口；构建路径产出**最小合法** PKCS#8（RFC 5958/5915，
 * 无可选的 parameters/publicKey 字段），`KeyFactory.getInstance("EC")` 直接可解析。
 */
object EcP256Pkcs8 {

    /** P-256 私钥标量的固定长度。 */
    const val SCALAR_BYTES: Int = 32

    /** OID 1.2.840.10045.2.1（ecPublicKey）的 DER 内容字节。 */
    private val OID_EC_PUBLIC_KEY = byteArrayOf(0x2a.toByte(), 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01)

    /** OID 1.2.840.10045.3.1.7（prime256v1）的 DER 内容字节。 */
    private val OID_PRIME256V1 = byteArrayOf(0x2a.toByte(), 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07)

    /** 标量 → PKCS#8。非法标量（>32 字节）抛 [IllegalArgumentException]。 */
    fun scalarToPkcs8(scalar: ByteArray): ByteArray {
        require(scalar.size in 1..SCALAR_BYTES) { "P-256 scalar must be 1..32 bytes, got ${scalar.size}" }
        val fixed = ByteArray(SCALAR_BYTES)
        scalar.copyInto(fixed, SCALAR_BYTES - scalar.size)

        val ecPrivateKey = der(0x30, der(0x02, byteArrayOf(0x01)) + der(0x04, fixed))
        val algId = der(0x30, der(0x06, OID_EC_PUBLIC_KEY) + der(0x06, OID_PRIME256V1))
        return der(0x30, der(0x02, byteArrayOf(0x00)) + algId + der(0x04, ecPrivateKey))
    }

    /** PKCS#8 → 标量（固定 32 字节）。形状不对抛 [IllegalArgumentException]。 */
    fun pkcs8ToScalar(pkcs8: ByteArray): ByteArray {
        val outer = DerReader(pkcs8).tlv(0x30, "PKCS8")
        val top = DerReader(outer)
        val version = top.tlv(0x02, "PKCS8 version")
        require(version.size == 1 && version[0].toInt() == 0) { "PKCS8 version must be 0" }
        top.tlv(0x30, "algorithmIdentifier") // 内容（两个 OID）与本转换无关，跳过
        val privateKeyBytes = top.tlv(0x04, "privateKey OCTET STRING")

        val ec = DerReader(privateKeyBytes)
        val ecInner = ec.tlv(0x30, "ECPrivateKey")
        val ecReader = DerReader(ecInner)
        val ecVersion = ecReader.tlv(0x02, "ECPrivateKey version")
        require(ecVersion.size == 1 && ecVersion[0].toInt() == 1) { "ECPrivateKey version must be 1" }
        val scalar = ecReader.tlv(0x04, "ECPrivateKey scalar")
        require(scalar.size in 1..SCALAR_BYTES) { "P-256 scalar must be 1..32 bytes, got ${scalar.size}" }
        // 后面可能还有可选的 [0] parameters / [1] publicKey —— 不用看，标量已经到手。
        return ByteArray(SCALAR_BYTES).also { scalar.copyInto(it, SCALAR_BYTES - scalar.size) }
    }

    // ---- DER 最小读写（只需要 SEQUENCE / INTEGER / OCTET STRING，长度 < 65536） ----
    // 关键纪律：一次 tlv() 必须完整消费 tag + length + content，绝不留下半个长度字段。

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val length = content.size
        require(length < 0x8000) { "DER content too long" }
        val header = if (length < 0x80) {
            byteArrayOf(tag.toByte(), length.toByte())
        } else {
            byteArrayOf(tag.toByte(), 0x82.toByte(), (length ushr 8).toByte(), length.toByte())
        }
        return header + content
    }

    private class DerReader(private val bytes: ByteArray) {
        private var pos = 0

        /** 读一个完整 TLV 并返回内容；tag 不符或长度越界抛 [IllegalArgumentException]。 */
        fun tlv(expectedTag: Int, what: String): ByteArray {
            require(pos < bytes.size) { "DER $what: truncated" }
            require(bytes[pos].toInt() and 0xff == expectedTag) {
                "DER $what: expected tag 0x${expectedTag.toString(16)}, got 0x${(bytes[pos].toInt() and 0xff).toString(16)}"
            }
            pos++
            val first = bytes[pos].toInt() and 0xff
            pos++
            val length = if (first < 0x80) {
                first
            } else {
                val n = first - 0x80
                require(n in 1..2) { "DER $what: unsupported long-form length 0x${first.toString(16)}" }
                require(pos + n <= bytes.size) { "DER $what: truncated length" }
                var length = 0
                repeat(n) {
                    length = (length shl 8) or (bytes[pos].toInt() and 0xff)
                    pos++
                }
                length
            }
            require(pos + length <= bytes.size) { "DER $what: length $length overruns input" }
            val content = bytes.copyOfRange(pos, pos + length)
            pos += length
            return content
        }
    }
}
