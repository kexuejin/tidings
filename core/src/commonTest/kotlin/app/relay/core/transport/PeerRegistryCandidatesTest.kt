// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 候选端点（配对码那一池地址）在 registry 里的记账。
 *
 * 真机现场（task-20）：registry 里每个 peer 只存一个"当前地址"，手机在「局域网 ↔ 移动网」
 * 之间切换时那个地址就不可达了；而对端的另一个地址明明通 —— 只是没人再去试。
 * 这一格就是"还有哪些可以试"，[PeerRegistry.setActiveEndpoint] 则是"试通了就改成它"。
 */
class PeerRegistryCandidatesTest {

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

    @Test
    fun candidatesAreStoredPerNodeAndFlattened() {
        val registry = PeerRegistry("self")
        registry.setCandidates("peer-1", listOf(PeerEndpoint("2001:db8::1", 17878), PeerEndpoint("192.168.2.9", 17878)))
        registry.setCandidates("peer-2", listOf(PeerEndpoint("10.0.0.9", 17878)))

        assertEquals(listOf("2001:db8::1", "192.168.2.9"), registry.candidatesOf("peer-1").map { it.host })
        assertEquals(2, registry.allCandidates().size)
        assertEquals(emptyList(), registry.candidatesOf("nobody"))
    }

    @Test
    fun anEmptyListForgetsTheNode() {
        val registry = PeerRegistry("self")
        registry.setCandidates("peer-1", listOf(PeerEndpoint("10.0.0.9", 17878)))
        registry.setCandidates("peer-1", emptyList())
        assertTrue(registry.allCandidates().isEmpty(), "空表 = 不再有兜底地址，不该留一条空记录")
    }

    @Test
    fun aSuccessfulSendPromotesTheEndpointToTheActiveAddress() {
        val registry = PeerRegistry("self")
        registry.touch("peer-1", "192.168.2.9", 17878, profile("peer-1"), profileTs = 1L, confirmed = true)
        registry.markOffline("peer-1")
        assertFalse(registry.get("peer-1")!!.online)

        // 一次成功的发送 = 最硬的事实：这个端点可达，直接改成当前地址（且回到在线）。
        registry.setActiveEndpoint("peer-1", "2001:db8::1", 17878)

        val record = registry.get("peer-1")!!
        assertEquals("2001:db8::1", record.ip)
        assertTrue(record.online)
    }

    @Test
    fun promotingAnUnknownNodeIsANoOp() {
        val registry = PeerRegistry("self")
        registry.setActiveEndpoint("nobody", "10.0.0.9", 17878)
        assertEquals(null, registry.get("nobody"))
    }
}
