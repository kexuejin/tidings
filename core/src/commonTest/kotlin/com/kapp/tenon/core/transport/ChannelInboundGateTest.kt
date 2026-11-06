// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `fch1` 协商门控的**接线**判据（分级声明版，D-018 方案 b；不是协商逻辑本身，
 * 那在 FileChannelNegotiationTest）。
 *
 * 这一层要钉的是"什么时候**不接管**"：接管了却不收字节 = 文件永远卡住；不接管却收了
 * = 用户找不到文件。分级后规则是：**对端声明 fch1 且本端数据面事实可用**才接管，
 * 其余一律 legacy —— 本端事实由调用方注入（luciola 数据面是否真的起来），
 * 对端 caps 未知（registry 里还没有它的资料）⇒ 不能猜，必须回退。
 */
class ChannelInboundGateTest {

    private fun caps(vararg c: String): List<String> = c.toList()

    @Test
    fun takesOverWhenDataPlaneUpAndPeerDeclares() {
        assertTrue(
            FileChannelNegotiation.canUseChannel(
                selfChannelAvailable = true,
                peerCaps = caps(MsgTypes.CAP_FILE_CHANNEL),
            ),
            "对端声明 + 本端数据面事实 ⇒ 接管",
        )
    }

    @Test
    fun unknownPeerCapsFallBackToLegacy() {
        // registry 里还没有这台对端（或它没发过 profile）⇒ 不能猜它支持，必须回退。
        assertFalse(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = true, peerCaps = null),
            "对端 caps 未知 ⇒ legacy（不猜）",
        )
    }

    @Test
    fun dataPlaneDownFallsBackEvenIfPeerDeclares() {
        assertFalse(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = false, peerCaps = caps(MsgTypes.CAP_FILE_CHANNEL)),
            "本端数据面没起 ⇒ legacy（声明与现实不符 = 互等死锁的形状）",
        )
    }
}
