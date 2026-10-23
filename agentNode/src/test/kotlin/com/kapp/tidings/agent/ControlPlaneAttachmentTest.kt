// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.AgentEventAttachment
import com.kapp.tidings.core.protocol.Codec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 宿主侧接通附件（task-47）：解析形状 + att1 闸门 + 旧帧不受影响。
 *
 * 真机背景：桥（dsh-tidings）能发结构化附件元数据，但宿主侧原来**没读** obj["attachment"]
 * ⇒ attachment 被静默丢掉（多键被忽略、不报错也不生效）。
 *
 * 两条判据的方向性比实现更重要：
 *  ① 对端没声明 att1 ⇒ **一定不带**附件（Codec 是严格白名单，带上去会让旧端整帧拒收）；
 *  ② attachment 在场但形状不对 ⇒ 400，**不许**洗成「没带附件」那种合法帧。
 */
class ControlPlaneAttachmentTest {

    private val full = Json.parseToJsonElement(
        """{"attachmentId":"att-1","mediaType":"image/png","width":920,"height":412,"bytes":30720,"name":"image.png"}""",
    )

    @Test
    fun parsesTheSixWhitelistedKeys() {
        val parsed = parseAgentEventAttachment(full)
        assertEquals(
            AgentEventAttachment("att-1", "image/png", 920, 412, 30720L, "image.png"),
            parsed,
        )
    }

    @Test
    fun optionalKeysMayBeAbsentAndExtraKeysAreDropped() {
        val minimal = Json.parseToJsonElement(
            """{"attachmentId":"att-1","mediaType":"image/png","path":"/tmp/x","secret":"y"}""",
        )
        assertEquals(AgentEventAttachment("att-1", "image/png"), parseAgentEventAttachment(minimal),
            "多出来的键不进这一帧（白名单重建）")
    }

    @Test
    fun shapeErrorsAreNotSilentlyWashedIntoNoAttachment() {
        assertNull(parseAgentEventAttachment(Json.parseToJsonElement("""{"mediaType":"image/png"}""")), "缺 attachmentId")
        assertNull(parseAgentEventAttachment(Json.parseToJsonElement("""{"attachmentId":"att-1"}""")), "缺 mediaType")
        assertNull(parseAgentEventAttachment(Json.parseToJsonElement("""{"attachmentId":123,"mediaType":"image/png"}""")), "id 是数字不是字符串")
        assertNull(parseAgentEventAttachment(Json.parseToJsonElement("""{"attachmentId":"att-1","mediaType":"image/png","width":"920"}""")), "width 是字符串不是数字")
        assertNull(parseAgentEventAttachment(Json.parseToJsonElement("""{"attachmentId":"att-1","mediaType":"image/png","name":7}""")), "name 是数字不是字符串")
        assertNull(parseAgentEventAttachment(Json.parseToJsonElement("\"att-1\"")), "不是对象")
    }

    @Test
    fun gateIsFailClosed() {
        val parsed = parseAgentEventAttachment(full)
        assertEquals(parsed, gatedAttachment(parsed, peerDeclaresAttachment = true), "声明了 att1 ⇒ 带附件")
        assertNull(gatedAttachment(parsed, peerDeclaresAttachment = false), "没声明 att1 ⇒ 整条不带（退回文本占位）")
        assertNull(gatedAttachment(null, peerDeclaresAttachment = true), "本来就没有附件 ⇒ 什么都不带")
    }

    @Test
    fun oldFramesWithoutAttachmentAreUnchanged() {
        val legacy = Codec.agentEventPayload("s-1", 7, "assistant.text", summary = "hi", body = "hello")
            .jsonObject
        assertTrue(!legacy.containsKey("attachment"), "不带附件的帧里连键都没有（旧端不受影响）")
        val withAttachment = Codec.agentEventPayload(
            "s-1", 7, "user.message", summary = "hi", body = "hello",
            attachment = AgentEventAttachment("att-1", "image/png", 920, 412, 30720L, "image.png"),
        ).jsonObject
        val wire = withAttachment["attachment"]!!.jsonObject
        assertEquals(
            listOf("attachmentId", "mediaType", "width", "height", "bytes", "name"),
            wire.keys.toList(),
            "线上键名与顺序就是协议白名单那六个",
        )
        assertEquals("att-1", wire["attachmentId"]!!.jsonPrimitive.content)
    }
}