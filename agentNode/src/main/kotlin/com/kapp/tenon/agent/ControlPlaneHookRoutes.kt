// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.AgentSessionStates
import com.kapp.tenon.core.protocol.Limits
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * hook 桥（外部 agent 工具的会话 / 审批 / 注入 / 卡片 / 派单）。
 *
 * Antigravity（App/IDE/agy）经 `antigravity-tidings-hook` 脚本（stdin/stdout JSON）打到
 * 这几条回环路由：SessionStart/Stop 宣告会话（手机看板可见）、PreToolUse 走免批表 + 手机
 * 审批（**fail-closed**：联系不上或超时 = deny）、PreInvocation 拉走手机端注入池里的待注入
 * 消息（RFC §4 的 injectSteps 契约）。
 *
 * 注入池按 hook 会话键（`agy-<conversationId>`）归集；feed 方（桌面壳 / 手机侧桥接）可按
 * conversationId 或 wsPath 投递。池与别名都是**进程内**状态（不进任何协议帧），卡片游标按
 * 会话递增 —— 同一会话多张卡按序推进，面板才排得对。
 */

private fun ControlPlane.hookKeyOf(conversationId: String): String = "agy-" + conversationId.trim()

/**
 * hook 审批的收件人对端：显式 to 优先；缺省走 [pickHookPeer]（先折叠同端点旧记录，再取确定性）。
 */
private fun ControlPlane.resolveHookPeer(exchange: HttpExchange, obj: JsonObject): String? {
    val explicit = obj["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit
    val picked = pickHookPeer(runtime.peerList())
    if (picked == null) {
        respond(exchange, 503, errorJson("no-phone", "没有在线且声明 agq1 的对端可收审批 —— 手机不在线时 hook 按超时拒绝"))
    }
    return picked
}

/**
 * `POST /v1/agent/hook/session` —— SessionStart / Stop 宣告。
 * 本机台账 + 送达给 hook 收件人对端（手机看板「正在运行」的来源）。
 */
internal fun ControlPlane.agentHookSession(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val conversationId = obj["conversationId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (conversationId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 conversationId"))
        return
    }
    val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: "start"
    if (event !in setOf("start", "stop")) {
        respond(exchange, 400, errorJson("bad-request", "event 只认 start / stop"))
        return
    }
    val wsPaths = (obj["wsPaths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val sessionId = hookKeyOf(conversationId)
    val state = if (event == "start") AgentSessionStates.BUSY else AgentSessionStates.DONE
    val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Antigravity 会话"
    sessions.recordSession(
        nodeId = runtime.nodeId,
        sessionId = sessionId,
        agentKind = "antigravity",
        agentId = null,
        state = state,
        title = title,
        wsPath = wsPaths.firstOrNull(),
        outbound = true,
    )
    if (event == "start" && wsPaths.isNotEmpty()) hookWsAlias[wsPaths.first()] = sessionId
    if (event == "stop") {
        hookWsAlias.entries.removeIf { it.value == sessionId }
        hookInjectPool.remove(sessionId)
    }
    // 宣告给对端（手机看板）：失败只记日志 —— 会话可见性是尽力而为，不是审批安全面。
    var announcedTo: String? = null
    val peer = resolveHookPeer(exchange, obj)
    if (peer != null) {
        runCatching {
            CarrierHold.hold("hook.sessionAnnounce", CarrierHold.SEND_MS) {
                runtime.sendAgentSession(
                    peerId = peer, sessionId = sessionId, agentKind = "antigravity", state = state,
                    title = title, wsPath = wsPaths.firstOrNull(), updatedAt = System.currentTimeMillis(),
                )
            }
        }.onSuccess { announcedTo = peer }
            .onFailure { println("[hook] session announce failed: " + it.message) }
    }
    respond(exchange, 200, buildJsonObject {
        put("sessionId", sessionId)
        put("state", state)
        put("announcedTo", announcedTo?.let { JsonPrimitive(it) } ?: JsonNull)
    })
}

/**
 * `POST /v1/agent/hook/approval` —— PreToolUse 审批（**长轮询**）。
 * 顺序：免批表短路（不打扰）→ 手机审批卡 → 阻塞等 ack → 决定。
 * fail-closed：没有可收审批的对端 / 送达失败 / 超时，一律 `deny`。
 */
internal fun ControlPlane.agentHookApproval(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val tool = obj["tool"]?.jsonPrimitive?.contentOrNull
    val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
    val conversationId = obj["conversationId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val riskLevel = obj["riskLevel"]?.jsonPrimitive?.contentOrNull
    val diff = obj["diff"]?.jsonPrimitive?.contentOrNull
    val timeoutMs = (numberOrNull(obj["timeoutMs"]) ?: Limits.APPROVAL_TTL_MS.toDouble()).toLong().coerceIn(1_000, 180_000)
    val peer = resolveHookPeer(exchange, obj) ?: return
    val agentKey = "antigravity"
    if (tool != null && toolApprovals.isAlwaysApproved(nodeId = peer, agentKey = agentKey, tool = tool)) {
        respond(exchange, 200, buildJsonObject {
            put("decision", "allow")
            put("reason", "matched an always-allow rule for tool=$tool")
        })
        return
    }
    val sessionId = if (conversationId.isEmpty()) "agy-approval" else hookKeyOf(conversationId)
    val approvalId = "hook-" + java.util.UUID.randomUUID()
    val expiresAt = System.currentTimeMillis() + timeoutMs
    val choices = defaultApprovalChoices
    val delivered = runCatching {
        CarrierHold.hold("hook.approvalSend", CarrierHold.SEND_MS) {
            runtime.sendAgentApproval(peer, approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff)
        }
    }
    if (delivered.isFailure) {
        sessions.recordApproval(peer, sessionId, approvalId, tool, summary, expiresAt, choices,
            status = "denied", outbound = true)
        respond(exchange, 200, buildJsonObject {
            put("decision", "deny")
            put("reason", "approval delivery failed: " + (delivered.exceptionOrNull()?.message ?: "unknown"))
        })
        return
    }
    sessions.recordApproval(peer, sessionId, approvalId, tool, summary, expiresAt, choices,
        status = "sent", outbound = true)
    // 长轮询改「决定落账信号」唤醒（T-G4）：recordApproval 推进 status 时触发
    // 监听 —— 不再 200ms 盲睡占线程。超时 = deny（fail-closed 语义不变）。
    val signal = kotlinx.coroutines.CompletableDeferred<String>()
    val disposeListener = sessions.addApprovalListener { id, status ->
        if (id == approvalId) signal.complete(status)
    }
    val finalStatus = try {
        // 这是**按设计**最长的一次按：长轮询等手机/桌面审批，等于 timeoutMs（上限 180 s）。
        // 预算照 timeoutMs 给 —— 在它之内按住是契约，不是缺陷。真正的超时由内层
        // withTimeoutOrNull 收场（超时 = deny，fail-closed 语义不变）。
        CarrierHold.hold("hook.approvalLongPoll", budgetMs = timeoutMs) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { signal.await() }
        }
    } finally {
        disposeListener()
    }
    val allow = finalStatus in setOf("approved", "allow_session")
    respond(exchange, 200, buildJsonObject {
        put("decision", if (allow) "allow" else "deny")
        put("reason", finalStatus ?: "approval timeout — denied fail-closed")
        put("approvalId", approvalId)
    })
}

/** `POST /v1/agent/hook/inject` —— PreInvocation 拉取并清空注入池（RFC §4 契约原样返回）。 */
internal fun ControlPlane.agentHookInject(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val conversationId = obj["conversationId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (conversationId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 conversationId"))
        return
    }
    val key = hookKeyOf(conversationId)
    val messages = synchronized(hookInjectPool) { hookInjectPool[key]?.toList().orEmpty() }
    if (messages.isNotEmpty()) {
        synchronized(hookInjectPool) { hookInjectPool[key]?.clear() }
    }
    respond(exchange, 200, buildJsonObject {
        put("decision", "continue")
        put("injectSteps", buildJsonArray {
            messages.forEach { m ->
                add(buildJsonObject { put("userMessage", JsonPrimitive(m)) })
            }
        })
    })
}

/** `POST /v1/agent/hook/inject-feed` —— 手机侧桥接投递（按 conversationId 或 wsPath 寻池）。 */
internal fun ControlPlane.agentHookInjectFeed(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val message = obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    if (message == null) {
        respond(exchange, 400, errorJson("bad-request", "缺少 message（要注入的正文）"))
        return
    }
    val key = obj["conversationId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { hookKeyOf(it) }
        ?: obj["wsPath"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { hookWsAlias[it] }
    if (key == null) {
        respond(exchange, 404, errorJson("no-session", "没有活跃的 hook 会话对得上这次投递（先 SessionStart 宣告）"))
        return
    }
    val queued = synchronized(hookInjectPool) {
        val list = hookInjectPool.getOrPut(key) { mutableListOf() }
        list.add(message)
        while (list.size > 50) list.removeAt(0)
        list.size
    }
    respond(exchange, 202, buildJsonObject { put("queued", queued); put("key", key) })
}

/**
 * 合流动作（T-G1）的本机事实查询：某会话最近一次沙箱 patch 的 (工作区路径, patch 路径)。
 * 两条都齐才返回 —— 合流要同时知道「patch 在哪」与「应用到哪个仓」。
 * 路径是本机事实，只经进程内返回，不进任何协议帧。
 */
fun ControlPlane.latestPatchRefFor(sessionId: String): Pair<String, String>? {
    val nodeId = runtime.nodeId
    val wsPath = sessions.sessionOrNull(nodeId, sessionId)?.wsPath?.takeIf { it.isNotBlank() } ?: return null
    val patchPath = sessions.events(nodeId, sessionId)
        .lastOrNull { it.eventType == "artifact" && it.body?.contains("path=") == true }
        ?.body
        ?.split("path=")?.lastOrNull()?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return wsPath to patchPath
}

/**
 * 同 [latestPatchRefFor]，但按**卡片**找回 —— 动作帧只带 cardId，从来不携带会话。
 *
 * 这不是「顺手多写一个重载」：`onHostCardAction` 那一处签名里的 `sessionId` 在
 * 现有链路上**恒为 null**（动作帧由 `groupId/cardId/rev/actionId` 组成，协议里没有
 * 会话位），于是按会话合流的判据永远不成立，点「合并此补丁」只会改卡面状态、
 * 宿主仓零提交 —— 真机实测就是这样（点「合并此补丁」只改了卡面状态）。
 *
 * 归属本来就落在台账里：派单出卡时写了一条 `card` 事件，body 就是卡片 JSON。
 * 按它反查会话最稳，也不引入协议键：同一张卡只可能由一个会话发出。
 */
fun ControlPlane.latestPatchRefForCard(cardId: String): SandboxPatchRef? {
    if (cardId.isBlank()) return null
    val nodeId = runtime.nodeId
    // 先按 cardId 找会话（最近的排前面），再用它复用会话那条路径 —— 两条查询的
    // 「哪条 artifact 才算这次的 patch」判据只写一遍。
    val owner = sessions.sessions()
        .filter { it.nodeId == nodeId }
        .sortedByDescending { it.updatedAt }
        .firstOrNull { rec ->
            sessions.events(nodeId, rec.sessionId)
                .any { it.eventType == "card" && it.body?.contains(cardId) == true }
        }
        ?: return null
    val (wsPath, patchPath) = latestPatchRefFor(owner.sessionId) ?: return null
    return SandboxPatchRef(owner.sessionId, wsPath, patchPath)
}

/**
 * `POST /v1/agent/hook/card` —— MCP `tenon_send_group_card` 的宿主落点：
 * 卡片 JSON 落 hook 会话的台账（type=card，与插件卡片同一渲染管线），随
 * 会话分页到手机。**归属即会话**：没有活跃 hook 会话 = 404，不替它造一个
 * （卡片挂错会话比发不出去更难排查）。
 */
internal fun ControlPlane.agentHookCard(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val conversationId = obj["conversationId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val cardJson = obj["card"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    if (conversationId.isEmpty() || cardJson == null) {
        respond(exchange, 400, errorJson("bad-request", "conversationId 与 card 都必填"))
        return
    }
    val key = hookKeyOf(conversationId)
    if (sessions.sessionOrNull(runtime.nodeId, key) == null) {
        respond(exchange, 404, errorJson("no-session", "没有宣告中的 hook 会话 " + key + "（先 SessionStart）"))
        return
    }
    // 卡片预算：与 §6.12 同一把尺子 —— 超限拒绝不截断（发送侧不产出注定被拒的帧）。
    val bytes = cardJson.toByteArray(Charsets.UTF_8).size
    if (bytes > com.kapp.tenon.core.protocol.Limits.CARD_BYTES) {
        respond(exchange, 400, errorJson("card-too-large", "card " + bytes + " B 超过预算 " + com.kapp.tenon.core.protocol.Limits.CARD_BYTES + " B（拒绝不截断）"))
        return
    }
    val cursor = hookCardCursors.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong(0) }
    sessions.recordEvent(
        nodeId = runtime.nodeId,
        sessionId = key,
        cursor = cursor.incrementAndGet(),
        eventType = "card",
        summary = null,
        body = cardJson,
    )
    respond(exchange, 200, buildJsonObject { put("recorded", true); put("cursor", cursor.get()) })
}

/**
 * `POST /v1/agent/hook/dispatch` —— E2E 指挥入口（T-G 装置）：直驱 [executeCliTask]
 * （agentKey 别名解析 → CLI 驱动 → 沙箱）。绕过群座席（席位由 UI/工作区宣告形成，
 * 脚本场景没有 UI）—— 台账/实时流/产物回调与群路径**同一套**。
 */
internal fun ControlPlane.agentHookDispatch(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val agentKey = obj["agentKey"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val wsPath = obj["wsPath"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    if (agentKey.isEmpty() || prompt == null) {
        respond(exchange, 400, errorJson("bad-request", "agentKey 与 prompt 都必填"))
        return
    }
    if (resolveCliDriver(agentKey) == null) {
        respond(exchange, 404, errorJson("no-driver", "没有匹配 agentKey 的 CLI 驱动（装了 agy/claude/codex 才有）"))
        return
    }
    respond(exchange, 202, buildJsonObject { put("accepted", true) })
    // 执行异步（HTTP 线程不被沙箱任务占住）；结果走台账与 liveAgentEvents。
    dispatchScope.launch {
        val (sessionId, status, error) = executeCliTask(agentKey, prompt, wsPath, null) { _, _, _, _ -> }
        println("[e2e] dispatch done: agentKey=" + agentKey + " sessionId=" + sessionId + " status=" + status + (error?.let { " error=" + it } ?: ""))
    }
}

/**
 * `POST /v1/agent/hook/question` —— `ask_question` 多选决策（长轮询）。
 *
 * 构造标准 `type: "ask_question"` 卡片，广播至协同群（或落进会话台账）。
 * 机主在手机或桌面点击选项，推进 [AgentSessionRegistry.recordCardAction]，
 * 本路由等待信号唤醒返回用户选定的文本；超时 = timeout（fail-closed 语义）。
 */
internal fun ControlPlane.agentHookQuestion(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val question = obj["question"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
    val optionsRaw = obj["options"] as? JsonArray
    val conversationId = obj["conversationId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val groupId = obj["groupId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    val agentKey = obj["agentKey"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: "antigravity"
    val timeoutMs = (numberOrNull(obj["timeoutMs"]) ?: Limits.APPROVAL_TTL_MS.toDouble()).toLong().coerceIn(1_000, 300_000)

    if (question.isNullOrEmpty() || optionsRaw.isNullOrEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "question 与 options 都必填"))
        return
    }

    val cardId = "question-" + java.util.UUID.randomUUID().toString().take(12)
    val optionsArray = buildJsonArray {
        optionsRaw.forEach { opt ->
            when (opt) {
                is JsonPrimitive -> add(buildJsonObject { put("label", opt.content) })
                is JsonObject -> {
                    val label = opt["label"]?.jsonPrimitive?.contentOrNull
                    if (!label.isNullOrBlank()) {
                        add(buildJsonObject {
                            put("label", label)
                            if (opt["recommended"]?.jsonPrimitive?.contentOrNull == "true") {
                                put("recommended", true)
                            }
                        })
                    }
                }
                else -> {}
            }
        }
    }
    if (optionsArray.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "options 列表中至少需要一个有效选项"))
        return
    }

    val cardJson = buildJsonObject {
        put("type", "ask_question")
        put("cardId", cardId)
        put("rev", 1)
        put("title", question)
        put("options", optionsArray)
    }.toString()

    val bytes = cardJson.toByteArray(Charsets.UTF_8).size
    if (bytes > Limits.CARD_BYTES) {
        respond(exchange, 400, errorJson("card-too-large", "card 超过预算 " + Limits.CARD_BYTES + " B"))
        return
    }

    // 1. 群广播（如提供了 groupId）
    if (groupId != null) {
        groupCardPublisher?.invoke(groupId, agentKey, cardJson)
    }

    // 2. 会话台账记录（如提供了 conversationId 且会话已宣告）
    if (conversationId.isNotEmpty()) {
        val key = hookKeyOf(conversationId)
        if (sessions.sessionOrNull(runtime.nodeId, key) != null) {
            val cursor = hookCardCursors.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong(0) }
            sessions.recordEvent(
                nodeId = runtime.nodeId,
                sessionId = key,
                cursor = cursor.incrementAndGet(),
                eventType = "card",
                summary = question,
                body = cardJson,
            )
        }
    }

    // 3. 挂起等待点选
    val signal = kotlinx.coroutines.CompletableDeferred<Pair<String, String>>()
    val dispose = sessions.addCardActionListener { id, actionId, decidedBy ->
        if (id == cardId) signal.complete(actionId to decidedBy)
    }
    val answerPair = try {
        CarrierHold.hold("hook.questionLongPoll", budgetMs = timeoutMs) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { signal.await() }
        }
    } finally {
        dispose()
    }

    if (answerPair != null) {
        respond(exchange, 200, buildJsonObject {
            put("status", "answered")
            put("decision", "allow")
            put("answer", answerPair.first)
            put("decidedBy", answerPair.second)
            put("cardId", cardId)
        })
    } else {
        respond(exchange, 200, buildJsonObject {
            put("status", "timeout")
            put("decision", "deny")
            put("reason", "question timeout after " + timeoutMs + "ms")
            put("cardId", cardId)
        })
    }
}
