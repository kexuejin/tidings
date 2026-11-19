// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2 规格 5 的**接线守卫**：429 捕获必须挂在驱动事件的两条执行通路上，
 * 冷却探针必须挂在两条派单出口的代码门上。摘掉任何一环，单测照样绿 ——
 * 真机形状是「429 已经报了，下一次派单还是白等 30 秒」。
 */
class DriverCooldownWiringGuardTest {

    private val gateway = GuardSourceScan.stripComments(
        GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/ControlPlanePluginGateway.kt"),
    )
    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )

    private fun occurrencesOf(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    @Test
    fun bothDriverEventPathsFeedTheTracker() {
        val captures = occurrencesOf(gateway, "DriverCooldownTracker.Shared.captureIfRateLimited(")
        assertEquals(2, captures, "CLI 与插件两条 DriverEvent.Error 分支都要捕获限流（实际 $captures 处）")
        assertTrue(gateway.contains("is DriverEvent.Error -> {"), "捕获必须在 Error 事件分支内")
    }

    /**
     * 判据按**出口函数**逐个点验，不再数全仓出现次数：`runTeamDispatch` 里还有第三处
     * `enforce`（Planner 建议的硬门预检——建议被否时回落编译链，而不是出 blocked 卡），
     * 计数式断言会把「多了一个合法调用点」误报成「少接了一根线」。
     */
    @Test
    fun bothDelegationExitsPassTheCooldownProbeToTheGate() {
        for (entry in listOf(
            "private suspend fun delegateByPlan(",
            "private suspend fun delegateByPlanAwait(",
        )) {
            val block = GuardSourceScan.bracedBlockAfter(orchestrator, entry)
            assertTrue(
                block.contains("StageChainGate.enforce("),
                "$entry 必须过代码门",
            )
            assertTrue(
                block.contains("DriverCooldownTracker.Shared::remainingMs"),
                "$entry 的 enforce 必须传同一个共享冷却探针",
            )
        }
    }

    /** 守卫自验证：把接线摘掉，上面两条断言必须真的变红（否则守卫只是字符串摆设）。 */
    @Test
    fun removingEitherWireMakesTheGuardFail() {
        val brokenGateway = gateway.replace("DriverCooldownTracker.Shared.captureIfRateLimited(", "")
        val brokenOrchestrator = orchestrator.replace("DriverCooldownTracker.Shared::remainingMs", "")
        assertFalse(brokenGateway.contains("captureIfRateLimited("), "摘掉捕获后守卫应当变红")
        assertFalse(brokenOrchestrator.contains("Shared::remainingMs"), "摘掉探针后守卫应当变红")
        assertEquals(0, occurrencesOf(brokenGateway, "DriverCooldownTracker.Shared.captureIfRateLimited("))
        assertEquals(0, occurrencesOf(brokenOrchestrator, "DriverCooldownTracker.Shared::remainingMs"))
    }
}
