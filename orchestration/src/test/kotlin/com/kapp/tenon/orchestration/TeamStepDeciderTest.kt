// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.LeaderBrief
import com.kapp.tenon.agent.LeaderMember
import com.kapp.tenon.agent.ReviewVerdict
import com.kapp.tenon.agent.StepBudget
import com.kapp.tenon.agent.StepDecision
import com.kapp.tenon.agent.StepInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 主理人裁决的**翻译层**：模型只说「派给谁、做什么」，驱动与模型必须从编制回查。
 *
 * 这条判据比「能不能解析 JSON」重要得多：一旦允许模型自带 `driverKey`，它会编出
 * 本机不存在的键，派单在链启动那一刻才失败，而卡面已经写着「已派给 X」。
 */
class TeamStepDeciderTest {

    private fun stage(role: String, key: String, assignee: String = role) =
        ChainStage(role = role, agentKey = key, instruction = "做 " + role, assignee = assignee)

    private val brief = LeaderBrief(
        leaderAgentId = "m-lead",
        leaderName = "领队",
        leaderPrompt = "先想清楚再派人",
        teamName = "鉴权小队",
        goal = "重构鉴权",
        members = listOf(
            LeaderMember("m-coder", "coder", "开发", isWorker = true, driverKey = "codex", model = "gpt-5"),
            LeaderMember("m-reviewer", "reviewer", "审查", isWorker = false, driverKey = "dsh"),
            LeaderMember("m-arch", "arch", "架构", isWorker = false, driverKey = "claude"),
        ),
    )

    private fun input(
        stageIndex: Int = 1,
        attempt: Int = 1,
        verdict: ReviewVerdict? = ReviewVerdict.REJECT,
    ) = StepInput(
        chainId = "c1",
        goal = "重构鉴权",
        stageIndex = stageIndex,
        stages = listOf(stage("开发", "codex", "coder"), stage("审查", "dsh", "reviewer")),
        attempt = attempt,
        verdict = verdict,
        feedback = "会话没有过期校验",
        history = emptyList(),
        budget = StepBudget(attempt = attempt, stageCount = 2),
    )

    @Test
    fun spawningUsesTheRosterForDriverAndModelNotTheModelAnswer() {
        val decision = StepSuggestMapper.toDecision(
            StepSuggest(action = "spawn", agentId = "m-arch", instruction = "补一份鉴权时序方案", reason = "方向未定"),
            input(), brief,
        )
        val spawn = decision as StepDecision.Spawn
        assertEquals("claude", spawn.stage.agentKey, "驱动必须从编制回查，模型说了不算")
        assertEquals("arch", spawn.stage.assignee, "卡面承办人是身份名，不是 agentId")
        assertEquals(1, spawn.afterIndex, "插在当前棒之后")
        assertEquals("方向未定", spawn.reason)
    }

    @Test
    fun anUnknownAgentIsRefusedInsteadOfInventingOne() {
        assertNull(
            StepSuggestMapper.toDecision(
                StepSuggest(action = "spawn", agentId = "m-nobody", instruction = "做点什么"), input(), brief,
            ),
            "不认识的人必须整份作废，不许猜一个近似的",
        )
    }

    @Test
    fun reworkWithoutFeedbackIsRefused() {
        assertNull(
            StepSuggestMapper.toDecision(StepSuggest(action = "rework", agentId = "m-coder"), input(), brief),
            "返工必须说清要改什么，否则 worker 只会原样再来一遍",
        )
    }

    @Test
    fun reworkCanPointAtASpecificStage() {
        val decision = StepSuggestMapper.toDecision(
            StepSuggest(action = "rework", agentId = "m-reviewer", feedback = "审查标准太松"),
            input(), brief,
        )
        val rework = decision as StepDecision.Rework
        assertEquals(1, rework.stageIndex, "点名审查者 ⇒ 回到审查那一棒")
        assertEquals(2, rework.attempt, "attempt 必须 +1，否则熔断永远不触发")
    }

    @Test
    fun passThroughActionsKeepTheirMeaning() {
        assertEquals(StepDecision.Finish, StepSuggestMapper.toDecision(StepSuggest("finish"), input(), brief))
        assertEquals(StepDecision.Next(2), StepSuggestMapper.toDecision(StepSuggest("next"), input(), brief))
        val stop = StepSuggestMapper.toDecision(StepSuggest("stop", reason = "仓库不可用"), input(), brief)
        assertEquals("仓库不可用", (stop as StepDecision.Stop).reason)
    }

    @Test
    fun theModelAnswerIsParsedIntoAStructuredSuggestion() {
        val content = """{"action":"rework","agentId":"M-Coder","feedback":"补过期校验"}"""
        val raw = buildJsonObject {
            put(
                "choices",
                JsonArray(
                    listOf(
                        JsonObject(mapOf("message" to JsonObject(mapOf("content" to JsonPrimitive(content))))),
                    ),
                ),
            )
        }.toString()
        val suggest = TeamStepLlm.decide(
            input(), brief, apiKey = "k",
            http = { _, _, _, _ -> raw },
        )
        assertEquals("rework", suggest?.action)
        assertEquals("m-coder", suggest?.agentId, "agentId 必须归一成小写，否则点将查不到人")
    }

    @Test
    fun garbageFromTheModelIsNullNotAThrownException() {
        val raw = """{"choices":[{"message":{"content":"not json"}}]}"""
        assertNull(TeamStepLlm.decide(input(), brief, apiKey = "k", http = { _, _, _, _ -> raw }))
        assertNull(TeamStepLlm.decide(input(), brief, apiKey = "", http = { _, _, _, _ -> raw }))
    }

    @Test
    fun theLeaderPromptReachesTheModel() {
        var body = ""
        TeamStepLlm.decide(input(), brief, apiKey = "k", http = { _, _, b, _ -> body = b; null })
        assertTrue(body.contains("先想清楚再派人"), "lead 自己的岗位说明必须进提示词，否则它只是在替宿主背台词")
        assertTrue(body.contains("m-arch"), "可用名单必须给出，否则模型只能瞎点名")
    }
}
