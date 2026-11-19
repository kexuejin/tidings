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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 一棒**意图**：身份层（组员 `agentId`），不是驱动键。
 *
 * `ChainStage.agentKey` 是驱动键（codex/claude/dsh…），LLM 没有权力碰它 —— 驱动、模型、
 * role、卡面承办人都是「代码按 `agentId` 回查执行档与群声明」得到的事实。LLM 只在
 * **谁干、审不审、各棒怎么交代**这三件事上做决策。
 */
data class StageIntent(
    val agentId: String,
    val asReviewer: Boolean,
    val instruction: String,
)

/**
 * Planner 的编排建议：`stages` 的顺序即链序，**1 棒是合法输出**（0 审查棒）。
 *
 * [rationale] 只进飞行记录，不上卡面 —— 让「这次为什么没审」有据可查，但不拿模型的话
 * 当用户可见事实。
 */
data class OrchestrationSuggestion(
    val stages: List<StageIntent>,
    val rationale: String? = null,
) {
    val reviewerCount: Int get() = stages.count { it.asReviewer }
}

/** 参与裁决的成员一行（只列**在位且未冷却**的成员：不列不可用的，减少无效往返）。 */
data class PlannerMemberBrief(
    val agentId: String,
    val name: String,
    val role: String,
    val isWorker: Boolean,
    val vendor: String?,
)

/**
 * 决策输入。
 *
 * [defaultWorkerAgentId] 是**默认落点**（`@团队名` → 编制首位 worker，`@组员` → 那个人），
 * **不是**必须遵守的人选：点将权在主理人 —— 编制里凡是 `kind=worker` 的人都能被它推到
 * 链首。提示词里把它写成 "default"，就是为了让模型知道「不换人也对」。
 */
data class PlannerChainInput(
    val goal: String,
    val defaultWorkerAgentId: String,
    val members: List<PlannerMemberBrief>,
)

/**
 * 建议的**形状门**（纯函数）：任一判据不过 ⇒ 整份建议作废 ⇒ 调用方回落编译固定链。
 *
 * 只守形状合法性。在位 / 冷却 / 异厂商三条**不在这里** —— 它们只住
 * [StageChainGate]，同一判据两处实现会各自腐坏；planner 的选择若被那三道门拒，
 * 由派单方回落编译链再裁决一次（见 `runTeamDispatch`）。
 */
object PlannerChainGate {

    /** 棒数上限：1 worker + 3 reviewer。再高，20s 预算与卡面高度都失控。 */
    const val MAX_STAGES: Int = 4

    /** 单棒指令上限：与组员人设提示词、[TeamPlanRefiner] 同一条预算。 */
    const val INSTRUCTION_MAX: Int = 2_000

    /**
     * @param members 执行档全集（**不是**群声明名册：名册里可能有人没有 driverKey，
     *   回查必失败）。`kind=worker` 的人才是可点的将 —— 这是本门唯一读 `kind` 的地方。
     */
    fun accept(
        suggestion: OrchestrationSuggestion,
        members: List<TeamExecutionMemberSpec>,
    ): OrchestrationSuggestion? {
        val known = members.associateBy { it.agentId.trim().lowercase() }
        if (known.isEmpty()) return null
        val rows = suggestion.stages.map {
            it.copy(agentId = it.agentId.trim().lowercase(), instruction = it.instruction.trim())
        }
        if (rows.isEmpty()) return null
        if (rows.size > minOf(members.size, MAX_STAGES)) return null
        if (rows.any { it.agentId !in known }) return null
        if (rows.map { it.agentId }.toSet().size != rows.size) return null
        val workerIndexes = rows.indices.filter { !rows[it].asReviewer }
        if (workerIndexes.size != 1) return null
        // 有审查棒时 worker 必须居首（先实现后审查）；0 审查棒时它就是唯一那棒。
        if (rows.size > 1 && workerIndexes.single() != 0) return null
        // **点将**：干活棒必须是编制里声明「能干活」的人。审查员顶上来不是点将，是把
        // 「只审查」的人塞进执行位 —— 他的授权、他的提示词、用户对它的预期全是审查语义。
        if (known[rows[0].agentId]?.isWorker != true) return null
        if (rows.any { it.instruction.isEmpty() || it.instruction.length > INSTRUCTION_MAX }) return null
        return suggestion.copy(
            stages = rows,
            rationale = suggestion.rationale?.trim()?.take(400)?.takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * 就位横幅、「换了谁下场」与「未经独立审查」的拼接规则（纯函数，便于钉住）。
 *
 * 0 审查棒**不限制**，但必须可见 —— 光有飞行记录不够：用户看的是卡，不是日志。
 * 同理，主理人**换了人**也必须可见：`@团队名` 的锚点只是"默认落点"，换人是它的职权，
 * 但用户点的是团队不是那个人 —— 卡上不说，用户只能从「承办人」那一列自己去猜。
 */
object PlanDecisionBanner {

    /**
     * @param pickedWorker 承办人标注的**已本地化文本**（`uiTexts.pickedWorkerNotice`）。
     *   空串 = 没装文案桥，不拼分隔符也不编中文。
     */
    fun compose(
        base: String,
        notice: String,
        decision: FlightPlanDecision?,
        pickedWorker: String = "",
    ): String {
        val parts = mutableListOf<String>()
        if (base.isNotBlank()) parts.add(base)
        if (workerSwitched(decision)) pickedWorker.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        val mark = notice.trim()
        if (decision != null && decision.reviewerCount == 0 && mark.isNotEmpty()) parts.add(mark)
        return parts.joinToString(" · ")
    }

    /**
     * 主理人是否换了人：有承办人名，且与锚点不是同一个。
     *
     * 锚点缺失（纯审查单、1:1 单席位）时**不判换人** —— 「没有锚点」不等于
     * 「换了个人」。承办人名空白同样不判：那句标注只能是"本单由 X 承办"，
     * 没有 X 就没有这句话。
     */
    fun workerSwitched(decision: FlightPlanDecision?): Boolean {
        val worker = decision?.workerAgentId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val anchor = decision.anchorAgentId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (decision.workerName?.isBlank() != false) return false
        return !worker.equals(anchor, ignoreCase = true)
    }
}

/**
 * 责任链的 LLM 裁决（[TeamPlanRefiner] 的上一档，二者不重叠）：
 *
 * - [TeamPlanRefiner] 在形状**已定**之后改顺序与文案（L3）；
 * - 这里在形状**未定**之前裁决「谁干 / 审不审 / 审几棒 / 谁审 / 各棒怎么交代」（L1）。
 *
 * 三条纪律（与 [PlannerLlm] / [TeamPlanRefiner] 同源）：
 *
 * 1. **门裁决，LLM 建议**：产物过 [PlannerChainGate]，不过门 / 无 key / 超时 / 解析失败
 *    一律 null，调用方回落编译固定链 —— 派单永不因为 LLM 卡住；
 * 2. **点将只在编制内**：候选池是执行档，`kind=worker` 才是可点的将；默认
 *    [PlannerChainInput.defaultWorkerAgentId] 不换人也完全合法。换人要有理由（任务
 *    更合适），不是为了"用一下别人"；
 * 3. **凭证只进请求头**（`DEEPSEEK_API_KEY` 走 [DshCredentials]）：不写日志、不进卡、不落账。
 */
object TeamPlannerLlm {

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    /** 与 [TeamBuilderLlm] / [TeamPlanRefiner] 同一预算：过了这个数，@ 的那句话已经等得不划算。 */
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    /**
     * 请一次裁决。null = 不可用（无 key / 超时 / 解析失败），调用方回落编译固定链。
     *
     * @param http HTTP 层（可注入测试）；缺省 = DeepSeek chat completions。
     */
    fun decide(
        input: PlannerChainInput,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        apiKey: String? = DshCredentials.resolve(),
        http: (endpoint: String, apiKey: String, body: String, timeoutMs: Long) -> String? = ::postChat,
    ): OrchestrationSuggestion? {
        if (input.goal.isBlank() || input.members.isEmpty()) return null
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val body = buildJsonObject {
            put("model", MODEL)
            put("temperature", 0.2)
            put("max_tokens", 900)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put(
                "messages",
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("system"),
                                "content" to JsonPrimitive(systemPrompt(input)),
                            ),
                        ),
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive("user"),
                                "content" to JsonPrimitive("Goal: " + input.goal.take(500)),
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

    /**
     * 提示词边界写死在 system 里：可用池只有**在位且未冷却**的成员、干活人只能从
     * `kind=worker` 里点、**审核棒数量由模型裁决且 0 是合法值**（0 是"不需要独立复核"，
     * 不是"省一步"）。
     */
    private fun systemPrompt(input: PlannerChainInput): String {
        val pool = input.members.joinToString("\n") { member ->
            "- agentId=" + member.agentId +
                " name=" + member.name +
                " role=" + member.role.ifBlank { "member" } +
                " kind=" + (if (member.isWorker) "worker" else "reviewer") +
                " vendor=" + (member.vendor ?: "unknown")
        }
        return """
            You are the dispatch planner of the host orchestrator for a team of coding agents.
            The user addressed the whole TEAM, so you decide who works on THIS goal.
            Members you may use (all of them are installed and free right now):
            $pool
            Decide the chain for this goal:
            - WHO implements: exactly one member whose kind is worker. The default is
              agentId=${input.defaultWorkerAgentId}; keep it unless another worker is clearly the
              better fit for this goal. A member whose kind is reviewer can never implement.
            - How many review stages are worth running for THIS goal: 0, 1 or 2 (never more than 2).
              0 is legal and often right: single-file, low-risk, easily self-verified changes.
              1 is the usual choice. 2 is for high-risk work or when two different kinds of
              expertise (e.g. frontend + security, or implementation + test) must be checked.
              There is no rule that forces a review stage; judge by the goal.
            - Which members review, in order. A reviewer must come from a different vendor than
              the worker (same-vendor review is not independent and will be rejected).
            The worker stage is always first, and each stage must be a different person.
            Write each stage instruction for THIS goal: worker = investigate first, then implement,
            then run the available checks and report; reviewer = what to verify, item by item,
            ending with an explicit verdict line. 2-5 sentences each.
            Put your reasoning in "rationale" (one sentence: why this worker, why this many reviews).
            Output JSON only, no markdown, no explanation:
            {"rationale":"...","stages":[{"agentId":"...","asReviewer":false,"instruction":"..."}]}
        """.trimIndent()
    }

    /** 解析回复（可能带 markdown 栅栏/噪声）：抠第一个 JSON 对象里的 stages 数组。 */
    fun parse(raw: String): OrchestrationSuggestion? = runCatching {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return@runCatching null
        val root = Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        val stages = root["stages"] as? JsonArray ?: return@runCatching null
        val rows = stages.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val agentId = (obj["agentId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val instruction = (obj["instruction"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (agentId.isEmpty() || instruction.isEmpty()) return@mapNotNull null
            StageIntent(agentId, obj["asReviewer"]?.jsonPrimitive?.contentOrNull == "true", instruction)
        }
        if (rows.isEmpty()) return@runCatching null
        OrchestrationSuggestion(
            stages = rows,
            rationale = (root["rationale"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    /** 成员池的一行：`agentId` + 群声明岗位 + 厂商（厂商只用来提示"别同厂商"）。 */
    fun briefOf(member: TeamExecutionMemberSpec, name: String, role: String): PlannerMemberBrief =
        PlannerMemberBrief(
            agentId = member.agentId,
            name = name,
            role = role,
            isWorker = member.isWorker,
            vendor = AgentProfiles.vendorOf(member.driverKey),
        )

    /** OpenAI 兼容响应 → `choices[0].message.content`。 */
    private fun extractContent(raw: String): String? = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val choices = obj["choices"]?.jsonArray ?: return@runCatching null
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
