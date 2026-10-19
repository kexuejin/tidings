// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.RelayController.Companion.MAX_AGENT_EVENTS
import app.relay.app.model.AgentApprovalUi
import app.relay.app.model.AgentArtifactKey
import app.relay.app.model.AgentArtifactUi
import app.relay.app.model.AgentEventUi
import app.relay.app.model.AgentQueueTaskUi
import app.relay.app.model.AgentRunKey
import app.relay.app.model.AgentRunUi
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.GrantUi
import app.relay.app.model.WorkspaceKey
import app.relay.app.model.WorkspaceUi
import app.relay.core.nowMillis
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.transport.SendOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * agent 协议帧的入站处理：会话、事件、队列、执行、审批、取消、回执、产物。
 *
 * 切出来的理由：这一族的演化动力与其它 7 个分片互不相干。过去它们都是
 * `internal fun RelayController.` 的顶层扩展，凑在一起约 3000 行；改其中一族要在
 * 另外六族里找它依赖了什么。
 *
 * @param host 提供本族用到的控制器成员与同包顶层扩展。逐个 `host.` 前缀而不是
 *   持有整个控制器，是为了让**依赖面完全显式**：读方法体就知道这一族碰了哪些状态。
 *   只对本族真正用到的名字加前缀 —— 同名的函数形参会遮蔽控制器成员（`state` 就是
 *   一例：控制器有 `state: StateFlow<RelayUiState>`，而 agent 帧形参叫 `state: String?`），
 *   给它加前缀会直接产出语法垃圾。
 */
internal class RelayControllerAgentComponent(
    private val host: RelayController,
) {
    private val notifiedApprovals = mutableSetOf<String>()
    private val notifiedRuns = mutableSetOf<String>()

/**
 * [RelayController] 的智能体（Agent A2A）域扩展。
 *
 * 覆盖 PPE-1 §6.1 / §6.2 会话协商与流式事件收发。
 */

@Suppress("LongParameterList")
suspend fun handleSendAgentSession(
    peerId: String,
    sessionId: String,
    alias: String? = null,
    agentKind: String? = null,
    state: String? = null,
    title: String? = null,
    startedAt: Long? = null,
    agentId: String? = null,
    wsId: String? = null,
    wsTitle: String? = null,
    wsPath: String? = null,
): SendOutcome? {
    val rt = host.runtime ?: return null
    if (!rt.peerDeclares(peerId, MsgTypes.CAP_AGENT_SESSION)) {
        return null
    }
    // D1 的 cap 门控不住在这里：投递层 NodeOutbound.sendAgentSession 统一按
    // 对端是否声明 `aws1` 决定附不附带新键（三个发送方共用一份判据）。
    return rt.sendAgentSession(
        peerId, sessionId, alias, agentKind, state, title, startedAt,
        agentId, wsId, wsTitle, wsPath,
    )
}


suspend fun handleSendAgentEvent(
    peerId: String,
    sessionId: String,
    cursor: Long,
    eventType: String,
    summary: String? = null,
    body: String? = null,
    ref: String? = null,
): SendOutcome? {
    val rt = host.runtime ?: return null
    if (!rt.peerDeclares(peerId, MsgTypes.CAP_AGENT_SESSION)) {
        return null
    }
    return rt.sendAgentEvent(peerId, sessionId, cursor, eventType, summary, body, ref)
}


fun handleOnAgentSession(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val at = nowMillis()
    var updatedSession: AgentSessionUi? = null
    host._state.update { s ->
        val sessionId = stringField(payload, "sessionId") ?: return@update s
        val key = AgentSessionKey(peerId = envelope.from, sessionId = sessionId)
        val session = agentSessionFrom(payload, envelope.from, at, prev = s.agentSessions[key])
            ?: return@update s
        updatedSession = session
        // 是不是**真的新行**（此前没见过这个 sessionId）。分页的自愈全靠它：宿主答了
        // 一页、行是新的 ⇒ 「这个工作区已到底」的结论当场作废。
        val isNew = key !in s.agentSessions
        // PPE-1 增补 D8：某工作区会话帧到达时摘除其分页拉取中的标记，恢复「加载更多」按钮交互
        val pageKey = session.wsId?.let { sessionPageKeyOf(envelope.from, it) }
        val nextLoading = if (pageKey != null && pageKey in s.sessionPageLoading) {
            s.sessionPageLoading - pageKey
        } else {
            s.sessionPageLoading
        }
        val nextExhausted = if (pageKey != null && isNew) {
            s.sessionPageExhausted - pageKey
        } else {
            s.sessionPageExhausted
        }
        s.copy(
            agentSessions = s.agentSessions + (key to session),
            sessionPageLoading = nextLoading,
            sessionPageExhausted = nextExhausted,
        )
    }
    updatedSession?.let { session ->
        host.scope.launch {
            runCatching { host.agentStore.saveSession(session) }
        }
    }
}


/**
 * 入站 `agent-workspace`（PPE-1 增补 D3）：宿主宣告**一个**工作区。
 *
 * 与 [handleOnAgentSession] 同一套纪律：`_state.update` 里算键与合并、锁外 `runCatching` 落库，
 * 解析交给 [workspaceFrom]（纯函数，能单测）。
 *
 * 键怎么定：`agentKey` 是内存键的一部分，而帧里**没有** `agentId`（父稿 §2.2 的键表），
 * 所以带 `agentKind` 时按它归行，没带时退回"这个 (node, wsId) 上唯一的那一行" ——
 * 有歧义（同 wsId 两个 agent 各有一行）时宁可落回 `agentKey = null` 那一格，
 * 也不猜它是谁的（父稿 R1/R3：归组必须有身份依据）。
 *
 * 换 agentKey 时旧键那行**必须一起删**：磁盘上的主键是 `(node_id, ws_id)`（`agent_key`
 * 不进主键，见 `RelayDatabase.sq` 的理由），冷启动只会读回一行。内存里留着旧键的话，
 * 表现就是"多出一行、重启又自己消失" —— 那种不一致最难查，因为它只在重启前后各对一半。
 */
fun handleOnAgentWorkspace(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val rawWsId = stringField(payload, "wsId")?.takeIf { it.isNotBlank() } ?: return
    if (isSyntheticWsId(rawWsId)) {
        return
    }
    val at = nowMillis()
    var saved: WorkspaceUi? = null
    host._state.update { s ->
        val wsId = rawWsId
        val incomingKind = stringField(payload, "agentKind")?.takeIf { it.isNotBlank() }
        val prevEntry = s.agentWorkspaces.entries.firstOrNull { entry ->
            entry.key.peerId == envelope.from && entry.key.wsId == wsId &&
                (incomingKind == null || entry.key.agentKey == incomingKind)
        }
        val workspace = workspaceFrom(payload, envelope.from, at, prevEntry?.value) ?: return@update s
        val key = WorkspaceKey(envelope.from, workspace.agentKey, wsId)
        val stale = prevEntry?.key?.takeIf { it != key }
        val kept = stale?.let { s.agentWorkspaces - it } ?: s.agentWorkspaces
        saved = workspace
        s.copy(agentWorkspaces = kept + (key to workspace))
    }
    saved?.let { workspace ->
        host.scope.launch {
            runCatching { host.agentStore.saveWorkspace(workspace) }
        }
    }
}

fun handleRecordLocalWorkspace(
    wsId: String,
    wsTitle: String?,
    wsPath: String?,
    agentKey: String?,
    agentKind: String?,
    online: Boolean?,
    sessionCount: Long?,
    order: Long?,
    at: Long,
) {
    if (isSyntheticWsId(wsId)) return
    var saved: WorkspaceUi? = null
    host._state.update { s ->
        val selfId = s.selfNodeId
        val prevEntry = s.agentWorkspaces.entries.firstOrNull { entry ->
            entry.key.peerId == selfId && entry.key.wsId == wsId &&
                (agentKey == null || entry.key.agentKey == agentKey)
        }
        val workspace = WorkspaceUi(
            peerId = selfId,
            agentKey = agentKey ?: agentKind,
            agentId = null,
            wsId = wsId,
            wsTitle = wsTitle ?: prevEntry?.value?.wsTitle,
            wsPath = wsPath ?: prevEntry?.value?.wsPath,
            agentKind = agentKind ?: prevEntry?.value?.agentKind,
            online = online ?: prevEntry?.value?.online ?: true,
            sessionCount = sessionCount ?: prevEntry?.value?.sessionCount,
            at = at,
            hostOrder = order ?: prevEntry?.value?.hostOrder,
        )
        val key = WorkspaceKey(selfId, workspace.agentKey, wsId)
        val stale = prevEntry?.key?.takeIf { it != key }
        val kept = stale?.let { s.agentWorkspaces - it } ?: s.agentWorkspaces
        saved = workspace
        s.copy(agentWorkspaces = kept + (key to workspace))
    }
    saved?.let { ws ->
        host.scope.launch {
            runCatching { host.agentStore.saveWorkspace(ws) }
        }
        host.groupsCtl.handleSyncWorkspaceGroups()
    }
}

fun handleRecordLocalSession(
    sessionId: String,
    alias: String?,
    agentKind: String?,
    state: String?,
    title: String?,
    startedAt: Long?,
    agentId: String?,
    wsId: String?,
    wsTitle: String?,
    wsPath: String?,
    updatedAt: Long?,
) {
    var saved: AgentSessionUi? = null
    host._state.update { s ->
        val selfId = s.selfNodeId
        val key = AgentSessionKey(peerId = selfId, sessionId = sessionId)
        val existing = s.agentSessions[key]
        val updated = AgentSessionUi(
            peerId = selfId,
            sessionId = sessionId,
            alias = alias ?: existing?.alias,
            agentKind = agentKind ?: existing?.agentKind,
            state = state ?: existing?.state,
            title = title ?: existing?.title,
            startedAt = startedAt ?: existing?.startedAt,
            agentId = agentId ?: existing?.agentId,
            wsId = wsId ?: existing?.wsId,
            wsTitle = wsTitle ?: existing?.wsTitle,
            wsPath = wsPath ?: existing?.wsPath,
            updatedAt = updatedAt ?: existing?.updatedAt ?: nowMillis(),
            hostUpdatedAt = updatedAt ?: existing?.hostUpdatedAt,
            announced = true,
        )
        saved = updated
        val isNew = key !in s.agentSessions
        val pageKey = updated.wsId?.let { sessionPageKeyOf(selfId, it) }
        val nextLoading = if (pageKey != null && pageKey in s.sessionPageLoading) {
            s.sessionPageLoading - pageKey
        } else {
            s.sessionPageLoading
        }
        val nextExhausted = if (pageKey != null && isNew) {
            s.sessionPageExhausted - pageKey
        } else {
            s.sessionPageExhausted
        }
        s.copy(
            agentSessions = s.agentSessions + (key to updated),
            sessionPageLoading = nextLoading,
            sessionPageExhausted = nextExhausted,
        )
    }
    saved?.let { session ->
        host.scope.launch {
            runCatching { host.agentStore.saveSession(session) }
        }
    }
}

fun handleRecordLocalEvent(
    nodeId: String,
    sessionId: String,
    cursor: Long,
    eventType: String,
    summary: String?,
    body: String?,
    ref: String?,
    at: Long,
) {
    val incoming = AgentEventUi(
        peerId = nodeId,
        sessionId = sessionId,
        cursor = cursor,
        eventType = eventType,
        summary = summary,
        body = body,
        ref = ref,
        at = at,
    )
    var savedEvent: AgentEventUi? = null
    var updatedSession: AgentSessionUi? = null
    host._state.update { s ->
        val key = AgentSessionKey(peerId = nodeId, sessionId = sessionId)
        val merged = mergeAgentEvent(
            existing = s.agentEvents[key].orEmpty(),
            incoming = incoming,
            max = MAX_AGENT_EVENTS,
        ) ?: return@update s
        savedEvent = incoming
        val sessions = if (key in s.agentSessions) {
            val existing = s.agentSessions.getValue(key)
            val updated = existing.copy(updatedAt = at)
            updatedSession = updated
            s.agentSessions + (key to updated)
        } else {
            val autoCreated = AgentSessionUi(
                peerId = nodeId,
                sessionId = sessionId,
                updatedAt = at,
                announced = false,
            )
            updatedSession = autoCreated
            s.agentSessions + (key to autoCreated)
        }
        s.copy(
            agentEvents = s.agentEvents + (key to merged),
            agentSessions = sessions,
        )
    }
    savedEvent?.let { event ->
        host.scope.launch {
            updatedSession?.let { session -> runCatching { host.agentStore.saveSession(session) } }
            runCatching { host.agentStore.saveEvent(event) }
        }
    }
}

/**
 * 入站 `agent-grant`（PPE-1 增补 D7）：宿主对"这台设备能不能看我的 agent"表了态。
 *
 * 三条动作，都跟"沉默 ≠ 拒绝"有关：
 * 1. 覆盖写 `(peer, agentKey)` 那一格（同一格重复到达按最新一次算 —— 授权本来就是可变的）；
 * 2. **清掉本端对这个节点的 PENDING 记账**：宿主已经答了，无论答的是什么，
 *    "还在等回复"这句就不再成立。留着的表现是一个永远转圈的徽标。
 *    按整个节点清（而不是只清发申请时那个键）：申请 device 级、回答 agent 级这种错位
 *    在 `request` 档里是合法的，此时那条申请同样已经有了下文。
 * 3. 落盘 —— `revoked` 也要落，否则重启后"已授权"复活，界面上会显示一件已经不存在的事实。
 */
fun handleOnAgentGrant(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val at = nowMillis()
    var saved: GrantUi? = null
    host._state.update { s ->
        val grant = grantFrom(payload, envelope.from, at) ?: return@update s
        saved = grant
        s.copy(
            agentGrants = s.agentGrants + (grantKeyOf(grant.peerId, grant.agentKey, grant.wsId) to grant),
            pendingGrantRequests = s.pendingGrantRequests.filterNot { it.startsWith(envelope.from + "/") }.toSet(),
        )
    }
    saved?.let { grant ->
        host.scope.launch {
            runCatching { host.agentStore.saveGrant(grant) }
        }
    }
}

/**
 * 入站 `agent-grant-list`（PPE-1 增补 D7）：宿主把"你被授权看哪些"逐条回过来。
 *
 * 一帧一条，所以这里每收到一条就写一格 —— 不需要"等齐"这件事：清单的语义是
 * 若干条独立生效的授权，缺一条只是少显示一条，不影响已经收到的那些。
 *
 * 每条都按 `approved` 入库（判据在 [grantListEntryFrom] 的 KDoc：**宿主只把生效的授权
 * 回进清单**）。因此这条路径不会产生 `denied` / `revoked` —— 那两个取值只能从
 * `agent-grant` 帧来。
 */
fun handleOnAgentGrantList(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val at = nowMillis()
    var saved: GrantUi? = null
    host._state.update { s ->
        val grant = grantListEntryFrom(payload, envelope.from, at) ?: return@update s
        saved = grant
        s.copy(
            agentGrants = s.agentGrants + (grantKeyOf(grant.peerId, grant.agentKey, grant.wsId) to grant),
            pendingGrantRequests = s.pendingGrantRequests.filterNot { it.startsWith(envelope.from + "/") }.toSet(),
        )
    }
    saved?.let { grant ->
        host.scope.launch {
            runCatching { host.agentStore.saveGrant(grant) }
        }
    }
}

/**
 * 出站 `agent-grant-request`（PPE-1 增补 D7）：向宿主申请看它的 agent。
 *
 * 门控只查 `ag1`（"这是一个 agent 节点"）：申请是发给**宿主**的，而宿主认不认这套
 * 回路不是本端能在发送点判的（`auto` 档的宿主根本不答，那是策略、不是错误）。
 * 对普通联系人发申请没有任何意义，所以这一道闸要有。
 *
 * **PENDING 只记在内存**（[RelayUiState.pendingGrantRequests]），重启退回 NONE。这是取舍，
 * 理由要说清：PENDING 记录的是"我刚发过一条申请"，而这条申请后来有没有被投出去、
 * 宿主有没有答过、答的是哪一档，本端一概不知道 —— 把它落盘，等于让一个进程内的
 * 乐观记账活成一个持久的结论（"已申请待批"永远挂着，用户以为对面还在看）。
 * 退回 NONE 的代价只是"要再点一次申请"，而重发一条申请是幂等的（宿主按 `(nodeId, agentKey)`
 * 记授权，不是按申请条数）。反过来落盘的收益是零：宿主的真实答复一到就被 [handleOnAgentGrant] 覆盖。
 */
suspend fun handleSendGrantRequest(
    peerId: String,
    agentKey: String? = null,
    reason: String? = null,
    wsId: String? = null,
    access: String? = null,
): SendOutcome? {
    val rt = host.runtime ?: return null
    if (!rt.peerDeclares(peerId, MsgTypes.CAP_AGENT)) return null
    // 门控必须也查 `agr1`：`agent-grant-request` 在 RELIABLE 里，而 core 的 sendUserMessage
    // 失败时会 `registry.markOffline` + 入离线补发队列。对一台**不声明 agr1 的旧版宿主**发它，
    // ACK 永远等不到 ⇒ 把一台在线电脑误标成离线（症状：它还能收聊天，手机却显示离线），
    // 而那条申请会在队列里躺满重发窗口。这与 agent-workspace 首版是同一个坑
    // （core 侧 `ReliableNoQueueTest` 钉住通道语义；这里是同一判据的发送侧落点）。
    // UI 侧的对应处理：此时「申请查看」按钮应禁用并给原因，而不是按了没反应。
    if (!rt.peerDeclares(peerId, MsgTypes.CAP_AGENT_GRANT)) return null
    val outcome = rt.sendAgentGrantRequest(peerId, agentKey, wsId, access, reason, nowMillis())
    // SENT 与 QUEUED 都算"这条申请已经交出去了"：QUEUED 是离线补发队列，
    // 帧本身没有丢，界面上"已申请待批"说的是这件事，不是"对方已经收到"。
    val key = grantKeyOf(peerId, agentKey, wsId)
    host._state.update { s -> s.copy(pendingGrantRequests = s.pendingGrantRequests + key) }
    // 超时自愈保底（与 [handleRequestSessionPage] 同一条纪律）：宿主**沉默**是常态 ——
    // `auto` 档的宿主根本不答，用户也可能永远不去桌面端点那个按钮。而 PENDING 记账
    // 只靠入站 `agent-grant` / `agent-grant-list` 清除（见 [handleOnAgentGrant]），
    // 那两条路都不来的时候，界面上的徽标会**永远转圈**，用户以为对面还在看。
    //
    // 10 秒后无条件摘除：这不是"判定被拒"（沉默 ≠ 拒绝，协议立场不变），
    // 而是**不再替用户宣称"还在等"** —— 摘掉之后界面回到 NONE，用户可以再点一次申请，
    // 而重发是幂等的（宿主按 `(nodeId, agentKey)` 记授权）。
    host.scope.launch {
        delay(GRANT_REQUEST_PENDING_TTL_MS)
        host._state.update { s ->
            if (key in s.pendingGrantRequests) {
                s.copy(pendingGrantRequests = s.pendingGrantRequests - key)
            } else {
                s
            }
        }
    }
    return outcome
}

/**
 * 向宿主要一页会话清单（PPE-1 增补 D8）。
 *
 * 手机点开某个工作区、或点「加载更多」时调用。宿主收到后用它**已有的** agent-session
 * 帧逐条回填，所以这里只发请求，落库/分组/UI 全走现成的那条路。
 *
 * ## 门控查 `agp1`（不是 `agn1`）
 *
 * 两个能力是两个方向：`agn1` = 「我看得懂你推来的会话帧」（收），`agp1` = 「我能向你
 * 要历史会议」（发）。对不声明 `agp1` 的旧宿主发这条请求，它既不认识也不回 ACK ——
 * 而 core 的 sendReliableNoQueue 失败不改在线状态、不入队（见 [MsgTypes.CAP_AGENT_SESSION_PAGE]
 * 的 KDoc），所以代价只是"白等"，不会把在线设备标成离线。界面的对应处理是：
 * 不声明时**不画**「加载更多」，而不是画一个点了没反应的按钮。
 *
 * ## 为什么 offset 由调用方给
 *
 * 分页游标就是"我已经有多少条了"。用 [RelayUiState.agentSessionsOf] 的条数当 offset
 * 是唯一不会漂的算法 —— 自己维护一个"下一页从哪开始"的计数器，在会话帧还会零散到达
 * （宿主推活动会话）的情况下迟早对不上。
 *
 * @return false = 没发出去（对端不声明 `agp1`、或没有 runtime）。
 */
suspend fun handleRequestSessionPage(peerId: String, wsId: String, offset: Long, limit: Long? = null): Boolean {
    val isLocal = peerId == host._state.value.selfNodeId
    val rt = host.runtime
    if (!isLocal) {
        if (rt == null) return false
        if (!rt.peerDeclares(peerId, MsgTypes.CAP_AGENT)) return false
        if (!rt.peerDeclares(peerId, MsgTypes.CAP_AGENT_SESSION_PAGE)) return false
    }
    // 先挂上「加载中」再发。反过来（先发再挂）在快速返回时会漏掉一次 UI 状态更新，
    // 表现是按钮闪一下又变回可点。
    val key = sessionPageKeyOf(peerId, wsId)
    // 首页请求（offset = 0）就是「从头再拉一遍」：这一格上此前那条「已到底」的结论当场作废。
    // 不撤的话，用户重开工作区时宿主可能已经多出会话，而「加载更多」永远不会再出现。
    val isFirstPage = offset == 0L
    // 这一页发出去之前本地的条数（**未过滤**原始表，与 offset 同分母）—— 5 秒后拿它比对，
    // 一条新行都没来就说明这个工作区确实没有下一页了。
    val before = host._state.value.agentSessionCountOf(peerId, wsId)
    host._state.update { s ->
        s.copy(
            sessionPageLoading = s.sessionPageLoading + key,
            sessionPageExhausted = if (isFirstPage) s.sessionPageExhausted - key else s.sessionPageExhausted,
        )
    }
    val sent = if (isLocal) {
        host.onLocalSessionPageRequest?.invoke(peerId, wsId, offset, limit) ?: false
    } else {
        rt?.sendAgentSessionPage(peerId, wsId, offset, limit, nowMillis()) ?: false
    }
    if (!sent) {
        // 没发出去就立刻摘掉标记：留着它等于向用户承诺一件不会发生的事。
        host._state.update { it.copy(sessionPageLoading = it.sessionPageLoading - key) }
    } else {
        // 超时自愈保底：宿主若读不到会话保持沉默、或网络丢包，5 秒后自动清除加载态，
        // 避免界面上的「加载更多」按钮陷入永久不可用的假死状态。
        host.scope.launch {
            delay(5_000L)
            host._state.update { s ->
                val loading = if (key in s.sessionPageLoading) {
                    s.sessionPageLoading - key
                } else {
                    s.sessionPageLoading
                }
                // 这一页要完还是没来新行 ⇒ 记下「已到底」，把按钮收掉。
                // 反过来若真来了新行，标记早被 `handleOnAgentSession` 撤掉了，这里只是保持。
                // 为什么不在收到帧时直接判：宿主可以对同一批行重发（分页边界重叠），
                // 「来帧了」不等于「还有下一页」；只有条数真的涨了才算。
                val exhausted = if (s.agentSessionCountOf(peerId, wsId) > before) {
                    s.sessionPageExhausted - key
                } else {
                    s.sessionPageExhausted + key
                }
                s.copy(sessionPageLoading = loading, sessionPageExhausted = exhausted)
            }
        }
    }
    return sent
}

/**
 * 某个工作区还有没有下一页（PPE-1 增补 D8）。
 *
 * 判据是「本地已知的会话数 < 工作区自报的会话数」。为什么用自报数当上界、而不是
 * 「上一页是不是满的」：后者在最后一页刚好装满时会多要一次空页，用户看到"加载更多"
 * 点下去什么都没发生。
 *
 * 为什么要数**手机落库的条数**而不是请求了几页：宿主可能对同一 sessionId 发过两次
 * （活动会话的增量帧叠加分页帧），按条数数自然免疫重复（同一键只算一条）。
 */

fun handleOnAgentEvent(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val at = nowMillis()
    var savedEvent: AgentEventUi? = null
    var updatedSession: AgentSessionUi? = null
    host._state.update { s ->
        val incoming = agentEventFrom(payload, envelope.from, at) ?: return@update s
        val key = AgentSessionKey(peerId = incoming.peerId, sessionId = incoming.sessionId)
        val merged = mergeAgentEvent(
            existing = s.agentEvents[key].orEmpty(),
            incoming = incoming,
            max = MAX_AGENT_EVENTS,
        ) ?: return@update s
        savedEvent = incoming
        val sessions = if (key in s.agentSessions) {
            val existing = s.agentSessions.getValue(key)
            val updated = existing.copy(updatedAt = at)
            updatedSession = updated
            s.agentSessions + (key to updated)
        } else {
            val autoCreated = AgentSessionUi(
                peerId = envelope.from,
                sessionId = incoming.sessionId,
                updatedAt = at,
                announced = false,
            )
            updatedSession = autoCreated
            s.agentSessions + (key to autoCreated)
        }
        s.copy(
            agentEvents = s.agentEvents + (key to merged),
            agentSessions = sessions,
        )
    }
    savedEvent?.let { event ->
        host.scope.launch {
            updatedSession?.let { session -> runCatching { host.agentStore.saveSession(session) } }
            runCatching { host.agentStore.saveEvent(event) }
        }
    }
}


fun handlePruneAgentSessions(now: Long = nowMillis()) {
    host._state.update { s ->
        val (healedSessions, healedList) = healStaleBusySessions(s.agentSessions, s.agentEvents, now)
        if (healedList.isNotEmpty()) {
            host.scope.launch {
                healedList.forEach { healed ->
                    runCatching { host.agentStore.saveSession(healed) }
                }
            }
        }
        val (prunedSessions, prunedEvents) = pruneEndedSessions(healedSessions, s.agentEvents, now)
        if (prunedSessions.size == s.agentSessions.size && prunedEvents.size == s.agentEvents.size && healedList.isEmpty()) {
            s
        } else {
            s.copy(agentSessions = prunedSessions, agentEvents = prunedEvents)
        }
    }
}


fun handleRestoreAgentData() {
    host.scope.launch {
        val loadedSessions = runCatching { host.agentStore.loadSessions() }.getOrNull().orEmpty()
        val loadedEvents = runCatching { host.agentStore.loadEvents() }.getOrNull().orEmpty()
        val loadedApprovals = runCatching { host.agentStore.loadApprovals() }.getOrNull().orEmpty()
        val loadedTasks = runCatching { host.agentStore.loadTasks() }.getOrNull().orEmpty()
        val loadedRuns = runCatching { host.agentStore.loadRuns() }.getOrNull().orEmpty()
        val loadedArtifacts = runCatching { host.agentStore.loadArtifacts() }.getOrNull().orEmpty()
        // 工作区与授权落盘后在此一同恢复，避免重启导致本地缓存丢失。
        // 恢复顺序：内存新数据优先于磁盘恢复数据。
        val loadedWorkspaces = runCatching { host.agentStore.loadWorkspaces() }.getOrNull().orEmpty()
        val loadedGrants = runCatching { host.agentStore.loadGrants() }.getOrNull().orEmpty()
        if (
            loadedSessions.isEmpty() && loadedEvents.isEmpty() && loadedApprovals.isEmpty() &&
            loadedTasks.isEmpty() && loadedRuns.isEmpty() && loadedArtifacts.isEmpty() &&
            loadedWorkspaces.isEmpty() && loadedGrants.isEmpty()
        ) {
            return@launch
        }
        // 恢复前重打 updatedAt：盘上那个值是**上次运行**收帧的时刻，原样带进来会撞
        // `pruneEndedSessions` 的 7 天 TTL —— 停机超过 7 天的会话重启后第一轮 prune 就被
        // 判过期清掉，而它刚刚才回到界面上（用户看到的是「刚恢复就少了几条」）。
        // 重打后语义仍与 `updatedAt` 的定义一致：这条记录**这次**是什么时候回到我手上的。
        // hostUpdatedAt（宿主侧活动时刻）**不重打** —— 那是宿主的事实，本端不许改写。
        val restoredAt = nowMillis()
        val restoredSessions = loadedSessions.mapValues { (_, session) ->
            session.copy(updatedAt = restoredAt)
        }
        host._state.update { s ->
            // 合并顺序是 **内存里的赢**（`Map.plus` 的右操作数覆盖左）。恢复是异步的，
            // 这一瞬间可能已经收到了新帧 —— 拿磁盘上的旧值盖掉刚到的真值就是往回退，
            // 而审批的 `status` 尤其经不起这种回退（点过的「允许」会变回「待审批」）。
            s.copy(
                agentSessions = restoredSessions + s.agentSessions,
                agentEvents = loadedEvents + s.agentEvents,
                agentApprovals = loadedApprovals + s.agentApprovals,
                agentTasks = loadedTasks + s.agentTasks,
                agentRuns = loadedRuns + s.agentRuns,
                agentArtifacts = loadedArtifacts + s.agentArtifacts,
                agentWorkspaces = loadedWorkspaces + s.agentWorkspaces,
                agentGrants = loadedGrants + s.agentGrants,
            )
        }
        host.groupsCtl.handleSyncWorkspaceGroups()
    }
}


fun handleOnAgentApproval(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val approvalId = stringField(payload, "approvalId") ?: return
    val sessionId = stringField(payload, "sessionId") ?: return
    val tool = stringField(payload, "tool")
    val summary = stringField(payload, "summary") ?: stringField(payload, "note")
    val expiresAt = longField(payload, "expiresAt") ?: 0L
    val choices = stringField(payload, "choices")
    val riskLevel = stringField(payload, "riskLevel")
    val diff = stringField(payload, "diff")
    val approvalUi = AgentApprovalUi(
        approvalId = approvalId,
        sessionId = sessionId,
        peerId = envelope.from,
        tool = tool,
        summary = summary,
        expiresAt = expiresAt,
        choices = choices,
        status = "pending",
        riskLevel = riskLevel,
        diff = diff,
    )
    host._state.update { s ->
        s.copy(agentApprovals = s.agentApprovals + (approvalId to approvalUi))
    }
    // 落盘：审批的**处置结果**是本端产生的，对端无从重算 —— 不存就等于
    // 「点完允许、重启又回到待审批」，而那条 `agent-ack` 其实早就发过了。
    host.scope.launch { runCatching { host.agentStore.saveApproval(approvalUi) } }

    // Goal 6: 零中心服务器离线消息与本地通知策略（幂等去重提醒）
    if (notifiedApprovals.add(approvalId)) {
        val peerName = host._state.value.peer(envelope.from)?.displayName ?: envelope.from.take(8)
        val notifBody = listOfNotNull(tool, summary).joinToString(": ").ifBlank { approvalId }
        host.showNotification(peerName, notifBody, envelope.from)
    }
}


fun handleOnAgentAck(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val ackOf = stringField(payload, "ackOf") ?: return
    val kind = stringField(payload, "kind") ?: return
    var decided: AgentApprovalUi? = null
    host._state.update { s ->
        val current = s.agentApprovals[ackOf]
        if (current != null) {
            val nextStatus = when (kind) {
                "approved" -> "approved"
                "denied" -> "denied"
                else -> current.status
            }
            val next = current.copy(status = nextStatus)
            // 只有真的改了状态才写盘：`received` / `queued` 这类回执推进不了审批，
            // 让它们每次都写一遍只是白白放大写盘次数。
            if (next != current) decided = next
            s.copy(agentApprovals = s.agentApprovals + (ackOf to next))
        } else {
            s
        }
    }
    // 回执推进的结果同样要落盘 —— 否则「对端批准了」这件事只活到本次进程结束。
    decided?.let { host.scope.launch { runCatching { host.agentStore.saveApproval(it) } } }
}


fun handleOnAgentQueue(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val taskId = stringField(payload, "taskId") ?: return
    val sessionId = stringField(payload, "sessionId")
    val op = stringField(payload, "op") ?: return
    val prompt = stringField(payload, "prompt")
    val queuedAt = longField(payload, "queuedAt") ?: nowMillis()
    if (op == "dequeue") {
        host._state.update { s ->
            s.copy(agentTasks = s.agentTasks - taskId)
        }
        // 「移走」也要落到磁盘：否则重启后这条早就撤掉的任务又冒出来，用户以为它还在排。
        host.scope.launch { runCatching { host.agentStore.deleteTask(envelope.from, taskId) } }
    } else {
        val taskUi = AgentQueueTaskUi(
            taskId = taskId,
            sessionId = sessionId,
            peerId = envelope.from,
            op = op,
            prompt = prompt,
            queuedAt = queuedAt,
        )
        host._state.update { s ->
            s.copy(agentTasks = s.agentTasks + (taskId to taskUi))
        }
        host.scope.launch { runCatching { host.agentStore.saveTask(taskUi) } }
    }
}

/**
 * 入站 `agent-run`（PPE-1 §6.4）：对端报告一次执行的开始 / 结束。
 *
 * 与 [handleOnAgentSession] 同一纪律：`state` **原样存**，不认识的取值照样放行（`Codec` 的取值域
 * 故意留宽容，界面上原样透传）。同一 `(peerId, runId)` 重复到达是正常的（§6.4 的
 * 状态变更就是同一 runId 的再次宣告），所以这里是**覆盖**而不是追加。
 *
 * 这条帧以前没有接收分支 —— 对端发过来会被传输层 ACK 然后静默丢弃（仓库自己的
 * `scripts/e2e/e2e_agent.py` R85 曾把这件事写成一条"不覆盖"的边界）。现在补上了。
 */
fun handleOnAgentRun(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val sessionId = stringField(payload, "sessionId") ?: return
    val runId = stringField(payload, "runId") ?: return
    val state = stringField(payload, "state") ?: return
    val at = longField(payload, "at") ?: 0L
    val note = stringField(payload, "note")
    val run = AgentRunUi(
        runId = runId,
        sessionId = sessionId,
        peerId = envelope.from,
        state = state,
        at = at,
        note = note,
        updatedAt = nowMillis(),
    )
    host._state.update { s ->
        s.copy(agentRuns = s.agentRuns + (AgentRunKey(envelope.from, runId) to run))
    }
    host.scope.launch { runCatching { host.agentStore.saveRun(run) } }

    if (state == "failed" && notifiedRuns.add(runId)) {
        val peerName = host._state.value.peer(envelope.from)?.displayName ?: envelope.from.take(8)
        val notifBody = note ?: state
        host.showNotification(peerName, notifBody, envelope.from)
    }
}

/**
 * 入站 `agent-cancel`（PPE-1 §6.6）：对端取消一次执行，或（`runId` 省略时）取消整个会话。
 *
 * 语义与 §6.6 的规范一致：`runId` 为空 ⇒ 该会话下**所有未到终态**的执行都标成 `cancelled`；
 * 给了 `runId` ⇒ 只标那一条（不存在就补一条，因为"取消"本身也是这条 run 的一个状态）。
 *
 * 不删记录而是**改成 `cancelled`**：界面上"它被取消了"和"它从来没存在过"是两件事，
 * 删掉会让用户以为任务凭空消失。
 */
fun handleOnAgentCancel(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val sessionId = stringField(payload, "sessionId") ?: return
    val runId = stringField(payload, "runId")
    val reason = stringField(payload, "reason")
    val at = nowMillis()
    // 用 map 收集改动而不是 list：`host._state.update` 在并发下会重跑 lambda，map 天然去重，
    // 不会把同一条 run 写两遍。
    val changed = mutableMapOf<AgentRunKey, AgentRunUi>()
    host._state.update { s ->
        if (runId != null) {
            val key = AgentRunKey(envelope.from, runId)
            val existing = s.agentRuns[key]
            val cancelled = AgentRunUi(
                runId = runId,
                sessionId = existing?.sessionId ?: sessionId,
                peerId = envelope.from,
                state = "cancelled",
                at = existing?.at ?: 0L,
                note = reason ?: existing?.note,
                updatedAt = at,
            )
            changed[key] = cancelled
            s.copy(agentRuns = s.agentRuns + (key to cancelled))
        } else {
            // 省略 runId = 取消整个会话：把该会话下所有还没到终态的 run 都标掉。
            val touched = s.agentRuns.mapValues { (key, run) ->
                if (key.peerId == envelope.from && run.sessionId == sessionId && !run.isTerminal) {
                    run.copy(state = "cancelled", note = reason ?: run.note, updatedAt = at)
                        .also { changed[key] = it }
                } else {
                    run
                }
            }
            s.copy(agentRuns = touched)
        }
    }
    // 取消的结果同样要落盘：重启后一条已被取消的 run 退回「进行中」是明确的误导。
    if (changed.isNotEmpty()) {
        host.scope.launch { runCatching { changed.values.forEach { host.agentStore.saveRun(it) } } }
    }
}

/**
 * 入站 `agent-artifact`（PPE-1 §6.7）：对端报告一件产物的元数据。
 *
 * **不下载任何东西** —— 这里只记元数据；要拿内容得按 [AgentArtifactUi.fileId] 去走
 * 既有的 `file-msg` 路径。同一 `(peerId, artifactId)` 重复到达按覆盖处理（幂等）。
 */


/**
 * 入站 `agent-artifact`（PPE-1 §6.7）：对端报告一件产物的元数据。
 *
 * **不下载任何东西** —— 这里只记元数据；要拿内容得按 [AgentArtifactUi.fileId] 去走
 * 既有的 `file-msg` 路径。同一 `(peerId, artifactId)` 重复到达按覆盖处理（幂等）。
 */
fun handleOnAgentArtifact(envelope: Envelope) {
    val payload = envelope.payload as? JsonObject ?: return
    val sessionId = stringField(payload, "sessionId") ?: return
    val artifactId = stringField(payload, "artifactId") ?: return
    val name = stringField(payload, "name") ?: return
    val artifact = AgentArtifactUi(
        artifactId = artifactId,
        sessionId = sessionId,
        peerId = envelope.from,
        fileId = stringField(payload, "fileId"),
        name = name,
        bytes = longField(payload, "bytes"),
        mime = stringField(payload, "mime"),
        at = nowMillis(),
    )
    host._state.update { s ->
        s.copy(
            agentArtifacts = s.agentArtifacts + (AgentArtifactKey(envelope.from, artifactId) to artifact),
        )
    }
    host.scope.launch { runCatching { host.agentStore.saveArtifact(artifact) } }
}


fun handleRespondApproval(approvalId: String, allow: Boolean, allowSession: Boolean = false) {
    val approval = host._state.value.agentApprovals[approvalId] ?: return
    val rt = host.runtime
    val kind = if (allowSession) "allow_session" else if (allow) "approved" else "denied"
    val nextStatus = if (allowSession) "allow_session" else if (allow) "approved" else "denied"
    val decided = approval.copy(status = nextStatus)
    host._state.update { s ->
        s.copy(agentApprovals = s.agentApprovals + (approvalId to decided))
    }
    // **本条是 F2 的核心场景**：用户点的这一下是本端产生的唯一凭据，对端只会看到那条 ack。
    // 不落盘的话，重启之后面板又显示「待审批」，而用户已经点过了。
    host.scope.launch { runCatching { host.agentStore.saveApproval(decided) } }
    if (rt != null && rt.peerDeclares(approval.peerId, MsgTypes.CAP_AGENT_QUEUE)) {
        host.scope.launch {
            runCatching {
                rt.sendAgentAck(
                    peerId = approval.peerId,
                    ackOf = approvalId,
                    kind = kind,
                    at = nowMillis(),
                )
            }
        }
    }
}


fun handleCancelAgentTask(taskId: String) {
    val task = host._state.value.agentTasks[taskId] ?: return
    host._state.update { s ->
        s.copy(agentTasks = s.agentTasks - taskId)
    }
    // 落盘要放在 `rt == null` 的提前返回**之前**：没有运行时也不该把这条撤掉的任务留在磁盘上，
    // 否则下次启动它又回来了，而本端已经显示它被撤掉了。
    host.scope.launch { runCatching { host.agentStore.deleteTask(task.peerId, taskId) } }
    val rt = host.runtime ?: return
    if (!rt.peerDeclares(task.peerId, MsgTypes.CAP_AGENT_QUEUE)) return
    host.scope.launch {
        runCatching {
            rt.sendAgentQueue(
                peerId = task.peerId,
                taskId = taskId,
                sessionId = task.sessionId,
                // §6.3 只有 `enqueue` / `dequeue` 两个 op：**把排队中的任务撤下来就是
                // `dequeue`**，不是第三个叫 `cancel` 的值。此前这里发的是 `"cancel"`，
                // 会被 `Codec.validateAgentQueue` 拒收 —— 手机端于是发了一条自己都不收的帧，
                // 对端的队列里那条任务永远留着，而本端界面已经把它删掉了。
                // 取消**执行中**的 run 走 §6.6 的 `sendAgentCancel`，与本方法不是一回事。
                op = "dequeue",
                queuedAt = nowMillis(),
            )
        }
    }
}
}

/** 会话分页的加载标记键：按 (节点, 工作区) —— 分页是按工作区要的。 */
internal fun sessionPageKeyOf(peerId: String, wsId: String): String = "$peerId|$wsId"

/**
 * 「已申请待批」这个内存记账的最长存活时间。
 *
 * 选 10 秒而不是更长：它要盖住一次正常往返（局域网 ACK 通常远小于 1 秒），
 * 又不能长到让用户以为对面真的在看。超时只是摘掉乐观记账，不写任何"被拒"结论。
 */
internal const val GRANT_REQUEST_PENDING_TTL_MS = 10_000L

internal const val MAX_ENDED_SESSIONS_PER_PEER = 20

internal const val ENDED_SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 天

/**
 * 清理超期（>7天）或超量（每个 peer 超过 20 个）的已结束/归档会话（D-2 生命周期软淘汰），
 * 并同步剔除这些淘汰会话关联的事件内存，避免长时间运行无界增长。
 * 纯函数，线程安全，易于单测。
 *
 * ## 两把尺子，别混
 *
 * · **超量淘汰**（每个 peer 保留活动最新的 [maxEndedPerPeer] 条）按
 *   [AgentSessionUi.sortActivityAt] 排序 —— 与列表显示、与宿主的活动倒序同源。
 *   用 [AgentSessionUi.updatedAt] 排会退化：历史分页一页 20 条在同一个 TCP tick 里到达，
 *   updatedAt 全落同一毫秒，「淘汰谁」就成了到达顺序。
 * · **超期淘汰**（`now - updatedAt > endedTtlMs`）仍按 [AgentSessionUi.updatedAt]，问的是
 *   「这条陈旧记录在我这儿躺了多久」—— 语义见 `AgentUi.kt` 该字段的 KDoc。
 */
internal fun pruneEndedSessions(
    sessions: Map<AgentSessionKey, AgentSessionUi>,
    events: Map<AgentSessionKey, List<AgentEventUi>>,
    now: Long,
    maxEndedPerPeer: Int = MAX_ENDED_SESSIONS_PER_PEER,
    endedTtlMs: Long = ENDED_SESSION_TTL_MS,
): Pair<Map<AgentSessionKey, AgentSessionUi>, Map<AgentSessionKey, List<AgentEventUi>>> {
    val keptSessions = mutableMapOf<AgentSessionKey, AgentSessionUi>()
    val keysToRemove = mutableSetOf<AgentSessionKey>()

    val byPeer = sessions.entries.groupBy { it.key.peerId }
    for ((_, entries) in byPeer) {
        val (ended, active) = entries.partition { it.value.isEnded }

        // 活跃会话全部保留
        for (item in active) {
            keptSessions[item.key] = item.value
        }

        // 已结束会话按**活动时刻**降序（与列表显示同源），超量时先淘汰活动最旧的。
        // 超期判据另用 updatedAt（见本函数 KDoc 的「两把尺子」）。
        val sortedEnded = ended.sortedByDescending { it.value.sortActivityAt }
        sortedEnded.forEachIndexed { index, item ->
            val isExpired = (now - item.value.updatedAt) > endedTtlMs
            val isOverCap = index >= maxEndedPerPeer
            if (isExpired || isOverCap) {
                keysToRemove.add(item.key)
            } else {
                keptSessions[item.key] = item.value
            }
        }
    }

    if (keysToRemove.isEmpty()) {
        return sessions to events
    }

    val keptEvents = events.filterKeys { it !in keysToRemove }
    return keptSessions to keptEvents
}

/**
 * 自动识别并自愈僵死状态（Stale Busy）：
 * 当会话标记为 busy，但无论宿主还是事件流在 [timeoutMs] 内没有任何新动作时，
 * 自动将其状态恢复为 idle，防止因宿主异常退出/断网未送收尾帧导致永久冻结为 busy。
 */
internal fun healStaleBusySessions(
    sessions: Map<AgentSessionKey, AgentSessionUi>,
    events: Map<AgentSessionKey, List<AgentEventUi>>,
    now: Long,
    timeoutMs: Long = STALE_BUSY_TIMEOUT_MS,
): Pair<Map<AgentSessionKey, AgentSessionUi>, List<AgentSessionUi>> {
    val healedSessions = mutableMapOf<AgentSessionKey, AgentSessionUi>()
    val healedList = mutableListOf<AgentSessionUi>()

    for ((key, session) in sessions) {
        if (session.state == "busy") {
            val lastEventAt = events[key].orEmpty().maxOfOrNull { it.at } ?: 0L
            val lastActivity = maxOf(session.sortActivityAt, lastEventAt)
            if (lastActivity > 0L && (now - lastActivity) > timeoutMs) {
                val healed = session.copy(state = "idle")
                healedSessions[key] = healed
                healedList.add(healed)
                continue
            }
        }
        healedSessions[key] = session
    }

    return healedSessions to healedList
}
