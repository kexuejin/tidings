// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * CLI 输出行 → [DriverEvent] 的映射（T-3b 从 `CliSubprocessDriver.mapLine` 原样抽出）：
 * 沙箱驱动（[SandboxedCliDriver]）与裸驱动（[CliSubprocessDriver]）共用同一份行语义
 * —— 两处各写一份迟早漂（本仓「多处必须一致」纪律）。
 *
 * 语义不变式（`DriverHonestyTest` / `CliSubprocessDriverTest` 钉着的）：
 * - 认不出的行（CLI 的 stderr 噪声、私有格式、未来新增的行型）**如实跳过**，绝不 crash；
 * - 行级的 "result"/"done" 只做参考，完成与否的裁决在退出码，不在这里。
 *
 * agy（Antigravity CLI，形态 A）的行形状来自**真 CLI 实测**（stream-json 信封
 * `{"event":…}`，与 claude/codex 的 `{"type":…}` 无键交集，互不干扰）：
 * - `init` → 跳过（conversation_id 是会话锚，裸驱动不用它寻址）；
 * - `step_update`：`text_delta` 非空 → TokenDelta；工具类 step（step_type 不是
 *   user_input/agent_response）ACTIVE → ToolCall（DONE → null，完成与否不在行上）；
 * - `result`：status 非 SUCCESS → Error（CLI 自报失败就算退出码 0 —— 不为没发生的
 *   活报完成；SUCCESS → null，裁决归退出码）。
 */
object CliLineEventMapper {

    /** agy 的两类「内容步」：它们的推进随 text_delta 流过，不算工具调用。 */
    private val AGY_TEXT_STEP_TYPES = setOf("user_input", "agent_response")


    fun map(line: String): DriverEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val obj = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return null
        // agy 信封（形态 A）：event 键与 claude/codex 的 type 键无交集，先分流。
        (obj["event"] as? JsonPrimitive)?.contentOrNull?.let { return mapAgyEvent(obj, it) }
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return textOf(obj)?.let { DriverEvent.TokenDelta(it) }
        // opencode 信封（形态 B）：`{"type":…,"sessionID":…,"part":{…}}`。判据是 **part 对象**
        // —— 与 claude/codex 的 `{"type":…}` 顶层形状区分得开，认错行型也不会误判。
        // agy 已在上面的 `event` 键分流走，三者互不干扰。
        (obj["part"] as? JsonObject)?.let { part -> return mapOpenCode(type, part) }
        return when (type) {
            "error" -> DriverEvent.Error(errorMessageOf(obj))
            "result", "done" -> null
            // dsh（DeepSeek Harness headless --json）：工具调用行。callId/tool 都是顶层键
            // （与 claude 的 assistant.content 块、opencode 的 part 信封都不同形）。
            "tool_call" -> {
                val tool = (obj["tool"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                if (tool == null) {
                    null
                } else {
                    DriverEvent.ToolCall(
                        toolCallId = (obj["callId"] as? JsonPrimitive)?.contentOrNull ?: "dsh-tool",
                        tool = tool,
                        inputJson = "",
                        riskLevel = "L2",
                    )
                }
            }
            // dsh 的 turn 收尾行：reason.kind == "error" 时 CLI 仍会以非零码退出，
            // 但错误正文只在这行里 —— 不挖出来，调用方就只剩「cli exit=1」。
            "status" -> {
                val reason = obj["reason"] as? JsonObject
                if ((reason?.get("kind") as? JsonPrimitive)?.contentOrNull == "error") {
                    val err = reason["error"] as? JsonObject
                    DriverEvent.Error(
                        (err?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: "dsh reported an error",
                    )
                } else {
                    null
                }
            }
            else -> textOf(obj)?.takeIf { it.isNotBlank() }?.let { DriverEvent.TokenDelta(it) }
        }
    }

    /**
     * opencode（实测 v2.2.5）的行型：`text` / `tool_use` / `step_start` / `step_finish` / `error`。
     *
     * - `text` → [DriverEvent.TokenDelta]（`part.text`，就是它嘴上说的话）；
     * - `tool_use` → [DriverEvent.ToolCall]（`part.tool` 是工具名，`part.id` 是调用 id）；
     * - `step_start` / `step_finish` → null（推进的记账，没有新事实；完成与否看退出码）；
     * - 其余（含未来新增行型）→ null，如实跳过不 crash。
     */
    private fun mapOpenCode(type: String, part: JsonObject): DriverEvent? = when (type) {
        "text" -> (part["text"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { DriverEvent.TokenDelta(it) }

        "tool_use" -> {
            val tool = (part["tool"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (tool == null) {
                null
            } else {
                DriverEvent.ToolCall(
                    toolCallId = (part["id"] as? JsonPrimitive)?.contentOrNull ?: "opencode-tool",
                    tool = tool,
                    inputJson = "",
                    riskLevel = "L2",
                )
            }
        }

        else -> null
    }

    /**
     * 错误行的正文：`message` 优先，其次**嵌套的** `error.message`
     * （opencode 是 `{"type":"error","error":{"type":"provider.…","message":"…"}}`，
     * 只看顶层会退化成「cli reported an error」，把真正的原因丢掉）。
     */
    private fun errorMessageOf(obj: JsonObject): String {
        (obj["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        (obj["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val nested = (obj["error"] as? JsonObject)?.let { err ->
            (err["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return nested ?: "cli reported an error"
    }

    /** agy 的三行型：init 跳过 / step_update 按内容分流 / result 按 status 诚信报。 */
    private fun mapAgyEvent(obj: JsonObject, event: String): DriverEvent? = when (event) {
        "init" -> null
        "step_update" -> {
            val step = obj["step_update"] as? JsonObject ?: return null
            val delta = (step["text_delta"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            if (delta != null) {
                DriverEvent.TokenDelta(delta)
            } else {
                val stepType = (step["step_type"] as? JsonPrimitive)?.contentOrNull ?: return null
                val state = (step["state"] as? JsonPrimitive)?.contentOrNull
                if (state == "ACTIVE" && stepType !in AGY_TEXT_STEP_TYPES) {
                    val stepIndex = (step["step_index"] as? JsonPrimitive)?.contentOrNull ?: "?"
                    DriverEvent.ToolCall(
                        toolCallId = "step-$stepIndex",
                        tool = stepType,
                        inputJson = "",
                        riskLevel = "L2",
                    )
                } else {
                    null // 文本步骤的 DONE / 其他状态：内容已随 delta 流过，无新事实。
                }
            }
        }
        "result" -> {
            val result = obj["result"] as? JsonObject ?: return null
            val status = (result["status"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (status != "SUCCESS") {
                DriverEvent.Error("agy reported status $status (exit code is not the only honest signal)")
            } else {
                null
            }
        }
        else -> null
    }

    /** 浅探测 + claude 的 assistant 行嵌套形状；都取不到 = 这行没有文本，如实跳过。 */
    private fun textOf(obj: JsonObject): String? {
        (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        (obj["delta"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        val content = (obj["message"] as? JsonObject)?.get("content") ?: obj["content"]
        if (content is JsonArray) {
            for (item in content) {
                val entry = item as? JsonObject ?: continue
                if ((entry["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (entry["text"] as? JsonPrimitive)?.contentOrNull?.let { return it }
                }
            }
        }
        return null
    }
}
