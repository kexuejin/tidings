// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 与真实 TS 实现（teahouse 0.61.1）的**字节级**对齐测试。
 *
 * [GOLDEN_VECTORS] 由 `gen-golden-vectors.test.ts` 跑在 `/tmp/th_up/teahouse-main` 的真实
 * `encode` / `decode` 上产出，见 [GoldenVectorsData]。三条断言：
 *  1. 每条向量的解码结论（通过 / 拒绝 + 拒绝原因）与 TS 完全一致；
 *  2. 通过解码的信封**重新编码后与原始线上字节逐字节相同**（守住键序与数字格式）；
 *  3. 用出站 `@Serializable` 类型构造出的帧，与真实 TS 输出的字节相同
 *     （守住 payload 序列化器的声明顺序与「不给必现场景默认值」这条规则）。
 *
 * PPE-1 的 `agent-*` 是**本端扩展**，TS 侧没有对应实现，所以它们的向量分两处：
 * [OLD_NODE_EXTENSION_VERDICTS]（老节点视角的结论记录）与 [EXTENSION_VECTORS]（实现后的结论）。
 */
class GoldenVectorTest {

    private companion object {
        const val ID = "33333333-3333-4333-8333-333333333333"
        const val PEER = "11111111-1111-4111-8111-111111111111"
        const val TS = 1_700_000_000_000L

        val SAMPLE_PROFILE = Profile(
            nodeId = "22222222-2222-4222-8222-222222222222",
            nick = "电脑",
            company = "",
            dept = "",
            team = "",
            avatar = 5,
            profileRev = 3,
            host = "MacBook-Pro",
            platform = Platforms.MAC,
            tcpPort = 17879,
            ver = "0.61.1",
            caps = listOf("fd1", "mrec1"),
        )

        /** 按名字取向量的范围：TS 生成集 + 老节点结论 + PPE-1 扩展集。 */
        val ALL_VECTORS: List<GoldenVector> =
            GOLDEN_VECTORS + OLD_NODE_EXTENSION_VERDICTS + EXTENSION_VECTORS
    }

    private fun vector(name: String): GoldenVector =
        ALL_VECTORS.firstOrNull { it.name == name } ?: fail("golden vector 缺失：$name")

    @Test
    fun goldenVectorsComeFromTheRealCodec() {
        assertEquals("teahouse 0.61.1", GENERATED_FROM)
        // 生成集原本 16 条：其中 2 条 agent-* 已移到 OLD_NODE_EXTENSION_VERDICTS ——
        // 本端实现这两个类型之后，同一批字节的结论不再与 TS 一致，留在 parity 集里就是错的。
        assertEquals(14, GOLDEN_VECTORS.size, "向量数量变了，说明生成脚本或夹具被改动过")
        assertEquals(2, OLD_NODE_EXTENSION_VERDICTS.size, "老节点结论记录被改动过")
        assertEquals(12, EXTENSION_VECTORS.size, "PPE-1 扩展向量数量变了")
    }

    @Test
    fun everyVectorDecodesAsExpected() {
        for (v in GOLDEN_VECTORS + EXTENSION_VECTORS) {
            when (val r = Codec.decode(v.json)) {
                is DecodeResult.Ok -> {
                    assertTrue(v.decodes, "${v.name}：期望被拒绝，实际解码通过")
                    assertEquals(v.known, r.known, "${v.name}：known 与 TS 不一致")
                }

                is DecodeResult.Rejected -> {
                    assertFalse(v.decodes, "${v.name}：期望通过，实际被拒（${r.reason}）")
                    assertEquals(v.reason, r.reason, "${v.name}：拒绝原因与 TS 不一致")
                }
            }
        }
    }

    @Test
    fun decodedVectorsReencodeByteIdentical() {
        for (v in GOLDEN_VECTORS + EXTENSION_VECTORS) {
            if (!v.decodes) continue
            val ok = Codec.decode(v.json) as? DecodeResult.Ok
                ?: fail("${v.name}：应当解码通过")
            assertEquals(v.json, Codec.encode(ok.env), "${v.name}：重新编码后与线上字节不一致")
        }
    }

    @Test
    fun unknownTypesDecodeButStayUnknown() {
        // 垂直扩展的前提：本端**没实现**的扩展类型照样解码成功、known=false、静默忽略、不崩溃。
        var sawUnimplemented = false
        for (v in EXTENSION_VECTORS) {
            if (v.known || !v.decodes) continue
            sawUnimplemented = true
            val ok = Codec.decode(v.json) as? DecodeResult.Ok ?: fail("${v.name}：应当解码通过")
            assertFalse(ok.known, "${v.name}：未知类型必须 known=false")
        }
        assertTrue(sawUnimplemented, "至少要留一条「本端未实现的扩展类型」向量，否则容忍契约没人守")
    }

    @Test
    fun oldNodeVerdictsAreRecordedButWeNowRefuseThoseShapes() {
        // PPE-1 §1：老节点遇到 agent-* 解码通过、known=false、不崩（记录在案）。
        // PPE-1 §5：本端实现之后，那些松散形状必须被字段白名单拒收，不能顺着老节点的宽容收下。
        for (v in OLD_NODE_EXTENSION_VERDICTS) {
            assertTrue(v.decodes, "${v.name}：老节点视角应当解码通过")
            assertFalse(v.known, "${v.name}：老节点视角必须 known=false")
            val rejected = Codec.decode(v.json) as? DecodeResult.Rejected
                ?: fail("${v.name}：本端已实现该类型，这份形状应当被拒")
            assertTrue(
                rejected.reason == "bad-payload:agent-session" || rejected.reason == "bad-payload:agent-event",
                "${v.name}：应当因字段白名单被拒，实际是 ${rejected.reason}",
            )
        }
    }

    @Test
    fun typedPayloadsMatchGoldenBytes() {
        assertTyped("entry", MsgTypes.ENTRY, PEER, Codec.profilePayload(SAMPLE_PROFILE))
        assertTyped(
            "alive", MsgTypes.ALIVE, PEER,
            Codec.profilePayload(SAMPLE_PROFILE, probeId = ID),
        )
        assertTyped("exit", MsgTypes.EXIT, PEER, Codec.profilePayload(SAMPLE_PROFILE))
        assertTyped("presence", MsgTypes.PRESENCE, PEER, Codec.presencePayload(seq = 7, profileRev = 3))
        assertTyped("msg-text", MsgTypes.MSG, PEER, Codec.textPayload("hi"))
        assertTyped(
            "msg-text-resend", MsgTypes.MSG, PEER,
            Codec.textPayload("昨晚的构建结果", resend = true),
        )
        assertTyped(
            "msg-group-text", MsgTypes.MSG, PEER,
            Codec.groupTextPayload(
                text = "@你 这个端口被占用了",
                groupId = "grp-1",
                groupRev = 12,
                mentions = listOf(SAMPLE_PROFILE.nodeId),
                replyTo = ID,
            ),
        )
        assertTyped("ack", MsgTypes.ACK, SAMPLE_PROFILE.nodeId, Codec.ackPayload(ID))
        assertTyped(
            "agent-session", MsgTypes.AGENT_SESSION, PEER,
            Codec.agentSessionPayload(
                sessionId = "s-1a97a6f3",
                alias = "refactor runner",
                agentKind = "codex",
                state = "busy",
            ),
        )
        // 会话排序键（活动时刻）与可见性两键：键序护栏 —— `updatedAt`/`origin`/`blank`
        // 必须在 wsPath **之后**，插进中间会改动既有帧字节（老接收端的 tolerantKeys
        // 是按尾部未知键设计的 —— 追加它们，老端不丢帧）。
        assertTyped(
            "agent-session-activity", MsgTypes.AGENT_SESSION, PEER,
            Codec.agentSessionPayload(
                sessionId = "s-1a97a6f3",
                alias = "refactor runner",
                agentKind = "codex",
                state = "busy",
                startedAt = TS,
                agentId = "dsh-main",
                wsId = "ws-a1b2c3d4",
                wsTitle = "teahouse-mobile",
                wsPath = "/src/teahouse",
                updatedAt = TS,
                origin = "subagent",
                blank = false,
            ),
        )
        assertTyped(
            "agent-event", MsgTypes.AGENT_EVENT, PEER,
            Codec.agentEventPayload(
                sessionId = "s-1a97a6f3",
                cursor = 42,
                eventType = "tool.started",
                summary = "跑测试",
            ),
        )
        // PPE-1 增补 D3/D7 的出站字节护栏（键序 = Payloads.kt 声明序）。
        assertTyped(
            "agent-workspace", MsgTypes.AGENT_WORKSPACE, PEER,
            Codec.agentWorkspacePayload(
                wsId = "ws-a1b2c3d4", wsTitle = "teahouse-mobile",
                wsPath = "/src/teahouse", agentId = "dsh-cli", agentKind = "DSH",
                online = true, sessionCount = 2, at = TS, order = 3,
            ),
        )
        assertTyped(
            "agent-grant-request", MsgTypes.AGENT_GRANT_REQUEST, PEER,
            Codec.agentGrantRequestPayload(agentKey = "dsh-main", reason = "想在手机上看", at = TS),
        )
        assertTyped(
            "agent-grant", MsgTypes.AGENT_GRANT, PEER,
            Codec.agentGrantPayload(state = "approved", scope = "agent", agentKey = "dsh-main", at = TS),
        )
        assertTyped(
            "agent-grant-list", MsgTypes.AGENT_GRANT_LIST, PEER,
            Codec.agentGrantListPayload(scope = "all", total = 1, at = TS),
        )
    }

    private fun assertTyped(name: String, type: String, from: String, payload: JsonElement) {
        val v = vector(name)
        val env = makeEnvelope(type = type, from = from, payload = payload, id = ID, ts = TS)
        assertEquals(v.json, Codec.encode(env), "golden vector `$name`：出站编码与 TS 字节不一致")
    }
}
