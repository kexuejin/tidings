// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.kapp.tidings.core.protocol.CodecWire.hasOnlyKeys
import com.kapp.tidings.core.protocol.CodecWire.intOf
import com.kapp.tidings.core.protocol.CodecWire.isStr
import com.kapp.tidings.core.protocol.CodecWire.utf8ByteLength

/**
 * 跨端派单网道（M4）的编解码：`agent-invoke` / `agent-invoke-result` 两帧（cap `agi1`）。
 *
 * 从 `CodecAgent` 分出的理由与 `CodecMsg` / `CodecGroup` 同一条：这一族的演化动力
 * （租约、授权应答、看板回传）与 PPE-1 §6 的会话帧互不相干。
 *
 * **严格白名单**：两帧都多一键整帧拒收（与 group-text 同一条宪法，AGENTS §2.2）。
 * `op` 两态的字段互斥（task 禁 action 字段、action 禁 prompt）—— 混写说明发送方语义
 * 混乱，宁可拒收也不猜。
 */
object CodecInvoke {

    /** `op` 取值：派单执行 / 卡片动作转发。封闭集合 —— 多一个值就多一种没人认识的语义。 */
    object InvokeOps {
        const val TASK = "task"
        const val ACTION = "action"
        val ALL: Set<String> = setOf(TASK, ACTION)
    }

    /**
     * 结果状态机。封闭集合：状态是协议事实，不是自由文本（与 `agentState` 四态同判）。
     *
     * - `done`：执行完成（`ok=true`，可带产出卡片）；
     * - `denied`：目标节点授权门禁拒绝（机主未批）；
     * - `no-executor`：目标节点声明了 `agi1` 但没有执行器（手机被误指名的诚实应答）；
     * - `no-plugin`：目标节点上没有这个 agentId 的插件；
     * - `timeout`：目标节点侧的租约先到期；
     * - `error`：执行中出错（`error` 键带原因）。
     */
    object InvokeResultStates {
        const val DONE = "done"
        const val DENIED = "denied"
        const val NO_EXECUTOR = "no-executor"
        const val NO_PLUGIN = "no-plugin"
        const val TIMEOUT = "timeout"
        const val ERROR = "error"
        val ALL: Set<String> = setOf(DONE, DENIED, NO_EXECUTOR, NO_PLUGIN, TIMEOUT, ERROR)
    }

    private val INVOKE_EXPECTED_KEYS = setOf(
        "invokeId", "op", "agentId", "prompt", "actionId", "cardId", "cardRev",
        "sessionId", "groupId", "groupRev", "wsId", "deadlineMs", "requestedBy", "decidedBy", "at",
    )
    private val RESULT_EXPECTED_KEYS = setOf("invokeId", "ok", "status", "summary", "error", "card", "at")

    // ---------------------------------------------------------------- validate

    fun validateInvoke(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, INVOKE_EXPECTED_KEYS)) return false
        if (!isStr(o["invokeId"], Limits.AGENT_RUN_ID)) return false
        if (!isStr(o["agentId"], Limits.AGENT_ID)) return false
        if (!isStr(o["requestedBy"], Limits.FROM)) return false
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (op !in InvokeOps.ALL) return false
        // 租约是本帧的存在理由（§7.3-1）：没有期限的派单等于永远等，期限必须为正。
        val deadline = intOf(o["deadlineMs"]) ?: return false
        if (deadline <= 0) return false
        if (o.containsKey("groupId") && !isStr(o["groupId"], Limits.ID)) return false
        if (o.containsKey("wsId") && !isStr(o["wsId"], Limits.AGENT_WS_ID)) return false
        if (o.containsKey("sessionId") && !isStr(o["sessionId"], Limits.AGENT_SESSION_ID)) return false
        return when (op) {
            InvokeOps.TASK -> {
                // task 的正文就是任务本身；action 的三个字段在 task 上没有任何意义。
                val prompt = (o["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (prompt.isNullOrEmpty()) return false
                if (utf8ByteLength(prompt) > Limits.AGENT_PROMPT) return false
                !o.containsKey("actionId") && !o.containsKey("cardId") &&
                    !o.containsKey("cardRev") && !o.containsKey("decidedBy")
            }
            InvokeOps.ACTION -> {
                // 动作必须有身份（哪张卡、哪颗按钮）与决定人（谁批的）；正文在这里没有意义。
                if (o.containsKey("prompt")) return false
                val cardRev = intOf(o["cardRev"])
                isStr(o["actionId"], Limits.AGENT_RUN_ID) &&
                    isStr(o["cardId"], Limits.AGENT_RUN_ID) &&
                    isStr(o["decidedBy"], Limits.FROM) &&
                    cardRev != null && cardRev >= 0
            }
            else -> false
        }
    }

    fun validateInvokeResult(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, RESULT_EXPECTED_KEYS)) return false
        if (!isStr(o["invokeId"], Limits.AGENT_RUN_ID)) return false
        val ok = (o["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.content == "true" } ?: return false
        val status = (o["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (status !in InvokeResultStates.ALL) return false
        // ok 与 status 必须自洽：done 才是真，其余全是失败 —— 一帧自相矛盾的结果
        // 比丢帧更糟（发起方会把失败当成功记进看板）。
        if (ok != (status == InvokeResultStates.DONE)) return false
        // 卡片是执行产出，只在 done 时存在 —— 被拒的派单不该带执行卡片。
        if (o.containsKey("card")) {
            if (status != InvokeResultStates.DONE) return false
            val card = o["card"] as? JsonObject ?: return false
            if (utf8ByteLength(card.toString()) > Limits.CARD_BYTES) return false
        }
        if (o.containsKey("summary")) {
            val summary = (o["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            if (utf8ByteLength(summary) > Limits.AGENT_INVOKE_SUMMARY) return false
        }
        if (o.containsKey("error")) {
            val error = (o["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            if (utf8ByteLength(error) > Limits.AGENT_NOTE) return false
        }
        return true
    }

    // ---------------------------------------------------------------- build

    fun agentInvokePayload(
        invokeId: String,
        op: String,
        agentId: String,
        requestedBy: String,
        deadlineMs: Long,
        prompt: String? = null,
        actionId: String? = null,
        cardId: String? = null,
        cardRev: Int? = null,
        decidedBy: String? = null,
        sessionId: String? = null,
        groupId: String? = null,
        groupRev: Int? = null,
        wsId: String? = null,
        at: Long? = null,
    ): JsonElement = buildJsonObject {
        put("invokeId", invokeId)
        put("op", op)
        put("agentId", agentId)
        if (prompt != null) put("prompt", prompt)
        if (actionId != null) put("actionId", actionId)
        if (cardId != null) put("cardId", cardId)
        if (cardRev != null) put("cardRev", cardRev)
        if (decidedBy != null) put("decidedBy", decidedBy)
        if (sessionId != null) put("sessionId", sessionId)
        if (groupId != null) put("groupId", groupId)
        if (groupRev != null) put("groupRev", groupRev)
        if (wsId != null) put("wsId", wsId)
        put("deadlineMs", deadlineMs)
        put("requestedBy", requestedBy)
        if (at != null) put("at", at)
    }

    fun agentInvokeResultPayload(
        invokeId: String,
        ok: Boolean,
        status: String,
        summary: String? = null,
        error: String? = null,
        card: JsonObject? = null,
        at: Long? = null,
    ): JsonElement = buildJsonObject {
        put("invokeId", invokeId)
        put("ok", ok)
        put("status", status)
        if (summary != null) put("summary", summary)
        if (error != null) put("error", error)
        if (card != null) put("card", card)
        if (at != null) put("at", at)
    }
}
