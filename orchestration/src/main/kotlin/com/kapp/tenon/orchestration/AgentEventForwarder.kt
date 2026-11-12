// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.AgentSessionRegistry
import com.kapp.tenon.agent.AgentWorkspaceRegistry
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.model.hasVisibleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **一条 agent 事件该不该进群、进哪个群、算不算重复** —— 转发侧的判定与账本。
 *
 * 这里躺着两层东西：
 *
 * 1. **纯判定 + 小账本**（顶层：`shouldForwardAgentEventToGroup` / `alreadyForwardedToGroup` /
 *    `groupForwardCandidates` / `GroupForwardLedger` / `EventRouterDiagnostics` / 游标与去重键）。
 *    参数齐备、不碰任何宿主字段。
 * 2. [AgentEventForwarder] —— 把上面这些判定接到一条真实事件上的**有状态**那一层。
 *
 * 为什么合在一处：这几条判定的缺陷形态**都是静默的** —— 转发错了群、漏转发、重复转发，
 * 任何一条都不抛异常，只会表现成「手机上看不到」或「群里刷两遍」。改这里时不该被
 * 控制面挂载、卡片动作、插件宿主那些代码夹在中间。
 */

/**
 * **agent 事件 → 群聊**这条路上全部有状态的部分（装配侧只剩一行转发调用）。
 *
 * ## 为什么是类，而不是又一组顶层函数
 *
 * 分发需要两样**随挂载态变化**的东西：当前运行时（往群里发要它）与当前会话台账
 * （按 `(nodeId, sessionId)` 找会话要它）。它们的所有者是 `DesktopExtension`，
 * 所以这里**不复制**这两份状态，只拿两个取值器（形参）。复制一份的后果是
 * 「控制面重挂之后，转发还在往旧运行时发帧」—— 正是 `attachedRuntime` 的 KDoc
 * 记着的那条坑。
 *
 * 另外三样是**跨事件累积**的状态（去重键集合 / 已转发水位 / 正在投递中的占位），
 * 唯一读者就是这里的分发逻辑，于是跟着搬进来，`DesktopExtension` 不再持有。
 */
internal class AgentEventForwarder(
    private val controller: RelayController,
    private val scope: CoroutineScope,
    /** 当前挂着的运行时；未挂 / 重启中为 null。取值器而不是副本，见类 KDoc。 */
    private val currentRuntime: () -> NodeRuntime?,
    /** 当前挂着的会话台账；未挂为 null。同上。 */
    private val registryRef: () -> AgentSessionRegistry?,
    /**
     * **会话 → 会话键**的归属查询（落盘台账，派单侧写）。未挂 / 没有绑定为 null。
     *
     * 默认值让「没有台账的装配」退回三键反查：测试与 :cli 不必为了构造一个转发器
     * 而先准备一份落盘文件。真实宿主在 `DesktopExtension` 里接到
     * `ControlPlane.conversationOfSession` 上 —— 那条路径是**唯一权威答案**。
     */
    private val conversationOf: (String) -> String? = { null },
) {
    /**
     * 已处理过的投递键（`(nodeId, sessionId, cursor)`），LRU 2000 条。
     *
     * 上限是必须的：事件条数只增不减，无界集合会随运行时长线性涨。
     * 键必须带 nodeId —— 见 [eventDeliveryKey] 的 KDoc（少了身份维度就是静默丢内容）。
     */
    private val processedEventCursors = java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(2000, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>?): Boolean = size > 2000
        }
    )

    /** 事件路由诊断：只有类型/游标/长度/原因与计数，**没有任何正文**。见 EventRouterDiagnostics。 */
    val eventDiagnostics = EventRouterDiagnostics()

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
        // 文案经 RelayUiTexts 桥取（与投屏卡正文、群横幅同一条接缝）：编排层是下沉到
        // :orchestration 的共享层，不许直接碰 composeResources —— 否则「编排面零 UI」就破了。
        bridgeBlocking("uiTexts.thinkingOnly", fallback = "") {
            controller.uiTexts.agentEventThinkingOnlyFallback()
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
    private val groupForwardLedger =
        GroupForwardLedger(java.io.File(controller.dataDirectory, "agent-group-forwarded.json"))

    /**
     * 正在投递中的（会话 × 群 × 游标）。
     *
     * 为什么需要它：水位**只在 send 成功后才记**（记早了就永远补不回来），而补发触发点是
     * 「换网 / 对端上线」，会在上一次 send 还在飞时再跑一遍 ⇒ 同一个 (session, group, cursor)
     * 被选中两次 ⇒ **群里出现两条**。占位在选中前加、失败释放、成功由水位接管。
     */
    private val inFlightGroupForwards = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 一条 agent 事件入库之后的**完整分发**：去重 → 落本地台账 → 找绑定的群 → 落气泡 → 发群。
     *
     * 这里原来是 `syncOnce` 里 `onRecordEvent` 的匿名实现（200 行 lambda）。逐字搬过来只改三处：
     * `return@onRecord` → `return`（原来就是同一个作用域里的早退）、`registryRef?.` → `registryRef()?.`、
     * `attachedRuntime` → `currentRuntime()`（见类 KDoc 的第二段）。
     */
    fun handleRecordEvent(rec: AgentSessionRegistry.EventRecord) {
        val eventKey = eventDeliveryKey(rec.nodeId, rec.sessionId, rec.cursor)
        val isNew = synchronized(processedEventCursors) { processedEventCursors.add(eventKey) }
        if (!isNew) {
            logEventRoute(rec.eventType, rec.sessionId, rec.cursor, 0, "duplicate-delivery")
            return
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
        val localSess = registryRef()?.findSession(rec.nodeId, rec.sessionId)
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
            val matchInRegistry = registryRef()?.sessions()?.firstOrNull { it.sessionId == rec.sessionId && !it.wsPath.isNullOrBlank() }
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

        // 没有绑定群就**不转发**（沉默不猜）。原因用规范 §3 不变量 1 的词汇
        // `no-conv-bound`：台账没记这条会话，三键又没能反查出**唯一**一个群。
        //
        // 这里原来有一段「兜底匹配」：群的名称/描述里带某个仓库名（写死过
        // "deepseek-harness"），或全局只有一个群，就把**任何**工作区的事件塞进去。
        // 后果是实测出来的：teahouse-mobile 的会话（session-868856e1…）根本没绑定群，
        // 却被灌进了「deepseek-harness 协同群」，整个会话的 user.message /
        // assistant.text 都出现在用户眼前。宁可这条事件不显示，也不要把甲仓库的活
        // 干到乙仓库的群里。finalWsPath / finalWsId / finalWsTitle 仍是上面匹配用的键。
        if (targetGroups.isEmpty()) {
            logEventRoute(rec.eventType, rec.sessionId, rec.cursor, rec.body?.length ?: 0, "no-conv-bound")
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
                if (!hasVisibleText(finalAnswer)) return
                for (group in targetGroups) {
                    val convId = com.kapp.tenon.core.protocol.ConvId.group(group.groupId)
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
                    val msg = com.kapp.tenon.domain.model.MessageUi(
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

                    val rt = currentRuntime()
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
        if (eventDebugEnabled) RelayLog.info("RELAY-EVENT", line)
    }

    /**
     * 一条 agent 事件在聊天通道里的**最终形态** —— 实时路由与补发共用这一个构造。
     *
     * 为什么必须共用（task-59 b）：此前两条路各写一份 —— 补发不传标题、发送者硬编码
     * agent:dsh，于是同一条事件在「实时」与「补发」下**文案与发送者都不一样**（用户可见）。
     * 收敛成一个函数之后，两边逐字一致是**构造上**成立的，不靠人记得同步改两处。
     */
    fun chatMessageFor(
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

    /**
     * 事件/会话 → 该投的群。**唯一真源**：实时路由与两个补发都走它。
     *
     * ## 三层判定，逐层收紧
     *
     * 1. **会话台账**（[conversationOf]）：派单侧在 `executeCliTask` 里写下
     *    `sessionId → convId`。命中就只投那一个群 —— 这是**根除串群**的那一层：
     *    在 A 群派的单永远只回 A 群，哪怕同一个目录还绑着 B 群。
     *    台账指向的群已经不在（退群 / 对方重建）⇒ 阻断，不猜别的群；
     *    台账指向私聊（键没有 `group:` 前缀）⇒ 不是群的路由问题，直接放弃。
     * 2. **工作区三键反查**（wsPath / wsId / wsTitle），**只接受唯一解**：
     *    命中 0 个 ⇒ 没绑定；命中 >1 个 ⇒ 歧义，阻断并记 `ambiguous-group`。
     *    这一层是给「没经过我们派单的会话」（用户在 DSH 里自己开的）兜底用的。
     *    多个群绑同一个目录时**不再复制成多份** —— 那正是串群缺陷的形态。
     * 3. 都没有 ⇒ 不投。
     *
     * 三键的三条口径：任一命中即算绑定（此前实时用三键、两个补发只用 wsPath ⇒
     * 靠 wsTitle 绑定的群实时能投、补发静默投不到）；全空时从 state 推断；
     * 同一个 sessionId 在不同设备上**报出不同工作区归属**（跨设备撞 sessionId）时阻断。
     */
    internal fun groupsForSession(
        state: com.kapp.tenon.domain.RelayUiState,
        sessionId: String,
        wsPath: String?,
        wsId: String?,
        wsTitle: String?,
    ): List<com.kapp.tenon.core.protocol.GroupMeta> {
        // ---- 第 1 层：台账 ----
        val boundConv = conversationOf(sessionId)?.trim().orEmpty()
        if (boundConv.isNotEmpty()) {
            val boundGroupId = com.kapp.tenon.core.protocol.ConvId.groupIdOf(boundConv)
            if (boundGroupId == null) {
                // 台账说这条会话属于某个私聊。事件照常落本地台账，只是不进任何群。
                logEventRoute("session", sessionId, 0L, 0, "bound-to-1to1")
                return emptyList()
            }
            val group = state.settings.groups.firstOrNull { it.groupId == boundGroupId }
            if (group == null) {
                // 派过单、但那个群已经不在本机名单里（退群/重建）。**不回落第 2 层**：
                // 回落等于把「A 群的活」按目录猜进 B 群 —— 正是要根除的那条。
                logEventRoute("session", sessionId, 0L, 0, "bound-group-gone")
                return emptyList()
            }
            return listOf(group)
        }

        // ---- 第 2 层：工作区三键，唯一解 ----
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
        val matched = state.settings.groups.filter { group ->
            val desc = group.description?.trim().orEmpty()
            (path.isNotEmpty() && desc.contains(path)) ||
                (id.isNotEmpty() && (desc.contains(id) || group.name.contains(id))) ||
                (title.isNotEmpty() && group.name.contains(title)) ||
                (path.isNotEmpty() && group.name.contains(path.substringAfterLast('/')))
        }
        if (matched.size > 1) {
            logEventRoute("session", sessionId, 0L, 0, "ambiguous-group hits=" + matched.size)
            return emptyList()
        }
        return matched
    }

    /**
     * 把「用户自己打的字」补发到群：离线期间、或 rt == null 时错过的那批。
     *
     * 与 backfillMissingAgentEvents 的分工：那个补**本地会话**（台账里本地没有的事件），
     * 这个补**群**（本地早就有、只是从没发出去过的用户消息）—— 两件事的判据不同，合成一个
     * 会把「本地有就跳过」当成「群里也发过了」，而那正是这条缺陷的成因。
     */
    fun backfillMissedGroupForwards() {
        val ledger = groupForwardLedger
        val rt = currentRuntime() ?: return
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

    fun backfillMissingAgentEvents() {
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
                        val convId = com.kapp.tenon.core.protocol.ConvId.group(group.groupId)
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
                        val msg = com.kapp.tenon.domain.model.MessageUi(
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


}

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
fun shouldForwardAgentEventToGroup(
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
fun alreadyForwardedToGroup(
    isUserAuthoredPrompt: Boolean,
    existingMessages: List<com.kapp.tenon.domain.model.MessageUi>,
    finalAnswer: String,
    eventAt: Long,
    agentKind: String?,
): Boolean {
    if (!isUserAuthoredPrompt) return false
    // kinds 由调用方给（会话自报的那一个）；两侧用同一份 ⇒ 去重口径一致。
    val kinds = setOfNotNull(agentKind?.trim()?.lowercase()?.takeIf { it.isNotBlank() })
    val answer = com.kapp.tenon.domain.RelayController.extractAgentPrompt(finalAnswer, kinds)
    return existingMessages.any { existing ->
        com.kapp.tenon.domain.RelayController.extractAgentPrompt(existing.text, kinds) == answer &&
            kotlin.math.abs(existing.at - eventAt) < 120_000L
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
fun recordLocalWorkspaceFrom(
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
class GroupForwardLedger(private val file: java.io.File) {
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
fun groupForwardCandidates(
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
class EventRouterDiagnostics(private val capacity: Int = 32) {

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
val eventDebugEnabled: Boolean = System.getProperty("relay.debug.events") == "1"

fun eventDiagnosticLine(
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
class EventCursorAllocator {

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
fun eventDeliveryKey(nodeId: String, sessionId: String, cursor: Long): String =
    nodeId + ":" + sessionId + ":" + cursor
