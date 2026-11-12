// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.DelegationPlan
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
 * 「leader 动态编排」的一次性 LLM 重排 —— [TeamBuilderLlm] / [PlannerLlm] 同款纪律：
 *
 * 1. **门裁决，LLM 建议**：产物过 [apply] 的纯函数门（成员集合不变、worker 恒居首、
 *    指令非空限长），不过门 / 超时 / 无 key / 解析失败一律 null，调用方回落编译固定链
 *    —— 编译链永远是保底，LLM 是增强，派单永不因为 LLM 卡住；
 * 2. **改不了的做不成单**：链的形状不变 —— 「先实现后审查」是产品不变量，调研合并进
 *    worker 棒指引（反官僚规则：workers investigate and implement）。LLM 只做两件事：
 *    **重排 reviewer 顺序** + **按需求定制各棒指令文案**。驱动 / 模型 / role 标签不许动
 *    —— 驱动与模型是用户执行档配置的事实，role 是群声明里的展示事实，各有其源；
 * 3. **凭证只进请求头**（DEEPSEEK_API_KEY 走 [DshCredentials]）：不写日志、不进卡、
 *    不落账 —— 与 [TeamBuilderLlm] 同一条纪律。
 */
object TeamPlanRefiner {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    /** 与 [TeamBuilderLlm] 同一预算：过了这个数，@ 的那句话已经等得不划算。 */
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    /** 单棒指令上限：与组员人设提示词同一条预算（TeamRosterDialog 的 2000 字）。 */
    private const val INSTRUCTION_MAX = 2_000

    /** LLM 的一行重排产物。 */
    data class RefinedStage(val agentKey: String, val instruction: String)

    /**
     * LLM 调用 + 门校验。null = 不可用，调用方回落编译固定链。
     *
     * @param http HTTP 层（可注入测试）；缺省 = DeepSeek chat completions。
     */
    fun refine(
        plan: DelegationPlan,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        apiKey: String? = DshCredentials.resolve(),
        http: (endpoint: String, apiKey: String, body: String, timeoutMs: Long) -> String? = ::postChat,
    ): DelegationPlan? {
        // 单棒没得重排（0 reviewer 的团队编译成单棒）；LLM 也不该为一个目标再调一次。
        if (plan.stages.size < 2) return null
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val raw = draft(plan, timeoutMs, key, http) ?: return null
        val refined = parseRefined(raw) ?: return null
        return apply(plan, refined)
    }

    /**
     * 纯函数门：把 LLM 的重排行套回原计划。返回 null = 校验不过（调用方回落编译链）。
     *
     * 四条判据：行数一致；agentKey 是原集合的一个**排列**（不许增减换人）；worker
     * （原计划首棒）恒居首；每条指令非空且限长。role / model 按原棒携带，LLM 没有授权动它们。
     */
    fun apply(plan: DelegationPlan, refined: List<RefinedStage>): DelegationPlan? {
        if (refined.size != plan.stages.size) return null
        val rows = refined.map { RefinedStage(it.agentKey.trim().lowercase(), it.instruction) }
        if (rows.any { it.agentKey.isEmpty() }) return null
        val originalKeys = plan.stages.map { it.agentKey.trim().lowercase() }
        if (rows.map { it.agentKey }.sorted() != originalKeys.sorted()) return null
        // worker（原计划首棒）恒居首：LLM 产物顺序的第一行必须是它。
        if (rows.first().agentKey != originalKeys.first()) return null
        // **产物顺序就是新链序**（重排是本函数存在的意义）：按 LLM 给的顺序逐行
        // 套回原棒 —— role / model 按 agentKey 配对原棒携带，LLM 没有授权动它们。
        val originals = plan.stages.toMutableList()
        val stages = mutableListOf<ChainStage>()
        for (row in rows) {
            val index = originals.indexOfFirst { it.agentKey.trim().lowercase() == row.agentKey }
            if (index < 0) return null
            val original = originals.removeAt(index)
            val instruction = row.instruction.trim().take(INSTRUCTION_MAX)
            if (instruction.isBlank()) return null
            stages.add(
                ChainStage(
                    role = original.role,
                    agentKey = original.agentKey,
                    instruction = instruction,
                    model = original.model,
                ),
            )
        }
        return plan.copy(stages = stages)
    }

    /** 解析 LLM 回复（可能带 markdown 栅栏/噪声）：抠第一个 JSON 对象里的 stages 数组。 */
    private fun parseRefined(raw: String): List<RefinedStage>? = runCatching {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return@runCatching null
        val root = Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        val stages = root["stages"] as? JsonArray ?: return@runCatching null
        stages.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val key = (obj["agentKey"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val instruction = (obj["instruction"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            RefinedStage(key, instruction)
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 拆解提示词：成员名册写死在 system 里，LLM 只重排与改写。 */
    private fun systemPrompt(plan: DelegationPlan): String {
        val briefs = plan.stages.joinToString("\n") { stage ->
            "- key=" + stage.agentKey + " role=" + stage.role +
                " currentInstruction=" + stage.instruction.take(160)
        }
        return """
            You are the dispatch planner of the host orchestrator.
            Goal: ${plan.goal.take(400)}
            The chain shape is fixed: the FIRST stage is the worker (implementation); the rest are reviewers.
            Members (current order):
            $briefs
            Rewrite for THIS goal:
            - Keep the same member set. The worker stays FIRST.
            - You may reorder the reviewers so the most goal-relevant duty goes first.
            - Rewrite each member's instruction in Chinese, staged for THIS goal:
              worker: investigate the workspace first, then implement, then run available checks and report;
              reviewers: what to verify for this goal, item by item, ending with an explicit verdict line.
              2-5 sentences each.
            - Do not add, remove, or swap members. Output JSON only, no explanation:
            {"stages":[{"agentKey":"...","instruction":"..."}]}
        """.trimIndent()
    }

    private fun draft(
        plan: DelegationPlan,
        timeoutMs: Long,
        apiKey: String,
        http: (String, String, String, Long) -> String?,
    ): String? {
        val body = buildJsonObject {
            put("model", MODEL)
            put("temperature", 0.2)
            put("max_tokens", 900)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put(
                "messages",
                JsonArray(
                    listOf(
                        JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(systemPrompt(plan)))),
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("user"),
                                "content" to JsonPrimitive("Goal: " + plan.goal.take(500)),
                            ),
                        ),
                    ),
                ),
            )
        }.toString()
        val raw = runCatching { http(ENDPOINT, apiKey, body, timeoutMs) }.getOrNull() ?: return null
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
