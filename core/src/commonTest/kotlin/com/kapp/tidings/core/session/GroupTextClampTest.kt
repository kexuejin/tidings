// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Limits
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群消息进群前的**字节截断**：正文（[clampGroupText]）与思考过程（[clampGroupReasoning]）。
 *
 * 为什么值得单独测：接收侧对这两个字段做的都是**字节上限校验**，超限 = 整帧丢弃。
 * 截断本身写错的后果不是「短一点」，而是三种静默丢失 ——
 * 按码元数截（中文 3 字节/字）会得到**仍然超限**的字符串；在代理对中间切会留下半个字符；
 * 上限取小了则「正文 + 思考」一起把 64 KiB 的整帧顶爆。
 */
class GroupTextClampTest {

    @Test
    fun shortReasoningIsUntouched() {
        assertEquals("先看 A 再看 B", clampGroupReasoning("先看 A 再看 B"))
    }

    /** 空/空白思考过程一律当作「没有」，别把一个空字符串送上线路。 */
    @Test
    fun blankReasoningBecomesNull() {
        assertNull(clampGroupReasoning(null))
        assertNull(clampGroupReasoning(""))
        assertNull(clampGroupReasoning("   \n\t"))
    }

    /** 超长思考过程按 UTF-8 字节截到上限以内，并且**过得了接收侧校验**。 */
    @Test
    fun overlongReasoningIsCutToTheByteLimit() {
        val clamped = clampGroupReasoning("汉".repeat(Limits.GROUP_REASONING_BYTES))!!
        val bytes = Codec.utf8ByteLength(clamped)
        assertTrue(bytes <= Limits.GROUP_REASONING_BYTES, "截断后仍超限：" + bytes + " 字节")
        assertTrue(clamped.endsWith("…"), "截断处必须留下省略号")
        assertTrue(
            Codec.validatePayload(
                MsgTypes.MSG,
                Codec.groupTextPayload("hi", "grp-1", 1, reasoning = clamped),
                Protocol.TEXT_TCP_LIMIT,
            ),
            "截断后的思考过程必须过得了入站校验",
        )
    }

    /** 一个 3 字节字符都不该被留下半个：截断点必须落在码点边界上。 */
    @Test
    fun cuttingNeverLeavesAHalfSurrogatePair() {
        val clamped = clampGroupReasoning("😀".repeat(100_000))!!
        assertTrue(Codec.utf8ByteLength(clamped) <= Limits.GROUP_REASONING_BYTES)
        assertNoLoneSurrogate(clamped.removeSuffix("…"))
    }

    /** 刚好等于上限时不该截断（边界是「超过才截」）。 */
    @Test
    fun exactlyAtTheLimitIsNotCut() {
        val exact = "a".repeat(Limits.GROUP_REASONING_BYTES)
        assertEquals(exact, clampGroupReasoning(exact))
        assertTrue(!clampGroupReasoning(exact)!!.endsWith("…"))
    }

    // ------------------------------------------------------------------ 正文

    /**
     * 正常长度的正文**原样**返回。
     *
     * 特别是不做 trim：那会改掉用户可见内容（尾部换行是排版的一部分），
     * 而这里唯一的职责是「别超限」。
     */
    @Test
    fun ordinaryTextIsReturnedVerbatim() {
        val text = "## 六项修复\n\n带尾随换行\n"
        assertEquals(text, clampGroupText(text))
    }

    /**
     * 回归：**4239 字节**这条必须原样发得出去。
     *
     * 这是真机上被丢掉的那条「全部完成。」（2530 字 / 4239 字节）—— 只超了旧的
     * 4096 上限 143 字节，于是整帧被对端判成 bad-payload，手机上永远看不到。
     */
    @Test
    fun theRealDroppedMessageIsNotCutAnyMore() {
        val head = "全部完成。"
        val text = head + "汉".repeat((4239 - Codec.utf8ByteLength(head)) / 3)
        assertTrue(
            Codec.utf8ByteLength(text) > Protocol.TEXT_TCP_LIMIT,
            "这条必须真的超过旧的 4096 上限（实际 " + Codec.utf8ByteLength(text) + " 字节）",
        )
        assertEquals(text, clampGroupText(text), "32 KiB 上限内不该动它一个字符")
    }

    /** 超过 32 KiB 的正文被截到上限内，且截断后**过得了接收侧校验**。 */
    @Test
    fun overlongTextIsCutToTheByteLimit() {
        val clamped = clampGroupText("汉".repeat(Limits.GROUP_TEXT_TCP_LIMIT))
        val bytes = Codec.utf8ByteLength(clamped)
        assertTrue(bytes <= Limits.GROUP_TEXT_TCP_LIMIT, "截断后仍超限：" + bytes + " 字节")
        assertTrue(clamped.endsWith("…"), "截断处必须留下省略号")
        assertTrue(
            Codec.validatePayload(
                MsgTypes.MSG,
                Codec.groupTextPayload(clamped, "grp-1", 1),
                Protocol.TEXT_TCP_LIMIT,
            ),
            "截断后的正文必须过得了入站校验",
        )
    }

    /** 正文截断同样不许在代理对中间切。 */
    @Test
    fun textCuttingNeverLeavesAHalfSurrogatePair() {
        val clamped = clampGroupText("😀".repeat(100_000))
        assertTrue(Codec.utf8ByteLength(clamped) <= Limits.GROUP_TEXT_TCP_LIMIT)
        assertNoLoneSurrogate(clamped.removeSuffix("…"))
    }

    /** 正文（32 KiB）+ 思考过程（24 KiB）合起来必须仍在 64 KiB 的整帧上限之内。 */
    @Test
    fun textPlusReasoningStillFitsInOneFrame() {
        val frameCap = 64 * 1024
        assertTrue(
            Limits.GROUP_TEXT_TCP_LIMIT + Limits.GROUP_REASONING_BYTES < frameCap,
            "正文 + 思考必须给信封开销留出余量，否则整帧被传输层拒掉",
        )
    }

    private fun assertNoLoneSurrogate(body: String) {
        assertTrue(body.isNotEmpty(), "至少该留下一个完整字符")
        var i = 0
        while (i < body.length) {
            val high = body[i].isHighSurrogate()
            val low = body[i].isLowSurrogate()
            assertTrue(!low || (i > 0 && body[i - 1].isHighSurrogate()), "第 " + i + " 位出现孤立低位代理")
            if (high) {
                assertTrue(i + 1 < body.length && body[i + 1].isLowSurrogate(), "第 " + i + " 位出现孤立高位代理")
                i += 2
            } else {
                i += 1
            }
        }
    }
}
