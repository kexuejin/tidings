// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 * avatar op: get —— 请求指定 SHA-256 哈希的自定义头像数据。
 *
 * ## 三个载荷的 `op` 为什么都带 `@EncodeDefault`
 *
 * 它是**协议字段**，不是可选默认值。`Codec.wireJson` 是 `encodeDefaults = false`，
 * 所以「等于默认值」的键会被整键省略 —— 不给 `op` 加这层注解，三条 `avatar` 帧
 * 线上就只剩 `{hash,data}`，而接收侧 `validateAvatar` 第一句就是
 * `o["op"] ?: return false` ⇒ 整帧 `bad-payload`。
 *
 * 也就是说：**发送侧一直在产出注定被对端拒收的帧**，只因为 `cap av1` 至今没有生产
 * 调用点（`RelayControllerAvatarComponent` 的 KDoc），这条路径没人走过才一直没发现。
 * 判据在 `AvatarPayloadOpKeyTest`，谁把注解删掉它就红。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AvatarGetPayload(
    @EncodeDefault
    val op: String = AvatarOps.GET,
    val hash: String,
)

/**
 * avatar op: data
 *
 * 响应指定 SHA-256 哈希的自定义头像图片数据（Base64 编码的 WebP）。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AvatarDataPayload(
    @EncodeDefault
    val op: String = AvatarOps.DATA,
    val hash: String,
    val data: String,
)

/**
 * avatar op: miss
 *
 * 响应未找到对应哈希的头像。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AvatarMissPayload(
    @EncodeDefault
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
        // 上限与发送侧同一个数（[Limits.AVATAR_DATA_B64]）：65536 那个旧值比整帧硬上限
        // 还大，等于「通过校验的载荷必然超帧」—— 收得到才叫上限（ARP-18⑥）。
        if (data.isEmpty() || data.length > Limits.AVATAR_DATA_B64) return false
    }
    return true
}
