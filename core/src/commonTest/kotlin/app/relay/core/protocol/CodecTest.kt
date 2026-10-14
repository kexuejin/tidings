// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 协议层边界测试：TS 侧没有 golden vector 覆盖、但错了会在真实网络上翻车的地方。
 */
class CodecTest {

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
