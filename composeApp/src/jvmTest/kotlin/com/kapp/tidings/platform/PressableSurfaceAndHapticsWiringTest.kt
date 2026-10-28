// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫「高频点击卡片接入 PressableSurface 与关键交互触发触感反馈（Haptics）」。
 *
 * ## 背景
 * 触屏与桌面界面中，列表行、导航项与动作按钮若缺少按压形变与触感反馈，用户无法即时区分
 * 「是否按中」与「系统是否响应」。
 * 本测试守卫：
 * 1. 核心高频点击行/卡片接入 [com.kapp.tidings.ui.components.PressableSurface]；
 * 2. 复制、发送、导航切换、长按等关键交互接入 [androidx.compose.ui.hapticfeedback.HapticFeedbackType]。
 *
 * ## 反向验证
 * 临时移除 MessageComposer 中的 performHapticFeedback 调用，测试立即变红。
 */
class PressableSurfaceAndHapticsWiringTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun pressableSurfaceIsAdoptedInCoreSurfaces() {
        val targets = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/conversations/ConversationRow.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodeRows.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopSettingsChrome.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNodesPane.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopFilesPane.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopConversationWidgets.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNavigation.kt",
        )
        targets.forEach { path ->
            val src = live(path)
            assertTrue(
                src.contains("PressableSurface("),
                "$path 必须接入 PressableSurface",
            )
        }
    }

    @Test
    fun hapticsAreTriggeredInKeyInteractions() {
        val targets = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatBubbles.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/MessageComposer.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/CompactScaffold.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNavigation.kt",
        )
        targets.forEach { path ->
            val src = live(path)
            assertTrue(
                src.contains("performHapticFeedback("),
                "$path 必须触发 performHapticFeedback",
            )
        }
    }
}
