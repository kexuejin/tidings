// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.LeaderBrief
import com.kapp.tenon.agent.StepDecider
import com.kapp.tenon.agent.StepDecision
import com.kapp.tenon.agent.StepInput
import com.kapp.tenon.agent.drivers.DshCredentials
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 主理人的一步裁决 —— 只描述「干什么」，不描述「怎么实现」。
 *
 * **反官僚化**：Lead 负责意图准入、点将与守门，不替 Worker 写实现方案。
 * 所以 [instruction] 只填到「这一棒要达成什么」的程度，细节由 Worker 在沙箱里自己调研。
 */
data class StepSuggest(
    val action: String,
    val agentId: String? = null,
    val instruction: String? = null,
    val feedback: String? = null,
    val reason: String? = null,
)

/**
 * 把 [StepSuggest] 翻成执行器能消费的 [StepDecision]。
 *
 * 身份映射在这里收口：模型给的是 `agentId`，驱动与模型**必须**从 [LeaderBrief.members]
 * 回查 —— 让它自己说用哪个底座是幻觉的第一来源。
 *
 * 返回 null = 这份建议不可用（缺必填项 / 不认识的人），调用方回落规约。
 */
object StepSuggestMapper {

    fun toDecision(suggest: StepSuggest, input: StepInput, brief: LeaderBrief): StepDecision? =
        when (suggest.action.trim().lowercase()) {
            "next" -> StepDecision.Next(input.stageIndex + 1)
            "finish" -> StepDecision.Finish
            "stop" -> StepDecision.Stop(suggest.reason?.trim()?.takeIf { it.isNotEmpty() } ?: "leader stopped")
            "rework" -> {
                val feedback = suggest.feedback?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                // 回哪一棒：模型可以点名「这是方案的问题」⇒ 回到那个人的棒；没点名就回第 0 棒。
                val target = suggest.agentId?.let { id -> brief.members.firstOrNull { it.agentId == id } }
                val at = target?.let { m -> input.stages.indexOfFirst { it.agentKey == m.driverKey } } ?: -1
                StepDecision.Rework(
                    attempt = input.attempt + 1,
                    feedback = feedback,
                    stageIndex = if (at >= 0) at else 0,
                )
            }
            "spawn" -> {
                val member = suggest.agentId?.let { id -> brief.members.firstOrNull { it.agentId == id } }
                    ?: return null
                val instruction = suggest.instruction?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                StepDecision.Spawn(
                    afterIndex = input.stageIndex,
                    stage = ChainStage(
                        role = member.role.ifBlank { if (member.isWorker) "worker" else "reviewer" },
                        agentKey = member.driverKey,
                        instruction = instruction.take(2_000),
                        model = member.model,
                        // 卡面括号里的承办人：身份名，不是驱动键。
                        assignee = member.name.ifBlank { member.agentId },
                    ),
                    reason = suggest.reason?.trim()?.take(200),
                )
            }
            else -> null
        }
}

/**
 * 主理人决策器：把 [StepInput] 交给 lead 裁决，落成 [StepDecision]。
 *
 * 它**只在被问到时才思考** —— 什么时候问由宿主决定（见 `EscalatingStepDecider`：
 * 常规流转不问，规约判不了才问）。这既保住了「专家随时在场」，又不会让一条链
 * 烧掉十次模型调用。
 */
class TeamStepDecider(
    private val brief: LeaderBrief,
    private val timeoutMs: Long = TeamStepLlm.DEFAULT_TIMEOUT_MS,
    private val http: (endpoint: String, apiKey: String, body: String, timeoutMs: Long) -> String? =
        TeamStepLlm::postChat,
) : StepDecider {

    override suspend fun decide(input: StepInput): StepDecision {
        // 请不动主理人时**抛**而不是返回 Stop：调用方（EscalatingStepDecider）据此回落
        // 规约的结论 —— 一次超时不该把一条本来能跑的链停掉。
        val suggest = withContext(Dispatchers.IO) {
            TeamStepLlm.decide(input, brief, timeoutMs, http = http)
        } ?: error("leader unavailable (timeout or unparsable answer)")
        return StepSuggestMapper.toDecision(suggest, input, brief)
            ?: error("leader answer is not actionable: " + suggest.action.take(40))
    }
}

/**
 * 一步裁决的模型调用（DeepSeek，`json_object`）。
 *
 * 三条与 [TeamPlannerLlm] 同源的纪律：凭证只进请求头、超时/解析失败一律返回 null
 * （由调用方回落规约）、提示词里写死「不许输出驱动键」。
 */
object TeamStepLlm {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    /** 一步裁决的预算比整盘规划更紧：它发生在链中间，用户在等卡。 */
    const val DEFAULT_TIMEOUT_MS: Long = 15_000L

    fun decide(
        input: StepInput,
        brief: LeaderBrief,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        apiKey: String? = DshCredentials.resolve(),
        http: (endpoint: String, apiKey: String, body: String, timeoutMs: Long) -> String? = ::postChat,
    ): StepSuggest? {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val body = buildJsonObject {
            put("model", MODEL)
            put("temperature", 0.2)
            put("max_tokens", 700)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put(
                "messages",
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("system"),
                                "content" to JsonPrimitive(systemPrompt(brief)),
                            ),
                        ),
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("user"),
                                "content" to JsonPrimitive(userPrompt(input)),
                            ),
                        ),
                    ),
                ),
            )
        }.toString()
        val raw = runCatching { http(ENDPOINT, key, body, timeoutMs) }.getOrNull() ?: return null
        val content = extractContent(raw) ?: return null
        return parse(content)
    }

    private fun systemPrompt(brief: LeaderBrief): String {
        val roster = brief.members.joinToString("\n") { m ->
            "- agentId=" + m.agentId + " name=" + m.name.ifBlank { m.agentId } +
                " role=" + m.role.ifBlank { if (m.isWorker) "worker" else "reviewer" } +
                " kind=" + (if (m.isWorker) "worker" else "reviewer")
        }
        val persona = brief.leaderPrompt?.trim()?.take(800)?.takeIf { it.isNotEmpty() }
            ?.let { "\n你的岗位说明：\n" + it } ?: ""
        return """
            你是团队「${brief.teamName}」的主理人（${brief.leaderName}），这次任务由你负责分派。
            团队成员（只能用这些人）：
            $roster
            $persona

            你刚刚看到一棒跑完的结论。现在决定下一步怎么走，只能选一个动作：
            - "next"：按计划推进到下一棒；
            - "rework"：让某个人重做（必须给 feedback，写清要改什么）；
            - "spawn"：加一个人做一件新事（必须给 agentId 与 instruction，只写目标与验收标准，
              不要替对方写实现方案）；
            - "finish"：现有产出已达成目标，收工等机主终审；
            - "stop"：确实无法继续（说明 reason）。

            规则：agentId 只能取上面名单里的值；不要输出模型名或驱动名；不确定时选 "next"。
            只输出 JSON：{"action":"next|rework|spawn|finish|stop","agentId":"...","instruction":"...",
            "feedback":"...","reason":"..."}
        """.trimIndent()
    }

    private fun userPrompt(input: StepInput): String = buildString {
        appendLine("目标：" + input.goal.take(500))
        appendLine("当前是第 " + (input.attempt) + " 轮。")
        appendLine("计划（按序）：")
        input.stages.forEachIndexed { i, s ->
            appendLine("  " + (i + 1) + ". " + s.role + " (" + (s.assignee ?: s.agentKey) + ")")
        }
        appendLine("刚跑完：" + (input.stages.getOrNull(input.stageIndex)?.role ?: "?"))
        appendLine("这一棒的结论：" + input.feedback.take(600))
        appendLine("审查判定：" + (input.verdict?.name ?: "无（不是审查棒）"))
        if (input.history.isNotEmpty()) {
            appendLine("已经做过的决定：")
            input.history.takeLast(6).forEach { r ->
                appendLine("  - " + r.stageRole + " → " + r.action +
                    (r.reason?.let { "（" + it.take(80) + "）" } ?: ""))
            }
        }
    }

    private fun parse(content: String): StepSuggest? = runCatching {
        val obj = Json.parseToJsonElement(content).jsonObject
        StepSuggest(
            action = obj["action"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null,
            agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase(),
            instruction = obj["instruction"]?.jsonPrimitive?.contentOrNull?.trim(),
            feedback = obj["feedback"]?.jsonPrimitive?.contentOrNull?.trim(),
            reason = obj["reason"]?.jsonPrimitive?.contentOrNull?.trim(),
        )
    }.getOrNull()

    private fun extractContent(raw: String): String? = runCatching {
        Json.parseToJsonElement(raw).jsonObject["choices"]
            ?.let { it as? JsonArray }?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    fun postChat(endpoint: String, apiKey: String, body: String, timeoutMs: Long): String? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + apiKey)
        }
        return runCatching {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) text else null
        }.getOrNull().also { runCatching { conn.disconnect() } }
    }

    private val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}
