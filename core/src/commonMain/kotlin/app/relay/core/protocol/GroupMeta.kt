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
    /**
     * nodeId → 昵称（**可选**，随 `group.info` 一起同步）。
     *
     * ## 为什么群元数据里要带成员昵称
     *
     * 群成员在每台设备上只有 nodeId。要让成员列表显示真名，此前只能靠**自己发现**那个节点
     * （同网段 mDNS / 曾经直连过）—— 而跨网场景下手机根本发现不了另外两个成员：
     * 它们既不在同一个局域网，也从来没有直接跟这台手机通过信。真机现场（红米走蜂窝）：
     * 群主正常（配对过），另外两个只显示 nodeId 前 8 位；而桌面端四个都正常 —— 因为它
     * 同一时刻在局域网里认识所有人。**同一个群，两端显示不一致**，且不一致的那一端看到的是
     * "一串十六进制"。这就是这一列的存在理由。
     *
     * ## 边界
     *
     * - **可选**：旧端不发它就是 null，成员行退回 nodeId 前 8 位（与以前一致，不更差）；
     * - **只用于显示**：不参与成员判定、不参与权限 —— 成员身份永远只认 [members] 与
     *   [ownerId] / [adminIds]，昵称是纯展示数据（伪造它没有任何特权收益）；
     * - 键必须属于 [members]（解码侧会按 members 过滤掉越界键）。
     */
    val memberNicks: Map<String, String>? = null,
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

/**
 * 群里一个可被 @ 的智能体（PPE-2 / Goal 5）。
 *
 * ## 身份是三元组
 *
 * `(hostNodeId, agentId, wsId)` —— 见 [app.relay.core.protocol.AgentMemberName] 的 KDoc：
 * 一台设备可以装多个 agent，不同 agent 也可能操作同一个 workspace，所以单项都不足以定位。
 *
 * ## `name` 是**暴露名**，不是显示名
 *
 * 它是 `@` 后面要打的那个 token（`codex-teahouse`），由 [AgentMemberName.base] 生成、
 * 由 [AgentMemberName.unique] 保证群内不冲突。**默认生成、不可手改**（加成员时不许冲突）。
 *
 * 与显示名的分工：气泡上显示什么由 `SenderIdentity.displayName` 决定（可以更漂亮、可以跟随
 * wsTitle），而 `@` 要打什么必须是稳定且 ASCII 的 —— 两者不必是同一个字符串。
 *
 * ## `wsId` 为 null 表示"没有绑定工作区"
 *
 * 这种 agent 仍然可以被 @（做问答、跑命令），只是没有"在哪个目录里干活"这回事。
 * 合法的一等状态，不是缺失。
 */
/**
 * 从群元数据里找出发送方 @ 到的那个 agent（用于「把手机的一句话交给它」）。
 *
 * ## 为什么这个判据要在 core 里
 *
 * 群元数据（`GroupMeta.virtualAgents`）是**协议事实**，四端共享；而「注入时投给哪个会话」
 * 必须先知道「@ 的是哪个 agent」。把这条放在 core，宿主侧（agentNode）与界面侧
 * （composeApp）用的是**同一份**判定 —— 各写一遍必然漂。
 *
 * ## 两种形态，按用户的话定
 *
 * 「添加 agent 进群的时候，要么这个 agent 就绑定了某个 workspace，要么就是没绑定。」
 *
 * - **绑了 workspace**（`wsId != null`）：那这个 agent 在群里的活儿都属于那个工作区。
 *   注入时按 `wsId` 找该工作区的会话。
 * - **没绑**（`wsId == null`）：它不属于任何工作区，注入只能投给**它自己的会话**
 *   （不带 wsId 的会话）。
 *
 * 两者都不猜「最近一个」：猜错的后果是 agent 在无关目录里执行命令。
 *
 * @param text 群消息正文（用来匹配 `@name`）。
 * @param agentNames 候选 token：群元数据里的 `VirtualAgentMember.name`。
 */
fun GroupMeta.mentionedVirtualAgent(text: String, tokens: List<String>): VirtualAgentMember? {
    val agents = virtualAgents.orEmpty()
    if (agents.isEmpty()) return null
    // 从长到短匹配：`@dsh-teahouse` 不该被 `@dsh` 抢先命中。
    val hit = tokens.sortedByDescending { it.length }.firstOrNull { token ->
        token.isNotBlank() && mentionsToken(text, token)
    } ?: return null
    return agents.firstOrNull { it.name.equals(hit, ignoreCase = true) }
}

/**
 * 正文里有没有出现 `@token`。
 *
 * 判据与 Tidings 侧 `isMentioningAgent` 同一条：`@` 之后按 **case-insensitive** 比，
 * 且要求 token 后面不是标识符字符（避免 `@dsh` 命中 `@dsh2`）。
 *
 * 为什么不在 core 里 import 界面层的实现：那一层在 composeApp，core 不能反向依赖。
 * 所以这里给一份**最小等价实现**，并用测试钉住它与界面层的行为一致（见
 * `GroupMentionTokenTest`）。这是本仓少见的「两份实现」，代价换 core 不依赖上层。
 */
/**
 * 这个字符算不算「标识符的延续」（用来区分 `@dsh` 与 `@dsh2`）。
 *
 * **只认 ASCII 字母、数字、下划线**。中文/日文/韩文等表意文字不算 ——
 * 中文用户写 `@dsh你好` 时，`@dsh` 是一个完整的 mention，不是 `dsh你` 这个 token。
 */
internal fun isAsciiIdentChar(c: Char): Boolean =
    c == '_' || (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')

internal fun mentionsToken(text: String, token: String): Boolean {
    if (token.isBlank()) return false
    val needle = "@" + token
    var from = 0
    while (true) {
        val at = text.indexOf(needle, from, ignoreCase = true)
        if (at < 0) return false
        val after = text.getOrNull(at + needle.length)
        // 后面是空/空白/标点 ⇒ 命中；是**标识符字符** ⇒ 那是另一个 token（如 `@dsh2`）。
        //
        // **「标识符字符」只算 ASCII**：中文是表意文字，不是 token 的延续。
        // 真机上踩过：中文群里用户必然写 `@dsh你好` 而不是 `@dsh 你好` ——
        // 而 `isLetterOrDigit` 对汉字返回 true，于是整条 @ 被判成「另一个 token」，
        // agentHint 恒为 null，用户看到的是「@ 了但没反应」。
        if (after == null || isAsciiIdentChar(after).not()) return true
        from = at + 1
    }
}

@Serializable
data class VirtualAgentMember(
    val agentId: String,
    val hostNodeId: String,
    /** `@` 后面要打的 token；群内唯一。见上面的 KDoc。 */
    val name: String,
    val role: String? = null,
    /**
     * 绑定的工作区 id；null = 不绑。
     *
     * **加可选键 ⇒ 旧节点整帧拒收**（`hasOnlyKeys` 判 extra-field）。本仓的取舍是
     * 两端必须同版本，不能悄悄加 —— 见 AGENTS.md §2.2。
     */
    val wsId: String? = null,
)

/**
 * 广播 `group.info` 前补齐**本机能提供的**那部分：成员昵称 + 本机声明。
 *
 * ## 为什么是纯函数
 *
 * 它原本长在 `RelayController.groupMetaWithNicks` 里、直接读全局状态，于是
 * 「null 时该保留还是该覆盖」这个判据**没法单独测**，只能靠在真机上撞见。
 * 撞见了两次：一次是昵称为空、一次是 [localAgents] 为 null。
 * 抽成纯函数后，两条都能用一个 `assertEquals` 钉住。
 *
 * ## 两条不可动摇的判据
 *
 * ① **[localAgents] 为 null 表示「本机没有要声明的」，必须保持原样。**
 *
 * 这不是可选的宽松处理：`virtualAgents` 里装的是**别人**声明进来的 agent。
 * 本机没有 agent 时把它覆盖成 null ⇒ 那台设备广播出去的元数据里 agent 就没了 ⇒
 * 所有收到它的成员都以为这个群没有 agent（真机现场：手机端群里 0 个 agent、
 * `@` 候选空、气泡署名退回 `dsh`）。而发送者自己本地还好好留着那一条 ——
 * 于是**两端对同一份元数据有两种说法**，正是本仓要消掉的分叉。
 *
 * ② **昵称为空时不覆盖已有的 `memberNicks`。**
 *
 * 同一个形状：拿不到不等于该抹掉。
 *
 * @param nicks 本机能提供昵称的那些成员；空表示「一个都不知道」
 * @param localAgents 本机声明；null 表示本机没有 agent（不是「清空」）
 */
fun GroupMeta.withLocalExtras(
    nicks: Map<String, String>,
    localAgents: List<VirtualAgentMember>?,
): GroupMeta {
    val nicksPart = if (nicks.isEmpty()) memberNicks else nicks
    val agentsPart = localAgents ?: virtualAgents
    if (nicksPart == memberNicks && agentsPart == virtualAgents) return this
    return copy(memberNicks = nicksPart, virtualAgents = agentsPart)
}
