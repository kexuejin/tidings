// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2 规格 4 的**接线守卫**：熔断卡的动作是数据驱动的，UI 复用既有输入抽屉。
 * 纯函数测试全绿不等于真机上能点 —— 少了任何一环，用户面对的仍是一张只能推倒重来的卡。
 */
class HealSteeringWiringGuardTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )
    private val actions = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/HostCardActions.kt"),
    )
    private val cards = GuardSourceScan.stripComments(
        GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/AgentCards.kt"),
    )

    @Test
    fun theExhaustedCardIsGivenTheSteeringActions() {
        assertTrue(orchestrator.contains("cardWithSteeringActions("), "熔断卡必须换上纠偏动作，不是旧的 Retry")
        assertTrue(orchestrator.contains("humanSteering = !ticket.steeringUsed"),
            "终验机会用过后不再给输入槽（第二次请求不许无限循环）")
        assertTrue(orchestrator.contains("humanSteering: Boolean = false"),
            "缺省 false：其它状态的卡片形状一字不变")
    }

    @Test
    fun theHostGrantsExactlyOneFinalRound() {
        assertTrue(orchestrator.contains("HealSteering.grantOnce("), "人工指引必须过单次授权")
        assertTrue(orchestrator.contains("maxHealRounds: Int = HealSteering.BASE_ROUNDS"),
            "自愈上限要有单一事实源，缺省仍是 3 轮")
        assertTrue(orchestrator.contains("maxHealRounds = 1"), "纠偏重跑只允许 1 轮终验")
        assertTrue(orchestrator.contains("steeringUsed = true"), "用过的机会必须落账")
    }

    @Test
    fun anAbandonActionKeepsTheWorkspaceInsteadOfDeletingIt() {
        assertTrue(actions.contains("\"abandon-heal\""), "放弃按钮要有宿主分支，不能落到插件转发被静默丢弃")
        assertTrue(orchestrator.contains("abandonHealByCard"), "放弃要走编排器（保留现场 + 终局翻面）")
        val body = orchestrator.substringAfter("fun abandonHealByCard(").substringBefore("\n    }")
        assertFalse(body.contains("cleanupTask") || body.contains("deleteRecursively") ||
            body.contains("worktree remove"), "放弃分支不许清理现场：${body.take(120)}")
    }

    @Test
    fun theInputDrawerIsTheExistingOneNotANewUiComponent() {
        assertTrue(cards.contains("SYSTEM_ACTION_REJECT_REVISION"),
            "输入槽复用既有抽屉触发常量：零 UI 代码改动、零像素变化")
        assertTrue(cards.contains("CardRevisionDrawer("), "抽屉必须真的挂在卡片上")
    }

    /** 守卫自验证：内存摘线后断言要变红。 */
    @Test
    fun removingTheGrantMakesTheGuardFail() {
        val broken = orchestrator.replace("HealSteering.grantOnce(", "")
        assertFalse(broken.contains("HealSteering.grantOnce("))
    }
}
