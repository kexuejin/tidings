// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.components

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守卫屏幕协助失败卡能按返回键关掉。
 *
 * 它是**终态**错误卡（对方拒绝 / 断链 / 超时），按返回键没反应会被用户读成「卡死了」。
 *
 * 另一半规则在本轮变了：**「共享中」不再是模态对话框**（原来它盖住整个 App，用户共享时
 * 想回聊天指路都做不到 —— ui review 的 P1）。现在它是 `Popup(focusable = false)` 的置顶条，
 * 本来就不吃返回键。所以只剩「等待同意」这一处需要 `dismissOnBackPress = false`：
 * 那是一份待同意的会话，误关等于替对方拒绝。
 *
 * 守卫因此有两半：终态错误卡必须能关，「等待同意」必须拦住；而共享中置顶条**不许**再退回
 * 模态（出现第二个 `dismissOnBackPress = false` 或它重新用 Dialog 都要红）。
 *
 * 反向验证：把错误卡改回 `dismissOnBackPress = false`，两条测试都变红。
 */
class RemoteViewErrorBackGuardTest {

    @Test
    fun theTerminalErrorCardIsDismissibleWithBack() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(REMOTE_VIEW))
        // 按**花括号配平**取函数体，而不是按字符窗口：加几行注释就会滑出窗口，
        // 那会变成假红或假绿。
        val body = GuardSourceScan.bracedBlockAfter(live, "private fun RemoteViewErrorDialog(")
        assertTrue(
            body.contains("dismissOnBackPress = true"),
            "终态错误卡必须能按返回键关掉；false 会让它看起来像卡死了",
        )
    }

    @Test
    fun theNonTerminalDialogsStillBlockBack() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(REMOTE_VIEW))
        val blocked = Regex("dismissOnBackPress\\s*=\\s*false").findAll(live).count()
        assertEquals(
            1,
            blocked,
            "只剩「等待同意」这一处该拦返回键（误关等于替对方拒绝）；" +
                "共享中已改成非模态置顶条 —— 出现第 2 处说明有人又把它做回了模态对话框",
        )
        assertTrue(
            live.contains("Popup("),
            "共享中的置顶条必须是非模态 Popup：做回 Dialog 就会再次盖住整个 App" +
                "（用户共享时想回聊天指路都做不到，这是 ui review 的 P1）",
        )
    }

    private companion object {
        const val REMOTE_VIEW =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/RemoteViewSessionDialog.kt"
    }
}
