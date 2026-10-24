// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守卫「发送 ⇒ 外部草稿表同步清空」这条**接线**（不只是 helper 本身）。
 *
 * `notifyDraftCleared` 的纯函数契约由 `DraftRetentionTest` 钉住；但有人把 ChatScreen
 * onSend 里那一行调用删掉（回到旧行为：只复位本地 `draft`、外部表留着已发送的正文，
 * 下次回到会话时已发内容「复活」），helper 还在、纯函数测试仍全绿 —— 正是
 * 「测试绿 ≠ 测到了」的那一族。这条守卫盯着**调用点**。
 *
 * 反向验证：把 ChatScreen onSend 里的 `notifyDraftCleared(peerId, onDraftChangeExternal)`
 * 注释掉，[theSendPathActuallyClearsTheExternalDraft] 立刻变红。
 */
class DraftClearOnSendWiringTest {

    @Test
    fun theSendPathActuallyClearsTheExternalDraft() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(CHAT_SCREEN))
        assertTrue(
            live.contains("notifyDraftCleared(peerId, onDraftChangeExternal)"),
            "ChatScreen 的 onSend 没有调用 notifyDraftCleared：" +
                "发送后外部草稿表里留着已发送的正文，回到这个会话时它会在输入框里「复活」",
        )
        // 恰好一次：两份输入形态（内置输入栏 / composerSlot）共用这一个 onSend，
        // 不需要第二处清草稿 —— 多出来的那一处早晚清错东西。
        assertEquals(
            1,
            Regex("notifyDraftCleared\\s*\\(").findAll(live).count(),
            "notifyDraftCleared 在 ChatScreen 里只应有一个调用点（onSend）",
        )
    }

    private companion object {
        const val CHAT_SCREEN =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt"
    }
}
