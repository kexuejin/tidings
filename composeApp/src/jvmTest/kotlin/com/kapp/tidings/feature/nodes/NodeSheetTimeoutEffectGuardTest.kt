// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.nodes

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「连接」超时反馈链的**接线守卫**：计时只挂在这一次尝试上，成功判定另起一条。
 *
 * 为什么必须有这条守卫：这条修复的回归**不改变任何纯函数的输出** —— 把计时 effect 的
 * key 改回 `(attempted, state.peers)`，节点表每次心跳都会重启计时、把 `timedOut` 抹回
 * false，红字永远出不来；而 `connectAttemptTimedOut` 的纯函数测试照样全绿。
 * 本仓没有 Compose UI 测试基建，"effect 挂在哪个 key 上"只能在源码层钉住
 * （与 `DeadButtonGuardTest` 同一取舍）。
 *
 * 反向验证：
 *  - 计时 effect 的 key 改回 `state.peers` → [timerEffectIsKeyedOnTheAttemptNotOnThePeerList] 变红；
 *  - 把 `delay(CONNECT_ATTEMPT_TIMEOUT_MS)` 挪回成功判定那条 effect → 同一条变红；
 *  - 窗口写回字面量 `delay(3_200)` → [timeoutWindowIsSharedWithTheController] 变红。
 */
class NodeSheetTimeoutEffectGuardTest {

    private val nodeSheets = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodeSheets.kt"

    private fun live(): String = GuardSourceScan.stripComments(GuardSourceScan.read(nodeSheets))

    @Test
    fun timerEffectIsKeyedOnTheAttemptNotOnThePeerList() {
        val text = live()
        val marker = "LaunchedEffect(attempted, attemptGen)"
        assertTrue(
            text.contains(marker),
            "计时的 effect 必须只挂在 attempted/attemptGen 上。挂在 state.peers 上时，" +
                "节点表每次心跳都会重启它、把 timedOut 抹回 false —— 网络越活跃红字越出不来。",
        )
        val timer = GuardSourceScan.bracedBlockAfter(text, marker)
        assertTrue(
            timer.contains("delay(CONNECT_ATTEMPT_TIMEOUT_MS)"),
            "计时 effect 里要等够共享窗口常量；少了它这一条就成了「点一下就说超时」。",
        )
        assertTrue(timer.contains("timedOut = true"), "等够之后才置红字。")
        assertFalse(
            timer.contains("state.peers"),
            "计时不可以看节点表：那是成功判定的事（另起一条 effect）。",
        )

        val success = GuardSourceScan.bracedBlockAfter(text, "LaunchedEffect(attempted, state.peers)")
        assertFalse(
            success.contains("delay("),
            "成功判定那条 effect 不能再带计时 —— 上一版就是两条合在一起，被 peers 抖动无限重置。",
        )
        assertTrue(
            success.contains("onDismiss()"),
            "迟到的成功也是成功：目标进了节点表就关弹层。",
        )
    }

    @Test
    fun timeoutWindowIsSharedWithTheController() {
        val text = live()
        assertTrue(
            text.contains("import com.kapp.tidings.domain.CONNECT_ATTEMPT_TIMEOUT_MS"),
            "窗口要与控制器里握手提示的过期共用一份常量：两边各写一个数，红字与横幅就永远差一拍。",
        )
        assertFalse(
            Regex("delay\\(\\s*3_?200\\s*\\)").containsMatchIn(text),
            "不要写回字面量超时窗口 —— 改一处漏一处正是这次要消灭的形态。",
        )
    }
}
