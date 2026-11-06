// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.AgentGrantAccess
import com.kapp.tenon.core.protocol.AgentGrantPolicies
import com.kapp.tenon.core.protocol.AgentGrantScopes
import com.kapp.tenon.core.protocol.AgentGrantStates
import com.kapp.tenon.core.protocol.MsgTypes
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 授权台账（grants 读 / 写 / 回执）。
 *
 * `/v1/agent/grants` 的读与写，以及宿主对申请的回执。
 *
 * 写的语义比读重：一次 PUT 可能同时落策略、白名单、四档状态与待办队列，任何一处
 * 「部分成功」都会让面板显示的和实际放行的对不上 —— 所以整块在一处，按顺序落地。
 *
 * 回执的三个结局（`GrantReplyOutcome`）必须分开：对端没声明 `agr1` 时**没发**，
 * 台账照写但界面不许说「已发送」。
 */

/** `GET /v1/agent/grants` —— 本宿主的策略与白名单 + 待办申请（桌面端授权页读数）。 */
internal fun ControlPlane.readGrants(exchange: HttpExchange) {
    val snap = grants.snapshot()
    respond(
        exchange,
        200,
        buildJsonObject {
            put("policy", snap.policy)
            // 非 null = 这次启动没能读回台账、已按最严档运行。桌面端授权页必须
            // 显式显示它 —— 否则用户看到的是一台"策略被改了"的宿主，实际是文件坏了。
            put("loadError", snap.loadError)
            putJsonArray("grants") {
                snap.grants.forEach { g ->
                    addJsonObject {
                        put("nodeId", g.nodeId)
                        put("scope", g.scope)
                        put("agentKey", g.agentKey)
                        put("wsId", g.wsId)
                        put("access", g.access)
                        if (g.isMyDevice) put("isMyDevice", true)
                        if (g.delegatedApproval) put("delegatedApproval", true)
                        put("grantedAt", g.grantedAt)
                        put("grantedBy", g.grantedBy)
                    }
                }
            }
            putJsonArray("requests") {
                snap.requests.forEach { r ->
                    addJsonObject {
                        put("nodeId", r.nodeId)
                        put("agentKey", r.agentKey)
                        put("wsId", r.wsId)
                        put("access", r.access)
                        put("reason", r.reason)
                        put("at", r.at)
                    }
                }
            }
        },
    )
}

/**
 * 把一个授权决定回给设备（agent-grant 帧，宿主 → 设备，cap agr1）。
 *
 * 两个调用方共用这一份实现：控制面 PUT /v1/agent/grants 的 grantReply 分支，
 * 与桌面端「授信设备」面板上人按的按钮（DesktopExtension 持有本控制面实例）。
 * 抽出来的理由就是"只留一份"：发帧要查 cap、要等 ACK、要区分"未发送"与"发失败" ——
 * 抄第二份必然漂移，而漂移的表现正是"界面说发了、手机没收到"。
 *
 * 调用方**必须先写台账**（AgentGrantStore.grant / revoke / rejectRequest）：决定是本机
 * 事实，帧发不出去也不该丢。本方法只管"把已经记下的决定告诉对端"，不碰台账。
 *
 * @return [GrantReplyOutcome]：Sent / Unsupported（对端没声明 agr1，**没发**）/ Failed。
 *   绝不把"没发出去"报成成功。
 */
suspend fun ControlPlane.sendGrantReply(
    nodeId: String,
    state: String,
    scope: String,
    agentKey: String? = null,
    wsId: String? = null,
    access: String? = null,
    isMyDevice: Boolean? = null,
    delegatedApproval: Boolean? = null,
): GrantReplyOutcome {
    // 取值域是封闭的（与 codec 同判据）：拼错一个等于语义不存在。HTTP 那一侧的
    // 400 文案更具体；这里只兜底，宁可如实报失败也不发一条对端必然拒收的帧。
    if (state !in AgentGrantStates.ALL || scope !in AgentGrantScopes.VALUES) {
        return GrantReplyOutcome.Failed(nodeId, "state/scope 取值非法")
    }
    // cap 门控（父稿 §3.3）：对端没声明 agr1 就不发。发了它也不认，而 grant 帧在
    // RELIABLE 里 ⇒ 等不到 ACK 会把一台**在线设备标成离线**并进离线补发队列
    // （与 agent-workspace 首版同一个坑）。
    if (!runtime.peerDeclares(nodeId, MsgTypes.CAP_AGENT_GRANT)) {
        return GrantReplyOutcome.Unsupported(nodeId, MsgTypes.CAP_AGENT_GRANT)
    }
    return try {
        runtime.sendAgentGrant(
            nodeId, state, scope, agentKey, wsId, access, isMyDevice, delegatedApproval,
            System.currentTimeMillis(),
        )
        GrantReplyOutcome.Sent(nodeId)
    } catch (t: Throwable) {
        GrantReplyOutcome.Failed(nodeId, t.message ?: "发送失败")
    }
}

/**
 * `PUT /v1/agent/grants` —— 写策略 / 授权 / 撤销（**写入者 = 桌面端**，父稿 §3.3）。
 *
 * body：`{policy?, grant?:{nodeId,scope,agentKey?,wsId?,access?,grantedBy?},
 * revoke?:{nodeId,scope,agentKey?,wsId?}, grantReply?:{nodeId,state,scope,agentKey?,wsId?,access?}}`
 *
 * `grantReply` 把决定**回给设备**（`agent-grant` 帧，宿主 → 设备）：批准 / 拒绝 / 撤销
 * 都是「有人按了按钮」，必须让对面知道 —— 否则手机端的四档状态永远停在「已申请待批」。
 * 撤销只拦未来投递（边界 1），回执文案如实写。
 *
 * ## 这条路的 access **照抄调用方**，与面板那条**策略不同**（task-60 审查标注，别互相「修」）
 *
 * 面板（`TrustedDeviceDecisions.approve`）按**按钮语义封顶**（协同 ⇒ 至多 RW），因为那面对的是
 * **对端自己写进申请里的档位**；而这条路由的调用方是本机进程（控制面绑回环，见本文件顶部的
 * 信任说明）—— 它代表用户本人在配置自己的台账，照抄才是对的，替它「封顶」会让显式配置失效。
 *
 * 两处策略不同是**有意**的：对端能影响的是**申请队列**（那里必须封顶），而这里没有任何对端输入
 * （入站帧没有写台账的分支）。将来要改任何一边之前，先读这段。
 */

internal fun ControlPlane.writeGrants(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    var changed = false
    val notes = mutableListOf<String>()

    obj["policy"]?.jsonPrimitive?.contentOrNull?.let { next ->
        if (!grants.setPolicy(next)) {
            respond(
                exchange, 400,
                errorJson("bad-request", "policy 只能是 " + AgentGrantPolicies.VALUES.joinToString(" / ") + "；收到「" + next + "」"),
            )
            return
        }
        changed = true
        notes += "policy=" + next
    }

    (obj["grant"] as? JsonObject)?.let { g ->
        val nodeId = g["nodeId"]?.jsonPrimitive?.contentOrNull
        val scope = g["scope"]?.jsonPrimitive?.contentOrNull
        if (nodeId == null || scope == null || scope !in AgentGrantScopes.VALUES) {
            respond(exchange, 400, errorJson("bad-request", "grant 需要 nodeId 与 scope（all / agent / workspace）"))
            return
        }
        val agentKey = g["agentKey"]?.jsonPrimitive?.contentOrNull
        if (scope == AgentGrantScopes.AGENT && agentKey.isNullOrEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "scope=agent 时 agentKey 必填（缺了它无法归组，不许按整台设备放行）"))
            return
        }
        val wsId = g["wsId"]?.jsonPrimitive?.contentOrNull
        if (scope == AgentGrantScopes.WORKSPACE && wsId.isNullOrEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "scope=workspace 时 wsId 必填"))
            return
        }
        val access = g["access"]?.jsonPrimitive?.contentOrNull ?: AgentGrantAccess.RW
        if (access !in AgentGrantAccess.VALUES) {
            respond(exchange, 400, errorJson("bad-request", "access 只能是 " + AgentGrantAccess.VALUES.joinToString(" / ")))
            return
        }
        val isMyDevice = (g["isMyDevice"] as? JsonPrimitive)?.booleanOrNull ?: false
        val delegatedApproval = (g["delegatedApproval"] as? JsonPrimitive)?.booleanOrNull ?: false
        grants.grant(
            nodeId = nodeId,
            scope = scope,
            agentKey = agentKey,
            wsId = wsId,
            access = access,
            isMyDevice = isMyDevice,
            delegatedApproval = delegatedApproval,
            grantedBy = g["grantedBy"]?.jsonPrimitive?.contentOrNull,
            at = System.currentTimeMillis(),
        )
        changed = true
        notes += "granted " + nodeId + "/" + scope + (if (agentKey != null) "/" + agentKey else "") +
            (if (wsId != null) "/" + wsId else "") + " [" + access + "]" +
            (if (isMyDevice) " (myDevice)" else "") + (if (delegatedApproval) " (delegatedApproval)" else "")
    }

    (obj["revoke"] as? JsonObject)?.let { r ->
        val nodeId = r["nodeId"]?.jsonPrimitive?.contentOrNull
        val scope = r["scope"]?.jsonPrimitive?.contentOrNull
        if (nodeId == null || scope == null || scope !in AgentGrantScopes.VALUES) {
            respond(exchange, 400, errorJson("bad-request", "revoke 需要 nodeId 与 scope（all / agent / workspace）"))
            return
        }
        val agentKey = r["agentKey"]?.jsonPrimitive?.contentOrNull
        val wsId = r["wsId"]?.jsonPrimitive?.contentOrNull
        val removed = grants.revoke(nodeId, scope, agentKey, wsId)
        changed = true
        notes += "revoked " + nodeId + "/" + scope + "（移除 " + removed + " 条；只拦未来投递，已送达的数据不回收）"
    }

    (obj["grantReply"] as? JsonObject)?.let { rp ->
        val nodeId = rp["nodeId"]?.jsonPrimitive?.contentOrNull
        val state = rp["state"]?.jsonPrimitive?.contentOrNull
        val scope = rp["scope"]?.jsonPrimitive?.contentOrNull
        if (nodeId == null || state == null || state !in AgentGrantStates.ALL ||
            scope == null || scope !in AgentGrantScopes.VALUES
        ) {
            respond(exchange, 400, errorJson("bad-request", "grantReply 需要 nodeId、state 与 scope（取值见 AgentGrantStates / AgentGrantScopes）"))
            return
        }
        val agentKey = rp["agentKey"]?.jsonPrimitive?.contentOrNull
        val wsId = rp["wsId"]?.jsonPrimitive?.contentOrNull
        val access = rp["access"]?.jsonPrimitive?.contentOrNull
        val isMyDevice = (rp["isMyDevice"] as? JsonPrimitive)?.booleanOrNull
        val delegatedApproval = (rp["delegatedApproval"] as? JsonPrimitive)?.booleanOrNull
        // 发帧只有一份实现（面板与这里共用同一份）：cap 门控、等 ACK、区分
        // "未发送"与"发失败"都在 sendGrantReply 里。notes 直接用它那句如实的回执 ——
        // 对端没声明 agr1 时写的是"未发送"，绝不写成 sent。
        val outcome = CarrierHold.hold("send.grantReply", CarrierHold.SEND_MS) {
            sendGrantReply(nodeId, state, scope, agentKey, wsId, access, isMyDevice, delegatedApproval)
        }
        notes += outcome.note
        // **`grantReply` 也算一次变更。**
        //
        // 漏掉这一行会让「只回帧、不改台账」的调用被判 400：
        // 路由末尾用 `changed` 判断「有没有做过任何事」，而 grantReply 分支从头到尾
        // 没碰过它 ⇒ 明明发出去了却报 `body 至少要带 policy / grant / revoke / grantReply 之一`。
        // 那句话还会反过来误导调用方以为是自己没带字段。
        changed = true
    }

    if (!changed) {
        respond(exchange, 400, errorJson("bad-request", "body 至少要带 policy / grant / revoke / grantReply 之一"))
        return
    }
    val snap = grants.snapshot()
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", true)
            put("policy", snap.policy)
            putJsonArray("notes") { notes.forEach { add(JsonPrimitive(it)) } }
        },
    )
}
