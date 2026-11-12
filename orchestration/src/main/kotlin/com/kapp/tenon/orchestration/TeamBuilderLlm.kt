// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.agent.drivers.DshCredentials
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 「AI 建队」助手的一次性 LLM 调用 —— [PlannerLlm] 的同款纪律，产物形状不同：
 *
 * 1. **门裁决，LLM 建议**：产物交 [com.kapp.tenon.domain.TeamScaffoldParser] 过门
 *    （领队必须在位、恰 1 worker、reviewer 异 worker 驱动），这里只做廉价拼装；
 * 2. **超时/无 key/解析失败一律返回 null**：调用方回落 [com.kapp.tenon.domain.TeamScaffoldFallback]
 *    —— 建队是增强，不是依赖，永不因为 LLM 卡住；
 * 3. **凭证只进请求头**：DEEPSEEK_API_KEY 走 [DshCredentials]（env > ~/.dsh 凭证文件），
 *    不写日志、不进卡、不落账 —— 与 [CliProviderEnv] 同一条纪律。
 *
 * system prompt 用 ASCII（本文件没有 cjkLiteralGate 基线条目；角色标签的中文由 LLM 按
 * 提示放 `role` 字段，落点在数据里、不在源码里）。
 */
object TeamBuilderLlm {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    /** 与 PlannerLlm 同一预算：过了这个数，用户等一张预览卡的时间已经不划算。 */
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    /** 拆解提示词：岗位名册写死在 system 里，LLM 只在词汇表内做决策。 */
    private fun systemPrompt(leaderKeys: Set<String>, driverKeys: Set<String>): String {
        val leaders = leaderKeys.joinToString(", ")
        val drivers = driverKeys.joinToString(", ") { key ->
            val vendor = AgentProfiles.vendorOf(key) ?: "unknown"
            "$key(vendor=$vendor)"
        }
        return """
            You are the team-builder assistant of the host orchestrator.
            Design a team roster from the user's need.
            Leader candidates (use exactly one as "leader"): $leaders
            Execution drivers: $drivers
            Rules:
            - 2-5 members; exactly one member with kind=worker, the rest kind=reviewer.
            - Every member "driver" must be one of the listed driver keys.
            - Reviewer drivers must be a different vendor than the worker driver.
            - "name" is a lowercase ascii slug (a-z, 0-9, hyphen), unique in the team.
            - "role" is a short human label for the duty; write it in Chinese.
            - "prompt" is that member's persona system prompt; write it in Chinese.
              The worker prompt should stage the work: investigate the codebase,
              implement, run available tests, report what changed. Reviewer prompts
              should focus the review on what the role label implies. 2-4 sentences.
            - "teamName" is a short Chinese team name.
            Output JSON only, no explanation:
            {"teamName":"...","leader":"<leader key>",
             "members":[{"name":"...","role":"...","prompt":"...","driver":"<driver key>","kind":"worker|reviewer"}]}
        """.trimIndent()
    }

    /**
     * 把需求交给 LLM 生成建队 JSON 文本。null = 没生成（无 key / 超时 / 非 2xx / 空 content），
     * 调用方回落模板。返回的是**原始 content** —— 门裁决在 [com.kapp.tenon.domain.TeamScaffoldParser]。
     *
     * @param http HTTP 层（可注入测试）；缺省 = DeepSeek chat completions。
     */
    fun draft(
        need: String,
        leaderKeys: Set<String>,
        driverKeys: Set<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        apiKey: String? = DshCredentials.resolve(),
        http: (endpoint: String, apiKey: String, body: String, timeoutMs: Long) -> String? = ::postChat,
    ): String? {
        if (need.isBlank() || leaderKeys.isEmpty() || driverKeys.isEmpty()) return null
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val body = buildJsonObject {
            put("model", MODEL)
            put("temperature", 0.2)
            // 900：每成员多一段人设 prompt（2-4 句中文）后 600 会截断 JSON 尾部。
            put("max_tokens", 900)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put(
                "messages",
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("system"),
                                "content" to JsonPrimitive(systemPrompt(leaderKeys, driverKeys)),
                            ),
                        ),
                        JsonObject(
                            mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(need.take(500))),
                        ),
                    ),
                ),
            )
        }.toString()
        val startedMs = System.nanoTime()
        val raw = runCatching { http(ENDPOINT, key, body, timeoutMs) }.getOrNull() ?: return null
        val elapsedMs = (System.nanoTime() - startedMs) / 1_000_000
        RelayLog.info("TEAM-BUILDER", "elapsedMs=" + elapsedMs)
        return extractContent(raw)?.takeIf { it.isNotBlank() }
    }

    /** OpenAI 兼容响应 → `choices[0].message.content`。 */
    private fun extractContent(raw: String): String? = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val choices = obj["choices"] as? JsonArray ?: return@runCatching null
        val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: return@runCatching null
        message["content"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** 缺省 HTTP：POST JSON，返回 2xx 响应体；其它一律 null（调用方降级，不读错误详情）。 */
    private fun postChat(endpoint: String, apiKey: String, body: String, timeoutMs: Long): String? {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs.toInt()
            connection.readTimeout = timeoutMs.toInt()
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
