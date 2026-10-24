// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC 4231 的 HMAC-SHA256 向量（Test Case 1 / 2）。 */
class HmacSha256Test {

    @Test
    fun rfc4231TestCase1() {
        val mac = HmacSha256(ByteArray(20) { 0x0b })
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            mac.compute("Hi There".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun rfc4231TestCase2() {
        val mac = HmacSha256("Jefe".encodeToByteArray())
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            mac.compute("what do ya want for nothing?".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun reuseGivesTheSameAnswerAsAFreshInstance() {
        val key = ByteArray(37) { (it * 7 + 3).toByte() }
        val data = ByteArray(200) { (it * 13 + 1).toByte() }
        val reusable = HmacSha256(key)
        val first = reusable.compute(data).toHex()
        val second = reusable.compute(data).toHex()
        val fresh = HmacSha256(key).compute(data).toHex()
        assertEquals(first, second, "同一实例反复 compute 必须稳定")
        assertEquals(first, fresh, "复用与新建必须逐字节一致")
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
