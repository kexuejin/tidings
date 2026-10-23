// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.qr

/**
 * 二维码生成（ISO/IEC 18004，**字节模式**，纠错等级 M，版本 1–10）。
 *
 * 为什么自己写而不是拉一个库：配对二维码的内容只有 `relay://192.168.1.5:17878` 这种
 * 几十字节的串，需要的功能是一个"能生成、能被系统相机读出来"的编码器，不是一套完整
 * 的编解码框架。手写这一版只有一处外部依赖——**标准本身**——而且可以在 JVM 上拿
 * ZXing 当独立裁判逐位验证（见 `core/src/jvmTest` 的 `QrEncoderOracleTest`）：
 * 自己写的编码器再配自己写的解码器只能证明自洽，证明不了别人扫得出来。
 *
 * 边界（写清楚，别让调用方猜）：
 *  - 只做**字节模式**：文本按 UTF-8 编码，不做数字/字母数字模式的压缩，也不做 ECI；
 *  - 只做**纠错等级 M**（约 15% 冗余，配对场景够用，也是屏幕显示与系统相机的常用档）；
 *  - 只做**版本 1–10**（等级 M 下最多 213 字节）。超出就抛
 *    [IllegalArgumentException]，**绝不截断**——截断出来的是一个能扫但内容是错的二维码。
 *
 * 生成结果是模块矩阵，不含任何绘图代码：Compose 侧负责画，测试侧负责逐位比对。
 */
object QrEncoder {

    /** 支持的版本区间（1–10）。 */
    const val MIN_VERSION: Int = 1
    const val MAX_VERSION: Int = 10

    /** 纠错等级 M 的格式信息位（`00`，见标准表 25）。 */
    private const val EC_LEVEL_M_BITS: Int = 0b00

    /** 各版本的总码字数（标准表 1）。 */
    private val TOTAL_CODEWORDS = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346)

    /** 各版本每块的纠错码字数（等级 M）。 */
    private val EC_CODEWORDS_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)

    /** 第一组块的数据码字数与块数（等级 M）。 */
    private val DATA_CODEWORDS_G1 = intArrayOf(16, 28, 44, 32, 43, 27, 31, 38, 36, 43)
    private val BLOCKS_G1 = intArrayOf(1, 1, 1, 2, 2, 4, 4, 2, 3, 4)

    /** 第二组块的数据码字数与块数（等级 M）；为 0 表示这个版本只有一组。 */
    private val DATA_CODEWORDS_G2 = intArrayOf(0, 0, 0, 0, 0, 0, 0, 39, 37, 44)
    private val BLOCKS_G2 = intArrayOf(0, 0, 0, 0, 0, 0, 0, 2, 2, 1)

    /** 各版本校正图案的坐标（标准附录 E）；版本 1 没有校正图案。 */
    private val ALIGNMENT_POSITIONS = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
    )

    /** 版本信息（18 位，版本 7 起才写进码里）。 */
    private val VERSION_INFO = intArrayOf(0x07C94, 0x085BC, 0x09A99, 0x0A4D3)

    /**
     * 一张生成好的二维码。
     *
     * [module] 的坐标原点是**左上角**，`x` 向右、`y` 向下；`true` = 深色模块。
     */
    class QrCode internal constructor(
        /** 版本号（1–10）。 */
        val version: Int,
        /** 边长（模块数）：`17 + 4 * version`。 */
        val size: Int,
        /** 实际使用的掩码（0–7）：格式信息里写的就是它，出问题时先看这个数。 */
        val mask: Int,
        private val modules: BooleanArray,
    ) {
        operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

        /** 只给测试用：整张矩阵的副本（行优先）。 */
        internal fun snapshot(): BooleanArray = modules.copyOf()
    }

    /**
     * 把 [text] 编成一张二维码。
     *
     * @param mask 强制指定掩码（0–7），主要用于测试；默认 `null` = 按标准罚分规则自动挑。
     * @throws IllegalArgumentException 文本超过版本 10 在等级 M 下的容量（213 字节）。
     */
    fun encode(text: String, mask: Int? = null): QrCode {
        require(mask == null || mask in 0..7) { "掩码只能是 0–7，收到 $mask" }
        val bytes = text.encodeToByteArray()
        val version = (MIN_VERSION..MAX_VERSION).firstOrNull { capacityBytes(it) >= bytes.size }
            ?: throw IllegalArgumentException(
                "内容 ${bytes.size} 字节，超过版本 $MAX_VERSION 在纠错等级 M 下的容量 " +
                    "${capacityBytes(MAX_VERSION)} 字节；请缩短内容，而不是截断它",
            )
        val dataCodewords = buildDataCodewords(bytes, version)
        val codewords = addErrorCorrection(dataCodewords, version)
        return buildMatrix(version, codewords, mask)
    }

    /** 版本 [version] 在字节模式下能装多少字节（等级 M）。测试用它钉容量边界。 */
    internal fun capacityBytes(version: Int): Int {
        require(version in MIN_VERSION..MAX_VERSION) { "版本只能是 $MIN_VERSION–$MAX_VERSION" }
        val dataCodewords = dataCodewordCount(version)
        // 4 位模式指示符 + 字符计数（版本 10 起 16 位，之前 8 位），剩下的才是数据。
        val countBits = if (version <= 9) 8 else 16
        return (dataCodewords * 8 - 4 - countBits) / 8
    }

    /**
     * 只给测试用：按标准 §8.8.2 的四条规则给一张**已完成**的码打分。
     *
     * 暴露它是为了让「选中的掩码确实是罚分最低的那个」这条断言可测；产品代码不需要它。
     */
    internal fun penaltyOf(code: QrCode): Int = penalty(code.snapshot(), code.size)

    private fun dataCodewordCount(version: Int): Int {
        val i = version - 1
        return DATA_CODEWORDS_G1[i] * BLOCKS_G1[i] + DATA_CODEWORDS_G2[i] * BLOCKS_G2[i]
    }

    // ------------------------------------------------------------------ 数据码字

    private fun buildDataCodewords(bytes: ByteArray, version: Int): ByteArray {
        val capacity = dataCodewordCount(version)
        val bits = BitBuffer()
        bits.append(0b0100, 4) // 字节模式
        bits.append(bytes.size, if (version <= 9) 8 else 16)
        for (b in bytes) bits.append(b.toInt() and 0xFF, 8)
        // 终止符最多 4 位，且不能越界。
        val remaining = capacity * 8 - bits.size
        bits.append(0, minOf(4, remaining))
        // 补齐到字节边界，再用 0xEC / 0x11 交替填满。
        bits.append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacity * 8) {
            bits.append(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        return bits.toByteArray()
    }

    // ------------------------------------------------------------------ 纠错

    private fun addErrorCorrection(data: ByteArray, version: Int): ByteArray {
        val i = version - 1
        val ecLen = EC_CODEWORDS_PER_BLOCK[i]
        val blocksG1 = BLOCKS_G1[i]
        val blocksG2 = BLOCKS_G2[i]
        val dataG1 = DATA_CODEWORDS_G1[i]
        val dataG2 = DATA_CODEWORDS_G2[i]
        val divisor = reedSolomonDivisor(ecLen)

        val datas = ArrayList<ByteArray>(blocksG1 + blocksG2)
        var offset = 0
        repeat(blocksG1) {
            datas += data.copyOfRange(offset, offset + dataG1).also { _ -> offset += dataG1 }
        }
        repeat(blocksG2) {
            datas += data.copyOfRange(offset, offset + dataG2).also { _ -> offset += dataG2 }
        }
        val eccs = datas.map { reedSolomonRemainder(it, divisor) }

        // 交织（标准 §8.6）：先按**列**发各块的数据码字，再按列发各块的纠错码字。
        // 长短块混用时，短块在数据段少一个字节——跳过它，而不是补零。
        val out = ByteArray(TOTAL_CODEWORDS[i])
        var k = 0
        val maxData = datas.maxOf { it.size }
        for (col in 0 until maxData) {
            for (block in datas) {
                if (col < block.size) out[k++] = block[col]
            }
        }
        for (col in 0 until ecLen) {
            for (ecc in eccs) out[k++] = ecc[col]
        }
        check(k == out.size) { "交织后码字数 $k 与版本 $version 的总码字数 ${out.size} 不符" }
        return out
    }

    private fun reedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor (result[0].toInt() and 0xFF)
            result.copyInto(result, 0, 1)
            result[result.size - 1] = 0
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor gfMultiply(divisor[j].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    /** GF(256) 乘法，本原多项式 `0x11D`。 */
    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    // ------------------------------------------------------------------ 排布

    private fun buildMatrix(version: Int, codewords: ByteArray, forcedMask: Int?): QrCode {
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y * size + x] = dark
            isFunction[y * size + x] = true
        }

        // 1) 定时图案：第 6 行与第 6 列交替深浅（穿过定位图案的部分随后被覆盖）。
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }

        // 2) 三个定位图案（含分隔符）。
        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) setFunction(x, y, dist != 2 && dist != 4)
                }
            }
        }
        finder(3, 3)
        finder(size - 4, 3)
        finder(3, size - 4)

        // 3) 校正图案：坐标的笛卡尔积，去掉与定位图案重叠的三个角。
        val positions = ALIGNMENT_POSITIONS[version - 1]
        for (cy in positions) {
            for (cx in positions) {
                val overlapsFinder = (cx == 6 && cy == 6) ||
                    (cx == 6 && cy == positions.last()) ||
                    (cx == positions.last() && cy == 6)
                if (overlapsFinder) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        setFunction(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                    }
                }
            }
        }

        // 4) 版本信息（版本 7 起）：两个 3×6 块。
        if (version >= 7) {
            val bits = VERSION_INFO[version - 7]
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3
                val b = i / 3
                setFunction(a, b, bit)
                setFunction(b, a, bit)
            }
        }

        // 5) 格式信息：先用掩码 0 占位（真正的值在挑完掩码后写）。
        drawFormatInfo(size, EC_LEVEL_M_BITS, 0, ::setFunction)

        // 6) 数据：从右下角起，两列一组蛇形向上/向下填，跳过第 6 列。
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y * size + x] && bitIndex < codewords.size * 8) {
                        val byte = codewords[bitIndex ushr 3].toInt() and 0xFF
                        modules[y * size + x] = (byte ushr (7 - (bitIndex and 7))) and 1 == 1
                        bitIndex++
                    }
                }
            }
            right -= 2
        }

        // 7) 挑掩码：逐个试，按标准罚分选最低的那个。
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (candidate in 0..7) {
            val trial = modules.copyOf()
            applyMask(trial, isFunction, size, candidate)
            drawFormatInfo(size, EC_LEVEL_M_BITS, candidate) { x, y, dark ->
                trial[y * size + x] = dark
            }
            val penalty = penalty(trial, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = candidate
            }
        }
        val chosen = forcedMask ?: bestMask
        applyMask(modules, isFunction, size, chosen)
        drawFormatInfo(size, EC_LEVEL_M_BITS, chosen, ::setFunction)
        return QrCode(version, size, chosen, modules)
    }

    private fun drawFormatInfo(
        size: Int,
        ecBits: Int,
        mask: Int,
        set: (Int, Int, Boolean) -> Unit,
    ) {
        val data = (ecBits shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 == 1

        // 第一份：围着左上定位图案。
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9..14) set(14 - i, 8, bit(i))

        // 第二份：右上横条 + 左下竖条。
        for (i in 0..7) set(size - 1 - i, 8, bit(i))
        for (i in 8..14) set(8, size - 15 + i, bit(i))

        // 固定的深色模块。
        set(8, size - 8, true)
    }

    private fun applyMask(modules: BooleanArray, isFunction: BooleanArray, size: Int, mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (isFunction[y * size + x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y * size + x] = !modules[y * size + x]
            }
        }
    }

    /** 标准 §8.8.2 的四条罚分规则。 */
    private fun penalty(modules: BooleanArray, size: Int): Int {
        var score = 0

        fun dark(x: Int, y: Int) = modules[y * size + x]

        // 规则 1：行/列里连续同色 ≥5 个。
        for (y in 0 until size) {
            var runColor = dark(0, y)
            var runLen = 1
            for (x in 1 until size) {
                if (dark(x, y) == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = dark(x, y)
                    runLen = 1
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)
        }
        for (x in 0 until size) {
            var runColor = dark(x, 0)
            var runLen = 1
            for (y in 1 until size) {
                if (dark(x, y) == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = dark(x, y)
                    runLen = 1
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)
        }

        // 规则 2：2×2 同色方块。
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = dark(x, y)
                if (c == dark(x + 1, y) && c == dark(x, y + 1) && c == dark(x + 1, y + 1)) score += 3
            }
        }

        // 规则 3：看起来像定位图案的 1:1:3:1:1 序列（两侧各 4 个浅色模块）。
        val patternA = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val patternB = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        fun matches(get: (Int) -> Boolean, at: Int, pattern: BooleanArray): Boolean {
            for (i in pattern.indices) if (get(at + i) != pattern[i]) return false
            return true
        }
        for (y in 0 until size) {
            for (x in 0..size - 11) {
                if (matches({ xx -> dark(xx, y) }, x, patternA)) score += 40
                if (matches({ xx -> dark(xx, y) }, x, patternB)) score += 40
            }
        }
        for (x in 0 until size) {
            for (y in 0..size - 11) {
                if (matches({ yy -> dark(x, yy) }, y, patternA)) score += 40
                if (matches({ yy -> dark(x, yy) }, y, patternB)) score += 40
            }
        }

        // 规则 4：深色比例偏离 50% 的程度。
        var darkCount = 0
        for (m in modules) if (m) darkCount++
        val total = size * size
        val percent = darkCount * 100 / total
        score += (kotlin.math.abs(percent - 50) / 5) * 10

        return score
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>(256)
        val size: Int get() = bits.size

        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits += (value ushr i) and 1 == 1
        }

        fun toByteArray(): ByteArray {
            check(bits.size % 8 == 0) { "位数 ${bits.size} 不是 8 的倍数" }
            val out = ByteArray(bits.size / 8)
            for (i in bits.indices) {
                if (bits[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
            return out
        }
    }
}
