// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.DecodeResult
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.core.transport.SendOutcome
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 跨端派单（invoke / chain / channel）。
 *
 * 三条「派一棒给别的节点」的 HTTP 入口，以及它们共用的目标解析与投递。
 *
 * 三种粒度刻意分成三条路由：`invoke` 一棒、`chain` 一条责任链、`channel` 一次 Channel 委派。
 * 三者都要经过同一段「解析目标 → 自检帧 → 发出」，那段在 `deliverAgentFrame` 与
 * `resolveAgentTarget` 里 —— 复制一份迟早会漂。
 */

/**
 * `POST /v1/agent/invoke` —— 跨端派单的 HTTP 入口（M4，规格 §6.2）。
 *
 * 职责对账结论（追踪器 M4 裁决）：现有 /v1/agent/ 下的全部路由都是 PPE-1 单向宣告帧发送器，
 * 「带租约的任务派发 RPC」是独立职责 ⇒ 新开路由；`/v1/agent/run` 原语义不动。
 * 这里只做解包与**如实**的失败面：域层没装配（agentNode 单进程）= 503，
 * 群里没有这个 agent = 404 —— 都不假装接了单。
 */
internal fun ControlPlane.agentInvoke(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val groupId = obj["groupId"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    val agentId = obj["agentId"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    val prompt = obj["prompt"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    if (groupId.isEmpty() || agentId.isEmpty() || prompt.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "groupId / agentId / prompt are all required"))
        return
    }
    val wsId = obj["wsId"]?.jsonPrimitiveOrNull()?.contentOrNull
    val deadlineMs = obj["deadlineMs"]?.let { Codec.intOf(it) } ?: ControlPlane.DEFAULT_INVOKE_DEADLINE_MS
    if (deadlineMs <= 0) {
        respond(exchange, 400, errorJson("bad-request", "deadlineMs must be positive (a lease without a deadline waits forever)"))
        return
    }
    val dispatcher = invokeDispatcher
    if (dispatcher == null) {
        respond(
            exchange,
            503,
            errorJson("invoke-not-wired", "no domain layer in this process; cross-host dispatch lives in the desktop shell"),
        )
        return
    }
    // fold 而不是 onFailure+getOrNull：两条路径都只许回**一次**响应 ——
    // 域层抛了异常再补一个 404，第二条 respond 会砸在同一个 exchange 上。
    // 预算直接用调用方给的租约 deadlineMs：它就是这条路由**约定**的最长占用时间，
    // 在它之内按多久都是按契约办事，超过它才值得打一行读数。
    runCatching {
        CarrierHold.hold("agentChannel.invoke", budgetMs = deadlineMs) {
            dispatcher.dispatch(groupId, agentId, prompt, wsId, deadlineMs)
        }
    }.fold(
        onSuccess = { invokeId ->
            if (invokeId == null) {
                respond(
                    exchange,
                    404,
                    errorJson("agent-not-found", "no virtual agent \"" + agentId + "\" declared in group " + groupId),
                )
                return
            }
            respond(
                exchange,
                200,
                buildJsonObject {
                    put("ok", true)
                    put("invokeId", invokeId)
                    put("note", "dispatched over the P2P mesh; the group sees only the leader's board card (zero cross-host text)")
                },
            )
        },
        onFailure = { t ->
            respond(exchange, 500, errorJson("invoke-failed", t.message ?: (t::class.simpleName ?: "unknown")))
        },
    )
}

/**
 * `POST /v1/agent/chain` —— 一次受理、后台跑完整条责任链。
 *
 * 载荷 `{groupId?, repoPath?, title?, stages:[{role, agentKey, instruction}]}`；
 * 回执 `{chainId, stages}`。回执只代表**受理**：进度与结论都在群里的那张链卡上
 * （一链一卡、rev 递增），这条路由不返回产物 —— 产物在宿主仓的提交里。
 *
 * 三处入口校验，都是「宁可 400 也不要开跑」：没装执行器 ⇒ 503；stages 空了或
 * 某棒缺 role/agentKey/instruction ⇒ 400；执行器拒绝（读不到 HEAD 之类）⇒ 409。
 */

internal fun ControlPlane.agentChain(exchange: HttpExchange) {
    val dispatcher = chainDispatcher
    if (dispatcher == null) {
        respond(exchange, 503, errorJson("no-chain-executor", "本机没有链执行器（需要壳层装配 chainDispatcher）"))
        return
    }
    val obj = readJsonObject(exchange) ?: return
    val stages = (obj["stages"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val role = o["role"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
        val key = o["agentKey"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
        val instruction = o["instruction"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
        // 缺一样就整棒作废：拿默认值凑出来的棒等于替调用方编了一份任务书。
        if (role.isEmpty() || key.isEmpty() || instruction.isEmpty()) return@mapNotNull null
        ChainStage(role = role, agentKey = key, instruction = instruction)
    }
    if (stages.isEmpty()) {
        respond(
            exchange,
            400,
            errorJson("bad-request", "stages 至少要有一棒，且每棒都要 role/agentKey/instruction"),
        )
        return
    }
    val groupId = obj["groupId"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
    val repoPath = obj["repoPath"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
    val title = obj["title"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() } ?: "责任链"
    // 只等"登记完一条链"（起 worktree / 落首棒），不等整条链跑完 —— 整条链是后台的事。
    val chainId = CarrierHold.hold("stageChain.start", CarrierHold.PLUGIN_MS) {
        dispatcher.start(groupId, repoPath, title, stages)
    }
    if (chainId == null) {
        respond(exchange, 409, errorJson("chain-rejected", "链执行器拒绝了这次请求（原因见宿主日志）"))
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("chainId", chainId)
            put("stages", stages.size)
        },
    )
}

/**
 * `POST /v1/agent/channel` —— Channel 委派的 HTTP 发起入口（M7-T2，决策包 §1.6）。
 *
 * 形状：`{"to":"<IP 或 nodeId>","channelId":"…","op":"assign|transfer","fromAgent":"…","task":"…",""" +
    """"toAgent"/"targetTag" 恰一, "expectedResult"/"resources"/"sourceMessageIds"/"deadlineMs"/"sessionId"/"groupId"/"groupRev" 可选}`。
 *
 * 三道门（顺序即责任链）：
 *  1. **caps 门（决策包 §1.6 的「发送前逐类门控」）**：对端没声明 `agc1` ⇒ 409，
 *     帧**不构造**——RELIABLE 发进老端 = ACK 后整帧拒收 + 队列重传（AGENTS §3
 *     第 2 种「没收到」的时间形态），发送侧不产出注定被拒的帧；
 *  2. **构造期校验**：`Codec.channelPayload` 的 require（op 互斥 / toAgent ⊕ targetTag
 *     恰一 / assign 必带租约）—— 违反 400，崩在发送侧而不是对端；
 *  3. **自检闭环**：`deliverAgentFrame` 把要发的帧先 `decodeTcp` 回来，自己都不收就不发。
 */

internal fun ControlPlane.agentChannel(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val to = obj["to"]?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
    if (to.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 to（对方 IP 或 nodeId）"))
        return
    }
    val nodeId = resolveTargetNodeId(to, obj["port"]?.jsonPrimitiveOrNull()?.contentOrNull?.toIntOrNull())
    if (nodeId == null) {
        respond(
            exchange,
            404,
            errorJson("peer-unreachable", "3 秒内无法与 " + to + " 建立握手（对方设备是否已启动？）"),
        )
        return
    }
    // 门 1：caps —— 逐类查 agc1（不许按 agn1 放行，决策包 §1.6）。
    if (!runtime.peerDeclares(nodeId, MsgTypes.CAP_AGENT_CHANNEL)) {
        respond(
            exchange,
            409,
            errorJson(
                "peer-unsupported",
                "对方（" + to + "）未声明 " + MsgTypes.CAP_AGENT_CHANNEL +
                    "（Channel 委派网道），按 PPE-1 §4 不发这一帧；" +
                    "对端升级前这个委派只能走别的方式",
            ),
        )
        return
    }
    val channelId = obj["channelId"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: newId()
    val op = obj["op"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    val fromAgent = obj["fromAgent"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    val task = obj["task"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
    val toAgent = obj["toAgent"]?.jsonPrimitiveOrNull()?.contentOrNull
    val targetTag = obj["targetTag"]?.jsonPrimitiveOrNull()?.contentOrNull
    val expectedResult = obj["expectedResult"]?.jsonPrimitiveOrNull()?.contentOrNull
    val resources = (obj["resources"] as? JsonArray)?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
    val sourceMessageIds = (obj["sourceMessageIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
    val deadlineMs = obj["deadlineMs"]?.let { Codec.intOf(it) }?.toLong()
    val sessionId = obj["sessionId"]?.jsonPrimitiveOrNull()?.contentOrNull
    val groupId = obj["groupId"]?.jsonPrimitiveOrNull()?.contentOrNull
    val groupRev = obj["groupRev"]?.let { Codec.intOf(it) }?.toLong()
    val payload = try {
        Codec.channelPayload(
            channelId = channelId, op = op, fromAgent = fromAgent, task = task,
            toAgent = toAgent, targetTag = targetTag, expectedResult = expectedResult,
            resources = resources, sourceMessageIds = sourceMessageIds,
            deadlineMs = deadlineMs, sessionId = sessionId, groupId = groupId,
            groupRev = groupRev,
        )
    } catch (e: IllegalArgumentException) {
        // 门 2：构造期 require —— op 互斥 / 恰一 / 租约，违反 = 调用方的形状错误。
        respond(exchange, 400, errorJson("bad-request", e.message ?: "invalid channel payload"))
        return
    }
    deliverAgentFrame(
        exchange,
        nodeId,
        MsgTypes.AGENT_CHANNEL,
        payload,
        send = { runtime.sendAgentChannel(peerId = nodeId, channelId = channelId, op = op, fromAgent = fromAgent, task = task, toAgent = toAgent, targetTag = targetTag, expectedResult = expectedResult, resources = resources, sourceMessageIds = sourceMessageIds, deadlineMs = deadlineMs, sessionId = sessionId, groupId = groupId, groupRev = groupRev) },
    )
}

/**
 * 解析 `to` / `port`、握手拿到 nodeId，并完成 §4 的 caps 前置检查。
 *
 * 返回 `null` 表示**已经回过响应**（错误），调用方直接 return。
 */
internal fun ControlPlane.resolveAgentTarget(
    exchange: HttpExchange,
    obj: JsonObject,
    /**
     * 这一类帧要求的 cap（§4 的 caps 表）。
     *
     * 必须**逐类**传，不能统一按 `agn1` 放行：`agq1` 的五个类型（queue/run/approval/
     * cancel/ack）要求对方真的实现了队列与审批回路，`agn1` 只保证它懂会话基线。
     * 拿 `agn1` 给 `agq1` 的帧开门，等于替对端宣称了一个它没声明过的能力 ——
     * 帧会发出去、被 ACK、然后在对端静默消失。
     */
    requiredCap: String,
    requireRw: Boolean = false,
): String? {
    val to = obj["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (to == runtime.nodeId || to == "self") {
        return runtime.nodeId
    }
    if (to.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 to（对方 IP 或 nodeId）"))
        return null
    }
    val port = obj["port"]?.jsonPrimitive?.intOrNull
    if (port != null && port !in 1..65535) {
        respond(exchange, 400, errorJson("bad-request", "port 越界：$port"))
        return null
    }
    val target = if (port != null) "$to:$port" else to
    val nodeId = resolveTargetNodeId(to, port)
    if (nodeId == null) {
        respond(
            exchange,
            404,
            errorJson(
                "peer-unreachable",
                "3 秒内无法与 $target 建立握手（对方设备是否已启动？IP 与端口是否正确？）",
            ),
        )
        return null
    }
    if (!runtime.peerDeclares(nodeId, MsgTypes.CAP_AGENT_SESSION)) {
        respond(
            exchange,
            409,
            errorJson(
                "peer-unsupported",
                "对方（$target）未声明 ${MsgTypes.CAP_AGENT_SESSION}（agent 会话语义基线），" +
                    "按 PPE-1 §4 不发这一帧；可改用 POST /v1/send 以普通文本送达",
            ),
        )
        return null
    }
    if (!runtime.peerDeclares(nodeId, requiredCap)) {
        respond(
            exchange,
            409,
            errorJson(
                "peer-unsupported",
                "对方（$target）未声明 $requiredCap（这一帧所属能力组），" +
                    "按 PPE-1 §4 不发这一帧；可改用 POST /v1/send 以普通文本送达",
            ),
        )
        return null
    }
    // PPE-1 增补 D7 的**执行点**：投递前按授权策略过滤 —— 这里不放行，数据就根本
    // 不出门（父稿 §3.3「没授权 = 没数据」只有在宿主进程过滤才成立）。
    // agentKey = agentId ?? agentKind（转发方知道自己在转发哪个 agent）；
    // 两者都没报时按"整台设备"判（scope=agent 的授权不放行无身份的帧，不猜）。
    val agentKey = obj["agentId"]?.jsonPrimitive?.contentOrNull
        ?: obj["agentKind"]?.jsonPrimitive?.contentOrNull
    val wsId = obj["wsId"]?.jsonPrimitive?.contentOrNull
    if (!grants.allows(nodeId, agentKey, wsId, requireRw)) {
        val snap = grants.snapshot()
        val reason = if (requireRw) {
            "对方（$target）对该 agent/工作区仅有只读权限（ro），无法执行写操作或下发指令。"
        } else {
            "对方（$target）未被授权看这台宿主的 agent（策略 ${snap.policy}）。"
        }
        respond(
            exchange,
            403,
            errorJson(
                "not-granted",
                reason +
                    // 台账读不回来时，对方看到的是"被拒"，但真实原因是"宿主自己丢了决定"。
                    // 不说这件事，人会去改一台好设备的授权，而问题在这台宿主的文件上。
                    (snap.loadError?.let { "注意：本宿主这次启动没能读回授权台账（$it），因此按最严档运行。" } ?: "") +
                    // 申请是 PPE-1 的 **协议帧**（agent-grant-request，设备 → 宿主），
                    // 不是 HTTP 路由 —— 控制面只对外开 GET/PUT /v1/agent/grants 两条。
                    // 从前这里写"经 POST /v1/agent/grant-request 申请"，那条路由不存在。
                    "在桌面端「榫卯 → 授权」里批准，或让对方重发一次 agent-grant-request 申请" +
                    "（申请走 PPE-1 协议帧，控制面没有对应的 HTTP 路由）。",
            ),
        )
        return null
    }
    return nodeId
}

/**
 * 自检 → 发送 → 回执。
 *
 * 自检复用**收端的校验器**：把要发的帧编码出来再 `Codec.decodeTcp` 解回去，我们自己
 * 都不收就绝不发出去。手抄一份字段规则迟早与 `Codec` 漂移，那样"发得出去、收不回来"
 * 会变成一个只在真机上才暴露的问题。
 */
internal fun ControlPlane.deliverAgentFrame(
    exchange: HttpExchange,
    nodeId: String,
    type: String,
    payload: JsonElement,
    send: suspend () -> SendOutcome,
    /**
     * 帧**真的投出去之后**才调（自检拒收 / 502 都不会走到这里）。
     *
     * 放在这里而不是各调用方里，是为了让「记账」与「投递成功」在代码上不可能分叉：
     * 两处调用各写一遍 `if (outcome == SENT)`，迟早有一处漏写或写成 `!= FAILED`，
     * 那样台账里会出现一个从没上过线的会话 —— 面板显示它「busy」，而线上什么都没有。
     */
    onSent: () -> Unit = {},
) {
    val frame = Codec.encode(makeEnvelope(type, runtime.nodeId, payload))
    val back = Codec.decodeTcp(frame)
    if (back is DecodeResult.Rejected) {
        respond(exchange, 400, errorJson("bad-request", "这一帧我们自己都拒收：${back.reason}"))
        return
    }
    val outcome = if (nodeId == runtime.nodeId) {
        SendOutcome.SENT
    } else {
        try {
            CarrierHold.hold("send.envelope", CarrierHold.SEND_MS) { send() }
        } catch (t: Throwable) {
            respond(exchange, 502, errorJson("send-failed", t.message ?: "发送失败"))
            return
        }
    }
    // queued 也记账：帧已经交给重传队列，会话确实"存在"，只是还没送达。
    // 不记的话，对端离线时发的宣告在面板上凭空消失，用户会以为没发出去。
    runCatching { onSent() }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("status", if (outcome == SendOutcome.SENT) "sent" else "queued")
            put("to", nodeId)
            put("type", type)
            put(
                "note",
                if (outcome == SendOutcome.SENT) {
                    "对方已 ACK"
                } else {
                    "已入队，等对方上线后补发（不代表已读）"
                },
            )
        },
    )
}
