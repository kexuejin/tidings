// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.PlannerLlm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * LLM planner（Step 4）的判据：**planner 建议，代码门裁决；失败必降级**。
 * HTTP 层注入，这里不碰真网。
 */
class PlannerLlmTest {

    private val available = setOf("codex", "dsh", "opencode")

    private fun chatBody(content: String): String {
        val contentJson = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"choices":[{"message":{"role":"assistant","content":"$contentJson"}}]}"""
    }

    // ---------------------------------------------------------------- 正常拆解

    @Test
    fun aPlanFromTheModelCompilesIntoWorkerFirstReviewerLast() {
        val plan = PlannerLlm.refine(
            goal = "给导出功能加 CSV 与 PDF 两种格式",
            availableKeys = available,
            apiKey = "test-key",
            http = { _, _, _, _ ->
                chatBody(
                    """{"stages":[
                        {"role":"开发","agent":"codex","instruction":"实现 CSV 导出"},
                        {"role":"开发","agent":"opencode","instruction":"实现 PDF 导出"},
                        {"role":"审核","agent":"dsh","instruction":"复核两份导出实现"}]}""",
                )
            },
        )
        assertEquals(listOf("codex", "opencode", "dsh"), plan?.map { it.agentKey })
        assertEquals("开发", plan?.get(0)?.role)
        assertEquals("审核", plan?.last()?.role)
    }

    @Test
    fun codeFencedJsonIsStripped() {
        val plan = PlannerLlm.refine(
            goal = "目标",
            availableKeys = available,
            apiKey = "test-key",
            http = { _, _, _, _ ->
                chatBody("```json\n{\"stages\":[{\"role\":\"开发\",\"agent\":\"codex\",\"instruction\":\"干\"},{\"role\":\"审核\",\"agent\":\"dsh\",\"instruction\":\"审\"}]}\n```")
            },
        )
        assertEquals(listOf("codex", "dsh"), plan?.map { it.agentKey })
    }

    // ---------------------------------------------------------------- 预校验

    @Test
    fun unknownOrUnavailableAgentStagesAreDroppedAndAnEmptyPlanIsRejected() {
        assertNull(
            PlannerLlm.refine(
                goal = "目标",
                availableKeys = available,
                apiKey = "test-key",
                http = { _, _, _, _ ->
                    chatBody("""{"stages":[{"role":"开发","agent":"chatgpt","instruction":"干"},{"role":"审核","agent":"claude","instruction":"审"}]}""")
                },
            ),
            "未知键全丢之后没有棒 ⇒ 不成立，回落启发式",
        )
    }

    @Test
    fun aReviewerOnlyPlanIsRejected() {
        assertNull(
            PlannerLlm.parsePlan(
                """{"stages":[{"role":"审核","agent":"dsh","instruction":"审"}]}""",
                available,
            ),
            "只有审核棒没有干活棒 ⇒ null",
        )
    }

    @Test
    fun reviewerStagesAlwaysMoveLastEvenIfTheModelPutThemFirst() {
        val stages = assertNotNull(
            PlannerLlm.parsePlan(
                """{"stages":[{"role":"审核","agent":"dsh","instruction":"审"},{"role":"开发","agent":"codex","instruction":"干"}]}""",
                available,
            ),
        )
        assertEquals(listOf("codex", "dsh"), stages.map { it.agentKey }, "A 干 B 审核的结构由代码保证")
    }

    // ---------------------------------------------------------------- 降级

    @Test
    fun noKeyNoRefine() {
        assertNull(
            PlannerLlm.refine("目标", available, apiKey = null, http = { _, _, _, _ -> fail() }),
            "没有 key ⇒ null（回落启发式），绝不发无凭证请求",
        )
    }

    @Test
    fun httpFailureOrGarbageDegradesToNull() {
        assertNull(
            PlannerLlm.refine("目标", available, apiKey = "k", http = { _, _, _, _ -> null }),
            "HTTP 失败 ⇒ null",
        )
        assertNull(
            PlannerLlm.refine("目标", available, apiKey = "k", http = { _, _, _, _ -> "not json" }),
            "响应不是 JSON ⇒ null",
        )
        assertNull(
            PlannerLlm.refine("目标", available, apiKey = "k", http = { _, _, _, _ -> chatBody("随便说两句") }),
            "content 不是计划 ⇒ null",
        )
    }

    @Test
    fun parsesDetailedPlanWithTargetModuleAndContext() {
        val json = """{
            "target":"帧预算加固",
            "module":":core:transport",
            "context":"严格对齐 TCP 帧预算与统一三处截断边界",
            "stages":[
                {"role":"开发","agent":"codex","instruction":"对齐预算"},
                {"role":"审核","agent":"dsh","instruction":"审计键白名单"}
            ]
        }"""
        val detailed = PlannerLlm.parsePlanDetailed(json, available)
        kotlin.test.assertNotNull(detailed)
        assertEquals("帧预算加固", detailed.target)
        assertEquals(":core:transport", detailed.module)
        assertEquals("严格对齐 TCP 帧预算与统一三处截断边界", detailed.context)
        assertEquals(listOf("codex", "dsh"), detailed.stages.map { it.agentKey })
    }

    // ---------------------------------------------------------------- 辅助

    private fun assertNotNull(value: List<com.kapp.tenon.agent.ChainStage>?): List<com.kapp.tenon.agent.ChainStage> {
        check(value != null) { "expected non-null plan" }
        return value
    }

    private fun fail(): Nothing = throw AssertionError("HTTP 不该被调用")
}
