// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 导航件（竖导 / 底导）的**读屏语义**守卫 —— 手机那份与桌面那份一起盯。
 *
 * ## 守的是什么
 *
 * 1. 竖导按钮与底导页签是同一物种：角色
 *    [androidx.compose.ui.semantics.Role.Tab] + 选中态 `semantics { selected }` +
 *    `mergeDescendants = true`（图标与标签并进同一个可点节点）。缺了它们，读屏软件
 *    只会念「按钮」、不念「哪个被选中」；不合并则会把同一个页签念两遍。
 * 2. 页签图标的 `contentDescription` 是 null：标签文字就在旁边可见着，
 *    图标再描述一遍会让 TalkBack 把同一个词播两次。
 * 3. 竖导 / 底导容器是 `selectableGroup()`：读屏才知道「第 2 个，共 5 个」。
 *
 * ## 为什么两条路径都必须出现在同一张表里
 *
 * 这套语义有两份实现：手机横屏的 `CompactScaffold.NavRailButton`，与桌面/平板的
 * `ui.desktop.DesktopNavigation.RailButton` / `CompactNavigationBar`。守卫此前只写死
 * 手机那一份的路径，于是「只加一侧」能静默通过 —— 桌面侧长期是个裸 `clickable`，
 * 读屏在平板上只念「按钮」。现在两条路径同表列一遍，断言消息带文件名：
 * 任何一侧回退都会红，而且**删掉一条扫描不会让另一条替它绿**（各断言各自带路径）。
 *
 * ## 为什么只能靠源码守卫
 *
 * 语义属性渲染进 accessibility 树，本仓没有 Compose UI 测试基建读不到；
 * 源码层钉结构 —— 把 `Role.Tab` / `selected` / `mergeDescendants` /
 * `contentDescription = null` 任何一处拿掉，对应断言就红。
 */
class CompactScaffoldSemanticsGuardTest {

    private val compactPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/CompactScaffold.kt"
    private val desktopPath =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNavigation.kt"

    private fun block(path: String, marker: String): String =
        GuardSourceScan.bracedBlockAfter(GuardSourceScan.stripComments(GuardSourceScan.read(path)), marker)

    private class NavButton(val where: String, val body: String)

    /** 两份竖导按钮：手机横屏（CompactScaffold）与桌面/平板（DesktopNavigation）。 */
    private fun railButtons(): List<NavButton> = listOf(
        NavButton("$compactPath · NavRailButton", block(compactPath, "private fun NavRailButton(")),
        NavButton("$desktopPath · RailButton", block(desktopPath, "public fun RailButton(")),
    )

    @Test
    fun everyRailButtonAnnouncesTabRoleAndSelection() {
        for (nav in railButtons()) {
            assertTrue(
                nav.body.contains("Role.Tab"),
                "${nav.where} 没有 Role.Tab —— 读屏软件只会念「按钮」，不知道这是页签",
            )
            assertTrue(
                nav.body.contains("this.selected = selected"),
                "${nav.where} 没有汇报选中态（semantics { selected }）—— " +
                    "读屏用户在竖导上分不清当前停在哪个页签",
            )
            assertTrue(
                nav.body.contains("semantics(mergeDescendants = true)"),
                "${nav.where} 没有 mergeDescendants = true —— 图标与标签是两个语义节点，" +
                    "TalkBack 会把同一个页签分两次念（先「已选中」、再念标签）",
            )
        }
    }

    @Test
    fun theDesktopBottomBarSpeaksTheSameLanguageAsTheRail() {
        val body = block(desktopPath, "public fun CompactNavigationBar(")
        assertTrue(
            body.contains("Role.Tab") && body.contains("this.selected = selected"),
            "$desktopPath · CompactNavigationBar 的页签没有 Role.Tab / selected 语义 —— " +
                "同一个桌面壳在窄档与中档讲两种话",
        )
        assertTrue(
            body.contains("semantics(mergeDescendants = true)"),
            "$desktopPath · CompactNavigationBar 的页签没有合并后代语义 —— 标签会被单独念第二遍",
        )
    }

    @Test
    fun navIconsDoNotDuplicateTheVisibleLabel() {
        // 手机横屏竖导：图标是内联的 Icon，直接钉它自己的 contentDescription。
        assertTrue(
            block(compactPath, "private fun NavRailButton(").contains("contentDescription = null"),
            "$compactPath · NavRailButton 的图标 contentDescription 又填了标签文案 —— 与下方可见文字重复播报",
        )
        // 桌面竖导 / 窄底导：图标由 NavIconWithBadge 统一画，钉那一处即可。
        for (nav in listOf("public fun RailButton(", "public fun CompactNavigationBar(")) {
            assertTrue(
                block(desktopPath, nav).contains("NavIconWithBadge("),
                "$desktopPath · $nav 的图标应走 NavIconWithBadge（唯一一处图标定义），不要各画一份",
            )
        }
        assertTrue(
            block(desktopPath, "private fun NavIconWithBadge(").contains("contentDescription = null"),
            "$desktopPath · NavIconWithBadge 的 contentDescription 又填了 label —— " +
                "两个调用点都把标签画在图标旁边可见着，TalkBack 会念两遍",
        )
        assertTrue(
            block(compactPath, "private fun NavIcon(").contains("contentDescription = null"),
            "底导图标的 contentDescription 又填了标签文案 —— NavigationBarItem 的标签就是读屏名字，重复播报",
        )
    }

    @Test
    fun navGroupsAreSelectableGroups() {
        assertTrue(
            block(compactPath, "private fun CompactNavigationRail(").contains("selectableGroup()"),
            "$compactPath · CompactNavigationRail 的列容器没有 selectableGroup() —— " +
                "读屏拿不到「第 2 个，共 5 个」",
        )
        assertTrue(
            block(desktopPath, "public fun NavigationRail(").contains("selectableGroup()"),
            "$desktopPath · NavigationRail 的列容器没有 selectableGroup()",
        )
        assertTrue(
            block(desktopPath, "public fun CompactNavigationBar(").contains("selectableGroup()"),
            "$desktopPath · CompactNavigationBar 的行容器没有 selectableGroup()",
        )
        // 手机竖屏底导演的是 M3 NavigationBarItem，分组语义由 M3 自己挂，这里不需要重复。
    }
}
