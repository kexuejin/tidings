// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.core.protocol.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面输入卡片「发送门」的单测。
 *
 * 真身：发送按钮一直有「超限置灰」的门，但 Enter 直发那条路从前不查 —— 超限的草稿
 * 按 Enter，输入框被清空、字没了，消息实际**没有发出去**（对端按字节预算整帧拒收，
 * 发送侧收不到任何错误）。修法是把按钮与 Enter 收拢到 [desktopComposerCanSend] 这一条
 * 判据上（「与按钮判据同源」），Enter 在 send() 开头先过同一扇门。
 *
 * 反向验证：把 [desktopComposerCanSend] 里的 `!mode.overLimit` 删掉（退回旧的
 * 「只看是否空白」），[overLimitDraftIsRefused] 立刻变红。
 */
class DesktopComposerSendGateTest {

    private fun modeOf(text: String): ComposeByteMode = composeByteMode(draftByteCount(text))

    @Test
    fun normalShortDraftSends() {
        val text = "一句话"
        assertTrue(desktopComposerCanSend(text, modeOf(text)), "未超限的正常草稿放行")
    }

    @Test
    fun blankDraftIsRefused() {
        assertFalse(desktopComposerCanSend("   ", ComposeByteMode.Udp), "纯空白草稿不放行")
        assertFalse(desktopComposerCanSend("", ComposeByteMode.Udp))
    }

    @Test
    fun overLimitDraftIsRefused() {
        // 超过 TCP 上限的草稿：发出去就是一帧注定被拒的字节。
        val over = "字".repeat(Protocol.TEXT_TCP_LIMIT) // 每个汉字 3 字节，必然超限
        assertEquals(ComposeByteMode.Over, modeOf(over))
        assertFalse(
            desktopComposerCanSend(over, modeOf(over)),
            "超限草稿必须被同一扇门拦下 —— Enter 与按钮一致",
        )
    }

    @Test
    fun tcpRoutedDraftStillSends() {
        // 「换条路」不是「发不出去」：800–4096 字节之间走 TCP，仍然放行。
        val tcp = "字".repeat(Protocol.TEXT_UDP_LIMIT + 1)
        assertEquals(ComposeByteMode.Tcp, modeOf(tcp))
        assertTrue(desktopComposerCanSend(tcp, modeOf(tcp)), "TCP 档照样发")
    }

    @Test
    fun blankDraftWithAttachmentsSends() {
        assertTrue(
            desktopComposerCanSend("", ComposeByteMode.Udp, hasAttachments = true),
            "正文为空但有暂存附件时放行",
        )
        assertTrue(
            desktopComposerCanSend("   ", ComposeByteMode.Udp, hasAttachments = true),
            "纯空白正文但有暂存附件时放行",
        )
    }

    @Test
    fun overLimitDraftWithAttachmentsIsStillRefused() {
        val over = "字".repeat(Protocol.TEXT_TCP_LIMIT)
        assertFalse(
            desktopComposerCanSend(over, modeOf(over), hasAttachments = true),
            "正文超限即使带附件也必须拦下",
        )
    }
}
