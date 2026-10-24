// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

/**
 * AES-256-GCM，纯 Kotlin（commonMain 一份，三端逐字节一致）。
 *
 * 为什么不用平台实现：Android/JVM 的 `javax.crypto` 可用，但 iOS 侧 Kotlin/Native 的
 * `platform.CoreCrypto` **不暴露 GCM**（那些函数住在 CommonCryptorSPI.h），CryptoKit 又是
 * Swift 框架进不了 Kotlin —— 要么三端两套口径，要么一份公共实现。本仓的立场与
 * [Sha256]/[pbkdf2HmacSha256] 一致：自包含、有公开测试向量可钉死的算法，commonMain
 * 一份更好测。AES-GCM 是标准算法，wire 字节与任何平台实现一致 —— 将来全量快照
 * （GB 级）嫌纯 Kotlin 慢，可以在**不改变格式**的前提下把 JVM 侧换回 javax.crypto。
 *
 * GCM 只需要 AES 的**加密方向**（CTR 族），解密走"重加密密文 + 常数时间比 tag"，
 * 所以这里没有逆 S 盒与逆轮。
 */

/** AES-256-GCM 认证失败：错口令与密文被篡改在密码学上是同一件事（tag 对不上）。 */
class AeadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** GCM 认证 tag 长度，本仓固定 128 bit。 */
const val AES_GCM_TAG_BYTES: Int = 16

/**
 * AES-256-GCM 封装，返回 `ciphertext || tag`（tag 固定 [AES_GCM_TAG_BYTES] 在尾部）。
 *
 * `aad` 参与认证但不加密 —— 恢复包用它绑定**未加密的头部字段**
 * （magic / 版本 / KDF 参数 / salt / nonce），防止头部被拆开重组。
 */
fun aes256GcmSeal(
    key32: ByteArray,
    nonce12: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): ByteArray {
    checkKeyAndNonce(key32, nonce12)
    val cipher = Aes256(key32)
    val h = cipher.encryptBlock(ByteArray(BLOCK_BYTES)) // H = E_K(0^128)

    val j0 = ByteArray(BLOCK_BYTES)
    nonce12.copyInto(j0)
    j0[12] = 0; j0[13] = 0; j0[14] = 0; j0[15] = 1

    val ciphertext = ctrXor(cipher, j0, plaintext)
    val ghash = Ghash(h)
    ghash.update(aad)
    ghash.update(ciphertext)
    val tag = ghash.digest(aadSize = aad.size.toLong(), dataSize = ciphertext.size.toLong())
    // tag 掩码是 **E_K(J0)**（不是 J0 原文）—— 漏了这层加密，tag 全错但密文是对的。
    val tagMask = cipher.encryptBlock(j0)
    for (i in 0 until AES_GCM_TAG_BYTES) tag[i] = (tag[i].toInt() xor tagMask[i].toInt()).toByte()
    return ciphertext + tag
}

/** AES-256-GCM 开封（输入 `ciphertext || tag`）；认证不过抛 [AeadException]。 */
fun aes256GcmOpen(
    key32: ByteArray,
    nonce12: ByteArray,
    sealed: ByteArray,
    aad: ByteArray,
): ByteArray {
    checkKeyAndNonce(key32, nonce12)
    if (sealed.size < AES_GCM_TAG_BYTES) {
        throw AeadException("sealed payload too short: ${sealed.size}")
    }
    val ciphertextSize = sealed.size - AES_GCM_TAG_BYTES
    val ciphertext = sealed.copyOf(ciphertextSize)
    val expectedTag = sealed.copyOfRange(ciphertextSize, sealed.size)

    val cipher = Aes256(key32)
    val h = cipher.encryptBlock(ByteArray(BLOCK_BYTES))
    val j0 = ByteArray(BLOCK_BYTES)
    nonce12.copyInto(j0)
    j0[12] = 0; j0[13] = 0; j0[14] = 0; j0[15] = 1

    // 先算 tag 再解密：认证不过就不产出明文，调用方拿不到半截数据。
    val ghash = Ghash(h)
    ghash.update(aad)
    ghash.update(ciphertext)
    val tag = ghash.digest(aadSize = aad.size.toLong(), dataSize = ciphertext.size.toLong())
    val tagMask = cipher.encryptBlock(j0)
    for (i in 0 until AES_GCM_TAG_BYTES) tag[i] = (tag[i].toInt() xor tagMask[i].toInt()).toByte()
    if (!constantTimeEquals(tag, expectedTag)) {
        throw AeadException("GCM authentication failed")
    }
    return ctrXor(cipher, j0, ciphertext)
}

private fun checkKeyAndNonce(key32: ByteArray, nonce12: ByteArray) {
    require(key32.size == 32) { "AES-256 key must be 32 bytes, got ${key32.size}" }
    require(nonce12.size == 12) { "GCM nonce must be 12 bytes, got ${nonce12.size}" }
}

/** 常数时间比较：比较长度不随内容变，逐字节累计差异，不提前返回。 */
private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** CTR 流：E_K(J0), E_K(J0+1), … 与明文逐字节异或（末块只取前缀）。 */
private fun ctrXor(cipher: Aes256, j0: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size)
    val counter = j0.copyOf()
    // GCM 规范 §7.1：数据的 GCTR 从 **Inc32(J0)** 起 —— J0 本身只当 tag 掩码（E_K(J0)）。
    // 从 J0 起步会让密文整体错位一个块，且 tag 跟着错，看起来就像"算法全错"。
    inc32(counter)
    var offset = 0
    while (offset < data.size) {
        val keystream = cipher.encryptBlock(counter)
        val n = minOf(BLOCK_BYTES, data.size - offset)
        for (i in 0 until n) {
            out[offset + i] = (data[offset + i].toInt() xor keystream[i].toInt()).toByte()
        }
        offset += n
        inc32(counter)
    }
    return out
}

/** Inc_32：最后 4 字节做大端自增（GCM 规范 §5.2.1.1）。 */
private fun inc32(block: ByteArray) {
    for (i in 15 downTo 12) {
        val v = (block[i].toInt() and 0xff) + 1
        block[i] = v.toByte()
        if (v <= 0xff) return
    }
}

private const val BLOCK_BYTES = 16

/**
 * 纯 Kotlin AES-256 **加密方向**（FIPS-197）。
 *
 * 为什么只实现加密：GCM 是 CTR 族，加密与解密都只用正向轮函数 ——
 * 解密（[aes256GcmOpen]）走"重加密密文"路线，整个文件不需要逆 S 盒与逆轮。
 */
private class Aes256(key: ByteArray) {

    /** 展开后的轮密钥，15 组 × 16 字节。 */
    private val roundKeys: Array<ByteArray>

    init {
        roundKeys = expandKey(key)
    }

    fun encryptBlock(block: ByteArray): ByteArray {
        val state = block.copyOf(BLOCK_BYTES)
        addRoundKey(state, 0)
        for (round in 1 until ROUNDS) {
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, round)
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, ROUNDS)
        return state
    }

    private fun addRoundKey(state: ByteArray, round: Int) {
        val key = roundKeys[round]
        for (i in 0 until BLOCK_BYTES) state[i] = (state[i].toInt() xor key[i].toInt()).toByte()
    }

    private fun subBytes(state: ByteArray) {
        for (i in 0 until BLOCK_BYTES) state[i] = SBOX[state[i].toInt() and 0xff].toByte()
    }

    private fun shiftRows(state: ByteArray) {
        // state 按列存：state[r + 4c]。行 r 循环左移 r。
        for (r in 1 until 4) {
            val row = ByteArray(4)
            for (c in 0 until 4) row[c] = state[r + 4 * c]
            for (c in 0 until 4) state[r + 4 * c] = row[(c + r) and 3]
        }
    }

    private fun mixColumns(state: ByteArray) {
        // 系数 [2,3,1,1]：2·a = xtime(a)，3·a = xtime(a) ⊕ a。
        for (c in 0 until 4) {
            val base = 4 * c
            val a0 = state[base].toInt() and 0xff
            val a1 = state[base + 1].toInt() and 0xff
            val a2 = state[base + 2].toInt() and 0xff
            val a3 = state[base + 3].toInt() and 0xff
            state[base] = (xtime(a0) xor xtime(a1) xor a1 xor a2 xor a3).toByte()
            state[base + 1] = (a0 xor xtime(a1) xor xtime(a2) xor a2 xor a3).toByte()
            state[base + 2] = (a0 xor a1 xor xtime(a2) xor xtime(a3) xor a3).toByte()
            state[base + 3] = (xtime(a0) xor a0 xor a1 xor a2 xor xtime(a3)).toByte()
        }
    }

    private fun expandKey(key: ByteArray): Array<ByteArray> {
        val words = IntArray(60)
        for (i in 0 until 8) {
            words[i] = ((key[4 * i].toInt() and 0xff) shl 24) or
                ((key[4 * i + 1].toInt() and 0xff) shl 16) or
                ((key[4 * i + 2].toInt() and 0xff) shl 8) or
                (key[4 * i + 3].toInt() and 0xff)
        }
        for (i in 8 until 60) {
            val temp = words[i - 1]
            var t = temp
            if (i and 7 == 0) {
                t = (t shl 8) or (t ushr 24) // RotWord
                t = ((SBOX[(t ushr 24) and 0xff].toInt() and 0xff) shl 24) or
                    ((SBOX[(t ushr 16) and 0xff].toInt() and 0xff) shl 16) or
                    ((SBOX[(t ushr 8) and 0xff].toInt() and 0xff) shl 8) or
                    (SBOX[t and 0xff].toInt() and 0xff)
                t = t xor (RCON[i / 8 - 1] shl 24)
            } else if (i and 7 == 4) {
                // AES-256 特有（FIPS-197）：每第 4 个字额外过一遍 SubWord —— 无 RotWord、无 Rcon。
                // 漏掉它，从第二个轮密钥起全错，且没有任何崩溃迹象，只有公开向量能抓出来。
                t = ((SBOX[(t ushr 24) and 0xff].toInt() and 0xff) shl 24) or
                    ((SBOX[(t ushr 16) and 0xff].toInt() and 0xff) shl 16) or
                    ((SBOX[(t ushr 8) and 0xff].toInt() and 0xff) shl 8) or
                    (SBOX[t and 0xff].toInt() and 0xff)
            }
            words[i] = words[i - 8] xor t
        }
        return Array(15) { r ->
            ByteArray(BLOCK_BYTES).also { out ->
                for (i in 0 until 4) {
                    val w = words[r * 4 + i]
                    out[4 * i] = (w ushr 24).toByte()
                    out[4 * i + 1] = (w ushr 16).toByte()
                    out[4 * i + 2] = (w ushr 8).toByte()
                    out[4 * i + 3] = w.toByte()
                }
            }
        }
    }

    companion object {
        private const val ROUNDS = 14
        private val RCON = intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40)

        /** FIPS-197 的 S 盒。 */
        private val SBOX = intArrayOf(
            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
        )
    }
}

/** GF(2^128) 上的 xtime（模 x^8+x^4+x^3+x+1）。 */
private fun xtime(a: Int): Int {
    val doubled = a shl 1
    return if (a and 0x80 != 0) (doubled xor 0x1b) and 0xff else doubled and 0xff
}

/**
 * GHASH：GHASH_H(X) = X1•H ⊕ X2•H ⊕ …，块长 16 字节，零填充到整块。
 * [digest] 再补上 len(A) 与 len(C) 的 64 位大端长度块（GCM 规范 §7.1）。
 */
private class Ghash(private val h: ByteArray) {
    private val state = ByteArray(BLOCK_BYTES)

    fun update(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = minOf(BLOCK_BYTES, data.size - offset)
            for (i in 0 until n) state[i] = (state[i].toInt() xor data[offset + i].toInt()).toByte()
            multiply(state, h)
            offset += n
        }
    }

    fun digest(aadSize: Long, dataSize: Long): ByteArray {
        val last = ByteArray(BLOCK_BYTES)
        writeLong(last, 0, aadSize * 8)
        writeLong(last, 8, dataSize * 8)
        for (i in 0 until BLOCK_BYTES) state[i] = (state[i].toInt() xor last[i].toInt()).toByte()
        multiply(state, h)
        return state.copyOf()
    }

    private fun writeLong(out: ByteArray, offset: Int, bits: Long) {
        for (i in 0 until 8) out[offset + i] = (bits ushr ((7 - i) * 8)).toByte()
    }

    companion object {
        /**
         * GF(2^128) 乘法（GCM 规范 §6.3 的右移算法）。
         * 注意 GCM 的位序与直觉相反：块的第 0 位是**最高位**（0x80）。
         */
        fun multiply(x: ByteArray, y: ByteArray) {
            val z = ByteArray(BLOCK_BYTES)
            val v = y.copyOf()
            for (i in 0 until BLOCK_BYTES * 8) {
                val byte = x[i / 8].toInt() and 0xff
                if ((byte and (0x80 ushr (i and 7))) != 0) {
                    for (j in 0 until BLOCK_BYTES) z[j] = (z[j].toInt() xor v[j].toInt()).toByte()
                }
                val lsb = v[15].toInt() and 0x01
                shiftRight(v)
                if (lsb == 1) {
                    v[0] = (v[0].toInt() xor 0xe1).toByte()
                }
            }
            z.copyInto(x)
        }

        /** 整块右移 1 位（GCM 位序：最高位在前）。 */
        private fun shiftRight(block: ByteArray) {
            var carry = 0
            for (i in 0 until BLOCK_BYTES) {
                val b = block[i].toInt() and 0xff
                block[i] = ((b ushr 1) or carry).toByte()
                carry = (b and 0x01) shl 7
            }
        }
    }
}
