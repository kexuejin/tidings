// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 中继节点的「在线」必须等于健康检查的结果。
 *
 * 真机验证（2026-10-18）记的「假在线」：refreshPeers 只要配置了中继就无条件塞一条
 * online = true 的 PeerUi，而唯一的判活函数 checkHealth() 全仓无人调用 —— 中继挂了
 * 界面照样显示在线。这条测试把判据钉死在「配置」与「判活」两件事分开上。
 */
class RemoteRelayPeerTest {

    private fun config(enabled: Boolean = true, nodeId: String? = "relay-1") = RemoteRelayConfig(
        url = "http://10.0.0.9:8788",
        token = "t",
        nodeId = nodeId,
        nick = "relay-nick",
        enabled = enabled,
    )

    @Test
    fun notConfiguredMeansNoInjectedPeer() {
        assertNull(remoteRelayPeer(null, healthy = true))
    }

    @Test
    fun disabledMeansNoInjectedPeer() {
        assertNull(remoteRelayPeer(config(enabled = false), healthy = true))
    }

    @Test
    fun missingNodeIdMeansNoInjectedPeer() {
        assertNull(remoteRelayPeer(config(nodeId = null), healthy = true))
    }

    @Test
    fun onlineFollowsTheHealthCheckNotTheConfig() {
        assertFalse(remoteRelayPeer(config(), healthy = false)!!.online)
        assertTrue(remoteRelayPeer(config(), healthy = true)!!.online)
    }

    @Test
    fun peerCarriesTheConfiguredIdentity() {
        val peer = remoteRelayPeer(config(), healthy = true)!!
        assertEquals("relay-1", peer.nodeId)
        assertEquals("relay-nick", peer.nick)
        assertEquals("http://10.0.0.9:8788", peer.host)
    }
}
