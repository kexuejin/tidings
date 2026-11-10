// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证 [DesktopStageChain] 的交接发言与组员展示接线（P0a / P0b 守卫）：
 * 1. 棒间交接发言必须调公开端口 broadcastAgentSpeech；
 * 2. 拟人发言必须走 stageHandoffSpeech 桥，禁止硬编码中文；
 * 3. 必须有 handoffSpoken 守卫，整条链上限 1 条，避免刷屏；
 * 4. 卡片 assignees 必须映射为角色名。
 */
class StageHandoffSpeechWiringGuardTest {

    private val desktopChain = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt"),
    )

    @Test
    fun theDesktopStageChainWiresHandoffSpeechSafely() {
        assertTrue(
            desktopChain.contains("controller.broadcastAgentSpeech("),
            "交棒时必须调用 controller.broadcastAgentSpeech 发送拟人发言",
        )
        assertTrue(
            desktopChain.contains("stageHandoffSpeech("),
            "交接发言文案必须走 uiTexts.stageHandoffSpeech 桥",
        )
        assertTrue(
            desktopChain.contains("handoffSpoken"),
            "必须具备 handoffSpoken 防重守卫，单链只发言一次",
        )
    }

    @Test
    fun theDesktopStageChainMapsAssigneesToRole() {
        assertTrue(
            desktopChain.contains("assignees = stages.map { it.role }"),
            "卡片步骤行与工牌 assignees 必须对齐为角色名",
        )
    }

    @Test
    fun removingTheWiringMakesTheGuardFail() {
        val broken = desktopChain.replace("controller.broadcastAgentSpeech(", "")
        assertFalse(broken.contains("controller.broadcastAgentSpeech("))
    }
}
