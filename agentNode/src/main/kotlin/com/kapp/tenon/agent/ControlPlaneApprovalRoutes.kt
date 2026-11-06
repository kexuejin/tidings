// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 审批回执（/v1/agent/approval）。
 *
 * 设备点下「允许 / 拒绝」之后的那一步：校验回执的授权、落「始终允许」的规则、把决定
 * 发回宿主，并把结果记进会话台账。
 *
 * **授权判据不在这里**：`ControlPlane.init` 装的 `sessions.approvalAuthority` 是唯一判据
 * （fail-closed），这条路由只负责「按判据执行」。两处都判 = 两套答案。
 *
 * §6.5 把审批标成关键控制路径，并规定它**不得**降级为「已送达」：所以回执仍是
 * `deliverAgentFrame` 的 `sent` / `queued`（那是**传输**状态），审批本身的状态在台账里是
 * `status`，由 §6.8 的 `agent-ack` 推进。
 */

/**
 * `POST /v1/agent/approval` —— 请对端确认一个受控动作（§6.5，cap `agq1`）。
 *
 * §6.5 把它标成「⚠️ 关键控制路径」，并规定审批**不得**降级为「已送达」：所以
 * 这里的回执仍是 `deliverAgentFrame` 的 `sent` / `queued`（那是**传输**状态），
 * 审批本身的状态在台账里是 `status`，由 §6.8 的 `agent-ack` 推进。
 */

internal fun ControlPlane.agentApproval(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_QUEUE, requireRw = true) ?: return
    val approvalId = obj["approvalId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (approvalId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 approvalId（§6.5 的必填字段）"))
        return
    }
    val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (sessionId.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.5 的必填字段）"))
        return
    }
    val expiresAt = numberOrNull(obj["expiresAt"])
    if (expiresAt == null || expiresAt < 0) {
        respond(
            exchange,
            400,
            errorJson("bad-request", "expiresAt 必须是非负整数（§6.5：审批的过期时刻，epoch ms）"),
        )
        return
    }
    val tool = obj["tool"]?.jsonPrimitive?.contentOrNull
    val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
    val choices = obj["choices"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: defaultApprovalChoices
    val riskLevel = obj["riskLevel"]?.jsonPrimitive?.contentOrNull
    val diff = obj["diff"]?.jsonPrimitive?.contentOrNull
    // **「始终允许」在入口短路**：对端点过一次「这个 tool 以后免审」，此后同一
    // (设备, agent, tool) 的请求不再打扰它 —— 这正是那个按钮的全部意义。
    //
    // 为什么必须在这里、而不是等对端再答一次：免批的用户价值就是"别再问我"。
    // 仍然发一帧过去再让对端自动回一条，等于把"不打扰"做成了"打扰两次"。
    //
    // 为什么 `status` 是新值 `auto-approved` 而不是沿用 `sent`：`sent` 的意思是
    // "帧已经交给传输层"，而这里**什么都没发**。让机器字段说谎，下一个读它的人
    // （或插件）就会以为对端已经知道这件事 —— 本仓在 `已送达 ≠ 对方知道` 上
    // 已经付过一次代价。`autoApproved: true` 给不需要读字符串的调用方。
    if (toolApprovals.isAlwaysApproved(
        nodeId = nodeId,
        // 与审批授权判据取同一个字段（`agentId ?: agentKind`）—— 两处取不同的名字，
        // 同一条会话就会在"谁能批"与"免批记给谁"上被判成两个 agent。
        agentKey = sessions.sessionOrNull(nodeId, sessionId)?.let { it.agentId ?: it.agentKind },
        tool = tool,
    )) {
        // 台账里记成**已批准**（`outbound = false`：这帧没有出门，是宿主按策略批的）。
        // 记下来才有审计：否则"这个动作谁批的"在台账里是一片空白。
        sessions.recordApproval(
            nodeId = nodeId,
            sessionId = sessionId,
            approvalId = approvalId,
            tool = tool,
            summary = summary,
            expiresAt = expiresAt,
            choices = choices,
            status = "approved",
            outbound = false,
        )
        respond(
            exchange,
            200,
            buildJsonObject {
                put("status", "auto-approved")
                put("to", nodeId)
                put("type", MsgTypes.AGENT_APPROVAL)
                put("autoApproved", true)
                put("note", "matched an 'always allow' rule for tool=" + (tool ?: "") + "; approved locally, not delivered")
            },
        )
        return
    }
    deliverAgentFrame(
        exchange = exchange,
        nodeId = nodeId,
        type = MsgTypes.AGENT_APPROVAL,
        payload = Codec.agentApprovalPayload(approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff),
        send = { runtime.sendAgentApproval(nodeId, approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff) },
        onSent = {
            sessions.recordApproval(
                nodeId = nodeId,
                sessionId = sessionId,
                approvalId = approvalId,
                tool = tool,
                summary = summary,
                expiresAt = expiresAt,
                choices = choices,
                outbound = true,
            )
        },
    )
}
