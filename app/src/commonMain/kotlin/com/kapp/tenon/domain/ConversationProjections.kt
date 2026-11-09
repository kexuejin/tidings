
// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.ConversationUi
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.domain.model.hasRenderableContent
import com.kapp.tenon.domain.model.visibleInDsh

/**
 * 会话列表的**切片投影**（Phase 4，蓝图 §4.4.3）：与 [RelayUiState.conversations] 逐字同义。
 *
 * 输入 chat / agent / node / settings 四片：agent 片是必须的 —— 列表里 agent 节点只有在
 * 「有可见会话或消息」时才算一条会话（旧实现在 getter 里直接调 `visibleAgentSessionsOf`）。
 */
fun conversations(
    chat: ChatSlice,
    agent: AgentSlice,
    node: NodeSlice,
    settingsSlice: SettingsSlice,
): List<ConversationUi> {
    val messages = chat.messages
    val conversationSummaries = chat.conversationSummaries
    val unreadByPeer = chat.unreadByPeer
    val pendingByPeer = chat.pendingByPeer
    val peers = node.peers
    val settings = settingsSlice.settings
    val selfNodeId = settings.nodeId

    fun peer(nodeId: String?): PeerUi? = nodeId?.let { id ->
        if (id == selfNodeId) {
            PeerUi(
                nodeId = selfNodeId,
                nick = settings.nick.ifBlank { selfNodeId.take(8) },
                platform = "",
                caps = listOf(MsgTypes.CAP_AGENT, MsgTypes.CAP_AGENT_WORKSPACE),
                online = true,
            )
        } else {
            peers.firstOrNull { it.nodeId == id }
        }
    }

    fun groupOfConv(convId: String?): GroupMeta? =
        convId?.let { ConvId.groupIdOf(it) }?.let { gid ->
            settings.groups.firstOrNull { it.groupId == gid }
        }

    fun convTitle(convId: String): String {
        groupOfConv(convId)?.name?.let { return it }
        if (!ConvId.isGroup(convId)) {
            // O1：与头部同一身份口径 —— 对端是 agent 宿主时列表行也显示 agent 名
            //（头部/署名/@候选同一份解析，§2.4 名字只有一份）。
            return resolveNodeIdentity(convId, senderContext(convId, chat, agent, node, settingsSlice)).displayName
        }
        return (ConvId.groupIdOf(convId) ?: convId).take(8)
    }

    fun memberOnlineCount(group: GroupMeta?): Int =
        group?.members.orEmpty().count { it != selfNodeId && peer(it)?.online == true }

    fun visibleAgentSessionsOf(peerId: String?): List<AgentSessionUi> =
        peerId?.let { id -> agent.agentSessions.filterKeys { it.peerId == id }.values }
            .orEmpty()
            .sortedWith(
                compareByDescending<AgentSessionUi> { session ->
                    val key = AgentSessionKey(session.peerId, session.sessionId)
                    val lastEventAt = agent.agentEvents[key]?.maxOfOrNull { it.at } ?: 0L
                    maxOf(session.sortActivityAt, lastEventAt)
                }.thenByDescending { it.startedAt ?: 0L }
                    .thenByDescending { it.sessionId },
            )
            .visibleInDsh()

            val real = peers.associateBy { it.nodeId }
            val groupsByConv = settings.groups.associateBy { ConvId.group(it.groupId) }
            // 会话摘要：会话列表的 id 集合、预览、最后消息时间全部来自它。
            //
            // 空 ⇒ 这个壳没接摘要（测试/预览/未接存储）：退回从已加载消息推导同一份形状，
            // 行为与改造前逐字一致。生产路径永远走 conversationSummaries。
            val summariesByConv: Map<String, ConversationSummaryRow> =
                if (conversationSummaries.isEmpty()) {
                    messages.mapNotNull { (convId, list) ->
                        // 与落库那份同一条判据：零正文的事件帧不算「最后一条」，
                        // 否则列表预览会落到它身上（null ⇒ 「还没聊过」）。
                        val last = list.lastOrNull { it.hasRenderableContent } ?: return@mapNotNull null
                        convId to ConversationSummaryRow(
                            convId = convId,
                            lastMessageId = last.id,
                            // 与落库那份（refreshSummary）共用同一份派生（conversationPreviewOf）：
                            // `<think>` 清洗 + 文件名兜底 —— 兜底路径也不许有第二份写法。
                            lastTextPreview = conversationPreviewOf(last.text, last.file?.name),
                            lastMessageAt = last.at,
                            lastMessageIsMine = last.mine,
                        )
                    }.toMap()
                } else {
                    conversationSummaries.associateBy { it.convId }
                }
            // 先认识到的对端在前，消息独有的会话排后面；去重靠 map，天然只留一条。
            val ids = LinkedHashSet<String>().apply {
                // 对端（task-42 ③）：**agent 节点**只有在「有可见会话」或「有消息」时才算
                // 一条会话。为什么只对 agent 收紧：agent 节点的全部内容都在 agentSessions
                // 与消息里，两者都空的行点进去既没有会话也没有消息 —— 用户问的「这几个没聊过
                // 的会话为什么在」就是它们（它们是「被发现的节点」，不是一个会话）。
                // 人类对端**保持原样**：刚配对完的电脑必须立刻出现在列表里，否则用户会以为
                // 配对失败（见本属性 KDoc 的「来源」一节）。
                addAll(real.keys.filter { id ->
                    val p = real[id]
                    p?.isAgent != true ||
                        summariesByConv.containsKey(id) ||
                        visibleAgentSessionsOf(id).isNotEmpty()
                })
                addAll(summariesByConv.keys.filter { key ->
                    groupsByConv.containsKey(key) || real.containsKey(key) || (!com.kapp.tenon.core.protocol.IpAddressUtils.isIpv4(key) && !com.kapp.tenon.core.protocol.IpAddressUtils.isIpv6(key) && !key.contains(':'))
                })
                addAll(groupsByConv.keys)
            }
            return ids
                .map { id ->
                    val group = groupsByConv[id]
                    val peer = real[id]
                    // 只读摘要：不再回头翻 messages —— 那正是「列表绑死消息窗口」的根源。
                    val summary = summariesByConv[id]
                    val lastMessageAt = summary?.lastMessageAt ?: 0L
                    // 对端最后在线（群没有"在线"这个概念，退回建群/更新时间 updatedTs）。
                    // 只有"还没聊过"那一档才拿它当尺子，见 ConversationUi.presenceAt 的 KDoc。
                    val presenceAt = group?.updatedTs ?: peer?.lastSeenAt ?: 0L
                    ConversationUi(
                        convId = id,
                        title = convTitle(id),
                        // 文件消息没有正文，列表预览要退回文件名，否则会话行是空白。
                        preview = summary?.lastTextPreview,
                        // 预览要能区分「他说的」与「我说的」：后者加「你：」前缀。
                        lastMessageIsMine = summary?.lastMessageIsMine == true,
                        isPinned = id in settings.pinnedConvIds,
                        isMuted = id in settings.mutedConvIds,
                        // 兼容兜底：桌面会话面板直接 relativeTime(at)，没有 0 守卫，
                        // 所以这里保持"有消息用消息时间、没有用在线时间"，语义见 at 的 KDoc。
                        at = if (lastMessageAt > 0) lastMessageAt else presenceAt,
                        unread = unreadByPeer[id] ?: 0,
                        isAgent = peer?.isAgent ?: false,
                        online = peer?.online ?: false,
                        pendingCount = pendingByPeer[id] ?: 0,
                        isGroup = group != null,
                        memberOnlineCount = memberOnlineCount(group),
                        memberCount = group?.members?.size ?: 0,
                        avatar = peer?.avatar,
                        lastMessageAt = lastMessageAt,
                        presenceAt = presenceAt,
                    )
                }
                // 排序口径见本属性的 KDoc：有消息的在前、按最后一条消息倒序；没消息的整档
                // 沉底、档内按最后在线倒序。刻意**不**含 unread —— 未读只做视觉标记。
                //
                // 群用 updatedTs 作 presenceAt（"避免刚建的群沉底"）：刚建的群没有消息，
                // 于是落进没消息那一档，靠建群时间在档内靠前，但不会压过任何有消息的会话。
                .sortedWith(
                    compareByDescending<ConversationUi> { it.lastMessageAt > 0 }
                        .thenByDescending { it.lastMessageAt }
                        .thenByDescending { it.presenceAt },
                )
        }

/** 人类对端（切片版）：与 [RelayUiState.humanPeers] 逐字同义。 */
fun humanPeers(node: NodeSlice): List<PeerUi> = node.peers.filterNot { it.isAgent }

/**
 * 按 nodeId 取对端（切片版）：与 [RelayUiState.peer] 逐字同义 —— 本机一律回一条合成 agent 宿主条目
 * （它不是节点表里的行，但会话/名字解析都要求它「在」）。
 */
fun peer(nodeId: String?, node: NodeSlice, settingsSlice: SettingsSlice): PeerUi? = nodeId?.let { id ->
    val selfNodeId = settingsSlice.settings.nodeId
    if (id == selfNodeId) {
        PeerUi(
            nodeId = selfNodeId,
            nick = settingsSlice.settings.nick.ifBlank { selfNodeId.take(8) },
            platform = "",
            caps = listOf(MsgTypes.CAP_AGENT, MsgTypes.CAP_AGENT_WORKSPACE),
            online = true,
        )
    } else {
        node.peers.firstOrNull { it.nodeId == id }
    }
}

/** 全部对端的待处理数之和（切片版）：与 [RelayUiState.totalPending] 逐字同义。 */
fun totalPending(chat: ChatSlice): Int = chat.pendingByPeer.values.sum()

/**
 * 群会话预览的发送者署名（切片版）：与 [RelayUiState.previewSenderNameOf] 同义。
 *
 * 身份解析链（`senderContext` / `resolveSenderIdentity` / `agentKindsByHost` …）有两百多行、
 * 且同时依赖 chat/agent/node/settings 四片。为了**不复制**这套解析（复制就是第二个事实来源），
 * 这里构造一个只填相关字段的 [RelayUiState] **只读视图**再调原解析器 —— 纯函数、无副作用，
 * 视图用完即弃。这是本文件唯一的 narrow-view 适配，其余投影都是字段直取。
 */
fun previewSenderNameOf(
    convId: String,
    chat: ChatSlice,
    agent: AgentSlice,
    node: NodeSlice,
    settingsSlice: SettingsSlice,
): String? = narrowIdentityView(chat, agent, node, settingsSlice).previewSenderNameOf(convId)

//
// **窄视图约束（改这里之前必读）**：`senderContext` / `resolveSenderIdentity` 的解析链会读
// 下面这些字段 —— selfNodeId/selfNick、peers、group、messages、agentSessions、agentEvents、
// agentWorkspaces、agentGrants、agentTasks、agentRuns、agentArtifacts（`agentKindsByHost` /
// `agentAliasesByHost` 由前几项派生）。**必须全部填满**：少填一个不会编译失败，只会让某个解析
// 分支静默退化成「nodeId 前缀」或「没名字」—— 那正是这类适配最危险的失败形态。
// 新增解析链依赖时，先补这里，再在 RelayStateProjectionsTest 的「解析分支样本」里加一档。
private fun narrowIdentityView(
    chat: ChatSlice,
    agent: AgentSlice,
    node: NodeSlice,
    settingsSlice: SettingsSlice,
): RelayUiState = RelayUiState(
    settings = settingsSlice.settings,
    peers = node.peers,
    messages = chat.messages,
    conversationSummaries = chat.conversationSummaries,
    unreadByPeer = chat.unreadByPeer,
    pendingByPeer = chat.pendingByPeer,
    agentSessions = agent.agentSessions,
    agentEvents = agent.agentEvents,
    agentApprovals = agent.agentApprovals,
    agentTasks = agent.agentTasks,
    agentRuns = agent.agentRuns,
    agentInvokes = agent.agentInvokes,
    agentArtifacts = agent.agentArtifacts,
    agentWorkspaces = agent.agentWorkspaces,
    agentGrants = agent.agentGrants,
    pendingGrantRequests = agent.pendingGrantRequests,
    sessionPageLoading = agent.sessionPageLoading,
    sessionPageExhausted = agent.sessionPageExhausted,
)

/** 会话键对应的群（切片版）：与 [RelayUiState.groupOfConv] 同义。 */
fun groupOfConv(settingsSlice: SettingsSlice, convId: String?): GroupMeta? =
    convId?.let { ConvId.groupIdOf(it) }?.let { gid ->
        settingsSlice.settings.groups.firstOrNull { it.groupId == gid }
    }

/** 群会话的占位「对端」（切片版）：与 [RelayUiState.groupPeer] 同义。 */
fun groupPeer(group: GroupMeta?, node: NodeSlice, settingsSlice: SettingsSlice): PeerUi? = group?.let {
    PeerUi(
        nodeId = ConvId.group(it.groupId),
        nick = it.name,
        online = memberOnlineCount(it, node, settingsSlice) > 0,
    )
}

/** 群成员在线数（切片版）：与 [RelayUiState.memberOnlineCount] 同义。 */
fun memberOnlineCount(group: GroupMeta?, node: NodeSlice, settingsSlice: SettingsSlice): Int =
    group?.members.orEmpty().count { it != settingsSlice.settings.nodeId && peer(it, node, settingsSlice)?.online == true }

/** 单聊的「对端」：没有真实记录时也回一个占位（切片版，与 [RelayUiState.chatPeer] 同义）。 */
fun chatPeer(chat: ChatSlice, node: NodeSlice, settingsSlice: SettingsSlice, nodeId: String?): PeerUi? =
    peer(nodeId, node, settingsSlice)
        ?: nodeId?.takeIf { chat.messages[it].orEmpty().isNotEmpty() }
            ?.let { PeerUi(nodeId = it, nick = "") }

/** 身份解析上下文（切片版）：与 [RelayUiState.senderContext] 同义（走同一个只读视图）。 */
fun senderContext(
    convId: String?,
    chat: ChatSlice,
    agent: AgentSlice,
    node: NodeSlice,
    settingsSlice: SettingsSlice,
): SenderIdentityContext = narrowIdentityView(chat, agent, node, settingsSlice).senderContext(convId)

/**
 * 会话显示名（切片版）：曾是 [RelayUiState.convTitle] 的同义实现。
 *
 * **O1 之后两者不再同义**：生产路径（会话列表 [conversations] / 搜索 / 通知标题）
 * 走的是带 agent 数据的身份解析（agent 宿主对端显示 agent 名，与头部同口径）；
 * 本函数没有 agent/chat 切片入参，保持设备昵称口径。当前无生产调用方
 * （[composeApp] 的 DesktopFilesPane 未被组合）—— 新调用请用带切片的版本，
 * 别让两套口径复活。
 */
fun convTitle(convId: String, node: NodeSlice, settingsSlice: SettingsSlice): String {
    groupOfConv(settingsSlice, convId)?.name?.let { return it }
    if (!ConvId.isGroup(convId)) {
        peer(convId, node, settingsSlice)?.displayName?.let { return it }
    }
    return (ConvId.groupIdOf(convId) ?: convId).take(8)
}

/** 本地认识的群（切片版）：与 [RelayUiState.groupOf] 同义。 */
fun groupOf(settingsSlice: SettingsSlice, groupId: String?): GroupMeta? =
    groupId?.let { id -> settingsSlice.settings.groups.firstOrNull { it.groupId == id } }

/** 群绑定的工作区（切片版）：与 [RelayUiState.workspaceOfGroup] 同义。 */
fun workspaceOfGroup(groupId: String?, agent: AgentSlice, settingsSlice: SettingsSlice): WorkspaceUi? {
    val meta = groupOf(settingsSlice, groupId) ?: return null
    val declared = meta.virtualAgents.orEmpty().mapNotNull { it.defaultWsId }.toSet()
    if (declared.isEmpty()) return null
    return agent.agentWorkspaces.values.firstOrNull { it.wsId in declared }
}

/** 会话绑定的工作区（切片版）：与 [RelayUiState.workspaceOfConv] 同义。 */
fun workspaceOfConv(convId: String?, agent: AgentSlice, settingsSlice: SettingsSlice): WorkspaceUi? =
    workspaceOfGroup(convId?.let { ConvId.groupIdOf(it) }, agent, settingsSlice)
