// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫「桌面长列表均已正确接入自适应垂直滚动条」。
 *
 * ## 背景
 * 桌面长列表（会话列表、节点列表、传输日志、设置面板）缺少滚动条时，用户无法直观获知内容跨度与位置，
 * 也无法通过鼠标拖拽滚动。本测试守卫：
 * 1. 跨平台滚动条 expect/actual 契约在各源集完整落地；
 * 2. 关键桌面长列表视图（会话列表、节点列表、传输日志、设置页）均已接入对应滚动条。
 *
 * ## 反向验证
 * 移除 ConversationListPanel 中的 PlatformLazyListScrollbar 调用点，测试立即变红。
 */
class PlatformScrollbarWiringTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun conversationListPanelWiresPlatformScrollbar() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/conversations/ConversationListPanel.kt")
        assertTrue(
            src.contains("PlatformLazyListScrollbar("),
            "ConversationListPanel 必须接入 PlatformLazyListScrollbar",
        )
    }

    @Test
    fun desktopNodesPaneWiresPlatformScrollbar() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNodesPane.kt")
        assertTrue(
            src.contains("PlatformLazyListScrollbar("),
            "DesktopNodesPane 必须接入 PlatformLazyListScrollbar",
        )
    }

    @Test
    fun desktopTransferLogPaneWiresPlatformScrollbar() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopTransferLogPane.kt")
        assertTrue(
            src.contains("PlatformScrollbar("),
            "DesktopTransferLogPane 必须接入 PlatformScrollbar",
        )
    }

    @Test
    fun desktopSettingsChromeWiresPlatformScrollbar() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopSettingsChrome.kt")
        assertTrue(
            src.contains("PlatformScrollbar("),
            "DesktopSettingsChrome 必须接入 PlatformScrollbar",
        )
    }

    @Test
    fun settingsPageChromeWiresPlatformScrollbar() {
        val src = live("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsPageChrome.kt")
        assertTrue(
            src.contains("PlatformScrollbar("),
            "SettingsPageChrome 必须接入 PlatformScrollbar",
        )
    }
}
