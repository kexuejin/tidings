// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 把一条**入站**信封按 PPE-1 §6.1–§6.8 记进台账；不是 agent 帧就原样忽略。
 *
 * ## 为什么抽出来共享
 *
 * [ControlPlane] 只写台账的**出站**那一半（它在 `deliverAgentFrame` 的回执里记「我发过」）。
 * **入站**那一半必须由「拥有这条 `NodeRuntime.inbound` 的进程」自己订阅写入 —— 这正是
 * [ControlPlane] 的 `sessions` 形参那条「两边必须是同一个实例」约束的另一半。
 *
 * 而同一个进程模型有两处：agentNode 进程（`Main`）与**桌面端内嵌**的控制面
 * （`DesktopExtension`）。它们是两个独立进程，各自订阅各自运行时的入站流，但**解析规则
 * 必须逐字相同** —— 否则同一个对端发来的同一条帧，在两个进程里会记成不同形状，
 * 面板拿到的就是两套说法。所以规则只有这一份。
 *
 * ## 为什么不复用 `/v1/inbox`
 *
 * 那是给宿主 agent 的**聊天增量**。agent 帧混进去对消费方就是一批没人认领的噪声
 * （`NodeRuntime` 里 SCREEN / SHARE 的注释是同一纪律）。所以这里只挑 agent-* 类型。
 *
 * ## 六类帧都要在这里落地
 *
 * `MsgTypes.AGENT_CAPS` 宣告了 `agq1`，对端据此会把队列、审批、执行状态发过来。
 * 入站侧少接一个类型，就等于宣告了一个做不到的能力：帧会被传输层 ACK（因为
 * `RELIABLE` 里有它），然后**静默消失** —— 这是最难查的一类故障（§13 反模式
 * 「未声明 caps 就发 `agent-*`」的镜像面）。故这里对六类逐一落地。
 *
 * 幂等：同 `(nodeId, sessionId)` 重投是协议允许的（§8.2 的续读语义），
 * 各 `record*` 都做合并或就地替换。
 */
fun AgentSessionRegistry.recordInbound(envelope: Envelope) {
    when (envelope.type) {
        MsgTypes.AGENT_SESSION -> {
            val p = envelope.payload as? JsonObject ?: return
            val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
            recordSession(
                nodeId = envelope.from,
                sessionId = sessionId,
                alias = p["alias"]?.jsonPrimitive?.contentOrNull,
                agentKind = p["agentKind"]?.jsonPrimitive?.contentOrNull,
                state = p["state"]?.jsonPrimitive?.contentOrNull,
                title = p["title"]?.jsonPrimitive?.contentOrNull,
                startedAt = p["startedAt"]?.jsonPrimitive?.longOrNull,
                // PPE-1 增补 D1/D6：四个新可选键，缺键即 null（不猜）。
                // wsId 与 wsTitle 双缺时忽略 wsPath（父稿 §2.1 归组纪律，与 codec 同判据）。
                agentId = p["agentId"]?.jsonPrimitive?.contentOrNull,
                wsId = p["wsId"]?.jsonPrimitive?.contentOrNull,
                wsTitle = p["wsTitle"]?.jsonPrimitive?.contentOrNull,
                wsPath = if (p.containsKey("wsId") || p.containsKey("wsTitle"))
                    p["wsPath"]?.jsonPrimitive?.contentOrNull else null,
                outbound = false,
            )
        }

        MsgTypes.AGENT_EVENT -> {
            val p = envelope.payload as? JsonObject ?: return
            val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
            val cursor = p["cursor"]?.jsonPrimitive?.longOrNull ?: return
            val eventType = p["eventType"]?.jsonPrimitive?.contentOrNull ?: return
            recordEvent(
                nodeId = envelope.from,
                sessionId = sessionId,
                cursor = cursor,
                eventType = eventType,
                summary = p["summary"]?.jsonPrimitive?.contentOrNull,
                body = p["body"]?.jsonPrimitive?.contentOrNull,
                ref = p["ref"]?.jsonPrimitive?.contentOrNull,
            )
        }

        MsgTypes.AGENT_QUEUE -> {
            val p = envelope.payload as? JsonObject ?: return
            val taskId = p["taskId"]?.jsonPrimitive?.contentOrNull ?: return
            val op = p["op"]?.jsonPrimitive?.contentOrNull ?: return
            recordQueue(
                nodeId = envelope.from,
                taskId = taskId,
                sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull,
                op = op,
                prompt = p["prompt"]?.jsonPrimitive?.contentOrNull,
                queuedAt = p["queuedAt"]?.jsonPrimitive?.longOrNull,
                outbound = false,
            )
        }

        MsgTypes.AGENT_RUN -> {
            val p = envelope.payload as? JsonObject ?: return
            val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
            val runId = p["runId"]?.jsonPrimitive?.contentOrNull ?: return
            val state = p["state"]?.jsonPrimitive?.contentOrNull ?: return
            recordRun(
                nodeId = envelope.from,
                sessionId = sessionId,
                runId = runId,
                state = state,
                at = p["at"]?.jsonPrimitive?.longOrNull,
                note = p["note"]?.jsonPrimitive?.contentOrNull,
                outbound = false,
            )
        }

        MsgTypes.AGENT_APPROVAL -> {
            val p = envelope.payload as? JsonObject ?: return
            val approvalId = p["approvalId"]?.jsonPrimitive?.contentOrNull ?: return
            val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
            // expiresAt 在 §6.5 是必填，`Codec` 已保证它是非负整数；到这里还缺就是帧被手改过。
            val expiresAt = p["expiresAt"]?.jsonPrimitive?.longOrNull ?: return
            recordApproval(
                nodeId = envelope.from,
                sessionId = sessionId,
                approvalId = approvalId,
                tool = p["tool"]?.jsonPrimitive?.contentOrNull,
                summary = p["summary"]?.jsonPrimitive?.contentOrNull,
                expiresAt = expiresAt,
                choices = p["choices"]?.jsonPrimitive?.contentOrNull,
                outbound = false,
            )
        }

        MsgTypes.AGENT_CANCEL -> {
            val p = envelope.payload as? JsonObject ?: return
            val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
            recordCancel(
                nodeId = envelope.from,
                sessionId = sessionId,
                runId = p["runId"]?.jsonPrimitive?.contentOrNull,
                reason = p["reason"]?.jsonPrimitive?.contentOrNull,
                outbound = false,
            )
        }

        MsgTypes.AGENT_ARTIFACT -> {
            val p = envelope.payload as? JsonObject ?: return
            val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
            val artifactId = p["artifactId"]?.jsonPrimitive?.contentOrNull ?: return
            val name = p["name"]?.jsonPrimitive?.contentOrNull ?: return
            recordArtifact(
                nodeId = envelope.from,
                sessionId = sessionId,
                artifactId = artifactId,
                name = name,
                fileId = p["fileId"]?.jsonPrimitive?.contentOrNull,
                bytes = p["bytes"]?.jsonPrimitive?.longOrNull,
                mime = p["mime"]?.jsonPrimitive?.contentOrNull,
                outbound = false,
            )
        }

        MsgTypes.AGENT_ACK -> {
            val p = envelope.payload as? JsonObject ?: return
            val ackOf = p["ackOf"]?.jsonPrimitive?.contentOrNull ?: return
            val kind = p["kind"]?.jsonPrimitive?.contentOrNull ?: return
            recordAck(
                nodeId = envelope.from,
                ackOf = ackOf,
                kind = kind,
                at = p["at"]?.jsonPrimitive?.longOrNull,
                outbound = false,
            )
        }
    }
}

/**
 * 把一条**入站** `agent-workspace`（PPE-1 增补 D3）记进工作区台账；不是这型帧就忽略。
 *
 * 与 [recordInbound] 同一纪律：解析规则只有这一份，agentNode 进程与桌面端内嵌控制面
 * 逐字共用（两个进程各自订阅各自的入站流，台账形状不许分家）。
 *
 * 工作区帧没有 agentId/agentKind 之外的"身份"层 —— 归组键里的 agentKey 取
 * `agentKey` 缺省退 `agentKind`（父稿 §1）；两者都没报时 agentKey=null，
 * 台账照记（工作区是这条帧唯一能表达的档），UI 按"未上报类型"降级。
 */
fun AgentWorkspaceRegistry.recordInboundWorkspace(envelope: Envelope) {
    if (envelope.type != MsgTypes.AGENT_WORKSPACE) return
    val p = envelope.payload as? JsonObject ?: return
    val wsId = p["wsId"]?.jsonPrimitive?.contentOrNull ?: return
    val agentKind = p["agentKind"]?.jsonPrimitive?.contentOrNull
    val agentId = p["agentId"]?.jsonPrimitive?.contentOrNull
    record(
        nodeId = envelope.from,
        // 归组键 = agentId ?? agentKind。必须与会话侧同判据，避免拆分成多行。
        agentKey = agentId ?: agentKind,
        wsId = wsId,
        wsTitle = p["wsTitle"]?.jsonPrimitive?.contentOrNull,
        wsPath = p["wsPath"]?.jsonPrimitive?.contentOrNull,
        agentKind = agentKind,
        online = (p["online"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { !it.isString }?.let { it.content == "true" },
        sessionCount = p["sessionCount"]?.jsonPrimitive?.longOrNull,
        // 宿主注册表持久序。入站这一路必须原样带上，否则工作区在宿主侧有 order、
        // 到了设备侧却整列 NULL（真机症状：同一批工作区两边排序不同）。
        order = p["order"]?.jsonPrimitive?.longOrNull,
        outbound = false,
        at = p["at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
    )
}

/**
 * 把一条入站 grant 帧记进授权台账（PPE-1 增补 D7）。
 *
 * 只处理 `agent-grant-request`（设备 → 宿主：进待办队列，宿主是决定方）；
 * `agent-grant` / `agent-grant-list` 是**发给设备**的帧，宿主收到它们 = 对方把自己
 * 当设备了 —— 本函数不处理，静默忽略（agentNode 与桌面端都不是设备）。
 *
 * 返回 true = 这条信封是一条申请（调用方据此决定要不要回 grant-list）。
 */
fun AgentGrantStore.recordInboundGrant(envelope: Envelope): Boolean {
    if (envelope.type != MsgTypes.AGENT_GRANT_REQUEST) return false
    val p = envelope.payload as? JsonObject ?: return false
    recordRequest(
        nodeId = envelope.from,
        agentKey = p["agentKey"]?.jsonPrimitive?.contentOrNull,
        wsId = p["wsId"]?.jsonPrimitive?.contentOrNull,
        access = p["access"]?.jsonPrimitive?.contentOrNull,
        reason = p["reason"]?.jsonPrimitive?.contentOrNull,
        at = p["at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
    )
    return true
}

/**
 * 入站 `agent-session-page` 请求的待办队列（PPE-1 增补 D8）。
 *
 * ## 为什么是队列而不是就地回包
 *
 * 会话历史只有**宿主（DSH 插件）**知道（`ctx.workspaceRegistry` 的 sessionIds / DSH 的
 * session 存储），agentNode 只转发。所以这张表的作用是：把设备发来的请求**留给宿主去答**。
 *
 * 与三张 grant 帧的关键差别：grant 的答案是**本机事实**（策略与白名单就在节点里），所以
 * `observeInbound` 能就地回；page 的答案在节点之外，节点回不了 —— 它只能把请求排队，
 * 等宿主来读（`GET /v1/agent/session-pages`）并把结果用 `POST /v1/agent/session` 回填。
 *
 * 队列有界（最老的先丢）：一个失控的客户端不该把节点内存撑爆，而丢掉的那些请求在
 * 手机上表现为「点了加载更多没反应」，用户重按一次即可。
 */
class AgentSessionPageStore(private val maxPending: Int = 64) {

    /** 一条待回答的请求。`id` 单调递增，宿主回填后可回报它（便于对账）。 */
    data class Request(
        val id: Long,
        val nodeId: String,
        val wsId: String,
        val offset: Long,
        val limit: Long?,
        val at: Long,
    )

    private val pending = ArrayDeque<Request>()
    private var nextId = 1L

    /** 记下一条入站请求；同 `(nodeId, wsId)` 的旧条目先去掉（连续点「更多」只该留最新那条）。 */
    @Synchronized
    fun record(nodeId: String, wsId: String, offset: Long, limit: Long?, at: Long) {
        pending.removeAll { it.nodeId == nodeId && it.wsId == wsId }
        pending += Request(nextId++, nodeId, wsId, offset, limit, at)
        while (pending.size > maxPending) pending.removeFirst()
    }

    /** 快照（宿主读用）。 */
    @Synchronized
    fun snapshot(): List<Request> = pending.toList()

    /** 宿主回填完成后销掉一条。 */
    @Synchronized
    fun resolve(id: Long): Boolean = pending.removeAll { it.id == id }
}

/** 从入站信封里取一条 page 请求；不是这帧就返回 false（与 [recordInboundGrant] 同形）。 */
fun AgentSessionPageStore.recordInbound(envelope: Envelope): Boolean {
    if (envelope.type != MsgTypes.AGENT_SESSION_PAGE) return false
    val p = envelope.payload as? JsonObject ?: return false
    val wsId = p["wsId"]?.jsonPrimitive?.contentOrNull ?: return false
    record(
        nodeId = envelope.from,
        wsId = wsId,
        offset = p["offset"]?.jsonPrimitive?.longOrNull ?: 0L,
        limit = p["limit"]?.jsonPrimitive?.longOrNull,
        at = p["at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
    )
    return true
}