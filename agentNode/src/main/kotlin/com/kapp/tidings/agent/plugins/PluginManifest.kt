// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.plugins

import com.kapp.tidings.core.protocol.AgentSenderId
import com.kapp.tidings.core.protocol.GroupWorkspaceInfo
import com.kapp.tidings.core.protocol.TeamMemberInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 插件清单（方案 §2.2）。真插件把它写在插件目录的 `manifest.json` 里；注册时原样
 * POST 给宿主。结构与群协议里的 [com.kapp.tidings.core.protocol.VirtualAgentMember]
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

    const val TYPE_SINGLE: String = "single"
    const val TYPE_TEAM: String = "team"
    private val TYPES: Set<String> = setOf(TYPE_SINGLE, TYPE_TEAM)

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Verdict {
        /** 注册通过；[agentSenderIds] 是 leader + members 的完整 senderId（host 前缀已带上）。 */
        data class Accepted(val manifest: PluginManifest, val agentSenderIds: List<String>) : Verdict

        /** 注册被拒；[error] 是机器可读码，[message] 给出可操作的修正方向。 */
        data class Rejected(val error: String, val message: String) : Verdict
    }

    fun parse(manifestElement: JsonElement, hostNodeId: String): Verdict {
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
