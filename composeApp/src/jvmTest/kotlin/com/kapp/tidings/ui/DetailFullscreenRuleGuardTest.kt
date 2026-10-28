// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「详情类一律全屏」的规则守卫。
 *
 * ## 为什么是源码守卫
 *
 * 「二级页底下还挂不挂一级页的底导」是导航结构事实：手机壳（App.kt）的全屏分支
 * 与桌面窄档（DesktopShell.kt）的底导渲染都是纯结构，没有运行时行为可断言，
 * 且本仓没有 Compose UI 测试基建 —— 与 ShellAccessibilityGuardTest 同一取舍。
 *
 * 这条规则统一了三套并存的行为（手机聊天全屏 / 手机其余二级页留底导 /
 * 桌面窄档聊天留底导）：tab 页是唯一挂底导的层，详情类全屏、退路是各自的顶栏。
 *
 * 反向验证：把 App.kt 全屏分支里的任一条件挪走、或把 DesktopShell 的底导
 * 渲染改回无条件，对应断言立刻红。
 */
class DetailFullscreenRuleGuardTest {

    private fun src(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun everyMobileSecondaryPageIsRenderedOutsideTheTabScaffold() {
        val app = src("composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt")
        assertTrue(
            Regex(
                "else if \\(\\s*globalSearchOpen \\|\\|\\s*chatDetailOnTop \\|\\|\\s*agentSubpageOnTop \\|\\|" +
                    "\\s*settingsSubpageOnTop \\|\\|\\s*peerDetailOnTop \\|\\|\\s*peerCabinetOnTop\\s*\\)",
            ).containsMatchIn(app),
            "手机端全屏分支缺了某类二级页：全局搜索/聊天/Agent 子页/设置子页/" +
                "节点详情/同事文件柜必须全部整屏渲染，底下不再挂底导",
        )
        assertTrue(
            app.contains("nav.selectedTabId == AppRoutes.SETTINGS && settingsPage != null"),
            "设置子页的全屏判据必须钉住当前页签，防止残留状态劫持别的页签",
        )
    }

    @Test
    fun desktopCompactHidesTheBottomBarWhileDetailIsOnTop() {
        val shell = src("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt")
        assertTrue(
            shell.contains("if (!compactDetailOnTop)"),
            "桌面窄档在详情盖满内容区时必须隐藏底导（if (!compactDetailOnTop) 包住 CompactNavigationBar）",
        )
        assertTrue(
            shell.contains("view == DesktopView.Conversations && selectedPeerId != null") &&
                shell.contains("state.cabinet.activePeerId != null"),
            "compactDetailOnTop 必须覆盖会话详情与同事文件柜两类详情",
        )
    }
}
