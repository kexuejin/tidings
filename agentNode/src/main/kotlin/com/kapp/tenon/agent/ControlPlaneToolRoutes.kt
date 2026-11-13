// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * agent 工具面（P3）：`GET/POST /v1/agent/tools`。
 *
 * ## 它补的是哪条断链
 *
 * 此前控制面只有派单、事件、审批、授权、项目目录 —— **没有任何「改编制 / 改档案」的路由**。
 * 于是 agent 能干活、能读群、能开会话，却不能给自己的队伍加一个人：数字员工和聊天机器人
 * 的分界线就在这一步。
 *
 * ## 四道闸，顺序不能换
 *
 * 1. **工具白名单**：动作名不在 [RosterTools.ALL] 里 = 400，并列出可用的名字
 *    （「猜一个动作名」不该有反馈循环）。
 * 2. **能力在位**（[RosterWritePort]）：null = 这套宿主没有档案面（headless 单进程），
 *    503 如实说缺，不假装能读能写。
 * 3. **目标存在**：员工/团队档案不存在 = 404，**在问机主之前**就拦掉（问一件注定做不成
 *    的事是浪费人的注意力）。只读动作只做这一步。
 * 4. **授权档**（[RosterWritePolicy]）：`request`（默认）= 每次弹审批卡、机主点了同意才写；
 *    `auto` = 机主已明确放行，直接写。档位只读文件 —— agent 拿不到写入口，不能给自己放行。
 *    **只读动作（`mutates = false`）不过这道闸**：读不改变世界，问一次人只是噪声。
 *
 * ## 审批复用既有链路，不新造一张卡
 *
 * `sendAgentApproval` + `recordApproval` + `addApprovalListener` 这套已经被 hook 的
 * PreToolUse 用了一整年（免批表、超时即拒、长轮询唤醒都在里面）。这里照抄同一套：
 * 卡上写的是**这次到底要改谁、改成什么**，机主拒绝时不写任何东西。
 *
 * 审批卡需要一个「抬眼就能看到」的设备：与 hook 同一条挑选规则（在线且声明 `agq1`）。
 * 一台都没有 = 没人能批 ⇒ 按拒绝处理（fail-closed），并说清「先把手机/桌面连上」。
 *
 * ## 信任边界（写下来，别让它变成"以为"）
 *
 * 这条路由的鉴权是控制面自己的 token（回环 + token，见 `authorized`）。**拿到 token 的
 * 本地进程就代表本机**，所以 body 里的 `agentKey` 只用于归属与审计，**不是**鉴权主体 ——
 * 与 `/v1/agent/hook/…` 那条通道同一条边界。真正的闸是「机主要点同意」（`request` 档）
 * 与「端口只允许改名字 / 职责 / 名册」（[RosterWritePort] 的签名）。
 */

/** `GET /v1/agent/tools` —— 工具目录 + 当前档位 + 写面是否装配。agent 先读它再决定怎么调。 */
internal fun ControlPlane.listTools(exchange: HttpExchange) {
    respond(
        exchange,
        200,
        buildJsonObject {
            put("tier", rosterWritePolicy.tier())
            // 非 null = 档位文件坏了、当前按 request 跑。调用方要能看见这件事，
            // 否则「我明明配了 auto」会变成一个查不出来的悬案。
            put("policyError", rosterWritePolicy.error())
            put("writer", if (rosterWriter != null) "ready" else "missing")
            // 用法简报：agent 读一次目录就知道「这些动作是干什么的、写要不要等批」。
            // 与 tools 同源生成（[RosterTools.brief]），不另写一份说明。
            put("brief", RosterTools.brief())
            putJsonArray("tools") {
                RosterTools.ALL.forEach { spec ->
                    addJsonObject {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("mutates", spec.mutates)
                        putJsonArray("args") {
                            spec.args.forEach { arg ->
                                addJsonObject {
                                    put("name", arg.name)
                                    put("description", arg.description)
                                    put("required", arg.required)
                                    put("shape", arg.shape.name.lowercase())
                                    arg.values?.let { values ->
                                        putJsonArray("values") { values.forEach { add(JsonPrimitive(it)) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** `GET /v1/agent/tools/employees` —— 本机员工档案快照（工具面拿 `employeeId` 的唯一来源）。 */
internal fun ControlPlane.listToolEmployees(exchange: HttpExchange) {
    val port = rosterWriter
    if (port == null) {
        respond(exchange, 503, errorJson("no-writer", "这套宿主没有档案面（:agentNode 单进程 / 未装配）"))
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            putJsonArray("employees") {
                port.employees().forEach { e ->
                    addJsonObject {
                        put("employeeId", e.employeeId)
                        put("agentId", e.agentId)
                        put("name", e.name)
                        put("duty", e.duty)
                    }
                }
            }
        },
    )
}

/**
 * `POST /v1/agent/tools` —— 调一个工具。
 *
 * body：`{"tool":"team.create","args":{…},"agentKey"?:"…","sessionId"?:"…"}`
 *
 * 响应始终带 `tool` 与 `status`；`applied` 带前后值（回执就是「改了什么」），
 * 拒绝带 `code` + 一句人话。HTTP 码只区分「请求不对 400 / 没有能力 503 /
 * 目标不存在 404 / 授权没过 403 / 做成了 200」—— **不拿 500 兜业务拒绝**。
 */
internal fun ControlPlane.invokeTool(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val toolName = obj["tool"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val spec = RosterTools.specOf(toolName)
    if (spec == null) {
        respond(
            exchange, 400,
            errorJson(
                "bad-request",
                "不认识的 tool「" + toolName + "」；可用：" + RosterTools.ALL.joinToString(" / ") { it.name },
            ),
        )
        return
    }
    val call = RosterTools.parseCall(spec, obj["args"] as? JsonObject).getOrElse { e ->
        respond(exchange, 400, errorJson("bad-request", e.message ?: "参数不合法"))
        return
    }
    val port = rosterWriter
    if (port == null) {
        respond(exchange, 503, errorJson("no-writer", "这套宿主没有档案面（:agentNode 单进程 / 未装配）"))
        return
    }

    val agentKey = obj["agentKey"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val sessionHint = obj["sessionId"]?.jsonPrimitive?.contentOrNull?.trim()

    when (call) {
        // 只读：仍然过 `gate`，只是 `mutates = false` 让它直接放行。
        // **判定只住一处**（`spec.mutates`）—— 在分支里另写一句「读不需要审批」，
        // 就和目录里的 `mutates` 成了两份判据，哪天一方改了另一方不会跟着变。
        is RosterTools.Call.TeamRead -> gate(exchange, spec, agentKey, sessionHint, toolName) {
            readTeams(exchange, port, call)
        }

        is RosterTools.Call.EmployeeUpdate -> {
            val before = port.employees().firstOrNull { it.employeeId == call.employeeId }
            if (before == null) {
                respond(
                    exchange, 404,
                    errorJson(
                        "unknown-employee",
                        "本机没有档案「" + call.employeeId + "」；先 GET /v1/agent/tools/employees 取 employeeId",
                    ),
                )
                return
            }
            gate(exchange, spec, agentKey, sessionHint, employeeSummary(call, before)) { approvalId ->
                executeEmployee(exchange, toolName, port, call, agentKey, approvalId)
            }
        }

        is RosterTools.Call.TeamCreate -> {
            gate(exchange, spec, agentKey, sessionHint, teamSummary(call, null)) { approvalId ->
                executeTeam(exchange, toolName, port, call, agentKey, approvalId)
            }
        }

        is RosterTools.Call.TeamUpdate -> {
            val before = port.teams().firstOrNull { it.teamId == call.teamId }
            if (before == null) {
                respond(
                    exchange, 404,
                    errorJson(
                        "unknown-team",
                        "本机没有团队档案「" + call.teamId + "」；先调 team.roster.read 取 teamId",
                    ),
                )
                return
            }
            gate(exchange, spec, agentKey, sessionHint, teamSummary(call, before)) { approvalId ->
                executeTeam(exchange, toolName, port, call, agentKey, approvalId)
            }
        }
    }
}

/** 只读动作：把快照回给 agent（给了 `teamId` 却查不到 = 404，不静默回空表）。 */
private fun ControlPlane.readTeams(
    exchange: HttpExchange,
    port: RosterWritePort,
    call: RosterTools.Call.TeamRead,
) {
    val all = port.teams()
    val selected = call.teamId?.let { id -> all.filter { it.teamId == id } } ?: all
    if (call.teamId != null && selected.isEmpty()) {
        respond(
            exchange, 404,
            errorJson("unknown-team", "本机没有团队档案「" + call.teamId + "」；不带 teamId 可以读全部"),
        )
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("tool", RosterTools.TEAM_READ)
            put("status", "ok")
            putJsonArray("teams") {
                selected.forEach { team ->
                    addJsonObject {
                        put("teamId", team.teamId)
                        put("name", team.name)
                        put("leaderAgentId", team.leaderAgentId)
                        put("rev", team.rev)
                        putJsonArray("groupIds") { team.groupIds.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("members") {
                            team.members.forEach { member ->
                                addJsonObject {
                                    put("agentId", member.agentId)
                                    put("name", member.name)
                                    put("role", member.role)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * 过授权档：`auto` 直接跑；`request` 先弹卡再跑（拒绝/超时就把 403 写出去，`block` 不执行）。
 */
private fun ControlPlane.gate(
    exchange: HttpExchange,
    spec: RosterTools.Spec,
    agentKey: String?,
    sessionHint: String?,
    summary: String,
    block: (approvalId: String?) -> Unit,
) {
    val tier = rosterWritePolicy.tier()
    if (!spec.mutates || tier != RosterWritePolicy.Tiers.REQUEST) {
        block(null)
        return
    }
    val decision = awaitRosterApproval(
        exchange = exchange,
        toolName = spec.name,
        agentKey = agentKey,
        summary = summary,
        sessionHint = sessionHint,
    ) ?: return
    if (!decision.approved) {
        respond(
            exchange, 403,
            buildJsonObject {
                put("tool", spec.name)
                put("status", "denied")
                put("approvalId", decision.approvalId)
                put("reason", decision.reason)
            },
        )
        return
    }
    block(decision.approvalId)
}

/** 员工档案落写：一次调用只有这一处执行点（成功/拒绝的文案也在这里）。 */
private fun ControlPlane.executeEmployee(
    exchange: HttpExchange,
    toolName: String,
    port: RosterWritePort,
    call: RosterTools.Call.EmployeeUpdate,
    agentKey: String?,
    approvalId: String?,
) {
    when (val result = port.updateEmployee(agentKey, call.employeeId, call.name, call.duty)) {
        is RosterWriteResult.Applied -> {
            // 审计：谁改的、改了什么。写路径那边只记 rev，工具面这一层才是「agent 主动改」的证据。
            println(
                "[roster-tool] applied " + toolName + " by agent=" + (agentKey ?: "(unnamed)") +
                    " approval=" + (approvalId ?: "auto") +
                    " " + result.before.name + " -> " + result.after.name +
                    " / " + result.before.duty + " -> " + result.after.duty,
            )
            respond(
                exchange, 200,
                buildJsonObject {
                    put("tool", toolName)
                    put("status", "applied")
                    put("approvalId", approvalId)
                    put("before", employeeJson(result.before))
                    put("after", employeeJson(result.after))
                },
            )
        }
        is RosterWriteResult.TeamApplied -> denied(exchange, toolName, approvalId, RosterDenial.OTHER, "写面回了一份团队结果")
        is RosterWriteResult.Denied -> denied(exchange, toolName, approvalId, result.reason, result.detail)
    }
}

/** 团队档案落写：建 / 改共用（`before == null` 就是建）。 */
private fun ControlPlane.executeTeam(
    exchange: HttpExchange,
    toolName: String,
    port: RosterWritePort,
    call: RosterTools.Call,
    agentKey: String?,
    approvalId: String?,
) {
    val result = when (call) {
        is RosterTools.Call.TeamCreate -> port.createTeam(agentKey, call.draft)
        is RosterTools.Call.TeamUpdate -> port.updateTeam(agentKey, call.teamId, call.name, call.members)
        else -> RosterWriteResult.Denied(RosterDenial.ARGUMENT_INVALID, "not-a-team-call")
    }
    when (result) {
        is RosterWriteResult.TeamApplied -> {
            println(
                "[roster-tool] applied " + toolName + " by agent=" + (agentKey ?: "(unnamed)") +
                    " approval=" + (approvalId ?: "auto") +
                    " team=" + result.after.teamId + " (" + result.after.name + ")" +
                    " rev=" + (result.before?.rev?.toString() ?: "-") + " -> " + result.after.rev +
                    " groups=" + result.after.groupIds.size + " members=" + result.after.members.size,
            )
            respond(
                exchange, 200,
                buildJsonObject {
                    put("tool", toolName)
                    put("status", "applied")
                    put("approvalId", approvalId)
                    // before = null 时明确写出来：新建与「改了一版」在回执里必须分得开。
                    result.before?.let { put("before", teamJson(it)) }
                    put("after", teamJson(result.after))
                },
            )
        }
        is RosterWriteResult.Applied -> denied(exchange, toolName, approvalId, RosterDenial.OTHER, "写面回了一份员工结果")
        is RosterWriteResult.Denied -> denied(exchange, toolName, approvalId, result.reason, result.detail)
    }
}

private fun ControlPlane.denied(
    exchange: HttpExchange,
    toolName: String,
    approvalId: String?,
    reason: RosterDenial,
    detail: String?,
) {
    respond(
        exchange, 403,
        buildJsonObject {
            put("tool", toolName)
            put("status", "denied")
            put("approvalId", approvalId)
            put("code", reason.name)
            put("reason", denialText(reason, detail))
        },
    )
}

/** 拒绝码 → 一句话（agent 与人都读这一句；分类码同时在 `code` 里）。 */
private fun denialText(reason: RosterDenial, detail: String?): String = when (reason) {
    RosterDenial.UNKNOWN_EMPLOYEE ->
        "本机没有档案「" + (detail ?: "?") + "」（可能刚被注销；先 GET /v1/agent/tools/employees 重新取）"
    RosterDenial.UNKNOWN_TEAM ->
        "本机没有团队档案「" + (detail ?: "?") + "」（可能刚被解散；先调 team.roster.read）"
    RosterDenial.NAME_INVALID -> "名字不合法：空、超长，或与本机另一份档案撞了"
    RosterDenial.ROSTER_INVALID -> "名册没过校验：空名、重复、非 slug，或超员（role 不能为空）"
    RosterDenial.ARGUMENT_INVALID -> "这次调用没有要改的字段，或改成了非法形态（名册不能为空）"
    RosterDenial.NOT_MEMBER -> "发起方不在目标群里，跨群重建快照被拒"
    RosterDenial.PERSIST_REJECTED -> "落盘被拒，什么都没改"
    RosterDenial.OTHER -> "写面拒绝：" + (detail ?: "未知原因")
}

private fun employeeJson(e: RosterEmployeeSnapshot): JsonObject = buildJsonObject {
    put("employeeId", e.employeeId)
    put("agentId", e.agentId)
    put("name", e.name)
    put("duty", e.duty)
}

private fun teamJson(t: RosterTeamSnapshot): JsonObject = buildJsonObject {
    put("teamId", t.teamId)
    put("name", t.name)
    put("leaderAgentId", t.leaderAgentId)
    put("rev", t.rev)
    putJsonArray("groupIds") { t.groupIds.forEach { add(JsonPrimitive(it)) } }
    putJsonArray("members") {
        t.members.forEach { member ->
            addJsonObject {
                put("agentId", member.agentId)
                put("name", member.name)
                put("role", member.role)
            }
        }
    }
}

/**
 * 卡片正文：一眼能读出「谁的手，要改谁、改成什么」。纯事实，不替用户判断该不该同意。
 *
 * 团队动作的正文要把**代价**写出来（进不进群、名册几个人）—— 机主批「建一支 3 人队」
 * 与批「改个名字」是两种决定。
 */
private fun employeeSummary(call: RosterTools.Call.EmployeeUpdate, before: RosterEmployeeSnapshot): String {
    val who = if (before.name.isNotBlank()) before.name + "（" + before.employeeId + "）" else before.employeeId
    val parts = mutableListOf<String>()
    call.name?.let { parts += "名字 → " + it }
    call.duty?.let { parts += "职责 → " + it }
    return "employee.update：" + who + " " + parts.joinToString("，")
}

private fun teamSummary(call: RosterTools.Call, before: RosterTeamSnapshot?): String = when (call) {
    is RosterTools.Call.TeamCreate -> buildString {
        append("team.create：建一支「").append(call.draft.name).append("」")
        append("，领队 ").append(call.draft.leaderAgentId)
        append("，").append(call.draft.members.size).append(" 个组员（")
        append(call.draft.members.joinToString("、") { it.name }).append("）")
        if (call.draft.groupIds.isEmpty()) {
            append("；不进任何群（群里看不见、@ 不到）")
        } else {
            append("；加进 ").append(call.draft.groupIds.size).append(" 个群")
        }
    }
    is RosterTools.Call.TeamUpdate -> {
        val who = before?.let { it.name + "（" + it.teamId + "）" } ?: call.teamId
        val parts = mutableListOf<String>()
        call.name?.let { parts += "名字 → " + it }
        call.members?.let { parts += "名册 → " + it.size + " 人（" + it.joinToString("、") { m -> m.name } + "）" }
        "team.update：" + who + " " + parts.joinToString("，") +
            (before?.let { "；将同步到 " + it.groupIds.size + " 个群" } ?: "")
    }
    else -> call.toString()
}

private data class RosterApprovalDecision(
    val approved: Boolean,
    val approvalId: String?,
    val reason: String,
)

/**
 * 弹卡并等机主决定。返回 null = 已经就地回过响应（没有可批的设备 / 送不出去），调用方直接收工。
 *
 * 三种结局都要能区分：同意 / 拒绝 / 超时（超时按拒绝，reason 写「超时」而不是「被拒绝」——
 * 这两件事对排查一个「点了没反应」的现场差别很大）。
 */
private fun ControlPlane.awaitRosterApproval(
    exchange: HttpExchange,
    toolName: String,
    agentKey: String?,
    summary: String,
    sessionHint: String?,
): RosterApprovalDecision? {
    val peer = pickHookPeer(runtime.peerList())
    if (peer == null) {
        respond(
            exchange, 503,
            errorJson(
                "no-approver",
                "request 档下写操作需要机主批准，但现在没有在线且声明审批能力的设备；" +
                    "先把手机/桌面连上再来（按拒绝处理，什么都没写）",
            ),
        )
        return null
    }
    // 没有会话对齐键就用自己的池键：卡片挂在自己的位置上，不去冒别人的会话。
    // （常量写在这里而不是文件顶部：路由文件不许声明属性，见 ControlPlaneRouteSplitTest。）
    val sessionId = sessionHint?.takeIf { hint -> sessions.sessionOrNull(runtime.nodeId, hint) != null }
        ?: "agy-roster-tool"
    val approvalId = "roster-" + java.util.UUID.randomUUID()
    val expiresAt = System.currentTimeMillis() + rosterApprovalTtlMs
    val choices = defaultApprovalChoices
    val delivered = runCatching {
        CarrierHold.hold("rosterTool.approvalSend", CarrierHold.SEND_MS) {
            runtime.sendAgentApproval(
                peer, approvalId, sessionId, toolName, summary, expiresAt, choices, riskLevel = null, diff = null,
            )
        }
    }
    if (delivered.isFailure) {
        sessions.recordApproval(
            peer, sessionId, approvalId, toolName, summary, expiresAt, choices,
            status = "denied", outbound = true,
        )
        respond(
            exchange, 503,
            errorJson("approval-undeliverable", "审批卡没送出去：" + (delivered.exceptionOrNull()?.message ?: "unknown") + "；按拒绝处理"),
        )
        return null
    }
    sessions.recordApproval(peer, sessionId, approvalId, toolName, summary, expiresAt, choices, status = "sent", outbound = true)
    println("[roster-tool] awaiting approval id=" + approvalId + " peer=" + peer + " agent=" + (agentKey ?: "(unnamed)") + " :: " + summary)

    val signal = CompletableDeferred<String>()
    val dispose = sessions.addApprovalListener { id, status -> if (id == approvalId) signal.complete(status) }
    val finalStatus = try {
        CarrierHold.hold("rosterTool.approvalLongPoll", budgetMs = rosterApprovalTtlMs) {
            withTimeoutOrNull(rosterApprovalTtlMs) { signal.await() }
        }
    } finally {
        dispose()
    }
    return when (finalStatus) {
        null -> RosterApprovalDecision(false, approvalId, "审批超时（" + rosterApprovalTtlMs + "ms）—— 按拒绝处理，什么都没写")
        in setOf("approved", "allow_session") -> RosterApprovalDecision(true, approvalId, "approved")
        else -> RosterApprovalDecision(false, approvalId, "机主拒绝（" + finalStatus + "）")
    }
}
