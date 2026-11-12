// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * leader 动态编排的门校验与回落路径（TeamPlanRefiner）。
 *
 * 反官僚边界钉在这里：LLM 拿不到「增减换人 / worker 挪位 / 越权改驱动」的授权 ——
 * 门校验必须能区分对错，任何越界形状都回落编译固定链。
 */
class TeamPlanRefinerTest {

    private fun plan(
        worker: String = "dsh-cli",
        reviewers: List<String> = listOf("codex-cli", "claude-cli"),
        goal: String = "给登录页加记住我",
    ): DelegationPlan = DelegationPlan(
        goal = goal,
        stages = buildList {
            add(ChainStage(role = "编码", agentKey = worker, instruction = goal, model = "m1"))
            reviewers.forEachIndexed { i, key ->
                add(ChainStage(role = if (i == 0) "测试验收" else "架构审查", agentKey = key, instruction = "审查"))
            }
        },
        source = DelegationPlan.Source.AUTO,
        fromTemplate = true,
    )

    private fun refined(vararg rows: Pair<String, String>): List<TeamPlanRefiner.RefinedStage> =
        rows.map { TeamPlanRefiner.RefinedStage(it.first, it.second) }

    /** 合法重排：reviewer 换序 + 指令定制 —— worker 居首，role/model 原棒携带。 */
    @Test
    fun validRerenderKeepsShapeAndCarriesRoleAndModel() {
        val p = plan()
        val out = TeamPlanRefiner.apply(
            p,
            refined(
                "dsh-cli" to "先调研登录页现状，再实现记住我，最后跑测试并报告改动。",
                "claude-cli" to "针对本次改动逐条核对架构一致性，末尾给 VERDICT 行。",
                "codex-cli" to "对本目标跑测试验收，逐项给结论，末尾给 VERDICT 行。",
            ),
        )
        assertTrue(out != null, "合法重排必须过门")
        assertEquals(listOf("dsh-cli", "claude-cli", "codex-cli"), out.stages.map { it.agentKey })
        assertEquals(p.stages[0].model, out.stages[0].model, "model 是执行档事实，不许丢")
        assertEquals("编码", out.stages[0].role, "role 是群声明事实，不许改")
        assertEquals("编码", out.stages[0].role)
        assertTrue(out.stages.all { it.instruction.isNotBlank() })
        assertTrue(out.fromTemplate, "fromTemplate 标记必须原样保留")
    }

    /** worker 不居首：编译期硬约束被 LLM 越界 —— 拒。 */
    @Test
    fun workerMustStayFirst() {
        val p = plan()
        assertNull(
            TeamPlanRefiner.apply(
                p,
                refined(
                    "codex-cli" to "审查",
                    "dsh-cli" to "实现",
                    "claude-cli" to "审查",
                ),
            ),
            "worker 居首是产品不变量，LLM 无权重排它",
        )
    }

    /** 增/减/换人：多重集不等 —— 拒。 */
    @Test
    fun memberSetIsAPermutationNotASuggestion() {
        val p = plan()
        assertNull(
            TeamPlanRefiner.apply(
                p,
                refined("dsh-cli" to "实现", "codex-cli" to "审查", "gemini-cli" to "审查"),
            ),
            "换人 = 越权改编制",
        )
        assertNull(
            TeamPlanRefiner.apply(p, refined("dsh-cli" to "实现", "codex-cli" to "审查")),
            "减员 = 越权改编制",
        )
    }

    /** 指令空白：等于让空话进链 —— 拒。 */
    @Test
    fun blankInstructionIsRejected() {
        val p = plan()
        assertNull(
            TeamPlanRefiner.apply(
                p,
                refined("dsh-cli" to "  ", "codex-cli" to "审查", "claude-cli" to "审查"),
            ),
        )
    }

    /** 行数不一致：形状已经坏了 —— 拒。 */
    @Test
    fun sizeMismatchIsRejected() {
        val p = plan()
        assertNull(TeamPlanRefiner.apply(p, refined("dsh-cli" to "实现")))
    }

    /** refine() 全回落路径：无 key / LLM 垃圾输出 / 单棒计划。 */
    @Test
    fun refineFallsBackToNullOnEveryUnavailablePath() {
        val p = plan()
        // 无 key
        assertNull(TeamPlanRefiner.refine(p, apiKey = null))
        assertNull(TeamPlanRefiner.refine(p, apiKey = "  "))
        // 单棒：没得重排，连 LLM 都不该调
        val single = DelegationPlan(
            goal = "g",
            stages = listOf(ChainStage(role = "编码", agentKey = "dsh-cli", instruction = "g")),
            source = DelegationPlan.Source.AUTO,
            fromTemplate = true,
        )
        var httpCalled = false
        assertNull(
            TeamPlanRefiner.refine(
                single,
                apiKey = "k",
                http = { _, _, _, _ -> httpCalled = true; "not json" },
            ),
            "单棒计划不该付出一次 LLM 调用",
        )
        assertTrue(!httpCalled, "单棒在门之前就该返回 null")
        // LLM 返回垃圾 JSON
        assertNull(TeamPlanRefiner.refine(p, apiKey = "k", http = { _, _, _, _ -> "I am not json" }))
        // LLM 越界形状（换人）→ 门拒
        assertNull(
            TeamPlanRefiner.refine(
                p,
                apiKey = "k",
                http = { _, _, _, _ ->
                    """{"stages":[{"agentKey":"dsh-cli","instruction":"实现"},
                        |{"agentKey":"gemini-cli","instruction":"审查"},
                        |{"agentKey":"codex-cli","instruction":"审查"}]}""".trimMargin()
                },
            ),
        )
    }

    /** refine() 成功路径：fake http 返回合法重排 → 新计划带着定制指令回来。 */
    @Test
    fun refineAppliesValidLlmOutput() {
        val p = plan()
        val out = TeamPlanRefiner.refine(
            p,
            apiKey = "k",
            http = { _, _, body, _ ->
                assertTrue("dsh-cli" in body, "system prompt 必须带成员名册")
                // fake http 返回的是 HTTP 响应体（OpenAI 信封），不是裸 content。
                val content = """
                    {"stages":[{"agentKey":"dsh-cli","instruction":"调研后实现"},
                    {"agentKey":"claude-cli","instruction":"架构核对"},
                    {"agentKey":"codex-cli","instruction":"测试验收"}]}
                    """.trimIndent()
                // content 是 JSON **字符串**（助手消息文本）：引号要转义、换行压空格。
                val escaped = content.replace("\"", "\\\"").replace("\n", " ")
                """{"choices":[{"message":{"content":"$escaped"}}]}"""
            },
        )
        assertTrue(out != null, "合法输出必须应用")
        assertEquals(listOf("dsh-cli", "claude-cli", "codex-cli"), out.stages.map { it.agentKey })
        assertEquals("调研后实现", out.stages[0].instruction)
    }
}
