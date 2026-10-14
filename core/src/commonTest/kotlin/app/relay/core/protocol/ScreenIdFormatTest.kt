// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `screen` 帧的**线上形状**契约（2026-10-09 真机 E2E 根因修复）。
 *
 * 背景：投屏在真机上一直失败，症状是手机进全屏等待约 12s 后回落，弹
 * 「屏幕协助已结束 (连接已断开)」，而茶话间侧**连邀请弹窗都不出现**。
 * 根因是 relay 生成的两个值不合法、且**失败是静默的**：
 *
 * - `sessionId` 用的是 `localMessageId()` → `local-<seq>-<ts>`，不是 UUIDv4。
 *   茶话间 `isScreenSessionId` 是严格 UUIDv4 正则，不合格的帧在它的
 *   `decodeEnvelopeObject` 里被判 `bad-payload:screen` 丢掉 —— **在发 ACK 之前**，
 *   所以既不弹邀请也不回 ACK；发起方只能等重传耗尽，最后按 `disconnected` 收场。
 * - `token` 用的是 `localMessageId().replace("-", "")` → `local1…`，含非 hex 字符、
 *   长度也不定。茶话间 `isScreenToken` 是 `/^[0-9a-f]{32}$/`，同样被拒；
 *   TCP 侧的 `screen-open` 握手也校验它，所以即使信令通了流也起不来。
 *
 * 现场实证（2026-10-09，向运行中的茶话间 UDP 17878 发真实帧）：用
 * `local-1-1791535251098` 发出 → 无 ACK；换成 UUIDv4 → 立刻收到
 * `{"type":"ack","ackFor":…,"from":"95d856ac-…"}`。
 *
 * 这组用例钉的是「relay 生成的东西必须能过茶话间的闸门」，即协议**对称性**：
 * 原来 relay 自己的 `validateScreen` 只查 `isStr(..., Limits.ID)`，比对面松得多，
 * 于是自测全绿、跨端全死。收紧后任何回归都会在本机就被拒。
 */
class ScreenIdFormatTest {

    private companion object {
        const val FROM = "11111111-1111-4111-8111-111111111111"
        const val OTHER = "22222222-2222-4222-8222-222222222222"

        /** 真机上被茶话间丢弃的那个真实值（`localMessageId()` 的产物）。 */
        const val RELAY_OLD_SESSION_ID = "local-1-1791535251098"

        fun env(type: String, payload: String): String =
            """{"v":1,"type":"$type","id":"$OTHER","from":"$FROM","ts":1700000000000,"payload":$payload}"""
    }

    private fun reason(json: String): String =
        (Codec.decode(json) as? DecodeResult.Rejected)?.reason ?: fail("期望被拒绝，实际通过：$json")

    private fun screenPayload(o: JsonObject): Boolean = Codec.validatePayload(MsgTypes.SCREEN, o)

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })

    // ------------------------------------------------------------ id/token 生成器

    @Test
    fun newIdIsUuidV4() {
        repeat(50) {
            val id = newId()
            assertTrue(isUuidV4(id), "newId() 必须产出 UUIDv4，实际 $id")
            assertEquals(36, id.length)
        }
    }

    @Test
    fun newScreenTokenIs32LowercaseHex() {
        repeat(50) {
            val token = newScreenToken()
            assertTrue(isScreenToken(token), "newScreenToken() 必须产出 32 位小写 hex，实际 $token")
            assertEquals(32, token.length)
            assertEquals(token.lowercase(), token, "token 必须是小写")
        }
    }

    /**
     * 旧写法就是坏的：`localMessageId().replace("-", "")` 产出含 `l/o/c/a` 的串。
     * 这条用例把「那串东西必须被判非法」钉死，防止有人再拿本地 id 当 token。
     */
    @Test
    fun oldLocalIdDerivedValuesAreRejected() {
        assertFalse(isUuidV4(RELAY_OLD_SESSION_ID), "local-<seq>-<ts> 不是 UUIDv4")
        assertFalse(isUuidV4("sess-1"), "测试夹具用的占位串不是 UUIDv4")

        assertFalse(isScreenToken("local11791535251098"), "含非 hex 字符的串不是 token")
        assertFalse(isScreenToken(RELAY_OLD_SESSION_ID), "带横线的串不是 token")
        assertFalse(isScreenToken(newId()), "UUID 本体（带横线）不是 token")
        assertFalse(isScreenToken(newScreenToken().uppercase()), "大写不算")
        assertFalse(isScreenToken("a".repeat(31)), "31 位不算")
        assertFalse(isScreenToken("a".repeat(33)), "33 位不算")
    }

    /** UUID 去横线正好 32 位小写 hex —— 这正是 [newScreenToken] 的实现依据。 */
    @Test
    fun uuidWithoutDashesIsAValidToken() {
        val stripped = newId().replace("-", "")
        assertEquals(32, stripped.length)
        assertTrue(isScreenToken(stripped))
    }

    // ------------------------------------------------------------ 严格形状校验

    @Test
    fun screenRequestFromRelayOldFormatIsRejectedLocally() {
        val payload = obj("op" to ScreenOps.REQUEST, "sessionId" to RELAY_OLD_SESSION_ID)
        assertFalse(
            screenPayload(payload),
            "旧的 sessionId 必须在本机就被拒：放进线上只会被茶话间静默丢弃，症状是「点了投屏，对方毫无反应」",
        )
    }

    @Test
    fun screenRequestFromProperConstructorIsAccepted() {
        val payload = CodecScreen.screenRequestPayload(newId()) as JsonObject
        assertTrue(screenPayload(payload), "screenRequestPayload(newId()) 必须自洽通过：$payload")
    }

    @Test
    fun screenAcceptFromProperConstructorIsAccepted() {
        val payload = CodecScreen.screenAcceptPayload(newId(), newScreenToken()) as JsonObject
        assertTrue(screenPayload(payload), "screenAcceptPayload 必须自洽通过：$payload")
    }

    @Test
    fun screenRejectAndEndFromProperConstructorsAreAccepted() {
        val reject = CodecScreen.screenRejectPayload(newId(), ScreenRejectReasons.DECLINED) as JsonObject
        assertTrue(screenPayload(reject), "screenRejectPayload 必须自洽通过：$reject")

        val end = CodecScreen.screenEndPayload(newId(), ScreenEndReasons.DISCONNECTED) as JsonObject
        assertTrue(screenPayload(end), "screenEndPayload 必须自洽通过：$end")
    }

    /**
     * 「构造器产出的帧必须能过自己的校验器」——把整条自洽性遍历一遍。
     * 这是最容易出现「自己发得出、对端收不下」的地方，也是本次事故的形态。
     */
    @Test
    fun everythingTheConstructorsEmitPassesOurOwnValidator() {
        repeat(20) {
            val sid = newId()
            assertTrue(screenPayload(CodecScreen.screenRequestPayload(sid) as JsonObject), "request")
            assertTrue(screenPayload(CodecScreen.screenAcceptPayload(sid, newScreenToken()) as JsonObject), "accept")
            assertTrue(screenPayload(CodecScreen.screenRejectPayload(sid, ScreenRejectReasons.BUSY) as JsonObject), "reject")
            assertTrue(screenPayload(CodecScreen.screenEndPayload(sid, ScreenEndReasons.USER) as JsonObject), "end")
        }
    }

    // ------------------------------------------------------------ 键数纪律

    /** 茶话间对 request 钉的是 `keys.length === 2`：多一个键整帧被拒。 */
    @Test
    fun screenRequestRejectsExtraKeys() {
        val payload = obj(
            "op" to ScreenOps.REQUEST,
            "sessionId" to newId(),
            "token" to newScreenToken(),
        )
        assertFalse(screenPayload(payload), "request 多带一个键，茶话间判 keys.length !== 2 而丢帧")
    }

    @Test
    fun screenAcceptRejectsMissingToken() {
        val payload = obj("op" to ScreenOps.ACCEPT, "sessionId" to newId())
        assertFalse(screenPayload(payload), "accept 缺 token 必须被拒")
    }

    @Test
    fun screenAcceptRejectsBadTokenFormat() {
        val payload = obj(
            "op" to ScreenOps.ACCEPT,
            "sessionId" to newId(),
            "token" to "local11791535251098",
        )
        assertFalse(screenPayload(payload), "accept 带非 hex token 必须被拒")
    }

    @Test
    fun screenRejectAndEndRejectUnknownReason() {
        assertFalse(
            screenPayload(obj("op" to ScreenOps.REJECT, "sessionId" to newId(), "reason" to "nope")),
            "未知 reject reason 必须被拒",
        )
        assertFalse(
            screenPayload(obj("op" to ScreenOps.END, "sessionId" to newId(), "reason" to "nope")),
            "未知 end reason 必须被拒",
        )
    }

    @Test
    fun screenRejectsUnknownOp() {
        assertFalse(
            screenPayload(obj("op" to "pause", "sessionId" to newId())),
            "未知 op 必须被拒",
        )
    }

    // ------------------------------------------------------------ 整帧闸门

    /**
     * 端到端形状：合法帧能解码，坏帧在 `Codec.decode` 处就判 `bad-payload:screen`，
     * 与茶话间 `decodeEnvelopeObject` 的判词**逐字一致** —— 这样排查时两端的日志能对上。
     */
    @Test
    fun envelopeGateMirrorsTeahouseRejectReason() {
        val good = Codec.encode(
            Envelope(
                v = Protocol.VERSION,
                type = MsgTypes.SCREEN,
                id = OTHER,
                from = FROM,
                ts = 1700000000000L,
                payload = CodecScreen.screenRequestPayload(newId()),
            ),
        )
        assertTrue(Codec.decode(good) is DecodeResult.Ok, "合法 screen 帧必须解码通过：$good")

        val bad = env("screen", """{"op":"request","sessionId":"$RELAY_OLD_SESSION_ID"}""")
        assertEquals(
            RejectReason.badPayload(MsgTypes.SCREEN),
            reason(bad),
            "坏 sessionId 的判词必须与茶话间一致（bad-payload:screen）",
        )
    }

    /** 解码器与校验器对同一批帧必须同进同退，不能一边放行一边丢。 */
    @Test
    fun validatorAndDecoderAgree() {
        val sid = newId()
        val frames = listOf(
            CodecScreen.screenRequestPayload(sid),
            CodecScreen.screenAcceptPayload(sid, newScreenToken()),
        )
        for (payload in frames) {
            val o = payload as? JsonObject ?: fail("构造器应产出 JsonObject")
            assertTrue(screenPayload(o), "校验器应放行：$o")
            assertNotNull(CodecScreen.decodeScreenMessage(o), "解码器应认它：$o")
        }
    }
}
