// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 顶栏 `.ibtn` 图标砖的**触达**守卫。
 *
 * ## 守的是什么
 *
 * `.ibtn` 的视觉是 34dp（聊天页 30dp）—— 那是设计契约，一个像素不能动；但 34dp
 * 低于 48dp 的无障碍触控下限（`AppButtons.kt` 的 KDoc 有明文裁决），历史上
 * `IbtnBox` 直接 `.size(34).clickable`，真机上拇指点不中。
 *
 * ## 为什么只能靠源码守卫
 *
 * 「命中区多大」要在真机上用手指量；本仓没有 Compose UI 测试基建。源码层能钉住的
 * 是**结构**：可点的必须是吃满 48dp 触控 token 的外层，视觉砖（`.size(size)`）
 * 上不得直接挂 clickable —— 把旧写法（`size(size).clip(...).clickable`）放回去，
 * 这两条断言都会红。
 */
class TopBarsTouchTargetGuardTest {

    private val path = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/TopBars.kt"

    private fun ibtnBoxBody(): String {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(path))
        return GuardSourceScan.bracedBlockAfter(text, "private fun IbtnBox(")
    }

    @Test
    fun theClickableSurfaceIsTheFullTouchTargetNotTheVisualBrick() {
        val body = ibtnBoxBody()
        assertTrue(
            body.contains(".size(LocalAppSpacing.current.xxxl)"),
            "IbtnBox 没有吃满触控 token（spacing.xxxl = 48dp）—— 34/30dp 的命中区在真机上点不中",
        )
        val clickableAt = body.indexOf(".clickable(")
        val visualBrickAt = body.indexOf(".size(size)")
        assertTrue(
            clickableAt >= 0,
            "IbtnBox 没有 clickable —— 顶栏动作图标点不了",
        )
        assertTrue(
            clickableAt < visualBrickAt,
            "clickable 挂在了视觉砖上（.size(size) 之后）—— 命中区又缩回 34/30dp 了；" +
                "必须是外层 48dp 命中区 + 内层视觉砖的两层结构",
        )
    }

    @Test
    fun theVisualBrickKeepsItsContractedGeometry() {
        val body = ibtnBoxBody()
        // 视觉砖还在（.size(size)），且底色仍然画在砖上而不是 48dp 外层上 ——
        // 触达翻倍不许把原型的 34dp 浅色方块撑大。
        assertTrue(
            body.contains(".size(size)"),
            "视觉砖没了 —— .ibtn 的 34/30dp 是设计契约，不许被触控目标吃掉",
        )
        val backgroundAt = body.indexOf(".background(SurfaceTiers.Inset)")
        val clickableAt = body.indexOf(".clickable(")
        assertTrue(
            backgroundAt >= 0 && backgroundAt > clickableAt,
            "视觉砖的底色没了或挂到了外层命中区上 —— 原型的浅色方块会被撑成 48dp",
        )
    }
}
