// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.Serializable

/**
 * 群元数据（协议 §7.4），形状与 TS 侧 `src/shared/protocol.ts` 的 `GroupMeta` 逐字段对齐。
 *
 * 它既是**线格式**（`group{op:"info"}` 的 `group` 字段）也是本仓的**领域模型** ——
 * 与 `PeerUi` 那种"UI 投影"不同，群元数据没有第二层表示的必要：它的每个字段都是协议事实，
 * 界面只是挑了其中两个（[name]、[members]）来显示。
 *
 * ### rev 与 LWW
 * [rev] 单调递增，冲突按 `(rev, updatedTs)` 取大（last-write-wins）。**不是**"越大越新就
 * 一定收"：收端还要过权限矩阵，见 `app.relay.app.GroupMerge`。
 *
 * ### 必填 / 可选
 * 协议侧（`codec.ts` 的 `MSG_TYPES.group` 分支）只强制 [groupId]、[name]、[members]、
 * [rev]、[updatedBy]、[updatedTs]；其余全部可选，**旧包可以缺省**。所以这里一律给 `null`
 * 默认值 —— 给空串或空列表当默认值会踩到 `encodeDefaults = false`：一旦某个群真的
 * `adminIds = []`，键会被省略，而 TS 侧 `hasOwnerId !== hasAdminIds` 会因此**整帧拒收**。
 *
 * ### 我们不实现的那部分（如实写清）
 * [adminSecretHash] / [adminHint] / [avatarHash] 三个字段本仓**只透传、不使用**：没有群管理
 * 界面、没有自定义群头像。透传是必须的 —— 收到 `op:"need"` 时要把**手里这份**原样回给对方，
 * 少一个字段就等于我们替群主改了群。
 *
 * [ownerId] 与 [adminIds] 协议要求**成对出现**（都有或都没有）。本类不强制，因为它是
 * 可序列化模型（手改坏的 `settings.json` 不该让应用起不来）；强制发生在两处：入站校验
 * （`Codec` 的 group 分支）与出站构造（`GroupMeta.create`）。`GroupMetaPairingTest` 钉住。
 */
@Serializable
data class GroupMeta(
    val groupId: String,
    val name: String,
    val members: List<String>,
    val rev: Int,
    val updatedBy: String,
    val updatedTs: Long,
    /** 建群端 IPv4；无管理密码时仅该来源 IP 可管理群。我们不校验它，只透传。 */
    val creatorIp: String? = null,
    /** 建群者 nodeId；多网卡环境下用于无密码群管理校验。 */
    val creatorId: String? = null,
    /** 当前群主 nodeId。v0.43 起随 `group.info` 同步，旧包可缺省。 */
    val ownerId: String? = null,
    /** 管理员 nodeId 列表；必须属于 [members] 且不含 [ownerId]。旧包可缺省。 */
    val adminIds: List<String>? = null,
    /** 自定义群头像 SHA-256；空串表示默认群图标。本仓只透传。 */
    val avatarHash: String? = null,
    /** 管理密码摘要；空串表示无密码。本仓只透传，不实现带密码的群管理。 */
    val adminSecretHash: String? = null,
    /** 管理密码提示；仅用于 UI 展示，不参与鉴权。本仓只透传。 */
    val adminHint: String? = null,
    /** 群简介；空串表示未设置。 */
    val description: String? = null,
    /** 群公告；空串表示未设置。 */
    val announce: String? = null,
    /** 虚拟 Agent 群成员列表（PPE-2 / Goal 5）。 */
    val virtualAgents: List<VirtualAgentMember>? = null,
) {
    /** 判断指定 nodeId / agentId 是否属于虚拟 Agent。 */
    fun isVirtualAgent(nodeOrAgentId: String): Boolean =
        virtualAgents?.any { it.agentId == nodeOrAgentId || "agent:${it.hostNodeId}:${it.agentId}" == nodeOrAgentId } == true

    /** 获取虚拟 Agent 所属的宿主 nodeId。 */
    fun hostNodeOf(nodeOrAgentId: String): String? =
        virtualAgents?.firstOrNull { it.agentId == nodeOrAgentId || "agent:${it.hostNodeId}:${it.agentId}" == nodeOrAgentId }?.hostNodeId
    /** 自己是不是成员。不是成员的群不该留在本机（见 `GroupMerge.canApply`）。 */
    fun hasMember(nodeId: String): Boolean = members.contains(nodeId)

    /** 成员里除自己以外的那些 —— 逐成员单播时的收件人列表。 */
    fun recipientsExcluding(selfId: String): List<String> = members.filter { it != selfId }

    /** 校验管理密码。未设置管理密码时返回 true。 */
    fun verifyAdminSecret(secret: String): Boolean {
        val hash = adminSecretHash?.takeIf { it.isNotBlank() } ?: return true
        val secretHash = app.relay.core.crypto.sha256Hex(secret.encodeToByteArray())
        return secretHash.equals(hash, ignoreCase = true)
    }

    /** 更新管理密码及提示。 */
    fun withAdminSecret(secret: String?, hint: String?): GroupMeta {
        val hash = secret?.takeIf { it.isNotBlank() }?.let {
            app.relay.core.crypto.sha256Hex(it.encodeToByteArray())
        }
        return copy(adminSecretHash = hash, adminHint = hint?.takeIf { it.isNotBlank() })
    }

    companion object {
        /**
         * 建群：构造一份**协议上合法**的初始元数据。
         *
         * [members] 传的是**除自己以外**的成员；自己由这里放进第一位。这不是顺手为之：
         * `ownerId` 必须是 `members` 之一（`codec.ts:216-221`），而本方法同时把
         * `ownerId = selfId`，所以"自己不在成员表里"会让**对方整帧拒收** ——
         * 症状是"建了群，成员那边什么都没发生"，而且没有任何错误提示。
         *
         * `ownerId` 与 `adminIds` 在这里成对给出（[adminIds] 默认空列表而不是 null），
         * 就是为了不踩那个"只给一个"的坑 —— 它是本仓唯一构造 `rev = 1` 的地方。
         *
         * [creatorIp] 用本机在局域网里的地址（`Profile.host`）：TS 侧在"无管理密码"时
         * 只允许该来源 IP 管理群，给空串会让对方**完全管不了**这个群。给不出来的话
         * 传空串，代价如实写在文档里。
         */
        fun create(
            groupId: String,
            name: String,
            members: List<String>,
            selfId: String,
            updatedTs: Long,
            creatorIp: String = "",
            description: String = "",
        ): GroupMeta = GroupMeta(
            groupId = groupId,
            name = name,
            members = (listOf(selfId) + members).distinct(),
            rev = 1,
            updatedBy = selfId,
            updatedTs = updatedTs,
            creatorIp = creatorIp,
            creatorId = selfId,
            ownerId = selfId,
            adminIds = emptyList(),
            description = description,
        )
    }
}

/**
 * `group{op:"info"}`：全量元数据快照。
 *
 * 投递语义与普通消息一致（可靠 + 离线入队）——「成员回来即知道自己进了群」靠的就是这个。
 */
@Serializable
data class GroupInfoPayload(
    val op: String,
    val group: GroupMeta,
)

/** `group{op:"need"}`：我不认识这个群 / 本地版本落后，请把全量元数据发我。 */
@Serializable
data class GroupNeedPayload(
    val op: String,
    val groupId: String,
)

/** `group` 载荷的两个 `op` 取值。协议里是字面量，这里给常量以免三处各写一遍。 */
object GroupOps {
    const val INFO = "info"
    const val NEED = "need"
}

/** 虚拟 Agent 群成员元数据（PPE-2 / Goal 5）。 */
@Serializable
data class VirtualAgentMember(
    val agentId: String,
    val hostNodeId: String,
    val name: String,
    val role: String? = null,
)
