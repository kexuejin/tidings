// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.transport.PeerRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * hook 审批的收件人选取（[pickHookPeer]）。
 *
 * 真机现场（Tier A 幕 3）：手机重装过一次，节点表里留下两条记录 —— 旧的 `44608041…`
 * （profileRev=0）与当前的 `e4bd7b99…`（profileRev=4），两条都声明 `agq1`、**都算在线**
 * （另一条 `lastSeen` 被同一台手机的广播刷新）、且指向同一个 `192.168.2.22:17878`。
 * 旧实现按 nodeId 字典序取最小 —— 恰好选中旧那条，于是：
 *
 * - 审批按 `to=44608041…` 落账，帧却发到同一端点，手机照常弹卡、用户照常点批准；
 * - 回执由手机当前的 nodeId 发出，`applyAckToApproval` 按 `key.nodeId == envelope.from`
 *   匹配 ⇒ 永远找不到那条审批 ⇒ 长轮询等满超时 ⇒ `deny`（fail-closed 把一次真实的
 *   批准记成了拒绝）。
 *
 * 钉住四条：
 * 1. 同一端点折叠到 `profileRev` 更大的那条（**不是** nodeId 更小的）；
 * 2. 折叠后仍按 nodeId 取最小 —— 两台不同设备时的确定性不许丢；
 * 3. 离线 / 未声明 `agq1` 的对端不参选；
 * 4. 一个都不剩就返回 null（调用方据此回 503，而不是瞎指一台）。
 */
class HookPeerPickTest {

    private fun record(
        nodeId: String,
        profileRev: Int = 1,
        online: Boolean = true,
        lastSeen: Long = 1_000L,
        ip: String = "192.168.2.22",
        udpPort: Int = 17878,
        caps: List<String> = listOf(MsgTypes.CAP_AGENT_QUEUE),
    ) = PeerRecord(
        profile = Profile(
            nodeId = nodeId, nick = nodeId, company = "", dept = "", team = "",
            avatar = 1, profileRev = profileRev, host = "h", platform = "linux",
            tcpPort = 17879, ver = "test", caps = caps,
        ),
        ip = ip,
        udpPort = udpPort,
        online = online,
        lastSeen = lastSeen,
    )

    @Test
    fun sameEndpointFoldsOntoTheFreshestProfileNotTheSmallestNodeId() {
        val peers = listOf(
            // 旧记录：rev 停在 0，lastSeen 反而更新（同一台手机的广播会刷新它）
            record("44608041-9382-40a6-831d-1b199a044946", profileRev = 0, lastSeen = 9_000L),
            record("e4bd7b99-6eb9-4278-a679-edde214909e9", profileRev = 4, lastSeen = 8_000L),
        )
        assertEquals(
            "e4bd7b99-6eb9-4278-a679-edde214909e9",
            pickHookPeer(peers),
            "同一端点只能有一台收件人：审批落账用的 nodeId 必须等于手机回执时的 from",
        )
    }

    @Test
    fun differentEndpointsStillPickDeterministicallyByNodeId() {
        val peers = listOf(
            record("phone-b", ip = "192.168.2.30", lastSeen = 9_000L),
            record("phone-a", ip = "192.168.2.22", lastSeen = 1_000L),
        )
        assertEquals(
            "phone-a",
            pickHookPeer(peers),
            "两台真设备并存时保持既定口径（nodeId 最小，确定性）—— 折叠只发生在同端点内",
        )
    }

    @Test
    fun offlineOrCapabilityLessPeersAreNotCandidates() {
        val peers = listOf(
            record("ghost", profileRev = 9, online = false),
            record("no-agq1", profileRev = 9, caps = listOf(MsgTypes.CAP_AGENT)),
            record("real", profileRev = 1),
        )
        assertEquals("real", pickHookPeer(peers), "离线、或没声明 agq1 的对端收不了审批帧")
    }

    @Test
    fun noCandidateMeansNullSoTheCallerCanFailClosed() {
        assertNull(pickHookPeer(emptyList()))
        assertNull(pickHookPeer(listOf(record("phone", online = false))))
    }
}
