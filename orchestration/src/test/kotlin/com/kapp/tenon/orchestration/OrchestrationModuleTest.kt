// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 编排层的模块内冒烟：这些件不经过桌面壳也能被构造、被断言。
 *
 * 真跑一条链的验收在 :cli 的假驱动测试里（那边证明「零桌面依赖」）；这里只证明
 * 从 :desktopApp 搬出来之后，公开面的形状与行为没变。
 */
class OrchestrationModuleTest {

    private fun stage(role: String, key: String) = ChainStage(
        role = role,
        agentKey = key,
        instruction = role + " 这一棒要做的事",
    )

    private fun plan(stages: List<ChainStage>): DelegationPlan = DelegationPlan(
        goal = "一起做点事",
        stages = stages,
        source = DelegationPlan.Source.EXPLICIT,
    )

    @Test
    fun gatePassesACrossVendorPlan() {
        val decision = StageChainGate.enforce(
            plan(listOf(stage("开发", "codex"), stage("审核", "dsh"))),
            availableKeys = setOf("codex", "dsh"),
        )
        assertTrue(decision is StageChainGate.Decision.Pass, "异厂商两棒应放行，实际：" + decision)
    }

    @Test
    fun gateRejectsASameVendorReviewer() {
        val decision = StageChainGate.enforce(
            plan(listOf(stage("开发", "codex"), stage("审核", "codex"))),
            availableKeys = setOf("codex"),
        )
        assertTrue(decision is StageChainGate.Decision.Reject, "同厂商审核必须拒绝，实际：" + decision)
        assertTrue(
            (decision as StageChainGate.Decision.Reject).reason.contains("同厂商"),
            "拒绝理由要点名同厂商（可操作），实际：" + decision.reason,
        )
    }

    @Test
    fun gateRejectsAgentsThatAreNotOnThisMachine() {
        val decision = StageChainGate.enforce(
            plan(listOf(stage("开发", "codex"), stage("审核", "dsh"))),
            availableKeys = setOf("codex"),
        )
        assertTrue(decision is StageChainGate.Decision.Reject)
        assertTrue((decision as StageChainGate.Decision.Reject).reason.contains("不在位"))
    }

    @Test
    fun healReportKeepsTheFirstCauseAndDeduplicates() {
        val banner = HealReport.bannerOf(
            rounds = listOf("cli exit=1", "no patch produced", "no patch produced"),
            maxRounds = 3,
        )
        assertTrue(banner != null && banner.contains("round 1"), "首因必须带上它的轮次：" + banner)
        assertEquals(
            1,
            Regex(Regex.escape("no patch produced")).findAll(banner!!).count(),
            "同一句原因只该出现一次（重复不增加信息）：" + banner,
        )
    }

    @Test
    fun lowRiskReviewerIsFailClosedOnUnknownDescriptions() {
        assertTrue(LowRiskReviewer.isLowRisk("L1", "update docs and add a unit test"))
        assertTrue(!LowRiskReviewer.isLowRisk("L1", "rm -rf build"))
        assertTrue(!LowRiskReviewer.isLowRisk(null, "mystery-tool --do-something"))
    }
}
