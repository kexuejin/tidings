// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Limits
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 群消息**正文**进群前的字节截断（[clampGroupText]）。
 *
 * 为什么值得单独测：接收侧对正文做的是**字节上限校验**，超限 = 整帧丢弃。
 * 截断本身写错的后果不是「短一点」，而是两种静默丢失 ——
 * 按码元数截（中文 3 字节/字）会得到**仍然超限**的字符串；在代理对中间切会留下半个字符。
 *
 * `reasoning` 的同档截断已随该键一起退场（CoT 不进群，白名单终态 14 键）。
 */
class GroupTextClampTest {

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

    /** 正文截断不许在代理对中间切。 */
    @Test
    fun textCuttingNeverLeavesAHalfSurrogatePair() {
        val clamped = clampGroupText("😀".repeat(100_000))
        assertTrue(Codec.utf8ByteLength(clamped) <= Limits.GROUP_TEXT_TCP_LIMIT)
        assertNoLoneSurrogate(clamped.removeSuffix("…"))
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
