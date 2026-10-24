// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「桌面输入卡片发消息真的走 ChatScreen 的发送出口」。
 *
 * ## 真身
 *
 * 桌面壳的输入卡片（`composerSlot`）从前**自己发消息**：
 * `controller.sendToConversation(peer.nodeId, text)`。于是三件事同时缺失 ——
 * 「回复」那条消息的 replyId 到不了发送侧（发出去是一条普通消息）、发完
 * `replyingToMessage` 没人清（引用条赖在输入框上方，下一条又变成"回复"）、
 * 外部草稿表靠另一条路碰巧清掉。手机内置输入栏这一切都对，差别只在"从哪个出口发"。
 *
 * 修法是把发送收成 `ChatScreen.submitMessage(body, replyId)` 一个出口：草稿复位、
 * 外部草稿表清空、引用态清空、replyId 传递四件事在同一个函数里发生；桌面 slot 通过
 * `ComposerSlotArgs.onSend` 拿到它。
 *
 * ## 为什么只能靠源码守卫
 *
 * 这条链路横跨四个文件、且全都是 Composable 内部状态（本仓没有 Compose UI 测试基建，
 * 与 `DraftClearOnSendWiringTest` / `DesktopComposerFocusGuardTest` 同一取舍）。
 * 纯函数式的"发送"不存在可断言的返回值：判据只能是**调用点在不在、走的哪一条**。
 *
 * ## 反向验证（每条都能分别变红）
 *
 * 1. 把 `DesktopComposer.send()` 改回 `controller.sendToConversation(peer.nodeId, text)`
 *    ⇒ [desktopSendGoesThroughTheSharedOutlet] 红；
 * 2. 把 `ChatPane` 的 `onSend = args.onSend` 删掉（退回默认实现）
 *    ⇒ [theDesktopSlotIsGivenTheSharedOutlet] 红；
 * 3. 把 `ChatScreen.submitMessage` 里的 `replyingToMessage = null` 删掉
 *    ⇒ [theSharedOutletClearsTheReplyState] 红。
 */
class ComposerSlotSendWiringTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * 发送出口把 replyId 用上：这是「回复」与「普通消息」的唯一区别。
     *
     * 桌面从前那一行是 `controller.sendToConversation(peer.nodeId, text)`（接口的第三个
     * 参数就是 replyTo，没传 = 丢）。只要它还出现在 send 的调用点，本条就红。
     */
    @Test
    fun desktopSendGoesThroughTheSharedOutlet() {
        val desktop = live(DESKTOP_COMPOSER)
        assertFalse(
            desktop.contains("controller.sendToConversation(peer.nodeId, text)"),
            "桌面输入卡片又自己发消息了：这条路径丢掉 replyId（「回复」变普通消息），" +
                "发完也不会清引用态。必须走 onSend(text, replyingTo?.id)",
        )
        assertTrue(
            desktop.contains("onSend(text, replyingTo?.id)"),
            "桌面的 send() 没把「正在引用的那条」交给发送出口 —— " +
                "用户点了回复，发出去的却是一条普通消息",
        )
    }

    /** slot 的发送入口必须是 ChatScreen 那个与手机内置输入栏共用的出口。 */
    @Test
    fun theDesktopSlotIsGivenTheSharedOutlet() {
        assertTrue(
            live(CHAT_SCREEN_ARGS).contains("val onSend: (body: String, replyToId: String?) -> Unit"),
            "ComposerSlotArgs 没有发送出口 —— 桌面要么自己发（丢 replyId），要么根本发不出去",
        )
        assertTrue(
            live(CHAT_SCREEN).contains("onSend = ::submitMessage"),
            "ChatScreen 没把 submitMessage 交给 composerSlot：桌面仍然走自己那条发送路径",
        )
        assertTrue(
            live(DESKTOP_CHAT_PANE).contains("onSend = args.onSend"),
            "ChatPane 没把 slot 的发送出口接进 DesktopComposer —— 接缝有了但没人用",
        )
    }

    /**
     * 出口本身必须四件事一起做：复位草稿、清外部草稿表、**清引用态**、带 replyId 发送。
     *
     * 「清引用态」是这一批最容易漏的一件：漏了以后引用条永远挂在输入框上方，
     * 用户每发一条都在"回复"上一条。
     */
    @Test
    fun theSharedOutletClearsTheReplyState() {
        val chat = live(CHAT_SCREEN)
        val outlet = GuardSourceScan.bracedBlockAfter(chat, "fun submitMessage(")
        assertTrue(
            outlet.contains("replyingToMessage = null"),
            "发送出口没有清引用态 —— 发完引用条还挂着，下一条又变成\"回复\"",
        )
        assertTrue(
            outlet.contains("notifyDraftCleared(peerId, onDraftChangeExternal)"),
            "发送出口没有清外部草稿表 —— 已发出的正文会在切回来时「复活」进输入框",
        )
        assertTrue(
            outlet.contains("onSend(peer.nodeId, body, replyId)"),
            "发送出口没把 replyId 交给发送侧 —— 回复语义在这一层就丢了",
        )
    }

    private companion object {
        const val CHAT_SCREEN =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt"
        const val CHAT_SCREEN_ARGS =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreenArgs.kt"
        const val DESKTOP_COMPOSER =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopComposer.kt"
        const val DESKTOP_CHAT_PANE =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopChatPane.kt"
    }
}
