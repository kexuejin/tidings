// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.session

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Limits
import app.relay.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 思考过程进群前的截断（[clampGroupReasoning]）。
 *
 * 为什么值得单独测：接收侧对 `reasoning` 做的是**字节上限校验**，超限 = 整帧丢弃。
 * 截断本身写错的后果不是「短一点」，而是两种静默丢失 ——
 * 按码元数截（中文 3 字节/字）会得到**仍然超限**的字符串；在代理对中间切会留下半个字符。
 */
class GroupReasoningClampTest {

    @Test
    fun shortReasoningIsUntouched() {
        assertEquals("先看 A 再看 B", clampGroupReasoning("先看 A 再看 B"))
    }

    /** 空/空白思考过程一律当作"没有"，别把一个空字符串送上线路。 */
    @Test
    fun blankReasoningBecomesNull() {
        assertNull(clampGroupReasoning(null))
        assertNull(clampGroupReasoning(""))
        assertNull(clampGroupReasoning("   \n\t"))
    }

    /** 超长按 UTF-8 字节截到上限以内，并且**过得了接收侧校验**。 */
    @Test
    fun overlongReasoningIsCutToTheByteLimit() {
        val clamped = clampGroupReasoning("汉".repeat(Limits.GROUP_REASONING_BYTES))!!
        val bytes = Codec.utf8ByteLength(clamped)
        assertTrue(bytes <= Limits.GROUP_REASONING_BYTES, "截断后仍超限：$bytes 字节")
        assertTrue(clamped.endsWith("…"), "截断处必须留下省略号")
        assertTrue(
            Codec.validatePayload(
                MsgTypes.MSG,
                Codec.groupTextPayload("hi", "grp-1", 1, reasoning = clamped),
                app.relay.core.protocol.Protocol.TEXT_TCP_LIMIT,
            ),
            "截断后的思考过程必须过得了入站校验",
        )
    }

    /** 一个 3 字节字符都不该被留下半个：截断点必须落在码点边界上。 */
    @Test
    fun cuttingNeverLeavesAHalfSurrogatePair() {
        val clamped = clampGroupReasoning("😀".repeat(100_000))!!
        assertTrue(Codec.utf8ByteLength(clamped) <= Limits.GROUP_REASONING_BYTES)
        val body = clamped.removeSuffix("…")
        assertTrue(body.isNotEmpty(), "至少该留下一个完整字符")
        var i = 0
        while (i < body.length) {
            val high = body[i].isHighSurrogate()
            val low = body[i].isLowSurrogate()
            assertTrue(!low || (i > 0 && body[i - 1].isHighSurrogate()), "第 $i 位出现孤立低位代理")
            if (high) {
                assertTrue(i + 1 < body.length && body[i + 1].isLowSurrogate(), "第 $i 位出现孤立高位代理")
                i += 2
            } else {
                i += 1
            }
        }
    }

    /** 刚好等于上限时不该截断（边界是"超过才截"）。 */
    @Test
    fun exactlyAtTheLimitIsNotCut() {
        val exact = "a".repeat(Limits.GROUP_REASONING_BYTES)
        assertEquals(exact, clampGroupReasoning(exact))
        assertTrue(!clampGroupReasoning(exact)!!.endsWith("…"))
    }
}
