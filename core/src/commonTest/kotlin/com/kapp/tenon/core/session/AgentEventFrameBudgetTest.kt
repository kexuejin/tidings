// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.session

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.makeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * agent-event 的**帧预算**（ARP-17）。
 *
 * 为什么值得单独测：`AGENT_BODY_TCP_LIMIT` 与整帧硬上限（[Limits.MAX_FRAME_BYTES]）
 * 是同一个 64 KiB —— body「合法」按定义就**不能**保证信封合法。而超帧在接收侧
 * 不是「截断」而是 framing 层 `len > MAX_FRAME_BYTES` ⇒ 返回 null ⇒ 对端按
 * peer-closed **拆整条链**；离线队列重放还会循环发同一毒帧。
 *
 * 更隐蔽的一层是 JSON 转义：`"` / `\` / 换行每个字符在帧里占两个字节，而这三样
 * 恰好是 CLI 输出（代码块、diff、日志）的常客。所以「按 body 字节数截断」这个
 * 看起来天经地义的做法，在引号密集的输入上会给出**两倍于上限**的帧。
 *
 * 判据因此写成「编码后的帧」而不是「body 的字节数」：只有真编码器说了才算。
 */
class AgentEventFrameBudgetTest {

    // ---------------------------------------------------------------- 夹具

    private fun envelope(body: String?, id: String = "fixed-id", ts: Long = 1_700_000_000_000L) =
        makeEnvelope(
            MsgTypes.AGENT_EVENT,
            "me",
            Codec.agentEventPayload("sess-1", 7, "output", "summary", body),
            id = id,
            ts = ts,
        )

    /** 真编码器量出来的帧长 —— 不复刻转义规则，避免「量具与被测物各说一套」。 */
    private fun frameBytes(body: String?): Int = Codec.encode(envelope(body)).encodeToByteArray().size

    private fun clamp(body: String?): String? = clampAgentEventBody(body, frameBytes = { frameBytes(it) })

    private fun assertFrameFits(body: String?) {
        val encoded = Codec.encode(envelope(body))
        val size = encoded.encodeToByteArray().size
        assertTrue(
            size <= Limits.MAX_FRAME_BYTES,
            "编码后 $size 字节，超过整帧硬上限 ${Limits.MAX_FRAME_BYTES} —— 对端会按 peer-closed 断链",
        )
        assertNotNull(Codec.decodeTcp(encoded), "这一帧必须过得了接收侧的 TCP 解码")
    }

    // ---------------------------------------------------------------- 用例

    /** 正常长度的 body **原样**返回（不做 trim，也不因「反正要截」而无条件截）。 */
    @Test
    fun ordinaryBodyIsReturnedVerbatim() {
        val body = "## 六项修复\n\n带尾随换行\n"
        assertEquals(body, clamp(body))
    }

    @Test
    fun nullStaysNull() {
        assertEquals(null, clamp(null))
    }

    /**
     * 66 KiB 的纯文本（无转义字符）：光这一条就已经是「按 body 上限合法、按帧超限」，
     * 旧口径（只按 body 字节截到 65 024）在这里过的原因是 headroom 够，但见下一条。
     */
    @Test
    fun overlongPlainBodyIsCutIntoTheFrameBudget() {
        val body = "汉".repeat(Limits.AGENT_BODY_TCP_LIMIT)
        val clamped = assertNotNull(clamp(body))
        assertTrue(clamped.length < body.length, "超限必须真的被截")
        assertFrameFits(clamped)
        assertTrue(clamped.endsWith("…"), "截断处必须留下省略号")
    }

    /**
     * **本命**：65 024 字节的引号（每个转义成 `\"`，帧里翻倍）。
     *
     * 这条在旧实现上是红的 —— 原始字节刚好卡在 `AGENT_BODY_TCP_LIMIT − 512` 以内，
     * 于是原样发出；转义后帧约 130 KB，对端整条链被拆。判据只能靠真编码器。
     */
    @Test
    fun quoteHeavyBodyIsClampedByEncodedFrameNotByRawBytes() {
        val body = "\"".repeat(AGENT_EVENT_BODY_SEND_LIMIT)
        assertTrue(
            Codec.utf8ByteLength(body) <= AGENT_EVENT_BODY_SEND_LIMIT,
            "夹具前提：原始字节在旧的钳制值以内（旧实现因此原样放行）",
        )
        assertTrue(frameBytes(body) > Limits.MAX_FRAME_BYTES, "夹具前提：转义后确实超帧（实际 ${frameBytes(body)} 字节）")

        val clamped = assertNotNull(clamp(body))
        assertFrameFits(clamped)
        assertTrue(clamped.length < body.length, "这条必须真的被截，否则就是把毒帧发出去")
    }

    /** 反斜杠与换行同族（CLI 日志与代码 diff 的常态分布）。 */
    @Test
    fun backslashAndNewlineHeavyBodyAlsoFits() {
        val body = "C:\\tmp\\out\n".repeat(6_000)
        assertTrue(frameBytes(body) > Limits.MAX_FRAME_BYTES, "夹具前提：这一份确实超帧")
        assertFrameFits(assertNotNull(clamp(body)))
    }

    /** 截断不许把代理对切成半个字符（与 [clampGroupText] 同一条纪律）。 */
    @Test
    fun cuttingNeverLeavesAHalfSurrogatePair() {
        val body = "\"😀".repeat(40_000)
        val clamped = assertNotNull(clamp(body))
        assertFrameFits(clamped)
        val kept = clamped.removeSuffix("…")
        var i = 0
        while (i < kept.length) {
            val high = kept[i].isHighSurrogate()
            val low = kept[i].isLowSurrogate()
            assertTrue(!low || (i > 0 && kept[i - 1].isHighSurrogate()), "第 $i 位出现孤立低位代理")
            if (high) {
                assertTrue(i + 1 < kept.length && kept[i + 1].isLowSurrogate(), "第 $i 位出现孤立高位代理")
                i += 2
            } else {
                i += 1
            }
        }
    }
}
