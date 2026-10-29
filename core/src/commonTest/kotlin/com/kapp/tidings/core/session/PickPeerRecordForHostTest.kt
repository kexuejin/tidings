// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * "这串 IP 现在是谁"的选取口径（[pickPeerRecordForHost]）。
 *
 * 真机现场（验证轮抓到）：控制面按 IP 投文件，registry 里同一 IP 挂着重装前的旧
 * nodeId（离线）与当前 nodeId，旧实现按"在线排序、并列落插入序"选中了**陈旧**的那条
 * —— offer 发进虚空，而在线的那条永远收不到。
 *
 * 钉住三条：
 * 1. 在线的那条必中（哪怕它是后插入的）；
 * 2. 全离线时按 lastSeen 新到旧（不是插入序）；
 * 3. 指定了端口就只认端口匹配的记录；onlineOnly=true 时离线记录不返回。
 */
class PickPeerRecordForHostTest {

    private fun record(nodeId: String, online: Boolean, lastSeen: Long, udpPort: Int = 17878) =
        com.kapp.tidings.core.transport.PeerRecord(
            profile = Profile(
                nodeId = nodeId, nick = nodeId, company = "", dept = "", team = "",
                avatar = 1, profileRev = 1, host = "h", platform = "linux",
                tcpPort = 17879, ver = "test", caps = emptyList(),
            ),
            ip = "192.168.2.22",
            udpPort = udpPort,
            online = online,
            lastSeen = lastSeen,
        )

    @Test
    fun onlineRecordWinsRegardlessOfInsertionOrder() {
        val staleFirst = listOf(
            record("stale-node", online = false, lastSeen = 1_000L),
            record("fresh-node", online = true, lastSeen = 2_000L),
        )
        assertEquals(
            "fresh-node",
            pickPeerRecordForHost(staleFirst, "192.168.2.22", null, onlineOnly = false)?.profile?.nodeId,
            "在线记录必中 —— 旧实现按插入序选中 stale-node 那条，offer 发进虚空",
        )
    }

    @Test
    fun allOfflineResolvesByFreshestLastSeen() {
        val records = listOf(
            record("old-node", online = false, lastSeen = 1_000L),
            record("newer-node", online = false, lastSeen = 9_000L),
        )
        assertEquals(
            "newer-node",
            pickPeerRecordForHost(records, "192.168.2.22", null, onlineOnly = false)?.profile?.nodeId,
            "全离线时回退到最近听到的那条（排队语义由发送侧承接）",
        )
    }

    @Test
    fun onlineOnlyReturnsNullWhenAllOffline() {
        val records = listOf(record("old-node", online = false, lastSeen = 1_000L))
        assertNull(
            pickPeerRecordForHost(records, "192.168.2.22", null, onlineOnly = true),
            "onlineOnly 档不给离线记录 —— 调用方先去握手要当前身份",
        )
    }

    @Test
    fun portMismatchIsSkipped() {
        val records = listOf(
            record("ephemeral-node", online = true, lastSeen = 3_000L, udpPort = 62623),
            record("default-node", online = true, lastSeen = 2_000L, udpPort = 17878),
        )
        assertEquals(
            "default-node",
            pickPeerRecordForHost(records, "192.168.2.22", 17878, onlineOnly = false)?.profile?.nodeId,
            "给了端口就只认端口匹配的记录",
        )
    }
}
