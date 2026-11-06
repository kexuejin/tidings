// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 委派代码门的判据（design/spec/orchestration-model-fit-2026-11-03 §二）：
 * **planner 可以推荐，代码门裁决** —— 在位、异厂商审核、降级优于吞单。
 *
 * vendor 规则用注入映射测试：静态档案表里没有两个同厂商的键，
 * 注入才能把「同厂商拒绝」这条规则本身钉住。
 */
class StageChainGateTest {

    private val available = setOf("codex", "dsh", "opencode")

    private fun plan(
        vararg stages: Pair<String, String>,
        source: DelegationPlan.Source = DelegationPlan.Source.AUTO,
    ) = DelegationPlan(
        goal = "修复登录",
        stages = stages.map { (role, key) -> ChainStage(role = role, agentKey = key, instruction = "做 $role") },
        source = source,
    )

    // ---------------------------------------------------------------- 放行

    @Test
    fun aCrossVendorPlanPasses() {
        val decision = StageChainGate.enforce(plan("开发" to "codex", "审核" to "dsh"), available)
        val stages = (decision as? StageChainGate.Decision.Pass)?.stages
        assertEquals(2, stages?.size, "异厂商计划放行，实际 $decision")
    }

    // ---------------------------------------------------------------- 在位

    @Test
    fun anUnavailableAgentIsRejectedWithWhatIsAvailable() {
        val decision = StageChainGate.enforce(plan("开发" to "codex", "审核" to "claude"), available)
        assertTrue(decision is StageChainGate.Decision.Reject, "不在位的审核棒必须拒绝，实际 $decision")
        assertTrue(decision.reason.contains("claude"), "拒绝文案要点名缺谁")
        assertTrue(decision.reason.contains("codex"), "拒绝文案要给出当前可用名单")
    }

    @Test
    fun cliSuffixedKeysAreNormalizedBeforeTheAvailabilityCheck() {
        val decision = StageChainGate.enforce(plan("开发" to "codex-cli", "审核" to "dsh-cli"), available)
        assertTrue(decision is StageChainGate.Decision.Pass, "codex-cli/dsh-cli 要归一成档案键，实际 $decision")
    }

    // ---------------------------------------------------------------- 异厂商审核

    @Test
    fun sameVendorReviewIsRejectedEvenIfTheStaticTableDoesNotHaveSuchAPair() {
        val sameVendor: (String) -> String? = { key -> when (key) {
            "codex" -> "vendor-a"
            "dsh" -> "vendor-a"
            else -> "vendor-b"
        } }
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", "审核" to "dsh"),
            available,
            vendorOf = sameVendor,
        )
        assertTrue(decision is StageChainGate.Decision.Reject, "同厂商审核必须拒绝，实际 $decision")
        assertTrue(decision.reason.contains("同厂商"), "拒绝文案要说明为什么：审核不独立")
        assertTrue(decision.reason.contains("OpenCode"), "拒绝文案要给异厂商替代项（显示名）")
    }

    @Test
    fun unknownVendorFailsClosedInsteadOfPassingSilently() {
        // 显式命名：新增的冷却探针排在后面，尾随 lambda 会被解析成它（Long）而不是 vendorOf。
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", "审核" to "dsh"),
            available,
            vendorOf = { null },
        )
        assertTrue(decision is StageChainGate.Decision.Reject, "厂商档案缺失要 fail-closed，实际 $decision")
    }

    // ---------------------------------------------------------------- 降级

    @Test
    fun autoPlanWithASingleStageDowngradesToTheWorkerInsteadOfSwallowingTheTicket() {
        val decision = StageChainGate.enforce(plan("开发" to "codex"), setOf("codex"))
        val downgrade = decision as? StageChainGate.Decision.Downgrade
        assertEquals("codex", downgrade?.stage?.agentKey, "降级保留干活棒，实际 $decision")
        assertTrue(downgrade?.reason?.contains("厂商") == true, "降级理由要可读")
    }

    @Test
    fun anExplicitSingleStagePlanIsRejectedNotDowngraded() {
        val decision = StageChainGate.enforce(
            plan("开发" to "codex", source = DelegationPlan.Source.EXPLICIT),
            setOf("codex"),
        )
        assertTrue(decision is StageChainGate.Decision.Reject, "显式计划一棒是畸形输入，拒绝而不是猜")
    }
}
