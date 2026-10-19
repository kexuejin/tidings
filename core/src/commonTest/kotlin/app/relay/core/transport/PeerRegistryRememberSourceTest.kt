// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「见过它的源地址就记进候选池」（task-28 回程自愈的兜底）。
 *
 * 三条不变量：配对码给的候选排在前面且不被挤掉；同一地址不重复记；
 * **当前地址与 online 不被改动**（漂移规则不放宽）。
 */
class PeerRegistryRememberSourceTest {

    private fun endpoint(host: String, port: Int) = PeerEndpoint(host, port)

    @Test
    fun aSourceAddressIsRememberedAsACandidate() {
        val registry = PeerRegistry("self")
        registry.rememberSource("peer", "192.168.2.140", 17878)
        assertEquals(listOf(endpoint("192.168.2.140", 17878)), registry.candidatesOf("peer"))
    }

    @Test
    fun theSameSourceIsNotRememberedTwice() {
        val registry = PeerRegistry("self")
        registry.rememberSource("peer", "192.168.2.140", 17878)
        registry.rememberSource("peer", "192.168.2.140", 17878)
        assertEquals(1, registry.candidatesOf("peer").size)
    }

    @Test
    fun pairingCandidatesComeFirstAndSurviveTheCap() {
        val registry = PeerRegistry("self")
        registry.setCandidates("peer", listOf(endpoint("2001:db8::9", 17878), endpoint("192.168.2.82", 17878)))
        for (index in 0 until 12) registry.rememberSource("peer", "10.0.0.$index", 17878)
        val candidates = registry.candidatesOf("peer")
        assertTrue(candidates.size <= 8, "候选池必须封顶：${candidates.size}")
        // 配对码给的两条是最可靠的，必须还在最前面。
        assertEquals(endpoint("2001:db8::9", 17878), candidates[0])
        assertEquals(endpoint("192.168.2.82", 17878), candidates[1])
        // 最近见到的那个必须在（出站重试要打它）。
        assertTrue(candidates.any { it.host == "10.0.0.11" }, "最新看到的源地址不能被挤掉")
    }

    @Test
    fun rememberingASourceNeverChangesTheCurrentAddress() {
        val registry = PeerRegistry("self")
        registry.rememberSource("peer", "192.168.2.140", 17878)
        assertEquals(null, registry.get("peer"), "只记候选，不建记录、不改当前地址")
    }
}
