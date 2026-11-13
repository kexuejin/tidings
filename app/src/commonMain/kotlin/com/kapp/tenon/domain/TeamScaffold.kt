// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentMemberName
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.TeamMemberDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/**
 * 「AI 建队」助手的**结构化产物**：LLM（或回落模板）按用户的一句话需求生成的建队方案。
 *
 * ## 它生成了什么、没生成什么
 *
 * 组员是**编制行**（名字 = `@` token、角色标签、内部稳定 id），不是真实运行的 agent 进程
 * —— 与手动建队（`TeamProfileDialog` 新建行生成 `m-<slug>-<rand>`）同一个语义。
 * 真正的约束只有一条：[leaderAgentId] 必须来自本机在位 agent（造一个不存在的领队 =
 * 造一个永远不会执行的头）。
 *
 * 成员同时携带**执行建议**（[Member.driverKey] / [Member.kind]）：确认建队时名册行与
 * 执行档（`TeamExecutionProfileUi`）共用同一份成员 id —— 两边各写各的 id 就会出现
 * 「成员悬空 / 不在名册」那类保存期拒绝。
 *
 * 本文件是纯函数层（`:app` commonMain）：解析、归一、回落都在这里，LLM 的 HTTP 调用在
 * 编排层（PlannerLlm 同款纪律：拆不成回落模板，建队永不因 LLM 卡住）。
 *
 * ## 为什么拟用名叫 [teamTitle] 不叫 teamName
 *
 * `TeamProfileWriteGuardTest` 按针 `teamName = ` 白名单钉「团队名的写入点」——那份名单
 * 是**席位/档案数据流**的写入口。本方案只是建队前的**拟用名**（用户确认时才经
 * `saveTeamProfile` 变成档案的 `name`），不碰席位；叫 `teamTitle` 让两条数据流在名字上
 * 就分开，守卫的针不必为本文件开口子。LLM 侧的 JSON 键仍是 `teamName`（对模型的契约），
 * 解析时映进 [teamTitle]。
 */
data class TeamScaffoldSpec(
    val teamTitle: String,
    val leaderAgentId: String,
    val members: List<Member>,
) {

    /**
     * 一名组员的完整编制行。
     *
     * @param agentId 团队内部稳定 id（`m-<slug>-<rand>`）；名册行与执行档**共用此键**。
     * @param name `@` token（小写 ASCII slug）；中文进 [role]。
     * @param role 角色标签（编码/审查/测试…，自由文本）。
     * @param driverKey 执行驱动键（执行档保存校验要求 reviewer 与 worker **异厂商**，
     *   调用方给的兜底驱动对必须已满足这条）。
     * @param kind 取值域见 [TeamExecutionKindsUi]；整份方案**恰 1 worker**（编译期硬约束）。
     */
    data class Member(
        val agentId: String,
        val name: String,
        val role: String,
        val driverKey: String,
        val kind: String,
        /**
         * 组员人设提示词（建队时自动生成 / 回落模板内置）：确认建队时随名册行
         * 一并写进执行档的 `customPrompt`。null = 没生成（LLM 没给 / 模板省略）
         * —— 执行档回落默认审查模板，不造空串人设。
         */
        val prompt: String? = null,
    )

    /** 名册行（团队档案写路径的入参形状）；新建行恒 [TeamMemberDraft.isNew]。 */
    fun toMemberDrafts(): List<TeamMemberDraft> = members.map {
        TeamMemberDraft(agentId = it.agentId, name = it.name, role = it.role, isNew = true)
    }

    /** 执行档成员行（`TeamExecutionPort.save` 的入参形状）；模型跟随驱动默认。 */
    fun toExecutionMembers(): List<TeamExecutionMemberUi> = members.map {
        TeamExecutionMemberUi(
            agentId = it.agentId,
            kind = it.kind,
            driverKey = it.driverKey,
            // 人设提示词与名册行共用同一份成员 id（本文件头部注释的约束）：
            // 两边各写各的就会出现「成员悬空 / 不在名册」那类保存期拒绝。
            customPrompt = it.prompt,
        )
    }
}

/** 新建编制行的内部 id 缺省形状（`m-<slug>-<rand4>`，与 `TeamProfileDialog` 新建行同款）。 */
public fun newTeamScaffoldAgentId(slugName: String): String {
    val rand = (1..4).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
    return "m-${slugName.take(Limits.ID - 6)}-$rand"
}

/**
 * LLM 产物 → [TeamScaffoldSpec] 的解析与归一。
 *
 * 三条纪律（与 PlannerLlm 同源）：
 *
 * 1. **门裁决，LLM 建议**：[leaderAgentId 参数][leaderKeys] 不在位 ⇒ 整份拒绝（返回 null，
 *    调用方回落模板）—— 领队是唯一的执行入口，这里没有"宽容"可言；
 * 2. **逐行修复，不整份丢弃**：驱动不在位 ⇒ 换调用方给的兜底键；kind 词表外 ⇒ reviewer；
 *    0 worker ⇒ 首行升 worker；多 worker ⇒ 降级成 reviewer（执行档保存校验恰 1 worker）；
 * 3. **缺省由调用方传**（队名 / 角色标签）：本文件生产源码不放 CJK 字面量（cjkLiteralGate），
 *    本地化文案是 UI 的事。
 */
object TeamScaffoldParser {

    /** 解析失败 = 调用方回落模板；成功 = 归一后的可建方案。 */
    fun parse(
        raw: String,
        leaderKeys: Set<String>,
        driverKeys: Set<String>,
        fallbackWorkerDriver: String?,
        fallbackReviewerDriver: String?,
        defaultTeamName: String,
        defaultRole: String,
        newAgentId: (slugName: String) -> String = ::newTeamScaffoldAgentId,
    ): TeamScaffoldSpec? {
        val root = parseJsonObject(raw) ?: return null
        val leader = root.str("leader")?.let { key -> canonicalKey(key, leaderKeys) }
            ?: return null
        val teamTitle = (root.str("teamName") ?: defaultTeamName)
            .trim()
            .take(Limits.TEAM)
            .ifBlank { defaultTeamName.trim().take(Limits.TEAM).ifBlank { return null } }
        val takenNames = mutableSetOf<String>()
        val rows = (root["members"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            normalizeRow(
                rawName = obj.str("name"),
                rawRole = obj.str("role"),
                rawPrompt = obj.str("prompt"),
                rawDriver = obj.str("driver") ?: obj.str("driverKey"),
                rawKind = obj.str("kind"),
                rawWorkerFlag = (obj["worker"] as? JsonPrimitive)?.booleanOrNull,
                leaderName = leader,
                driverKeys = driverKeys,
                fallbackWorkerDriver = fallbackWorkerDriver,
                fallbackReviewerDriver = fallbackReviewerDriver,
                defaultRole = defaultRole,
                takenNames = takenNames,
                newAgentId = newAgentId,
            )
        } ?: return null

        val clamped = rows.take(Limits.TEAM_MEMBERS_MAX)
        val enforced = enforceSingleWorker(clamped) ?: return null
        if (enforced.isEmpty()) return null
        return TeamScaffoldSpec(
            teamTitle = teamTitle,
            leaderAgentId = leader,
            members = keepReviewersOffWorkerDriver(
                enforced,
                fallbackReviewerDriver?.takeIf {
                    it != enforced.first { m -> m.kind == TeamExecutionKindsUi.WORKER }.driverKey
                },
            ),
        )
    }

    /** 组员人设提示词上限：与 TeamRosterDialog 的手写输入同一预算。 */
    private const val PROMPT_MAX = 2_000

    /** 词表外 kind 的归一：干活词 ⇒ worker，其余（含词表外/空白）⇒ reviewer（保守侧）。 */
    private fun normalizeKind(raw: String?, workerFlag: Boolean?): String = when {
        workerFlag == true -> TeamExecutionKindsUi.WORKER
        workerFlag == false -> TeamExecutionKindsUi.REVIEWER
        raw?.trim()?.lowercase() in WORKER_WORDS -> TeamExecutionKindsUi.WORKER
        else -> TeamExecutionKindsUi.REVIEWER
    }

    private val WORKER_WORDS: Set<String> = setOf("worker", "dev", "developer", "coder", "build")

    /** 驱动键归一：精确命中优先，其次大小写不敏感；不在位 = null（由调用方兜底换键）。 */
    private fun canonicalKey(raw: String?, keys: Set<String>): String? {
        val key = raw?.trim().orEmpty()
        if (key.isEmpty()) return null
        if (key in keys) return key
        return keys.firstOrNull { it.equals(key, ignoreCase = true) }
    }

    /**
     * 单行归一。名字三段回落：slug(名) → slug(角色) → 丢行；团队内撞名在**这一步**去重
     * （保存期才报 `DUPLICATE_IN_TEAM` 等于让用户替 LLM 擦屁股）；驱动不在位按 kind 换
     * 调用方兜底键（worker/reviewer 各一个，兜底对由调用方保证异厂商）；角色空白用缺省。
     */
    private fun normalizeRow(
        rawName: String?,
        rawRole: String?,
        rawPrompt: String?,
        rawDriver: String?,
        rawKind: String?,
        rawWorkerFlag: Boolean?,
        leaderName: String,
        driverKeys: Set<String>,
        fallbackWorkerDriver: String?,
        fallbackReviewerDriver: String?,
        defaultRole: String,
        takenNames: MutableSet<String>,
        newAgentId: (String) -> String,
    ): TeamScaffoldSpec.Member? {
        val role = rawRole?.trim()?.take(Limits.TEAM_ROLE).orEmpty()
            .ifBlank { defaultRole }
        var name = AgentMemberName.slug(rawName)
        if (name.isBlank()) name = AgentMemberName.slug(role)
        if (name.isBlank() || name == AgentMemberName.slug(leaderName)) return null
        name = AgentMemberName.unique(name, takenNames)
        takenNames += name
        val kind = normalizeKind(rawKind, rawWorkerFlag)
        val driver = canonicalKey(rawDriver, driverKeys)
            ?: (if (kind == TeamExecutionKindsUi.WORKER) fallbackWorkerDriver else fallbackReviewerDriver)
            ?: return null
        return TeamScaffoldSpec.Member(
            agentId = newAgentId(name),
            name = name,
            role = role,
            driverKey = driver,
            kind = kind,
            prompt = rawPrompt?.trim()?.take(PROMPT_MAX)?.ifBlank { null },
        )
    }

    /**
     * 恰 1 worker（编译期硬约束的生成侧镜像）：0 ⇒ 首行升 worker；多 ⇒ 降级成 reviewer。
     * reviewer 与 worker 同驱动（同厂商）会被执行档保存校验拒绝 ⇒ 在这一步换兜底键，
     * 兜底键也救不回来就如实保留（让保存期的结构化原因说话，不静默造一版看似合法的档）。
     */
    private fun keepReviewersOffWorkerDriver(
        rows: List<TeamScaffoldSpec.Member>,
        reviewerEscapeDriver: String?,
    ): List<TeamScaffoldSpec.Member> {
        val worker = rows.first { it.kind == TeamExecutionKindsUi.WORKER }
        return rows.map { m ->
            if (m.kind != TeamExecutionKindsUi.REVIEWER ||
                m.driverKey != worker.driverKey ||
                reviewerEscapeDriver == null
            ) {
                m
            } else {
                m.copy(driverKey = reviewerEscapeDriver)
            }
        }
    }

    /** 恰 1 worker 的执行侧镜像：0 ⇒ 首行升 worker；多 ⇒ 降级成 reviewer。 */
    private fun enforceSingleWorker(rows: List<TeamScaffoldSpec.Member>): List<TeamScaffoldSpec.Member>? {
        if (rows.isEmpty()) return null
        val workers = rows.filter { it.kind == TeamExecutionKindsUi.WORKER }
        return when {
            workers.isEmpty() -> rows.mapIndexed { i, m ->
                if (i == 0) m.copy(kind = TeamExecutionKindsUi.WORKER) else m
            }
            workers.size == 1 -> rows
            else -> {
                val keep = workers.first().agentId
                rows.map { m ->
                    if (m.kind == TeamExecutionKindsUi.WORKER && m.agentId != keep) {
                        m.copy(kind = TeamExecutionKindsUi.REVIEWER)
                    } else {
                        m
                    }
                }
            }
        }
    }

    /** 从可能带 markdown 代码栅栏 / 前后噪声的回复里抠出第一个 JSON 对象。 */
    private fun parseJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * 「AI 建队」生成桥（`:app` 只定形状；实现住编排层，`DesktopExtension` 装配）。
 *
 * 契约是**「尽最大努力给一份可建方案」**：实现方内部先走 LLM（TeamBuilderLlm）→
 * 过门（[TeamScaffoldParser]），失败自己落回落模板（[TeamScaffoldFallback]）——
 * UI 只面对「null = 生成不了」一个出口，不感知 LLM 与模板的分层。
 * 与 `teamExecutionPort` 同一条装配纪律：手机/预览不装配 = 界面不画入口。
 */
fun interface TeamScaffoldBridge {
    suspend fun generate(
        need: String,
        leaderKeys: Set<String>,
        defaults: TeamScaffoldDefaults,
    ): TeamScaffoldSpec?
}

/**
 * 建队方案的**本地化缺省**（cjkLiteralGate：缺省文案由 UI 传参进来，编排层源码
 * 不放 CJK 字面量）。
 *
 * @param teamTitle 队名缺省（LLM 没给队名 / 需求为空时用）。
 * @param role 角色标签缺省（LLM 某行没给 role 时用）。
 * @param roles 回落模板三棒的角色标签（编码/测试/审查）；不足 3 个时模板用 ASCII 形状
 *   （coder/tester/reviewer）—— 落模板是降级路径，标签可读性让位于可用性。
 */
data class TeamScaffoldDefaults(
    val teamTitle: String,
    val role: String,
    val roles: List<String> = emptyList(),
)

/**
 * 回落模板：LLM 不可用（无 key / 超时 / 结构不合法）时的**确定性**建队方案。
 *
 * 固定 `coder + tester + reviewer` 三人（1 worker + 2 reviewer，全链可跑）：不读需求关键词
 * 做花式拆解 —— 回落的意义在"稳定可用"，不在"聪明"。驱动对（worker/reviewer）由调用方
 * 从在位驱动里挑**异厂商**的一对传入；任一为 null = 无法组成合法执行档，返回 null。
 */
object TeamScaffoldFallback {

    /**
     * 回落模板的内置人设（ASCII —— 这三段是**执行指令**不是 UI 文案，本地化让位
     * 可用性）。worker 覆盖「调研→实现→自测→报告」的阶段化指引；reviewer 保持
     * 默认审查模板已覆盖的形状，只加「逐条 + VERDICT」的硬要求；tester 拿的是
     * **执行验证**文案（跑命令、以证据说话）而不是审查文案 —— 三根棒里它和
     * reviewer 同为 REVIEWER kind，靠槽位区分，同一段文案会让「测试」棒从不执行
     * 任何验证，与「审查」棒说同样的话。
     */
    private const val FALLBACK_WORKER_PROMPT =
        "Investigate the workspace before changing anything. Implement the goal, " +
            "then run the available build/test commands and fix what they expose. " +
            "Report what changed and what you verified."

    private const val FALLBACK_TESTER_PROMPT =
        "Verify the goal by actually running the available build/test commands against " +
            "the workspace. Execute the checks, report each command you ran and its result " +
            "as evidence, list any failure item by item, and end with an explicit final " +
            "verdict line. Do not rewrite the implementation."

    private const val FALLBACK_REVIEWER_PROMPT =
        "Review the current workspace against the goal. Give findings item by item, " +
            "a problem list, and an explicit final verdict line. Do not rewrite the implementation."

    fun template(
        need: String,
        leaderAgentId: String,
        workerDriver: String,
        reviewerDriver: String,
        defaults: TeamScaffoldDefaults,
        newAgentId: (String) -> String,
    ): TeamScaffoldSpec? {
        if (workerDriver.isBlank() || reviewerDriver.isBlank()) return null
        val teamTitle = need.trim().take(12).ifBlank { defaults.teamTitle.trim().take(12) }
        val base = AgentMemberName.slug(teamTitle).ifBlank { "dev" }
        val labels = defaults.roles.map { it.trim() }.filter { it.isNotEmpty() }
        val taken = mutableSetOf<String>()
        fun row(slug: String, role: String, driver: String, kind: String, prompt: String): TeamScaffoldSpec.Member {
            val unique = AgentMemberName.unique("$base-$slug", taken)
            taken += unique
            return TeamScaffoldSpec.Member(
                agentId = newAgentId(unique),
                name = unique,
                role = role,
                driverKey = driver,
                kind = kind,
                // 回落模板也带内置人设：AI 建队与回落模板的差距在「聪明」，不在
                // 「有没有人设」—— 没人设的棒只会收到裸 goal（§workerInstruction）。
                prompt = prompt,
            )
        }
        return TeamScaffoldSpec(
            teamTitle = teamTitle,
            leaderAgentId = leaderAgentId,
            members = listOf(
                row("coder", labels.getOrElse(0) { "coder" }, workerDriver, TeamExecutionKindsUi.WORKER, FALLBACK_WORKER_PROMPT),
                row("tester", labels.getOrElse(1) { "tester" }, reviewerDriver, TeamExecutionKindsUi.REVIEWER, FALLBACK_TESTER_PROMPT),
                row("reviewer", labels.getOrElse(2) { "reviewer" }, reviewerDriver, TeamExecutionKindsUi.REVIEWER, FALLBACK_REVIEWER_PROMPT),
            ),
        )
    }
}
