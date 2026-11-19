// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.AgentMemberName
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.sameAgent
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
     * @param agentId 团队内部稳定 id（`m-<slug>-<rand>` 新建行）；**复用行 = 员工 `employeeId`**
     *   （方案 §3.4.1：复用是「引用」不是「快照」—— 名册行与执行档、员工档案共用此键）。
     * @param name `@` token（小写 ASCII slug）；复用行 = 档案 [TeamScaffoldCandidate.token]，
     *   **不是**档案中文名（群里的 `@` 按席位名匹配，写中文名等于让这一行派不出去）。
     * @param role 角色标签（编码/审查/测试…，自由文本）。
     * @param driverKey 执行驱动键（执行档保存校验要求 reviewer 与 worker **异厂商**，
     *   调用方给的兜底驱动对必须已满足这条）。
     * @param kind 取值域见 [TeamExecutionKindsUi]；整份方案**恰 1 worker**（编译期硬约束）。
     * @param reused 是否复用已有员工。UI 用它画来源药丸，**不许拿 `agentId` 是否以 `m-`
     *   开头来猜** —— 这是生成层的显式结论，不是 UI 的推断题。
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
        val reused: Boolean = false,
    )

    /** 名册行（团队档案写路径的入参形状）；新建行恒 [TeamMemberDraft.isNew]。人设随行落档。 */
    fun toMemberDrafts(): List<TeamMemberDraft> = members.map {
        TeamMemberDraft(agentId = it.agentId, name = it.name, role = it.role, isNew = true, prompt = it.prompt)
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
 * 解析产物 = 方案 + `use` 契约的**如实计数**（方案 §3.4.1）：跳过的行要数出来给用户看
 * （`N 行没找到对应的员工` / `N 行和领队或别的组员重了`），不许静默丢弃。
 */
data class TeamScaffoldDraft(
    val spec: TeamScaffoldSpec,
    /** `use` 指向的人池里没有 —— 丢行计数。 */
    val missedUseRows: Int = 0,
    /** `use` 指到领队本人 / 同一员工第二次 —— 它们**找到了**人，只是重复（单独计数，§4-14）。 */
    val conflictUseRows: Int = 0,
)

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
 *
 * ## `use` 契约（方案 §3.4.1，复用优先于新建）
 *
 * 组员行允许 `{"use":"<token|agentKey|label>", "kind":"…", "prompt":"…"}`：池里按
 * token → agentKey → label 精确匹配（[TeamScaffoldRoster.find]）。复用行
 * `agentId = employeeId`、`name = 档案 token`、`driverKey = 档案声明值`（直接读，不反解）、
 * `reused = true`（**不许重算**）。`use` 未命中 ⇒ **丢行并计数**，不新造；只有它同时给了
 * `name/role/driver` 才按新建处理。
 */
object TeamScaffoldParser {

    /** 解析失败 = 调用方回落模板；成功 = 归一后的可建方案 + `use` 计数。 */
    fun parse(
        raw: String,
        roster: TeamScaffoldRoster,
        driverKeys: Set<String>,
        fallbackWorkerDriver: String?,
        fallbackReviewerDriver: String?,
        defaultTeamName: String,
        defaultRole: String,
        vendorOf: (String) -> String? = { null },
        newAgentId: (slugName: String) -> String = ::newTeamScaffoldAgentId,
    ): TeamScaffoldDraft? {
        val root = parseJsonObject(raw) ?: return null
        val leaderKeys = roster.leaders.map { it.agentKey }.toSet()
        val leader = root.str("leader")?.let { key -> canonicalKey(key, leaderKeys) }
            ?: return null
        val teamTitle = (root.str("teamName") ?: defaultTeamName)
            .trim()
            .take(Limits.TEAM)
            .ifBlank { defaultTeamName.trim().take(Limits.TEAM).ifBlank { return null } }
        val takenNames = mutableSetOf<String>()
        val usedCandidates = mutableSetOf<String>()
        var missed = 0
        var conflicts = 0
        val rows = (root["members"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val useKey = obj.str("use")
            if (useKey != null) {
                // `use` 契约：找到人就复用，找不到**不新造**（丢行计数）；指到领队本人或
                // 同一员工第二次 = 找到了但重复（单独计数，§4-14），同样不静默顶掉。
                val candidate = roster.find(useKey) ?: run { missed++; return@mapNotNull null }
                if (candidate.agentKey == leader || candidate.agentKey in usedCandidates) {
                    conflicts++
                    return@mapNotNull null
                }
                usedCandidates.add(candidate.agentKey)
                val role = obj.str("role")?.trim()?.take(Limits.TEAM_ROLE)?.ifBlank { null }
                    ?: candidate.duty.ifBlank { defaultRole }
                // 复用行的名字必须是 token（群里 @ 得到），档案中文名只进预览灰字。
                val name = AgentMemberName.slug(candidate.token).ifBlank { return@mapNotNull null }
                    .let { AgentMemberName.unique(it, takenNames) }
                    .also { takenNames += it }
                val kind = normalizeKind(obj.str("kind"), (obj["worker"] as? JsonPrimitive)?.booleanOrNull)
                return@mapNotNull TeamScaffoldSpec.Member(
                    agentId = candidate.agentKey,
                    name = name,
                    role = role,
                    driverKey = candidate.driverKey,
                    kind = kind,
                    prompt = obj.str("prompt")?.trim()?.take(PROMPT_MAX)?.ifBlank { null },
                    reused = true,
                )
            }
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
        val enforced = ensureOneWorker(clamped) ?: return null
        if (enforced.isEmpty()) return null
        val workers = enforced.filter { m -> m.kind == TeamExecutionKindsUi.WORKER }
        val escapeVendorOk = fallbackReviewerDriver?.takeIf { key ->
            val vendor = vendorOf(key)
            vendor != null && workers.all { vendorOf(it.driverKey) != vendor }
        }
        val spec = TeamScaffoldSpec(
            teamTitle = teamTitle,
            leaderAgentId = leader,
            members = keepReviewersOffWorkerDriver(enforced, workers, escapeVendorOk, vendorOf),
        )
        return TeamScaffoldDraft(spec, missed, conflicts)
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
     * 至少 1 worker（生成侧兜底）：0 ⇒ 首行升 worker；**多 ⇒ 保留**（那是点将的候选池，
     * 主理人按任务挑一个下场）。降级成 reviewer 等于把模型写的「两个都能干」悄悄改成
     * 「只有一个人能干」，用户看不到这处改写。
     *
     * reviewer 与**任何一位** worker 同厂商（同驱动）都会被保存校验拒绝 ⇒ 在这一步换兜底键；
     * 复用行自带档案底座，同样过这条校验（vendorOf 判厂商，兜底键也救不回来就如实保留
     * —— 让保存期的结构化原因说话，不静默造一版看似合法的档）。
     */
    private fun keepReviewersOffWorkerDriver(
        rows: List<TeamScaffoldSpec.Member>,
        workers: List<TeamScaffoldSpec.Member>,
        reviewerEscapeDriver: String?,
        vendorOf: (String) -> String?,
    ): List<TeamScaffoldSpec.Member> {
        val workerVendors = workers.mapNotNull { vendorOf(it.driverKey) }.toSet()
        return rows.map { m ->
            val vendor = vendorOf(m.driverKey)
            val sameVendor = vendor != null && vendor in workerVendors
            if (m.kind != TeamExecutionKindsUi.REVIEWER ||
                !sameVendor ||
                reviewerEscapeDriver == null
            ) {
                m
            } else {
                m.copy(driverKey = reviewerEscapeDriver)
            }
        }
    }

    /** 至少 1 worker 的执行侧镜像：0 ⇒ 首行升 worker；多 ⇒ 原样保留（点将候选池）。 */
    private fun ensureOneWorker(rows: List<TeamScaffoldSpec.Member>): List<TeamScaffoldSpec.Member>? {
        if (rows.isEmpty()) return null
        if (rows.any { it.kind == TeamExecutionKindsUi.WORKER }) return rows
        return rows.mapIndexed { i, m ->
            if (i == 0) m.copy(kind = TeamExecutionKindsUi.WORKER) else m
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
 *
 * @param roster **候选池**（方案 §3.4.1）：员工档案 ∪ 本机卡片。池是生成层的唯一人选来源
 *   —— LLM 与回落模板都从这里挑人；`pool.none { it.canLead }` 时生成层返回 null
 *   （洞 B 契约：零员工没有领队，主行动是「新建员工」）。
 */
fun interface TeamScaffoldBridge {
    suspend fun generate(
        need: String,
        roster: TeamScaffoldRoster,
        defaults: TeamScaffoldDefaults,
    ): TeamScaffoldSpec?
}

/**
 * 复用池里的一名候选人（方案 §3.4.1）。
 *
 * @property agentKey 候选键：档案行 = `employeeId`（复用行的名册键，也是派单键）；
 *   未登记的本机卡片行 = 规范驱动键（kind 级）。
 * @property label 人看的名字（档案名 / 卡片显示名）—— 只进预览灰字，**不进名册**
 *   （名册行写 [token]，中文名派不出去）。
 * @property token 名册行的 `@` token（档案 [EmployeeProfile.effectiveToken] / 卡片名 slug）。
 * @property duty 归一职责（回落匹配的判据）。
 * @property driverKey 声明底座：档案显式字段；未登记卡片由 `resolveKey` 反解 ——
 *   解不出**不进池**（绝不猜）。空串永不出现。
 * @property online 卡片在线事实；档案行 null（没有运行时事实就如实说不知道，不冒充离线）。
 */
data class TeamScaffoldCandidate(
    val agentKey: String,
    val label: String,
    val token: String,
    val duty: String,
    val driverKey: String,
    val model: String? = null,
    val online: Boolean? = null,
) {
    /** `canLead` = 池里**有 `driverKey` 的那一行**，档案与卡片一视同仁（方案 §3.4.1）：
     *  不要求有卡片、不要求 online、不要求有 agentId、不要求有席位。 */
    val canLead: Boolean get() = driverKey.isNotBlank()
}

/**
 * 复用池：单表，**别存第二份 leaders**（派生，不是字段）。
 *
 * 合并判据 = `employeeId` 精确（[sameEmployee]，与 `employeeProfileOf` 同一把尺子）；
 * 没有 `employeeId` 的老数据回落 `sameAgent` 兼容（构建处 [teamScaffoldRoster]）。
 * 去重键不是名字。
 */
data class TeamScaffoldRoster(val pool: List<TeamScaffoldCandidate>) {

    /** 领队候选 = 池里 `canLead` 的行（派生，不是字段）。 */
    val leaders: List<TeamScaffoldCandidate> get() = pool.filter { it.canLead }

    /**
     * `use` 契约的匹配（方案 §3.4.1）：池里按 **token → agentKey → label** 精确匹配
     * （大小写不敏感）。null = 没找到 —— `use` 未命中**不新造**。
     */
    fun find(key: String?): TeamScaffoldCandidate? {
        val k = key?.trim()?.lowercase().orEmpty()
        if (k.isEmpty()) return null
        return pool.firstOrNull { it.token.lowercase() == k }
            ?: pool.firstOrNull { it.agentKey.lowercase() == k }
            ?: pool.firstOrNull { it.label.lowercase() == k }
    }
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
 * 复用池装配（方案 §3.4.1）：**员工档案 ∪ 本机卡片**，一视同仁。
 *
 * ## 两条过滤，各挡一类错
 *
 * - **解不出底座的不进池**（A7）：档案 `driverKey` 空时由 `agentId` 反解一次，解不出就
 *   如实缺席 —— 池里的行必须有明确底座，`canLead` 才立得住；绝不猜。
 * - **同一人不进两行**：合并判据 = `employeeId` 精确；卡片没带引用的老数据回落
 *   `sameAgent`（档案 agentId 与卡片键同 head 即同一人）。
 *
 * 卡片行给档案行补**在线事实**（预览的「当前离线」副行要的是真话）；卡片行自己
 * （未登记的本机 agent）token 取显示名 slug、duty 落单兵默认 —— 没登记也能被复用。
 */
public fun RelayController.teamScaffoldRoster(): TeamScaffoldRoster {
    val st = _state.value
    val self = st.selfNodeId
    val resolve = driverKeyResolveOf
        ?: return TeamScaffoldRoster(emptyList()) // 手机/预览：没有名册事实，池如实为空
    val cards = hubAgents(st.toAgentSlice(), st.toNodeSlice(), st.toSettingsSlice(), nowMillis())
        .filter { it.hostNodeId == self }
    val cardOnlineByEmployeeId = cards.filter { it.employeeId != null }.associateBy { it.employeeId!! }
    val profileRows = mutableListOf<TeamScaffoldCandidate>()
    val profileAgentIds = mutableListOf<String>()
    for (profile in st.settings.employeeProfiles) {
        val driver = profile.driverKey.ifBlank {
            profile.agentId.takeIf { it.isNotBlank() }?.let(resolve).orEmpty()
        }
        if (driver.isBlank()) continue // 解不出不进池：池里的行必须有明确底座（不猜）
        profileAgentIds.add(profile.agentId)
        profileRows.add(
            TeamScaffoldCandidate(
                agentKey = profile.employeeId,
                label = profile.name,
                token = profile.effectiveToken(),
                duty = profile.duty,
                driverKey = driver,
                model = profile.model,
                // 在线事实从卡片补（同一员工）：档案没有运行时事实，如实借卡片的。
                online = cardOnlineByEmployeeId[profile.employeeId]?.online,
            ),
        )
    }
    val claimedKeys = profileRows.map { it.agentKey.lowercase() }.toSet()
    val cardRows = cards.mapNotNull { card ->
        val key = card.agentKey?.trim().orEmpty()
        if (key.isEmpty()) return@mapNotNull null
        // 档案已覆盖的人不再出第二行（employeeId 精确；老数据 sameAgent 兼容）。
        val claimed = (card.employeeId?.lowercase() in claimedKeys) ||
            profileAgentIds.any { sameAgent(it, null, key) }
        if (claimed) return@mapNotNull null
        val driver = resolve(key) ?: return@mapNotNull null // 解不出不进池（绝不猜）
        if (driver.isBlank()) return@mapNotNull null
        TeamScaffoldCandidate(
            agentKey = key,
            label = card.displayName ?: key,
            token = AgentMemberName.slug(card.displayName).ifBlank { AgentMemberName.slug(key) }
                .ifBlank { key },
            duty = AgentDuties.DEFAULT_SINGLE,
            driverKey = driver,
            model = null,
            online = card.online,
        )
    }
    // token 池内去重：两条行撞 token 时后到者加后缀（`AgentMemberName.unique` 与建档同款）。
    val seen = mutableSetOf<String>()
    val pool = (profileRows + cardRows).map { row ->
        val unique = AgentMemberName.unique(row.token.lowercase(), seen)
        seen.add(unique)
        if (unique == row.token) row else row.copy(token = unique)
    }
    return TeamScaffoldRoster(pool)
}

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
        roster: TeamScaffoldRoster,
        workerDriver: String,
        reviewerDriver: String,
        defaults: TeamScaffoldDefaults,
        vendorOf: (String) -> String? = { null },
        newAgentId: (String) -> String = ::newTeamScaffoldAgentId,
    ): TeamScaffoldSpec? {
        if (workerDriver.isBlank() || reviewerDriver.isBlank()) return null
        // **领队也是池里挑的**（方案 §3.4.1：回落模板不许 `leaderKeys.first()`）：
        // 先按干活职责匹配（coder/ops/dev），再退而求其次取第一行可领队者 ——
        // 两档都是判据，不是"取列表第一个"。池空（无可领队）= 生成层直接 null（洞 B 契约）。
        val leader = roster.leaders.firstOrNull {
            it.duty.lowercase() in WORKER_SLOT_WORDS
        } ?: roster.leaders.firstOrNull() ?: return null
        val teamTitle = need.trim().take(12).ifBlank { defaults.teamTitle.trim().take(12) }
        val base = AgentMemberName.slug(teamTitle).ifBlank { "dev" }
        val labels = defaults.roles.map { it.trim() }.filter { it.isNotEmpty() }
        val taken = mutableSetOf<String>()
        val used = mutableSetOf(leader.agentKey)
        fun pick(slotWords: Set<String>, slotLabel: String, slotDriver: String, kind: String, prompt: String): TeamScaffoldSpec.Member {
            // 槽位先进池：按 duty 匹配（未用过的），再放宽到未用过的任何人 ——
            // 复用优先于新建；同厂商校验不过就换下一个（reviewer 必须 != worker 厂商）。
            val workerVendor = vendorOf(workerDriver)
            val candidate = roster.pool
                .filter { it.agentKey != leader.agentKey && it.agentKey !in used }
                .firstOrNull {
                    (slotWords.isEmpty() || it.duty.lowercase() in slotWords) &&
                        (kind != TeamExecutionKindsUi.REVIEWER ||
                            vendorOf(it.driverKey) == null || vendorOf(it.driverKey) != workerVendor)
                }
                ?: roster.pool
                    .filter { it.agentKey != leader.agentKey && it.agentKey !in used }
                    .firstOrNull {
                        kind != TeamExecutionKindsUi.REVIEWER ||
                            vendorOf(it.driverKey) == null || vendorOf(it.driverKey) != workerVendor
                    }
            if (candidate != null) {
                used += candidate.agentKey
                val unique = AgentMemberName.unique(
                    AgentMemberName.slug(candidate.token).ifBlank { "$base-$slotLabel" },
                    taken,
                )
                taken += unique
                return TeamScaffoldSpec.Member(
                    agentId = candidate.agentKey,
                    name = unique,
                    role = candidate.duty.ifBlank { slotLabel },
                    driverKey = candidate.driverKey,
                    kind = kind,
                    prompt = prompt,
                    reused = true,
                )
            }
            // 池里凑不出这一槽才新建（`m-<slug>-<rand>`，与手动建队同形）。
            val unique = AgentMemberName.unique("$base-$slotLabel", taken)
            taken += unique
            return TeamScaffoldSpec.Member(
                agentId = newAgentId(unique),
                name = unique,
                role = labels.getOrElse(SLOT_LABELS.indexOf(slotLabel).coerceAtLeast(0)) { slotLabel },
                driverKey = slotDriver,
                kind = kind,
                prompt = prompt,
            )
        }
        return TeamScaffoldSpec(
            teamTitle = teamTitle,
            leaderAgentId = leader.agentKey,
            members = listOf(
                pick(WORKER_SLOT_WORDS, "coder", workerDriver, TeamExecutionKindsUi.WORKER, FALLBACK_WORKER_PROMPT),
                pick(TESTER_SLOT_WORDS, "tester", reviewerDriver, TeamExecutionKindsUi.REVIEWER, FALLBACK_TESTER_PROMPT),
                pick(REVIEWER_SLOT_WORDS, "reviewer", reviewerDriver, TeamExecutionKindsUi.REVIEWER, FALLBACK_REVIEWER_PROMPT),
            ),
        )
    }

    /** 回落槽位的职责匹配词表（ASCII：duty 是归一后的 ASCII token）。 */
    private val WORKER_SLOT_WORDS = setOf("coder", "ops", "dev", "developer", "worker")
    private val TESTER_SLOT_WORDS = setOf("tester", "test", "qa")
    private val REVIEWER_SLOT_WORDS = setOf("reviewer", "review", "audit")
    private val SLOT_LABELS = listOf("coder", "tester", "reviewer")
}
