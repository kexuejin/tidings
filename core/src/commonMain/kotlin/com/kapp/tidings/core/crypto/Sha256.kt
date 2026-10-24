// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

/**
 * 纯 Kotlin SHA-256。
 *
 * 为什么不用平台实现：`java.security.MessageDigest` 只在 JVM/Android 有，
 * iOS 要走 CommonCrypto 的 `CC_SHA256`，那就得 expect/actual 三份；
 * 而这个算法是自包含的、有公开测试向量可钉死的，一份 commonMain 实现
 * 反而更少代码、更好测。
 *
 * 流式：`update` 只保留一个 64 字节的块缓冲，凑满就压，整块直接就地压缩，
 * 所以哈希几百 MB 的文件不需要把输入整个读进内存。
 *
 * 非线程安全：一个实例只能被一个线程使用。
 */
class Sha256 {

    /** 八个状态字 h0..h7。 */
    private val h = INIT.copyOf()

    /** 未满一块的尾巴，最多 63 字节留在里面等下次 `update` 或 `digest`。 */
    private val block = ByteArray(BLOCK_SIZE)

    private var blockLen = 0

    /** 已喂入的总字节数。用 Long 记字节、收尾时再乘 8，才能支持 >2^32 比特的消息。 */
    private var totalBytes = 0L

    /** [digest] 之后置位：终结态的实例只能 [reset] 后复用。 */
    private var finalized = false

    /** 消息扩展的 64 个字，复用同一个数组避免每块都分配。 */
    private val w = IntArray(64)

    /**
     * 追加一段输入。
     *
     * 可以任意多次、任意切分地调用；[count] 默认为从 [offset] 到数组末尾。
     *
     * @throws IllegalArgumentException offset/count 为负，或 `offset + count` 越过数组末尾。
     * @throws IllegalStateException 实例已被 [digest] 终结，尚未 [reset]。
     */
    fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        check(!finalized) { "实例已被 digest() 终结，复用前请先 reset()" }
        require(offset >= 0) { "offset 不能为负：$offset" }
        require(count >= 0) { "count 不能为负：$count" }
        // 用 Long 比较：offset + count 两者都很大时 Int 相加会溢出成负数。
        require(offset.toLong() + count <= bytes.size) {
            "越界：offset=$offset, count=$count, size=${bytes.size}"
        }

        totalBytes += count.toLong()

        var pos = offset
        var remaining = count

        // 先补满上次剩下的半个块。
        if (blockLen > 0) {
            val need = minOf(BLOCK_SIZE - blockLen, remaining)
            bytes.copyInto(block, blockLen, pos, pos + need)
            blockLen += need
            pos += need
            remaining -= need
            if (blockLen == BLOCK_SIZE) {
                compress(h, block, 0)
                blockLen = 0
            }
        }

        // 整块直接对调用方的数组压缩，不先拷进 block —— 流式哈希大文件时，
        // 这一次全量拷贝正是要避免的东西。
        while (remaining >= BLOCK_SIZE) {
            compress(h, bytes, pos)
            pos += BLOCK_SIZE
            remaining -= BLOCK_SIZE
        }

        // 不足一块的尾巴留给下一次。
        if (remaining > 0) {
            bytes.copyInto(block, blockLen, pos, pos + remaining)
            blockLen += remaining
        }
    }

    /**
     * 收尾并返回 32 字节摘要（8 个大端序状态字）。
     *
     * 本方法终结实例：填充就地写在块缓冲上、压缩就地写回状态字，返回后工作状态已被摘要
     * 覆盖，再调 [update] 或 [digest] 会抛 [IllegalStateException]。要接着哈希另一段
     * 输入，先调用 [reset]。
     *
     * @throws IllegalStateException 实例已被本方法终结过，尚未 [reset]。
     */
    fun digest(): ByteArray {
        check(!finalized) { "digest() 只能调用一次，复用前请先 reset()" }

        // 填充：先写 0x80，再补 0 到 56 (mod 64)，最后 8 字节是大端序比特长度。
        val bits = totalBytes * 8L
        block[blockLen++] = 0x80.toByte()
        if (blockLen > 56) {
            // 0x80 顶到了长度字段的位置，本块塞不下长度，得再压一整块。
            while (blockLen < BLOCK_SIZE) block[blockLen++] = 0
            compress(h, block, 0)
            blockLen = 0
        }
        while (blockLen < 56) block[blockLen++] = 0
        for (i in 0 until 8) {
            // bits 最多有效 61 位，直接从高位往低位数 8 个字节。
            block[56 + i] = (bits ushr (56 - i * 8)).toByte()
        }

        compress(h, block, 0)
        finalized = true
        blockLen = 0

        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = h[i]
            out[i * 4] = (v ushr 24).toByte()
            out[i * 4 + 1] = (v ushr 16).toByte()
            out[i * 4 + 2] = (v ushr 8).toByte()
            out[i * 4 + 3] = v.toByte()
        }
        return out
    }

    /** 32 字节摘要的小写十六进制形式，恰好 64 个字符。同样终结实例，见 [digest]。 */
    fun hexDigest(): String {
        val d = digest()
        return buildString(HEX_PER_DIGEST) {
            for (b in d) {
                val v = b.toInt() and 0xff
                append(HEX[v ushr 4])
                append(HEX[v and 0x0f])
            }
        }
    }

    /** 清空状态，回到刚构造的样子，可以重新哈希另一段输入。 */
    fun reset() {
        INIT.copyInto(h)
        block.fill(0)
        blockLen = 0
        totalBytes = 0L
        finalized = false
    }

    /**
     * 深拷贝当前状态：新实例从**这一刻**的哈希状态继续，原实例不受影响。
     *
     * 为什么要有它：HMAC 的 ipad/opad 中间态只需压一次（64 字节 key-pad），
     * 之后每次 MAC 调用从中间态 [copy] 出发，省掉一半压缩 —— PBKDF2 六十万轮
     * 就是省出六十万次不必要的块压缩。
     */
    fun copy(): Sha256 {
        check(!finalized) { "已终结的实例没有可拷贝的状态" }
        val c = Sha256()
        h.copyInto(c.h)
        block.copyInto(c.block)
        c.blockLen = blockLen
        c.totalBytes = totalBytes
        return c
    }

    /** 压缩一个 64 字节块到状态字 [h]。 */
    private fun compress(h: IntArray, data: ByteArray, offset: Int) {
        val w = this.w

        // 前 16 个字直接从大端序字节拼出来；必须 and 0xff，
        // 否则 Byte 的符号位会被带到高位上。
        for (i in 0 until 16) {
            val j = offset + i * 4
            w[i] = ((data[j].toInt() and 0xff) shl 24) or
                ((data[j + 1].toInt() and 0xff) shl 16) or
                ((data[j + 2].toInt() and 0xff) shl 8) or
                (data[j + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            // σ0(x) = ROTR7 ^ ROTR18 ^ SHR3，σ1(x) = ROTR17 ^ ROTR19 ^ SHR10。
            // 右移一律用 ushr：Kotlin 的 shr 在 Int 上是算术右移，会把符号位补进来。
            val x = w[i - 15]
            val y = w[i - 2]
            val s0 = ((x ushr 7) or (x shl 25)) xor ((x ushr 18) or (x shl 14)) xor (x ushr 3)
            val s1 = ((y ushr 17) or (y shl 15)) xor ((y ushr 19) or (y shl 13)) xor (y ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]

        for (i in 0 until 64) {
            // 同样的旋转写法：ROTR n 就是 (x ushr n) or (x shl (32 - n))。
            val s1 = ((e ushr 6) or (e shl 26)) xor ((e ushr 11) or (e shl 21)) xor ((e ushr 25) or (e shl 7))
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[i] + w[i]
            val s0 = ((a ushr 2) or (a shl 30)) xor ((a ushr 13) or (a shl 19)) xor ((a ushr 22) or (a shl 10))
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj

            hh = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }

        // Int 加法的溢出回绕正是模 2^32 加法，无需额外处理。
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }

    private companion object {
        const val BLOCK_SIZE = 64
        const val HEX_PER_DIGEST = 64

        /** h0..h7 初值：前 8 个素数平方根小数部分的前 32 位（FIPS 180-4 §5.3.3）。 */
        val INIT = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )

        /** 64 个轮常量：前 64 个素数立方根小数部分的前 32 位（FIPS 180-4 §4.2.2）。 */
        val K = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
            0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
            0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )

        const val HEX = "0123456789abcdef"
    }
}

/** 一次性便捷函数：直接算 [bytes] 的十六进制摘要。 */
fun sha256Hex(bytes: ByteArray): String = Sha256().apply { update(bytes) }.hexDigest()
