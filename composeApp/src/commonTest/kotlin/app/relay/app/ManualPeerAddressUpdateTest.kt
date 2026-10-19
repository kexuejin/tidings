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

    // ---- 合成名便条：`node-<8 位>` 不该一直留着（真机红米 manualPeers[0].name 就是它）----

    @Test
    fun aSyntheticLabelIsUpgradedWhenTheAddressChanges() {
        val entries = listOf(ManualPeerEntry("2001:db8::1", 17878, "node-a1b2c3d4"))
        val updated = manualPeersWithUpdatedAddress(
            entries = entries,
            oldHost = "2001:db8::1",
            oldPort = 17878,
            newHost = "2001:db8:2020:cb44::9",
            newPort = 17878,
            newName = "demo-mac-97",
        )
        assertEquals("demo-mac-97", updated?.get(0)?.name, "对端报了真名，合成名就该被顶掉")
        assertEquals("2001:db8:2020:cb44::9", updated?.get(0)?.host)
    }

    @Test
    fun aSyntheticLabelIsUpgradedEvenWhenTheAddressIsUnchanged() {
        val entries = listOf(ManualPeerEntry("2001:db8::1", 17878, "node-a1b2c3d4"))
        val updated = manualPeersWithUpdatedAddress(
            entries, "2001:db8::1", 17878, "2001:db8::1", 17878, newName = "demo-mac-97",
        )
        assertEquals("demo-mac-97", updated?.get(0)?.name, "地址没变也要能改名，否则合成名永远留在便条上")
    }

    @Test
    fun aUserChosenLabelIsNeverOverwritten() {
        val entries = listOf(ManualPeerEntry("2001:db8::1", 17878, "老周的旧笔记本"))
        val updated = manualPeersWithUpdatedAddress(
            entries, "2001:db8::1", 17878, "2001:db8::1", 17878, newName = "demo-mac-97",
        )
        assertSame(entries, updated, "用户自己起的便条不许被对端的昵称覆盖（那是他的东西）")
    }

    @Test
    fun aMissingLabelIsFilledIn() {
        val entries = listOf(ManualPeerEntry("2001:db8::1", 17878))
        val updated = manualPeersWithUpdatedAddress(
            entries, "2001:db8::1", 17878, "2001:db8::1", 17878, newName = "demo-mac-97",
        )
        assertEquals("demo-mac-97", updated?.get(0)?.name)
    }

    @Test
    fun theReplacementRuleOnlyTouchesEmptyOrSyntheticLabels() {
        assertEquals(true, shouldReplacePeerLabel(null, "demo-mac-97"))
        assertEquals(true, shouldReplacePeerLabel("  ", "demo-mac-97"))
        assertEquals(true, shouldReplacePeerLabel("node-a1b2c3d4", "demo-mac-97"))
        assertEquals(false, shouldReplacePeerLabel("书房的电脑", "demo-mac-97"))
        // 对端自己也只有合成名时不动：换了等于没换，还会把用户的便条洗掉。
        assertEquals(false, shouldReplacePeerLabel("node-a1b2c3d4", "node-abcdef12"))
        assertEquals(false, shouldReplacePeerLabel("书房的电脑", ""))
    }
}
