// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「一次播报要发给谁」的护栏。
 *
 * 这个文件的存在理由是一个真机缺陷：广播与单播被写成二选一
 * （`if (manualPeers.isEmpty()) 广播 else 只发手动节点`）。后果有两条，都会让消息
 * 静默进排队而不是发出去：
 *
 * 1. 用户在「发现」页手动加过任何一个节点之后，本机**再也不广播**，同网段其他节点
 *    再也发现不了它；
 * 2. **被动握过手**的节点（对方主动 entry 进来、而我没配过它）永远收不到 presence，
 *    在 `offlineAfter`（90 秒）后被我方判成离线。
 *
 * 2026-10-03 在 Pixel 6 + Mac 的真实链路上复现：手机会话头写着「离线」，回复气泡写着
 * 「已排队」；把手动节点补上之后排队消息立刻补发。
 *
 * 断言的是**收件人集合**而不是网络行为，所以不需要开 socket，两端平台都能跑。
 */
class DiscoveryTargetsTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = "n",
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "h",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = emptyList(),
    )

    private fun record(nodeId: String, ip: String, port: Int, online: Boolean) =
        PeerRecord(profile(nodeId), ip, port, lastSeen = 0L, online = online)

    private fun targets(
        manual: List<ManualPeer> = emptyList(),
        peers: List<PeerRecord> = emptyList(),
        candidates: List<Pair<String, Int>> = emptyList(),
    ) = broadcastTargets(
        broadcastHost = "255.255.255.255",
        broadcastPort = 17878,
        manualPeers = manual,
        knownPeers = peers,
        candidateEndpoints = candidates,
    )

    @Test
    fun emptyEverythingStillBroadcasts() {
        assertEquals(listOf("255.255.255.255" to 17878), targets())
    }

    @Test
    fun broadcastSurvivesConfiguredManualPeers() {
        val result = targets(manual = listOf(ManualPeer("192.168.2.82", 17891)))
        assertTrue(
            ("255.255.255.255" to 17878) in result,
            "配置了手动节点之后仍然必须广播，否则别人再也发现不了本机：$result",
        )
        assertTrue(("192.168.2.82" to 17891) in result, "手动节点也必须收到：$result")
    }

    @Test
    fun passivelyLearnedPeerStillGetsPresence() {
        // 修复点：没配过、只是被对方握过手的节点，必须在收件人里。
        val result = targets(
            manual = listOf(ManualPeer("192.168.2.82", 17891)),
            peers = listOf(record("peer-1", "192.168.2.99", 17878, online = true)),
        )
        assertTrue(
            ("192.168.2.99" to 17878) in result,
            "被动学到的在线节点必须收到 presence，否则 90 秒后会被误判离线：$result",
        )
    }

    @Test
    fun candidateEndpointsAreAlwaysIncluded() {
        // task-20：手机只留移动网时，桌面在 registry 里的地址可能是上一次被采纳的局域网
        // 地址（不可达）；而配对码里存的公网 IPv6 明明通。心跳必须也发候选，否则手机
        // 永远送不出 presence、桌面也就永远学不到手机的运营商地址 —— 两边互相显示离线。
        val result = targets(candidates = listOf("2001:db8::1" to 17878))
        assertTrue(("2001:db8::1" to 17878) in result, "候选端点必须进收件人：$result")
    }

    @Test
    fun offlinePeersAreSkipped() {
        val result = targets(peers = listOf(record("dead", "10.0.0.9", 17878, online = false)))
        assertEquals(listOf("255.255.255.255" to 17878), result)
    }

    @Test
    fun manualPeerAndRegistryPeerBothGetIt() {
        val result = targets(
            manual = listOf(ManualPeer("192.168.2.82", 17891)),
            peers = listOf(record("peer-1", "192.168.2.99", 17878, online = true)),
        )
        assertEquals(
            listOf(
                "255.255.255.255" to 17878,
                "192.168.2.82" to 17891,
                "192.168.2.99" to 17878,
            ),
            result,
        )
    }
}
