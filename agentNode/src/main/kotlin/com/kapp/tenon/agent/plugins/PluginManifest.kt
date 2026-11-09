// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.schedule.CronExpression
import com.kapp.tenon.agent.schedule.MissedFirePolicies
import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.GroupWorkspaceInfo
import com.kapp.tenon.core.protocol.TeamMemberInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 插件清单（方案 §2.2）。真插件把它写在插件目录的 `manifest.json` 里；注册时原样
 * POST 给宿主。结构与群协议里的 [com.kapp.tenon.core.protocol.VirtualAgentMember]
 * 共用 [TeamMemberInfo] / [GroupWorkspaceInfo] 两个引用类型 —— 身份与工作区条目
 * 只有一套形状，注册进来的插件与广播出去的群成员不会各说各话。
 *
 * `leader` / `members` 的 `agentId` 在注册时按 [PluginRegistration.validateAgentId]
 * 校验（slug 字符集 + 长度预算）—— 这是**发送侧不变量**的一部分：超预算的 agentId
 * 产出的 senderId 会被接收端整帧拒收，而发送侧拿不到任何错误。在入驻这一刻拒绝，
 * 比上线之后「对方没收到」便宜得多。
 */
@Serializable
data class PluginManifest(
    /** 插件 id；同时是数据目录下插件目录的名字，因此字符集收得很紧（见 [PluginRegistration.PLUGIN_ID_PATTERN]）。 */
    val pluginId: String,
    /** 展示名（群内 @ 的名字走 leader/members 的 name，不是这个）。 */
    val name: String,
    val version: String = "0.0.0",
    /** `"single"` 单兵 | `"team"` 协同团队（对外单一窗口 = leader）。 */
    val type: String = "single",
    /** 托管模式的启动命令（如 `node ./dist/index.js`）；守护进程模式可不填。 */
    val entrypoint: String? = null,
    /** 团队唯一对外发言人（被 @ 的那个）。必填。 */
    val leader: TeamMemberInfo? = null,
    /** 组员清单；对外不可见，组员间交互走插件内部通道。 */
    val members: List<TeamMemberInfo> = emptyList(),
    /** 机主可授权给本插件的工作区白名单（路径不出宿主，见 [GroupWorkspaceInfo]）。 */
    val allowedWorkspaces: List<GroupWorkspaceInfo> = emptyList(),
    /** 默认工作区 id。 */
    val defaultWsId: String? = null,
    /** 能力声明；宿主驱动的 listCapabilities 读这里，不经插件进程。 */
    val capabilities: List<String> = emptyList(),
    /**
     * 触发器声明（提案 §5，阶段三增量）。空 = 纯会话驱动插件（向后兼容——旧清单
     * 不写这个键，严格解析照常通过）。校验见 [PluginRegistration.validate] 的
     * triggers 分支；宿主级事实（webhook 密钥、回投会话存在性）在注册入口的
     * host-facts 钩子里校验。
     */
    val triggers: List<PluginTriggerDef> = emptyList(),
)

/**
 * 插件注册请求的解析与校验（`POST /v1/plugin/register` 的 body → [PluginManifest]）。
 *
 * 判据全部来自既有事实，不新造第二套规则：
 *  - pluginId 字符集 = 数据目录安全子集（它同时是插件目录名）；
 *  - agentId 校验 = [AgentSenderId.slugForSenderId] 的字符集与 [AgentSenderId.MAX_AGENT_ID]
 *    长度预算（`AgentSenderIdShapeTest` 钉住的同一契约）；
 *  - 身份去重按 slug —— `echo lead` 与 `echo-lead` 是同一个 senderId，注册成两个
 *    组员等于把「两台设备合并成一个人」的缺陷在清单层面重演。
 */
object PluginRegistration {

    /** pluginId 同时是数据目录下的目录名：只许小写字母数字与连字符，且不以连字符开头。 */
    val PLUGIN_ID_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

    /** Jitter 幅度上限（§2.3 常规 ±30s；300s 封顶防荒谬清单）。 */
    const val MAX_JITTER_SECONDS: Int = 300

    const val TYPE_SINGLE: String = "single"
    const val TYPE_TEAM: String = "team"
    private val TYPES: Set<String> = setOf(TYPE_SINGLE, TYPE_TEAM)

    /**
     * 严格模式解析器：未知键一律抛错，绝不静默丢弃。旧配置 ignoreUnknownKeys=true
     * 时，开发者声明 `triggers` 这类尚不支持的字段会「注册成功但永不生效」——
     * 发送侧一切正常、接收侧什么都没有的清单版翻版（提案 §5 WARNING）。
     */
    private val json = Json { ignoreUnknownKeys = false }

    /** 清单顶层合法键 = [PluginManifest] 的字段集。新增字段时这里与 data class 同步改。 */
    private val KNOWN_TOP_LEVEL_KEYS: Set<String> = setOf(
        "pluginId", "name", "version", "type", "entrypoint", "leader",
        "members", "allowedWorkspaces", "defaultWsId", "capabilities", "triggers",
    )

    sealed interface Verdict {
        /** 注册通过；[agentSenderIds] 是 leader + members 的完整 senderId（host 前缀已带上）。 */
        data class Accepted(val manifest: PluginManifest, val agentSenderIds: List<String>) : Verdict

        /** 注册被拒；[error] 是机器可读码，[message] 给出可操作的修正方向。 */
        data class Rejected(val error: String, val message: String) : Verdict
    }

    fun parse(manifestElement: JsonElement, hostNodeId: String): Verdict {
        // 顶层未知键前置扫描：给出专用错误码与可操作指引。严格解码也能兜住它
        // （kotlinx 会抛 unknown key），但那是一条嵌在序列化异常里的长消息——
        // 开发者配错了字段值得一个一眼能懂的独立错误码。
        (manifestElement as? kotlinx.serialization.json.JsonObject)?.let { obj ->
            val unknown = obj.keys.filter { it !in KNOWN_TOP_LEVEL_KEYS }
            if (unknown.isNotEmpty()) {
                return Verdict.Rejected(
                    "unknown-field",
                    "manifest has unsupported keys: " + unknown.joinToString(", ") +
                        "; this host version would silently ignore them — remove them or upgrade the host " +
                        "(declarations that look meaningful but do nothing are worse than rejection)",
                )
            }
        }
        val manifest = runCatching {
            json.decodeFromJsonElement(PluginManifest.serializer(), manifestElement)
        }.getOrElse {
            return Verdict.Rejected(
                "bad-manifest",
                "manifest is not a valid plugin manifest (§2.2): " + (it.message ?: "parse error"),
            )
        }
        validate(manifest)?.let { return it }
        val refs = listOfNotNull(manifest.leader) + manifest.members
        val senderIds = refs.map { AgentSenderId.of(hostNodeId, it.agentId) }
        return Verdict.Accepted(manifest, senderIds)
    }

    /** 结构校验；null = 通过。错误信息都写清「为什么拒」与「怎么改」。 */
    fun validate(manifest: PluginManifest): Verdict.Rejected? {
        if (!PLUGIN_ID_PATTERN.matches(manifest.pluginId)) {
            return Verdict.Rejected(
                "bad-plugin-id",
                "pluginId must match " + PLUGIN_ID_PATTERN.pattern + " (it doubles as a directory name " +
                    "under the host data dir); got \"" + manifest.pluginId + "\"",
            )
        }
        if (manifest.name.isBlank()) {
            return Verdict.Rejected("bad-name", "manifest.name must not be blank")
        }
        if (manifest.type !in TYPES) {
            return Verdict.Rejected(
                "bad-type",
                "type must be " + TYPES.joinToString(" / ") + "; got \"" + manifest.type + "\"",
            )
        }
        val leader = manifest.leader
            ?: return Verdict.Rejected(
                "missing-leader",
                "manifest.leader is required: the team's single outward voice is what users @ in the group",
            )
        validateAgentId(leader.agentId)?.let { return Verdict.Rejected("bad-agent-id", it) }
        val slugs = HashSet<String>()
        slugs += AgentSenderId.slugForSenderId(leader.agentId)
        manifest.members.forEach { member ->
            validateAgentId(member.agentId)?.let { return Verdict.Rejected("bad-agent-id", it) }
            val slug = AgentSenderId.slugForSenderId(member.agentId)
            if (!slugs.add(slug)) {
                return Verdict.Rejected(
                    "duplicate-agent-id",
                    "agentId \"" + member.agentId + "\" normalizes to the same sender id as an existing member " +
                        "(\"" + slug + "\"); two members cannot share one identity",
                )
            }
        }
        manifest.capabilities.forEach { cap ->
            if (cap.isBlank()) {
                return Verdict.Rejected("bad-capability", "capabilities entries must not be blank")
            }
        }
        manifest.allowedWorkspaces.forEach { ws ->
            if (ws.wsId.isBlank() || ws.title.isBlank()) {
                return Verdict.Rejected("bad-workspace", "allowedWorkspaces entries need both wsId and title")
            }
        }
        manifest.defaultWsId?.let { d ->
            if (d.isBlank()) return Verdict.Rejected("bad-workspace", "defaultWsId must not be blank when present")
        }
        // triggers 校验（提案 §5.2）。结构性判据在这里；宿主级事实（webhook 密钥、
        // 回投会话存在性）在注册入口的 host-facts 钩子——两道都是 fail-closed。
        val triggerIds = HashSet<String>()
        manifest.triggers.forEach { trigger ->
            if (!PLUGIN_ID_PATTERN.matches(trigger.id)) {
                return Verdict.Rejected(
                    "bad-trigger-id",
                    "trigger id must match " + PLUGIN_ID_PATTERN.pattern + "; got \"" + trigger.id + "\"",
                )
            }
            if (!triggerIds.add(trigger.id)) {
                return Verdict.Rejected("duplicate-trigger-id", "trigger id \"" + trigger.id + "\" appears twice; ids must be unique within one manifest")
            }
            validateTrigger(trigger)?.let { return it }
        }
        return null
    }

    /** 单个 trigger 的结构校验（§5.2 字段约束）；null = 通过。 */
    private fun validateTrigger(trigger: PluginTriggerDef): Verdict.Rejected? {
        if (trigger.name.isBlank()) {
            return Verdict.Rejected("bad-trigger-name", "trigger \"" + trigger.id + "\" needs a non-blank name")
        }
        if (trigger.type.equals("fileWatch", ignoreCase = true)) {
            return Verdict.Rejected(
                "bad-trigger-type",
                "trigger \"" + trigger.id + "\": fileWatch is a candidate form, not in the roadmap yet " +
                    "(it would be silently ignored without this check); declare \"cron\" or \"webhook\"",
            )
        }
        if (trigger.type !in TriggerTypes.VALUES) {
            return Verdict.Rejected(
                "bad-trigger-type",
                "trigger \"" + trigger.id + "\": type must be " + TriggerTypes.VALUES.joinToString(" / ") +
                    "; got \"" + trigger.type + "\"",
            )
        }
        when (trigger.type) {
            TriggerTypes.CRON -> {
                val schedule = trigger.spec?.schedule
                if (schedule.isNullOrBlank()) {
                    return Verdict.Rejected(
                        "bad-trigger-spec",
                        "cron trigger \"" + trigger.id + "\" needs spec.schedule (5-field cron: minute hour day-of-month month day-of-week)",
                    )
                }
                try {
                    CronExpression.parse(schedule)
                } catch (e: IllegalArgumentException) {
                    return Verdict.Rejected("bad-cron", "trigger \"" + trigger.id + "\": " + e.message)
                }
                trigger.spec.timezone?.takeIf { it.isNotBlank() }?.let { tz ->
                    try {
                        java.time.ZoneId.of(tz)
                    } catch (_: Exception) {
                        return Verdict.Rejected(
                            "bad-timezone",
                            "trigger \"" + trigger.id + "\": spec.timezone \"" + tz + "\" is not an IANA zone id",
                        )
                    }
                }
                trigger.spec.jitterSeconds?.let { j ->
                    if (j < 0 || j > MAX_JITTER_SECONDS) {
                        return Verdict.Rejected(
                            "bad-jitter",
                            "trigger \"" + trigger.id + "\": spec.jitterSeconds must be in 0.." + MAX_JITTER_SECONDS + "; got " + j,
                        )
                    }
                }
                trigger.spec.missedFirePolicy?.takeIf { it.isNotBlank() }?.let { p ->
                    if (!MissedFirePolicies.isKnown(p)) {
                        return Verdict.Rejected(
                            "bad-missed-fire-policy",
                            "trigger \"" + trigger.id + "\": missedFirePolicy must be " +
                                MissedFirePolicies.VALUES.joinToString(" / ") + "; got \"" + p + "\"",
                        )
                    }
                }
            }

            TriggerTypes.WEBHOOK -> {
                val sourceId = trigger.spec?.sourceId
                if (sourceId.isNullOrBlank() || !PLUGIN_ID_PATTERN.matches(sourceId)) {
                    return Verdict.Rejected(
                        "bad-trigger-spec",
                        "webhook trigger \"" + trigger.id + "\" needs spec.sourceId matching " +
                            PLUGIN_ID_PATTERN.pattern + " (the <sourceId> of POST /v1/webhook/<sourceId>)",
                    )
                }
                trigger.spec.secretHeader?.takeIf { it.isNotBlank() }?.let { header ->
                    if (header.lowercase() !in TriggerSecretHeaders.ALLOWED) {
                        return Verdict.Rejected(
                            "bad-secret-header",
                            "trigger \"" + trigger.id + "\": secretHeader must be one of " +
                                TriggerSecretHeaders.ALLOWED.joinToString(" / ") +
                                " (it names which signature header the sender uses; it is not a way to register new headers)",
                        )
                    }
                }
            }
        }
        val prompt = trigger.dispatch.prompt
        if (prompt.isBlank()) {
            return Verdict.Rejected("bad-trigger-prompt", "trigger \"" + trigger.id + "\" needs a non-blank dispatch.prompt")
        }
        TriggerPromptKeys.PLACEHOLDER.findAll(prompt).forEach { match ->
            val key = match.groupValues[1]
            if (key !in TriggerPromptKeys.ALLOWED) {
                return Verdict.Rejected(
                    "bad-trigger-prompt",
                    "trigger \"" + trigger.id + "\": placeholder {{" + key + "}} is not allowed; " +
                        "only the six StandardSourceEvent fields " + TriggerPromptKeys.ALLOWED + " may be referenced",
                )
            }
        }
        if (trigger.dispatch.targetConvId.isBlank()) {
            return Verdict.Rejected(
                "bad-trigger-target",
                "trigger \"" + trigger.id + "\" needs dispatch.targetConvId (an existing group conversation on this host)",
            )
        }
        trigger.filter?.let { f ->
            if (f.types.any { it.isBlank() } || f.keywords.any { it.isBlank() }) {
                return Verdict.Rejected(
                    "bad-trigger-filter",
                    "trigger \"" + trigger.id + "\": filter entries must not be blank",
                )
            }
        }
        return null
    }

    /**
     * agentId 的 slug 校验：空（无法 slug 化）或超预算都拒。返回 null = 通过。
     *
     * 预算 = [AgentSenderId.MAX_AGENT_ID]（21）：`agent:` + hostNodeId(36) + `:` 之后
     * 剩下的字段空间。超了会被 `Limits.ID` 的整帧校验拒收 —— 发送侧无感知的「没收到」。
     */
    fun validateAgentId(agentId: String): String? {
        val slug = AgentSenderId.slugForSenderId(agentId)
        if (slug.isEmpty()) {
            return "agentId \"" + agentId + "\" cannot be normalized to a sender id " +
                "(all-non-ASCII names are rejected; keep at least the ASCII part)"
        }
        if (slug.length > AgentSenderId.MAX_AGENT_ID) {
            return "agentId \"" + agentId + "\" normalizes to " + slug.length +
                " chars, over the " + AgentSenderId.MAX_AGENT_ID +
                " char budget (sender frames over the limit are silently dropped by receivers)"
        }
        return null
    }
}
