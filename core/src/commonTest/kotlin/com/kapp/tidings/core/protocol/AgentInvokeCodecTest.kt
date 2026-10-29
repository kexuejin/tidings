// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `agent-invoke` / `agent-invoke-result` 两帧的编解码契约（M4 跨端派单网道）。
 *
 * 反向验证锚点：严格白名单（多一键整帧拒收）、op 分支的必填/禁用字段、
 * 结果帧的 status/ok/card 一致性。实现缺任何一条，对应用例红。
 */
class AgentInvokeCodecTest {

    private val invoke = buildJsonObject {
        put("invokeId", "inv-1")
        put("op", "task")
        put("agentId", "gpu-runner")
        put("prompt", "run the regression suite")
        put("groupId", "g-1")
        put("groupRev", 3)
        put("deadlineMs", 30_000L)
        put("requestedBy", "11111111-1111-4111-8111-111111111111")
    }

    private fun valid(type: String, payload: JsonObject, textLimit: Int = Protocol.TEXT_TCP_LIMIT): Boolean =
        Codec.validatePayload(type, payload, textLimit)

    // ---- MsgTypes 装配 ----

    @Test
    fun invokeFramesAreKnownReliableAndHandled() {
        assertTrue(MsgTypes.AGENT_INVOKE in MsgTypes.KNOWN)
        assertTrue(MsgTypes.AGENT_INVOKE_RESULT in MsgTypes.KNOWN)
        // 请求-结果都必须可靠投递：派单丢了靠重传兜底，结果丢了租约会误判超时。
        assertTrue(MsgTypes.AGENT_INVOKE in MsgTypes.RELIABLE)
        assertTrue(MsgTypes.AGENT_INVOKE_RESULT in MsgTypes.RELIABLE)
        assertTrue(MsgTypes.AGENT_INVOKE in MsgTypes.HANDLED)
        assertTrue(MsgTypes.AGENT_INVOKE_RESULT in MsgTypes.HANDLED)
    }

    @Test
    fun invokeCapabilityIsDeclaredByBothProfiles() {
        // 两端同版本帧：手机要能发 action 请求、宿主要能收 invoke —— 谁少声明谁被当旧端。
        assertTrue(MsgTypes.CAP_AGENT_INVOKE in MsgTypes.MOBILE_CAPS)
        assertTrue(MsgTypes.CAP_AGENT_INVOKE in MsgTypes.AGENT_CAPS)
    }

    // ---- agent-invoke ----

    @Test
    fun taskInvokeValidates() {
        assertTrue(valid(MsgTypes.AGENT_INVOKE, invoke))
    }

    @Test
    fun taskInvokeRoundTripsThroughTcpDecode() {
        val env = makeEnvelope(MsgTypes.AGENT_INVOKE, "11111111-1111-4111-8111-111111111111", invoke)
        val decoded = Codec.decodeTcp(Codec.encode(env))
        assertTrue(decoded is DecodeResult.Ok, "合法 invoke 必须能从 TCP 通道原样回来")
        assertEquals(invoke, (decoded as DecodeResult.Ok).env.payload)
    }

    @Test
    fun invokeWithExtraKeyIsRejected() {
        // 严格白名单是本仓协议的宪法（AGENTS §2.2）：多一个键，对端整帧拒收。
        val extra = JsonObject(invoke + ("inject" to kotlinx.serialization.json.JsonPrimitive("x")))
        assertFalse(valid(MsgTypes.AGENT_INVOKE, extra))
    }

    @Test
    fun invokeWithoutDeadlineIsRejected() {
        val noDeadline = JsonObject(invoke.toMap() - "deadlineMs")
        assertFalse(valid(MsgTypes.AGENT_INVOKE, noDeadline), "没有期限的派单等于永远等 —— 租约必须带上")
    }

    @Test
    fun invokeWithNonPositiveDeadlineIsRejected() {
        assertFalse(valid(MsgTypes.AGENT_INVOKE, JsonObject(invoke.toMap() + ("deadlineMs" to kotlinx.serialization.json.JsonPrimitive(0L)))))
        assertFalse(valid(MsgTypes.AGENT_INVOKE, JsonObject(invoke.toMap() + ("deadlineMs" to kotlinx.serialization.json.JsonPrimitive(-5L)))))
    }

    @Test
    fun taskInvokeWithoutPromptIsRejected() {
        val noPrompt = JsonObject(invoke.toMap() - "prompt")
        assertFalse(valid(MsgTypes.AGENT_INVOKE, noPrompt), "op=task 的正文就是任务本身，空任务没有意义")
    }

    @Test
    fun invokeWithoutRequestedByIsRejected() {
        val noSender = JsonObject(invoke.toMap() - "requestedBy")
        assertFalse(valid(MsgTypes.AGENT_INVOKE, noSender), "授权判定的主语不能缺 —— 缺了就没法判谁在请求")
    }

    @Test
    fun actionInvokeRequiresActionIdAndCardId() {
        val action = buildJsonObject {
            put("invokeId", "inv-2")
            put("op", "action")
            put("agentId", "echo")
            put("actionId", "approve")
            put("cardId", "card-9")
            put("cardRev", 2)
            put("decidedBy", "22222222-2222-4222-8222-222222222222")
            put("groupId", "g-1")
            put("deadlineMs", 15_000L)
            put("requestedBy", "11111111-1111-4111-8111-111111111111")
        }
        assertTrue(valid(MsgTypes.AGENT_INVOKE, action))
        assertFalse(valid(MsgTypes.AGENT_INVOKE, JsonObject(action.toMap() - "cardId")))
        assertFalse(valid(MsgTypes.AGENT_INVOKE, JsonObject(action.toMap() - "actionId")))
        assertFalse(valid(MsgTypes.AGENT_INVOKE, JsonObject(action.toMap() - "decidedBy")), "没有决定人的审批没有归属")
    }

    @Test
    fun actionInvokeMustNotCarryPrompt() {
        // 两种 op 的字段互斥：混写说明发送方语义混乱，宁可整帧拒收。
        val mixed = JsonObject(
            invoke.toMap() +
                ("op" to kotlinx.serialization.json.JsonPrimitive("action")) +
                ("actionId" to kotlinx.serialization.json.JsonPrimitive("a")) +
                ("cardId" to kotlinx.serialization.json.JsonPrimitive("c")) +
                ("cardRev" to kotlinx.serialization.json.JsonPrimitive(1)) +
                ("decidedBy" to kotlinx.serialization.json.JsonPrimitive("22222222-2222-4222-8222-222222222222")),
        )
        assertFalse(valid(MsgTypes.AGENT_INVOKE, mixed))
    }

    @Test
    fun invokeWithUnknownOpIsRejected() {
        val badOp = JsonObject(invoke.toMap() + ("op" to kotlinx.serialization.json.JsonPrimitive("cancel")))
        assertFalse(valid(MsgTypes.AGENT_INVOKE, badOp))
    }

    // ---- agent-invoke-result ----

    private val result = buildJsonObject {
        put("invokeId", "inv-1")
        put("ok", true)
        put("status", "done")
        put("summary", "3 steps finished")
        put(
            "card",
            buildJsonObject {
                put("cardId", "board-1")
                put("rev", 1)
            },
        )
    }

    @Test
    fun invokeResultValidates() {
        assertTrue(valid(MsgTypes.AGENT_INVOKE_RESULT, result))
    }

    @Test
    fun invokeResultRoundTripsThroughTcpDecode() {
        val env = makeEnvelope(MsgTypes.AGENT_INVOKE_RESULT, "22222222-2222-4222-8222-222222222222", result)
        val decoded = Codec.decodeTcp(Codec.encode(env))
        assertTrue(decoded is DecodeResult.Ok)
        assertEquals(result, (decoded as DecodeResult.Ok).env.payload)
    }

    @Test
    fun resultWithExtraKeyIsRejected() {
        val extra = JsonObject(result + ("events" to kotlinx.serialization.json.JsonArray(emptyList())))
        assertFalse(valid(MsgTypes.AGENT_INVOKE_RESULT, extra))
    }

    @Test
    fun doneResultMustBeOkAndCarryNoError() {
        assertFalse(
            valid(
                MsgTypes.AGENT_INVOKE_RESULT,
                JsonObject(result.toMap() + ("ok" to kotlinx.serialization.json.JsonPrimitive(false))),
            ),
            "status=done 与 ok=false 自相矛盾",
        )
    }

    @Test
    fun failedResultMustBeOkFalse() {
        val failed = buildJsonObject {
            put("invokeId", "inv-1")
            put("ok", true)
            put("status", "denied")
        }
        assertFalse(valid(MsgTypes.AGENT_INVOKE_RESULT, failed), "denied 还 ok=true 是在撒谎")
    }

    @Test
    fun cardOnlyRidesOnDone() {
        val deniedWithCard = JsonObject(
            result.toMap() +
                ("ok" to kotlinx.serialization.json.JsonPrimitive(false)) +
                ("status" to kotlinx.serialization.json.JsonPrimitive("denied")),
        )
        assertFalse(valid(MsgTypes.AGENT_INVOKE_RESULT, deniedWithCard), "被拒的派单不该带执行卡片")
    }

    @Test
    fun resultWithUnknownStatusIsRejected() {
        val bad = JsonObject(result.toMap() + ("status" to kotlinx.serialization.json.JsonPrimitive("whatever")))
        assertFalse(valid(MsgTypes.AGENT_INVOKE_RESULT, bad), "状态机是协议事实，不是自由文本")
    }

    @Test
    fun resultCardIsBudgeted() {
        // 卡片预算与群文本的 card 键同源（Limits.CARD_BYTES）：网道不是预算旁路。
        val bigCard = buildJsonObject {
            put("invokeId", "inv-1")
            put("ok", true)
            put("status", "done")
            put("card", buildJsonObject { put("blob", "x".repeat(Limits.CARD_BYTES)) })
        }
        assertFalse(valid(MsgTypes.AGENT_INVOKE_RESULT, bigCard))
    }

    // ---- JSON 产物可被宽松 JSON 解析（wire JSON 与消费方的 Json 一致性）----

    @Test
    fun payloadBuildersProduceParsableJson() {
        val built = Codec.agentInvokePayload(
            invokeId = "inv-3",
            op = "task",
            agentId = "echo",
            prompt = "hi",
            groupId = "g-1",
            groupRev = 1,
            deadlineMs = 1_000L,
            requestedBy = "11111111-1111-4111-8111-111111111111",
        )
        val parsed = Json.parseToJsonElement(built.toString())
        assertTrue(parsed is JsonObject)
        assertTrue(valid(MsgTypes.AGENT_INVOKE, parsed as JsonObject))
    }
}
