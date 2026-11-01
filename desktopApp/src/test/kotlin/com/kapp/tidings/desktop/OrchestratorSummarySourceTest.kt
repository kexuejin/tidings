// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工单卡摘要的**来源守卫**。
 *
 * 编排器把「CLI 这一轮说了什么」压成一句话放到卡面 banner 上。来源一旦取错，卡面就
 * 变成给用户读 JSON：`{"cardId":"diff-t-2-…","rev":2,"type":"diff-review",…}` —— 这是
 * 协议载荷，不是话。类型系统挡不住它（所有事件都是 `(String, String?, String?)`），
 * 只有把"哪些事件算话"钉成白名单才挡得住。
 */
class OrchestratorSummarySourceTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
    )

    @Test
    fun summaryOnlyComesFromWhatTheCliSaid() {
        assertTrue(
            orchestrator.contains("\"thought.delta\"") && orchestrator.contains("\"token.delta\""),
            "卡面摘要只能取 CLI 自己说的话（thought/token 增量）",
        )
        assertFalse(
            orchestrator.contains("eventType != \"finished\" && eventType != \"heartbeat\""),
            "摘要来源不能退回黑名单：漏掉 card 就把卡片 JSON 摊到用户脸上",
        )
    }

    @Test
    fun ticketBannerNeverClaimsTestsWereVerified() {
        assertFalse(
            orchestrator.contains("all tests verified"),
            "没有任何东西在核对测试；这句话是假的，卡面不该出现",
        )
    }
}
