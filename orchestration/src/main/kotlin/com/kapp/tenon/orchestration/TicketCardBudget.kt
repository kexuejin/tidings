// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.Codec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/** 帧预算实测读数（P2 规格 2）：只量卡 JSON 本身，不猜、不截断。 */
object FrameBudgetInspector {
    fun measure(cardJson: String): Int = Codec.utf8ByteLength(cardJson)
    fun withinSoftLimit(cardJson: String): Boolean =
        measure(cardJson) <= TicketCardBudget.SOFT_LIMIT_BYTES
}

/**
 * 工单交付流的**单卡折叠 + 5 KiB 软预算**（P2 规格 2）。
 *
 * 独立于 `Limits.CARD_BYTES`（24 KiB 协议硬红线）：那是整帧能不能发，这是产品承诺的
 * 「一张轻量动态卡」。做法分两步：
 * 1. 长文本（banner / 棒详情 / 崩溃抽屉 / diff 摘要）一律落盘为本地 Artifact，卡内只留指针；
 * 2. 外置后仍超 5 KiB（典型：棒数太多）⇒ 换一张**同 cardId 的最小卡**，说明详情去了哪里。
 *
 * 绝不截断原 JSON：截断的卡对端解不开，比不发更坏。落盘失败也不外置 —— 宁可把
 * 完整卡交给 24 KiB 硬闸门裁决，也不静默丢掉用户看得见的现场。
 */
object TicketCardBudget {

    const val SOFT_LIMIT_BYTES: Int = 5 * 1024

    /** 单段文本超过这个长度就外置；短文本保持原样，卡形状一字不变。 */
    const val INLINE_TEXT_LIMIT: Int = 512

    private const val POINTER_PREVIEW_CHARS = 80
    private val LONG_TEXT_KEYS = setOf("text", "detail", "observation", "drawer", "summary")

    sealed interface Outcome {
        val json: String
        val bytes: Int

        /** 正常发出（可能已外置过长文本）。 */
        data class Sent(override val json: String, override val bytes: Int) : Outcome

        /** 结构性超预算：换成同 cardId 的最小卡，详情仍在本地 Artifact。 */
        data class Minimal(override val json: String, override val bytes: Int) : Outcome
    }

    /** @param sink 返回 true 表示这段长文本确实落盘了；false = 保留原文，不外置。 */
    fun enforce(cardJson: String, sink: (name: String, text: String) -> Boolean): Outcome {
        val parsed = runCatching { Json.parseToJsonElement(cardJson).jsonObject }.getOrNull()
            ?: return Outcome.Sent(cardJson, FrameBudgetInspector.measure(cardJson))
        var sinkFailed = false
        val counter = AtomicInteger(0)
        val guardedSink: (String, String) -> Boolean = { name, text ->
            val ok = sink(name, text)
            if (!ok) sinkFailed = true
            ok
        }
        val folded = externalize(parsed, guardedSink, counter)
        if (sinkFailed) {
            return Outcome.Sent(cardJson, FrameBudgetInspector.measure(cardJson))
        }
        val foldedJson = folded.toString()
        val foldedBytes = FrameBudgetInspector.measure(foldedJson)
        if (foldedBytes <= SOFT_LIMIT_BYTES) return Outcome.Sent(foldedJson, foldedBytes)
        val minimalJson = minimalCard(parsed).toString()
        return Outcome.Minimal(minimalJson, FrameBudgetInspector.measure(minimalJson))
    }

    private fun externalize(
        element: JsonElement,
        sink: (String, String) -> Boolean,
        counter: AtomicInteger,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val out = LinkedHashMap<String, JsonElement>()
            element.forEach { (key, value) ->
                out[key] = if (key in LONG_TEXT_KEYS && value is JsonPrimitive && value.isString) {
                    val text = value.content
                    if (text.length > INLINE_TEXT_LIMIT) {
                        val name = "card-text-" + counter.incrementAndGet() + ".txt"
                        if (sink(name, text)) {
                            JsonPrimitive("[artifact $name] " + text.take(POINTER_PREVIEW_CHARS))
                        } else {
                            value
                        }
                    } else {
                        value
                    }
                } else {
                    externalize(value, sink, counter)
                }
            }
            JsonObject(out)
        }
        is JsonArray -> JsonArray(element.map { externalize(it, sink, counter) })
        else -> element
    }

    private fun minimalCard(original: JsonObject): JsonObject {
        val kept = listOf("cardId", "rev", "type", "state")
            .mapNotNull { key -> original[key]?.let { key to it } }
            .toMap()
        return JsonObject(kept + mapOf(
            "title" to JsonPrimitive(
                (original["title"]?.jsonPrimitive?.content ?: "ticket").take(60),
            ),
            "items" to JsonArray(emptyList()),
            "banner" to buildJsonObject {
                put("text", "Details externalized to local Artifacts; full board exceeds 5 KiB")
                put("tone", "warning")
            },
        ))
    }
}
