// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 身份恢复 L2 两张帧的载荷校验。
 *
 * - `recovery-req`：**严格空对象** —— 多一个键整帧拒收（与 group-text 白名单同一口径）。
 *   请求内容是隐含的："你我这段会话的全部"。
 * - `recovery-data`：帧级键严格白名单（`batch` / `more` / `messages`）；`messages` 的
 *   **元素**只做形状校验（必须是对象、必须有非空 `id`）—— 消息实体的完整 schema
 *   归应用层所有（`MessageUi` 住在 :app），core 不越层验字段；反序列化失败由
 *   应用层按"丢这一批"处理，不会让坏数据入库。
 */
internal object CodecRecovery {

    /** `recovery-data` 的帧级已知键；多一个键整帧拒收。 */
    val RECOVERY_DATA_KEYS = setOf("batch", "more", "messages")

    /** 单批消息条数上限：批按 64 KiB 帧预算切，条数上限只是防滥用。 */
    const val RECOVERY_DATA_MAX_MESSAGES = 128

    fun validateRecoveryReq(o: JsonObject): Boolean = o.isEmpty()

    fun validateRecoveryData(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, RECOVERY_DATA_KEYS)) return false
        val batch = intField(o, "batch")
        if (batch == null || batch < 0) return false
        val more = o["more"] as? JsonPrimitive ?: return false
        if (more.isString) return false
        if (more.content != "true" && more.content != "false") return false
        val messages = o["messages"] as? JsonArray ?: return false
        if (messages.size > RECOVERY_DATA_MAX_MESSAGES) return false
        for (element in messages) {
            val message = element as? JsonObject ?: return false
            val id = (message["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id.isNullOrBlank()) return false
        }
        return true
    }

    private fun hasOnlyKeys(o: JsonObject, keys: Set<String>): Boolean =
        o.keys.size == keys.size && o.keys.all { it in keys }

    private fun intField(o: JsonObject, key: String): Int? =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
}
