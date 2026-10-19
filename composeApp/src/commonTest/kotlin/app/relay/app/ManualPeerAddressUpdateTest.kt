// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 重新配对同一个 nodeId 时，通讯录那一行要**原地改地址**（task-20 ①）。
 *
 * 为什么不是新增一行：nodeId 才是身份，[ManualPeerEntry] 只是「这个身份上次是从哪个地址
 * 找到的」。手机从局域网切到移动网、地址从私网 IPv6 变成公网 IPv6 时，如果新增一行，
 * 节点列表里就会同时出现两台"机器"，而群成员表/会话/消息只认 nodeId 一份。
 */
class ManualPeerAddressUpdateTest {

    @Test
    fun theEntryWithTheOldAddressIsRewrittenInPlace() {
        val entries = listOf(
            ManualPeerEntry("192.168.2.82", 17878, "书房的电脑"),
            ManualPeerEntry("2001:db8::1", 17878),
        )

        val updated = manualPeersWithUpdatedAddress(
            entries = entries,
            oldHost = "192.168.2.82",
            oldPort = 17878,
            newHost = "2001:db8:2020:8494::9",
            newPort = 17878,
        )

        assertEquals(2, updated?.size, "只改那一行，不新增也不删")
        assertEquals("2001:db8:2020:8494::9", updated?.get(0)?.host)
        assertEquals("书房的电脑", updated?.get(0)?.name, "本机起的名字要跟着这一行走")
        assertEquals("2001:db8::1", updated?.get(1)?.host, "另一行不受影响")
    }

    @Test
    fun anUnchangedAddressReturnsTheSameList() {
        val entries = listOf(ManualPeerEntry("192.168.2.82", 17878))
        val updated = manualPeersWithUpdatedAddress(entries, "192.168.2.82", 17878, "192.168.2.82", 17878)
        assertSame(entries, updated, "地址没变就不该触发一次落盘")
    }

    @Test
    fun noMatchingEntryMeansTheCallerShouldAddOne() {
        val entries = listOf(ManualPeerEntry("10.0.0.9", 17878))
        assertNull(manualPeersWithUpdatedAddress(entries, "192.168.2.82", 17878, "2001:db8::1", 17878))
    }
}
