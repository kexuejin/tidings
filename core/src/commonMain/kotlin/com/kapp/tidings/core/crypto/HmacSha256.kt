// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

/**
 * 纯 Kotlin HMAC-SHA-256（RFC 2104）。
 *
 * 与 [Sha256] 同一立场：算法自包含、有公开测试向量可钉死，一份 commonMain 实现
 * 比三份平台 actual 更少代码、更好测、且**跨平台逐字节一致**（恢复口令的 KDF
 * 在桌面导出、手机导入，两侧必须派生出同一把钥匙）。
 *
 * 一个实例可以**反复**使用：[compute] 每次都从 ipad/opad 中间态出发，
 * 中间态只压一次（见 [Sha256.copy]）。
 *
 * 非线程安全：一个实例只能被一个线程使用。
 */
class HmacSha256(key: ByteArray) {

    private val ipadState: Sha256
    private val opadState: Sha256

    init {
        // key 长于块长先哈希；短于块长补零 —— RFC 2104 §2。
        val k = if (key.size > BLOCK_SIZE) {
            Sha256().apply { update(key) }.digest()
        } else {
            key.copyOf()
        }
        // ipad/opad 必须各自从原始 key 派生：原地异或同一个数组会把 ipad 混进 opad
        // （(k^36)^5c = k^6a —— 两次掩码叠在一个 buffer 上，向量必红）。
        val ipad = ByteArray(BLOCK_SIZE)
        val opad = ByteArray(BLOCK_SIZE)
        for (i in 0 until BLOCK_SIZE) {
            val kb = if (i < k.size) k[i] else 0
            ipad[i] = (kb.toInt() xor XOR_IPAD.toInt()).toByte()
            opad[i] = (kb.toInt() xor XOR_OPAD.toInt()).toByte()
        }
        ipadState = Sha256().apply { update(ipad) }
        opadState = Sha256().apply { update(opad) }
    }

    /** 一次性 MAC。实例可复用，输出 32 字节。 */
    fun compute(data: ByteArray, offset: Int = 0, count: Int = data.size - offset): ByteArray {
        val inner = ipadState.copy()
        inner.update(data, offset, count)
        val innerDigest = inner.digest()
        val outer = opadState.copy()
        outer.update(innerDigest)
        return outer.digest()
    }

    companion object {
        private const val BLOCK_SIZE = 64
        private const val XOR_IPAD: Int = 0x36
        private const val XOR_OPAD: Int = 0x5c
    }
}
