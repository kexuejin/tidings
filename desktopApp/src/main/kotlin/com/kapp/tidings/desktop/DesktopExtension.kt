// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.AgentExecPolicyStore
import com.kapp.tidings.core.protocol.AgentGrantScopes
import com.kapp.tidings.domain.execDeviceRows
import com.kapp.tidings.domain.rebroadcastGroupMeta
import com.kapp.tidings.domain.ExecGrantView
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.agent.AgentGrantStore
import com.kapp.tidings.agent.AgentSessionRegistry
import com.kapp.tidings.agent.AgentWorkspaceRegistry
import com.kapp.tidings.agent.AgentInboxEnricher
import com.kapp.tidings.agent.ControlPlane
import com.kapp.tidings.agent.JsonObjectLike
import com.kapp.tidings.agent.GrantReplyOutcome
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.model.hasVisibleText
import com.kapp.tidings.core.session.NodeRuntime
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.agent_event_error_fallback
import com.kapp.tidings.generated.resources.agent_event_finished_fallback
import com.kapp.tidings.generated.resources.agent_event_thinking_only_fallback
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.kapp.tidings.core.protocol.AgentSenderId

/**
 * 桌面版的「扩展装配」：让 agent 与 UI 活在同一个进程里。
 *
 * ## 为什么不自己 new 一个 NodeRuntime
 * [RelayController] 已经拥有唯一的运行时（它 start 时按设置里的端口绑 UDP/TCP）。
 * 桌面壳如果再建一个 `NodeRuntime`，两个实例会抢同一个端口：后绑的那个要么失败，
 * 要么悄悄分流 UDP 报文，结果就是 UI 收不到消息、控制面也读不到 peer。
 * 所以这里只**读**控制器的运行时（[RelayController.activeRuntime]），把控制面挂上去。
 *
 * ## 为什么控制面要跟着运行时起落
 * 控制面只是一个 localhost 的 JSON 门面，它读的全是运行时的状态。运行时没了，
 * `/v1/peers` 就会返回一份空表 —— 那比 503 更糟，因为调用方会以为「真的没有节点」。
 * 运行时生命周期不暴露事件（见 [RelayController.stop]），所以这里轮询对齐。
 *
 * ## 为什么 agent 台账是这里建的
 * [ControlPlane] 要求记账的**出站**方与**入站**方共用同一个 [AgentSessionRegistry]；
 * 入站那一半由「订阅 `runtime.inbound` 的人」写。桌面端与 agentNode 是两个进程，
 * 所以桌面端必须自己订阅、自己持有，详见 [sessions] 的 KDoc。
 */
internal class DesktopExtension(
    private val controller: RelayController,
    private val scope: CoroutineScope,
    /** 0 = 由系统分配端口；实际端口读 [controlPlanePort]。 */
    private val httpPort: Int = 0,
    /** 空串 = 回环放行（见 [ControlPlane] 的 token 约定）。 */
    private val token: String = "",
) {
    private var plane: ControlPlane? = null

    /**
     * 当前控制面挂着的运行时。必须记引用而不是只记「已挂」：
     * `RelayController.restart()` 会新建运行时而未必出现可观察到的 null 窗口
     * （旧运行时被 stop 后马上换新的），只比「plane != null」会让控制面永远留在
     * 已死的旧运行时上，`/v1/peers` 就成了一份过期的空表。
     */
    private var attachedRuntime: NodeRuntime? = null
    private var followJob: Job? = null

    /**
     * 当前运行时的 agent 会话台账；未挂时为 null。
     *
     * ## 为什么桌面端要自己持有一个
     *
     * [ControlPlane] 的 `sessions` 形参要求「出站写入方与入站写入方共用同一个实例」：
     * 控制面只记自己**发出**的 agent 帧，**收到**的帧得由拥有 `runtime.inbound` 的
     * 那一方补上，否则面板只看得到一半会话（自己发的，看不到对方发的）。
     *
     * 在 agentNode 进程里，那一方是 `Main`；而桌面端是**另一个进程**，`Main` 里那个
     * 实例根本不在这里。所以这条约束在桌面端的正确落点就是：**本进程自己订阅
     * 本进程运行时的入站流，并把同一个实例交给控制面**（`ControlPlane` 的 `sessions` 形参
     * KDoc 说的就是这件事）。解析规则复用 `recordInbound`，与 `Main` 逐字同源。
     *
     * 随运行时重挂而重建：台账是**当前态**的视图（见 [AgentSessionRegistry] 的 KDoc），
     * 换了一个运行时之后，旧运行时的会话已经跟我们没关系了，留着比清掉更误导。
     */
    private var sessions: AgentSessionRegistry? = null

    /**
     * 入站订阅的 Job；与 [sessions] 同生命周期，[detach] 时一并取消。
     *
     * 订阅由 `ControlPlane.observeInbound` 托管（三张台账一份规则），
     * 它**必须**可取消：桌面端会随运行时重启重挂控制面，旧任务必须取消以防重复记账。
     */
    private var inboundJob: Job? = null

    /**
     * 两条兜底文案（事件没带 summary 时用）。
     *
     * 资源在 composeResources，取用 API 是 suspend；而这个回调
     * （`AgentSessionRegistry.onRecordEvent`）是**非 suspend** 的（agentNode 的签名，不在本任务范围内），
     * 所以这里 `by lazy` + `runBlocking` **只在第一次用到时**取一次并缓存 —— 不是每条事件都阻塞。
     * 取不到时给空串：调用点的 `hasVisibleText` 会把空文本挡掉（少一条气泡，好过编一句中文）。
     */
    private val finishedFallback: String by lazy {
        runCatching { runBlocking { getString(Res.string.agent_event_finished_fallback) } }.getOrDefault("")
    }

    private val errorFallback: String by lazy {
        runCatching { runBlocking { getString(Res.string.agent_event_error_fallback) } }.getOrDefault("")
    }

    /**
     * 「这一轮只有思考、没有正文」时的**替代正文**。
     *
     * ## 为什么不为空（来自真机读数）
     *
     * 插件在回合结束、整轮没有任何正文时，发来的是一条 body 只有 `<think>…</think>` 的
     * `assistant.text`（summary `思考完成`）。桌面端剥掉思考块后正文为空，此前直接
     * `return@onRecord` —— 用户看到的是「回合明明结束了，群里一个字都没有」。
     * 真机上连测到三轮这种形态（剥完只剩思考），轨迹里全是「没有下文」。
     *
     * 判据：**空正文不等于「没有发生」**。宁可给一句如实的话（思考块照样折叠显示），
     * 也不要让一段历史凭空消失 —— 静默丢弃正是本仓最贵的那类缺陷。
     *
     * 取不到资源时退回空串：那时它等价于旧行为（少一条气泡），不会编一句中文。
     */
    private val thinkingOnlyFallback: String by lazy {
        runCatching { runBlocking { getString(Res.string.agent_event_thinking_only_fallback) } }
            .getOrDefault("")
    }

    private val processedEventCursors = java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(2000, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>?): Boolean = size > 2000
        }
    )

    /** 会话内单调的事件游标（见 [EventCursorAllocator] 的 KDoc：时间戳当游标会静默丢事件）。 */
    private val eventCursors = EventCursorAllocator()

    /** 控制面实际监听端口；未启动时为 0。菜单栏与状态栏都读它。 */
    val controlPlanePort: Int
        get() = plane?.boundPort ?: 0

    var grantStore: AgentGrantStore? = null

    /**
     * 每个 agent 的执行范围（谁能让它干活）。与 [grantStore] 同一档暴露给 UI ——
     * 两者都是**宿主自己的**台账，面板读写它们就是本机用户在配自己的机器。
     */
    var execPolicyStore: AgentExecPolicyStore? = null
        private set

    /**
     * 把一个授权决定回给设备（`agent-grant` 帧，宿主 → 设备）。
     *
     * 桌面端「设置 → 授信设备」面板上人按的每个按钮都走这里：面板先写本机台账
     * （[grantStore]），再调本方法把决定告诉对端。实现只有一份，在 [ControlPlane.sendGrantReply]
     * 里 —— 与 HTTP 的 `PUT /v1/agent/grants` 的 `grantReply` 分支共用；此前面板够不到
     * 那份实现，于是"用户点完、手机看不到"。
     *
     * 控制面没挂上（端口被占 / 未 start）时如实返回 [GrantReplyOutcome.Failed]，
     * **不假装发了**：面板据此显示"发送失败"。
     */
    suspend fun sendGrantReply(decision: GrantDecision): GrantReplyOutcome {
        // 原因用 ASCII：本文件的 CJK 字面量计数已在棘轮基线上（新增一处就是给门禁添一条红），
        // 而这是一句诊断而不是用户可见文案。
        val plane = plane ?: return GrantReplyOutcome.Failed(decision.nodeId, "control-plane-not-running")
        return plane.sendGrantReply(
            nodeId = decision.nodeId,
            state = decision.state,
            scope = decision.scope,
            agentKey = decision.agentKey,
            wsId = decision.wsId,
            access = decision.access,
            isMyDevice = decision.isMyDevice,
            delegatedApproval = decision.delegatedApproval,
        )
    }

    /**
     * 开始跟随控制器生命周期。必须在 [RelayController.start] **之前**调用：
     * 运行时的创建是异步完成的，之后再挂会错过时机。
     */
    fun start() {
        if (followJob != null) return
        followJob = scope.launch {
            while (isActive) {
                syncOnce()
                delay(FOLLOW_POLL_MS)
            }
            // 协程被取消（窗口退出）时也别把控制面留在监听状态。
            detach()
        }
    }

    fun stop() {
        followJob?.cancel()
        followJob = null
        detach()
    }

    /**
     * 一拍对齐：运行时换了就重挂，没了（stop / 重启中）就摘掉。
     * 幂等 —— 稳态下每拍只做一次引用比较，不产生任何副作用。
     */
    private fun syncOnce() {
        val runtime = controller.activeRuntime()
        if (runtime == null || (plane != null && attachedRuntime !== runtime)) {
            detach()
            if (runtime == null) return
        }
        if (plane != null) return
        // 台账先建再 start：控制面一旦 start()，`/v1/agent/sessions` 就可能被问，
        // 那一刻台账必须已经在收对方的帧了（空列表对调用方就是「本机没有会话」）。
        // 入站订阅不再在这里手写 —— 三张台账（会话 / 工作区 / 授权）的解析规则
        // 只有 `ControlPlane.observeInbound` 这一份，桌面端、agentNode、测试共用，
        // 集中维护解析分支，防止分叉遗漏。
        var registryRef: AgentSessionRegistry? = null
        val registry = AgentSessionRegistry(
            onRecordSession = { rec ->
                controller.recordLocalSession(
                    sessionId = rec.sessionId,
                    alias = rec.alias,
                    agentKind = rec.agentKind,
                    state = rec.state,
                    title = rec.title,
                    startedAt = rec.startedAt,
                    agentId = rec.agentId,
                    wsId = rec.wsId,
                    wsTitle = rec.wsTitle,
                    wsPath = rec.wsPath,
                    updatedAt = rec.updatedAt,
                )
            },
            onRecordEvent = onRecord@ { rec ->
                 val eventKey = eventDeliveryKey(rec.nodeId, rec.sessionId, rec.cursor)
                 val isNew = synchronized(processedEventCursors) { processedEventCursors.add(eventKey) }
                 if (!isNew) {
                     logEventRoute(rec.eventType, rec.sessionId, rec.cursor, 0, "duplicate-delivery")
                     return@onRecord
                 }

                 controller.recordLocalEvent(
                    nodeId = rec.nodeId,
                    sessionId = rec.sessionId,
                    cursor = rec.cursor,
                    eventType = rec.eventType,
                    summary = rec.summary,
                    body = rec.body,
                    ref = rec.ref,
                    at = rec.at,
                )
                // task-60：带 nodeId 的严格查找 —— 台账的键就是 (nodeId, sessionId)，
                // 只按 sessionId 查会把别的设备的同名会话当成自己的（那会把甲仓库的活
                // 干进乙仓库的群）。1 参重载已随本次迁移删除。
                val localSess = registryRef?.findSession(rec.nodeId, rec.sessionId)
                val allSessionsForId = controller.state.value.agentSessions.values.filter { it.sessionId == rec.sessionId }
                var wsPath: String? = localSess?.wsPath?.takeIf { it.isNotBlank() }
                    ?: allSessionsForId.firstOrNull { !it.wsPath.isNullOrBlank() }?.wsPath
                var wsId: String? = localSess?.wsId?.takeIf { it.isNotBlank() }
                    ?: allSessionsForId.firstOrNull { !it.wsId.isNullOrBlank() }?.wsId
                var wsTitle: String? = localSess?.wsTitle?.takeIf { it.isNotBlank() }
                    ?: allSessionsForId.firstOrNull { !it.wsTitle.isNullOrBlank() }?.wsTitle
                // kind 只来自**声明**（会话帧自报）：没有任何声明 ⇒ 没有可署名的身份，
                // 不再退回内置默认种类（债#6）。此时 chatMessageFor 返回 null，
                // 事件照常落本地台账，只是不进群（无人能给它署名）。
                val agentKind: String? = localSess?.agentKind?.takeIf { it.isNotBlank() }
                    ?: allSessionsForId.firstOrNull { !it.agentKind.isNullOrBlank() }?.agentKind
                val sessionTitle: String? = localSess?.title?.takeIf { it.isNotBlank() }
                    ?: allSessionsForId.firstOrNull { !it.title.isNullOrBlank() }?.title

                // 防护层 1: 从 AgentSessionRegistry 全量台账查
                if (wsPath.isNullOrBlank()) {
                    val matchInRegistry = registryRef?.sessions()?.firstOrNull { it.sessionId == rec.sessionId && !it.wsPath.isNullOrBlank() }
                    if (matchInRegistry != null) {
                        wsPath = matchInRegistry.wsPath
                        wsId = wsId ?: matchInRegistry.wsId
                        wsTitle = wsTitle ?: matchInRegistry.wsTitle
                    }
                }

                // 防护层 2: 从 controller.state.value.agentWorkspaces 查
                if (wsPath.isNullOrBlank()) {
                    val ws = if (!wsId.isNullOrBlank()) {
                        controller.state.value.agentWorkspaces.values.firstOrNull { it.wsId == wsId }
                    } else null
                    if (ws != null) {
                        wsPath = ws.wsPath
                        wsTitle = wsTitle ?: ws.wsTitle
                    }
                }

                val finalWsPath = wsPath
                val finalWsId = wsId
                val finalWsTitle = wsTitle
                // 与两条补发**同一条**匹配规则（groupsForSession）：此前这里是自己那段等价的三键
                // filter —— 行为等价但不是同一个函数，改一边忘一边就会「实时能投、补发投不到」
                // （task-59 修的就是这条）。实时这处传**事件记录自带的三键**（它们权威）；
                // 两条补发传 null 让函数自己从 state 补齐。
                val targetGroups = groupsForSession(
                    controller.state.value,
                    rec.sessionId,
                    finalWsPath,
                    finalWsId,
                    finalWsTitle,
                )

                // 没有绑定群就**不转发**（沉默不猜）。
                //
                // 这里原来有一段「兜底匹配」：群的名称/描述里带某个仓库名（写死过
                // "deepseek-harness"），或全局只有一个群，就把**任何**工作区的事件塞进去。
                // 后果是实测出来的：teahouse-mobile 的会话（session-868856e1…）根本没绑定群，
                // 却被灌进了「deepseek-harness 协同群」，整个会话的 user.message /
                // assistant.text 都出现在用户眼前。宁可这条事件不显示，也不要把甲仓库的活
                // 干到乙仓库的群里。finalWsPath / finalWsId / finalWsTitle 仍是上面匹配用的键。
                if (targetGroups.isEmpty()) {
                    logEventRoute(rec.eventType, rec.sessionId, rec.cursor, rec.body?.length ?: 0, "no-group-bound")
                }

                logEventRoute(rec.eventType, rec.sessionId, rec.cursor, rec.body?.length ?: 0, "dispatch groups=" + targetGroups.size)

                if (targetGroups.isNotEmpty()) {
                    // 实时与补发**共用同一个构造**（chatMessageFor）：文案/发送者/思考块不可能漂。
                    val message = chatMessageFor(
                        rec = rec,
                        sessionTitle = sessionTitle,
                        selfNodeId = controller.state.value.selfNodeId,
                        agentKind = agentKind,
                    )
                    if (message != null) {
                        val isUserPrompt = message.isUserPrompt
                        val parsedReasoning = message.reasoning
                        val finalAnswer = message.text
                        val agentSenderId = message.senderId
                        // 思考块被剥掉之后可能什么都不剩（纯 <think> 的一条）：那条不进群，
                        // 否则界面上就是一个只有时间戳的空气泡。
                        if (!hasVisibleText(finalAnswer)) return@onRecord
                        for (group in targetGroups) {
                            val convId = com.kapp.tidings.core.protocol.ConvId.group(group.groupId)
                            // **去重要按剥离后的正文比**（见 [alreadyForwardedToGroup]）：
                            // relay 注入时剥掉了 mention，回传的正文比群里原文少一个 `@token`。
                            val alreadyInChat = alreadyForwardedToGroup(
                                isUserAuthoredPrompt = isUserPrompt,
                                existingMessages = controller.state.value.messages[convId].orEmpty(),
                                finalAnswer = finalAnswer,
                                eventAt = rec.at,
                                agentKind = agentKind,
                            )
                            if (!shouldForwardAgentEventToGroup(isUserAuthoredPrompt = isUserPrompt, alreadyInChat = alreadyInChat)) {
                                logEventRoute(rec.eventType, rec.sessionId, rec.cursor, finalAnswer.length, "duplicate-in-chat")
                                continue
                            }
                            val msgId = "agent-ev-${rec.sessionId}-${rec.cursor}"
                            val msg = com.kapp.tidings.domain.model.MessageUi(
                                id = msgId,
                                peerId = convId,
                                mine = isUserPrompt,
                                text = finalAnswer,
                                reasoning = parsedReasoning,
                                at = rec.at,
                                senderId = agentSenderId,
                                // **会话归属必须落在这里。**
                                //
                                // 漏掉的后果是真机上的「桌面有分段线、手机没有」：手机只从群里
                                // 收消息，它拿不到 id 前缀那条回退（转发时 id 会换成信封 UUID），
                                // 只能靠协议字段；而这条实时路径自己手写 `MessageUi`、没填这两个
                                // 字段 —— 于是 `agent_session_id` 一直是 NULL。
                                //
                                // 探针实测：`SAVE agent=5212 withSid=0` —— 进 save() 时就已经是空，
                                // 不是落库丢的。而补发那条路（走 `agentChatMessageFor`）一直是对的，
                                // 于是同一件事在两条路上表现不同 —— 正是本仓反复出现的那类分叉。
                                agentSessionId = rec.sessionId,
                            )
                            controller.appendMessage(msg, incoming = !isUserPrompt)

                            val rt = attachedRuntime
                            // 用户自己（宿主侧 DSH 里）打的字**也要进群**：手机端只从群里收消息，
                            // 此前这里写的是 `!isUserPrompt && …`，于是那条用户消息只落在桌面本地 ——
                            // 真机反馈「DSH 里在 agent 思考时发消息，relay 这边一直显示思考中、
                            // 没插入新消息」的真身就是它（桌面看得见、手机永远看不见）。
                            //
                            // 回声保护不在这里：手机自己发的那条会先经 `handleSendMessage`
                            // 落进本地会话，事件回来时上面那段 `alreadyInChat`（同文本 + 120s 内）
                            // 已经把它判成重复并 `continue` 掉了（见 shouldForwardAgentEventToGroup）。
                            if (rt != null && group.members.isNotEmpty()) {
                                // in-flight 占位：换网/对端上线会再触发补发那一趟，没有占位就会
                                // 在上一趟还在飞时把同一条 (session, group, cursor) 再投一次
                                // ⇒ 群里出现两条（水位是 send 成功后才记的，挡不住在飞的）。
                                val flightKey = rec.sessionId + "|" + group.groupId + "|" + rec.cursor
                                if (inFlightGroupForwards.add(flightKey)) {
                                scope.launch {
                                    try {
                                    runCatching {
                                        rt.sendGroupText(
                                            // 发**解析后的正文**，不是原始事件文本：手机端此前收到的是带
                                            // <think>…</think> 的原文，与桌面端落库的正文不一致，
                                            // 同一件事在两端显示成两句话。
                                            text = finalAnswer,
                                            groupId = group.groupId,
                                            groupRev = group.rev,
                                            members = group.members,
                                            // **这条是 agent 说的，不是桌面端说的**（真机缺陷）：
                                            // 信封 `from` 永远是发送设备，不传 senderId，手机端就把
                                            // agent 的话显示成「桌面端发的」，而桌面端自己按
                                            // `agent:<kind>` 落库 ⇒ 同一个群两端发送者不一致。
                                            // **必须传 `agentSenderId`，不能再算一遍。**
                                            // 本机落库用的就是它（用户自己发的话 = 用户 nodeId，agent 答的 = `agent:<小写 kind>`），
                                            // 而这里原来无条件写 `agentSenderIdFor(agentKind)` ⇒ 用户自己在群里
                                            // 打的字，在别人设备上显示成「agent 说的」（真机红米库里就是这么存的）。
                                            senderId = agentSenderId,
                                            // 思考过程**不进群**（规格 §4.1 铁律：CoT 禁止进群，
                                            // 白名单已把 `reasoning` 退场 —— 带它的帧对端整帧拒收）。
                                            // 思考块仍然落在桌面本地消息里（本地 `MessageUi.reasoning`），
                                            // 界面上折叠显示；群里只有解析后的正文。
                                            // **会话归属**：把「这条属于哪个 agent 会话」一起送出去。
                                            // 此前只写进本地消息 id 前缀、转发时没带 —— 于是桌面有
                                            // 「任务分段线」、手机一条都没有（同一件事两端两个样）。
                                            agentSessionId = message.agentSessionId,
                                            agentSessionTitle = message.agentSessionTitle,
                                            agentCursor = message.agentCursor,
                                        )
                                        // 交给运行时之后才记水位：rt == null（或发送抛错）时**不记**，
                                        // 于是补发那一趟会把它补上；记早了就永远补不回来。
                                        groupForwardLedger?.mark(rec.sessionId, group.groupId, rec.cursor)
                                    }
                                    } finally {
                                        // 失败也要释放：否则这条永远补不回来（水位没记、占位也留着）。
                                        inFlightGroupForwards.remove(flightKey)
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            },
        )
        registryRef = registry
        groupForwardLedger = GroupForwardLedger(java.io.File(controller.dataDirectory, "agent-group-forwarded.json"))
        val workspaces = AgentWorkspaceRegistry(
            onRecord = { rec -> recordLocalWorkspaceFrom(controller, rec) },
        )
        val grants = AgentGrantStore(java.io.File(controller.dataDirectory, "agent-grants.json"))
        grantStore = grants
        val execPolicies = AgentExecPolicyStore(
            java.io.File(controller.dataDirectory, "agent-exec-policies.json"),
        )
        execPolicyStore = execPolicies
        val created = ControlPlane(
            runtime, httpPort, token,
            sessions = registry, workspaces = workspaces, grants = grants, execPolicies = execPolicies,
            // **注入判定**：判据只有一份（core 的 mentionedVirtualAgent），这里只提供
            // 「这个群的元数据长什么样」这个事实 —— desktopApp 能读到 composeApp 的
            // settings.groups，agentNode 读不到，所以由这里注入。
            // **桌面端只提供事实**：这个群的元数据。
            // 「@ 到谁」「谁有权执行」「投给哪个会话」三个判据都在 Tidings 侧
            // （core 的判据 + ControlPlane 的台账），桥接侧只消费结论。
            inboxEnricher = object : AgentInboxEnricher {
                override fun groupMetaOf(payload: JsonObjectLike): GroupMeta? {
                    val groupId = payload.groupId ?: return null
                    return controller.state.value.settings.groups
                        .firstOrNull { it.groupId == groupId }
                }
            },
        )
        try {
            created.start()
            inboundJob = created.observeInbound(scope)
            plane = created
            attachedRuntime = runtime
            sessions = registry
            backfillMissingAgentEvents()
            // 补发时机：启动（StateFlow 的首个值）与**重连 / 对端上线 / 换网**。
            // 不能只等下一次启动 —— 用户离线一分钟回来，那条消息就该在一分钟内到。
            scope.launch {
                delay(3000L)
                backfillMissedGroupForwards()
                // **群元数据补发只做一次，且不挂在状态流上。**
                //
                // 第一版把它放进下面那个 `collect`，结果**自我触发了循环**：广播会改变
                // 在线对端集合 → 状态流又发一个值 → 又广播。真机上是每 15 秒一轮、
                // 队列瞬间涨到 286 条 group-info 帧，全是同一个 rev。
                //
                // 补发要解决的是「对端错过了那一次快照」，而这件事只需要在**本机启动后**
                // 做一次 —— 对端在线时它会自己来问（`op:need`），离线时队列会替它留着。
                controller.rebroadcastGroupMeta()
                controller.state
                    .map { st -> st.network.phase to st.peers.filter { it.online }.map { it.nodeId }.sorted() }
                    .distinctUntilChanged()
                    .collect {
                        backfillMissedGroupForwards()
                        // **群元数据也要补**：它是快照，而广播只在「变更时」发一次。
                        // 对端在那一刻离线就永远缺 `virtualAgents` —— 接收端还会因为「我已知
                        // 这个 rev」而不再发 need，自己走不出来（真机：手机上三处全是 `dsh`）。
                        //
                        // `rebroadcastGroupMeta` 自带 `(对端, 群) → 已发 rev` 台账，
                        // 所以挂在这个会抖动的状态流上是安全的：同 rev 只发一次。
                        // 第一版没有台账，真机上每 15 秒一轮、队列涨到 286 帧 —— 别去掉它。
                        controller.rebroadcastGroupMeta()
                    }
            }
            controller.onLocalSessionPageRequest = { peerId, wsId, offset, limit ->
                created.recordSessionPage(peerId, wsId, offset, limit)
                true
            }
            val driverRegistry = com.kapp.tidings.agent.drivers.DriverRegistry()

            // **执行名单的宿主侧实现**：台账在桌面端（`AgentGrantStore`），控制器拿不到。
            //
            // 名单 = `scope=AGENT` 且 agentKey 匹配的授权记录；可信 = `scope=ALL`。
            // 两者都从**同一份快照**读，所以界面上看到的「可信」与「已勾」永远属于同一时刻 ——
            // 分两次读会在并发改动时给出自相矛盾的一屏。
            controller.onExecDevices = { agentKey ->
                val key = agentKey?.trim()?.takeIf { it.isNotEmpty() }
                if (key == null) {
                    null
                } else {
                    val snapshot = grants.snapshot()
                    val rows = snapshot.grants.map {
                        ExecGrantView(
                            nodeId = it.nodeId,
                            scope = it.scope,
                            agentKey = it.agentKey,
                            isMyDevice = it.isMyDevice,
                        )
                    }
                    // 已知设备：群成员 ∪ 对端。标签取 peers 的昵称，拿不到就留给派生层兜底（id 前 8 位）。
                    val st = controller.state.value
                    // **本机自己一定用 `settings.nick`**：它不在对端表里（那是「别人」），
                    // 也不是每个群都在 `memberNicks` 里记了自己。漏了它就会退化成
                    // nodeId 前 8 位 —— 界面上显示一串字母，而用户明明给这台机器起过名字。
                    //
                    // 合并顺序 = 优先级：本机名 > 对端昵称 > 群成员昵称；
                    // `distinctBy` 保留**第一条**，所以有名字的来源要排在前面，
                    // 且空串不算名字（否则一条空 nick 会把真名挤掉）。
                    val known = buildList {
                        if (st.selfNodeId.isNotBlank()) add(st.selfNodeId to st.settings.nick)
                        addAll(st.peers.map { it.nodeId to it.nick })
                        st.settings.groups.forEach { g ->
                            g.members.forEach { m -> add(m to (g.memberNicks?.get(m).orEmpty())) }
                        }
                    }
                        // 空串标签等于「没有名字」，不能占位 —— 它会把后面的真名挤掉。
                        .filter { it.second.isNotBlank() }
                        .distinctBy { it.first }
                    execDeviceRows(
                        selfNodeId = st.selfNodeId,
                        agentKey = key,
                        knownDevices = known,
                        grants = rows,
                    )
                }
            }
            controller.onSetExecDeviceAllowed = { agentKey, nodeId, allowed ->
                // **只动 `scope=AGENT`**：取消时不该碰「这台设备可信任」那条记录 ——
                // 「不许它执行这个 agent」与「不再信任这台设备」是两件事。
                if (allowed) {
                    grants.grant(
                        nodeId = nodeId,
                        scope = AgentGrantScopes.AGENT,
                        agentKey = agentKey,
                        grantedBy = "panel",
                        at = System.currentTimeMillis(),
                    )
                } else {
                    grants.revoke(nodeId = nodeId, scope = AgentGrantScopes.AGENT, agentKey = agentKey)
                }
            }
            // **执行由 DSH 注入桥承担，这里不再跑本地驱动。**
            //
            // 原先这里接的是 `driverRegistry` 里的 `native` 驱动 —— 它自己 KDoc 写着
            // 「没有挂载执行运行时：这次不会执行命令、不读写文件、不产出结果」，
            // 于是用户 @agent 后收到的是那句「本机 native 驱动的兜底」，而活一件没干。
            // 那正是本仓反复出现的「界面在说假话」形态：一个**永远成功但什么都不做**的执行器。
            //
            // 真正的执行路径是 `dsh-tidings`：它轮询本机 agentNode 的 `/v1/inbox`，
            // 按随行给出的 `agentHint`（addressd / 授权 / targetSessionId）把 prompt
            // 注入宿主上的 DSH 会话。那条路径**与本回调无关** —— 它只看 inbox。
            //
            // 所以这里保持**非 null**（群消息路由要靠 `onAgentPrompt != null` 判「本机有 agent」），
            // 但什么都不做：让既有的 `NativeRelayDriver` 不再产出假回复。
            //
            // 代价（如实记在这里）：**没装 dsh-tidings 的宿主，@agent 将得不到任何回复**。
            // 那比回一句「我没有运行时」诚实 —— 后者让用户以为 agent 收到了却干不了活。
            controller.onAgentPrompt = { _, _, _, _ ->
                // 有意为空。执行由 dsh-tidings 从 inbox 取走并注入宿主会话。
            }
        } catch (t: Throwable) {
            // 控制面是「加装」能力，绑不上（例如 8788 被别的进程占了）不能拖垮整个桌面壳：
            // UI 和传输层跟它无关，仍然可用。这里吞掉异常，菜单栏的状态栏会显示端口 0。
            plane = null
            attachedRuntime = null
            sessions = null
            controller.onLocalSessionPageRequest = null
            controller.onAgentPrompt = null
            controller.onExecDevices = null
            controller.onSetExecDeviceAllowed = null
            inboundJob?.cancel()
            inboundJob = null
        }
    }

    private fun detach() {
        controller.onLocalSessionPageRequest = null
        controller.onAgentPrompt = null
        plane?.stop()
        plane = null
        attachedRuntime = null
        sessions = null
        grantStore = null
        execPolicyStore = null
        // 先停控制面再撤订阅：collector 里会调 runtime 发送（grant 回包），
        // 运行时可能紧随其后被 stop —— 留着它跑就是往死运行时里发帧。
        inboundJob?.cancel()
        inboundJob = null
    }

    /**
     * 记一条事件路由诊断：**永远**进计数（无内容），只有打开 debug 开关时才打 stdout。
     *
     * 正文长度是唯一的「内容侧」信息，且是长度不是内容 —— 事件类型 + 会话 + 游标 +
     * 长度 + 原因足够回答「这条为什么没投出去」（今天几次排查都靠这类信息）。
     */
    private fun logEventRoute(type: String, sessionId: String, cursor: Long, textLength: Int, reason: String) {
        val line = eventDiagnosticLine(type, sessionId, cursor, textLength, reason)
        eventDiagnostics.record(reason, line)
        if (eventDebugEnabled) println("[RELAY-EVENT] " + line)
    }

    /**
     * 一条 agent 事件在聊天通道里的**最终形态** —— 实时路由与补发共用这一个构造。
     *
     * 为什么必须共用（task-59 b）：此前两条路各写一份 —— 补发不传标题、发送者硬编码
     * agent:dsh，于是同一条事件在「实时」与「补发」下**文案与发送者都不一样**（用户可见）。
     * 收敛成一个函数之后，两边逐字一致是**构造上**成立的，不靠人记得同步改两处。
     */
    internal fun chatMessageFor(
        rec: AgentSessionRegistry.EventRecord,
        sessionTitle: String?,
        selfNodeId: String,
        agentKind: String?,
    ): AgentChatMessage? = agentChatMessageFor(
        rec = rec,
        sessionTitle = sessionTitle,
        selfNodeId = selfNodeId,
        agentKind = agentKind,
        format = ::formatAgentEventForChat,
        // 只有思考、没有正文时用这句替代正文（见 thinkingOnlyFallback 的 KDoc）：
        // 空串 = 资源取不到 ⇒ 等价于旧行为，不会凭空编内容。
        thinkingOnlyFallback = thinkingOnlyFallback,
    )


    private fun formatAgentEventForChat(rec: AgentSessionRegistry.EventRecord, sessionTitle: String?): String? {
        val evtType = rec.eventType.lowercase()
        return when {
            evtType == "turn.end" -> {
                val body = rec.body?.trim().orEmpty()
                // 只有当有真实内容时才作为消息展示，空 body 不再输出 "执行完成" / "turn 1" 等垃圾气泡。
                // hasVisibleText 而不是 isNotEmpty：只含零宽字符的 body 也算空（空气泡的真凶）。
                if (hasVisibleText(body)) body else null
            }
            evtType in listOf("assistant.text", "assistant.message", "text", "message") -> {
                rec.body?.trim()?.takeIf { hasVisibleText(it) }
            }
            evtType == "user.message" -> {
                rec.body?.trim()?.takeIf { hasVisibleText(it) } ?: rec.summary?.trim()?.takeIf { hasVisibleText(it) }
            }
            evtType == "finished" -> {
                val s = rec.summary ?: finishedFallback
                val b = rec.body?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                "✅ $s$b"
            }
            // 工具调用是底层执行细节，绝不应刷屏污染聊天气泡
            evtType in listOf("tool.started", "tool.call", "tool.completed", "tool.result") -> null
            evtType == "session.started" -> null
            evtType == "error" -> {
                "⚠️ ${rec.summary ?: errorFallback}: ${rec.body.orEmpty()}"
            }
            else -> {
                if (!evtType.contains("delta") && !evtType.contains("thought") && !evtType.contains("tool") && !evtType.contains("turn")) {
                    rec.body?.takeIf { it.isNotBlank() }
                } else null
            }
        }
    }

    /**
     * 「这条用户消息**已经发过群**」的水位（sessionId → 已转发到群的最大 cursor）。
     *
     * 为什么必须单独记：alreadyInChat 只能回答「本地会话里有没有这句」，而**用户自己打的字
     * 无论如何都会先落进本地会话** —— 本地有 ≠ 群里发过。离线 / rt == null 时那次群发送会被
     * 静默跳过，事后没有任何痕迹可查（真机反馈：手机离线期间错过的用户消息永远补不回来）。
     *
     * 为什么**落盘**：补发时机是「下次启动」与「重连 / 对端上线」，重启后必须仍然知道哪些发过 ——
     * 只放内存会在每次启动时把历史用户消息重发一遍。
     */
    private var groupForwardLedger: GroupForwardLedger? = null

    /**
     * 正在投递中的（会话 × 群 × 游标）。
     *
     * 为什么需要它：水位**只在 send 成功后才记**（记早了就永远补不回来），而补发触发点是
     * 「换网 / 对端上线」，会在上一次 send 还在飞时再跑一遍 ⇒ 同一个 (session, group, cursor)
     * 被选中两次 ⇒ **群里出现两条**。占位在选中前加、失败释放、成功由水位接管。
     */
    private val inFlightGroupForwards = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 事件路由诊断：只有类型/游标/长度/原因与计数，**没有任何正文**。见 EventRouterDiagnostics。 */
    internal val eventDiagnostics = EventRouterDiagnostics()

    /**
     * 把「用户自己打的字」补发到群：离线期间、或 rt == null 时错过的那批。
     *
     * 与 backfillMissingAgentEvents 的分工：那个补**本地会话**（台账里本地没有的事件），
     * 这个补**群**（本地早就有、只是从没发出去过的用户消息）—— 两件事的判据不同，合成一个
     * 会把「本地有就跳过」当成「群里也发过了」，而那正是这条缺陷的成因。
     */
    /**
     * 事件/会话 → 该投的群。**唯一真源**：实时路由与两个补发都走它。
     *
     * 三个键（wsPath / wsId / wsTitle）任一命中即算绑定 —— 此前实时用三键、两个补发只用
     * wsPath ⇒ **靠 wsTitle 绑定的群实时能投、补发静默投不到**（同一条规则两份实现，迟早漂）。
     *
     * fail-closed：wsPath/wsId/wsTitle 全空、或同一个 sessionId 在不同设备上报出了**不同的
     * 工作区归属**（跨设备撞 sessionId）时返回空 —— 宁可不投，也不要把甲设备的活投进乙设备的群。
     */
    private fun groupsForSession(
        state: com.kapp.tidings.domain.RelayUiState,
        sessionId: String,
        wsPath: String?,
        wsId: String?,
        wsTitle: String?,
    ): List<com.kapp.tidings.core.protocol.GroupMeta> {
        if (wsPath.isNullOrBlank() && wsId.isNullOrBlank() && wsTitle.isNullOrBlank()) return emptyList()
        val owners = state.agentSessions.values
            .filter { it.sessionId == sessionId }
            .map { Triple(it.wsPath?.trim().orEmpty(), it.wsId?.trim().orEmpty(), it.wsTitle?.trim().orEmpty()) }
            .filter { it.first.isNotEmpty() || it.second.isNotEmpty() || it.third.isNotEmpty() }
            .distinct()
        // 只在**必须从 state 推断**（调用方三个键全空）时才把歧义当阻断：事件记录自带
        // 的 wsPath/wsId/wsTitle 是权威的，不该被别的设备的同名会话连累。
        if (owners.size > 1 && wsPath.isNullOrBlank() && wsId.isNullOrBlank() && wsTitle.isNullOrBlank()) {
            logEventRoute("session", sessionId, 0L, 0, "ambiguous-owner")
            return emptyList()
        }
        val path = wsPath?.trim().orEmpty().ifEmpty { owners.firstOrNull()?.first.orEmpty() }
        val id = wsId?.trim().orEmpty().ifEmpty { owners.firstOrNull()?.second.orEmpty() }
        val title = wsTitle?.trim().orEmpty().ifEmpty { owners.firstOrNull()?.third.orEmpty() }
        if (path.isEmpty() && id.isEmpty() && title.isEmpty()) return emptyList()
        return state.settings.groups.filter { group ->
            val desc = group.description?.trim().orEmpty()
            (path.isNotEmpty() && desc.contains(path)) ||
                (id.isNotEmpty() && (desc.contains(id) || group.name.contains(id))) ||
                (title.isNotEmpty() && group.name.contains(title)) ||
                (path.isNotEmpty() && group.name.contains(path.substringAfterLast('/')))
        }
    }

    private fun backfillMissedGroupForwards() {
        val ledger = groupForwardLedger ?: return
        val rt = attachedRuntime ?: return
        val state = controller.state.value
        for ((sessionKey, eventList) in state.agentEvents) {
            val sessionId = sessionKey.sessionId
            // 与实时路由**同一条**匹配规则（groupsForSession）：wsId/wsTitle 由它自己从
            // state 里补齐 —— 此前这里只看 wsPath，于是「靠 wsTitle 绑定的群」实时能投、
            // 补发静默投不到（同一条规则两份实现，迟早漂）。
            val targetGroups = groupsForSession(state, sessionId, wsPath = null, wsId = null, wsTitle = null)
            if (targetGroups.isEmpty()) continue
            val records = eventList.distinctBy { it.cursor }.map { ev ->
                AgentSessionRegistry.EventRecord(
                    nodeId = ev.peerId,
                    sessionId = sessionId,
                    cursor = ev.cursor,
                    eventType = ev.eventType,
                    summary = ev.summary,
                    body = ev.body,
                    ref = ev.ref,
                    at = ev.at,
                )
            }
            // **按（会话 × 群）逐目标判**：每个群是独立的投递目标，对一个群失败不该压制另一个群。
            for (group in targetGroups) {
                val candidates = groupForwardCandidates(
                    events = records,
                    forwardedCursorOf = { rec -> ledger.cursorOf(rec.sessionId, group.groupId) },
                    isPeerEcho = { rec ->
                        // 手机自己发的那条：本地会话里有同一句、同一时刻、且 mine = false。
                        val raw = (rec.body ?: rec.summary)?.trim().orEmpty()
                        raw.isNotEmpty() && state.messages.values.asSequence().flatten().any { existing ->
                            !existing.mine && existing.text.trim() == raw &&
                                kotlin.math.abs(existing.at - rec.at) < 120_000L
                        }
                    },
                )
                for (rec in candidates) {
                    // 与实时路由共用同一个构造（含**会话标题**）：此前这里传 null，
                    // 于是同一条事件在实时与补发下文案不一样（task-59 b，用户可见）。
                    val backfillTitle = state.agentSessions.values
                        .firstOrNull { it.sessionId == rec.sessionId && !it.title.isNullOrBlank() }?.title
                    val backfillKind = state.agentSessions.values
                        .firstOrNull { it.sessionId == rec.sessionId && !it.agentKind.isNullOrBlank() }?.agentKind
                    val message = chatMessageFor(
                        rec = rec,
                        sessionTitle = backfillTitle,
                        selfNodeId = state.selfNodeId,
                        agentKind = backfillKind,
                    ) ?: continue
                    val body = message.text
                    if (!hasVisibleText(body)) continue
                    val flightKey = rec.sessionId + "|" + group.groupId + "|" + rec.cursor
                    if (!inFlightGroupForwards.add(flightKey)) continue
                    scope.launch {
                        try {
                            runCatching {
                                rt.sendGroupText(
                                    text = body,
                                    groupId = group.groupId,
                                    groupRev = group.rev,
                                    members = group.members,
                                    // 与实时那条同源：用共用构造算出来的发送者，
                                    // 而不是按 kind 现拼（用户自己的话同样会拼错）。
                                    senderId = message.senderId,
                                    // 思考过程**不进群**（与实时那条同一条铁律：CoT 禁止进群，
                                    // 白名单已把 `reasoning` 退场 —— 带它的帧对端整帧拒收）。
                                    // 补发与实时必须带同一个标题：只带正文会让「离线一会儿回来」
                                    // 的那条在手机上显示成「新会话」，而实时的显示真标题。
                                    agentSessionId = message.agentSessionId,
                                    agentSessionTitle = message.agentSessionTitle,
                                    agentCursor = message.agentCursor,
                                )
                                ledger.mark(rec.sessionId, group.groupId, rec.cursor)
                            }
                        } finally {
                            inFlightGroupForwards.remove(flightKey)
                        }
                    }
                }
            }
        }
    }

    private fun backfillMissingAgentEvents() {
        scope.launch {
            delay(2000L)
            val state = controller.state.value
            val currentMsgIds = state.messages.values.flatten().map { it.id }.toSet()

            for ((sessionKey, eventList) in state.agentEvents) {
                val sessionId = sessionKey.sessionId
                val allSessionsForId = state.agentSessions.values.filter { it.sessionId == sessionId }
                val wsPath = allSessionsForId.firstOrNull { !it.wsPath.isNullOrBlank() }?.wsPath ?: continue

                val targetGroups = groupsForSession(state, sessionId, wsPath = null, wsId = null, wsTitle = null)
                if (targetGroups.isEmpty()) continue

                val distinctEvents = eventList.distinctBy { it.cursor }.sortedBy { it.cursor }
                for (ev in distinctEvents) {
                    val msgId = "agent-ev-$sessionId-${ev.cursor}"
                    if (msgId in currentMsgIds) continue
                    val existingLegacy = state.messages.values.asSequence().flatten().firstOrNull { 
                        it.id == msgId || it.id.startsWith("agent-ev-$sessionId-${ev.cursor}-")
                    }
                    if (existingLegacy != null) continue

                    val rec = AgentSessionRegistry.EventRecord(
                        nodeId = ev.peerId,
                        sessionId = sessionId,
                        cursor = ev.cursor,
                        eventType = ev.eventType,
                        summary = ev.summary,
                        body = ev.body,
                        ref = ev.ref,
                        at = ev.at,
                    )
                    // 与实时路由共用同一个构造；标题与 agentKind 也按同一口径从会话里取
                    // （原来这里写死 agent:dsh、且不传标题 ⇒ 用户可见的不一致，task-59 b）。
                    val backfillTitle = state.agentSessions.values
                        .firstOrNull { it.sessionId == sessionId && !it.title.isNullOrBlank() }?.title
                    val backfillAgentKind = state.agentSessions.values
                        .firstOrNull { it.sessionId == sessionId && !it.agentKind.isNullOrBlank() }?.agentKind
                    val message = chatMessageFor(
                        rec = rec,
                        sessionTitle = backfillTitle,
                        selfNodeId = state.selfNodeId,
                        agentKind = backfillAgentKind,
                    ) ?: continue
                    val isUserPrompt = message.isUserPrompt
                    val parsedReasoning = message.reasoning
                    val finalAnswer = message.text
                    val agentSenderId = message.senderId

                    for (group in targetGroups) {
                        val convId = com.kapp.tidings.core.protocol.ConvId.group(group.groupId)
                        // 与上面那条转发路径**同一个判据**（原先这里是手写的同样逻辑，
                        // 两处一旦分叉就会出现「补发那趟多发、实时那趟不多发」这种诡异现象）。
                        if (
                            alreadyForwardedToGroup(
                                isUserAuthoredPrompt = isUserPrompt,
                                existingMessages = controller.state.value.messages[convId].orEmpty(),
                                finalAnswer = finalAnswer,
                                eventAt = rec.at,
                                agentKind = backfillAgentKind,
                            )
                        ) {
                            continue
                        }
                        val msg = com.kapp.tidings.domain.model.MessageUi(
                            id = msgId,
                            peerId = convId,
                            mine = isUserPrompt,
                            text = finalAnswer,
                            reasoning = parsedReasoning,
                            at = rec.at,
                            senderId = agentSenderId,
                            agentSessionId = message.agentSessionId,
                        )
                        controller.appendMessage(msg, incoming = !isUserPrompt)
                    }
                }
            }
        }
    }

    private companion object {
        /**
         * 运行时生命周期不暴露事件，只能轮询。1s 足够 —— 这个窗口只影响
         * 「重启后控制面多久恢复」，不影响正确性。
         */
        const val FOLLOW_POLL_MS = 1_000L
    }
}

/**
 * 工作区台账的一条记录 → [RelayController.recordLocalWorkspace] 的调用。
 *
 * 抽成顶层函数只为一件事：**这一层曾经把 `order` 丢掉**（真机库里桌面端
 * `AgentWorkspaceEntity.host_order` 15/15 是 NULL，而手机库同一批工作区有 0/1/2/3…），
 * 症状是同一批工作区在两端排序不同。它是纯映射，不需要真控制器就能单测
 * （见 `DesktopWorkspaceRecordTest`），所以不给它留"下次又漏一个字段"的机会。
 */
/**
 * 一条 agent 事件要不要落进这个群的聊天流。
 *
 * **真机缺陷（用户反馈「DSH 里在 agent 思考时发消息，relay 这边一直显示
 * 思考中、没插入新消息」）**：这里原来把用户自己发的话整类排除掉了（`!isUserPrompt`），
 * 于是宿主用户在 DSH 里打的字**只落在桌面本地**、从不进群；手机端只从群里收消息，
 * 那条用户消息在手机上**永远不出现** —— 桌面看得见、手机看不见，正是「没插入」的真身。
 *
 * 回声保护靠 [alreadyInChat]：手机自己发的那条会先经 `handleSendMessage` 落进本地会话，
 * 事件回来时（同文本 + 120s 内）判成重复 ⇒ 不重复转发。
 */
internal fun shouldForwardAgentEventToGroup(
    @Suppress("UNUSED_PARAMETER") isUserAuthoredPrompt: Boolean,
    alreadyInChat: Boolean,
): Boolean = !alreadyInChat

/**
 * **这条 agent 事件，是不是群里已经有一条同样的了？**
 *
 * ## 为什么不能直接比字符串
 *
 * 用户 @ 了 agent 的那条群消息，经过 relay 注入时**被剥掉了 mention**
 * （`stripMention`：agent 应该看到指令，不该看到 `@token`）。回传到群里的
 * `finalAnswer` 因此是剥离后的正文。
 *
 * 真机现场：群里发 `@dsh-teahouse-mobile test2` ⇒ 注入 ⇒ 事件回传 `test2` ⇒
 * 去重拿 `"@dsh-teahouse-mobile test2"` 和 `"test2"` 比，**不相等** ⇒ 又发一条。
 * 用户只发了一次，群里出现两条 —— 而第二条看起来就是他发的（`mine = isUserPrompt`）。
 *
 * ## 判据
 *
 * 两边都剥掉 mention 再比，用的是 `extractAgentPrompt`（与 relay 的 `stripMention`
 * 同一口径：剥 `@token` + 归一化空格）。规则一处改，两边一起变。
 *
 * 时间窗保留：同一条指令隔几分钟重新执行是合理的，不该被当重复吞掉。
 */
internal fun alreadyForwardedToGroup(
    isUserAuthoredPrompt: Boolean,
    existingMessages: List<com.kapp.tidings.domain.model.MessageUi>,
    finalAnswer: String,
    eventAt: Long,
    agentKind: String?,
): Boolean {
    if (!isUserAuthoredPrompt) return false
    // kinds 由调用方给（会话自报的那一个）；两侧用同一份 ⇒ 去重口径一致。
    val kinds = setOfNotNull(agentKind?.trim()?.lowercase()?.takeIf { it.isNotBlank() })
    val answer = com.kapp.tidings.domain.RelayController.extractAgentPrompt(finalAnswer, kinds)
    return existingMessages.any { existing ->
        com.kapp.tidings.domain.RelayController.extractAgentPrompt(existing.text, kinds) == answer &&
            kotlin.math.abs(existing.at - eventAt) < 120_000L
    }
}

internal fun recordLocalWorkspaceFrom(
    controller: RelayController,
    rec: AgentWorkspaceRegistry.WorkspaceRecord,
) {
    controller.recordLocalWorkspace(
        wsId = rec.wsId,
        wsTitle = rec.wsTitle,
        wsPath = rec.wsPath,
        agentKey = rec.agentKey,
        agentKind = rec.agentKind,
        online = rec.online,
        sessionCount = rec.sessionCount,
        order = rec.order,
        at = rec.updatedAt,
    )
}
/**
 * 「已经发过群」的水位台账：（sessionId × groupId）→ 已转发到该群的最大 cursor。落盘成一个小文本。
 *
 * 读不到 / 读坏了就当作**空台账**：后果是补发那一趟会多试一次，而那次发送还会被
 * alreadyInChat 判重挡掉 —— 宁可多试一次，也不要因为一个坏文件把整段历史判成「发过了」。
 */
internal class GroupForwardLedger(private val file: java.io.File) {
    private val cursors = LinkedHashMap<String, Long>()

    init {
        // 格式：每行「sessionId<TAB>groupId<TAB>cursor」。**不用 JSON**：desktopApp 的编译类路径上
        // 没有 kotlinx-serialization（那是 composeApp 的依赖），为一个水位文件引进依赖不值。
        // 只认三列；旧的两列行（本功能没上线过，不存在真实数据）会被跳过 ⇒ 退化成空台账。
        runCatching {
            if (file.isFile) {
                for (line in file.readLines()) {
                    val parts = line.split('\t')
                    if (parts.size != 3) continue
                    val cursor = parts[2].trim().toLongOrNull() ?: continue
                    cursors[keyOf(parts[0], parts[1])] = cursor
                }
            }
        }
    }

    /** 这个（会话 × 群）已经转发到的最大 cursor；没记过返回 -1（任何非负 cursor 都比它大）。 */
    fun cursorOf(sessionId: String, groupId: String): Long = cursors[keyOf(sessionId, groupId)] ?: -1L

    /**
     * 记下「这条事件已经交给运行时发这个群了」。只在发送真的被调用之后记。
     *
     * 粒度是**（会话 × 群）**：每个群是独立的投递目标，对一个群失败不该压制另一个群。
     */
    fun mark(sessionId: String, groupId: String, cursor: Long) {
        val key = keyOf(sessionId, groupId)
        if (cursor <= (cursors[key] ?: -1L)) return
        cursors[key] = cursor
        runCatching {
            file.parentFile?.mkdirs()
            // 整份重写（不是追加）：条目数很小，而重写让文件里不会留下同一个键的旧行 ——
            // init 的解析是「后者覆盖前者」，追加会依赖行序，重写则不依赖。
            file.writeText(cursors.entries.joinToString("\n") { (k, v) -> k + "\t" + v })
        }
    }

    private fun keyOf(sessionId: String, groupId: String): String = sessionId + "\t" + groupId
}

/**
 * 从事件列表里挑出**该补发到群**的那批（task-46 的判据，唯一真源）。
 *
 * 三条同时成立才算候选：
 *  1. 是**用户消息**（`user.message`）—— agent 正文由实时那一路负责，补发重发它只会刷屏；
 *  2. 游标**大于**该（会话 × 群）的水位 —— 发过的绝不再发（幂等）；
 *  3. **不是对端回声** —— 手机自己发的那条会先落本地会话，补发它等于用户自己的话在群里出现两遍。
 *
 * 顺序保持入参顺序（不排序）：调用方按事件列表顺序投递，与实时那一路的观感一致。
 */
internal fun groupForwardCandidates(
    events: List<AgentSessionRegistry.EventRecord>,
    forwardedCursorOf: (AgentSessionRegistry.EventRecord) -> Long,
    isPeerEcho: (AgentSessionRegistry.EventRecord) -> Boolean,
): List<AgentSessionRegistry.EventRecord> = events.filter { rec ->
    rec.eventType.lowercase() == "user.message" &&
        rec.cursor > forwardedCursorOf(rec) &&
        !isPeerEcho(rec)
}

/**
 * 事件路由诊断（task-57）：**诊断行不含正文**，计数 + 有界环形缓冲。
 *
 * 热路径原来直接 println —— 每条事件一条，还把**用户消息正文**打出去（私有信使的 stdout
 * 会进终端/日志/被别的工具抓走）。收敛之后：诊断行里**没有正文的参数位**（只有长度），
 * 逐事件 stdout 默认关闭（见 logEventRoute 的 eventDebugEnabled）。
 */
internal class EventRouterDiagnostics(private val capacity: Int = 32) {

    private val counts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val recent = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun record(reason: String, line: String) {
        counts.computeIfAbsent(reason) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        recent.addLast(line)
        while (recent.size > capacity) recent.pollFirst()
    }

    /** 按原因聚合的计数，**多的排前面**（读起来先看到主因）。 */
    fun snapshot(): Map<String, Long> =
        counts.entries.sortedByDescending { it.value.get() }.associate { it.key to it.value.get() }

    /** 最近若干条（最新在后），每条都是 [eventDiagnosticLine] 的产物。 */
    fun recentLines(): List<String> = recent.toList()
}

/**
 * 逐事件 stdout 输出**默认关闭**：只有显式 JVM 参数 relay.debug.events=1 才打。
 */
internal val eventDebugEnabled: Boolean = System.getProperty("relay.debug.events") == "1"

/** 一条 agent 事件在聊天通道里的最终形态（实时与补发共用的构造产物）。 */
internal data class AgentChatMessage(
    val text: String,
    val reasoning: String?,
    val isUserPrompt: Boolean,
    val senderId: String,
    /**
     * 这条消息属于哪个 agent 会话（schema 见 `rec`）。
     *
     * 加在**共用构造**里而不是两个调用点各传一次：实时与补发两条路各写一份的话，
     * 迟早有一处漏掉 —— 而漏掉的表现是「实时有分段线、补发没有」，只在断网重连后才看得见。
     */
    val agentSessionId: String? = null,
    /**
     * 该会话的标题 —— 分段线在**对端**显示「任务：xxx」的唯一来源。
     *
     * 标题本来是本机私有数据（`agentSessions` 从没同步过对端），真机上手机侧那张表
     * 是空的 ⇒ 它那边所有分段线只能显示「新会话」。跟着消息走之后单帧自包含。
     */
    val agentSessionTitle: String? = null,
    /** 会话内游标（与 [agentSessionId] 配对）。 */
    val agentCursor: Long? = null,
)

/**
 * 实时与补发**共用的消息构造**（task-59）：文案、思考块、发送者只有这一份实现。
 *
 * 此前两条路各写一份 —— 补发不传会话标题、发送者硬编码 agent:dsh，于是同一条事件在
 * 「实时」与「补发」下**文案与发送者都不一样**（用户可见）。共用之后不可能再漂。
 */
internal fun agentChatMessageFor(
    rec: AgentSessionRegistry.EventRecord,
    sessionTitle: String?,
    selfNodeId: String,
    agentKind: String?,
    format: (AgentSessionRegistry.EventRecord, String?) -> String?,
    /**
     * 只有思考、没有正文时的替代正文（空串 = 维持旧行为，直接丢弃这条）。
     *
     * 为什么放在**共用构造**里而不是两个调用点各传一次：实时与补发各写一份的话，
     * 迟早有一处漏掉 —— 而漏掉的表现正是「只是那一路没有下文」，只在断网重连后才看得见
     * （与 task-59 b 同一条纪律）。
     */
    thinkingOnlyFallback: String = "",
): AgentChatMessage? {
    // 债#6：没有 kind（且没有 agentId）就没有可署名的身份 —— 不产出发送者，
    // 事件不进群。身份必须来自声明；本地台账照记（recordLocalEvent 在调用方）。
    if (agentKind.isNullOrBlank()) return null
    val eventText = format(rec, sessionTitle) ?: return null
    if (!hasVisibleText(eventText)) return null
    val isUserPrompt = rec.eventType.lowercase() == "user.message"
    val (parsedReasoning, cleanAnswer) = com.kapp.tidings.domain.model.parseThinkingContent(eventText)
    // 只有思考、没有正文：**不许静默丢弃**（真机上这类回合整轮消失过，用户只说「没有下文」）。
    // 思考块留在 reasoning 里（界面上是折叠的「思考」），正文用一句如实的话顶上；
    // 顶替文本取不到（空串）时才退回旧行为。
    val thinkOnly = !hasVisibleText(cleanAnswer)
    val finalAnswer = when {
        !thinkOnly -> cleanAnswer
        parsedReasoning != null && hasVisibleText(thinkingOnlyFallback) -> thinkingOnlyFallback
        else -> return null
    }
    return AgentChatMessage(
        text = finalAnswer,
        reasoning = parsedReasoning,
        isUserPrompt = isUserPrompt,
        // agent 的标签**必须**走 `agentSenderIdFor`（它会把 kind 统一成小写）。
        // 这里原来写的是 `"agent:" + agentKind`，而线上那条路用的是 `agentSenderIdFor` ——
        // 同一个说话者在桌面端落库成 `agent:DSH`、在手机端落库成 `agent:dsh`，
        // 一个 agent 在群里显示成两个人（真机库里两种拼写各 801 / 113 条）。
        senderId = if (isUserPrompt) selfNodeId else agentSenderIdFor(selfNodeId, agentKind),
        // 会话归属：`rec` 就是这条路唯一的事实来源，两条路（实时/补发）都从这里取，
        // 所以不会出现「实时有分段线、补发没有」。
        agentSessionId = rec.sessionId,
        agentSessionTitle = sessionTitle,
        agentCursor = rec.cursor,
    )
}

/**
 * 诊断行：**只有类型/会话/游标/长度/原因**，没有正文的参数位。
 *
 * 长度是唯一的「内容侧」信息 —— 事件类型 + 会话 + 游标 + 长度 + 原因足够回答
 * 「这条为什么没投出去」，而正文永远不会出现在任何输出里。
 */
/**
 * 群消息里表示"这是 agent 说的"的发送者键，形如 `agent:dsh`。
 *
 * 与**桌面端本地落库**（`RelayControllerGroupsComponent` 的 `senderId = "agent:${kind}"`）
 * 同一形态 —— 两端显示同一条气泡时必须得到同一个发送者，否则同一个群在手机上写"桌面端"、
 * 在桌面上写 "DSH"，用户会以为多了一个人。
 *
 * kind 为空时退回 `agent:dsh`：宁可显示一个通用 agent 名，也不要退回成"桌面端"这个**错的人**。
 */
internal fun agentSenderIdFor(selfNodeId: String, agentKind: String?, agentId: String? = null): String =
    // 交给 core 的**唯一来源**：它产出 `agent:<hostNodeId>:<agentId>`，并处理长度预算
    // （Limits.ID = 64，留给 agentId 只有 21 个字符）。各写一份已经漂过一次 ——
    // 真机库里 `agent:DSH` 与 `agent:dsh` 并存。
    AgentSenderId.of(selfNodeId, agentId, agentKind)

internal fun eventDiagnosticLine(
    type: String,
    sessionId: String,
    cursor: Long,
    textLength: Int,
    reason: String,
): String = "type=" + type + " session=" + sessionId + " cursor=" + cursor +
    " len=" + textLength + " reason=" + reason

/**
 * 会话内**单调**的事件游标分配器（task-50 审查修）。
 *
 * 不能用 `System.currentTimeMillis()` 当 cursor：同一毫秒内的两条事件会撞在同一个游标上，
 * 而 AgentSessionRegistry 按游标覆盖、投递侧又按游标去重 ⇒ **静默丢内容**。
 * 这里保证「同一会话内严格递增」：时钟前进就跟时钟，时钟不动/回拨就在上一个值上 +1。
 * 跨会话互不影响（每个 sessionId 一个计数器）。
 */
internal class EventCursorAllocator {

    private val cursors = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /** 下一个游标。[now] 只为单测可注入，生产用时钟。 */
    fun next(sessionId: String, now: Long = System.currentTimeMillis()): Long {
        // 初值用哨兵而不是 now：下面那条 updateAndGet 对「第一条」必须落在 now 本身
        // （不是 now+1）。用 now 当初值会让每个会话的第一条游标凭空 +1，与「跟时钟走」不符。
        val counter = cursors.computeIfAbsent(sessionId) {
            java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        }
        return counter.updateAndGet { prev -> if (now > prev) now else prev + 1 }
    }
}

/**
 * 事件投递去重键（task-60 中段通读修）。
 *
 * **必须带 nodeId**：`AgentSessionRegistry` 的 KDoc 逐字写着「cursor 只在同一个 (node, session)
 * 内单调，所以去重键是 (nodeId, sessionId, cursor)」。这里原来只用了 `sessionId:cursor` ——
 * 两台设备（桌面自己也是宿主）若出现同名 sessionId + 同毫秒游标，后到那条会被当成
 * **重复投递丢掉**：群里少一条、界面没有任何提示。与我这一轮修的游标那条同一族
 * （都是「键少了身份维度 ⇒ 静默丢内容」）。
 */
internal fun eventDeliveryKey(nodeId: String, sessionId: String, cursor: Long): String =
    nodeId + ":" + sessionId + ":" + cursor
