// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings

import androidx.compose.ui.unit.dp
import com.kapp.tidings.navigation.AppLayoutMode
import com.kapp.tidings.ui.desktop.DesktopMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 自适应断点表。这些数字同时也是 `@Preview` 截图矩阵的尺寸，
 * 两边必须一致 —— 改了这里就要改截图矩阵，否则门禁拍的和应用跑的不是一回事。
 */
class AppLayoutModeTest {

    @Test
    fun phonePortraitIsSinglePane() {
        assertEquals(AppLayoutMode.CompactSinglePane, resolveAppLayoutMode(393.dp, 852.dp))
    }

    @Test
    fun shortAndWideIsCompactLandscape() {
        // 宽 > 高 且宽 < 600：手机横屏，仍然单栏，但换一套紧凑排布。
        assertEquals(AppLayoutMode.CompactLandscape, resolveAppLayoutMode(852.dp, 393.dp))
    }

    @Test
    fun tabletPortraitIsTwoPane() {
        assertEquals(AppLayoutMode.MediumTwoPane, resolveAppLayoutMode(800.dp, 1280.dp))
    }

    @Test
    fun wideIsListDetail() {
        assertEquals(AppLayoutMode.ExpandedListDetail, resolveAppLayoutMode(1280.dp, 800.dp))
    }

    @Test
    fun wideButShortStaysCompact() {
        // 800×400 够宽但不够高。分两栏会把内容挤扁，所以退回紧凑横屏。
        // 这条曾经是错的：旧实现只看宽度，852×393 的手机横屏被误判成两栏，
        // 与 Preview 截图矩阵（852×393 → CompactLandscape）不符。
        assertEquals(AppLayoutMode.CompactLandscape, resolveAppLayoutMode(800.dp, 400.dp))
    }

    @Test
    fun twoPaneNeedsBothWidthAndHeight() {
        // 边界取常量，不写数字：断点表和它渲染的外壳必须是同一件事。
        assertEquals(
            AppLayoutMode.MediumTwoPane,
            resolveAppLayoutMode(800.dp, TWO_PANE_MIN_HEIGHT_DP.dp),
        )
        // 高度差 1dp 就退回紧凑横屏。
        assertEquals(
            AppLayoutMode.CompactLandscape,
            resolveAppLayoutMode(800.dp, (TWO_PANE_MIN_HEIGHT_DP - 1).dp),
        )
        // 够高但不够宽也不是两栏。
        assertEquals(AppLayoutMode.CompactSinglePane, resolveAppLayoutMode(599.dp, 900.dp))
    }

    /**
     * 高度下限必须**与外壳同源**，不能各写一个数字。
     *
     * 回归的是这条真实缺陷：本层曾经用独立的 `600`，而外壳要的是
     * `MIN_CHAT_HEIGHT_PX + TABLET_CHROME_PX = 420 + 46 = 466`。A 比 B 严 134px，
     * 于是 `664..999` 宽 × `466..599` 高这一带（平板分屏的常见形状，如 `800×580`）
     * 被判成 `CompactLandscape` → 落回**手机壳的底部页签**；同一个窗口只要再高
     * 20px 就切成桌面壳双栏，中间是硬跳变。
     *
     * 这条断言盯的是**同源**这件事本身：把 `TWO_PANE_MIN_HEIGHT_DP` 的定义式
     * 抄一遍，而不是钉死 466 —— 将来任何一边调预算，这里会立刻红。
     */
    @Test
    fun 两栏高度下限与外壳预算同源() {
        assertEquals(
            DesktopMetrics.MIN_CHAT_HEIGHT_PX + DesktopMetrics.TABLET_CHROME_PX,
            TWO_PANE_MIN_HEIGHT_DP,
            "本层的高度下限必须由外壳预算推导（可用高度 + 平板 chrome），" +
                "写死一个独立数字就会重演 800×580 落手机壳那条缺陷。",
        )
        // 差额带里的两个样本：改前是 CompactLandscape（手机壳），改后必须是两栏。
        assertEquals(AppLayoutMode.MediumTwoPane, resolveAppLayoutMode(800.dp, 580.dp))
        assertEquals(AppLayoutMode.MediumTwoPane, resolveAppLayoutMode(800.dp, 520.dp))
        // 差额带上方的老边界仍然成立（不是把阈值一律放宽）。
        assertEquals(AppLayoutMode.MediumTwoPane, resolveAppLayoutMode(800.dp, 600.dp))
        // 手机横屏那条不受影响：852×393 宽度够、高度远低于下限。
        assertEquals(AppLayoutMode.CompactLandscape, resolveAppLayoutMode(852.dp, 393.dp))
        assertEquals(AppLayoutMode.CompactLandscape, resolveAppLayoutMode(800.dp, 400.dp))
    }

    @Test
    fun breakpointsAreInclusiveAtTheirBoundary() {
        // 两栏下界取外壳自己的预算常量，不写数字：断点表和它渲染的外壳必须是同一件事。
        assertEquals(
            AppLayoutMode.MediumTwoPane,
            resolveAppLayoutMode(DesktopMetrics.MEDIUM_MIN_PX.dp, 1000.dp),
        )
        assertEquals(AppLayoutMode.ExpandedListDetail, resolveAppLayoutMode(DesktopMetrics.EXPANDED_MIN_PX.dp, 1000.dp))
        // 差 1dp 就该落回上一档。
        assertEquals(
            AppLayoutMode.CompactSinglePane,
            resolveAppLayoutMode((DesktopMetrics.MEDIUM_MIN_PX - 1).dp, 1000.dp),
        )
        assertEquals(
            AppLayoutMode.CompactLandscape,
            resolveAppLayoutMode((DesktopMetrics.MEDIUM_MIN_PX - 1).dp, 400.dp),
        )
        assertEquals(
            AppLayoutMode.MediumTwoPane,
            resolveAppLayoutMode((DesktopMetrics.EXPANDED_MIN_PX - 1).dp, 1400.dp),
        )
    }

    @Test
    fun 方窗不再分栏因为聊天栏装不下输入栏() {
        // 620×620 曾经是两栏：620 − 竖导 64 − 侧栏 300 = 256px 聊天栏，
        // 桌面输入栏底行在那里是坏的（两条提示语叠在一起、发送按钮被挤出屏幕）。
        // 现在这个宽度退回单栏，聊天栏拿到整幅 620px。
        assertEquals(AppLayoutMode.CompactSinglePane, resolveAppLayoutMode(620.dp, 620.dp))
        // 刚够两栏的最小宽度，聊天栏正好拿到它的下限。
        assertEquals(
            AppLayoutMode.MediumTwoPane,
            resolveAppLayoutMode(DesktopMetrics.MEDIUM_MIN_PX.dp, 620.dp),
        )
    }
}
