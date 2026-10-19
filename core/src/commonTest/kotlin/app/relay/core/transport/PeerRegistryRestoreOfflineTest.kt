// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PeerRegistry.restoreOffline] 的三条不变量（task-80 ①）。
 *
 * 为什么单独测它、而不是走 `touch`：`touch` 的语义是"**现在**收到了一帧"，
 * 它会 `lastSeen = now` + `online = true`。用它在启动时恢复历史记录，等于对整个网络
 * 宣布"这些设备都在线"—— 而蜂窝下它们可能根本不可达，消息会进一条通不了的队列。
 */
class PeerRegistryRestoreOfflineTest {

    private fun profile(nodeId: String, nick: String) = Profile(
        nodeId = nodeId,
        nick = nick,
        company = "",
        dept = "",
        team = "",
        avatar = -1,
        profileRev = 0,
        host = "MacBook-Pro",
        platform = "mac",
        tcpPort = 17879,
        ver = "0.61.1",
        caps = listOf("lnk1"),
    )

    @Test
    fun restoredPeersAreOfflineAndKeepTheirOldLastSeen() {
        val registry = PeerRegistry("self")
        val ancient = 1_700_000_000_000L
        val record = registry.restoreOffline(profile("node-a", "demo-mac-97"), "1.2.3.4", 17878, ancient)

        assertNotNull(record)
        assertEquals("demo-mac-97", record.profile.nick, "名字必须还在（这正是本缺陷要修的）")
        assertFalse(record.online, "恢复出来的对端必须**离线** —— 蜂窝下它确实不可达")
        assertEquals(ancient, record.lastSeen, "不许把 lastSeen 刷新成现在（那是在撒谎说\"刚见过\"）")
    }

    @Test
    fun aLiveRecordIsNeverOverwrittenByHistory() {
        val registry = PeerRegistry("self")
        // 模拟启动顺序：discovery 先跑起来、真的收到了这台设备的帧。
        registry.touch("node-b", "9.9.9.9", 17878, profile("node-b", "真实名字"), confirmed = true)
        val live = registry.get("node-b")!!
        assertTrue(live.online)

        // 盘上的旧记录随后才被恢复 —— 它绝不能把实时记录盖掉。
        val restored = registry.restoreOffline(profile("node-b", "旧名字"), "1.1.1.1", 17878, 1L)
        assertNull(restored, "已经有实时记录时不该恢复历史")
        val after = registry.get("node-b")!!
        assertEquals("真实名字", after.profile.nick, "实时资料比盘上的新，不能被历史覆盖")
        assertTrue(after.online)
    }

    @Test
    fun selfIsNeverRestoredAsAPeer() {
        // 盘上万一有自己那一条（比如旧版本写进去的），也不能把自己当成对端。
        assertNull(PeerRegistry("self").restoreOffline(profile("self", "我"), "1.2.3.4", 17878, 1L))
    }
}