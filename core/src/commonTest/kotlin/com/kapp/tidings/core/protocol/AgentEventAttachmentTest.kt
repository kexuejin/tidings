// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task-45：`agent-event` 的**附件元数据**（cap `att1`）。
 *
 * 三条要钉住的东西：
 *  1. 带附件的载荷能编出正确形状（键名与 DSH 侧 content 块同口径）；
 *  2. **不带附件**的旧载荷照样合法 ⇒ 退回文本占位；
 *  3. 附件形状不对 ⇒ **整帧拒**（半张卡片比占位更糟），而未知的**新增键**按 D2 的规矩忽略。
 */
class AgentEventAttachmentTest {

    private val limit = Protocol.TEXT_TCP_LIMIT

    private fun payload(attachment: AgentEventAttachment? = null): JsonObject {
        val element = Codec.agentEventPayload(
            sessionId = "s-1",
            cursor = 7,
            eventType = "tool",
            summary = "生成了一张图",
            body = "🖼 图片: image.png (920×412, 30 KB)",
            ref = null,
            attachment = attachment,
        )
        return element as JsonObject
    }

    @Test
    fun theAttachmentMetadataRoundTripsWithTheExpectedKeyNames() {
        val json = payload(
            AgentEventAttachment(
                attachmentId = "att-1",
                mediaType = "image/png",
                width = 920,
                height = 412,
                bytes = 30_720,
                name = "image.png",
            ),
        )
        val attachment = json["attachment"] as? JsonObject ?: error("attachment 必须是个对象：$json")
        assertEquals("att-1", (attachment["attachmentId"] as JsonPrimitive).content)
        assertEquals("image/png", (attachment["mediaType"] as JsonPrimitive).content)
        assertEquals("920", (attachment["width"] as JsonPrimitive).content)
        assertEquals("412", (attachment["height"] as JsonPrimitive).content)
        assertEquals("30720", (attachment["bytes"] as JsonPrimitive).content)
        assertEquals("image.png", (attachment["name"] as JsonPrimitive).content)
        assertTrue(CodecAgent.validateAgentEvent(json, limit), "带合法附件的帧必须通过")
    }

    @Test
    fun aPayloadWithoutAttachmentStaysValidSoOldSendersKeepWorking() {
        val json = payload()
        assertFalse(json.containsKey("attachment"), "没传附件就不该出现这个键")
        assertTrue(CodecAgent.validateAgentEvent(json, limit), "旧载荷（无附件）必须继续合法 ⇒ 退回文本占位")
    }

    @Test
    fun aMalformedAttachmentRejectsTheWholeFrame() {
        // 缺 mediaType
        val missingType = buildJsonObject {
            put("attachment", buildJsonObject { put("attachmentId", "att-1") })
        }
        assertFalse(CodecAgent.validateAgentEvent(merge(payload(), missingType), limit), "缺 mediaType 必须拒")
        // 宽为 0
        val zeroWidth = buildJsonObject {
            put(
                "attachment",
                buildJsonObject {
                    put("attachmentId", "att-1")
                    put("mediaType", "image/png")
                    put("width", 0)
                },
            )
        }
        assertFalse(CodecAgent.validateAgentEvent(merge(payload(), zeroWidth), limit), "宽 0 必须拒")
        // 附件里多一个未知键（附件对象自己仍是**严格**白名单）
        val extraKey = buildJsonObject {
            put(
                "attachment",
                buildJsonObject {
                    put("attachmentId", "att-1")
                    put("mediaType", "image/png")
                    put("surprise", "x")
                },
            )
        }
        assertFalse(CodecAgent.validateAgentEvent(merge(payload(), extraKey), limit), "附件对象多一个键必须拒")
    }

    @Test
    fun anUnknownTopLevelKeyIsIgnoredInsteadOfDroppingTheFrame() {
        // D2 起 agent-session 的规矩（必需子集 + 忽略未知键），task-45 把它也用在 agent-event：
        // 将来新增的顶层键不会让**本接收端**整帧丢掉。
        val withFutureKey = merge(payload(), buildJsonObject { put("futureField", "v2") })
        assertTrue(CodecAgent.validateAgentEvent(withFutureKey, limit), "未知顶层键应被忽略，而不是整帧拒")
    }

    /** 把 [overlay] 的键并进 [base]（只用于测试构造）。 */
    private fun merge(base: JsonObject, overlay: JsonObject): JsonObject =
        JsonObject(base + overlay)
}
