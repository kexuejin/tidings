// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.AgentToolRuleRowPayload
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 免批规则读撤（cap `atr1`）的就地应答（从 `ControlPlane` 按资源切出的路由文件，
 * 与 grant/page 同一拆分纪律 —— 见 `ControlPlaneRouteSplitTest` 的行预算）。
 *
 * 免批表（[ControlPlane.toolApprovals]）是**节点本地事实**，与 grant 家族同一先例
 * —— 节点自己答，不需要 session-page 那套队列/宿主回填。`tool` 在场 = 撤（身份
 * 四元组逐字匹配，对不上时快照原样回 —— total 不变就是「没撤掉」，不猜不造）；
 * 缺席 = 读。两种形态都回一份**全量快照**，设备侧只认这一种对账来源。
 *
 * 撤销是收紧方向（让宿主重新开始问），不设授权闸：规则免的是「不再打扰」，收回它
 * 只会让系统更谨慎。正在等批准的那一条不受影响，已执行完的不会回滚 —— 那条边界
 * 在界面上如实写（ToolApprovalsSection 的撤销语义段）。
 */
internal suspend fun ControlPlane.observeInboundToolRules(envelope: Envelope) {
    if (envelope.type != MsgTypes.AGENT_TOOL_RULES_REQUEST) return
    val p = envelope.payload as? JsonObject ?: return
    // codec 已保证撤形态的 tool 非空、nodeId 在场；这里只做取值，不重复校验
    // （两处各判一遍迟早漂 —— 同 handleOnAgentChannel 的双应答防线注释）。
    fun field(key: String): String? = p[key]?.jsonPrimitive?.contentOrNull
    val tool = field("tool")
    if (tool != null) {
        val owner = field("nodeId") ?: return
        runCatching {
            toolApprovals.revokeExactly(
                nodeId = owner,
                agentKey = field("agentKey"),
                wsId = field("wsId"),
                tool = tool,
            )
        }
    }
    runCatching {
        runtime.sendAgentToolRulesList(
            peerId = envelope.from,
            rules = toolApprovals.snapshot().map {
                AgentToolRuleRowPayload(
                    nodeId = it.nodeId,
                    tool = it.tool,
                    agentKey = it.agentKey,
                    wsId = it.wsId,
                    grantedAt = it.grantedAt,
                    grantedBy = it.grantedBy,
                )
            },
        )
    }
}
