// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.AgentAttachmentUi
import com.kapp.tidings.domain.model.agentAttachmentCaption
import com.kapp.tidings.domain.model.agentAttachmentIsImage
import com.kapp.tidings.domain.model.humanBytes
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task-45 ②：手机端把 agent 事件的附件元数据解成**卡片模型**（纯函数，不碰 UI）。
 *
 * 三条契约：
 *  1. 带附件的载荷 ⇒ 卡片模型带全字段；
 *  2. 不带附件（旧端）⇒ 模型为 null ⇒ 退回现有文本占位；
 *  3. 附件形状不对 ⇒ 模型为 null，但**事件本身照常显示**（不因为一张卡片画不出来丢整条事件）。
 */
class AgentAttachmentCardTest {

    private fun eventJson(attachment: String?): JsonObject {
        val base = """{"sessionId":"s-1","cursor":7,"eventType":"tool","summary":"生成了一张图",""" +
            """"body":"🖼 图片: image.png (920×412, 30 KB)""""
        val json = if (attachment == null) "$base}" else "$base,\"attachment\":$attachment}"
        return kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject
    }

    @Test
    fun anEventWithAttachmentParsesIntoTheCardModel() {
        val event = agentEventFrom(
            eventJson(
                """{"attachmentId":"att-1","mediaType":"image/png","width":920,"height":412,""" +
                    """"bytes":30720,"name":"image.png"}""",
            ),
            peerId = "peer-1",
            at = 1L,
        ) ?: error("事件必须解得出来")
        val attachment = event.attachment ?: error("附件必须解出来")
        assertEquals("att-1", attachment.attachmentId)
        assertEquals("image/png", attachment.mediaType)
        assertEquals(920, attachment.width)
        assertEquals(412, attachment.height)
        assertEquals(30_720L, attachment.bytes)
        assertEquals("image.png", attachment.name)
        assertTrue(agentAttachmentIsImage(attachment), "image/png 必须判成图片")
        // 卡片那一行文案：名字 · 尺寸 · 大小
        assertEquals("image.png · 920×412 · 30 KB", agentAttachmentCaption(attachment))
    }

    @Test
    fun anEventWithoutAttachmentKeepsWorkingSoOldSendersFallBackToThePlaceholder() {
        val event = agentEventFrom(eventJson(null), peerId = "peer-1", at = 1L) ?: error("事件必须解得出来")
        assertNull(event.attachment, "没有附件键 ⇒ 卡片模型为 null ⇒ 退回文本占位")
        // 占位正文照旧可用（这就是现在的行为）。
        assertTrue(event.body!!.contains("🖼 图片"), "旧路径的占位正文必须原样保留：${event.body}")
    }

    @Test
    fun aMalformedAttachmentDoesNotDropTheEventItself() {
        // 缺 mediaType
        val missingType = agentEventFrom(
            eventJson("""{"attachmentId":"att-1"}"""),
            peerId = "peer-1",
            at = 1L,
        ) ?: error("附件坏了也不许丢事件")
        assertNull(missingType.attachment, "解不出的附件 ⇒ 没有卡片")
        assertEquals("生成了一张图", missingType.summary, "事件本体必须还在")
        // 附件根本不是对象
        val notAnObject = agentEventFrom(eventJson(""""oops""""), peerId = "peer-1", at = 1L)
        assertNull(notAnObject?.attachment)
    }

    @Test
    fun theCaptionFallsBackToTheMediaSubtypeAndFormatsSizes() {
        val unnamed = AgentAttachmentUi(attachmentId = "a", mediaType = "image/png", bytes = 1024)
        assertEquals("png · 1 KB", agentAttachmentCaption(unnamed), "没名字就退回 MIME 子类型")
        assertEquals("512 B", humanBytes(512))
        assertEquals("1 KB", humanBytes(1024))
        assertEquals("1.5 MB", humanBytes(1024L * 1024 * 3 / 2))
        // 只有宽没有高 ⇒ 不拼尺寸（半个尺寸是坏数据，不显示）
        val half = AgentAttachmentUi(attachmentId = "a", mediaType = "image/png", width = 100)
        assertEquals("png", agentAttachmentCaption(half))
    }
}
