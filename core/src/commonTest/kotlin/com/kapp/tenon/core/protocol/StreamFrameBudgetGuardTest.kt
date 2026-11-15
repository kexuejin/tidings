// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import com.kapp.tenon.core.protocol.TcpFrames
import com.kapp.tenon.core.session.clampStreamChunkText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 支线日志流三帧（Phase 1）的帧预算守卫。
 *
 * 守的是两条「发送侧不产出注定被拒的帧」不变量：
 * 1. `stream-chunk` 的 body 严格 ≤ [Limits.STREAM_CHUNK_BODY]（接收侧同一份数值，
 *    超限整帧 bad-payload）；
 * 2. 编码后的整帧 ≤ [Limits.MAX_FRAME_BYTES] —— 终端输出引号/控制符密集，JSON 转义
 *    逐字符翻倍，「字节数达标的 body」照样可能编出超帧，超帧帧在对端 framing 层按
 *    peer-closed **拆整条链**（ARP-17 / `clampAgentEventBody` 的同一现场）。
 *
 * 以及同族纪律：截断绝不产出 U+FFFD 乱码替换符（码点边界截断）。
 */
class StreamFrameBudgetGuardTest {

    /**
     * 量「假设这个 body 上线，整帧多少字节」。**不走带 require 的构造器**：
     * 反向锚点与接收侧行为测试都要量「注定被拒」的超限帧，那正是构造器按不变量
     * 拒绝的输入 —— 量具必须能表达错的样子，否则测不到东西。
     */
    private fun frameBytes(body: String): Int =
        TcpFrames.msgFrameWireBytes(
            makeEnvelope(
                MsgTypes.STREAM_CHUNK, "self",
                buildJsonObject {
                    put("questId", "q-1")
                    put("seq", 1L)
                    put("text", body)
                },
            ),
        )

    /** 信封字节数（不含 msg 帧包装）——窗口探测用（code review #2 的落窗场景）。 */
    private fun envelopeBytes(body: String): Int =
        Codec.encodeToBytes(
            makeEnvelope(
                MsgTypes.STREAM_CHUNK, "self",
                buildJsonObject {
                    put("questId", "q-1")
                    put("seq", 1L)
                    put("text", body)
                },
            ),
        ).size

    private fun encodedEnvelope(text: String, seq: Long = 1, questId: String = "q-1"): String =
        Codec.encode(
            makeEnvelope(
                MsgTypes.STREAM_CHUNK, "self",
                buildJsonObject {
                    put("questId", questId)
                    put("seq", seq)
                    put("text", text)
                },
            ),
        )

    /** 引号密集的 40 KiB 文本（CLI 日志/代码的真实形态）。 */
    private fun quoteDenseText(units: Int): String = "\"".repeat(units)

    @Test
    fun clampKeepsEncodedFrameWithinBudgetAndNeverProducesReplacementChar() {
        val raw = quoteDenseText(40_000) // 40 KiB 的引号：转义后 ~80 KiB，必然超 64 KiB 帧帽
        // 反向锚点：不钳制的原始文本编出的帧**确实**超预算（守卫必须能区分对错）。
        assertTrue(frameBytes(raw) > Limits.MAX_FRAME_BYTES, "夹具本身应当编出超帧，否则这条测试没在测东西")

        val clamped = clampStreamChunkText(raw) { frameBytes(it) }

        assertTrue(
            Codec.utf8ByteLength(clamped) <= Limits.STREAM_CHUNK_BODY,
            "第一刀：body 必须卡在 STREAM_CHUNK_BODY 内",
        )
        assertTrue(
            frameBytes(clamped) <= Limits.MAX_FRAME_BYTES,
            "第二刀：编码后的整帧必须 ≤ MAX_FRAME_BYTES",
        )
        assertFalse(clamped.contains('\uFFFD'), "截断绝不产出乱码替换符")
    }

    /**
     * code review #2 的窗口用例：msg 帧比信封多 26 字节包装——信封落在
     * (MAX-26, MAX] 的块通过「量裸信封」的旧夹刀，接收侧却因线框 > MAX 整链判死。
     * 探测一段**信封在窗口内、线框超预算**的引号密集文本，断言夹刀后线框回到
     * 预算内——量具换线框实测后这条才可能通过（反向验证：量裸信封的旧夹刀对
     * 这段文本返回原样，断言立刻红）。
     */
    @Test
    fun clampHandlesTheEnvelopeVsWireFrameWindow(): Unit = kotlinx.coroutines.test.runTest {
        var windowed: String? = null
        for (n in 1_000..40_000 step 7) {
            val raw = quoteDenseText(n)
            val env = envelopeBytes(raw)
            val wire = frameBytes(raw)
            if (env <= Limits.MAX_FRAME_BYTES && wire > Limits.MAX_FRAME_BYTES) {
                windowed = raw
                break
            }
        }
        assertNotNull(windowed, "未探测到落窗文本——量具假设失效，本用例需重审")
        val clamped = clampStreamChunkText(windowed) { frameBytes(it) }
        assertTrue(
            frameBytes(clamped) <= Limits.MAX_FRAME_BYTES,
            "窗口内的块夹刀后线框必须回到预算内（接收侧整链判死的根因）",
        )
        assertTrue(Codec.utf8ByteLength(clamped) <= Limits.STREAM_CHUNK_BODY)
    }

    @Test
    fun clampTruncatesAtCodePointBoundaryForSurrogatePairs() {
        // 表情（4 字节 UTF-8 / 2 码元 UTF-16）密集的文本：切在代理对中间 = U+FFFD。
        val raw = "🚀".repeat(12_000) // ~48 KiB
        val clamped = clampStreamChunkText(raw) { frameBytes(it) }
        assertTrue(Codec.utf8ByteLength(clamped) <= Limits.STREAM_CHUNK_BODY)
        assertFalse(clamped.contains('\uFFFD'), "码点边界截断：半个代理对都不许留")
        // 钳过的文本必须仍能构造合法负载（require 不会再崩）。
        Codec.streamChunkPayload("q-1", 1, clamped)
    }

    @Test
    fun withinBudgetTextPassesThroughUnchanged() {
        val raw = "plain build log line\n"
        val clamped = clampStreamChunkText(raw) { frameBytes(it) }
        assertEquals(raw, clamped, "预算内的正文原样返回（不做任何 trim）")
    }

    @Test
    fun oversizeBodyIsRejectedOnWireButAcceptedWithinBudget() {
        val oversize = "x".repeat(Limits.STREAM_CHUNK_BODY + 1)
        val oversizeJson = encodedEnvelope(oversize)
        val rejected = Codec.decodeTcp(oversizeJson)
        assertTrue(rejected is DecodeResult.Rejected, "body 超限必须整帧拒收")
        assertEquals("bad-payload:stream-chunk", rejected.reason)

        val within = encodedEnvelope("x".repeat(Limits.STREAM_CHUNK_BODY))
        val ok = Codec.decodeTcp(within)
        assertTrue(ok is DecodeResult.Ok, "恰好压线的 body 必须收下")
        assertEquals(MsgTypes.STREAM_CHUNK, ok.env.type)
    }

    @Test
    fun udpSizedChunkNeverEvenReachesPayloadValidation() {
        // 32 KiB 的块从 UDP 入口进来：信封级 OVERSIZE 直接拒 —— 这就是「chunk 严禁走 UDP」
        // 的接收侧兜底（发送侧从不这么发；万一有人发，这里也不会收）。
        val oversize = "x".repeat(5_000)
        val result = Codec.decode(encodedEnvelope(oversize))
        assertTrue(result is DecodeResult.Rejected)
        assertEquals(RejectReason.OVERSIZE, result.reason)
    }

    @Test
    fun builderRejectsBadShapesAtSendSide() {
        // seq 必须从 1 起单调。
        assertFailsWith<IllegalArgumentException> { Codec.streamChunkPayload("q-1", seq = 0, text = "hi") }
        // 空批不发（没有信息量的帧只消耗预算）。
        assertFailsWith<IllegalArgumentException> { Codec.streamChunkPayload("q-1", seq = 1, text = "") }
        // body 超预算在构造期就崩 —— 不允许「先构造再钳」的次序倒置。
        assertFailsWith<IllegalArgumentException> {
            Codec.streamChunkPayload("q-1", seq = 1, text = "x".repeat(Limits.STREAM_CHUNK_BODY + 1))
        }
        // questId 形状。
        assertFailsWith<IllegalArgumentException> { Codec.streamSubPayload(questId = "") }
        assertFailsWith<IllegalArgumentException> { Codec.streamCancelPayload(questId = "  ") }
    }

    @Test
    fun validatorAcceptsCanonicalShapes() {
        val sub = Codec.decode(
            Codec.encode(makeEnvelope(MsgTypes.STREAM_SUB, "self", Codec.streamSubPayload("q-1", sinceSeq = 5))),
        )
        assertTrue(sub is DecodeResult.Ok)

        // sinceSeq 缺省 = 从缓冲最早一批开始补。
        val subNoSince = Codec.decode(
            Codec.encode(makeEnvelope(MsgTypes.STREAM_SUB, "self", Codec.streamSubPayload("q-1"))),
        )
        assertTrue(subNoSince is DecodeResult.Ok)

        val cancel = Codec.decode(
            Codec.encode(makeEnvelope(MsgTypes.STREAM_CANCEL, "self", Codec.streamCancelPayload("q-1"))),
        )
        assertTrue(cancel is DecodeResult.Ok)

        // sinceSeq < 0 整帧拒收。
        val negSince = Codec.decodeTcp(
            Codec.encode(
                makeEnvelope(
                    MsgTypes.STREAM_SUB, "self",
                    buildJsonObject {
                        put("questId", "q-1")
                        put("sinceSeq", -1)
                    },
                ),
            ),
        )
        assertTrue(negSince is DecodeResult.Rejected, "sinceSeq < 0 必须整帧拒收")
        assertEquals("bad-payload:stream-sub", negSince.reason)
    }
}
