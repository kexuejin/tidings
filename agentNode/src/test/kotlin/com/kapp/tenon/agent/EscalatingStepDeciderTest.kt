// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 升级策略的判据必须钉死：**什么时候打扰主理人**是产品语义，不是实现细节。
 * 判错的两种代价都很大 —— 太勤会让每条链慢成幻灯片，太懒则等于专家从没到场。
 */
class EscalatingStepDeciderTest {

    private fun stage(role: String) = ChainStage(role = role, agentKey = "k-" + role, instruction = "做 " + role)

    private fun input(
        verdict: ReviewVerdict? = ReviewVerdict.PASS,
        history: List<StepRecord> = emptyList(),
        attempt: Int = 1,
        stageIndex: Int = 1,
    ) = StepInput(
        chainId = "c1",
        goal = "鉴权重构",
        stageIndex = stageIndex,
        stages = listOf(stage("开发"), stage("审查")),
        attempt = attempt,
        verdict = verdict,
        feedback = "审查意见",
        history = history,
        budget = StepBudget(attempt = attempt, stageCount = 2),
    )

    private class Fixed(private val decision: StepDecision, private val label: String = "fixed") : StepDecider {
        var calls = 0
        override suspend fun decide(input: StepInput): StepDecision {
            calls++
            return decision
        }
    }

    private fun rules(decision: StepDecision) = Fixed(decision, "rule")

    @Test
    fun routineProgressNeverBothersTheLeader() = runBlocking {
        val leader = Fixed(StepDecision.Finish)
        val decider = EscalatingStepDecider(rules(StepDecision.Next(1)), leader)
        assertEquals(StepDecision.Next(1), decider.decide(input()))
        assertEquals(0, leader.calls, "常规推进调主理人 = 纯延迟与费用")
    }

    @Test
    fun finishingDoesNotBotherTheLeaderEither() = runBlocking {
        val leader = Fixed(StepDecision.Stop("should not be asked"))
        val decider = EscalatingStepDecider(rules(StepDecision.Finish), leader)
        assertEquals(StepDecision.Finish, decider.decide(input()))
        assertEquals(0, leader.calls, "收工是确定性结论，不需要专家点头")
    }

    @Test
    fun aStopIsEscalatedSoTheExpertCanRescueTheChain() = runBlocking {
        val spawn = StepDecision.Spawn(1, stage("架构复核"), reason = "方向存疑")
        val leader = Fixed(spawn)
        val decider = EscalatingStepDecider(rules(StepDecision.Stop("review verdict missing")), leader)
        assertEquals(spawn, decider.decide(input(verdict = null)))
        assertEquals(1, leader.calls, "规约判不了时必须让主理人接")
    }

    @Test
    fun theFirstReworkStaysWithTheRules() = runBlocking {
        val leader = Fixed(StepDecision.Finish)
        val decider = EscalatingStepDecider(
            rules(StepDecision.Rework(attempt = 2, feedback = "返工")), leader,
        )
        assertTrue(decider.decide(input()) is StepDecision.Rework)
        assertEquals(0, leader.calls, "第一次打回是常规返工，不该升级")
    }

    @Test
    fun aSecondReworkOnTheSameChainEscalates() = runBlocking {
        val leader = Fixed(StepDecision.Rework(attempt = 3, feedback = "换人重做", stageIndex = 1))
        val history = listOf(
            StepRecord(0, "开发", null, "next:1", StepRecord.BY_RULE),
            StepRecord(1, "审查", ReviewVerdict.REJECT, "rework:2", StepRecord.BY_RULE),
        )
        val decider = EscalatingStepDecider(
            rules(StepDecision.Rework(attempt = 2, feedback = "返工")), leader,
        )
        assertTrue(decider.decide(input(history = history)) is StepDecision.Rework)
        assertEquals(1, leader.calls, "原地打转必须升级，否则第三轮还是同一个错")
    }

    @Test
    fun aLeaderThatThrowsFallsBackToTheRuleInsteadOfStoppingTheChain() = runBlocking {
        val exploding = object : StepDecider {
            override suspend fun decide(input: StepInput): StepDecision = error("model timeout")
        }
        val baseline = StepDecision.Stop("review verdict missing")
        val decider = EscalatingStepDecider(rules(baseline), exploding)
        assertEquals(baseline, decider.decide(input(verdict = null)), "请不动专家不能把链弄停")
    }

    @Test
    fun theSafetyValveStillCapsTheExpert() = runBlocking {
        val runaway = Fixed(StepDecision.Rework(attempt = 99, feedback = "永远再来一轮"))
        val baseline = StepDecision.Stop("budget exhausted")
        val decider = EscalatingStepDecider(rules(baseline), runaway, maxAttempts = 3)
        assertEquals(baseline, decider.decide(input(attempt = 3)), "主理人也不许突破返工上限")
    }

    @Test
    fun theExpertCannotTurnARejectIntoAFinish() = runBlocking {
        val leader = Fixed(StepDecision.Finish)
        val baseline = StepDecision.Rework(attempt = 2, feedback = "返工")
        val history = listOf(
            StepRecord(1, "审查", ReviewVerdict.REJECT, "rework:2", StepRecord.BY_RULE),
        )
        val decider = EscalatingStepDecider(rules(baseline), leader)
        assertEquals(
            baseline,
            decider.decide(input(verdict = ReviewVerdict.REJECT, history = history)),
            "审查否决后主理人判收工 = 把「没通过」说成「通过」，必须拒绝",
        )
    }

    @Test
    fun aWorkerRungWithNoVerdictMayStillBeClosedByTheExpert() = runBlocking {
        val leader = Fixed(StepDecision.Finish)
        val baseline = StepDecision.Stop("no reviewer on this chain")
        val decider = EscalatingStepDecider(rules(baseline), leader)
        assertEquals(
            StepDecision.Finish,
            decider.decide(input(verdict = null, stageIndex = 0)),
            "首棒（干活棒）本来就没有审查结论，收工是合法决定",
        )
    }

    @Test
    fun noLeaderMeansTheRulesAreTheWholeStory() = runBlocking {
        val decider = EscalatingStepDecider(rules(StepDecision.Stop("no verdict")), leader = null)
        assertFalse(decider.decide(input(verdict = null)) is StepDecision.Next)
    }
}
