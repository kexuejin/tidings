// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * screen 帧（屏幕协助）的校验与编解码。
 *
 * 从 Codec 里拆出来是因为它是**唯一一条与上游逐字对齐到 op 级**的消息线：
 * 每个 op 的 keys.length、token 形状（32 位小写 hex）、sessionId（严格 UUIDv4）
 * 全都钉死在上游 TS 实现上。集中一个文件，上游一改动就能就地核对。
 */
object CodecScreen {

// ------------------------------------------------------------------ 屏幕协助 (Screen)

/**
 * screen 帧校验。**形状必须与茶话间逐字对齐**：那边是 `isScreenSessionId(sessionId)`
 * （严格 UUIDv4）、`isScreenToken(token)`（`/^[0-9a-f]{32}$/`），且每个 op 还钉了
 * 精确的 `keys.length`（2 / 3 / 3 / 3）。
 *
 * 原来只做 `isStr(..., Limits.ID)` 是**对称性缺口**：自己发得出、对端收不下。茶话间
 * 在 `decodeEnvelopeObject` 里就把帧丢了 —— 既不回 ACK 也不弹邀请，发起方只能等到
 * 重传耗尽，界面报「屏幕协助已结束 (连接已断开)」，从症状看不出真因（真机
 * E2E 实测）。校验收紧后坏帧在**本机**即被拒，问题留在自己能看见的地方。
 */
fun validateScreen(o: JsonObject): Boolean {
    val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    val sessionId = (o["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
    if (!isUuidV4(sessionId)) return false
    return when (op) {
        // 协议要求：`keys.length === 2`（op + sessionId），多一个键整帧被拒。
        ScreenOps.REQUEST -> o.keys == setOf("op", "sessionId")
        // 协议要求：`keys.length === 3`，且 token 必须是 32 位小写 hex。
        ScreenOps.ACCEPT -> {
            if (o.keys != setOf("op", "sessionId", "token")) return false
            val token = (o["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            isScreenToken(token)
        }
        ScreenOps.REJECT -> {
            if (o.keys != setOf("op", "sessionId", "reason")) return false
            val reason = (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            reason in ScreenRejectReasons.ALL
        }
        ScreenOps.END -> {
            if (o.keys != setOf("op", "sessionId", "reason")) return false
            val reason = (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            reason in ScreenEndReasons.ALL
        }
        else -> false
    }
}

fun decodeScreenMessage(payload: JsonElement?): ScreenMessage? {
    val o = payload as? JsonObject ?: return null
    val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return try {
        when (op) {
            ScreenOps.REQUEST -> ScreenMessage.Request(Codec.wireJson.decodeFromJsonElement(ScreenRequestPayload.serializer(), o))
            ScreenOps.ACCEPT -> ScreenMessage.Accept(Codec.wireJson.decodeFromJsonElement(ScreenAcceptPayload.serializer(), o))
            ScreenOps.REJECT -> ScreenMessage.Reject(Codec.wireJson.decodeFromJsonElement(ScreenRejectPayload.serializer(), o))
            ScreenOps.END -> ScreenMessage.End(Codec.wireJson.decodeFromJsonElement(ScreenEndPayload.serializer(), o))
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}

// screen 四个构造器必须**显式传 op**：ScreenPayload 的 `op` 不给默认值，
// 否则 `encodeDefaults = false` 会把这个键整个省掉，而对端的 `validateScreen`
// 要求 op 存在 —— 缺了它整帧被拒、连 ACK 都没有（投屏接线时
// 用回环测试暴露：症状是"点了投屏，对方毫无反应"，两端都是静默）。

fun screenRequestPayload(sessionId: String): JsonElement =
    Codec.wireJson.encodeToJsonElement(
        ScreenRequestPayload.serializer(),
        ScreenRequestPayload(op = ScreenOps.REQUEST, sessionId = sessionId),
    )

fun screenAcceptPayload(sessionId: String, token: String): JsonElement =
    Codec.wireJson.encodeToJsonElement(
        ScreenAcceptPayload.serializer(),
        ScreenAcceptPayload(op = ScreenOps.ACCEPT, sessionId = sessionId, token = token),
    )

fun screenRejectPayload(sessionId: String, reason: String): JsonElement =
    Codec.wireJson.encodeToJsonElement(
        ScreenRejectPayload.serializer(),
        ScreenRejectPayload(op = ScreenOps.REJECT, sessionId = sessionId, reason = reason),
    )

fun screenEndPayload(sessionId: String, reason: String): JsonElement =
    Codec.wireJson.encodeToJsonElement(
        ScreenEndPayload.serializer(),
        ScreenEndPayload(op = ScreenOps.END, sessionId = sessionId, reason = reason),
    )
}
