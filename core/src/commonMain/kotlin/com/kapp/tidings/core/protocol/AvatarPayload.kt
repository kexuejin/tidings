// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自定义头像操作指令（op）。
 *
 * 对齐 protocol §3 / PPE-1 §15。
 */
object AvatarOps {
    const val GET = "get"
    const val DATA = "data"
    const val MISS = "miss"

    val ALL: Set<String> = setOf(GET, DATA, MISS)
}

/**
 * avatar op: get
 *
 * 请求指定 SHA-256 哈希的自定义头像数据。
 */
@Serializable
data class AvatarGetPayload(
    val op: String = AvatarOps.GET,
    val hash: String,
)

/**
 * avatar op: data
 *
 * 响应指定 SHA-256 哈希的自定义头像图片数据（Base64 编码的 WebP）。
 */
@Serializable
data class AvatarDataPayload(
    val op: String = AvatarOps.DATA,
    val hash: String,
    val data: String,
)

/**
 * avatar op: miss
 *
 * 响应未找到对应哈希的头像。
 */
@Serializable
data class AvatarMissPayload(
    val op: String = AvatarOps.MISS,
    val hash: String,
)

/**
 * avatar 载荷校验。
 */
fun validateAvatar(o: JsonObject): Boolean {
    val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    if (op !in AvatarOps.ALL) return false
    val hash = (o["hash"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    if (!isHex64(hash)) return false
    if (op == AvatarOps.DATA) {
        val data = (o["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (data.isEmpty() || data.length > 65536) return false
    }
    return true
}
