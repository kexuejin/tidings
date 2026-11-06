// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.session

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 单聊正文在**核心层**的截断（[clampDirectText]）。
 *
 * ## 为什么这条判据以前不存在
 *
 * 4096 这个上限一直都在，但执行点只在 app 的输入框（`MessageComposer` 的档位判定、
 * 桌面 `DesktopLayout` 的发送门）。核心层的 `sendText` / `replayOutgoing` 一次都没卡过
 * —— 于是任何不走输入框的入口（重发重建、脚本、以后的转发）都能产出一帧注定被对端
 * 判 `bad-payload` 的 `msg`，而待发队列会一直重放它。这是 ARP-18⑤。
 *
 * 所以这里的判据不是「截断函数算得对不对」（[clampUtf8] 已被 [GroupTextClampTest] 钉住），
 * 而是两件只有单聊才有的事：
 * 1. 截断用的是**单聊的上限**（4096），不是群文本那档 32 KiB —— 写错成后者就等于没截；
 * 2. 截断后的正文**过得了接收侧对 `msg` 的校验**（这才是「不产出注定被拒的帧」）。
 */
class DirectTextClampTest {

    /**
     * 正好压在上限上的正文**一个字符都不动**。
     *
     * 边界取「含」：接收侧判的是 `> limit`，不是 `>=`。多截一个字节就是白改用户内容。
     */
    @Test
    fun textExactlyAtTheLimitIsReturnedVerbatim() {
        val head = "a"
        val text = head + "汉".repeat((Protocol.TEXT_TCP_LIMIT - Codec.utf8ByteLength(head)) / 3)
        assertTrue(
            Codec.utf8ByteLength(text) <= Protocol.TEXT_TCP_LIMIT,
            "样本必须落在上限内（实际 " + Codec.utf8ByteLength(text) + " 字节）",
        )
        assertEquals(text, clampDirectText(text))
    }

    /**
     * 超一位就截 —— 而且截到的是**单聊的上限**。
     *
     * 反证：把 `clampDirectText` 里的上限换成 `Limits.GROUP_TEXT_TCP_LIMIT`（32 KiB，
     * 看起来"更宽松"）这条就会红：一段 4 KiB+ 的正文会原样返回，接收侧照旧整帧丢弃。
     */
    @Test
    fun overlongTextIsCutToTheSingleChatLimitNotTheGroupOne() {
        val clamped = clampDirectText("汉".repeat(Protocol.TEXT_TCP_LIMIT))
        val bytes = Codec.utf8ByteLength(clamped)
        assertTrue(bytes <= Protocol.TEXT_TCP_LIMIT, "截断后仍超单聊上限：" + bytes + " 字节")
        assertTrue(clamped.endsWith("…"), "截断处必须留下省略号")
    }

    /** 截断后的正文必须过接收侧的 `msg` 校验 —— 否则等于「截了个寂寞」。 */
    @Test
    fun theClampedTextStillValidatesAsAnInboundSingleChatMessage() {
        val clamped = clampDirectText("汉".repeat(Protocol.TEXT_TCP_LIMIT))
        assertTrue(
            Codec.validatePayload(MsgTypes.MSG, Codec.textPayload(clamped), Protocol.TEXT_TCP_LIMIT),
            "截断后的单聊正文过不了入站校验",
        )
    }

    /**
     * 截断前的原文**过不了**校验（样本自检）。
     *
     * 没有这一条，上面那条断言在「样本其实没超限」时会照样绿 —— 那正是本仓最常见的假绿。
     */
    @Test
    fun theUnclampedSampleWouldActuallyBeRejected() {
        val raw = "汉".repeat(Protocol.TEXT_TCP_LIMIT)
        assertFalse(
            Codec.validatePayload(MsgTypes.MSG, Codec.textPayload(raw), Protocol.TEXT_TCP_LIMIT),
            "样本本身必须真的超限，否则截断判据是空转",
        )
    }
}
