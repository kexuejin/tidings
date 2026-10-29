// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.core.protocol.AGENT_SENDER_PREFIX
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.sameAgent
import com.kapp.tidings.core.protocol.AgentSenderId
import com.kapp.tidings.core.protocol.AgentMemberName

/**
 * 「这个身份是谁」的**唯一解析器**（气泡署名、气泡头像、Markdown 判定、群成员列表、
 * @ 候选、会话行标题全部走它）。
 *
 * ## 为什么必须只有一份
 *
 * 同一个 senderId 在不同界面里被各判一次，已经判出过三种答案：气泡署名能显示
 * 「DSH Agent」（[RelayUiState.senderName] 的兜底）、头像却当成未知人（`peer()` 查不到
 * `agent:<kind>`）、Markdown 又按「peer 是不是 agent」判成否 —— 同一条消息在三个地方
 * 三个身份。而群里 agent 的「成员身份」与「nodeId」本来就不是一回事：
 * agent 的**宿主**是群成员，agent 自己是宿主上的一个身份（`agent:<kind>`）。
 *
 * ## 名字优先级（一个事实一个来源）
 *
 * 1. 对端档案昵称（`profile.nick`，即 [PeerUi.nick]）；
 * 2. 本机给它的备注（[PeerUi.remark]）；
 * 3. 群元数据里同步过来的成员昵称（[GroupMeta.memberNicks]，跨网兜底）；
 * 4. **agent 的 kind / 宿主自报的 alias** —— 宿主昵称、群昵称都**不**参与这一档：
 *    用户要看到的是 `DSH`，不是那台 mac mini 的机器名；
 * 5. 最后才回落 nodeId 前 8 位，并且 [SenderIdentity.hasProfile] = false ——
 *    调用方要如实说「未获取到资料」，而不是把它当成一个真名。
 *
 * 第 4 档为什么要排在群昵称之前：群昵称来自群主的设备，它把 agent 的宿主节点当成
 * 普通成员登记了一个机器名；而 agent 名来自宿主自己宣告的 agent 身份，更新鲜也更具体。
 */

/** 显示名是从哪一档来的（界面据此决定要不要补一句「未获取到资料」）。 */
enum class DisplayNameSource {
    /** 对端档案里的昵称。 */
    Profile,

    /** 本机给对端起过的备注。 */
    Remark,

    /** 群元数据 `memberNicks` 同步过来的昵称。 */
    GroupNick,

    /** agent 的 kind / alias。 */
    AgentKind,

    /** 只知道 id，资料没到。 */
    FallbackId,
}

/**
 * 一个身份在界面上的完整投影。
 *
 * [id] 是**身份键**：真人 = nodeId，agent = `agent:<kind>`（AGENTS §2.4 的唯一来源
 * [com.kapp.tidings.core.protocol.AGENT_SENDER_PREFIX]，拼写只由发送侧决定）。
 */
data class SenderIdentity(
    val id: String,
    /** 永不为空；最后一档是 8 位 id，配合 [hasProfile] = false 使用。 */
    val displayName: String,
    val avatar: Int? = null,
    val isAgent: Boolean = false,
    /** agent 的 kind（`dsh` / `native` …）；真人恒 null。 */
    val agentKind: String? = null,
    /** 宿主自报的 agent alias（PPE-1 会话帧的 alias）；没有就 null。 */
    val agentAlias: String? = null,
    /**
     * 这个 agent 绑定的工作区**展示名**（`teahouse-mobile`）；null = 没有绑定。
     *
     * 只用于 `@` token 与显示，不参与身份判定 —— 身份始终是 `(host, kind)`。
     */
    val workspaceName: String? = null,
    /** 在线态：真人取对端，agent 取宿主；不知道 = null（不编）。 */
    val online: Boolean? = null,
    val isSelf: Boolean = false,
    /** 是否是**这个会话的参与者**（群成员；agent 依据宿主成员或群里说过话判定）。 */
    val isMember: Boolean = true,
    val nameSource: DisplayNameSource = DisplayNameSource.FallbackId,
    /** false = 只知道 id；界面要如实说「未获取到资料」。 */
    val hasProfile: Boolean = true,
) {
    /**
     * 插入 @ 时用的 token。
     *
     * ## 名字只有一份
     *
     * **就是 [displayName]。** 用户看到的名字、@ 候选显示的名字、点进去插入的名字、
     * 气泡上署的名字、判据要匹配的名字 —— 全是同一个字符串。
     *
     * 原先 agent 这条另算一遍 `AgentMemberName.token(agentKind, workspaceName)`，
     * 于是同一个 agent 有**两个名字**：列表显示 `DSH`、插入 `@dsh-teahouse-mobile`。
     * 真机症状：用户按看到的名字打 `@DSH`，判据认长 token ⇒「@ 了没反应」。
     *
     * 一份事实两个说法，正是本仓要消掉的那类分叉。
     *
     * 唯一例外：**没有任何名字时**（未登记的裸 agent）退回 `AgentMemberName.token`，
     * 否则会插出一个空的 `@`。
     */
    val mentionToken: String
        get() {
            val name = displayName.trim()
            if (name.isNotEmpty() && name != "Agent") return "@" + name
            // 没有任何可用名字：按 kind 拼一个，保证不是空 token。
            return "@" + AgentMemberName.token(agentKind, workspaceName)
        }

    /** 自己不该出现在 @ 候选里（给自己发通知没有意义）。 */
    val canMention: Boolean get() = !isSelf

    /** 群成员列表 / @ 候选上的副行：资料没到时如实说（文案由 UI 层给资源）。 */
    val needsProfileNote: Boolean get() = !hasProfile
}

/**
 * 解析所需的全部事实。
 *
 * 全部是**入参**而不是去读全局状态：解析是纯函数，测试才能把「同名不同源」的用例逐条钉住。
 *
 * @param agentKindsByHost 宿主 nodeId → 该宿主上 agent 的 kind（来自 agent-workspace /
 *   agent-session 帧）。一个宿主够了：D6 的 `agentId` 属于"同一宿主的同类多实例"，
 *   群里展示仍以 kind 为准。
 * @param agentAliasesByHost 宿主 nodeId → 宿主自报的 alias（优先于 kind 做显示名）。
 * @param agentSenderIds 这个会话里以 `agent:<host>:<id>` 署过名的发送者（消息里出现过就算成员）。
 */
data class SenderIdentityContext(
    val selfNodeId: String,
    /**
     * 本机的昵称（）。**peers 里没有自己**，所以必须单独给 ——
     * 否则群主（= 本机）在成员面板里只能显示 nodeId 前 8 位（真机反馈）。
     */
    val selfNick: String? = null,
    val peers: List<PeerUi> = emptyList(),
    val group: GroupMeta? = null,
    /**
     * 宿主 nodeId → 该宿主上**所有** agent 的 kind（小写）。
     *
     * **一台设备可以装多个 agent**（codex / opencode / dsh…），所以这里是集合而不是
     * 单值。以前用 `Map<String, String>` + `if (!out.containsKey(host))` 只留第一个，
     * 装了第二个 agent 之后 `@opencode` 就永远匹配不上 —— 触发判据会静默漏掉它。
     */
    val agentKindsByHost: Map<String, Set<String>> = emptyMap(),
    /**
     * **「一个裸 nodeId 要不要按 agent 渲染」—— 两个场景的答案相反，所以必须是显式开关。**
     *
     * ## 两个场景
     *
     * | 场景 | 一个 agent 宿主的裸 nodeId 该显示 |
     * |---|---|
     * | **群成员列表 / 详情** | **agent 名**（那一行代表「这台机器上的 agent」）|
     * | **消息署名** | **人的昵称**（那条消息是**人**说的）|
     *
     * ## 真身
     *
     * 用户在自己的 DSH 里打字，桌面端把那条 prompt 转发进群，`senderId` 是**用户的
     * nodeId**（这是对的 —— 说这句话的确实是人）。手机的 `agentKindsByHost` 里那个
     * nodeId 有 `dsh`（它确实能跑 agent），于是被渲染成 `dsh`：
     * **用户看到自己说的话署名成了 agent**。
     *
     * 判据错了方向：「这台机器**装了** agent」不等于「**这条消息是** agent 说的」。
     * 后者由 `senderId` 形态决定 —— `agent:<host>:<id>` 是 agent，裸 `<nodeId>` 是人。
     *
     * 默认 false：成员列表那条既有行为一个字节不变（它渲染成 agent 名是对的）。
     */
    val bareNodeIdIsHuman: Boolean = false,
    val agentAliasesByHost: Map<String, String> = emptyMap(),
    val agentSenderIds: Set<String> = emptySet(),
    /**
     * 宿主 nodeId + kind → 该 agent **当前活跃会话**的 `wsId`。
     *
     * 用途：**让单聊和群聊算出同一个名字。**
     *
     * 群里有 `virtualAgents[].name`（入驻时按 `AgentMemberName.base(kind, agentId, wsTitle, wsId)`
     * 生成）；单聊没有群元数据，但那个 agent 的会话自己带着 `wsId` ——
     * 用同一条规则就能算出同一个 `dsh-teahouse-mobile`。
     *
     * 缺了它，同一个 agent 在群里叫 `dsh-teahouse-mobile`、在单聊里叫 `dsh` ——
     * 又是两个名字，正是要消掉的那种分叉。
     */
    val agentWorkspaceByHostAndKind: Map<String, Map<String, String>> = emptyMap(),
    /**
     * 工作区 id → 展示名（`wsTitle`）。
     *
     * 用途：`virtualAgents` 只存 `wsId`，而 **@ token 要的是工作区的名字**
     * （`@dsh-teahouse-mobile`，不是 `@dsh-ws-e9b18063d183`）。
     * 缺了它就只能拿 wsId 凑 token —— 用户打不出来。
     */
    val workspaceTitles: Map<String, String> = emptyMap(),
)

/** 群成员面板的两段：先 agent，再人。 */
data class GroupParticipantRows(
    val agents: List<SenderIdentity>,
    val humans: List<SenderIdentity>,
) {
    /** 这个群里有没有 agent 成员（问题 1 的「agentInvolved」唯一判据）。 */
    val hasAgent: Boolean get() = agents.isNotEmpty()

    /** 触发判据要认的 agent kind 集合（小写，与 [SenderIdentity.mentionToken] 同源）。 */
    val agentKinds: Set<String>
        get() = agents.mapNotNull { it.agentKind?.trim()?.lowercase() }.filter { it.isNotEmpty() }.toSet()
}

/** 这条 senderId 是不是 agent 署的名（`agent:<kind>`）。 */
fun isAgentSenderId(senderId: String?): Boolean =
    senderId?.startsWith(AGENT_SENDER_PREFIX) == true

/** `agent:<kind>` → `kind`；不是 agent 署名的返回 null。 */
fun agentKindOfSenderId(senderId: String?): String? {
    if (!isAgentSenderId(senderId)) return null
    val rest = senderId!!.removePrefix(AGENT_SENDER_PREFIX).trim()
    // D6 的「虚拟 agent」形态 `agent:<hostNodeId>:<agentId>`：kind 不在这一层，
    // 交给 virtualAgents 台账按 agentId 解析，这里不猜它是哪一种 kind。
    if (rest.isEmpty() || rest.contains(':')) return null
    return rest
}

/**
 * agent 的显示名：**kind 优先，alias 只作兜底**，都没有才退回通用的 "Agent"。
 *
 * 为什么不是 alias 优先（真机教训）：alias 是宿主给**会话**起的别名，随会话变
 * （实测群里出现过 `#协同优化测试` 这种把会话标题当人名的形态）—— 用户要的是
 * 「这是哪个 agent」，`DSH` 比会话别名稳定得多。alias 只在 kind 缺失时兜底。
 *
 * ## 原样返回，**不再 `uppercase()`**
 *
 * 以前取大写是为了「群成员列表里 `DSH` 才像一个人名」。但那个场景已经被
 * `virtualAgents` 的暴露名取代了（`dsh-teahouse-mobile`）—— 现在只剩**没有名字的
 * 兜底路径**会走到这里（单聊、或宿主没上报工作区）。
 *
 * 而兜底路径若返回 `DSH`，`@` 却要打 `@dsh`（判据按小写匹配 kind）—— 又是两个名字。
 * 原样返回让显示名与 token 是同一个串。
 */
fun agentDisplayName(agentKind: String?, alias: String? = null): String {
    agentKind?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    alias?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    return "Agent"
}

/**
 * 解析一个发送者（气泡署名 / 头像 / Markdown 判定）。
 *
 * 返回 null 只有一种情况：senderId 缺失（旧端帧、系统行）。缺 id 就没有身份，
 * 界面不该编一个人出来。
 */
fun resolveSenderIdentity(senderId: String?, ctx: SenderIdentityContext): SenderIdentity? {
    val id = senderId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val agentPart = if (isAgentSenderId(id)) id.removePrefix(AGENT_SENDER_PREFIX).trim() else null
    if (agentPart != null) {
        if (!agentPart.contains(':')) {
            // **债#4：老形态 `agent:<kind>` 的读侧兼容分支已删。**
            //
            // 写侧经 `AgentSenderId.of` 早已只产完整形态 `agent:<hostNodeId>:<agentId>`；
            // 两段式只存在于旧构建写下的历史行。按「缺失身份」处理（与 senderId 缺失
            // 同一条路）—— 界面画「未知成员」，不编一个人出来。谁把两段式解析加回来，
            // `SenderIdentityTest.anOldShapedAgentIdIsNoLongerResolved` 会红。
            return null
        }
        // `agent:<hostNodeId>:<agentId>`：显示 agentId（宿主给 agent 起的身份名），
        // 绝不拿宿主节点昵称顶替（用户要的是 agent 名，不是那台机器的名字）。
            val hostNodeId = agentPart.substringBefore(':').trim().ifBlank { null }
            val agentId = agentPart.substringAfterLast(':').trim().ifBlank { null }
            // **大小写必须忽略**：真机里声明写的是 `DSH`（入驻时按 agentKind 兜底），
            // 而会话帧/发送者 id 报的是小写 `dsh`。原先这里是 `==`，于是
            //   `"DSH" == "dsh"` → false，拼出的 `agent:<host>:DSH` ≠ `agent:<host>:dsh` → false
            // ⇒ 找不到声明 ⇒ 气泡署名退回 `agentId` 原样（`dsh`），
            // 而群详情那条路径用了 ignoreCase（`resolveAgentOfHost`）显示的是长名。
            // 同一件事两处口径不同 ⇒ 用户看到「同一个 agent 两个名字」。
            //
            // 用 `sameAgent`（core 的判据，容忍 kind/id 精度差异）而不是自己拼字符串：
            // 「这条消息是哪个 agent 说的」与「@ 到的是哪个 agent」必须是同一套判据。
            val virtualName = ctx.group?.virtualAgents
                ?.firstOrNull { declared ->
                    sameAgent(agentId, null, declared.agentId) &&
                        (hostNodeId == null || declared.hostNodeId == hostNodeId)
                }
                ?.name
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            // 名字优先级：声明名（virtualAgents.name）> agentId **原样**。
            //
            // 这里不做 uppercase，与老形态 `agent:<kind>` 的 DSH 大写**不冲突**：
            // 老形态里 kind 是驱动类型（当产品名显示，全大写），而三段式里的 agentId
            // 是**宿主给这个 agent 起的名字**，用户起的名字不该被改大小写。
        val name = virtualName ?: agentId ?: "Agent"
        val hostPeer = hostNodeId?.let { ctx.peerOf(it) }
        return SenderIdentity(
            id = id,
            displayName = name,
            avatar = hostPeer?.avatar?.takeIf { it >= 0 },
            isAgent = true,
            agentKind = null,
            agentAlias = virtualName,
            online = hostPeer?.online,
            isSelf = false,
            isMember = ctx.isParticipant(hostNodeId),
            nameSource = DisplayNameSource.AgentKind,
            hasProfile = true,
        )
    }
    return resolveNodeIdentity(id, ctx)
}

/**
 * 解析一个 **nodeId**（真人对端、或 agent 宿主节点）。
 *
 * 与 [resolveSenderIdentity] 的分工：这条只认 nodeId，所以 agent 宿主在这里是
 * **agent 成员**（显示 agent 名），而不是"宿主那台设备"。群的成员表里 agent 的身份
 * 就是它的宿主节点，这正是用户看到的「入驻智能体显示成 mac mini」的修法所在。
 */
fun resolveNodeIdentity(nodeId: String, ctx: SenderIdentityContext): SenderIdentity {
    val id = nodeId.trim()
    val self = id == ctx.selfNodeId
    // 自己不在 `peers` 里（那是"发现到的对端"），但昵称是有的（`settings.nick`）。
    // 不退化成 peers 里的一条，而是现造一条只带昵称的记录 —— 这样"档案昵称"这一档
    // 对**自己**也成立，群主不会在成员面板里显示成 nodeId 前 8 位（真机反馈）。
    val selfProfile = if (self) {
        ctx.selfNick?.trim()?.takeIf { it.isNotBlank() }?.let {
            PeerUi(nodeId = id, nick = it, remark = "", online = true, avatar = -1, caps = emptyList())
        }
    } else {
        null
    }
    val peer = ctx.peerOf(id) ?: selfProfile
    // 显示名只取一个 kind（有几个就取排序后第一个，保证稳定）；
    // 触发判据那边用的是**整个集合**（agentKindsInGroup）。
    val agentKind = ctx.agentKindsByHost[id]?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    val agentAlias = ctx.agentAliasesByHost[id]?.trim()?.takeIf { it.isNotBlank() }
    // agent 宿主：显示 agent 名（第 4 档），**跳过**宿主昵称 / 群昵称那两档。
    // **CAP_AGENT（`peer.isAgent`）只说明这台机器"能跑 agent"，不说明"它的身份是 agent"**
    // （见 NodeUi.isAgent 的 KDoc）。桌面端同进程跑 :agentNode 就带这个 cap，于是
    // 群主那台在**别的设备**上看会被当成 agent、还回落到宿主昵称 —— 真机现场：
    // 手机端群成员面板里群主消失了，多了个叫 demo-mac-97 的"智能体"。
    // 所以这里只认**已知的 agent kind / alias**；只有 cap 的节点按人类渲染。
    // `bareNodeIdIsHuman`：消息署名场景下，一个**裸 nodeId** 是「人」，不是「这台机器上的 agent」。
    // 详见 `SenderIdentityContext.bareNodeIdIsHuman` 的 KDoc（用户在自己 DSH 里打的字
    // 被渲染成 `dsh` 的真身就在这）。成员列表仍走 agent 名那一支 —— 默认 false。
    if (!self && !ctx.bareNodeIdIsHuman && (agentKind != null || agentAlias != null)) {
        val name = when {
            agentKind != null || agentAlias != null -> agentDisplayName(agentKind, agentAlias)
            else -> firstProfileName(peer, id)?.first ?: id.take(8)
        }
        val source = if (agentKind != null || agentAlias != null) {
            DisplayNameSource.AgentKind
        } else {
            firstProfileName(peer, id)?.second ?: DisplayNameSource.FallbackId
        }
        return SenderIdentity(
            id = id,
            displayName = name,
            avatar = peer?.avatar?.takeIf { it >= 0 },
            isAgent = true,
            agentKind = agentKind,
            agentAlias = agentAlias,
            online = peer?.online,
            isSelf = false,
            isMember = ctx.isParticipant(id),
            nameSource = source,
            hasProfile = source != DisplayNameSource.FallbackId,
        )
    }
    val resolved = firstProfileName(peer, id)
    if (resolved != null) {
        return SenderIdentity(
            id = id,
            displayName = resolved.first,
            avatar = peer?.avatar?.takeIf { it >= 0 },
            isAgent = peer?.isAgent == true,
            online = peer?.online,
            isSelf = self,
            isMember = ctx.isParticipant(id),
            nameSource = resolved.second,
            hasProfile = true,
        )
    }
    val groupNick = ctx.group?.memberNicks?.get(id)?.trim()?.takeIf { it.isNotBlank() }
    if (groupNick != null) {
        return SenderIdentity(
            id = id,
            displayName = groupNick,
            isSelf = self,
            isMember = ctx.isParticipant(id),
            nameSource = DisplayNameSource.GroupNick,
            hasProfile = true,
        )
    }
    return SenderIdentity(
        id = id,
        displayName = id.take(8),
        isSelf = self,
        isMember = ctx.isParticipant(id),
        nameSource = DisplayNameSource.FallbackId,
        hasProfile = false,
    )
}

/** 群的成员面板 / @ 候选 / agentInvolved 的唯一来源。 */
fun groupParticipantRows(ctx: SenderIdentityContext): GroupParticipantRows {
    val group = ctx.group ?: return GroupParticipantRows(emptyList(), emptyList())
    val memberIds = group.members.distinct()
    // **群的 agent 列表只有一个来源：`virtualAgents` 声明。**
    //
    // `virtualAgents[].name` 是**暴露名**（`dsh-teahouse-mobile`），由 `AgentMemberName`
    // 生成、群内唯一 —— 它既是用户看到的名字，也是 `@` 要打的 token，还是气泡署名。
    // **一份事实一个说法。**
    //
    // 这里曾经还有两条来源，都删掉了：
    //
    //   · 「群成员里有 agent 宿主 ⇒ 把它所有 kind 都算进群」：那不是入驻。
    //     加一台设备进群不等于它的 agent 入驻了这个群 —— 用户从没为它起过名字，
    //     于是又得现编一个（`DSH`），两个名字的分叉就从这里涨出来。
    //   · 「消息里出现过 `agent:` 发送者 ⇒ 补进列表」：那更不是入驻。
    //     任何一条历史 agent 消息都会让它永久出现在「本群的 agent」里 ——
    //     真机上那个不存在的 `NATIVE`（本地驱动的占位身份）就是这么冒出来的。
    //
    // 想让它出现在群里 ⇒ 走「邀请新智能体入驻」，那一步才生成名字。
    val declaredAgents = group.virtualAgents.orEmpty()
    val agentsByIdentity = LinkedHashMap<String, SenderIdentity>()
    declaredAgents.forEach { declared ->
        val host = declared.hostNodeId
        // kind 优先取宿主上报的（`dsh`），退回声明里的 agentId —— 两者都决定
        // 「这是哪一类 agent」，而**名字**永远来自 `declared.name`。
        val kind = ctx.agentKindsByHost[host].orEmpty()
            .firstOrNull { it.equals(declared.agentId, ignoreCase = true) }
            ?: declared.agentId.takeIf { it.isNotBlank() }
            ?: ctx.agentKindsByHost[host].orEmpty().firstOrNull()
            ?: "agent"
        val identity = resolveAgentOfHost(host, kind, ctx)
        // 用 `getOrPut` 而不是 `putIfAbsent`：后者是 `java.util.Map` 的方法，
        // Kotlin 的 MutableMap 没有它 —— 在 commonMain 里会让 iOS 编译不过。
        // 「已存在就不覆盖」的语义由 getOrPut 的契约保证（命中时不执行 lambda）。
        agentsByIdentity.getOrPut(agentIdentityKey(identity)) { identity }
    }
    // **成员恒为人类**：`group.members` 里是节点的 nodeId（人的设备）。
    // 它托管的 agent 在 [agents] 那一栏单独出现，**不把宿主从成员里剔掉** ——
    // 剔掉就等于"你托管了 agent，你就不再是人"，群主会被这么判。
    val humans = memberIds
        .map { resolveNodeIdentity(it, ctx) }
        .sortedWith(compareByDescending<SenderIdentity> { it.isSelf }.thenBy { it.displayName.lowercase() })
    val agents = agentsByIdentity.values.sortedBy { it.displayName.lowercase() }
    return GroupParticipantRows(agents = agents, humans = humans)
}

/** 这个群里有哪些 agent kind（触发 / 抽取 prompt 用；小写）。 */

/** 某个宿主上的**某一个** agent（同一台设备可以有多个）。 */

/**
 * 成员面板里 agent 的**去重键**：同一个 agent 不论从哪条路径进来
 * （宿主枚举 / 消息发送者），都必须落到同一行。
 *
 * **不能用 alias**：宿主自报的 alias 对同一台设备上的多个 agent 是同一个值，
 * 会把 codex 与 opencode 合并成一行（实测：面板里只剩 codex，`@opencode` 匹配不上）。
 */
/** agent 在成员面板里的**去重键**。 */
private fun agentIdentityKey(identity: SenderIdentity): String {
    // 同一个 agent 有三条构造路径，`id` 形状不同：
    //
    // | 来源 | id |
    // |---|---|
    // | `resolveAgentOfHost`（宿主枚举） | `agent:<host>:<kind>` |
    // | 三段式消息 | `agent:<host>:<agentId>`（原样） |
    // | 老形态消息 | `agent:<kind>`（**没有 host**） |
    //
    // 指向同一个 agent 时必须落到同一行，否则 LazyColumn 两个 item 的 `key` 相同
    // ⇒ 直接崩（`Key "agent:...:dsh" was already used`，真机反馈）。
    //
    // 键以 **kind 为主**：老形态没有 host，但 kind 相同就足以说明是同一个 agent
    // （同一个群里同一个 kind 出现两次，一定是同一台宿主的两条来源，不是两个 agent ——
    // 真正的第二个 agent 是不同 kind，如 codex / opencode）。
    val name = identity.agentKind?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?: AgentSenderId.parse(identity.id)?.second?.lowercase()
        ?: identity.id
    return "agent:$name"
}

private fun resolveAgentOfHost(host: String, kind: String, ctx: SenderIdentityContext): SenderIdentity {
    val base = resolveNodeIdentity(host, ctx)
    val k = kind.trim().lowercase().takeIf { it.isNotBlank() } ?: return base
    // **名字只有一份：优先用群里登记的那一条。**
    //
    // `virtualAgents[].name` 是**暴露名**（`dsh-teahouse-mobile`），由
    // `AgentMemberName.base` 生成、`unique` 保证群内不冲突 —— 它才是用户在群里
    // 看到并 @ 的那个字符串。
    //
    // 原先这里用 `agentDisplayName(kind)`（= `DSH`），于是同一个 agent 有**两个名字**：
    // 列表显示 `DSH`、@ 插入 `@dsh-teahouse-mobile` —— 用户按看到的名字打 `@DSH`，
    // 判据却只认长 token，症状是「@ 了没反应」。一份事实两个说法，就是要消掉的那类分叉。
    //
    // ## 名字的三档，顺序固定
    //
    // ① **群里登记过的名字**（`virtualAgents[].name`）—— 最权威，群内已保证唯一；
    // ② **按同一条规则就地生成**：`AgentMemberName.base(kind, agentId, wsTitle, wsId)`。
    //    单聊没有群元数据，但那个 agent 的会话自己带 `wsId` —— 用同一套规则就能算出
    //    **同一个** `dsh-teahouse-mobile`。这是「名字只有一份」在单聊里的落地：
    //    同一个 agent，不管从群进还是从单聊进，看到的都是这个字符串。
    // ③ 都没有（宿主没上报工作区）→ 退回 kind/alias。
    val declaredName = ctx.group?.virtualAgents
        ?.firstOrNull { it.hostNodeId == host && it.agentId.equals(k, ignoreCase = true) }
        ?.name
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val generatedName = ctx.agentWorkspaceByHostAndKind[host]?.get(k)?.let { wsId ->
        val agentId = ctx.group?.virtualAgents
            ?.firstOrNull { it.hostNodeId == host && it.agentId.equals(k, ignoreCase = true) }
            ?.agentId
        AgentMemberName.base(k, agentId ?: k, ctx.workspaceTitles[wsId], wsId)
    }
    return base.copy(
        // 身份键必须带 kind：同一个宿主上的两个 agent 不能撞成一行。
        id = "agent:$host:$k",
        displayName = declaredName
            ?: generatedName
            ?: agentDisplayName(k, ctx.agentAliasesByHost[host]),
        agentKind = k,
        isAgent = true,
        // **不继承宿主的 isSelf**：那台机器是我自己的，但**托管在它上面的 agent 不是我**。
        // 不重置这一格时，`base`（宿主的身份）带着 `isSelf = true` 被拷贝进来，
        // 而 `canMention = !isSelf` 会把「我自己的 agent」从 @ 候选里排掉 ——
        // 用户看到的就是「群里 @ 列表只有人、没有 agent」（真机截图）。
        // 而自己那台机器托管的 agent 恰恰最需要能被 @：它说话时是独立的发送者。
        isSelf = false,
        // **不继承宿主的 alias**：alias 是"宿主自报的显示名"，同一个宿主上的两个 agent
        // 会拿到同一个值。去重键用的是 agentAlias，继承过来就会把 codex 与 opencode
        // 合并成一行（实测：面板里只剩 codex，`@opencode` 匹配不上）。
        agentAlias = null,
        // 这个 agent 在群里声明的默认工作区（`virtualAgents` 里按 host + kind 找）。
        // 找不到就是 null —— 那就是没有绑定 workspace 的 agent，@ 只用 kind。
        //
        // **这里要的是工作区的名字，不是它的 id。**
        // 原先直接传 `wsId`，于是 token 被拼成 `@dsh-ws-e9b18063d183` ——
        // 一串内部 id，用户不可能打得出来。`AgentMemberName.token` 期待的是
        // 「agent + workspace 名」（`@dsh-teahouse-mobile`）。
        workspaceName = ctx.group?.virtualAgents
            ?.firstOrNull { it.hostNodeId == host && it.agentId.equals(k, ignoreCase = true) }
            ?.defaultWsId
            ?.let { wsId -> ctx.workspaceTitles[wsId] },
    )
}
fun agentKindsInGroup(ctx: SenderIdentityContext): Set<String> =
    groupParticipantRows(ctx).agentKinds

/**
 * **单聊**里可以被 @ 的对象（群用 [groupParticipantRows]）。
 *
 * ## 为什么必须单独有一个
 *
 * @ 候选原来只从群成员派生（`groupParticipantRows`），于是**单聊里输 @ 什么都不弹** ——
 * 而单聊恰好是与 agent 对话的主场景。两处的「谁能被 @」判据必须同源，
 * 所以这里复用同一套：只认**已知 kind/alias** 的宿主（不认裸 `CAP_AGENT`，
 * 理由见 [agentHostsInGroup] 的 KDoc）。
 *
 * 对端不是 agent（没有已知 kind/alias）时返回空列表 —— 空就是空，不编一个候选。
 */
fun directChatMentionTargets(peerId: String, ctx: SenderIdentityContext): List<SenderIdentity> {
    if (ctx.agentKindsByHost[peerId] == null && ctx.agentAliasesByHost[peerId] == null) {
        return emptyList()
    }
    val kinds = ctx.agentKindsByHost[peerId].orEmpty()
    // 与 [groupParticipantRows] 同一个展开规则：一个宿主上的每个 kind 各算一个 agent。
    return if (kinds.isEmpty()) {
        listOf(resolveNodeIdentity(peerId, ctx))
    } else {
        kinds.sorted().map { kind -> resolveAgentOfHost(peerId, kind, ctx) }
    }
}

/** 这个群的成员里，agent 宿主节点都有谁（含本机；触发路由用）。 */
fun agentHostsInGroup(ctx: SenderIdentityContext): Set<String> {
    val group = ctx.group ?: return emptySet()
    // 判据必须与 [groupParticipantRows] 里那条**完全一致**：只认「已知 kind/alias」的宿主。
    //
    // 这里原来还认 `peer.isAgent`（CAP_AGENT）—— 那表示"这台机器能跑 agent"，不表示
    // "它托管的 agent 是哪一个"。两处判据一旦分叉，就会出现「成员面板说群里没 agent、
    // 而这里说有三个宿主」的自相矛盾；反过来更糟：面板里 agent 那一栏是空的，
    // 于是聊天页的 `agentSessions` 过滤结果也是空的 ⇒ **「正在思考」永远不显示**，
    // 只剩输入区那句「有成员不在线」（真机反馈 #3）。
    return group.members.filter { id ->
        ctx.agentKindsByHost[id] != null || ctx.agentAliasesByHost[id] != null
    }.toSet()
}

private fun SenderIdentityContext.peerOf(nodeId: String): PeerUi? =
    peers.firstOrNull { it.nodeId == nodeId }

private fun SenderIdentityContext.isParticipant(nodeId: String?): Boolean {
    if (group == null) return true
    return nodeId != null && group.members.contains(nodeId)
}

/**
 * 名字的第 1、2 档：档案昵称 → 本机备注。
 *
 * 备注排在昵称**之后**（与 [PeerUi.displayName] 的"备注优先"相反）：备注是本机起的
 * 外号，昵称是对端自己报的名字。群成员列表里显示对方报的名字，比显示我给 ta 起的外号
 * 更不容易让另一个用户看不懂；备注只在对端没报昵称时兜底。
 */
private fun firstProfileName(peer: PeerUi?, id: String): Pair<String, DisplayNameSource>? {
    peer?.nick?.trim()?.takeIf { it.isNotBlank() }?.let { return it to DisplayNameSource.Profile }
    peer?.remark?.trim()?.takeIf { it.isNotBlank() }?.let { return it to DisplayNameSource.Remark }
    return null
}
