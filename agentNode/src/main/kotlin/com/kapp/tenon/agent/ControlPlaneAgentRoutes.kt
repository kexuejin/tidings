// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.AgentSessionStates
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.DecodeResult
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.makeEnvelope
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * agent 会话台账（session / event / workspace / session-pages / sessions）。
 *
 * 设备侧提交会话与事件、宿主侧读清单：这张台账是面板读数的唯一来源。
 *
 * 「谁记了什么」只有这一处 —— 消费者（桌面面板、手机列表、编排层）都读
 * `AgentSessionRegistry` 与 `AgentWorkspaceRegistry`，没有第二条写入路径。
 *
 * 两条 agent 路由受 §4 约束：**对方没声明 `agn1` 就不发**，返回 409 并建议改用 `/v1/send`；
 * 返回 409 而不是排进重传队列，是因为后者会表现成「对方离线」，从日志里查不出来。
 */

/** `POST /v1/agent/session` —— 宣告一个会话，或它的状态变更。 */
internal fun ControlPlane.agentSession(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_SESSION) ?: return
    val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (sessionId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.1 的必填字段）"))
        return
    }
    val alias = obj["alias"]?.jsonPrimitive?.contentOrNull
    val agentKind = obj["agentKind"]?.jsonPrimitive?.contentOrNull
    val state = obj["state"]?.jsonPrimitive?.contentOrNull
    // 出站收紧、收端仍然开放：`Codec.validateAgentSession` 的 KDoc 解释了收端为什么必须
    // 宽容（拒收整帧会让对端一直重传到队列剪枝）。控制面是**发送方**，`state` 是我们自己
    // 写的字符串，收紧的代价只是一个可操作的 400 —— 而不是让一个拼错/超长的值走到自检
    // 那一步，换回一句「这一帧我们自己都拒收：…」。取值集复用 `AgentSessionStates.ALL`，
    // 不另造第二套真源。
    if (state != null && state !in AgentSessionStates.ALL) {
        respond(
            exchange,
            400,
            errorJson(
                "bad-request",
                "state 只能是 ${AgentSessionStates.ALL.joinToString(" / ")}（§6.1）；收到「$state」",
            ),
        )
        return
    }
    val title = obj["title"]?.jsonPrimitive?.contentOrNull
    val startedAt = numberOrNull(obj["startedAt"])
    // PPE-1 增补 D1/D6：控制面是**转发方**，四个新键原样透传。cap 门控
    // （只向声明 aws1 的对端附带新键）住在投递层 NodeOutbound.sendAgentSession，
    // 不在这里；这里只把形状交给 codec 自检。
    val agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull
    val wsId = obj["wsId"]?.jsonPrimitive?.contentOrNull
    val wsTitle = obj["wsTitle"]?.jsonPrimitive?.contentOrNull
    val wsPath = obj["wsPath"]?.jsonPrimitive?.contentOrNull
    // 会话的**活动时刻**（epoch 毫秒）= max(createdAt, lastPromptAt)，DSH 自己的会话
    // 列表就是用它排序的。它属于**会话**、不属于工作区身份，所以和 startedAt 同一档
    // （不像那四个 D1/D6 键那样受 aws1 门控）。台账**不落**它：AgentSessionRegistry 的
    // updatedAt 是"本节点记账时刻"、是淘汰的唯一依据，混进来会让淘汰判错。
    val updatedAt = numberOrNull(obj["updatedAt"])
    // 可见性两键（PPE-1 增补）：`origin` 缺省 undefined、`blank` 三态。控制面是转发方，
    // 不做取值收紧 —— 取值不封闭，由 codec 按形状校验（见 AgentSessionPayload 的 KDoc）。
    val origin = obj["origin"]?.jsonPrimitive?.contentOrNull
    val blank = boolOrNull(obj["blank"])
    deliverAgentFrame(
        exchange = exchange,
        nodeId = nodeId,
        type = MsgTypes.AGENT_SESSION,
        payload = Codec.agentSessionPayload(
            sessionId, alias, agentKind, state, title, startedAt,
            agentId, wsId, wsTitle, wsPath, updatedAt, origin, blank,
        ),
        send = {
            runtime.sendAgentSession(
                nodeId, sessionId, alias, agentKind, state, title, startedAt,
                agentId, wsId, wsTitle, wsPath, updatedAt, origin, blank,
            )
        },
        // 只在这一帧真的投出去之后才记账：`deliverAgentFrame` 的 `onSent` 是成功回调，
        // 失败（自检拒收 / 502）时台账里不该留下一个从没上过线的会话。
        onSent = {
            sessions.recordSession(
                nodeId = nodeId,
                sessionId = sessionId,
                alias = alias,
                agentKind = agentKind,
                state = state,
                title = title,
                startedAt = startedAt,
                agentId = agentId,
                wsId = wsId,
                wsTitle = wsTitle,
                wsPath = wsPath,
                outbound = true,
            )
        },
    )
}

/**
 * `POST /v1/agent/workspace` —— 宣告**一个**工作区（PPE-1 增补 D3，cap `aws1`）。
 *
 * 与 session/event 两条路由的差别：这帧**尽力而为**（不在 RELIABLE），所以回执里没有
 * sent/queued 之分 —— 返回 `delivered`（已交给传输层）并如实注明"无送达回执"。
 * 台账（出站那一半）在这里记；入站那一半由宿主进程的订阅走 `recordInboundWorkspace`，
 * 两边共用同一实例（与 sessions 台账同一条纪律）。
 */
internal fun ControlPlane.agentWorkspace(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_WORKSPACE) ?: return
    val wsId = obj["wsId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (wsId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 wsId（增补 D3 的必填字段）"))
        return
    }
    val wsTitle = obj["wsTitle"]?.jsonPrimitive?.contentOrNull
    val wsPath = obj["wsPath"]?.jsonPrimitive?.contentOrNull
    val agentKind = obj["agentKind"]?.jsonPrimitive?.contentOrNull
    val agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull
    // 出站收紧、收端仍然开放（与 agentSession 的 state 同一纪律）：`online` 写成字符串
    // 时**必须 400** —— 第一版用 takeIf 静默丢键，调用方传了 `"online":"true"` 却收到
    // 一份没有 online 的帧，那是"把用户要的东西悄悄改成别的东西"。
    val online: Boolean? = if (!obj.containsKey("online")) null else {
        val p = obj["online"] as? JsonPrimitive
        if (p == null || p.isString || (p.content != "true" && p.content != "false")) {
            respond(
                exchange, 400,
                errorJson("bad-request", "online 必须是布尔字面量 true / false（宿主自报的事实，形状错了就是假数据）"),
            )
            return
        }
        p.content == "true"
    }
    val sessionCount: Long? = if (!obj.containsKey("sessionCount")) null else numberOrNull(obj["sessionCount"])
    if (obj.containsKey("sessionCount") && sessionCount == null) {
        respond(exchange, 400, errorJson("bad-request", "sessionCount 必须是非负整数"))
        return
    }
    if (sessionCount != null && sessionCount < 0) {
        respond(exchange, 400, errorJson("bad-request", "sessionCount 必须是非负整数；收到 $sessionCount"))
        return
    }
    // 注册表持久序（`AgentWorkspacePayload.order`）。与 sessionCount 同款：错了就是假数据 400，
    // 而不是静默丢键 —— 丢了手机就只能退回按到达序排，重启一次顺序就乱。
    val order: Long? = if (!obj.containsKey("order")) null else numberOrNull(obj["order"])
    if (obj.containsKey("order") && order == null) {
        respond(exchange, 400, errorJson("bad-request", "order 必须是非负整数"))
        return
    }
    if (order != null && order < 0) {
        respond(exchange, 400, errorJson("bad-request", "order 必须是非负整数；收到 $order"))
        return
    }
    val at = numberOrNull(obj["at"])
    // 归组键 = agentId ?? agentKind（与会话侧同判据，见 AgentInbound 的注释）。
    val agentKey = agentId ?: agentKind

    val frame = Codec.encode(
        makeEnvelope(
            MsgTypes.AGENT_WORKSPACE, runtime.nodeId,
            Codec.agentWorkspacePayload(wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order),
        ),
    )
    val back = Codec.decodeTcp(frame)
    if (back is DecodeResult.Rejected) {
        respond(exchange, 400, errorJson("bad-request", "这一帧我们自己都拒收：" + back.reason))
        return
    }
    // cap 门控（aws1）已由 resolveAgentTarget 按帧类型查过（没声明 ⇒ 409 提前拒绝）。
    val acked = if (nodeId == runtime.nodeId) true else try {
        CarrierHold.hold("send.agentWorkspace", CarrierHold.SEND_MS) {
            runtime.sendAgentWorkspace(
                nodeId, wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order,
            )
        }
    } catch (t: Throwable) {
        respond(exchange, 502, errorJson("send-failed", t.message ?: "发送失败"))
        return
    }
    workspaces.record(
        nodeId = runtime.nodeId,
        agentKey = agentKey,
        wsId = wsId,
        wsTitle = wsTitle,
        wsPath = wsPath,
        agentKind = agentKind,
        online = online,
        sessionCount = sessionCount,
        // 上面刚按 codec 校验过的 order 必须进台账：只发给对端、本机不记，
        // 面板的「本宿主工作区」就永远排不出宿主自己的顺序（真机 15/15 NULL）。
        order = order,
        outbound = true,
        at = at ?: System.currentTimeMillis(),
    )
    respond(
        exchange,
        200,
        buildJsonObject {
            // 可靠但不入队：回执只有 sent / not-acked 两档，**没有 queued** ——
            // 状态快照不补发（下一份快照会盖掉它），也不该把在线对端拖进离线队列。
            put("status", if (acked) "sent" else "not-acked")
            put("to", nodeId)
            put("type", MsgTypes.AGENT_WORKSPACE)
            put("note", "台账已记本宿主的工作区（宣告与送达无关）；" +
                if (acked) "对方已 ACK" else "对方没 ACK（多半是不认识这帧的旧版本）；不入队、下次宣告自然覆盖")
        },
    )
}

/** `GET /v1/agent/workspaces` —— 本宿主的工作区清单（面板「第三面」的读数）。 */
internal fun ControlPlane.agentWorkspaces(exchange: HttpExchange) {
    val query = parseQuery(exchange.requestURI.rawQuery)
    val wantedNode = query["nodeId"]?.trim().orEmpty()
    val rows = workspaces.mergeSessionCounts(sessions)
        .filter { wantedNode.isEmpty() || it.nodeId == wantedNode }
    respond(
        exchange,
        200,
        buildJsonObject {
            putJsonArray("workspaces") {
                rows.forEach { w ->
                    addJsonObject {
                        put("nodeId", w.nodeId)
                        put("agentKey", w.agentKey)
                        put("wsId", w.wsId)
                        put("wsTitle", w.wsTitle)
                        put("wsPath", w.wsPath)
                        put("agentKind", w.agentKind)
                        put("online", w.online)
                        put("sessionCount", w.sessionCount)
                        put("updatedAt", w.updatedAt)
                    }
                }
            }
            put("total", rows.size)
        },
    )
}

/**
 * 待回答的会话分页请求（宿主读）。
 *
 * 只读；宿主读完用 `POST /v1/agent/session` 把那一页会话逐条回填，再调
 * `/v1/agent/session-pages/resolve` 销掉这条。销不掉也不会重复答 —— 队列里同一
 * `(nodeId, wsId)` 只有最新一条（见 AgentSessionPageStore.record）。
 */
internal fun ControlPlane.agentSessionPages(exchange: HttpExchange) {
    val rows = sessionPages.snapshot()
    respond(
        exchange,
        200,
        buildJsonObject {
            put("total", rows.size)
            putJsonArray("requests") {
                rows.forEach { r ->
                    addJsonObject {
                        put("id", r.id)
                        put("nodeId", r.nodeId)
                        put("wsId", r.wsId)
                        put("offset", r.offset)
                        put("limit", r.limit)
                        put("at", r.at)
                    }
                }
            }
        },
    )
}

/** 宿主办完一条后销掉它（body `{ id }`）。幂等：找不到也回 ok。 */
internal fun ControlPlane.resolveAgentSessionPage(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: run {
        respond(exchange, 400, errorJson("bad-request", "请求体不是 JSON 对象"))
        return
    }
    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: run {
        respond(exchange, 400, errorJson("bad-request", "缺少 id"))
        return
    }
    val removed = sessionPages.resolve(id)
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("removed", removed)
        },
    )
}

/** 写入一条会话分页请求（本地桌面端或外部发起的拉取会话请求）。 */
internal fun ControlPlane.createAgentSessionPage(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val wsId = obj["wsId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (wsId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 wsId"))
        return
    }
    val nodeId = obj["nodeId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { runtime.nodeId }
    val offset = numberOrNull(obj["offset"]) ?: 0L
    val limit = numberOrNull(obj["limit"])
    sessionPages.record(
        nodeId = nodeId,
        wsId = wsId,
        offset = offset,
        limit = limit,
        at = System.currentTimeMillis(),
    )
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("status", "recorded")
            put("nodeId", nodeId)
            put("wsId", wsId)
            put("offset", offset)
        },
    )
}

/** 供宿主进程在进程内直调记录会话分页请求。 */
fun ControlPlane.recordSessionPage(nodeId: String, wsId: String, offset: Long, limit: Long? = null) {
    sessionPages.record(nodeId, wsId, offset, limit, System.currentTimeMillis())
}

/** `POST /v1/agent/event` —— 追加一条事件（游标由调用方给，本端不代生成）。 */
internal fun ControlPlane.agentEvent(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_SESSION) ?: return
    val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (sessionId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.2 的必填字段）"))
        return
    }
    val eventType = obj["eventType"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (eventType.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 eventType（§6.2 的必填字段）"))
        return
    }
    // cursor 必须是**数字**：字符串 "42" 会被 Codec 拒收（`validateAgentEvent` 要求整数），
    // 在这里就拦下来，免得走到自检那一步才报一个绕口的理由。
    val cursor = numberOrNull(obj["cursor"])
    if (cursor == null || cursor < 0) {
        respond(
            exchange,
            400,
            errorJson("bad-request", "cursor 必须是非负整数（§6.2：同一会话内的单调游标，不是时间戳）"),
        )
        return
    }
    val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
    val body = obj["body"]?.jsonPrimitive?.contentOrNull
    val ref = obj["ref"]?.jsonPrimitive?.contentOrNull
    // 附件元数据（task-45，cap att1）。两件事在这里做：
    //  ① 形状检查：attachment 在、但读不出 attachmentId + mediaType（或某个可选键类型不对）
    //     ⇒ **整条请求 400**，不静默丢掉、也不在下面把类型错误洗成 null（那是放宽）；
    //  ② **att1 闸门（fail-closed）**：对端没声明 att1 就整条附件不带 —— 帧里连键都没有，
    //     手机看到的仍是 body 里的文本占位。闸门必须在宿主侧：桥看不到对端 caps
    //     （它的源码自己写着控制面读不到），而宿主知道、也正是构帧的地方。
    // 越界（id/name 长度、宽高、bytes）不在这里拦：交给 deliverAgentFrame 的本地自检
    // 整帧拒收并回 400 —— 一处权威（Codec），两处判迟早漂。
    val rawAttachment = obj["attachment"]
    val hasAttachment = rawAttachment != null && rawAttachment !is kotlinx.serialization.json.JsonNull
    val parsedAttachment = if (hasAttachment) parseAgentEventAttachment(rawAttachment) else null
    if (hasAttachment && parsedAttachment == null) {
        respond(
            exchange,
            400,
            errorJson(
                "bad-request",
                "attachment 形状不对：需要 attachmentId 与 mediaType 两个字符串，width/height/bytes/name 可选但类型必须匹配",
            ),
        )
        return
    }
    val attachment = gatedAttachment(parsedAttachment, runtime.peerDeclares(nodeId, MsgTypes.CAP_ATTACHMENT))
    deliverAgentFrame(
        exchange = exchange,
        nodeId = nodeId,
        type = MsgTypes.AGENT_EVENT,
        payload = Codec.agentEventPayload(sessionId, cursor, eventType, summary, body, ref, attachment),
        send = { runtime.sendAgentEvent(nodeId, sessionId, cursor, eventType, summary, body, ref, attachment) },
        onSent = {
            sessions.recordEvent(
                nodeId = nodeId,
                sessionId = sessionId,
                cursor = cursor,
                eventType = eventType,
                summary = summary,
                body = body,
                ref = ref,
            )
        },
    )
}

/**
 * `GET /v1/agent/sessions` —— 本节点的 agent 会话台账（面板的数据源）。
 *
 * 存在的理由：agent 帧出站不写 `messageStore`、也不进 `/v1/inbox`，所以「本机有哪些
 * agent 会话」在控制面上原本**问不到**，面板只能画一个空列表。这个只读路由把
 * [AgentSessionRegistry] 暴露出来。
 *
 * 可选 `sessionId` 查询参数：带上时额外返回该会话的事件明细（面板点进会话要看的事件流）；
 * 不带时只回会话列表，事件条数用 `eventCount` 给一个数（列表页够用，且避免一次拉全部事件）。
 *
 * 可选 `nodeId` 查询参数：把列表与事件明细都限制在这一个节点内。`sessionId` 由各节点
 * 自行分配，两个节点完全可能报同一个 id（`AgentInbound` 的幂等键正是 `(nodeId, sessionId)`），
 * 所以只按 `sessionId` 过滤会把对方的同名会话一并返回，调用方无法分辨。
 */
internal fun ControlPlane.agentSessions(exchange: HttpExchange) {
    val query = parseQuery(exchange.requestURI.rawQuery)
    val wanted = query["sessionId"]?.trim().orEmpty()
    val wantedNode = query["nodeId"]?.trim().orEmpty()
    // 先按 nodeId 限定范围，再按 sessionId 收敛——**只算一次**，`total` 与下面的各个数组
    // 共用同一个 `rows`。此前 `total` 用的是「只按 nodeId 过滤」的那份，于是带 sessionId
    // 点名一个会话时：数组回 1 条，`total` 却回该节点的全部会话数，两个口径打架。
    val wantedWs = query["wsId"]?.trim().orEmpty()
    val scoped = sessions.sessions()
        .filter { wantedNode.isEmpty() || it.nodeId == wantedNode }
        // PPE-1 增补 §4：按工作区收敛（面板「第三面」的读数口径）。
        .filter { wantedWs.isEmpty() || it.wsId == wantedWs }
    // 带 sessionId 时只列那一个会话，免得面板拿到一份它没要的全量列表。
    val rows = if (wanted.isEmpty()) scoped else scoped.filter { it.sessionId == wanted }
    respond(
        exchange,
        200,
        buildJsonObject {
            putJsonArray("sessions") {
                rows.forEach { s ->
                    addJsonObject {
                        put("nodeId", s.nodeId)
                        put("sessionId", s.sessionId)
                        // 可空字段直接交给 put：kotlinx.serialization 的 `put(key, String?)` /
                        // `put(key, Number?)` 会自己写 JsonNull。写成 `s.alias ?: JsonNull`
                        // 反而编不过 —— String 与 JsonNull 的公共父类是 Any，put 收不了。
                        put("alias", s.alias)
                        put("agentKind", s.agentKind)
                        put("state", s.state)
                        put("title", s.title)
                        put("startedAt", s.startedAt)
                        // PPE-1 增补 D1/D6：四个新键（未上报 = null）。
                        put("agentId", s.agentId)
                        put("wsId", s.wsId)
                        put("wsTitle", s.wsTitle)
                        put("wsPath", s.wsPath)
                        put("announcedOut", s.announcedOut)
                        put("announcedIn", s.announcedIn)
                        // true = 这条会话是**推断**出来的占位（两侧都没宣告过，见
                        // `AgentSessionRegistry.ensureSession`）。它的 state 一般为 null，
                        // 面板应降级显示，而不是当成一个正常会话。
                        put("synthesized", s.synthesized)
                        put("updatedAt", s.updatedAt)
                        put("eventCount", sessions.events(s.nodeId, s.sessionId).size)
                    }
                }
            }
            // 事件明细只在点名要的时候给，且同样受 `wanted` 过滤。
            if (wanted.isNotEmpty()) {
                putJsonArray("events") {
                    rows.forEach { s ->
                        sessions.events(s.nodeId, s.sessionId).forEach { e ->
                            addJsonObject {
                                put("nodeId", e.nodeId)
                                put("sessionId", e.sessionId)
                                put("cursor", e.cursor)
                                put("eventType", e.eventType)
                                put("summary", e.summary)
                                put("body", e.body)
                                put("ref", e.ref)
                                put("at", e.at)
                            }
                        }
                    }
                }
                // P1 的五个 agq1 类型 + §6.7 的产物：同样只在点名某个会话时给明细。
                // 面板点进一个会话要看的就是「它排了什么、跑了什么、在等谁同意、被谁取消、
                // 产出了什么」，这五张表合起来才是 §6 的完整语义。
                putJsonArray("queues") {
                    rows.forEach { s ->
                        // 队列是会话级的，但"待分配"那一档挂在空串键下（§6.3 的
                        // `sessionId` 可为空），所以这里按 nodeId 取全部再筛。
                        sessions.queues(s.nodeId).filter { it.sessionId == null || it.sessionId == wanted }
                            .forEach { q ->
                                addJsonObject {
                                    put("nodeId", q.nodeId)
                                    put("taskId", q.taskId)
                                    put("sessionId", q.sessionId)
                                    put("op", q.op)
                                    put("prompt", q.prompt)
                                    put("queuedAt", q.queuedAt)
                                    put("at", q.at)
                                    put("outbound", q.outbound)
                                }
                            }
                    }
                }
                putJsonArray("runs") {
                    rows.forEach { s ->
                        sessions.runs(s.nodeId, s.sessionId).forEach { r ->
                            addJsonObject {
                                put("nodeId", r.nodeId)
                                put("sessionId", r.sessionId)
                                put("runId", r.runId)
                                put("state", r.state)
                                put("at", r.at)
                                put("note", r.note)
                                put("updatedAt", r.updatedAt)
                                put("outbound", r.outbound)
                            }
                        }
                    }
                }
                putJsonArray("approvals") {
                    rows.forEach { s ->
                        sessions.approvals(s.nodeId, s.sessionId).forEach { a ->
                            addJsonObject {
                                put("nodeId", a.nodeId)
                                put("sessionId", a.sessionId)
                                put("approvalId", a.approvalId)
                                put("tool", a.tool)
                                put("summary", a.summary)
                                put("expiresAt", a.expiresAt)
                                put("choices", a.choices)
                                // `status` 为 null = 还没处置（≠ 被拒）。§10 不许把
                                // "没有回执" 显示成任何结论，所以这个 null 必须原样传出去。
                                put("status", a.status)
                                put("updatedAt", a.updatedAt)
                                put("outbound", a.outbound)
                            }
                        }
                    }
                }
                putJsonArray("cancels") {
                    rows.forEach { s ->
                        sessions.cancels(s.nodeId, s.sessionId).forEach { c ->
                            addJsonObject {
                                put("nodeId", c.nodeId)
                                put("sessionId", c.sessionId)
                                put("runId", c.runId)
                                put("reason", c.reason)
                                put("at", c.at)
                                put("outbound", c.outbound)
                            }
                        }
                    }
                }
                putJsonArray("artifacts") {
                    rows.forEach { s ->
                        sessions.artifacts(s.nodeId, s.sessionId).forEach { a ->
                            addJsonObject {
                                put("nodeId", a.nodeId)
                                put("sessionId", a.sessionId)
                                put("artifactId", a.artifactId)
                                put("fileId", a.fileId)
                                put("name", a.name)
                                put("bytes", a.bytes)
                                put("mime", a.mime)
                                put("at", a.at)
                                put("outbound", a.outbound)
                            }
                        }
                    }
                }
            }
            put("total", rows.size)
        },
    )
}
