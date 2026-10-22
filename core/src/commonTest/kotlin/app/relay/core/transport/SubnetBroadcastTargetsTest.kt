// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **子网广播必须和其他发现目标一起发出去。**
 *
 * ## 真机现场（2026-10-22）
 *
 * 代理开 TUN 增强模式时装下 `0.0.0.0/1` + `128.0.0.0/1` 两条路由（比默认 `/0` 更具体）。
 * 受限广播 `255.255.255.255` **没有具体路由条目**，只能落到默认路由 ⇒ 被整条接进代理
 * 的虚拟口，局域网里一个对端都收不到。
 *
 * 用户的疑问：「没关之前可以正常通过 Tailscale/ZeroTier 访问，为什么会对 Tidings 有影响」
 * —— 因为 Tailscale 走 `100.64/10`（更具体的路由，抢先匹配），而 Tidings 的广播没有那条。
 *
 * 子网广播（`192.168.2.255`）有直连路由（`192.168.2.0/24`），抢不走。
 */
class SubnetBroadcastTargetsTest {

    /** 给了子网广播就必须出现在目标里（否则这条修复等于没做）。 */
    @Test
    fun subnetBroadcastsJoinTheTargetList() {
        val targets = broadcastTargets(
            broadcastHost = "255.255.255.255",
            broadcastPort = 17878,
            manualPeers = emptyList(),
            knownPeers = emptyList(),
            subnetBroadcasts = listOf("192.168.2.255", "10.233.138.255"),
        )
        assertTrue(
            targets.contains("255.255.255.255" to 17878),
            "受限广播仍要保留：同网段、无代理时它是最省的发现方式",
        )
        assertTrue(
            targets.contains("192.168.2.255" to 17878),
            "子网广播必须发：TUN 环境下只有它能出网",
        )
        assertTrue(targets.contains("10.233.138.255" to 17878), "多网卡都要覆盖")
    }

    /** 不给子网广播时行为与改动前**一字不差**（未接入的平台走这条）。 */
    @Test
    fun anEmptySubnetListKeepsTheOldBehaviour() {
        val targets = broadcastTargets(
            broadcastHost = "255.255.255.255",
            broadcastPort = 17878,
            manualPeers = emptyList(),
            knownPeers = emptyList(),
        )
        assertEquals(listOf("255.255.255.255" to 17878), targets)
    }

    /** 空白项与自身重复不该被发出去（那是无意义的包）。 */
    @Test
    fun blankAndDuplicateBroadcastAddressesAreSkipped() {
        val targets = broadcastTargets(
            broadcastHost = "255.255.255.255",
            broadcastPort = 17878,
            manualPeers = emptyList(),
            knownPeers = emptyList(),
            subnetBroadcasts = listOf("", "   ", "255.255.255.255"),
        )
        assertEquals(listOf("255.255.255.255" to 17878), targets)
    }
}
