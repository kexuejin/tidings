// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * L3 租约屏障的**落点守卫**：判定件自身的行为在 [HighRiskLeaseTest]（注入时钟）；
 * 这里钉的是「宿主动作入口真的接了闸」—— 摘掉闸（动作分发前不再查 cmd/租约）测试必须红。
 */
class HighRiskLeaseGateGuardTest {

    private val extension = GuardSourceScan.stripComments(
        GuardSourceScan.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
    )

    @Test
    fun actionEntryConsultsTheCommandWordlist() {
        assertTrue(
            extension.contains("HighRiskCommands.classify(cmd)"),
            "动作入口要对卡上的 cmd 做高危词表判定（HighRiskCommands）",
        )
    }

    @Test
    fun approvalArrivalGoesThroughTheLeaseVerdict() {
        assertTrue(
            extension.contains("LeaseBarrier.evaluate("),
            "审批到达要走租约三态判定（LeaseBarrier.evaluate），不许旁路",
        )
    }

    @Test
    fun expiredOrLeaselessHighRiskBlocksEveryLaterBranch() {
        assertTrue(
            extension.contains("highRiskBlocked") && extension.contains("flipStaleChainCard(cardId, agentId)"),
            "过期/无租约的高危动作：闸住并如实翻面，不许再进任何后续分支（fail-closed）",
        )
    }
}
