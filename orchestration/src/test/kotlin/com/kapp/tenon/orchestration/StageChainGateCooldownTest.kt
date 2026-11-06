// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 规格 5 用例 5.2：代码门**在拉起任何 CLI / 发任何网络帧之前**拒收冷却中的棒。
 * Gate 是纯函数：冷却探针之外不许碰厂商档案表，也不许有 IO 出口可挂。
 */
class StageChainGateCooldownTest {

    private val available = setOf("codex", "dsh", "opencode", "claude")

    private fun plan(vararg stages: Pair<String, String>) = DelegationPlan(
        goal = "重构鉴权模块",
        stages = stages.map { (role, key) -> ChainStage(role = role, agentKey = key, instruction = "做 $role") },
        source = DelegationPlan.Source.EXPLICIT,
    )

    @Test
    fun aCoolingWorkerIsRejectedBeforeAnyOtherCheck() {
        var vendorLookups = 0
        var networkProbes = 0
        val decision = StageChainGate.enforce(
            plan("开发" to "claude", "审核" to "dsh"),
            available,
            vendorOf = { vendorLookups++; "anthropic" },
            cooldownRemainingMs = { key -> networkProbes++; if (key == "claude") 120_000L else 0L },
        )
        assertTrue(decision is StageChainGate.Decision.Reject, "冷却棒必须拒收，实际 $decision")
        assertTrue(decision.reason.contains("claude"), "要点名是哪个 agent 冷却")
        assertTrue(decision.reason.contains("120s"), "要给出剩余秒数（可操作）")
        assertEquals(0, vendorLookups, "冷却检查必须排在最前：厂商档案都不该查")
        assertTrue(networkProbes > 0, "冷却探针必须真的被调用，不能是摆设")
    }

    @Test
    fun theRejectionNamesAnAvailableCrossVendorAlternative() {
        val decision = StageChainGate.enforce(
            plan("开发" to "claude", "审核" to "dsh"),
            available,
            cooldownRemainingMs = { key -> if (key == "claude") 120_000L else 0L },
        )
        assertTrue(decision is StageChainGate.Decision.Reject)
        assertTrue(decision.reason.contains("recommended:"), "要给降级推荐：${decision.reason}")
        val recommended = decision.reason.substringAfter("recommended: ")
        assertTrue(recommended.contains("codex") || recommended.contains("dsh"),
            "推荐里要有可用替代：${decision.reason}")
        assertTrue(!recommended.contains("claude"), "冷却中的自己不能被推荐：${decision.reason}")
    }

    @Test
    fun aCooledMiddleReviewerIsNotHiddenByTheLastStage() {
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", "安全审查" to "claude", "性能审查" to "dsh"),
            available,
            cooldownRemainingMs = { key -> if (key == "claude") 60_000L else 0L },
        )
        assertTrue(decision is StageChainGate.Decision.Reject, "中间棒冷却同样要拒收，实际 $decision")
        assertTrue(decision.reason.contains("claude"))
    }

    @Test
    fun withNoCooldownThePlanPassesUnchanged() {
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", "审核" to "dsh"),
            available,
        )
        assertTrue(decision is StageChainGate.Decision.Pass, "缺省探针 = 零冷却，行为零变更，实际 $decision")
        assertEquals(2, (decision as StageChainGate.Decision.Pass).stages.size)
    }

    @Test
    fun everyAlternativeCoolingMeansNoRecommendationRatherThanASilentSwap() {
        val decision = StageChainGate.enforce(
            plan("开发" to "claude", "审核" to "dsh"),
            available,
            cooldownRemainingMs = { 120_000L },
        )
        assertTrue(decision is StageChainGate.Decision.Reject)
        assertTrue(decision.reason.contains("wait"), "全员冷却时明确让用户等，不静默换人，实际 ${decision.reason}")
    }
}
