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
 * 同一文件另两处 `dismissOnBackPress = false` 是对的：等待同意、共享中置顶条都不是终态，
 * 误关会丢掉进行中的会话 —— 所以这条守卫同时钉住「该开的开、该拦的拦」。
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
            2,
            blocked,
            "等待同意与共享中置顶条这两处必须继续拦返回键（误关会丢掉进行中的会话）；" +
                "数字对不上说明有人把该拦的那两处也放开了",
        )
    }

    private companion object {
        const val REMOTE_VIEW =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/RemoteViewSessionDialog.kt"
    }
}
