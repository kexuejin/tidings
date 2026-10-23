// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 二维码编码器。
 *
 * 主要证据是 [everyGoldenVectorIsReproducedBitForBit]：拿 **libqrencode 4.1.1** 的输出
 * 逐模块对拍。自己写的编码器配自己写的解码器只能证明自洽——真正要证明的是
 * 「别人（系统相机、ML Kit、iOS Vision）扫出来的是同一张码」，所以裁判必须是外部的。
 */
class QrEncoderTest {

    @Test
    fun everyGoldenVectorIsReproducedBitForBit() {
        assertEquals("qrencode 4.1.1", QrGoldenVectorsData.GENERATED_FROM)
        for (vector in QrGoldenVectorsData.VECTORS) {
            // 掩码用向量里的那一个：**掩码选择是编码器的自由**——八张掩码都产生合法二维码，
            // 读码器不关心选了哪张（libqrencode 与本实现在边界罚分上的取舍不同，见
            // theChosenMaskIsTheCheapestUnderOurOwnScoring）。这里钉的是必须一致的部分：
            // 数据码字、纠错码字与交织、功能图案、版本/格式信息、掩码施加。
            val code = QrEncoder.encode(vector.text, mask = vector.mask)
            assertEquals(vector.version, code.version, "${vector.name}：版本不对")
            assertEquals(vector.size, code.size, "${vector.name}：边长不对")
            val wrong = ArrayList<String>()
            for (y in 0 until vector.size) {
                for (x in 0 until vector.size) {
                    val expected = vector.rows[y][x] == '1'
                    if (code[x, y] != expected) wrong += "($x,$y)"
                }
            }
            assertTrue(
                wrong.isEmpty(),
                "${vector.name}：与 libqrencode 有 ${wrong.size} 个模块不同（掩码 ${vector.mask}）；" +
                    "前几个：${wrong.take(8)}",
            )
        }
    }

    @Test
    fun theChosenMaskIsTheCheapestUnderOurOwnScoring() {
        // 这是**自己跟自己的打分函数**比，不是与 libqrencode 比：选中的掩码罚分必须不高于
        // 其余七张。标准在「贴着符号边界的 1:1:3:1:1 图案」上各家实现有出入（符号外的静区
        // 算不算浅色模块），所以刻意不断言掩码号与 libqrencode 相同——那属于实现自由，
        // 断言它反而会把一个正确实现钉死在别人的取舍上。
        for (text in listOf("relay://1:1", "relay://192.168.1.5:17878", "relay://" + "d".repeat(120))) {
            val chosen = QrEncoder.encode(text)
            val chosenPenalty = QrEncoder.penaltyOf(chosen)
            for (mask in 0..7) {
                val alternative = QrEncoder.encode(text, mask = mask)
                assertTrue(
                    chosenPenalty <= QrEncoder.penaltyOf(alternative),
                    "自动选的掩码 ${chosen.mask} 罚分 $chosenPenalty 高于强制掩码 $mask 的 " +
                        "${QrEncoder.penaltyOf(alternative)}",
                )
            }
        }
    }

    @Test
    fun capacityBoundariesLandOnTheExpectedVersion() {
        for (version in QrEncoder.MIN_VERSION until QrEncoder.MAX_VERSION) {
            val capacity = QrEncoder.capacityBytes(version)
            assertEquals(
                version,
                QrEncoder.encode("x".repeat(capacity)).version,
                "版本 $version 在等级 M 下应刚好装下 $capacity 字节",
            )
            assertEquals(
                version + 1,
                QrEncoder.encode("x".repeat(capacity + 1)).version,
                "版本 $version 多一个字节就该升到版本 ${version + 1}",
            )
        }
    }

    @Test
    fun aPayloadBeyondTheLargestVersionIsRefusedInsteadOfTruncated() {
        val capacity = QrEncoder.capacityBytes(QrEncoder.MAX_VERSION)
        val error = assertFailsWith<IllegalArgumentException> { QrEncoder.encode("x".repeat(capacity + 1)) }
        val message = error.message.orEmpty()
        assertTrue(message.contains("${capacity + 1}"), "报错要说清收到多少字节：$message")
        assertTrue(message.contains("$capacity"), "报错要说清容量是多少：$message")
        assertTrue(message.contains("不是截断"), "报错要说清为什么不截断：$message")
    }

    @Test
    fun everyForcedMaskIsWrittenIntoTheFormatInformation() {
        val text = "relay://192.168.1.5:17878"
        for (mask in 0..7) {
            val code = QrEncoder.encode(text, mask = mask)
            assertEquals(mask, code.mask, "强制掩码 $mask 后 QrCode.mask 应如实记录")
            val info = formatInfoOf(code)
            assertEquals(mask, info.mask, "格式信息里的掩码与请求的不符（请求 $mask）")
            assertEquals(0b00, info.ecBits, "纠错等级应固定为 M（格式信息位 00）")
        }
    }

    @Test
    fun anOutOfRangeMaskIsRefused() {
        assertFailsWith<IllegalArgumentException> { QrEncoder.encode("relay://1:1", mask = 8) }
        assertFailsWith<IllegalArgumentException> { QrEncoder.encode("relay://1:1", mask = -1) }
    }

    @Test
    fun emptyTextStillProducesAWellFormedSymbol() {
        // libqrencode 拒绝空输入（`Failed to encode the input data: Invalid argument`），
        // 所以这条**没有黄金向量**：只能钉住结构完整，不能声称与独立裁判逐位一致。
        val code = QrEncoder.encode("")
        assertEquals(1, code.version)
        assertEquals(21, code.size)
        assertFinder(code, 3, 3)
        assertFinder(code, code.size - 4, 3)
        assertFinder(code, 3, code.size - 4)
        for (i in 8 until code.size - 8) {
            assertEquals(i % 2 == 0, code[i, 6], "定时图案第 6 行第 $i 列")
            assertEquals(i % 2 == 0, code[6, i], "定时图案第 6 列第 $i 行")
        }
        assertTrue(code[8, code.size - 8], "固定的深色模块必须在 (8, size-8)")
    }

    private fun assertFinder(code: QrEncoder.QrCode, cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until code.size || y !in 0 until code.size) continue
                assertEquals(dist != 2 && dist != 4, code[x, y], "定位图案 ($cx,$cy) 的 ($x,$y)")
            }
        }
    }

    private class FormatInfo(val ecBits: Int, val mask: Int)

    /** 从第二份格式信息（右上横条 + 左下竖条）里把 15 位读回来。 */
    private fun formatInfoOf(code: QrEncoder.QrCode): FormatInfo {
        val size = code.size
        var raw = 0
        for (i in 0..7) if (code[size - 1 - i, 8]) raw = raw or (1 shl i)
        for (i in 8..14) if (code[8, size - 15 + i]) raw = raw or (1 shl i)
        val bits = raw xor 0x5412
        return FormatInfo((bits shr 13) and 0b11, (bits shr 10) and 0b111)
    }
}
