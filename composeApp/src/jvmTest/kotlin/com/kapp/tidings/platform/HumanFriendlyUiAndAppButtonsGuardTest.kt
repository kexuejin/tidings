// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「文本术语去机械化、消除十六进制短 ID 露底与收敛至统一按钮族 AppButtons」。
 *
 * ## 背景
 * 1. UI 字符串里的内部数据库实现（如 "LIKE"）与机械术语（如 "能力："、"字节数"）破坏用户直觉；
 * 2. 人际沟通与会话详情视图中，直接退回 `nodeId.take(8)` 是技术露底（一串十六进制对用户无信息量）；
 * 3. 散落在各屏的裸 Material3 按钮（Button/OutlinedButton/IconButton）破坏触控下限与规格一致性。
 *
 * ## 反向验证
 * 临时在 ChatHeader 中换回裸 IconButton，测试立即变红。
 */
class HumanFriendlyUiAndAppButtonsGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun humanFriendlyUiStringsNoTechnicalLeak() {
        val xml = GuardSourceScan.read("composeApp/src/commonMain/composeResources/values/strings.xml")
        assertFalse(xml.contains("LIKE 最多取"), "搜索页脚不得向用户泄露 SQL LIKE 关键词")
        assertFalse(xml.contains("<string name=\"chat_caps_label\">能力："), "会话能力标签不得使用生硬的「能力：」")
        assertFalse(xml.contains("收到的字节数与声明不符"), "文件传输失败原因应使用自然大小表述，不得露底「字节数」")
    }

    @Test
    fun noRawTake8InHumanFacingViews() {
        val targets = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopChatPane.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/CabinetScreen.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/PeerCabinetView.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/HistorySearchOverlay.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopFilesPane.kt",
            "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopTrustedDevicesPanel.kt",
            "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopPeerWindow.kt",
        )
        targets.forEach { path ->
            val src = live(path)
            assertFalse(
                src.contains("owner.take(8)") ||
                    src.contains("activeAgentRun.peerId.take(8)") ||
                    src.contains("grant.nodeId.take(8)") ||
                    src.contains("cabinet.activePeerId.orEmpty().take(8)") ||
                    src.contains("hit.peerId).take(8)") ||
                    src.contains("peerId.take(8)") ||
                    src.contains("outcome.nodeId.take(8)") ||
                    src.contains("req.nodeId.take(8)"),
                "$path 存在裸露的十六进制 take(8) 回退，应使用好友备注或如实提示",
            )
        }
    }

    @Test
    fun appButtonsAreAdoptedInMigratedSurfaces() {
        val targets = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatHeader.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/TopBars.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/WorkspaceDetailView.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/AttachmentStagingRow.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/VoiceRecordDialog.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopComposer.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatBubbles.kt",
        )
        targets.forEach { path ->
            val src = live(path)
            val hasAdopted = src.contains("AppButton(") ||
                src.contains("AppOutlinedButton(") ||
                src.contains("AppTextButton(") ||
                src.contains("AppIconButton(") ||
                src.contains("PressableSurface(")
            assertTrue(hasAdopted, "$path 必须接入 AppButtons 统一按钮族或 PressableSurface")
        }
    }
}
