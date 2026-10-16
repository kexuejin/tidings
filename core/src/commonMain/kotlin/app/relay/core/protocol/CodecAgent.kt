// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.relay.core.protocol.CodecWire.boolOf
import app.relay.core.protocol.CodecWire.hasOnlyKeys
import app.relay.core.protocol.CodecWire.intOf
import app.relay.core.protocol.CodecWire.isStr
import app.relay.core.protocol.CodecWire.isStrAllowEmpty
import app.relay.core.protocol.CodecWire.tolerantKeys
import app.relay.core.protocol.CodecWire.utf8ByteLength
import app.relay.core.protocol.CodecWire.wireJson

/**
 * agent-* 一族的字段白名单、校验与 payload 构造器。
 *
 * 十二种帧：§6.1~§6.8 的八种（session / event / queue / run / approval / cancel /
 * ack / artifact），加 PPE-1 增补的四种（D3 `agent-workspace`、D7 三张 grant 帧）。
 *
 * 从 `Codec.kt` 按「变化原因」切出：这一族的演化动力是 PPE-1 协议本身在长。
 * **每个 `AGENT_*_EXPECTED_KEYS` 必须与它对应的 `validate*` 待在同一个文件** ——
 * 白名单与校验器一旦分家就会漂移，症状是某个新字段被静默丢掉，或旧字段收不下来，
 * 而且只在真机上暴露。
 */
object CodecAgent {

    /**
     * PPE-1 §5 的 agent-* 字段白名单。字段顺序与 `Payloads.kt` 的声明顺序一致（也是线上键序）。
     *
     * 自 D2 起 `agent-session` 的语义是「必需子集 + 忽略未知键（带预算）」，见
     * [validateAgentSession] 与 `CodecWire.tolerantKeys`；其余 `agent-*` 仍按严格白名单
     * （`hasOnlyKeys`）整帧比对。
     */
    internal val AGENT_SESSION_EXPECTED_KEYS = setOf(
        "sessionId", "alias", "agentKind", "state", "title", "startedAt",
        "agentId", "wsId", "wsTitle", "wsPath", "updatedAt",
        // 可见性两键（与 DSH 的 sessionVisible() 同口径）：追加在尾部。
        "origin", "blank",
    )

    private val AGENT_EVENT_EXPECTED_KEYS = setOf(
        "sessionId", "cursor", "eventType", "summary", "body", "ref",
    )

    private val AGENT_QUEUE_EXPECTED_KEYS = setOf("taskId", "sessionId", "op", "prompt", "queuedAt")
    private val AGENT_RUN_EXPECTED_KEYS = setOf("sessionId", "runId", "state", "at", "note")
    private val AGENT_APPROVAL_EXPECTED_KEYS = setOf("approvalId", "sessionId", "tool", "summary", "expiresAt", "choices")
    private val AGENT_CANCEL_EXPECTED_KEYS = setOf("sessionId", "runId", "reason")
    private val AGENT_ACK_EXPECTED_KEYS = setOf("ackOf", "kind", "at")

    // PPE-1 增补 D3 / D7。键序 = `Payloads.kt` 声明序 = 线上键序。
    internal val AGENT_WORKSPACE_EXPECTED_KEYS =
        setOf("wsId", "wsTitle", "wsPath", "agentId", "agentKind", "online", "sessionCount", "at", "order")
    internal val AGENT_GRANT_REQUEST_EXPECTED_KEYS = setOf("agentKey", "reason", "at")
    internal val AGENT_GRANT_EXPECTED_KEYS = setOf("state", "scope", "agentKey", "at")
    internal val AGENT_GRANT_LIST_EXPECTED_KEYS = setOf("scope", "agentKey", "total", "at")
    internal val AGENT_SESSION_PAGE_EXPECTED_KEYS = setOf("wsId", "offset", "limit", "at")
    internal val AGENT_ARTIFACT_EXPECTED_KEYS = setOf("sessionId", "artifactId", "fileId", "name", "bytes", "mime")

    // --------------------------------------------------------------- agent-* 校验

    /**
     * PPE-1 §6.1 `agent-session`。
     *
     * `state` / `agentKind` 的**取值故意不封闭**：§5 的严格性针对「字段集合」，
     * 取值域留宽容 —— 对端将来多一个 `state` 取值时，拒收整帧会让它一直重传到入队，
     * 而 `title` / `summary` 这类人话字段本来就够 UI 显示。
     */
    internal fun validateAgentSession(o: JsonObject): Boolean {
        if (!isStr(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        if (o.containsKey("alias") && !isStrAllowEmpty(o["alias"], Limits.AGENT_ALIAS)) return false
        if (o.containsKey("agentKind") && !isStr(o["agentKind"], Limits.AGENT_KIND)) return false
        if (o.containsKey("state") && !isStr(o["state"], Limits.AGENT_STATE)) return false
        if (o.containsKey("title") && !isStrAllowEmpty(o["title"], Limits.AGENT_TITLE)) return false
        if (o.containsKey("startedAt") && intOf(o["startedAt"]) == null) return false
        // PPE-1 增补 D1/D6：四个新可选键，逐个按界校验。
        if (o.containsKey("agentId") && !isStr(o["agentId"], Limits.AGENT_ID)) return false
        if (o.containsKey("wsId") && !isStr(o["wsId"], Limits.AGENT_WS_ID)) return false
        if (o.containsKey("wsTitle") && !isStrAllowEmpty(o["wsTitle"], Limits.AGENT_WS_TITLE)) return false
        if (o.containsKey("wsPath") && !isStr(o["wsPath"], Limits.AGENT_WS_PATH)) return false
        // 宿主侧活动时刻（见 `AgentSessionPayload.updatedAt` 的 KDoc）。与 `startedAt` 同形校验：
        // 必须是数字。**不检查它的大小** —— 那是对端时钟，我们无权判对错，能且只能拒绝
        // 「给了这个键却不是数字」这种坏载荷。
        if (o.containsKey("updatedAt") && intOf(o["updatedAt"]) == null) return false
        // 可见性两键（见 `AgentSessionPayload.origin` / `.blank` 的 KDoc）。`origin` 的取值
        // 不封闭，所以只按字符串界校验；`blank` 必须是布尔 —— 字符串 "true" 不算，
        // 那是对端把布尔写成了字符串，正是我们要在线上拦下来的形状。
        if (o.containsKey("origin") && !isStr(o["origin"], Limits.AGENT_KIND)) return false
        if (o.containsKey("blank") && boolOf(o["blank"]) == null) return false
        // 父稿 §2.1 的归组纪律：`wsId` 与 `wsTitle` 双缺时不得只发 `wsPath`
        // （无 id 无法归组）—— 接收端**忽略该键并计数**而不是丢帧。这里照做：
        // 忽略 = 从校验视图里去掉它；计数在台账侧（recordInbound / 控制面各自透传）。
        val dropBarePath = o.containsKey("wsPath") && !o.containsKey("wsId") && !o.containsKey("wsTitle")
        val known = if (dropBarePath) AGENT_SESSION_EXPECTED_KEYS - "wsPath" else AGENT_SESSION_EXPECTED_KEYS
        // PPE-1 增补 D2：必需子集 + 忽略未知键（预算内）。
        return tolerantKeys(o, required = setOf("sessionId"), known = known)
    }

    /**
     * PPE-1 §6.2 `agent-event`。
     *
     * `body` 与 `text` 同规矩：上限按 **UTF-8 字节**算、按通道取值（UDP 800 / TCP 4096），
     * 所以同一个事件走 TCP 收得下、走 UDP 会被拒 —— 这就是 §7 的分通道预算。
     * `eventType` 取值同样不封闭，理由见 [validateAgentSession]。
     */
    internal fun validateAgentEvent(o: JsonObject, textLimit: Int): Boolean {
        if (!isStr(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        val cursor = intOf(o["cursor"]) ?: return false
        if (cursor < 0) return false
        if (!isStr(o["eventType"], Limits.AGENT_EVENT_TYPE)) return false
        if (o.containsKey("summary") && !isStrAllowEmpty(o["summary"], Limits.AGENT_SUMMARY)) return false
        if (o.containsKey("body")) {
            val body = (o["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            if (utf8ByteLength(body) > textLimit) return false
        }
        if (o.containsKey("ref") && !isStr(o["ref"], Limits.AGENT_REF)) return false
        return hasOnlyKeys(o, AGENT_EVENT_EXPECTED_KEYS)
    }

    internal fun validateAgentQueue(o: JsonObject): Boolean {
        if (!isStr(o["taskId"], Limits.AGENT_TASK_ID)) return false
        if (o.containsKey("sessionId") && !isStrAllowEmpty(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (op !in setOf("enqueue", "dequeue")) return false
        if (o.containsKey("prompt") && !isStrAllowEmpty(o["prompt"], Limits.AGENT_PROMPT)) return false
        if (o.containsKey("queuedAt") && intOf(o["queuedAt"]) == null) return false
        return hasOnlyKeys(o, AGENT_QUEUE_EXPECTED_KEYS)
    }

    internal fun validateAgentRun(o: JsonObject): Boolean {
        if (!isStr(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        if (!isStr(o["runId"], Limits.AGENT_RUN_ID)) return false
        val state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (state !in setOf("running", "succeeded", "failed", "cancelled")) return false
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        if (o.containsKey("note") && !isStrAllowEmpty(o["note"], Limits.AGENT_NOTE)) return false
        return hasOnlyKeys(o, AGENT_RUN_EXPECTED_KEYS)
    }

    internal fun validateAgentApproval(o: JsonObject): Boolean {
        if (!isStr(o["approvalId"], Limits.AGENT_APPROVAL_ID)) return false
        if (!isStr(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        if (o.containsKey("tool") && !isStrAllowEmpty(o["tool"], Limits.AGENT_TOOL)) return false
        if (o.containsKey("summary") && !isStrAllowEmpty(o["summary"], Limits.AGENT_SUMMARY)) return false
        val expiresAt = intOf(o["expiresAt"]) ?: return false
        if (expiresAt < 0) return false
        if (o.containsKey("choices") && !isStrAllowEmpty(o["choices"], Limits.AGENT_CHOICES)) return false
        return hasOnlyKeys(o, AGENT_APPROVAL_EXPECTED_KEYS)
    }

    internal fun validateAgentCancel(o: JsonObject): Boolean {
        if (!isStr(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        if (o.containsKey("runId") && !isStr(o["runId"], Limits.AGENT_RUN_ID)) return false
        if (o.containsKey("reason") && !isStrAllowEmpty(o["reason"], Limits.AGENT_NOTE)) return false
        return hasOnlyKeys(o, AGENT_CANCEL_EXPECTED_KEYS)
    }

    internal fun validateAgentAck(o: JsonObject): Boolean {
        if (!isStr(o["ackOf"], Limits.ID)) return false
        val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (kind !in setOf("queued", "approved", "denied", "cancelled", "received")) return false
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        return hasOnlyKeys(o, AGENT_ACK_EXPECTED_KEYS)
    }

    /**
     * §6.7 `agent-artifact` 校验（cap `agn1`）。
     *
     * `fileId` 只在**真的有一条 `file-msg`** 时才出现，所以它是可空可选；`bytes` 同理
     * （产物大小由发送方决定要不要报）。`name` 必填 —— 一个没有名字的产物在界面上没法显示。
     */
    /**
     * PPE-1 增补 D3 `agent-workspace`（cap `aws1`）。
     *
     * 严格白名单（与 `agent-session` 的 D2 宽容不同）：这帧今天刚落地，没有"旧对端
     * 多写键"的历史包袱，等它需要再加键时再逐帧切换。取值域照旧开放
     * （`agentKind` 是开放集合）；`online` 必须是布尔、`sessionCount` 必须非负整数
     * —— 这两个是"宿主自报的事实"，形状错了就是假数据，宁可拒帧。
     */
    internal fun validateAgentWorkspace(o: JsonObject): Boolean {
        if (!isStr(o["wsId"], Limits.AGENT_WS_ID)) return false
        if (o.containsKey("wsTitle") && !isStrAllowEmpty(o["wsTitle"], Limits.AGENT_WS_TITLE)) return false
        if (o.containsKey("wsPath") && !isStr(o["wsPath"], Limits.AGENT_WS_PATH)) return false
        // D6 的 agentId：与会话帧同界。归组键要靠它对齐（否则同一工作区会被拆成两行）。
        if (o.containsKey("agentId") && !isStr(o["agentId"], Limits.AGENT_ID)) return false
        if (o.containsKey("agentKind") && !isStr(o["agentKind"], Limits.AGENT_KIND)) return false
        if (o.containsKey("online")) {
            // JsonPrimitive 没有 isBoolean：非字符串 + 内容是 true/false 才算布尔字面量。
            val p = o["online"] as? JsonPrimitive ?: return false
            if (p.isString || (p.content != "true" && p.content != "false")) return false
        }
        if (o.containsKey("sessionCount")) {
            val n = intOf(o["sessionCount"]) ?: return false
            if (n < 0) return false
        }
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        // 注册表顺序（见 AgentWorkspacePayload.order 的 KDoc）。与 sessionCount 同规矩：
        // 这是宿主自报的事实，取值为负就是假数据，宁可拒帧也不要在手机上排出个负序。
        if (o.containsKey("order")) {
            val n = intOf(o["order"]) ?: return false
            if (n < 0) return false
        }
        return hasOnlyKeys(o, AGENT_WORKSPACE_EXPECTED_KEYS)
    }

    /**
     * PPE-1 增补 D7 三帧（cap `agr1`）。
     *
     * `state` / `scope` 是**封闭取值**（与 `agent-session.state` 的开放域相反）：
     * 它们是"有人按了按钮"的结论，拼错一个等于语义不存在，codec 直接拒收。
     */
    internal fun validateAgentGrantRequest(o: JsonObject): Boolean {
        if (o.containsKey("agentKey") && !isStr(o["agentKey"], Limits.AGENT_GRANT_AGENT_KEY)) return false
        if (o.containsKey("reason") && !isStrAllowEmpty(o["reason"], Limits.AGENT_GRANT_REASON)) return false
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        return hasOnlyKeys(o, AGENT_GRANT_REQUEST_EXPECTED_KEYS)
    }

    internal fun validateAgentGrant(o: JsonObject): Boolean {
        val state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (state !in AgentGrantStates.ALL) return false
        val scope = (o["scope"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (scope !in AgentGrantScopes.VALUES) return false
        if (o.containsKey("agentKey") && !isStr(o["agentKey"], Limits.AGENT_GRANT_AGENT_KEY)) return false
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        return hasOnlyKeys(o, AGENT_GRANT_EXPECTED_KEYS)
    }

    internal fun validateAgentGrantList(o: JsonObject): Boolean {
        val scope = (o["scope"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (scope !in AgentGrantScopes.VALUES) return false
        if (o.containsKey("agentKey") && !isStr(o["agentKey"], Limits.AGENT_GRANT_AGENT_KEY)) return false
        if (o.containsKey("total")) {
            val n = intOf(o["total"]) ?: return false
            if (n < 0) return false
        }
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        return hasOnlyKeys(o, AGENT_GRANT_LIST_EXPECTED_KEYS)
    }

    /**
     * PPE-1 增补 D8 `agent-session-page`（cap `agp1`）。
     *
     * `wsId` 必填且非空：这一帧的语义就是「给我这个工作区的会话」，没有"全部"这个档。
     * `offset` / `limit` 是**非负整数**（形状卡死，取值不封闭 —— 宿主自定上限，
     * 与 `agent-session` 的 `state` 同一条纪律：严字段集合、宽取值域）。
     */
    internal fun validateAgentSessionPage(o: JsonObject): Boolean {
        if (!isStr(o["wsId"], Limits.AGENT_WS_ID)) return false
        if (o.containsKey("offset")) {
            val v = intOf(o["offset"]) ?: return false
            if (v < 0) return false
        }
        if (o.containsKey("limit")) {
            val v = intOf(o["limit"]) ?: return false
            if (v < 0) return false
        }
        if (o.containsKey("at") && intOf(o["at"]) == null) return false
        return hasOnlyKeys(o, AGENT_SESSION_PAGE_EXPECTED_KEYS)
    }


    internal fun validateAgentArtifact(o: JsonObject): Boolean {
        if (!isStr(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        if (!isStr(o["artifactId"], Limits.AGENT_ARTIFACT_ID)) return false
        if (!isStr(o["name"], Limits.AGENT_ARTIFACT_NAME)) return false
        if (o.containsKey("fileId") && !isStr(o["fileId"], Limits.ID)) return false
        if (o.containsKey("bytes")) {
            val bytes = intOf(o["bytes"]) ?: return false
            if (bytes < 0) return false
        }
        if (o.containsKey("mime") && !isStrAllowEmpty(o["mime"], Limits.AGENT_ARTIFACT_MIME)) return false
        return hasOnlyKeys(o, AGENT_ARTIFACT_EXPECTED_KEYS)
    }

    /**
     * PPE-1 §6.1：宣告一个 agent 会话。
     *
     * 这里只管形状，**不查对端 caps** —— §4 的「对方没声明 `agn1` 就不许发」是应用层的判断
     * （只有它知道对端 profile）。发给不认识 `agent-session` 的老节点会被静默丢弃，
     * 而本端因为拿不到 ACK 会一直重传并最终入队，那就是一条假的「对方离线」。
     */
    @Suppress("LongParameterList")
    fun agentSessionPayload(
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
        updatedAt: Long? = null,
        origin: String? = null,
        blank: Boolean? = null,
        gitBranch: String? = null,
        segmentId: String? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentSessionPayload.serializer(),
        AgentSessionPayload(
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
            updatedAt = updatedAt,
            origin = origin,
            blank = blank,
            gitBranch = gitBranch,
            segmentId = segmentId,
        ),
    )

    /**
     * PPE-1 §6.2：一条会话事件。
     *
     * `body` 超出通道预算时调用方**必须**改走 `file-msg`（§6.2），这里不做通道选择 ——
     * 通道由发送层按 `utf8ByteLength(Codec.encode(env))` 与 `TEXT_UDP_LIMIT` 决定。
     */
    fun agentEventPayload(
        sessionId: String,
        cursor: Long,
        eventType: String,
        summary: String? = null,
        body: String? = null,
        ref: String? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentEventPayload.serializer(),
        AgentEventPayload(
            sessionId = sessionId,
            cursor = cursor,
            eventType = eventType,
            summary = summary,
            body = body,
            ref = ref,
        ),
    )

    fun agentQueuePayload(
        taskId: String,
        sessionId: String? = null,
        op: String,
        prompt: String? = null,
        queuedAt: Long? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentQueuePayload.serializer(),
        AgentQueuePayload(taskId = taskId, sessionId = sessionId, op = op, prompt = prompt, queuedAt = queuedAt),
    )

    fun agentRunPayload(
        sessionId: String,
        runId: String,
        state: String,
        at: Long? = null,
        note: String? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentRunPayload.serializer(),
        AgentRunPayload(sessionId = sessionId, runId = runId, state = state, at = at, note = note),
    )

    fun agentApprovalPayload(
        approvalId: String,
        sessionId: String,
        tool: String? = null,
        summary: String? = null,
        expiresAt: Long,
        choices: String? = null,
        riskLevel: String? = null,
        diff: String? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentApprovalPayload.serializer(),
        AgentApprovalPayload(
            approvalId = approvalId,
            sessionId = sessionId,
            tool = tool,
            summary = summary,
            expiresAt = expiresAt,
            choices = choices,
            riskLevel = riskLevel,
            diff = diff,
        ),
    )

    fun agentCancelPayload(
        sessionId: String,
        runId: String? = null,
        reason: String? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentCancelPayload.serializer(),
        AgentCancelPayload(sessionId = sessionId, runId = runId, reason = reason),
    )

    fun agentAckPayload(
        ackOf: String,
        kind: String,
        at: Long? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentAckPayload.serializer(),
        AgentAckPayload(ackOf = ackOf, kind = kind, at = at),
    )

    /**
     * §6.7 `agent-artifact`：产物元数据（cap `agn1`）。
     *
     * **不传二进制** —— `fileId` 指向一条已经发过的 `file-msg`；调用方负责先发文件再发这一帧。
     */
    fun agentArtifactPayload(
        sessionId: String,
        artifactId: String,
        name: String,
        fileId: String? = null,
        bytes: Long? = null,
        mime: String? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentArtifactPayload.serializer(),
        AgentArtifactPayload(
            sessionId = sessionId,
            artifactId = artifactId,
            fileId = fileId,
            name = name,
            bytes = bytes,
            mime = mime,
        ),
    )

    /**
     * PPE-1 增补 D3：宣告**一个**工作区（cap `aws1`）。
     *
     * 发送方是宿主（agentNode / 桌面端 / 插件驱动的自动宣告）；改名 / 新增 / 移除时重发。
     * 这帧走**尽力而为**（不在 RELIABLE），调用方不许把它当"已送达"记账。
     */
    @Suppress("LongParameterList")
    fun agentWorkspacePayload(
        wsId: String,
        wsTitle: String? = null,
        wsPath: String? = null,
        agentId: String? = null,
        agentKind: String? = null,
        online: Boolean? = null,
        sessionCount: Long? = null,
        at: Long? = null,
        order: Long? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentWorkspacePayload.serializer(),
        AgentWorkspacePayload(wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order),
    )

    /** PPE-1 增补 D7：设备发起授权申请（cap `agr1`）。 */
    fun agentGrantRequestPayload(
        agentKey: String? = null,
        reason: String? = null,
        at: Long? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentGrantRequestPayload.serializer(),
        AgentGrantRequestPayload(agentKey, reason, at),
    )

    /** PPE-1 增补 D7：宿主的授权决定（cap `agr1`）。 */
    fun agentGrantPayload(
        state: String,
        scope: String,
        agentKey: String? = null,
        at: Long? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentGrantPayload.serializer(),
        AgentGrantPayload(state, scope, agentKey, at),
    )

    /** PPE-1 增补 D7：宿主逐条回授权清单（cap `agr1`）。 */
    fun agentGrantListPayload(
        scope: String,
        agentKey: String? = null,
        total: Long? = null,
        at: Long? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentGrantListPayload.serializer(),
        AgentGrantListPayload(scope, agentKey, total, at),
    )

    /**
     * PPE-1 增补 D8 `agent-session-page`（cap `agp1`）。
     *
     * 出站收紧：`offset` / `limit` 为负是调用方的错，这里直接夹到合法范围，
     * 不让一帧形状非法的请求占着 RELIABLE 的重传（与其它 builder 同一条纪律）。
     */
    fun agentSessionPagePayload(
        wsId: String,
        offset: Long? = null,
        limit: Long? = null,
        at: Long? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        AgentSessionPagePayload.serializer(),
        AgentSessionPagePayload(wsId, offset?.coerceAtLeast(0L), limit?.coerceAtLeast(0L), at),
    )
}
