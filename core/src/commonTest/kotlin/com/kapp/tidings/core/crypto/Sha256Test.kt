// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SHA-256 已知答案向量（FIPS 180-4 附录 + NIST 经典用例）与流式行为。
 */
class Sha256Test {

    private companion object {
        const val EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        const val FIVE_SIX = "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        const val MILLION_A = "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        const val PANGRAM = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"
        const val PANGRAM_TEXT = "The quick brown fox jumps over the lazy dog"

        /** 确定性伪随机输入：长度不同、内容不同，方便定位切分 bug。 */
        fun sample(n: Int): ByteArray = ByteArray(n) { (it * 7 + 13).toByte() }

        fun ByteArray.toHex(): String = buildString(size * 2) {
            for (b in this@toHex) {
                val v = b.toInt() and 0xff
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0f])
            }
        }
    }

    /** 按 [cuts] 的升序切点分多段 `update`，等价于一次喂完。 */
    private fun hashSplit(bytes: ByteArray, cuts: List<Int>): String {
        val sha = Sha256()
        var start = 0
        for (cut in cuts) {
            sha.update(bytes, start, cut - start)
            start = cut
        }
        sha.update(bytes, start, bytes.size - start)
        return sha.hexDigest()
    }

    // ------------------------------------------------------------------ 已知向量

    @Test
    fun knownAnswerVectors() {
        assertEquals(EMPTY, sha256Hex("".encodeToByteArray()))
        assertEquals(ABC, sha256Hex("abc".encodeToByteArray()))
        assertEquals(FIVE_SIX, sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
        assertEquals(PANGRAM, sha256Hex(PANGRAM_TEXT.encodeToByteArray()))
    }

    @Test
    fun oneMillionAs() {
        // 1e6 字节 = 15625 个整块，覆盖多块流式与长度计数器。
        val million = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals(MILLION_A, sha256Hex(million))

        // 同一段输入按 1000 字节一段流式喂进去，摘要必须一模一样。
        val sha = Sha256()
        var pos = 0
        while (pos < million.size) {
            val n = minOf(1000, million.size - pos)
            sha.update(million, pos, n)
            pos += n
        }
        assertEquals(MILLION_A, sha.hexDigest())
    }

    // ------------------------------------------------------------------ 流式切分

    @Test
    fun oneByteAtATimeMatchesWholeBuffer() {
        val bytes = PANGRAM_TEXT.encodeToByteArray()
        val whole = sha256Hex(bytes)

        val sha = Sha256()
        for (i in bytes.indices) sha.update(bytes, i, 1)

        assertEquals(PANGRAM, whole)
        assertEquals(whole, sha.hexDigest())
    }

    @Test
    fun blockEdgeSizesSurviveEverySplit() {
        // 63/64/65 卡在块边界，119 卡在第二块的填充边界。
        for (n in intArrayOf(63, 64, 65, 119)) {
            val bytes = sample(n)
            val whole = sha256Hex(bytes)

            // 单切点：所有位置。
            for (cut in 1 until n) {
                assertEquals(whole, hashSplit(bytes, listOf(cut)), "n=$n 单切点 cut=$cut")
            }
            // 双切点：所有组合，覆盖「整块前后各留一点」的情况。
            for (i in 1 until n) {
                for (j in i + 1 until n) {
                    assertEquals(whole, hashSplit(bytes, listOf(i, j)), "n=$n 双切点 $i/$j")
                }
            }
        }
    }

    @Test
    fun chunkBoundariesAreIrrelevantAroundFullBlocks() {
        // 一次喂 64 字节整块 vs 每块切成 3 段，结果必须一致。
        val bytes = sample(256)
        val whole = sha256Hex(bytes)
        assertEquals(whole, hashSplit(bytes, (64 until 256 step 64).toList()))
        assertEquals(whole, hashSplit(bytes, (0 until 256 step 3).toList().drop(1)))
    }

    // ------------------------------------------------------------------ 输出形状

    @Test
    fun digestAndHexHaveExactShape() {
        val bytes = "abc".encodeToByteArray()

        assertEquals(32, Sha256().apply { update(bytes) }.digest().size)

        val hex = sha256Hex(bytes)
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' }, "期望小写十六进制，实际：$hex")
        assertEquals(ABC, hex)
    }

    // ------------------------------------------------------------------ 生命周期

    @Test
    fun digestFinalizesTheInstanceAndResetStartsOver() {
        val sha = Sha256()
        sha.update("abc".encodeToByteArray())

        // digest() 就地填充并终结：返回之后实例已被摘要覆盖，不能继续用。
        assertEquals(ABC, sha.digest().toHex())
        assertFailsWith<IllegalStateException> { sha.update("def".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { sha.digest() }
        assertFailsWith<IllegalStateException> { sha.hexDigest() }

        // reset() 把它恢复成刚构造的样子，可以哈希另一段完全不同的输入。
        sha.reset()
        assertEquals(EMPTY, sha.hexDigest())

        sha.reset()
        sha.update(PANGRAM_TEXT.encodeToByteArray())
        assertEquals(PANGRAM, sha.hexDigest())
    }

    // ------------------------------------------------------------------ 参数校验

    @Test
    fun updateValidatesArguments() {
        val bytes = ByteArray(16)

        assertFailsWith<IllegalArgumentException> { Sha256().update(bytes, -1, 1) }
        assertFailsWith<IllegalArgumentException> { Sha256().update(bytes, 0, -1) }
        assertFailsWith<IllegalArgumentException> { Sha256().update(bytes, 1, bytes.size) }
        assertFailsWith<IllegalArgumentException> { Sha256().update(bytes, bytes.size + 1, 0) }
        // offset + count 用 Int 相加会溢出成负数，必须仍然判越界。
        assertFailsWith<IllegalArgumentException> { Sha256().update(bytes, Int.MAX_VALUE, Int.MAX_VALUE) }

        // 合法的空区间和默认 count 不能误伤。
        val sha = Sha256()
        sha.update(bytes, bytes.size, 0)
        assertEquals(EMPTY, sha.hexDigest())
        assertEquals(sha256Hex(bytes), Sha256().apply { update(bytes) }.hexDigest())
    }
}
