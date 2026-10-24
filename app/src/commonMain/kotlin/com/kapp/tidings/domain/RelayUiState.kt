// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.domain.model.*
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentArtifactKey
import com.kapp.tidings.domain.model.AgentArtifactUi
import com.kapp.tidings.domain.model.AgentEventUi
import com.kapp.tidings.domain.model.AgentQueueTaskUi
import com.kapp.tidings.domain.model.AgentRunKey
import com.kapp.tidings.domain.model.AgentRunUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.model.CabinetUiState
import com.kapp.tidings.domain.model.ConversationUi
import com.kapp.tidings.domain.model.MdnsServiceUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NetworkUi
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.model.ScanProgressUi
import com.kapp.tidings.domain.model.ScreenSessionUiState
import com.kapp.tidings.domain.model.TransferRecordUi

/**
 * 全应用唯一的状态。
 *
 * 为什么不是每屏一个 state：真实数据（节点表、消息、队列深度）是**同一份**，
 * 会话屏和聊天屏看的是同一个对端。脚手架把六屏各自做成互不相干的
 * `items: List<XxxItemUi>`，于是只能渲染占位串 —— 状态模型本身就决定了 UI 能不能真实。
 */
data class RelayUiState(
    val settings: RelaySettings = RelaySettings.firstRun(),
    val network: NetworkUi = NetworkUi(),
    val peers: List<PeerUi> = emptyList(),
    val messages: Map<String, List<MessageUi>> = emptyMap(),
    /**
     * 会话摘要（会话列表需要的最后一句话 / 时间 / 归属）。
     *
     * 为什么与 [messages] 分开：会话列表要一行预览、最后消息时间、是否「我说的」，
     * 这些曾经全从 [messages] 现算 —— 于是「列表」被绑死在「消息窗口」上，
     * 想只加载当前打开的会话就不可能。摘要把这两件事拆开（见 ConversationSummary 表）。
     *
     * 生产路径每帧都有它（RelayStateWriter 在每次 commit 时合成：内存里有的会话用
     * 最新一条覆盖，内存里没有的沿用启动时从库里读到的那份）。
     * 空表示「这个壳没有摘要」（测试 / 预览 / 未接存储），此时 conversations 退回从
     * [messages] 推导，行为与改造前逐字一致。
     */
    val conversationSummaries: List<ConversationSummaryRow> = emptyList(),
    val unreadByPeer: Map<String, Int> = emptyMap(),
    val pendingByPeer: Map<String, Int> = emptyMap(),
    val banner: BannerUi? = null,
    /** 设置页保存后的就地反馈。 */
    val settingsNote: BannerMessage? = null,
    /**
     * 一次「把这个会话摆到用户面前」的请求（目前只有戳一碰会产生）。
     *
     * 协议 §8 要求戳一碰的收端「唤起主窗并定位到 `single:<from>` 会话」。手机端没有把
     * 应用从后台拉到前台的能力，但**定位**这一半做得到：控制器把目标 `peerId` 放在这里，
     * 外壳消费一次就清掉（[RelayController.consumeFocusRequest]）。
     *
     * 用一次性字段而不是「当前选中会话」，是因为控制器不该成为会话选择的第二个事实来源 ——
     * 用户自己点了别处，这里不能把他拽回去。
     */
    val focusRequest: String? = null,
    /**
     * 正在重启节点（停止 → 重新绑定端口 → 启动）。
     *
     * 重启是真的慢：必须先让旧节点释放 TCP 端口，再重新绑定；这中间界面若什么都不说，
     * 用户会以为按钮没反应而反复点。原先这里叫 `busy`，但全仓没有一处读写它 ——
     * 一个没人用的名字比没有字段更糟（看着像有反馈，其实没有）。
     */
    val restarting: Boolean = false,
    /** mDNS 自动发现的服务列表。 */
    val mdnsServices: List<MdnsServiceUi> = emptyList(),
    /**
     * 按 `(peerId, sessionId)` 二元组索引的 agent 会话（PPE-1 §6.1）。
     *
     * 协议与存储均按 (node, session) 复合隔离，运行时必须使用复合键避免同名会话跨节点碰撞。
     */
    val agentSessions: Map<AgentSessionKey, AgentSessionUi> = emptyMap(),
    /** 按 `(peerId, sessionId)` 复合键索引的事件流，**按 cursor 升序**（§6.2：cursor 只在同一会话内可比）。 */
    val agentEvents: Map<AgentSessionKey, List<AgentEventUi>> = emptyMap(),
    /** Agent 审批请求表（PPE-1 §6.5）。 */
    val agentApprovals: Map<String, AgentApprovalUi> = emptyMap(),
    /** Agent 任务队列（PPE-1 §6.3）。 */
    val agentTasks: Map<String, AgentQueueTaskUi> = emptyMap(),
    /** 按 `(peerId, runId)` 索引的执行状态（PPE-1 §6.4）。 */
    val agentRuns: Map<AgentRunKey, AgentRunUi> = emptyMap(),
    /** 按 `(peerId, artifactId)` 索引的产物元数据（PPE-1 §6.7）。 */
    val agentArtifacts: Map<AgentArtifactKey, AgentArtifactUi> = emptyMap(),
    /**
     * 按 `(peerId, agentKey, wsId)` 索引的**工作区宣告**（PPE-1 增补 D3，`agent-workspace`）。
     *
     * 它不是 `agentSessions` 的一个投影：这张表里可以有**零会话**的工作区，而那正是
     * D3 存在的唯一理由（父稿 §2.2 ——「工作区已存在但还没有任何会话」这一档）。
     * 派生层（`inboxWorkspaceUnits()`）取的是**并集**，所以这里只当宣告台账用。
     */
    val agentWorkspaces: Map<WorkspaceKey, WorkspaceUi> = emptyMap(),
    /**
     * 宿主给过的授权结论，键 = [grantKeyOf]（PPE-1 增补 D7，父稿 §3.3）。
     *
     * 空串后缀代表**整台设备**那一档（`scope = "all"` 的帧没有 `agentKey`）。用串键而不是
     * `WorkspaceKey` 那种三元组：授权只有两层（节点 × agent），硬凑一个 wsId 位是假结构。
     *
     * 这里**只**装宿主真的发过 `agent-grant` / `agent-grant-list` 帧的那些条目，
     * `state` 是协议原样取值。不在表里 = 沉默（[GrantRequestStatus.NONE]），
     * **不是** "denied" —— 四档能互相区分就靠这一点（父稿 §3.3）。
     */
    val agentGrants: Map<String, GrantUi> = emptyMap(),
    /**
     * 本端已发出 `agent-grant-request`、还没等到宿主表态的那些键（[grantKeyOf]）。
     *
     * 单独一格而**不**塞进 [agentGrants]：那张表的每个值都必须是宿主的结论。混进一个
     * 本端自造的 `state` 字面量，等于往线协议的封闭取值域里塞第四个取值
     * （`AgentGrantStates.ALL` 没有它），落库与渲染就各要写一条"这是假的"的特判。
     *
     * 只活在本进程：它是"我刚发过一条申请"这个事实；重启之后申请会不会被补投、宿主会不会
     * 再答一次本端都不知道，所以退回 [GrantRequestStatus.NONE] 是**如实**而不是丢失
     * （详见 `RelayControllerAgentComponent.handleSendGrantRequest` 的 KDoc）。
     */
    val pendingGrantRequests: Set<String> = emptySet(),
    /**
     * 正在等待宿主回填会话页的那些工作区键（PPE-1 增补 D8）。
     *
     * 与 [pendingGrantRequests] 同一取舍：只活在本进程。它表达的是「我刚发过一条
     * agent-session-page 请求、还没看到结果」—— 而"结果"本身会以普通 agent-session
     * 帧陆续落库。把这个标记落盘等于让一个几秒内就该消失的状态活成持久的结论
     * （重启后"加载中"永远转下去）。
     *
     * 键用 `(peerId, wsId)`：会话分页是按**工作区**要的，同一个节点上的两个工作区
     * 可以同时在拉。
     */
    val sessionPageLoading: Set<String> = emptySet(),
    /**
     * 已经拉到「没有下一页」的那些工作区键（[sessionPageKeyOf]，PPE-1 增补 D8）。
     *
     * 与 [sessionPageLoading] 同一取舍：只活在本进程，不落盘。语义要读准 —— 它说的是
     * 「这一格在我这儿已经拉到底了」，**不是**「宿主说没有更多」：宿主根本没有
     * 「没有更多」这个表达（沉默既可能是到底、也可能是没收到），所以这个结论只能由
     * 本端自己观察得出，也只对本端这次运行有效。
     *
     * 写入：`handleRequestSessionPage` 发页后 5 秒，本工作区的会话条数一条没涨 ⇒ 加进来
     * （分页边界重叠导致的重复帧不算涨）。清除：收到一条**新的** sessionId 的帧，
     * 或有人以 `offset = 0` 从头再拉（工作区重开）—— 那时宿主可能已经多出会话，
     * 留着这个结论会让「加载更多」永远不再出现。
     */
    val sessionPageExhausted: Set<String> = emptySet(),
    /** 应用软件版本检查与更新状态。 */
    val updateState: UpdateCheckState = UpdateCheckState(),
    /** 共享文件柜状态。 */
    val cabinet: CabinetUiState = CabinetUiState(),
    /** 网段扫描进度：非空表示后台正在扫描。 */
    val scanProgress: ScanProgressUi? = null,
    /** 屏幕协助会话状态。 */
    val screenSession: ScreenSessionUiState = ScreenSessionUiState(),
    /** 传输历史记录（最多保留最近 30 条）。 */
    val transferLogs: List<TransferRecordUi> = emptyList(),
    /** 自定义头像图片缓存（hash -> 字节数据）。 */
    val avatarCache: Map<String, ByteArray> = emptyMap(),
    /**
     * 本机在「上传头像」页刚选中的那张图（还没宣告出去）。
     *
     * 与 [avatarCache] 分开：那个是**对端头像**的缓存（key 是 `avatarHash`，来源是 wire），
     * 这个是**本机自己刚挑的**、还没有宣告、也不该混进对端缓存里。`SetAvatarPage`
     * 的 `pickedAvatarBytes` 形参吃它 —— 非空时顶栏与选图卡画真图，空时画占位。
     */
    val pickedAvatarBytes: ByteArray? = null,
    /** 上面那张图的 sha256，选中的那一刻就算好（原型第 3 步「编码 + 落盘」的文件名就是它）。 */
    val pickedAvatarHash: String? = null,
    /**
     * 那张图在系统选择器里的原始文件名。资料页选图卡与「上传头像」页都靠它显示
     * **真实的**文件名 —— 之前选完图界面只能写「本机图片」，用户分不清选的是哪一张。
     * 移除选择时与 bytes、hash 一起清空。
     */
    val pickedAvatarName: String? = null,
) {
    val selfNodeId: String get() = settings.nodeId

    val onlineCount: Int get() = peers.count { it.online }

    val agentPeers: List<PeerUi> get() {
        val hostNode = if (agentWorkspaces.keys.any { it.peerId == selfNodeId }) {
            PeerUi(
                nodeId = selfNodeId,
                nick = settings.nick.ifBlank { selfNodeId.take(8) },
                platform = "",
                caps = listOf(MsgTypes.CAP_AGENT, MsgTypes.CAP_AGENT_WORKSPACE),
                online = true,
            )
        } else null
        return peers.filter { it.isAgent } + listOfNotNull(hostNode)
    }

    val humanPeers: List<PeerUi> get() = peers.filterNot { it.isAgent }

    /**
     * 宿主 nodeId → 该宿主上 agent 的 kind（`dsh` / `opencode` …）。
     *
     * 这是**唯一**的「这个节点上有哪种 agent」台账：成员列表把 agent 显示成 `DSH`
     * 而不是宿主机器名、@ 候选给出 `@DSH`、触发判据认哪些 kind，都从这里取。
     * 来源只有两个（都是宿主自己宣告的帧）：工作区宣告与会话宣告；先到先得，不合成。
     */
    /**
     * 宿主 nodeId → 该宿主上**所有** agent 的 kind（小写去重）。
     *
     * 一台设备可以装多个 agent（codex / opencode / dsh…），所以这里收集合。
     * 旧实现是 `if (!out.containsKey(host)) out[host] = k` —— **每个宿主只留第一个 kind**，
     * 于是装了第二个 agent 之后 `@opencode` 永远匹配不上：触发判据会静默漏掉它，
     * 表现为「@ 了却没反应」。
     */
    fun agentKindsByHost(): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        fun note(host: String, kind: String?) {
            val k = kind?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
            out.getOrPut(host) { LinkedHashSet() }.add(k)
        }
        agentWorkspaces.values.forEach { note(it.peerId, it.agentKind) }
        agentSessions.values.forEach { note(it.peerId, it.agentKind) }
        return out
    }

    /**
     * 宿主 nodeId → agent 的 alias（PPE-1 会话帧 `alias`）。
     *
     * 只用于显示名：宿主自己报的 `DSH` 比 kind 的机械大写更像人名。**不参与判定** ——
     * 身份键始终是 kind / nodeId，alias 换了不该让同一个人在群里换一栏。
     */
    fun agentAliasesByHost(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        agentSessions.values.forEach { s ->
            val alias = s.alias?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!out.containsKey(s.peerId)) out[s.peerId] = alias
        }
        return out
    }

    /**
     * 一个会话的身份解析上下文（气泡、成员面板、@ 候选、会话行标题共用）。
     *
     * [SenderIdentityContext.agentSenderIds] 从**这个会话的消息**里收集：群里 agent
     * 说过话就说明它在场，哪怕宿主节点还没进 `peers`（跨网冷启动时常见）。
     */
    fun senderContext(convId: String?): SenderIdentityContext {
        val group = groupOfConv(convId)
        val agentSenders = messages[convId].orEmpty()
            .mapNotNull { it.senderId }
            .filter { isAgentSenderId(it) }
            .toSet()
        return SenderIdentityContext(
            selfNodeId = selfNodeId,
            // 自己不在 peers 里 —— 昵称来自 settings.nick，不传就显示成 nodeId 前 8 位。
            selfNick = settings.nick,
            peers = peers,
            group = group,
            agentKindsByHost = agentKindsByHost(),
            agentAliasesByHost = agentAliasesByHost(),
            agentSenderIds = agentSenders,
            // 工作区 id → 名字：@ token 要的是名字（`@dsh-teahouse-mobile`），
            // 而 `virtualAgents` 只存 wsId。同一份状态里已有 `agentWorkspaces`，
            // 不需要额外请求。
            workspaceTitles = agentWorkspaces.values
                .mapNotNull { ws -> ws.wsTitle?.takeIf { it.isNotBlank() }?.let { ws.wsId to it } }
                .toMap(),
            // **宿主的 agent 各自活跃在哪个工作区** —— 单聊里算名字要用它。
            //
            // 群聊从 `virtualAgents` 拿到名字（入驻时生成的）；单聊没有群元数据，
            // 但 agent 的会话自己带 `wsId` —— 有了这张表，单聊能用**同一条规则**
            // 算出同一个名字，而不是退回 `dsh`。
            //
            // 取该 (host, kind) 下**最近活跃**的那条会话的 wsId：用户在单聊里面对的
            // 就是那个工作区的 agent，名字与群聊里看到的一致。
            agentWorkspaceByHostAndKind = agentSessions.values
                .filter { it.wsId != null && !it.agentKind.isNullOrBlank() }
                .groupBy { session -> session.peerId }
                .mapValues { (_, sessionsOfHost) ->
                    sessionsOfHost
                        .groupBy { session -> session.agentKind!!.lowercase() }
                        .mapValues { (_, sameKind) ->
                            sameKind.maxByOrNull { it.sortActivityAt }!!.wsId!!
                        }
                },
        )
    }

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

    /**
     * 打开某个会话时用它：对端还在线/已知就返回真实记录，**只剩落盘历史时返回一个占位对端**。
     *
     * 冷启动时 [MessageStore] 先把历史读回来，网络层此刻可能一个 [PeerUi] 都还没有
     * （对端没广播、设备刚上电、`messagesRestored` 早于 `NodeRuntime` 建好）。这时
     * [peer] 返回 null，聊天页只能画"选一个会话"，用户看着像消息全丢了 ——
     * 和 [conversations] 当初"只列 peers"是同一个坑，只是漏在下一屏。
     *
     * 占位对端只有 `nodeId`，于是 `displayName` 落到 `nodeId.take(8)`，
     * 与会话列表里没有对端记录时用的标题一致；`online=false` 也如实反映"现在联系不上"。
     */
    fun chatPeer(nodeId: String?): PeerUi? = peer(nodeId)
        ?: nodeId?.takeIf { messages[it].orEmpty().isNotEmpty() }
            ?.let { PeerUi(nodeId = it, nick = "") }

    fun messagesWith(peerId: String?): List<MessageUi> = peerId?.let { messages[it] }.orEmpty()

    /**
     * 群会话的"对端"：一个只用来画头部的占位 [PeerUi]。
     *
     * 群没有 host/IP/能力，这些字段一律留空（[PeerUi.subtitle] 会因此是空串，
     * 群头部的副标题由 `ChatHeader` 自己按成员数拼）。做成占位而不是让 `ChatHeader`
     * 多接一套群参数，是为了让"头部怎么画"这条路径只有一条 —— 否则群和私聊的头部
     * 迟早会长成两套，改一处漏一处。
     */
    fun groupPeer(group: GroupMeta?): PeerUi? = group?.let {
        PeerUi(
            nodeId = ConvId.group(it.groupId),
            nick = it.name,
            online = memberOnlineCount(it) > 0,
        )
    }

    /** 本地认识的群；没有就返回 null（"我不在这个群里"或"元数据还没同步到"）。 */
    fun groupOf(groupId: String?): GroupMeta? =
        groupId?.let { id -> settings.groups.firstOrNull { it.groupId == id } }

    /** 按会话键取群：`group:<groupId>` → [GroupMeta]。私聊键恒返回 null。 */
    fun groupOfConv(convId: String?): GroupMeta? = groupOf(convId?.let { ConvId.groupIdOf(it) })

    /** 该群绑定的工作区；未绑定或未宣告时返回 null。 */
    fun workspaceOfGroup(groupId: String?): WorkspaceUi? {
        val meta = groupOf(groupId) ?: return null
        val desc = meta.description.orEmpty().trim()
        return agentWorkspaces.values.firstOrNull { ws ->
            (desc.isNotBlank() && (desc == ws.wsPath || desc == ws.wsId || (ws.wsPath?.isNotBlank() == true && desc.contains(ws.wsPath)))) ||
                (ws.wsTitle?.isNotBlank() == true && meta.name.contains(ws.wsTitle))
        }
    }

    /** 该会话绑定的工作区：`group:<groupId>` → [WorkspaceUi]。私聊键恒返回 null。 */
    fun workspaceOfConv(convId: String?): WorkspaceUi? =
        workspaceOfGroup(convId?.let { ConvId.groupIdOf(it) })

    /**
     * 群里当前在线的人数（**不含自己**）。
     *
     * "5 个成员里 2 个在线"和"全离线"是完全不同的处境：前者发出去的话立刻有人看到，
     * 后者只能排队等。群行上复用 [ConversationUi.online] 那个圆点表达不了这件事
     * （一个布尔装不下"几个人"），所以成员数单独算、单独显示。
     */
    fun memberOnlineCount(group: GroupMeta?): Int =
        group?.members.orEmpty().count { it != selfNodeId && peer(it)?.online == true }

    /**
     * 群消息气泡该署谁的名。
     *
     * **只是 [resolveSenderIdentity] 的薄封装**，不保留第二套判定：agent 的
     * `agent:<kind>` 在旧实现里单独走一条 "DSH Agent" 分支，于是「署名认 agent、
     * 头像和 Markdown 不认」—— 同一条消息三个身份。现在一律走解析器。
     *
     * 拿不到会话上下文（群昵称那一档）时退化成「对端档案 + agent kind」，聊天气泡
     * 该用 [senderContext] 构造的上下文再调 [resolveSenderIdentity]。
     */
    fun senderName(senderId: String?): String? {
        val id = senderId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (id == selfNodeId) return null
        return resolveSenderIdentity(id, SenderIdentityContext(selfNodeId = selfNodeId, peers = peers))?.displayName
    }

    /**
     * 某个 agent 节点名下的会话，**活动倒序**在前。
     *
     * 排序键是 [AgentSessionUi.sortActivityAt]（宿主侧活动时刻优先），不是"本端收到帧的时刻" ——
     * 后者在历史分页那 20 条上全落在同一毫秒（一页走同一个 TCP tick），排序退化成到达顺序，
     * 手机上于是显示成"最近创建的"甚至随机顺序，而 DSH 那边是
     * `max(createdAt, lastPromptAt)` 的活动倒序。
     *
     * `sortedByDescending` 是**稳定排序**：宿主按活动序发、本端同毫秒到达时保留到达序，
     * 结果仍与宿主一致 —— 所以顺序的权威始终在宿主那侧，这里只负责别把它打乱。
     */
    fun agentSessionsOf(peerId: String?): List<AgentSessionUi> =
        peerId?.let { id -> agentSessions.filterKeys { it.peerId == id }.values }
            .orEmpty()
            .sortedWith(
                compareByDescending<AgentSessionUi> { session ->
                    val key = AgentSessionKey(session.peerId, session.sessionId)
                    val lastEventAt = agentEvents[key]?.maxOfOrNull { it.at } ?: 0L
                    maxOf(session.sortActivityAt, lastEventAt)
                }.thenByDescending { it.startedAt ?: 0L }
                 .thenByDescending { it.sessionId },
            )

    /**
     * 某个节点名下**该显示**的会话（已按 DSH 可见性两键过滤），活动倒序在前。
     *
     * 这是 UI 该用的那个口径；[agentSessionsOf] 是**未过滤**的原始列表，它有两个
     * 必须保持原样的消费者（不要让 UI 需求把它们带坏）：
     *   · 分页游标（`App.kt` 的 `onLoadMoreAgentSessions` 拿它的条数当 offset）——
     *     宿主的分页 offset 作用在**全部候选**上（宿主不替手机藏行），两边必须同分母，
     *     在这里过滤会让下一页跳过被隐藏的行；
     *   · `ChatScreen` 的 `hasMoreSessionsLocal` 也一样直接数原始表。
     */
    fun visibleAgentSessionsOf(peerId: String?): List<AgentSessionUi> =
        agentSessionsOf(peerId).visibleInDsh()

    /**
     * 按节点收紧后的原始会话表（**未过滤**），键 = 同一 `Sequence` → `List` 的一次物化。
     *
     * 给「一次要看到整个节点的会话才能判可见性」的展示层消费者用（工作区卡片那条路
     * 不走 [agentSessionsOf]）：blank 那一档问的是「同工作区还有没有别的可见会话」，
     * 逐条判不出来。
     */
    fun agentSessionsOfNode(peerId: String): List<AgentSessionUi> =
        agentSessions.values.filter { it.peerId == peerId }

    /** [agentSessionsOfNode] 的可见过滤版。 */
    fun visibleAgentSessionsOfNode(peerId: String): List<AgentSessionUi> =
        agentSessionsOfNode(peerId).visibleInDsh()

    /**
     * 某个工作区名下的会话条数（**未过滤**原始表）。
     *
     * 分页的 offset 就长这样：宿主的分页作用在全部候选上，手机若拿「可见」那份来数，
     * 下一页会跳过被隐藏的行。所以这里与 `App.kt` 的 `onLoadMoreAgentSessions`、
     * `ChatScreen.hasMoreSessionsLocal` 必须同分母。
     */
    fun agentSessionCountOf(peerId: String, wsId: String): Int =
        agentSessions.values.count { it.peerId == peerId && it.wsId == wsId }

    /** 某个 agent 节点名下的进行中会话（未结束状态），最近更新在前。 */
    fun activeAgentSessionsOf(peerId: String?): List<AgentSessionUi> =
        visibleAgentSessionsOf(peerId).filterNot { it.isEnded }

    /** 某个 agent 节点名下的已结束/归档会话（done/error/archived 等），最近更新在前。 */
    fun endedAgentSessionsOf(peerId: String?): List<AgentSessionUi> =
        agentSessionsOf(peerId).filter { it.isEnded }

    /** 某个会话的事件流（已按 cursor 升序）。 */
    fun agentEventsOf(peerId: String?, sessionId: String?): List<AgentEventUi> =
        if (peerId != null && sessionId != null) {
            agentEvents[AgentSessionKey(peerId, sessionId)].orEmpty()
        } else {
            emptyList()
        }

    /** 某个会话的事件流（重载：直接传会话对象）。 */
    fun agentEventsOf(session: AgentSessionUi?): List<AgentEventUi> =
        if (session != null) agentEventsOf(session.peerId, session.sessionId) else emptyList()

    /** 某个会话的执行状态，最近收到在前（PPE-1 §6.4）。 */
    fun agentRunsOf(peerId: String?, sessionId: String?): List<AgentRunUi> =
        if (peerId != null && sessionId != null) {
            agentRuns.values
                .filter { it.peerId == peerId && it.sessionId == sessionId }
                .sortedByDescending { it.updatedAt }
        } else {
            emptyList()
        }

    /**
     * 某个 agent 节点名下**宣告过**的工作区，按**稳定序**在前（PPE-1 增补 D3，父稿 §5）。
     *
     * `agentKey` = `agentId ?? agentKind`；传 null = 不过滤，返回该节点全部工作区。
     * 注意这里只包含 `agent-workspace` 帧宣告的那些 —— **只从会话派生出来的**工作区
     * （会话带了 `wsId` 而对端从没发过宣告帧）不在这张表里，界面上要画工作区一律走
     * `inboxWorkspaceUnits()` 的并集，别拿这个函数当"全部工作区"。
     *
     * ## 排序与 `inboxWorkspaceUnits()` 必须**同一把尺子**
     *
     * 顺序 = DSH 注册表的持久序（用户能手工拖，`hostOrder`）；对端没报 order 的那些
     * （宿主桥不发 `order` / 本地台账兜底路）退回 [workspaceSortName]（标题 → 路径末段 →
     * wsId）→ `wsId`。
     *
     * 此前这里退的是**收到时刻倒序**（`-at`）：每来一次宣告帧都可能重排，而且与工作区
     * 看板用的那把尺子（待审批 → 最近活动）不同 —— 同一份数据在两屏两个顺序，正是本仓
     * 反复抓过的"两把尺子"。两者现在都收敛到同一个稳定键。
     */
    fun workspacesOf(peerId: String?, agentKey: String? = null): List<WorkspaceUi> =
        if (peerId == null) emptyList() else agentWorkspaces.values
            .filter {
                it.peerId == peerId && (agentKey == null || it.agentKey == agentKey) &&
                    !isSyntheticWsId(it.wsId)
            }
            .sortedWith(
                compareBy(
                    { it.hostOrder ?: Long.MAX_VALUE },
                    { workspaceSortName(it.wsTitle, it.wsPath, it.wsId) },
                    { it.wsId },
                ),
            )

    /**
     * 我对某个节点（可选细到某个 agent）的申请状态，四档见 [GrantRequestStatus]。
     *
     * 判序是**宿主的结论优先于我的等待**：先查生效授权，再问是不是在等回复。反过来会
     * 画出「已申请待批」压住「已授权」那种谎 —— 宿主批完一定会发 `agent-grant` 或清单，
     * 那一行才是事实，而 PENDING 只是本端的一次乐观记账。
     */
    /**
     * 我对某个节点（可选细到某个 agent，可选细到某个工作区）的申请状态，四档见 [GrantRequestStatus]。
     *
     * 判序是**宿主的结论优先于我的等待**：先查生效授权，再问是不是在等回复。反过来会
     * 画出「已申请待批」压住「已授权」那种谎 —— 宿主批完一定会发 `agent-grant` 或清单，
     * 那一行才是事实，而 PENDING 只是本端的一次乐观记账。
     */
    fun grantStatusOf(peerId: String, agentKey: String? = null, wsId: String? = null): GrantRequestStatus {
        grantFor(peerId, agentKey, wsId)?.let { return grantStateToStatus(it.state) }
        if (grantKeyOf(peerId, agentKey, wsId) in pendingGrantRequests) return GrantRequestStatus.PENDING
        if (wsId != null && grantKeyOf(peerId, agentKey, null) in pendingGrantRequests) return GrantRequestStatus.PENDING
        return GrantRequestStatus.NONE
    }

    /**
     * 生效授权条目：先查工作区级，再退到 agent 级，再退到整台设备（`scope = "all"` 那一档）。
     *
     * 顺序不能反：设备级授权覆盖 agent 级，反过来会让"批了整台机器"显示成"没批"。
     * 两处都没有 = null ⇒ 界面上是沉默（NONE），不是拒绝（父稿 §3.3）。
     */
    fun grantFor(peerId: String, agentKey: String? = null, wsId: String? = null): GrantUi? {
        if (wsId != null) {
            agentGrants[grantKeyOf(peerId, agentKey, wsId)]?.let { return it }
        }
        if (agentKey != null) {
            agentGrants[grantKeyOf(peerId, agentKey, null)]?.let { return it }
        }
        return agentGrants[grantKeyOf(peerId, null, null)]
    }

    /** 某个会话的产物，最近收到在前（PPE-1 §6.7）。 */
    fun agentArtifactsOf(peerId: String?, sessionId: String?): List<AgentArtifactUi> =
        if (peerId != null && sessionId != null) {
            agentArtifacts.values
                .filter { it.peerId == peerId && it.sessionId == sessionId }
                .sortedByDescending { it.at }
        } else {
            emptyList()
        }

    val totalPending: Int get() = pendingByPeer.values.sum()

    /**
     * 会话列表：有消息往来的对端 + 已知但还没说过话的对端 + 认识的群。
     *
     * ## 排序：**按最近一条消息时间降序**，未读只做视觉标记
     *
     * 这是真机反馈 #1 的落点：过去这里先按"有未读"置顶、再用一个混着装了对端 `lastSeenAt`
     * 的时间戳排序，于是局域网发现每刷新一次，没聊过的在线对端就被顶到最前
     * （它的时间戳恒等于"刚刚"）。现在：
     *
     * 1. 有消息的会话在前（[ConversationUi.lastMessageAt] > 0），按它倒序；
     * 2. 没消息的整档排在**所有**有消息的会话之后，档内按 [ConversationUi.presenceAt]
     *    （对端最后在线 / 群 `updatedTs`）倒序 —— 最近还在线的靠前，但永远压不过任何
     *    一条真实消息；
     * 3. 未读**不参与排序**（[ConversationUi.unread] 只给会话行画红点）。
     *
     * 排序的**唯一真源是本派生**：会话屏的 feed 混排对会话项用的键就是
     * [ConversationUi.lastMessageAt]、且是稳定排序，不可能把这里的顺序改写成相反的样子。
     *
     * ## 来源
     *
     * 把"还没说过话的在线节点"也放进来是刻意的 —— 刚配对完的电脑必须立刻出现在
     * 会话列表里，否则用户会以为配对失败。
     *
     * 另一半同样刻意：**只有消息、对端记录还没有的会话也要在列表里**。
     * 冷启动时先恢复消息、再建立网络，对端的 `PeerUi` 要等它下一次被局域网发现才出现；
     * 如果这里只遍历 `peers`，那"重启后历史还在磁盘上、但列表空着"就会变成
     * "重启即丢消息"。所以取 `peers` 与 `messages` 的并集：有真实对端记录时用它的
     * 昵称/在线状态，没有时先落成一条离线会话（标题退回 nodeId 前 8 位），
     * 等对端重新上线时再被真实记录顶替 —— 同一个 nodeId 不会重复出现。
     *
     * 第三类来源是**群**：`settings.groups` 里的每个群都必须在列表里，哪怕还没说过话。
     * 刚被拉进一个群却看不到它，用户会以为对方发错了；而对端那边的成员表里明明有我们，
     * 消息随时会来。
     */
    val conversations: List<ConversationUi>
        get() {
            val real = peers.associateBy { it.nodeId }
            val groupsByConv = settings.groups.associateBy { ConvId.group(it.groupId) }
            // 会话摘要：会话列表的 id 集合、预览、最后消息时间全部来自它。
            //
            // 空 ⇒ 这个壳没接摘要（测试/预览/未接存储）：退回从已加载消息推导同一份形状，
            // 行为与改造前逐字一致。生产路径永远走 conversationSummaries。
            val summariesByConv: Map<String, ConversationSummaryRow> =
                if (conversationSummaries.isEmpty()) {
                    messages.mapNotNull { (convId, list) ->
                        val last = list.lastOrNull() ?: return@mapNotNull null
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
                    groupsByConv.containsKey(key) || real.containsKey(key) || (!com.kapp.tidings.core.protocol.IpAddressUtils.isIpv4(key) && !com.kapp.tidings.core.protocol.IpAddressUtils.isIpv6(key) && !key.contains(':'))
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

    /**
     * 群会话预览该署的**发送者显示名**；非群会话恒 null。
     *
     * 为什么会有它：群行预览过去只有「你：」一种前缀 —— 群里多人说话，列表里一句
     * 「周五聚餐」分不清是谁说的。名字的解析走 [resolveSenderIdentity] + [senderContext]，
     * 与会话标题（[convTitle]）、气泡署名是**同一个解析器**；这里绝不自拼 senderId
     * （`agent:<host>:<id>` 一类身份键拼出来的是内部形态，不是人名）。
     *
     * 返回 null 的每一档都是「如实」而不是丢失：
     * - 最后一条不在内存窗口里（摘要还在、消息没加载）—— 不知道是谁，就不署；
     * - 发送者没署名（旧端帧、系统提示行）；
     * - 最后一条是自己说的 —— 界面另有「你：」前缀管这一档。
     */
    fun previewSenderNameOf(convId: String): String? {
        if (groupOfConv(convId) == null) return null
        val last = messages[convId]?.lastOrNull() ?: return null
        val sender = last.senderId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (sender == selfNodeId) return null
        return resolveSenderIdentity(sender, senderContext(convId))?.displayName
    }

    /** 会话还没有名字时的兜底：群退回 groupId 前缀，私聊退回 nodeId 前缀。 */
    private fun fallbackTitle(convId: String): String =
        (ConvId.groupIdOf(convId) ?: convId).take(8)

    /**
     * 一个会话该显示的名字：群名 → 对端昵称 → id 前缀。
     *
     * 对端还没被重新发现时只能退回前缀；和 [PeerUi.displayName] 的最后一级兜底保持一致，
     * 避免同一个会话前后两种叫法。群会话退回的是 groupId 前缀，不是 `group:xx` ——
     * 会话键的前缀是实现细节，不该出现在用户眼前。
     *
     * **别绕过它自己写 `peer(convId)?.displayName ?: convId.take(8)`**：`convId` 是
     * **会话键**而不是节点 id，群会话的键是 `group:<groupId>`，在节点表里永远查不到人，
     * 于是群名显示成 `group:3a`（真机与模拟器上都拍到过）。原来这句话挂在
     * 聊天详情标签条的 `sessionTabsOf()` 上，那条标签条已移除，教训搬到这里。
     *
     * 公开出来是因为**不止会话列表要它**：文件页把一条带附件的消息折算成一行时，
     * 也得用同一种叫法。两处各写一遍的结果是同一个群在两个页面上有两个名字。
     */
    fun convTitle(convId: String): String {
        groupOfConv(convId)?.name?.let { return it }
        if (!ConvId.isGroup(convId)) {
            // 1:1 会话标题也走**同一个解析器**：agent 节点的会话就是「跟那个 agent 说话」
            // （1:1 的每条消息都触发它），标题因此必须是 agent 名（`DSH`），
            // 而不是那台宿主机器的昵称（用户看到的是「mac mini」，找不到 DSH）。
            resolveSenderIdentity(convId, senderContext(convId))?.displayName?.let { return it }
        }
        return fallbackTitle(convId)
    }
}

/**
 * [RelayUiState.agentGrants] 的键 = `peerId + "/" + (agentKey ?: "") + if (wsId != null) "/ws/$wsId" else ""`。
 *
 * 空串后缀代表整台设备那一档（`scope = "all"` 的帧没有 `agentKey`）。写成唯一的构造函数
 * 而不是让入站处理、查询、落库三处各拼一遍：拼错一处就是两条会互相看不见的记录
 * （症状是"批了却还显示未申请"，最难查的那类）。
 */
internal fun grantKeyOf(peerId: String, agentKey: String? = null, wsId: String? = null): String =
    peerId + "/" + (agentKey ?: "") + if (wsId != null) "/ws/$wsId" else ""

