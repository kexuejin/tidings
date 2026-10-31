// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面导航层图标一源（整改方案 V4）：`desktopViewIcon` 与竖导/底导的图标全部来自
 * drawable `ic_*`（与原型 sprite 同一套 stroke 语言、由 design/icon-mapping.json
 * 逐 symbol 对账），material-icons 的矢量字形在**导航层**退场。
 *
 * ## 为什么要一源
 *
 * 同一个语义两个来源（终端 = `Icons.Outlined.Terminal` 又 = `ic_terminal`），
 * `designIconGate` 只对账 drawable 一侧 —— 矢量字形那半张图在映射之外，改了原型
 * sprite 它不会红。V4 收敛后导航层进对账面；`DesktopComposer` 的 DragHandle/Send
 * 属分批（本包不动，写进 V4 的 KDoc 与台账）。
 *
 * ## 为什么只能源码守卫
 *
 * 「图标从哪族来」在渲染帧里只有像素差，源级判据（是否引用 Icons.*）才能把
 * 「这一层不再有第二族」钉成可判红的纪律。
 */
class DesktopNavigationIconGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val navPath = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNavigation.kt"

    /**
     * 回退方式：把 `Icons.AutoMirrored.Outlined.Chat` 等矢量字形放回
     * desktopViewIcon ⇒ 本条红。
     */
    @Test
    fun navigationLayerHasNoMaterialIconImports() {
        val nav = live(navPath)
        assertFalse(
            nav.contains("Icons.Outlined") || nav.contains("Icons.AutoMirrored"),
            "桌面导航层还有 material-icons 字形 —— 与 drawable ic_* 双源并存（V4 收敛裁决）",
        )
    }

    /**
     * desktopViewIcon 返回 Painter（drawable 资源），五个视图逐一对上原型的
     * sprite symbol（DVIEWS: chat/folder/hdd/lan/terminal）。
     *
     * 回退方式：删掉任一 ic_* 分支 ⇒ 本条红。
     */
    @Test
    fun viewIconsComeFromDrawableSprites() {
        val nav = live(navPath)
        val at = nav.indexOf("fun desktopViewIcon(")
        val fn = if (at < 0) "" else nav.substring(at)
        for (needle in listOf("ic_chat_bubble", "ic_folder", "ic_hdd", "ic_lan_search", "ic_terminal")) {
            assertTrue(
                fn.contains(needle),
                "desktopViewIcon 缺 [$needle] —— 五视图图标必须逐一对上原型 DVIEWS 的 symbol",
            )
        }
    }
}
