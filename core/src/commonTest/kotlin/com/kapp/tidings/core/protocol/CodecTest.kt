// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 协议层边界测试：TS 侧没有 golden vector 覆盖、但错了会在真实网络上翻车的地方。
 */
class CodecTest {

    /**
     * 群文本的 `senderId`（可选）：**不传时载荷必须与以前逐字节相同**。
     *
     * 这条是向后兼容的门：旧端不认识这个键，但我们的 golden vector 是一串固定的 JSON ——
     * 默认 `null` 若被序列化成 `"senderId":null`，所有老端的 golden 断言都会红，
     * 而且线上帧会白白胖 18 字节。
     */
    /**
     * 不传会话归属时，载荷形状**必须与改动前逐字一致**（老端视角不会看到新键）。
     */
    @Test
    fun groupTextWithoutSessionKeepsTheOldShape() {
        val legacy = Codec.groupTextPayload("hi", "grp-1", 1).toString()
        assertFalse(legacy.contains("agentSessionId"), legacy)
        assertFalse(legacy.contains("agentCursor"), legacy)
    }

    /**
     * 带会话归属的群文本必须**过得了入站校验**（走解码这条路，不是只看 JSON 有没有键）。
     *
     * 守的是 AGENTS.md §2.2 那条死规矩：加可选键必须同时加进 GROUP_TEXT_KEYS。
     * 漏加的话症状是：桌面发得出去、本地落库成功、SendOutcome.SENT —— 手机端一条都收不到。
     */
    @Test
    fun groupTextWithSessionSurvivesInboundValidation() {
        val payload = Codec.groupTextPayload(
            text = "agent 说的话",
            groupId = "grp-1",
            groupRev = 1,
            agentSessionId = "s-abc",
            agentCursor = 7L,
        ).toString()
        val decoded = Codec.decodeTcp(env("msg", payload)) as? DecodeResult.Ok
            ?: fail("带会话归属的群文本被入站校验拒了（白名单漏加？）：$payload")
        val obj = decoded.env.payload as JsonObject
        assertEquals("s-abc", (obj["agentSessionId"] as JsonPrimitive).content)
        assertEquals(7L, (obj["agentCursor"] as JsonPrimitive).content.toLong())
    }

    /**
     * **两者必须成对出现**：只有 sessionId 没有 cursor 的帧整帧拒收。
     *
     * 理由与 recall 的 groupId/groupRev 同一条：接收端要么猜、要么忽略；
     * 两种都不如直接拒 —— 拒收可诊断，猜错不可诊断。
     */
    @Test
    fun aSessionWithoutCursorIsRejected() {
        val payload = "{\"kind\":\"group-text\",\"text\":\"hi\",\"groupId\":\"grp-1\",\"groupRev\":1,\"agentSessionId\":\"s-abc\"}"
        assertTrue(
            Codec.decodeTcp(env("msg", payload)) !is DecodeResult.Ok,
            "只给 sessionId 不给 cursor 的帧必须被拒",
        )
    }

    @Test
    fun groupTextWithoutSenderIdKeepsTheOldShape() {
        val legacy = Codec.groupTextPayload("hi", "grp-1", 1).toString()
        assertFalse(legacy.contains("senderId"), "不传 senderId 时载荷里不该出现这个键：" + legacy)
    }

    /** 传了就必须写进去，而且读得回来（`agent:` 形态原样保留，不做大小写或前缀改写）。 */
    @Test
    fun groupTextCarriesSenderIdWhenProvided() {
        val json = Codec.groupTextPayload(
            text = "这是 agent 说的",
            groupId = "grp-1",
            groupRev = 1,
            senderId = "agent:dsh",
        ).toString()
        assertTrue(json.contains("\"senderId\""), json)
        assertTrue(json.contains("agent:dsh"), "agent: 前缀必须原样保留：" + json)
    }

    /**
     * 带 `senderId` 的群文本必须**过得了入站校验**。
     *
     * 这条是回归测试：`senderId` 曾经只加进了 data class 与编码器，**漏加进
     * `CodecMsg.GROUP_TEXT_KEYS` 白名单** ⇒ 对端 `hasOnlyKeys` 判成 extra-field 整帧拒收。
     * 症状极具迷惑性：发送侧编码成功、本地落库成功、发送返回 SENT，接收侧却一条都没有。
     * 所以断言必须走**解码**这条路，只断言「JSON 里有这个键」是抓不住的。
     */
    @Test
    fun groupTextWithSenderIdSurvivesInboundValidation() {
        val payload = Codec.groupTextPayload(
            text = "hi",
            groupId = "grp-1",
            groupRev = 1,
            senderId = "agent:dsh",
        ).toString()
        val decoded = Codec.decodeTcp(env("msg", payload)) as? DecodeResult.Ok
            ?: fail("带 senderId 的群文本被入站校验拒了：$payload")
        val obj = decoded.env.payload as JsonObject
        assertEquals("agent:dsh", (obj["senderId"] as JsonPrimitive).content)
    }

    /**
     * `reasoning` 已从群文本白名单**退场**（规格 §4.1 铁律：CoT 禁止进群）——
     * 现在带这个键的帧是 extra-field，整帧拒收。
     *
     * 这条是反向护栏：谁把 `reasoning` 加回 `GROUP_TEXT_KEYS`，这里立刻红。
     */
    @Test
    fun reasoningIsNoLongerAccepted() {
        val payload = Codec.groupTextPayload(
            text = "答案",
            groupId = "grp-1",
            groupRev = 1,
        ).toString()
        assertTrue(!payload.contains("reasoning"), "出站编码不许再产出这个键：$payload")
        val rawFrame = """{"v":1,"type":"msg","id":"33333333-3333-4333-8333-333333333333",""" +
            """"from":"11111111-1111-4111-8111-111111111111","ts":1700000000000,""" +
            """"payload":{"kind":"group-text","text":"hi","groupId":"grp-1","groupRev":1,"reasoning":"先看 A"}}"""
        assertEquals(
            RejectReason.badPayload(MsgTypes.MSG),
            (Codec.decodeTcp(rawFrame) as? DecodeResult.Rejected)?.reason,
            "带 reasoning 的群文本必须整帧拒收（CoT 不进群）",
        )
    }

    /**
     * 群文本在 **TCP** 上放宽到 `Limits.GROUP_TEXT_TCP_LIMIT`：真机丢掉的那条 4239 字节
     * 「全部完成。」必须能过。
     *
     * 回归背景：`TEXT_TCP_LIMIT = 4096` 是 UDP 时代的产物，agent 的长回复动辄 4000+ 字节，
     * 实测 **140 条群消息**超限被整帧丢弃（最大 9902 字节），手机上一条都看不到。
     */
    @Test
    fun groupTextOverTheOldTcpLimitNowDecodes() {
        val text = "全部完成。" + "汉".repeat(1400)
        assertTrue(
            Codec.utf8ByteLength(text) > Protocol.TEXT_TCP_LIMIT,
            "样本必须真的超过旧的 4096 上限（实际 " + Codec.utf8ByteLength(text) + " 字节）",
        )
        assertTrue(Codec.utf8ByteLength(text) <= Limits.GROUP_TEXT_TCP_LIMIT)
        val payload = Codec.groupTextPayload(text, "grp-1", 1).toString()
        val decoded = Codec.decodeTcp(env("msg", payload)) as? DecodeResult.Ok
            ?: fail("超 4096 字节的群文本在 TCP 上仍被拒：" + Codec.utf8ByteLength(text) + " 字节")
        assertEquals(text, ((decoded.env.payload as JsonObject)["text"] as JsonPrimitive).content)
    }

    /** 但 **UDP** 侧不许跟着放宽：800 字节那条线仍然是 800（防 IP 分片）。 */
    @Test
    fun groupTextOverTheOldTcpLimitIsStillRejectedOnUdp() {
        val text = "汉".repeat(400)
        assertTrue(Codec.utf8ByteLength(text) > Protocol.TEXT_UDP_LIMIT)
        val payload = Codec.groupTextPayload(text, "grp-1", 1).toString()
        assertEquals(
            RejectReason.badPayload(MsgTypes.MSG),
            (Codec.decode(env("msg", payload)) as? DecodeResult.Rejected)?.reason,
        )
    }

    /** 单聊文本不跟着放宽：用户手打的字，4096 足够，没必要放大攻击面。 */
    @Test
    fun directTextKeepsTheTcpLimit() {
        val text = "汉".repeat(1500)
        assertTrue(Codec.utf8ByteLength(text) > Protocol.TEXT_TCP_LIMIT)
        val payload = Codec.textPayload(text).toString()
        assertEquals(
            RejectReason.badPayload(MsgTypes.MSG),
            (Codec.decodeTcp(env("msg", payload)) as? DecodeResult.Rejected)?.reason,
        )
    }

    /** 超过 32 KiB 的群文本在 TCP 上仍被拒 ⇒ 发送侧必须先截断（`clampGroupText`）。 */
    @Test
    fun groupTextOverTheNewLimitIsStillRejected() {
        val payload = Codec.groupTextPayload(
            "汉".repeat(Limits.GROUP_TEXT_TCP_LIMIT),
            "grp-1",
            1,
        ).toString()
        assertEquals(
            RejectReason.badPayload(MsgTypes.MSG),
            (Codec.decodeTcp(env("msg", payload)) as? DecodeResult.Rejected)?.reason,
        )
    }


    private companion object {
        const val ID = "33333333-3333-4333-8333-333333333333"
        const val FROM = "11111111-1111-4111-8111-111111111111"

        val PROFILE_JSON = """
            {"nodeId":"$FROM","nick":"电脑","company":"","dept":"","team":"","avatar":5,
             "profileRev":3,"host":"MacBook-Pro","platform":"mac","tcpPort":17879,
             "ver":"0.61.1","caps":["fd1","mrec1"]}
        """.trimIndent().replace("\n", "").replace(" ", "")

        fun env(type: String, payload: String): String =
            """{"v":1,"type":"$type","id":"$ID","from":"$FROM","ts":1700000000000,"payload":$payload}"""
    }

    private fun reason(json: String): String =
        (Codec.decode(json) as? DecodeResult.Rejected)?.reason ?: fail("期望被拒绝，实际通过：$json")

    private fun ok(json: String): DecodeResult.Ok =
        Codec.decode(json) as? DecodeResult.Ok ?: fail("期望解码通过，实际被拒：$json")

    // ------------------------------------------------------------------ 信封层

    @Test
    fun envelopeRejectReasonsMatchTheWireContract() {
        assertEquals(RejectReason.NOT_OBJECT, reason("[1,2,3]"))
        assertEquals(RejectReason.BAD_JSON, reason("{not json"))
        assertEquals(RejectReason.VERSION, reason(env("msg", """{"kind":"text","text":"hi"}""").replace("\"v\":1", "\"v\":2")))
        assertEquals(
            RejectReason.NO_PAYLOAD,
            reason("""{"v":1,"type":"msg","id":"$ID","from":"$FROM","ts":1700000000000}"""),
        )
        assertEquals(
            RejectReason.BAD_TS,
            reason("""{"v":1,"type":"msg","id":"$ID","from":"$FROM","ts":0,"payload":{}}"""),
        )
        assertEquals(
            RejectReason.BAD_TYPE,
            reason("""{"v":1,"type":"","id":"$ID","from":"$FROM","ts":1,"payload":{}}"""),
        )
    }

    @Test
    fun oversizeIsRejectedBeforeParsing() {
        // 4096 字节硬上限先于 JSON 解析 —— 所以哪怕内容合法也直接 oversize。
        val huge = "x".repeat(Protocol.UDP_MAX_INBOUND + 1)
        assertEquals(RejectReason.OVERSIZE, reason(huge))
    }

    @Test
    fun tcpDecodeHasNoOversizeGate() {
        // 长文本走 TCP 通道：UDP 侧按整帧字节硬拒（oversize），TCP 侧没有这个字节闸门，
        // 文本只受 TEXT_TCP_LIMIT（4096 字节）约束。
        // 文本取满 4096 字节时，加上信封开销整帧必然超过 UDP_MAX_INBOUND。
        val long = "a".repeat(Protocol.TEXT_TCP_LIMIT)
        val json = env("msg", """{"kind":"text","text":"$long"}""")
        assertTrue(
            Codec.utf8ByteLength(json) > Protocol.UDP_MAX_INBOUND,
            "构造的帧应当超过 UDP 入站上限（含信封开销）",
        )
        assertEquals(RejectReason.OVERSIZE, reason(json))
        assertTrue(Codec.decodeTcp(json) is DecodeResult.Ok, "TCP 通道应当接受这个长度")
    }

    // ------------------------------------------------------------------ 载荷层

    @Test
    fun groupTextStrictWhitelistDropsExtraKeys() {
        val withExtra = env(
            "msg",
            """{"kind":"group-text","text":"hi","groupId":"grp-1","groupRev":1,"type":"extra"}""",
        )
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(withExtra))

        val clean = env(
            "msg",
            """{"kind":"group-text","text":"hi","groupId":"grp-1","groupRev":1}""",
        )
        assertTrue(ok(clean).known)
    }

    @Test
    fun plainTextHasNoFieldWhitelist() {
        // kind=text 在 TS 侧没有白名单：多字段被容忍，只有 group-text 才严格。
        val withExtra = env("msg", """{"kind":"text","text":"hi","whatever":1}""")
        assertTrue(ok(withExtra).known)
    }

    @Test
    fun emptyTextIsRejected() {
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env("msg", """{"kind":"text","text":""}""")))
    }

    @Test
    fun nudgeCarriesNoTextAndIsStillValid() {
        // 茶话间发戳一戳的线上形状就是 {kind:"nudge"}，没有 text。
        val nudge = env("msg", """{"kind":"nudge"}""")
        val decoded = ok(nudge)
        assertTrue(decoded.known)
        assertEquals("""{"kind":"nudge"}""", decoded.env.payload.toString())

        // 带 resend 的不是戳一戳（重发只服务于有正文的帧）。
        assertEquals(
            RejectReason.badPayload(MsgTypes.MSG),
            reason(env("msg", """{"kind":"nudge","resend":true}""")),
        )
    }

    @Test
    fun pkValidAndInvalidShapes() {
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env("msg", """{"kind":"pk"}""")))
        assertTrue(ok(env("msg", """{"kind":"pk","game":"rps","value":"rock"}""")).known)
        assertTrue(ok(env("msg", """{"kind":"pk","game":"rps","value":"scissors"}""")).known)
        assertTrue(ok(env("msg", """{"kind":"pk","game":"rps","value":"paper"}""")).known)
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env("msg", """{"kind":"pk","game":"rps","value":"foo"}""")))
        assertTrue(ok(env("msg", """{"kind":"pk","game":"dice","value":6}""")).known)
        assertTrue(ok(env("msg", """{"kind":"pk","game":"dice","value":1}""")).known)
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env("msg", """{"kind":"pk","game":"dice","value":0}""")))
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env("msg", """{"kind":"pk","game":"dice","value":7}""")))
        assertTrue(ok(env("msg", """{"kind":"pk","game":"dice","value":3,"groupId":"g-1","groupRev":2}""")).known)
        assertEquals(RejectReason.badPayload(MsgTypes.MSG), reason(env("msg", """{"kind":"pk","game":"dice","value":3,"groupId":"g-1"}""")))
    }

    @Test
    fun profileAcceptsGoldenShapeAndRejectsNullAvatarHash() {
        assertTrue(ok(env("entry", """{"profile":$PROFILE_JSON}""")).known)

        // avatarHash 存在即必须是 hex64 —— 显式 null 也算「存在」。
        val nullHash = PROFILE_JSON.replace("\"avatar\":5,", "\"avatar\":5,\"avatarHash\":null,")
        assertEquals(RejectReason.badPayload(MsgTypes.ENTRY), reason(env("entry", """{"profile":$nullHash}""")))
    }

    @Test
    fun textLimitIsCountedInUtf8BytesNotCharacters() {
        assertEquals(6, Codec.utf8ByteLength("电脑"))
        assertEquals(2, Codec.utf8ByteLength("hi"))

        // 300 个中文 = 900 字节 > 800，走 UDP 时必须被拒。
        val tooLong = "电".repeat(300)
        assertEquals(
            RejectReason.badPayload(MsgTypes.MSG),
            reason(env("msg", """{"kind":"text","text":"$tooLong"}""")),
        )
    }

    // ------------------------------------------------------------------ 编码侧

    @Test
    fun nonAsciiIsEmittedVerbatim() {
        // 如果 kotlinx 把中文转义成 \uXXXX，字节比对必然失败 —— 这条把假设钉死。
        val json = Codec.encode(makeEnvelope(MsgTypes.MSG, FROM, Codec.textPayload("电脑"), ID, 1L))
        assertTrue(json.contains("电脑"), "非 ASCII 被转义了：$json")
    }

    @Test
    fun mobileProfileMasqueradesAsLinuxAndNeverDeclaresUpdateCap() {
        val p = Profile.mobile(nodeId = ID, nick = "手机", host = "Pixel", ver = "0.1.0")
        assertEquals(Platforms.LINUX, p.platform, "协议没有 mobile 平台值，手机端伪装成 linux")
        assertFalse(p.caps.contains("upd1"), "声明 upd1 会让局域网桌面端推自更新包")
        assertTrue(p.caps.contains("tw1"), "没有 tw1 就绝不能发 wait 帧")
    }

    // ------------------------------------------------------------------ TCP 分帧

    @Test
    fun tcpFrameIsFourByteBigEndianPrefix() {
        val body = "hello".encodeToByteArray()
        val framed = TcpFrame.encode(body)

        assertEquals(TcpFrame.LENGTH_PREFIX_BYTES + body.size, framed.size)
        assertEquals(body.size, TcpFrame.decodeLength(framed))

        val twoFiftyEight = TcpFrame.encode(ByteArray(258))
        assertEquals(0, twoFiftyEight[0].toInt())
        assertEquals(0, twoFiftyEight[1].toInt())
        assertEquals(1, twoFiftyEight[2].toInt())
        assertEquals(2, twoFiftyEight[3].toInt())
    }
}
