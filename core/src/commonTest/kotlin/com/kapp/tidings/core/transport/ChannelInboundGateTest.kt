// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `fch1` 协商门控的**接线**判据（不是协商逻辑本身，那在 FileChannelNegotiationTest）。
 *
 * 这一层要钉的是"什么时候**不接管**"：接管了却不收字节 = 文件永远卡住；不接管却收了
 * = 用户找不到文件。所以规则必须是：**双方都声明 fch1 才接管**，其余一律 legacy。
 */
class ChannelInboundGateTest {

    private fun caps(vararg c: String): List<String> = c.toList()

    private fun profile(caps: List<String>) = Profile(
        nodeId = "p", nick = "p", company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "linux", tcpPort = 17879, ver = "1", caps = caps,
    )

    @Test
    fun takesOverOnlyWhenBothDeclareFch1() {
        val self = profile(caps(MsgTypes.CAP_FILE_CHANNEL))
        assertTrue(FileChannelNegotiation.canUseChannel(self, profile(caps(MsgTypes.CAP_FILE_CHANNEL))))
        assertFalse(FileChannelNegotiation.canUseChannel(self, profile(caps())))
        assertFalse(FileChannelNegotiation.canUseChannel(profile(caps()), self))
    }

    @Test
    fun unknownPeerCapsFallBackToLegacy() {
        // registry 里还没有这台对端（或它没发过 profile）⇒ 不能猜它支持，必须回退。
        assertFalse(FileChannelNegotiation.canUseChannel(profile(caps(MsgTypes.CAP_FILE_CHANNEL)), null))
    }
}
