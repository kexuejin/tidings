// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 单聊文本的**署名**（真机 A22：agent 的话在 1:1 里没有归属）。
 *
 * 1:1 直派的反馈（「Ticket accepted…」/「改动已就绪…」）是宿主上的 agent 说的，
 * 走的是 [Codec.textPayload] 这条**纯文本**载荷 —— 而它此前根本没有 senderId 参数，
 * 只有带卡片的 [Codec.textPayloadWithCard] 有。于是同一轮里工单卡有署名、两句文本没有：
 * 手机库里前者的 `sender_id = agent:<宿主>:dsh`，后者是 NULL（真机实测），界面上
 * 两句话就被算成"对端那台设备说的"。
 *
 * 反向验证：把 `textPayload` 的 senderId 参数去掉（或只在有 card 时写），
 * `单聊纯文本也能带署名` 变红。
 */
class DirectTextSenderIdTest {

    @Test
    fun 不传署名时载荷与旧版逐字节相同() {
        val legacy = Codec.textPayload("hi").toString()
        assertFalse(legacy.contains("senderId"), "不传 senderId 时载荷里不该出现这个键：$legacy")
        assertEquals("""{"kind":"text","text":"hi"}""", legacy, "老载荷形状必须逐字节不变")
    }

    @Test
    fun 单聊纯文本也能带署名() {
        val json = Codec.textPayload("改动已就绪", senderId = "agent:host-1:dsh").toString()
        assertTrue(json.contains("\"senderId\""), json)
        assertTrue(json.contains("agent:host-1:dsh"), "agent 三段式必须原样保留：$json")
    }

    @Test
    fun 带署名的单聊文本过得了入站校验() {
        // 单聊帧不做字段白名单（validateMsg 在 `kind != group-text` 处即返回），
        // 但这条断言要的是**解码这条路**：载荷形状变了而校验没跟上时，
        // 症状是"发出去了、对端一个字都没收到"，只看编码结果抓不住。
        val payload = Codec.textPayload("hi", senderId = "agent:host-1:dsh").toString()
        val decoded = Codec.decodeTcp(env("msg", payload)) as? DecodeResult.Ok
            ?: error("带 senderId 的单聊文本被入站校验拒了：$payload")
        assertEquals(
            "agent:host-1:dsh",
            (decoded.env.payload as JsonObject)["senderId"]?.jsonPrimitive?.content,
        )
    }

    private fun env(type: String, payload: String): String =
        """{"v":1,"type":"$type","id":"aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb","from":"host-1","ts":1700000000000,"payload":$payload}"""
}
