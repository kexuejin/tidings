// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * quest 跨设备帧族的编解码守卫（§3 的两类教训都适用）：
 * 白名单（多一个键整帧拒）+ 取值域（status 必须在 QuestStatus 枚举内）+ 可选键缺省合法。
 */
class CodecQuestTest {

    private val dispatch = buildJsonObject {
        put("clientToken", JsonPrimitive("note-abc123"))
        put("prompt", JsonPrimitive("optimize the FTS trigger"))
    }

    private val notice = buildJsonObject {
        put("questId", JsonPrimitive("quest-1"))
        put("title", JsonPrimitive("A quest"))
        put("status", JsonPrimitive(QuestStatus.RUNNING.name))
        put("summary", JsonPrimitive("working"))
        put("convId", JsonPrimitive(ConvId.ASSISTANT))
        put("rev", JsonPrimitive(0L))
    }

    @Test
    fun dispatchValidationAcceptsWellFormedAndRejectsViolations() {
        assertTrue(CodecQuest.validateQuestDispatch(dispatch, textLimit = 4096))

        // 多一个键 = 整帧拒（§2.2 白名单纪律）
        val extra = buildJsonObject {
            dispatch.forEach { (k, v) -> put(k, v) }
            put("sneaky", JsonPrimitive("x"))
        }
        assertFalse(CodecQuest.validateQuestDispatch(extra, textLimit = 4096))

        // 缺键 = 拒
        assertFalse(CodecQuest.validateQuestDispatch(buildJsonObject { put("clientToken", JsonPrimitive("t")) }, 4096))

        // 超预算 prompt = 拒（textLimit 由通道预算决定）
        assertFalse(CodecQuest.validateQuestDispatch(dispatch, textLimit = 8))
    }

    @Test
    fun noticeValidationAcceptsOptionalAbsentAndRejectsBadStatus() {
        assertTrue(CodecQuest.validateQuestNotice(notice))

        // 可选键在场且合法 = 收
        val full = buildJsonObject {
            notice.forEach { (k, v) -> put(k, v) }
            put("clientToken", JsonPrimitive("note-1"))
            put("questionText", JsonPrimitive("Proceed?"))
        }
        assertTrue(CodecQuest.validateQuestNotice(full))

        // 可选键在场但非法形状 = 拒
        val badKind = buildJsonObject {
            full.forEach { (k, v) -> put(k, v) }
            put("questionKind", JsonPrimitive("not_a_known_kind"))
        }
        assertFalse(CodecQuest.validateQuestNotice(badKind))

        // status 取值域：枚举外的字符串整帧拒（§3 第 7 种）
        val badStatus = buildJsonObject {
            notice.forEach { (k, v) -> put(k, v) }
            put("status", JsonPrimitive("allow_session"))
        }
        assertFalse(CodecQuest.validateQuestNotice(badStatus))

        // 多一个键 = 拒
        val extra = buildJsonObject {
            notice.forEach { (k, v) -> put(k, v) }
            put("extra", JsonPrimitive(1))
        }
        assertFalse(CodecQuest.validateQuestNotice(extra))
    }

    @Test
    fun noticeRoundTripsThroughEncodeAndDecode() {
        val notice = AssistantQuestNotice(
            questId = "quest-rt",
            title = "Round trip",
            status = QuestStatus.WAITING_INPUT,
            summary = "waiting",
            convId = ConvId.ASSISTANT,
            clientToken = "note-token-1",
            questionText = "Proceed?",
            questionKind = AssistantQuestNotice.QUESTION_MERGE_APPROVAL,
            rev = 3L,
        )
        val encoded = CodecQuest.encodeQuestNotice(notice)
        // 编码键集必须落在校验白名单内（发送侧不产出注定被拒的帧）
        encoded.keys.forEach { key ->
            assertTrue(
                key in CodecQuest.QUEST_NOTICE_REQUIRED_KEYS || key in CodecQuest.QUEST_NOTICE_OPTIONAL_KEYS,
                "编码键 $key 不在白名单内 —— 发送侧产出了注定被拒的帧",
            )
        }
        val decoded = CodecQuest.decodeQuestNotice(encoded)
        assertEquals(notice, decoded)
    }

    @Test
    fun wirePayloadValidationAcceptsQuestFrames() {
        // CodecWire 总入口：与生产解码同一条路（不是只测私有校验器）
        assertTrue(Codec.validatePayload(MsgTypes.QUEST_DISPATCH, dispatch, textLimit = 4096))
        assertTrue(Codec.validatePayload(MsgTypes.QUEST_NOTICE, notice))
    }

    @Test
    fun questFramesAreRegisteredInSpecTable() {
        // 旧端判据：老 KNOWN_TYPES 没有这两帧 → known=false → 静默忽略（发端便签留存）。
        // 新端判据：注册进 MessageSpecs（known/handled/reliable 三位如实）——本测试钉注册。
        for (type in listOf(MsgTypes.QUEST_DISPATCH, MsgTypes.QUEST_NOTICE)) {
            val spec = MessageSpecs.byType[type]
            assertNotNull(spec, type + " 必须注册进 MessageSpecs（否则新端也当 unknown 静默忽略）")
            assertTrue(spec.known && spec.handled && spec.reliable)
        }
    }

    @Test
    fun dispatchEncodeProducesWhitelistedKeys() {
        val encoded = CodecQuest.encodeQuestDispatch("note-1", "do the thing")
        assertEquals(setOf("clientToken", "prompt"), encoded.keys)
        assertEquals("do the thing", (encoded["prompt"] as JsonPrimitive).content)
    }
}
