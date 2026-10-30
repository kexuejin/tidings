// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.theme

import androidx.compose.ui.graphics.Color
import com.kapp.tidings.ui.theme.Contrast
import com.kapp.tidings.ui.theme.RelayDarkAccents
import com.kapp.tidings.ui.theme.RelayDarkBackground
import com.kapp.tidings.ui.theme.RelayLightAccents
import com.kapp.tidings.ui.theme.RelayLightBackground
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WP-D1 的 token 对门槛护栏（overhaul-plan §3 WP-D1 ①的元建议）：
 *
 * - 非文本（控件边框 / 状态点）对所在底 **≥ 3.0**；
 * - 文字档（onSurfaceVariant 这一级）对所在底 **≥ 4.5**。
 *
 * 摘掉任何一条断言对应的修复（或把 token 改回旧值），这里先红 —— 反向验证的骨架。
 * 清单按「底色 × 前景」成对列，新增 theme 色必须在这里表态。
 */
class ThemeContrastGateTest {

    private val lightBg = RelayLightBackground
    private val darkBg = RelayDarkBackground

    @Test
    fun outlineStrongClearsTheNonTextThresholdOnBothBackgrounds() {
        assertTrue(
            Contrast.contrastRatio(RelayLightAccents.outlineStrong, lightBg) >= 3.0,
            "outlineStrong（控件边框档）对浅底必须 ≥3.0 —— D2 的病根就是 1.42:1",
        )
        assertTrue(
            Contrast.contrastRatio(RelayDarkAccents.outlineStrong, darkBg) >= 3.0,
            "outlineStrong 对深底必须 ≥3.0（亮暗同值时深侧天然更宽，钉住防回退）",
        )
    }

    @Test
    fun onSurfaceVariantKeepsTheTextThresholdOnBothBackgrounds() {
        val lightOnSurfaceVariant = Color(0xFF5C5F5C)
        assertTrue(
            Contrast.contrastRatio(lightOnSurfaceVariant, lightBg) >= 4.5,
            "onSurfaceVariant 是 D1/D3 修复后的次级文字档，对浅底 ≥4.5",
        )
    }
}
