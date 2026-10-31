// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.theme

import androidx.compose.ui.graphics.Color
import com.kapp.tidings.ui.theme.Contrast
import com.kapp.tidings.ui.theme.RelayDarkAccents
import com.kapp.tidings.ui.theme.RelayDarkBackground
import com.kapp.tidings.ui.theme.RelayLightAccents
import com.kapp.tidings.ui.theme.RelayDarkSurface
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
    // ── WP-D6④（D8 / D10）──────────────────────────────────────────────

    @Test
    fun darkOnPrimaryClearsTheKnobThreshold() {
        // D8：暗色 primary 容器上的旋钮/勾。旧实现写死 Color.White（对 #74C69D =
        // 2.04:1），换成 onPrimary 后 6.47:1 —— 门槛钉在 3.0（非文本）。
        val darkPrimary = Color(0xFF74C69D)
        val darkOnPrimary = Color(0xFF003826)
        assertTrue(
            Contrast.contrastRatio(darkOnPrimary, darkPrimary) >= 3.0,
            "暗色 onPrimary 对 primary ≥3.0 —— D8 的白钮在暗色只有 2.04，两态跳档",
        )
        // 旧值必须不过线（这条红 = 有人把 Color.White 换回来）：
        assertTrue(
            Contrast.contrastRatio(Color.White, darkPrimary) < 3.0,
            "Color.White 对暗色 primary 必须不达标 —— 守住『改回白即红』的反向通道",
        )
    }

    @Test
    fun darkSurfaceLiftsCardAboveBackground() {
        // D10：surfaceTint==surface 后暗色层次全靠阴影（最深 1.02），卡面抬到
        // #232625 → 对 #121413 = 1.24。门槛钉在 1.2（旧 1.13 必红）。
        assertTrue(
            Contrast.contrastRatio(RelayDarkSurface, RelayDarkBackground) >= 1.2,
            "暗色卡面对底 ≥1.2 —— D10 旧值 #1E201F 只有 1.13，卡与页糊成一片",
        )
    }
}
