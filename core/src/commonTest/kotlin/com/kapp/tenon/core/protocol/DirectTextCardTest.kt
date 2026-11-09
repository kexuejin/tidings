// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **1:1 单聊文本携带声明式卡片**（`Codec.textPayloadWithCard` / `NodeOutbound.sendDirectText`）。
 *
 * ## 为什么这组用例存在
 *
 * 群聊有看板卡（`group-text` 的 `card` 键），1:1 直派却一直没有承载卡片的面：宿主把卡
 * 落进自己的库就结束，发起方手机上一条都没有。2026-11-08 真机 A22 现场：工单在沙箱里
 * 真跑 20.8 s、五帧 rev 全部「落库成功」，手机库同时段**零入站行**，界面一字不变。
 *
 * 修法是把卡片挂在**单聊文本帧**上 —— 这条路之所以成立，只因为
 * [CodecMsg.validateMsg] 在 `if (kind != MsgKinds.GROUP_TEXT) return true` 处就返回，
 * 单聊帧**不做字段白名单**。所以这组用例钉的正是这个前提：
 *
 *  1. 编码出来的载荷形状（kind/text/card，不多不少）；
 *  2. 带 `card` 的 1:1 帧**过得了解码**（一旦有人给单聊加白名单，这条立刻红）；
 *  3. 解码后卡片是同一个对象，收端 `payload["card"] as? JsonObject` 拿得到。
 *
 * **第 2 条是给未来的自己看的**：给单聊帧加字段白名单不是"更严格"，而是把老端/新端
 * 之间从「少看到一张卡」退化成「整条消息都收不到」（`group-text` 的 `gta1` 门控就是
 * 为了这件事才存在的）。改那条早返回之前，先读这条用例。
 */
class DirectTextCardTest {

    private companion object {
        const val ID = "77777777-7777-4777-8777-777777777777"
        const val FROM = "88888888-8888-4888-8888-888888888888"

        fun env(payload: String): String =
            """{"v":1,"type":"msg","id":"$ID","from":"$FROM","ts":1700000000000,"payload":$payload}"""
    }

    private fun ok(json: String): DecodeResult.Ok =
        Codec.decodeTcp(json) as? DecodeResult.Ok ?: fail("期望解码通过，实际被拒：$json")

    private fun card(): JsonObject = buildJsonObject {
        put("cardId", "chain-tmw77l9bj")
        put("rev", 1)
        put("type", "task-board")
        put("state", "proposed")
        put("title", "implement create probe-20261108.txt PROBE-OK")
    }

    /** 编码形状：`kind` / `text` / `card` 三个键，卡片原样是对象。 */
    @Test
    fun textPayloadWithCardKeepsCardObject() {
        val payload = Codec.textPayloadWithCard("看板", card())
        assertEquals("text", (payload["kind"] as? JsonPrimitive)?.content, "单聊文本的 kind 必须是 text")
        assertEquals("看板", (payload["text"] as? JsonPrimitive)?.content)
        val carried = payload["card"] as? JsonObject ?: fail("card 必须原样是对象：$payload")
        assertEquals("chain-tmw77l9bj", (carried["cardId"] as? JsonPrimitive)?.content)
        assertEquals(setOf("kind", "text", "card"), payload.keys, "不多带键（与 textPayload 的 kind/text 形状对齐）")
    }

    /**
     * **关键前提**：不带 `groupId` 的 `text` 帧挂 `card` 仍然解得开。
     *
     * 白名单只对 `group-text` 生效（见类注释）。这条红了 = 有人给单聊加了白名单。
     */
    @Test
    fun directTextFrameWithCardDecodes() {
        val payload = """{"kind":"text","text":"工单已受理","card":${card()}}"""
        val obj = ok(env(payload)).env.payload as JsonObject
        val carried = obj["card"] as? JsonObject ?: fail("解码后 card 必须还在：$obj")
        assertEquals("task-board", (carried["type"] as? JsonPrimitive)?.content)
        assertEquals("1", (carried["rev"] as? JsonPrimitive)?.content)
    }

    /** 单聊帧带**任意**附加键都不拒 —— 这是「不做白名单」的既有语义，不是漏写。 */
    @Test
    fun directTextFrameToleratesExtraKeys() {
        val payload = """{"kind":"text","text":"hi","senderId":"agent:xyz","whatever":1}"""
        val obj = ok(env(payload)).env.payload as JsonObject
        assertTrue(obj.containsKey("whatever"), "单聊帧不做字段白名单，附加键原样保留")
    }
}
