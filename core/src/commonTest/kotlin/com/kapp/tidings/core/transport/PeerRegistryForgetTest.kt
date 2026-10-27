// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「移除节点」在 registry 这一层的语义。
 *
 * 这条用例存在的理由：删除最典型的失败形态不是崩溃，而是**删不干净** —— 节点行没了，
 * 但 pin / 候选地址 / 档案新鲜度还留着，于是重启后又冒出来一行，或者它再被扫到时
 * 被判成「旧档案」拒收。四个容器少清一个，症状都不一样，而且都不会报错。
 */
class PeerRegistryForgetTest {

    private fun profile(id: String) = Profile(
        nodeId = id,
        nick = "nick-$id",
        company = "",
        dept = "",
        team = "",
        avatar = 1,
        profileRev = 1,
        host = "h-$id",
        platform = "jvm-test",
        tcpPort = 0,
        ver = "1",
        caps = emptyList(),
    )

    private fun registryWithPeer(): Pair<PeerRegistry, MutableList<PeerEvent>> {
        val registry = PeerRegistry("self")
        val events = mutableListOf<PeerEvent>()
        registry.addListener { events += it }
        registry.touch("peer-1", "127.0.0.1", 17878, profile("peer-1"), profileTs = 1L, confirmed = true)
        registry.setPin("peer-1", "sha256:" + "a".repeat(64), PeerRegistry.PIN_SOURCE_PAIRING)
        registry.setCandidates("peer-1", listOf(PeerEndpoint("10.0.0.2", 17878)))
        events.clear()
        return registry to events
    }

    @Test
    fun forgetClearsIdentityPinAndCandidatesThenEmitsRemoved() {
        val (registry, events) = registryWithPeer()

        assertTrue(registry.forget("peer-1"), "删一条存在的节点必须返回 true（界面靠它决定要不要弹回执）")

        assertNull(registry.get("peer-1"), "节点行必须没了")
        assertNull(registry.pinOf("peer-1"), "pin 必须一起清：否则重启后它仍是「我认识这台设备」")
        assertTrue(registry.candidatesOf("peer-1").isEmpty(), "候选地址池必须一起清：不清会继续往旧地址发心跳")
        assertEquals<List<PeerEvent>>(
            listOf(PeerEvent.Removed("peer-1")),
            events,
            "必须发一条 Removed —— 落盘只在 Updated/Online/Removed 上标脏，不发它文件里那条会留到重启",
        )
    }

    @Test
    fun forgettingAnUnknownNodeIsANoOp() {
        val registry = PeerRegistry("self")
        val events = mutableListOf<PeerEvent>()
        registry.addListener { events += it }

        assertFalse(registry.forget("nobody"), "没这条节点时返回 false，界面不该弹「已移除」")
        assertTrue(events.isEmpty(), "没有东西被删就不该发事件（否则落盘白写一次）")
    }

    @Test
    fun aForgottenNodeCanBeLearnedAgain() {
        // 「移除」不是拉黑：它下次被发现在网上时必须能重新学回来。
        val (registry, _) = registryWithPeer()
        registry.forget("peer-1")

        registry.touch("peer-1", "192.168.2.9", 17878, profile("peer-1"), profileTs = 2L, confirmed = true)

        assertNotNull(registry.get("peer-1"), "重新被发现时必须重新出现在节点表里（这是设计，不是缺陷）")
        // 档案新鲜度判据也要被清：留着会让同一份 profile 被判成旧档案而拒收。
        assertEquals(1, registry.profileRevOf("peer-1"), "profileRev 来自新学到的 profile")
    }
}
