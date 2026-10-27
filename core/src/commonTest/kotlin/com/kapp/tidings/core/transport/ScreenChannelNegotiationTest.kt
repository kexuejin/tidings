// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `scr1` 的协商判据：**双方都声明 + 对端给出端口**才算数。
 *
 * 这三种"缺一条"的组合就是灰度期间的全部可能，每一种都必须落回 legacy ——
 * 半升级最坏的结果是"双方都在等对方"，而投屏是用户点了「看屏幕」就在等画面的交互。
 */
class ScreenChannelNegotiationTest {

    private fun profile(id: String, caps: List<String>, screenPort: Int? = null) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "linux", tcpPort = 17879,
        ver = "1", caps = caps, screenPort = screenPort,
    )

    private val both = listOf(MsgTypes.CAP_REMOTE_VIEW, MsgTypes.CAP_SCREEN_CHANNEL)

    @Test
    fun onlyWhenBothSidesDeclareAndPeerAdvertisesPort() {
        val self = profile("self", both)
        val peer = profile("peer", both, screenPort = 17900)
        assertTrue(ScreenChannelNegotiation.canUseChannel(self, peer))
    }

    @Test
    fun nullPeerIsLegacy() {
        assertFalse(ScreenChannelNegotiation.canUseChannel(profile("self", both), null))
    }

    @Test
    fun selfWithoutCapFallsBack() {
        val self = profile("self", listOf(MsgTypes.CAP_REMOTE_VIEW))
        assertFalse(ScreenChannelNegotiation.canUseChannel(self, profile("peer", both, 17900)))
    }

    @Test
    fun peerWithoutCapFallsBack() {
        val peer = profile("peer", listOf(MsgTypes.CAP_REMOTE_VIEW), 17900)
        assertFalse(ScreenChannelNegotiation.canUseChannel(profile("self", both), peer))
    }

    @Test
    fun peerWithoutPortFallsBack() {
        // 声明了能力却没给端口：不许"猜一个端口"去拨（老端可能把 17882 当画面口）。
        assertFalse(ScreenChannelNegotiation.canUseChannel(profile("self", both), profile("peer", both)))
    }

    @Test
    fun outOfRangePortFallsBack() {
        assertFalse(ScreenChannelNegotiation.canUseChannel(profile("self", both), profile("peer", both, 0)))
        assertFalse(ScreenChannelNegotiation.canUseChannel(profile("self", both), profile("peer", both, 70000)))
    }

    @Test
    fun capsOverloadAgreesWithProfileOverload() {
        val self = profile("self", both)
        val peer = profile("peer", both, 17900)
        assertTrue(
            ScreenChannelNegotiation.canUseChannel(self.caps, peer.caps, peer.screenPort) ==
                ScreenChannelNegotiation.canUseChannel(self, peer),
        )
        assertFalse(ScreenChannelNegotiation.canUseChannel(self.caps, null, 17900))
    }
}
