// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐步决策的**物理穿透**：决策器说的下一步真的改变棒序，而不是只在一张纯函数表里成立。
 *
 * 两条底线：
 * 1. 既有规约（[DeterministicStepDecider]）与 `review = ReviewPipeline(...)` 行为等价 ——
 *    抽接口是重构，不是改版；
 * 2. 显式决策器可以插棒（Spawn）、可以回中间棒（Rework），执行器照做。
 */
class StepDeciderTest {

    private fun stage(role: String, key: String) =
        ChainStage(role = role, agentKey = key, instruction = "做 " + role)

    private val three = listOf(stage("开发", "codex"), stage("安全审查", "dsh"), stage("性能审查", "opencode"))

    private class ScriptedDecider(private val script: MutableList<StepDecision>) : StepDecider {
        val inputs = mutableListOf<StepInput>()

        override suspend fun decide(input: StepInput): StepDecision {
            inputs += input
            return if (script.isEmpty()) StepDecision.Next(input.stageIndex + 1) else script.removeAt(0)
        }
    }

    private fun runner(
        ran: MutableList<String>,
        seen: MutableList<ChainProgress>,
        verdicts: MutableList<ReviewVerdict> = mutableListOf(),
        decider: StepDecider? = null,
        onDecision: (StepRecord) -> Unit = {},
    ) = StageChainRunner(
        headSha = { "base0000" },
        runStage = { s, _, _ ->
            ran += s.role
            val verdict = when (s.role) {
                "安全审查" -> ReviewVerdict.PASS
                "性能审查" -> if (verdicts.isEmpty()) ReviewVerdict.PASS else verdicts.removeAt(0)
                "复核" -> ReviewVerdict.PASS
                else -> null
            }
            StageRun(ok = true, sessionId = "s-" + s.role + "-" + ran.size, summary = s.role + " 完成", verdict = verdict)
        },
        land = { _, run, _ -> "sha-" + run.sessionId },
        report = { seen += it },
        review = if (decider == null) ReviewPipeline(maxAttempts = 3) else null,
        verdictOf = { it.verdict },
        decider = decider,
        onDecision = onDecision,
    )

    @Test
    fun deterministicDeciderIsBehaviourallyEqualToTheReviewPipeline() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        runner(ran, seen, decider = DeterministicStepDecider(ReviewPipeline(maxAttempts = 3)))
            .run("c-det", "/tmp/repo", "鉴权重构", three)

        assertEquals(listOf("开发", "安全审查", "性能审查"), ran, "全 PASS 时严格线性推进一次")
        assertEquals("review_ready", seen.last().state)
        assertTrue(seen.last().reviewPassed, "末棒 PASS 必须带出合流信号")
    }

    @Test
    fun deterministicDeciderStillBouncesARejectBackToTheWorker() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        runner(ran, seen, verdicts = mutableListOf(ReviewVerdict.REJECT),
            decider = DeterministicStepDecider(ReviewPipeline(maxAttempts = 3)))
            .run("c-rej", "/tmp/repo", "鉴权重构", three)

        assertEquals(2, ran.count { it == "开发" }, "REJECT 必须把 Worker 叫回来再干一轮")
        assertEquals(6, ran.size, "打回后审查棒也要重跑一轮")
        assertTrue(seen.last().reviewPassed)
    }

    @Test
    fun theDeciderCanInsertANewStageAndItReallyRuns() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val two = listOf(stage("开发", "codex"), stage("性能审查", "opencode"))
        val decider = ScriptedDecider(
            mutableListOf(
                StepDecision.Next(1),
                // 审查通过，但决策者认为还需要一次独立复核 —— 现场加人。
                StepDecision.Spawn(afterIndex = 1, stage = stage("复核", "dsh"), reason = "方向性风险"),
            ),
        )
        runner(ran, seen, decider = decider).run("c-spawn", "/tmp/repo", "鉴权重构", two)

        assertEquals(listOf("开发", "性能审查", "复核"), ran, "插进来的棒必须在原位之后立刻执行")
        assertEquals("review_ready", seen.last().state)
        assertEquals("复核", seen.last().currentRole, "合流卡认最后一棒")
        assertNull(decider.inputs.first().budget.stagesLeft, "调用方没设上限时不编一个数字")
    }

    @Test
    fun reworkCanTargetAMiddleStageInsteadOfTheWorker() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val decider = ScriptedDecider(
            mutableListOf(
                StepDecision.Next(1),
                StepDecision.Next(2),
                // 审查认为「方案本身错了」：回第 1 棒重做方案，而不是让实现再改一遍。
                StepDecision.Rework(attempt = 2, feedback = "方案与目标不符", stageIndex = 1),
            ),
        )
        runner(ran, seen, decider = decider).run("c-mid", "/tmp/repo", "鉴权重构", three)

        assertEquals(listOf("开发", "安全审查", "性能审查", "安全审查", "性能审查"), ran,
            "Rework(stageIndex=1) 必须从第 2 棒起跑，不重跑第 1 棒")
    }

    @Test
    fun aDeciderThatThrowsStopsTheChainInsteadOfPretendingToPass() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val boom = StepDecider { error("model timeout") }
        runner(ran, seen, decider = boom).run("c-boom", "/tmp/repo", "鉴权重构", three)

        assertEquals("blocked", seen.last().state, "决策不可用必须停下，不许悄悄顺序推进")
        assertTrue(seen.last().banner?.contains("step decider failed") == true, "原因要如实写进卡面")
        assertFalse(seen.last().reviewPassed)
    }

    @Test
    fun spawningBeyondTheBudgetStopsTheChain() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val one = listOf(stage("开发", "codex"))
        val decider = ScriptedDecider(
            mutableListOf(
                StepDecision.Spawn(0, stage("复核", "dsh")),
                StepDecision.Spawn(1, stage("复核", "opencode")),
                StepDecision.Spawn(2, stage("复核", "dsh")),
            ),
        )
        runner(ran, seen, decider = decider).run("c-budget", "/tmp/repo", "鉴权重构", one)

        assertEquals("blocked", seen.last().state)
        assertTrue(seen.last().banner?.contains("dynamic stages exceed budget") == true)
    }

    @Test
    fun everyStepLeavesATraceWithItsDecider() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val traces = mutableListOf<StepRecord>()
        runner(ran, seen, decider = DeterministicStepDecider(ReviewPipeline(maxAttempts = 3))) { traces += it }
            .run("c-trace", "/tmp/repo", "鉴权重构", three)

        assertEquals(3, traces.size, "每一棒都要留一条决策记录")
        assertTrue(traces.all { it.decidedBy == StepRecord.BY_LEADER }, "显式决策器优先于规约")
        assertEquals(listOf("next:1", "next:2", "finish"), traces.map { it.action })
    }

    @Test
    fun theRuleTraceIsMarkedAsRuleNotAsALeaderDecision() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val traces = mutableListOf<StepRecord>()
        runner(ran, seen) { traces += it }.run("c-rule", "/tmp/repo", "鉴权重构", three)

        assertTrue(traces.isNotEmpty())
        assertTrue(traces.all { it.decidedBy == StepRecord.BY_RULE }, "规约裁决不得冒充主理人裁决")
    }
}
