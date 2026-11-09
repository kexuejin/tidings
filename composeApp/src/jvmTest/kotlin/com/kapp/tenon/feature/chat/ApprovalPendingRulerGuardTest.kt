// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「待审批只有一把尺子」（O3，A22 第八轮真机立案）。
 *
 * ## 真身
 *
 * 手机输入提示条挂着「等待你审批工具调用…」永不消失 —— 5 条 hook-* 审批的
 * `expiresAt` 已过数小时。根因：[ChatAgentSessionState] 手写
 * `approval.status == "pending"`，漏掉过期判据；而全仓唯一判据
 * `isPendingApproval`（pending **且未过期**）的 KDoc 里明写「此前各处手写
 * `status == "pending"`，页头说 2 项、段头说 3 项 —— 同一个屏两把尺子」。
 * 同病还有 [desktopNavBadges] 的徽标计数。
 *
 * ## 为什么只能靠源码守卫
 *
 * 两处都在 Composable/纯函数内部状态，判据是"用了哪把尺子"——
 * 与 [DispatchWorkspaceMemoryWiringTest] 同一取舍。
 *
 * ## 反向验证
 *
 * 把任一处改回手写 `status == "pending"` ⇒ 对应断言红。
 * （判据本身的语义由 InboxWorkspaceUnits 的既有测试钉住，这里只钉"用的是它"。）
 */
class ApprovalPendingRulerGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /** 输入提示条的待审批必须过唯一判据（过期的不许再挂「等待你审批」）。 */
    @Test
    fun theComposerStripUsesTheSolePendingRuler() {
        val s = live(CHAT_AGENT_SESSION_STATE)
        assertTrue(
            s.contains("isPendingApproval(approval, now)"),
            "输入提示条没有走全仓唯一判据 isPendingApproval：过期审批会永远挂着" +
                "「等待你审批工具调用…」（O3 复发）",
        )
        assertFalse(
            s.contains("approval.status == \"pending\""),
            "手写 status==\"pending\" 尺子回来了 —— 与 isPendingApproval 分叉," +
                "页头/角标/提示条将各说各话",
        )
    }

    /** 桌面导航徽标同罪同治：计数也必须过唯一判据。 */
    @Test
    fun theDesktopNavBadgeUsesTheSolePendingRuler() {
        val s = live(DESKTOP_NAVIGATION)
        assertTrue(
            s.contains("pendingApprovalCount(agent.agentApprovals.values, now)"),
            "桌面导航徽标的待审批计数没有走唯一判据 pendingApprovalCount",
        )
        assertFalse(
            s.contains("it.status == \"pending\""),
            "手写 status==\"pending\" 尺子回来了（桌面徽标会把过期审批也数成待办）",
        )
    }

    private companion object {
        const val CHAT_AGENT_SESSION_STATE =
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatAgentSessionState.kt"
        const val DESKTOP_NAVIGATION =
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopNavigation.kt"
    }
}
