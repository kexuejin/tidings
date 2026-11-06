// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2 规格 3 用例 3.2/3.3 的**物理穿透**：审查结论真的驱动 StageChainRunner 的棒序，
 * 不是只有一张纯函数状态机。全注入，不碰进程与 git。
 */
class StageChainReviewTest {

    private fun stage(role: String, key: String) =
        ChainStage(role = role, agentKey = key, instruction = "做 $role")

    private val three = listOf(stage("开发", "codex"), stage("安全审查", "dsh"), stage("性能审查", "opencode"))

    /** verdicts: 每次「性能审查」跑到的轮次给出什么结论（按出现顺序消费）。 */
    private fun runner(
        verdicts: MutableList<ReviewVerdict>,
        ran: MutableList<String>,
        seen: MutableList<ChainProgress>,
        landed: MutableList<String> = mutableListOf(),
    ) = StageChainRunner(
        headSha = { "base0000" },
        runStage = { stage, _, _ ->
            ran += stage.role
            val verdict = when (stage.role) {
                "安全审查" -> ReviewVerdict.PASS
                "性能审查" -> if (verdicts.isEmpty()) ReviewVerdict.PASS else verdicts.removeAt(0)
                else -> null
            }
            StageRun(ok = true, sessionId = "s-" + stage.role + "-" + ran.size,
                summary = stage.role + " 完成", verdict = verdict)
        },
        land = { _, run, message -> landed += message; "sha-" + run.sessionId },
        report = { seen += it },
        review = ReviewPipeline(maxAttempts = 3),
        verdictOf = { it.verdict },
    )

    @Test
    fun allReviewersPassingEmitsTheMergeSignalOnTheFinalCard() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val landed = mutableListOf<String>()
        runner(mutableListOf(), ran, seen, landed).run("c-pass", "/tmp/repo", "鉴权重构", three)

        assertEquals(listOf("开发", "安全审查", "性能审查"), ran, "全 PASS 时严格线性推进一次")
        assertEquals(3, landed.size)
        assertEquals("review_ready", seen.last().state, "卡形状仍是既有的 review_ready（零协议字节变化）")
        assertTrue(seen.last().reviewPassed, "全棒 PASS 必须带出合流信号")
    }

    @Test
    fun aRejectAtTheLastReviewerReturnsToTheWorkerWithFeedback() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        runner(mutableListOf(ReviewVerdict.REJECT), ran, seen).run("c-reject", "/tmp/repo", "鉴权重构", three)

        assertEquals(2, ran.count { it == "开发" }, "REJECT 必须把 Worker 叫回来再干一轮")
        assertEquals(6, ran.size, "打回后审查棒也要重跑一轮，不能拿旧结论合流")
        assertTrue(seen.last().reviewPassed, "第 2 轮全 PASS 后正常给合流信号")
        assertTrue(seen.any { it.banner?.contains("attempt 2") == true }, "打回要上卡说明是第几轮")
    }

    @Test
    fun threeRejectsBlockTheChainInsteadOfLoopingForever() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        runner(mutableListOf(ReviewVerdict.REJECT, ReviewVerdict.REJECT, ReviewVerdict.REJECT), ran, seen)
            .run("c-block", "/tmp/repo", "鉴权重构", three)

        assertEquals(3, ran.count { it == "开发" }, "最多 3 轮，Worker 不许被无限召回")
        assertEquals("blocked", seen.last().state)
        assertFalse(seen.last().reviewPassed, "熔断卡绝不能带合流信号")
    }

    @Test
    fun isolationStagesAreRefusedUnderTheReviewPipeline() = runBlocking {
        val ran = mutableListOf<String>()
        val seen = mutableListOf<ChainProgress>()
        val mixed = listOf(
            stage("开发", "codex").copy(requiresIsolation = true),
            stage("安全审查", "dsh"),
        )
        runner(mutableListOf(), ran, seen).run("c-iso", "/tmp/repo", "鉴权重构", mixed)

        assertTrue(ran.isEmpty(), "线性多棒模式不许出现并行隔离组（规格明确拒绝 DAG）")
        assertEquals("blocked", seen.last().state, "拓扑违规要当场停机，不是悄悄顺序化")
    }
}
