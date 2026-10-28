// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「关闭并清空会话」接线完整性与破坏性动作防护的源码级守卫。
 *
 * ## 守卫的三条不变量
 *
 * 1. **二次确认框的破坏性形态**：
 *    - 标题/正文/确认文案均使用专属本地化资源（[chat_close_and_clear_*]）；
 *    - 确认按钮必须声明 `danger = true`（红色警示，不与普通取消同色）；
 *    - 必须保留「取消」路径（[common_cancel]）。
 *
 * 2. **手机与桌面双壳完整接入**：
 *    - [ChatHeader] 与 [DesktopChatHeader] 必须均挂接 [chat_close_and_clear_action]；
 *    - 触发点击只能翻开确认状态（`showCloseAndClearConfirm = true`），不可在头部的 onClick 中直接调删除。
 *
 * 3. **确认后的双重语义（清空+退出）**：
 *    - 确认后必须执行清空会话（`controller.deleteConversation`）；
 *    - 并且必须退回到会话列表（手机端 `nav.leaveChatDetail()`，桌面端 `onBack?.invoke()`）。
 */
class CloseAndClearConversationGuardTest {

    private val clearDialogFile =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ClearConversationDialog.kt"
    private val chatHeaderFile =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatHeader.kt"
    private val chatScreenFile =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt"
    private val appFile =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt"
    private val desktopChatPaneFile =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopChatPane.kt"

    @Test
    fun dialogHasDestructiveWarningAndCancelAction() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(clearDialogFile))
        val dialogAt = text.indexOf("fun CloseAndClearConversationDialog(")
        assertTrue(dialogAt >= 0, "找不到 CloseAndClearConversationDialog 的定义")
        val dialogDef = text.substring(dialogAt)

        assertTrue(
            dialogDef.contains("chat_close_and_clear_title"),
            "对话框标题必须使用 chat_close_and_clear_title 资源",
        )
        assertTrue(
            dialogDef.contains("chat_close_and_clear_body"),
            "对话框正文必须使用 chat_close_and_clear_body 资源，明确说明只影响本机且不可恢复",
        )
        assertTrue(
            dialogDef.contains("chat_close_and_clear_confirm"),
            "确认按钮文案必须使用 chat_close_and_clear_confirm",
        )
        assertTrue(
            dialogDef.contains("danger = true"),
            "破坏性操作的确认按钮必须带 danger = true 标红",
        )
        assertTrue(
            dialogDef.contains("common_cancel"),
            "必须提供取消操作",
        )
    }

    @Test
    fun chatHeaderAndScreenRequireConfirmationBeforeCloseAndClear() {
        val headerText = GuardSourceScan.stripComments(GuardSourceScan.read(chatHeaderFile))
        assertTrue(
            headerText.contains("onCloseAndClearConversation: (() -> Unit)? = null"),
            "ChatHeader 必须包含 onCloseAndClearConversation 参数",
        )
        assertTrue(
            headerText.contains("chat_close_and_clear_action"),
            "ChatHeader 菜单项必须包含 chat_close_and_clear_action",
        )

        val screenText = GuardSourceScan.stripComments(GuardSourceScan.read(chatScreenFile))
        assertTrue(
            screenText.contains("var showCloseAndClearConfirm by remember(peerId) { mutableStateOf(false) }"),
            "ChatScreen 必须按 peerId 隔离二次确认状态",
        )
        assertTrue(
            screenText.contains("onCloseAndClearConversation = onCloseAndClearConversation?.let { { showCloseAndClearConfirm = true } }"),
            "ChatScreen 传给 ChatHeader 的动作必须仅打开确认框，不许直接调删除",
        )
        assertTrue(
            screenText.contains("CloseAndClearConversationDialog("),
            "ChatScreen 必须在确认状态下呈现 CloseAndClearConversationDialog",
        )
        assertTrue(
            screenText.contains("val onCloseAndClearConversation = actions.onCloseAndClearConversation"),
            "ChatScreen 必须解构 actions.onCloseAndClearConversation",
        )
        assertTrue(
            screenText.contains("onCloseAndClearConversation()"),
            "确认对话框确认时才真正触发 onCloseAndClearConversation",
        )
    }

    @Test
    fun mobileAppCompositionRootDeletesAndLeavesChat() {
        val appText = GuardSourceScan.stripComments(GuardSourceScan.read(appFile))
        assertTrue(
            appText.contains("controller.deleteConversation(convId)"),
            "App.kt 必须在关闭并清空会话时调用 controller.deleteConversation",
        )
        assertTrue(
            appText.contains("nav = nav.leaveChatDetail()"),
            "App.kt 必须在关闭并清空会话后退回会话列表",
        )
    }

    @Test
    fun desktopChatPaneIntegratesCloseAndClearWithConfirmation() {
        val desktopText = GuardSourceScan.stripComments(GuardSourceScan.read(desktopChatPaneFile))
        assertTrue(
            desktopText.contains("onCloseAndClearConversation: (() -> Unit)? = null"),
            "DesktopChatHeader 必须声明 onCloseAndClearConversation 参数",
        )
        assertTrue(
            desktopText.contains("chat_close_and_clear_action"),
            "DesktopChatHeader 必须接入 chat_close_and_clear_action",
        )
        assertTrue(
            desktopText.contains("var showCloseAndClearConfirm by remember(selectedPeerId) { mutableStateOf(false) }"),
            "Desktop ChatPane 必须持有 showCloseAndClearConfirm 状态",
        )
        assertTrue(
            desktopText.contains("CloseAndClearConversationDialog("),
            "Desktop ChatPane 必须挂接 CloseAndClearConversationDialog",
        )
        assertTrue(
            desktopText.contains("controller.deleteConversation(it)"),
            "Desktop 端确认后必须调用 controller.deleteConversation",
        )
        assertTrue(
            desktopText.contains("onBack?.invoke()"),
            "Desktop 端确认后必须调用 onBack?.invoke() 退出当前会话",
        )
    }
}
