// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * group-text 的三个 agent 键：`agentIntent`（人类 → 插件的单帧原子意图）、
 * `agentState`（插件 → 全群的执行状态，四态）、`card`（声明式卡片负载）。
 *
 * 三条铁律各有一组用例：
 *  1. **白名单**：带新键的帧必须过得了入站校验（走解码，不只看 JSON 有没有键）——
 *     `senderId` 当年只加 data class 漏加白名单，症状是发送侧 SENT、对端一条没有；
 *  2. **值类型**：白名单只管键的存在，不管值的形状。`agentState` 限四态枚举，
 *     `agentIntent` / `card` 限对象 —— 值形状不对的帧整帧拒收，绝不「尽量解读」；
 *  3. **字节预算**：`Limits.CARD_BYTES` 在发送（拒绝不截断）、接收（bad-payload）、
 *     队列重放（`decodeTcp` 走同一份 `validateMsg`）三处同源。
 *
 * 这些用例先于实现编写：白名单没加键时，正向用例全部红（extra-field 整帧拒收）。
 */
class GroupTextAgentKeysTest {

    private companion object {
        const val ID = "33333333-3333-4333-8333-333333333333"
        const val FROM = "11111111-1111-4111-8111-111111111111"

        /** 手写原始 JSON（不经过本仓编码器）：被测的是**入站**校验，不是本端自己编不编得出来。 */
        fun env(payload: String): String =
            """{"v":1,"type":"msg","id":"$ID","from":"$FROM","ts":1700000000000,"payload":$payload}"""
    }

    private fun ok(json: String): DecodeResult.Ok =
        Codec.decodeTcp(json) as? DecodeResult.Ok ?: fail("期望解码通过，实际被拒：$json")

    private fun reason(json: String): String =
        (Codec.decodeTcp(json) as? DecodeResult.Rejected)?.reason ?: fail("期望被拒绝，实际通过：$json")

    // ---------------------------------------------------------------- 白名单

    /** `agentIntent`（单帧原子意图）随群文本走，且解码后原样可读。 */
    @Test
    fun framesWithAgentIntentDecode() {
        val intent = """{"agentId":"dev-lead","wsId":"teahouse-mobile","mode":"auto"}"""
        val payload = """{"kind":"group-text","text":"@dev-lead 重构传输层","groupId":"grp-1","groupRev":1,"agentIntent":$intent}"""
        val decoded = ok(env(payload))
        val obj = decoded.env.payload as JsonObject
        val carried = obj["agentIntent"] as? JsonObject ?: fail("agentIntent 必须原样是对象：$obj")
        assertEquals("dev-lead", (carried["agentId"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    /** `agentState` 四态（accept / blocked / completed / failed）都放行。 */
    @Test
    fun framesWithAgentStateDecode() {
        for (state in listOf("accept", "blocked", "completed", "failed")) {
            val payload = """{"kind":"group-text","text":"进度","groupId":"grp-1","groupRev":1,"agentState":"$state"}"""
            val obj = (ok(env(payload))).env.payload as JsonObject
            assertEquals(state, (obj["agentState"] as kotlinx.serialization.json.JsonPrimitive).content, "state=$state")
        }
    }

    /** `card`（声明式卡片负载）随群文本走，解码后是同一个对象。 */
    @Test
    fun framesWithCardDecode() {
        val card = """{"type":"team-task-board","cardId":"task-89","rev":3,"title":"重构网络传输层"}"""
        val payload = """{"kind":"group-text","text":"看板","groupId":"grp-1","groupRev":1,"card":$card}"""
        val decoded = ok(env(payload))
        val obj = decoded.env.payload as JsonObject
        val carried = obj["card"] as? JsonObject ?: fail("card 必须原样是对象：$obj")
        assertEquals("task-89", (carried["cardId"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    // ---------------------------------------------------------------- 值类型

    /** `agentState` 出了四态枚举就是坏载荷 —— 状态机是协议事实，不是自由文本。 */
    @Test
    fun agentStateOutsideTheFourStatesIsRejected() {
        for (bad in listOf("running", "pending", "ACCEPT", "")) {
            val payload = """{"kind":"group-text","text":"进度","groupId":"grp-1","groupRev":1,"agentState":"$bad"}"""
            assertEquals(
                RejectReason.badPayload(MsgTypes.MSG),
                reason(env(payload)),
                "agentState=\"$bad\" 必须整帧拒收",
            )
        }
    }

    /** `agentState` 必须是字符串：数字 / 布尔 / 对象都不行。 */
    @Test
    fun nonStringAgentStateIsRejected() {
        val payload = """{"kind":"group-text","text":"进度","groupId":"grp-1","groupRev":1,"agentState":1}"""
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env(payload)))
    }

    /** `agentIntent` 必须是对象：字符串形态的「意图」是坏载荷。 */
    @Test
    fun nonObjectAgentIntentIsRejected() {
        val payload = """{"kind":"group-text","text":"派单","groupId":"grp-1","groupRev":1,"agentIntent":"dev-lead"}"""
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env(payload)))
    }

    /** `card` 必须是对象：数组 / 字符串 / 数字都不行。 */
    @Test
    fun nonObjectCardIsRejected() {
        for (bad in listOf("""["x"]""", """"card"""", "1")) {
            val payload = """{"kind":"group-text","text":"看板","groupId":"grp-1","groupRev":1,"card":$bad}"""
            assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env(payload)), "card=$bad")
        }
    }

    // ---------------------------------------------------------------- 字节预算

    /** 卡片超过 `Limits.CARD_BYTES` 整帧拒收（接收侧 bad-payload）。 */
    @Test
    fun oversizedCardIsRejected() {
        val oversizedTitle = "汉".repeat(Limits.CARD_BYTES) // 每字 3 字节，必超
        val card = buildJsonObject { put("title", oversizedTitle) }
        val payload = """{"kind":"group-text","text":"看板","groupId":"grp-1","groupRev":1,"card":$card}"""
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env(payload)))
    }

    /** 预算边界之内（含贴边）的卡片放行 —— 上限是「超过才拒」。 */
    @Test
    fun cardAtTheBudgetEdgeDecodes() {
        // 序列化后卡在预算以内：3 字节/字的标题 + 其余键的开销，留出余量。
        val title = "汉".repeat((Limits.CARD_BYTES - 64) / 3)
        val card = buildJsonObject { put("title", title) }
        assertTrue(
            Codec.utf8ByteLength(card.toString()) <= Limits.CARD_BYTES,
            "前提：这张卡片必须真的在预算内",
        )
        val payload = """{"kind":"group-text","text":"看板","groupId":"grp-1","groupRev":1,"card":$card}"""
        ok(env(payload))
    }

    /** 白名单依然是严格的：三个新键放行不等于放行别的键。 */
    @Test
    fun stillRejectsTrulyUnknownKeys() {
        val payload = """{"kind":"group-text","text":"x","groupId":"grp-1","groupRev":1,"type":"extra"}"""
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env(payload)))
    }

    // ---------------------------------------------------------------- 出站编码

    /** 编码器带上三个键后，走 decodeTcp 必须能原样读回（键序在尾部，老键序不变）。 */
    @Test
    fun encoderCarriesTheThreeKeys() {
        val intent = buildJsonObject {
            put("agentId", "dev-lead")
            put("wsId", "teahouse-mobile")
            put("mode", "auto")
        }
        val card = buildJsonObject {
            put("type", "team-task-board")
            put("cardId", "task-89")
            put("rev", 3)
        }
        val payload = Codec.groupTextPayload(
            text = "答案",
            groupId = "grp-1",
            groupRev = 1,
            agentIntent = intent,
            agentState = "completed",
            card = card,
        ).toString()
        val decoded = Codec.decodeTcp(env(payload)) as? DecodeResult.Ok
            ?: fail("带三个 agent 键的群文本被入站校验拒了（白名单漏加？）：$payload")
        val obj = decoded.env.payload as JsonObject
        assertEquals("completed", (obj["agentState"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("dev-lead", ((obj["agentIntent"] as JsonObject)["agentId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(3, ((obj["card"] as JsonObject)["rev"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    /** 不传三个新键时，载荷与以前逐字节同形（键缺席，不是 `"agentIntent":null`）。 */
    @Test
    fun withoutTheNewKeysThePayloadKeepsTheOldShape() {
        val legacy = Codec.groupTextPayload("hi", "grp-1", 1).toString()
        assertFalse(legacy.contains("agentIntent"), legacy)
        assertFalse(legacy.contains("agentState"), legacy)
        assertFalse(legacy.contains("card"), legacy)
    }

    /** 信封数学：正文（32 KiB）+ 卡片（24 KiB）+ 信封开销必须仍在 64 KiB 整帧硬上限内。 */
    @Test
    fun textPlusCardStillFitsInOneFrame() {
        val frameCap = 64 * 1024
        assertTrue(
            Limits.GROUP_TEXT_TCP_LIMIT + Limits.CARD_BYTES < frameCap,
            "正文 + 卡片必须给信封开销留出余量，否则整帧被传输层拒掉",
        )
    }
}
