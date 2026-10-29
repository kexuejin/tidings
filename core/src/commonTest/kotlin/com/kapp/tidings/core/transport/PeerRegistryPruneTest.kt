// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * registry 的数据卫生：久未露面的离线身份在启动修剪里被剪掉。
 *
 * 真机现场（验证轮抓到）：registry 只进不出 —— 重装换过身份的手机、联调留下的旧实例、
 * 夹具的一次性节点，把节点表与建群成员选择器堆到 40+ 条（一台桌面在手机上是 5 个
 * 同名设备条目）。修剪不是拉黑：设备再出现时发现流程会原样把它学回来。
 *
 * 钉住五条：
 * 1. 超窗的离线身份被剪，窗口内的不剪；
 * 2. [keep] 认领的（群成员）**绝不**剪 —— 离线队列还要为它留着位；
 * 3. 被剪身份的**候选池**一起消失，但 **pin 留着** —— 自动过期是数据卫生，不是撤销信任；
 * 4. 在线的条目不在本函数语义里（sweep 管在线→离线）；
 * 5. 用户显式 [PeerRegistry.forget] 才是撤销信任（pin 一起消失）—— 两条删除语义不同，
 *    混在一起就会让"节点表自己清理一下"顺手把公网信任锚降级成 TOFU。
 */
class PeerRegistryPruneTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId, nick = nodeId, company = "", dept = "", team = "",
        avatar = 0, profileRev = 1, host = "h", platform = "linux",
        tcpPort = 17879, ver = "test", caps = emptyList(),
    )

    private fun registryWith(vararg entries: Triple<String, Long, Boolean>): PeerRegistry {
        val registry = PeerRegistry(selfNodeId = "self-node")
        for ((nodeId, lastSeen, online) in entries) {
            if (online) {
                registry.touch(
                    nodeId = nodeId,
                    ip = "192.168.2.22",
                    udpPort = 17878,
                    profile = profile(nodeId),
                    confirmed = false,
                )
                // touch 把 lastSeen 钉在"现在"；测试要"在线但超窗"的形态就把它拨回去。
                registry.get(nodeId)!!.lastSeen = lastSeen
            } else {
                registry.restoreOffline(profile(nodeId), "192.168.2.22", 17878, lastSeen)
            }
        }
        return registry
    }

    @Test
    fun staleOfflineIdentityIsPrunedButRecentOneStays() {
        val registry = registryWith(
            Triple("ghost-node", now - 8 * day, false),
            Triple("fresh-node", now - 1 * day, false),
        )
        val pruned = registry.pruneOfflineOlderThan(7 * day, keep = { false }, now = now)

        assertEquals(listOf("ghost-node"), pruned)
        assertNull(registry.get("ghost-node"))
        assertEquals("fresh-node", registry.get("fresh-node")?.profile?.nodeId)
    }

    @Test
    fun keptNodesSurviveEvenWhenStale() {
        val registry = registryWith(
            Triple("group-member", now - 30 * day, false),
            Triple("stranger", now - 30 * day, false),
        )
        val pruned = registry.pruneOfflineOlderThan(7 * day, keep = { it == "group-member" }, now = now)

        assertEquals(listOf("stranger"), pruned)
        assertEquals("group-member", registry.get("group-member")?.profile?.nodeId)
    }

    @Test
    fun prunedIdentityDropsCandidatesButKeepsPin() {
        val registry = registryWith(Triple("ghost-node", now - 8 * day, false))
        registry.setPin("ghost-node", "sha256:abc", PeerRegistry.PIN_SOURCE_PAIRING)
        registry.setCandidates("ghost-node", listOf(PeerEndpoint("192.168.2.99", 17878)))

        registry.pruneOfflineOlderThan(7 * day, keep = { false }, now = now)

        assertEquals(
            "sha256:abc",
            registry.pinOf("ghost-node"),
            "自动过期不许撤销信任根 —— 剪掉 pin 会让那台设备下次出现时落到 TOFU 首次固定",
        )
        assertEquals(PeerRegistry.PIN_SOURCE_PAIRING, registry.pinSourceOf("ghost-node"))
        assertTrue(registry.candidatesOf("ghost-node").isEmpty(), "候选池是地址记忆，跟着剪")
    }

    @Test
    fun explicitForgetStillRevokesThePin() {
        // 两条删除语义的分界线：用户显式删节点 = 撤销信任（pin 必须消失），
        // 自动过期 = 只剪记忆。把这条钉住，避免哪天有人把 prune 改回 forget。
        val registry = registryWith(Triple("ghost-node", now - 8 * day, false))
        registry.setPin("ghost-node", "sha256:abc", PeerRegistry.PIN_SOURCE_PAIRING)

        assertTrue(registry.forget("ghost-node"))

        assertNull(registry.pinOf("ghost-node"), "用户显式删节点必须把 pin 一起撤销")
    }

    @Test
    fun pruneEmitsRemovedSoPersistenceFollows() {
        val registry = registryWith(Triple("ghost-node", now - 8 * day, false))
        val removed = mutableListOf<String>()
        registry.addListener { if (it is PeerEvent.Removed) removed.add(it.nodeId) }

        registry.pruneOfflineOlderThan(7 * day, keep = { false }, now = now)

        assertEquals(
            listOf("ghost-node"),
            removed,
            "Removed 事件必须发出 —— known-peers 的落盘合并窗口靠它标脏，否则重启时原样回来",
        )
    }

    @Test
    fun onlineEntriesAreNotPrunedEvenIfStale() {
        // 在线但 lastSeen 很旧 = sweep 还没跑到的窗口形态；本函数只管离线条目。
        val registry = registryWith(Triple("online-node", now - 30 * day, true))
        val pruned = registry.pruneOfflineOlderThan(7 * day, keep = { false }, now = now)

        assertEquals(emptyList(), pruned)
        assertEquals("online-node", registry.get("online-node")?.profile?.nodeId)
    }
}
