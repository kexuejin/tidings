// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 「回复 / 长按头像直接 @ 之后，焦点要落进输入框」——桌面那条路的接缝守卫。
 *
 * ## 真身
 *
 * `ChatScreen` 的 `composerFocus` 只在**内置输入栏**分支挂载（MessageComposer 才带
 * `Modifier.focusRequester`）。桌面走 `composerSlot`，那个 requester 没有挂载点：
 * 调 `requestFocus()` 只会打印 `FocusRequester is not initialized` 并返回 false ——
 * 不崩，但光标留在消息列表上，用户看到「点了回复 / @ 了人，还得自己再点一下输入框」。
 *
 * 修法：焦点表达成**计数意图**（[COMPOSER_FOCUS_REQUEST_IDLE] /
 * [nextComposerFocusRequest]）经 `ComposerSlotArgs.focusRequest` 交给桌面输入卡片，
 * 由卡片自己的 requester 在 `LaunchedEffect(focusRequest)` 里消费。
 *
 * ## 为什么键必须是计数而不是布尔
 *
 * 布尔从 true 到 true 不产生新键 —— 同一会话里第二次点「回复」就不聚焦了。
 * [nextComposerFocusRequest] 的三条性质钉的就是这件事。
 *
 * ## 反向验证
 *
 * 1. 把 `nextComposerFocusRequest` 改成原样返回 current
 *    ⇒ [theRequestAlwaysChangesSoTheEffectReruns] 红；
 * 2. `ChatScreen` 的 onReply 退回直接 composerFocus.requestFocus()
 *    ⇒ [replyAndMentionGoThroughTheFocusIntent] 红；
 * 3. 桌面删掉 `LaunchedEffect(focusRequest)` 里的 requestFocus
 *    ⇒ [theDesktopComposerConsumesTheIntentWithoutRetrying] 红。
 */
class ComposerFocusRequestTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    // ---------------------------------------------------------------- 纯函数性质

    @Test
    fun idleMeansNoRequestAndTheFirstRequestIsNotIdle() {
        assertEquals(0, COMPOSER_FOCUS_REQUEST_IDLE, "空闲值必须是 0 —— 它同时是 remember 的初值")
        assertNotEquals(
            COMPOSER_FOCUS_REQUEST_IDLE,
            nextComposerFocusRequest(COMPOSER_FOCUS_REQUEST_IDLE),
            "第一次请求不能停在空闲值上：值不变，LaunchedEffect 的键就不变，聚焦永远不发生",
        )
    }

    @Test
    fun theRequestAlwaysChangesSoTheEffectReruns() {
        var current = COMPOSER_FOCUS_REQUEST_IDLE
        repeat(50) {
            val next = nextComposerFocusRequest(current)
            assertNotEquals(current, next, "连续两次请求拿到了同一个值 —— 第二次点「回复」不会聚焦")
            current = next
        }
    }

    @Test
    fun theRequestNeverWrapsBackToIdleOrNegative() {
        assertEquals(
            1,
            nextComposerFocusRequest(Int.MAX_VALUE),
            "溢出回绕到 1，不能回到 0（0 的语义是没有请求过）",
        )
        assertTrue(nextComposerFocusRequest(Int.MAX_VALUE - 1) > 0, "正常递增值必须为正")
    }

    // ---------------------------------------------------------------- 接线守卫

    /** slot 的接缝里真的有一个焦点意图字段，且边界值是共享常量。 */
    @Test
    fun theSlotCarriesAFocusIntent() {
        assertTrue(
            live(CHAT_SCREEN_ARGS).contains("val focusRequest: Int = COMPOSER_FOCUS_REQUEST_IDLE"),
            "ComposerSlotArgs 没有焦点意图字段 —— 桌面没有任何办法知道用户要求聚焦了",
        )
    }

    /** ChatScreen 的出口：槽存在时递计数器，否则直接请求（内置输入栏的 requester 是挂着的）。 */
    @Test
    fun chatScreenBranchesOnWhichComposerIsInUse() {
        assertTrue(
            live(CHAT_SCREEN).contains("focusRequest = composerFocusRequest"),
            "ChatScreen 没把焦点意图交给 composerSlot —— 桌面永远收不到",
        )
        val outlet = GuardSourceScan.bracedBlockAfter(live(CHAT_SCREEN), "fun requestComposerFocus(")
        assertTrue(
            outlet.contains("composerFocus.requestFocus()"),
            "内置输入栏那条路丢了直接请求焦点",
        )
        assertTrue(
            outlet.contains("nextComposerFocusRequest(composerFocusRequest)"),
            "桌面那条路没有递增计数 —— 意图发不出去",
        )
    }

    /** 「回复」与「长按头像直接 @」都必须走同一个焦点出口。 */
    @Test
    fun replyAndMentionGoThroughTheFocusIntent() {
        val chat = live(CHAT_SCREEN)
        assertTrue(
            GuardSourceScan.bracedBlockAfter(chat, "onReply = {").contains("requestComposerFocus()"),
            "气泡菜单的「回复」没有走焦点出口 —— 回复完光标不会进输入框",
        )
        assertTrue(
            GuardSourceScan
                .bracedBlockAfter(chat, "val mentionSender: (SenderIdentity) -> Unit = {")
                .contains("requestComposerFocus()"),
            "长按头像「直接 @」没有走焦点出口 —— @ 插进去了但焦点没进来",
        )
    }

    /** 桌面真的消费了意图，而且**不重试**（重试会把已经可见的焦点再抢一次）。 */
    @Test
    fun theDesktopComposerConsumesTheIntentWithoutRetrying() {
        val desktop = live(DESKTOP_COMPOSER)
        assertTrue(
            desktop.contains("focusRequest: Int = COMPOSER_FOCUS_REQUEST_IDLE"),
            "DesktopComposer 没有 focusRequest 参数 —— 接缝有了但没人收",
        )
        val effect = GuardSourceScan.bracedBlockAfter(desktop, "LaunchedEffect(focusRequest)")
        assertTrue(
            effect.contains("focusRequester.requestFocus()"),
            "消费焦点意图的 effect 里没有请求焦点 —— 意图被吞掉了",
        )
        assertFalse(
            effect.contains("withFrameNanos"),
            "焦点意图的 effect 里出现了按帧重试 —— 那是首次进会话那条的写法；" +
                "卡片此时早已在树上，重试等于在赌时序并会持续抢焦点",
        )
        assertTrue(
            live(DESKTOP_CHAT_PANE).contains("focusRequest = args.focusRequest"),
            "ChatPane 没把 slot 的焦点意图接进 DesktopComposer",
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
