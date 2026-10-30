// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * WCAG 对比度计算（WP-D1 验收骨架）：把「这组 token 达不达标」从**人算**变成**测试断言**。
 *
 * 决策包（overhaul-plan §3 WP-D1 ①）的元建议：对比度清单测试对 token 对清单断言门槛 ——
 * 文字 4.5:1、非文本（边框/状态点）3:1。新增或改值任何 theme 色没过门槛，这里先红。
 */
object Contrast {

    /** WCAG 2.x 相对亮度（sRGB 线性化）。 */
    fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return (
            0.2126 * channel(color.red) +
                0.7152 * channel(color.green) +
                0.0722 * channel(color.blue)
            )
    }

    /** (L1 + 0.05) / (L2 + 0.05)，恒 ≥ 1。 */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val (hi, lo) = if (la >= lb) la to lb else lb to la
        return (hi + 0.05) / (lo + 0.05)
    }
}
