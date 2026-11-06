// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
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

/** Planner 拆解的结构化计划，包含执行棒、目标语义胶囊、模块胶囊与意图说明。 */
data class PlannerPlan(
    val stages: List<ChainStage>,
    val target: String? = null,
    val module: String? = null,
    val context: String? = null,
)

/**
 * LLM planner —— `组队:` 挡位的**任务拆解**升级（编排模型适配设计）。
 *
 * 启发式 v1（[com.kapp.tenon.agent.DelegationParser.parseAuto]）只会「一棒干活 + 一棒审核」；
 * 这里把目标**先拆解再组队**：LLM 读目标与在位 agent 的岗位档案，产出 2-4 棒的计划 JSON。
 * 三条纪律：
 *
 * 1. **门裁决，planner 建议**：产物照样过 [StageChainGate] —— 这里只做廉价预校验
 *    （键在位、有干活有审核），硬约束（异厂商等）不在这里重复执行；
 * 2. **超时/无 key/解析失败一律回落启发式**：planner 是增强，不是依赖 —— 返回 null
 *    就退回 v1 的两棒计划，派单永不因为 planner 而卡住；
 * 3. **凭证只进请求头**：DEEPSEEK_API_KEY 走 [DshCredentials]（env > ~/.dsh 凭证文件），
 *    不写日志、不进卡、不落账 —— 与 [CliProviderEnv] 同一条纪律。
 */
object PlannerLlm {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    /** 单次规划的上限：过了这个数，用户等一张卡的时间已经不划算，降级更快。 */
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    /** 拆解提示词：把岗位档案与硬规则写死在 system 里，LLM 只在词汇表内做决策。 */
    private fun systemPrompt(availableKeys: Set<String>): String {
        val roster = availableKeys.mapNotNull { key ->
            AgentProfiles.profileOf(key)?.let { "${it.key}(${it.vendor}, 岗位=${it.defaultRole})" }
        }.joinToString()
        return """
            你是宿主编排器的 planner。把用户的委派目标拆成一条多棒执行计划。
            可用 agent（键(厂商, 岗位)）：$roster。
            规则：只能用列出的 agent 键；1-3 根干活棒 + 恰好 1 根审核棒；审核棒必须排在最后，
            且审核棒与干活棒不能是同一键；每棒 instruction 用一句可执行的中文。
            可带 target（简短目标）、module（模块或路径）、context（意图方案简述）。
            只输出 JSON，不要解释：{"target":"...","module":"...","context":"...",
            "stages":[{"role":"开发","agent":"键","instruction":"..."},
            {"role":"审核","agent":"键","instruction":"..."}]}
        """.trimIndent()
    }

    /**
     * 把目标交给 LLM 拆解。null = 没拆成（无 key / 超时 / 结构不合法），调用方回落启发式。
     *
     * @param http HTTP 层（可注入测试）；缺省 = DeepSeek chat completions。
     */
    fun refine(
        goal: String,
        availableKeys: Set<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        apiKey: String? = DshCredentials.resolve(),
        http: (endpoint: String, apiKey: String, body: String, timeoutMs: Long) -> String? = ::postChat,
    ): List<ChainStage>? = refinePlan(goal, availableKeys, timeoutMs, apiKey, http)?.stages

    /**
     * 完整计划拆解：包含 stages 执行棒与 target / module / context 语义胶囊。
     */
    fun refinePlan(
        goal: String,
        availableKeys: Set<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        apiKey: String? = DshCredentials.resolve(),
        http: (endpoint: String, apiKey: String, body: String, timeoutMs: Long) -> String? = ::postChat,
    ): PlannerPlan? {
        if (goal.isBlank() || availableKeys.isEmpty()) return null
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val body = buildJsonObject {
            put("model", MODEL)
            put("temperature", 0.2)
            put("max_tokens", 800)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", JsonArray(listOf(
                JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(systemPrompt(availableKeys)))),
                JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(goal.take(500)))),
            )))
        }.toString()
        // §11.4 观测钉子（先有读数，再谈优化）：稳定前缀的字节哈希 + 单端单调钟的请求耗时。
        // 稳定前缀 = system prompt（只随名册变化）；计时点 = 宿主发出请求 → 返回，
        // **不含** P2P 投递（真机端到端耗时不是 TTFT —— 两端时钟差 13s、投递 120s 乱序）。
        // provider 侧缓存命中 token 数：响应 usage 里未拆 prompt_cache_hit 字段 ⇒
        // **如实记「不可测」**，不立指标（§11.4：不可测就不立指标）。
        val prefixDigest = Integer.toHexString(systemPrompt(availableKeys).hashCode())
        val startedMs = System.nanoTime()
        val raw = runCatching { http(ENDPOINT, key, body, timeoutMs) }.getOrNull() ?: return null
        val elapsedMs = (System.nanoTime() - startedMs) / 1_000_000
        RelayLog.info(
            "PLANNER",
            "ttftMs=" + elapsedMs + " prefixDigest=" + prefixDigest +
                " cacheHitTokens=unmeasurable (provider usage not exposed)",
        )
        val content = extractContent(raw) ?: return null
        return parsePlanDetailed(content, availableKeys)
    }

    /** 模型爱给 ```json 围栏：剥掉围栏行，只留最外层花括号里的东西。 */
    private fun stripFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val body = trimmed.removePrefix("```").substringAfter("\n", "").trim()
        return body.removeSuffix("```").trim()
    }

    /** OpenAI 兼容响应 → `choices[0].message.content`。 */
    private fun extractContent(raw: String): String? = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val choices = obj["choices"] as? JsonArray ?: return@runCatching null
        val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: return@runCatching null
        message["content"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * 计划 JSON → 链棒。**廉价预校验**：未知键的棒直接丢；干完活要还有干活棒和审核棒
     * 才算成立；审核棒强制收尾（与解析器同一结构保证）。
     */
    fun parsePlan(content: String, availableKeys: Set<String>): List<ChainStage>? =
        parsePlanDetailed(content, availableKeys)?.stages

    /**
     * 完整计划 JSON 解析：解析 stages 以及可选的 target / module / context 语义字段。
     */
    fun parsePlanDetailed(content: String, availableKeys: Set<String>): PlannerPlan? {
        val json = runCatching { Json.parseToJsonElement(stripFence(content)).jsonObject }.getOrNull() ?: return null
        val stages = (json["stages"] as? JsonArray) ?: return null
        val workers = mutableListOf<ChainStage>()
        val reviewers = mutableListOf<ChainStage>()
        for (element in stages) {
            val obj = element as? JsonObject ?: continue
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: continue
            val agent = AgentProfiles.normalizeKey(obj["agent"]?.jsonPrimitive?.contentOrNull) ?: continue
            if (agent !in availableKeys) continue
            val instruction = obj["instruction"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (instruction.isEmpty()) continue
            if (role == "审核") {
                reviewers.add(ChainStage(role = "审核", agentKey = agent, instruction = instruction))
            } else {
                workers.add(ChainStage(role = "开发", agentKey = agent, instruction = instruction))
            }
        }
        if (workers.isEmpty() || reviewers.isEmpty()) return null
        val target = json["target"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val module = json["module"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val context = json["context"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return PlannerPlan(
            stages = workers + reviewers,
            target = target,
            module = module,
            context = context,
        )
    }

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
