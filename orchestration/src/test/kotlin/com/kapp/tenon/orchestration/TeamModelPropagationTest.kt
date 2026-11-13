// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.TeamMemberInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 执行档的 `models` → 责任链/单棒的**模型透传**（不变量 5「模型」这一维）。
 *
 * 为什么要单独钉这一条：`models` 曾经是「存了但不生效」—— 执行档里选得动、派单时
 * 谁也不读它，症状是「配了 claude-opus 跑的还是默认模型」，而编译、派单、卡面全绿。
 * 现在唯一消费路径是 `TeamPlanCompiler` 把 `models.first()` 带进
 * [com.kapp.tenon.agent.ChainStage.model] / [TeamPlanCompiler.Compiled.Single.model]，
 * 再由 `executeCliTask(model=)` 落到驱动 argv。这里钉的是**编译层那一跳**。
 *
 * 反向探针：把 `compile()` 里任何一处的 `model = …models.firstOrNull()` 删掉，
 * 对应断言必须变红（尤其 chain 那条 —— 只钉 Single 会漏掉多棒路径）。
 */
class TeamModelPropagationTest {

    private val seatMembers = listOf(
        TeamMemberInfo("m-coder", "coder", "编码"),
        TeamMemberInfo("m-reviewer", "reviewer", "审查"),
    )

    private fun profile(vararg members: TeamExecutionMemberSpec) = TeamExecutionProfile(
        dirId = "ws-1", leaderAgentId = "dsh-lead",
        updatedAt = 0, members = members.toList(),
    )

    private val coderInfo = seatMembers[0]
    private val reviewerInfo = seatMembers[1]

    @Test
    fun chainStagesCarryEachMembersOwnModel() {
        val coder = TeamExecutionMemberSpec(
            "m-coder", TeamExecutionKinds.WORKER, "codex", models = listOf("gpt-5-codex"),
        )
        val reviewer = TeamExecutionMemberSpec(
            "m-reviewer", TeamExecutionKinds.REVIEWER, "claude", models = listOf("claude-opus-4-1"),
        )
        val compiled = TeamPlanCompiler.compile(
            profile(coder, reviewer), seatMembers, coderInfo,
            goal = "fix the login timeout", availableDrivers = setOf("codex", "claude"),
        )
        val stages = assertIs<TeamPlanCompiler.Compiled.Plan>(compiled).plan.stages
        assertEquals(2, stages.size, "前置：worker + 1 reviewer")
        assertEquals("gpt-5-codex", stages[0].model, "worker 棒带自己的模型")
        assertEquals("claude-opus-4-1", stages[1].model, "reviewer 棒带自己的模型，不与 worker 混用")
    }

    @Test
    fun singleWorkerWithoutReviewersCarriesItsModel() {
        val coder = TeamExecutionMemberSpec(
            "m-coder", TeamExecutionKinds.WORKER, "codex", models = listOf("gpt-5-codex"),
        )
        val compiled = TeamPlanCompiler.compile(
            profile(coder), seatMembers, coderInfo,
            goal = "fix it", availableDrivers = setOf("codex"),
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(compiled)
        assertEquals("gpt-5-codex", single.model, "单棒也要带模型 —— 它才是 @组员 的常见形态")
    }

    @Test
    fun reviewerMentionCarriesTheReviewersModelNotTheWorkers() {
        val coder = TeamExecutionMemberSpec(
            "m-coder", TeamExecutionKinds.WORKER, "codex", models = listOf("gpt-5-codex"),
        )
        val reviewer = TeamExecutionMemberSpec(
            "m-reviewer", TeamExecutionKinds.REVIEWER, "claude", models = listOf("claude-sonnet-4-5"),
        )
        val compiled = TeamPlanCompiler.compile(
            profile(coder, reviewer), seatMembers, reviewerInfo,
            goal = "audit this", availableDrivers = setOf("codex", "claude"),
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(compiled)
        assertEquals("claude-sonnet-4-5", single.model, "直接呼 reviewer ⇒ 用 reviewer 的模型")
    }

    @Test
    fun emptyModelsStayNullSoTheDriverKeepsItsDefault() {
        val coder = TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex")
        val compiled = TeamPlanCompiler.compile(
            profile(coder), seatMembers, coderInfo,
            goal = "fix it", availableDrivers = setOf("codex"),
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(compiled)
        assertNull(single.model, "没配模型 = null（跟随驱动默认），不是空串")
    }
}
