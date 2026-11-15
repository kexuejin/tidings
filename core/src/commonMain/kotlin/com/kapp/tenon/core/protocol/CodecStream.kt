// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.kapp.tenon.core.protocol.CodecWire.hasOnlyKeys
import com.kapp.tenon.core.protocol.CodecWire.intOf
import com.kapp.tenon.core.protocol.CodecWire.isStr
import com.kapp.tenon.core.protocol.CodecWire.strOrNull
import com.kapp.tenon.core.protocol.CodecWire.utf8ByteLength
import com.kapp.tenon.core.protocol.CodecWire.wireJson

/**
 * 支线日志流三帧（`stream-sub` / `stream-chunk` / `stream-cancel`，Phase 1）的编解码。
 * 先例即 [CodecChannel]：校验器（收端）、构造器（发端）引用同一份键集与取值域，
 * 谁在别处手写第二份，第 7 种「没收到」就从那里长出来（AGENTS.md §3 #7）。
 *
 * ## 通道与投递语义（与 `recovery` 家族同族）
 *
 * 三帧**只在加密长连接上收发**（`NodeOutbound.sendStreamFrame` → `LinkDownlink.send`），
 * 严禁 UDP datagram 与 17879 明文回退。发送侧**绝不入离线队列**：过期的订阅与积压块
 * 被 TTL 重放出去是垃圾流量，还会挤占真实待发的队列额度；ACK 不到 = 对端旧版或
 * 链路不通，调用方如实上报「日志流不可用」（探测窗语义，Q5 先例）。
 */
object CodecStream {

    internal val STREAM_SUB_EXPECTED_KEYS = setOf("questId", "sinceSeq")
    internal val STREAM_CHUNK_EXPECTED_KEYS = setOf("questId", "seq", "text")
    internal val STREAM_CANCEL_EXPECTED_KEYS = setOf("questId")

    internal fun validateStreamSub(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, STREAM_SUB_EXPECTED_KEYS)) return false
        if (!isStr(o["questId"], Limits.ID)) return false
        if (o.containsKey("sinceSeq")) {
            val since = intOf(o["sinceSeq"]) ?: return false
            if (since < 0) return false
        }
        return true
    }

    internal fun validateStreamChunk(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, STREAM_CHUNK_EXPECTED_KEYS)) return false
        if (!isStr(o["questId"], Limits.ID)) return false
        val seq = intOf(o["seq"]) ?: return false
        // seq 从 1 起单调：0 与负数是「没有这批」，整帧拒收而不是静默错序。
        if (seq < 1) return false
        // text 非空 + UTF-8 字节 ≤ STREAM_CHUNK_BODY（与发送侧双刀钳制同一份数值：
        // 两侧各写一份就会演成「发得出去、收不下」）。
        val text = strOrNull(o["text"], Limits.STREAM_CHUNK_BODY) ?: return false
        if (utf8ByteLength(text) > Limits.STREAM_CHUNK_BODY) return false
        return true
    }

    internal fun validateStreamCancel(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, STREAM_CANCEL_EXPECTED_KEYS)) return false
        if (!isStr(o["questId"], Limits.ID)) return false
        return true
    }

    /** 构造 `stream-sub` 负载。发送侧先过一遍同一份形状谓词，坏形状崩在发送侧。 */
    fun streamSubPayload(questId: String, sinceSeq: Long? = null): JsonElement {
        require(questId.isNotBlank() && utf8ByteLength(questId) <= Limits.ID) {
            "stream-sub questId 形状不合法"
        }
        require(sinceSeq == null || sinceSeq >= 0) { "stream-sub sinceSeq 必须 >= 0" }
        return wireJson.encodeToJsonElement(
            StreamSubPayload.serializer(),
            StreamSubPayload(questId = questId, sinceSeq = sinceSeq),
        )
    }

    /** 构造 `stream-chunk` 负载；body 超预算在发送侧就崩（`require`），绝不带病上线。 */
    fun streamChunkPayload(questId: String, seq: Long, text: String): JsonElement {
        require(questId.isNotBlank() && utf8ByteLength(questId) <= Limits.ID) {
            "stream-chunk questId 形状不合法"
        }
        require(seq >= 1) { "stream-chunk seq 必须 >= 1" }
        require(text.isNotEmpty()) { "stream-chunk 不发空批（没有信息量的帧只会消耗预算）" }
        require(utf8ByteLength(text) <= Limits.STREAM_CHUNK_BODY) {
            "stream-chunk text 超 STREAM_CHUNK_BODY（先过 NodeOutbound.clampStreamChunkText 再构造）"
        }
        return wireJson.encodeToJsonElement(
            StreamChunkPayload.serializer(),
            StreamChunkPayload(questId = questId, seq = seq, text = text),
        )
    }

    /** 构造 `stream-cancel` 负载。 */
    fun streamCancelPayload(questId: String): JsonElement {
        require(questId.isNotBlank() && utf8ByteLength(questId) <= Limits.ID) {
            "stream-cancel questId 形状不合法"
        }
        return wireJson.encodeToJsonElement(
            StreamCancelPayload.serializer(),
            StreamCancelPayload(questId = questId),
        )
    }
}
