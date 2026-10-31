// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

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
 */
object CliLineEventMapper {

    fun map(line: String): DriverEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val obj = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return textOf(obj)?.let { DriverEvent.TokenDelta(it) }
        return when (type) {
            "error" -> DriverEvent.Error(
                (obj["message"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
                    ?: "cli reported an error",
            )
            "result", "done" -> null
            else -> textOf(obj)?.let { DriverEvent.TokenDelta(it) }
        }
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
