// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P2 规格 3 用例 3.1：线性多棒链里**每一根**审查棒都要与 Worker 异厂商。
 * 旧门只查 first()/last()：把同厂商棒塞到中间就能绕过 —— 这是真缺陷，不是新规则。
 */
class StageChainMultiReviewerGateTest {

    private val available = setOf("codex", "claude", "dsh", "opencode")

    /** 让 codex 与 claude 同厂商、dsh 异厂商，才能构造出静态档案表里没有的违规形状。 */
    private val vendorOf: (String) -> String? = { key -> when (key) {
        "codex", "claude" -> "vendor-a"
        "dsh", "opencode" -> "vendor-b"
        else -> null
    } }

    private fun plan(vararg stages: Pair<String, String>) = DelegationPlan(
        goal = "重构鉴权模块",
        stages = stages.map { (role, key) -> ChainStage(role = role, agentKey = key, instruction = "做 $role") },
        source = DelegationPlan.Source.EXPLICIT,
    )

    @Test
    fun aSameVendorMiddleReviewerIsRejected() {
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", "安全审查" to "claude", "性能审查" to "dsh"),
            available,
            vendorOf = vendorOf,
        )
        assertTrue(decision is StageChainGate.Decision.Reject,
            "中间棒同厂商必须拒绝（旧门只看首尾会漏判），实际 $decision")
        assertTrue(decision.reason.contains("claude"), "拒绝文案要点名违规的那一棒：${decision.reason}")
    }

    @Test
    fun allCrossVendorReviewersPassInOrder() {
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", "安全审查" to "dsh", "性能审查" to "opencode"),
            available,
            vendorOf = vendorOf,
        )
        assertTrue(decision is StageChainGate.Decision.Pass, "三棒全异厂商应放行，实际 $decision")
        assertEquals(3, (decision as StageChainGate.Decision.Pass).stages.size, "顺序与棒数一字不改")
        assertEquals("性能审查", decision.stages.last().role)
    }

    @Test
    fun aReviewerWithoutVendorProfileFailsClosed() {
        val custom: (String) -> String? = { key -> if (key == "unknown") null else vendorOf(key) }
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", "安全审查" to "dsh", "性能审查" to "unknown"),
            available + "unknown",
            vendorOf = custom,
        )
        assertTrue(decision is StageChainGate.Decision.Reject, "任一棒厂商档案缺失都 fail-closed，实际 $decision")
    }

    @Test
    fun theTwoStageLegacyShapeStillPasses() {
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", "审核" to "dsh"),
            available,
            vendorOf = vendorOf,
        )
        assertTrue(decision is StageChainGate.Decision.Pass, "两棒旧形状行为零变更，实际 $decision")
    }
}
