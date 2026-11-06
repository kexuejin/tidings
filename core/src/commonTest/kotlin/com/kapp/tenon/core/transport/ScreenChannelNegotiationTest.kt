// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `scr1` 的协商判据（分级声明版，D-018 方案 b）：**本端画面信道事实 × 对端声明 +
 * 对端给出端口** 才算数。
 *
 * 这三种"缺一条"的组合就是灰度期间的全部可能，每一种都必须落回 legacy ——
 * 半升级最坏的结果是"双方都在等对方"，而投屏是用户点了「看屏幕」就在等画面的交互。
 * 分级后 self 侧从 caps 声明改为**本地事实**（画面信道真的起来了 —— scr1 停止广播，
 * 广播面被 Limits.CAPS=16 卡死，见 TieredChannelNegotiationMatrixTest 的 KDoc）。
 */
class ScreenChannelNegotiationTest {

    private fun profile(id: String, caps: List<String>, screenPort: Int? = null) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "linux", tcpPort = 17879,
        ver = "1", caps = caps, screenPort = screenPort,
    )

    private val both = listOf(MsgTypes.CAP_REMOTE_VIEW, MsgTypes.CAP_SCREEN_CHANNEL)

    @Test
    fun channelUpWithDeclaringPeerAndPortTakesOver() {
        val peer = profile("peer", both, screenPort = 17900)
        assertTrue(ScreenChannelNegotiation.canUseChannel(selfChannelAvailable = true, peer = peer))
    }

    @Test
    fun nullPeerIsLegacy() {
        assertFalse(ScreenChannelNegotiation.canUseChannel(selfChannelAvailable = true, peer = null))
    }

    @Test
    fun selfChannelDownFallsBack() {
        val peer = profile("peer", both, screenPort = 17900)
        assertFalse(
            ScreenChannelNegotiation.canUseChannel(selfChannelAvailable = false, peer = peer),
            "本端画面信道没起 ⇒ legacy（声明与现实不符 = 互等形状）",
        )
    }

    @Test
    fun peerWithoutCapFallsBack() {
        val peer = profile("peer", listOf(MsgTypes.CAP_REMOTE_VIEW), 17900)
        assertFalse(ScreenChannelNegotiation.canUseChannel(selfChannelAvailable = true, peer = peer))
    }

    @Test
    fun peerWithoutPortFallsBack() {
        // 声明了能力却没给端口：不许"猜一个端口"去拨（老端可能把 17882 当画面口）。
        assertFalse(
            ScreenChannelNegotiation.canUseChannel(
                selfChannelAvailable = true,
                peer = profile("peer", both),
            ),
        )
    }

    @Test
    fun outOfRangePortFallsBack() {
        assertFalse(
            ScreenChannelNegotiation.canUseChannel(
                selfChannelAvailable = true,
                peer = profile("peer", both, 0),
            ),
        )
        assertFalse(
            ScreenChannelNegotiation.canUseChannel(
                selfChannelAvailable = true,
                peer = profile("peer", both, 70000),
            ),
        )
    }
}
