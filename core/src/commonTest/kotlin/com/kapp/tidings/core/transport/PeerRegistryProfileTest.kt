// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `profileConfirmationOnly` 这道"封印"必须是**可解除**的。
 *
 * 这道封印本身是对的：`profileRev` 相同时，一条时间戳不前进的 entry 不该改写已知资料
 * （防乱序 / 防重放）。但上游 `peer-registry.ts:83` 在**真正的新版本**到来时会把它删掉
 * （`if (newer) this.profileConfirmationOnly.delete(nodeId)`），我们漏了这一句，于是封印
 * 变成永久：一个节点只要曾经被记过一次，之后无论把 `profileRev` 提到多少，它的资料都
 * 再也进不来。
 *
 * 2026-10-04 在真机上撞到的就是这条：agentNode 改了 `tcpPort`，手机手里永远停着旧的
 * `17879`，>1200 字节的消息继续被投递进桌面茶话间。
 */
class PeerRegistryProfileTest {

    @Test
    fun newerRevisionClearsTheConfirmationLatch() {
        val registry = PeerRegistry(SELF_ID)

        // 1. 第一次接触：confirmationOnly 里还没有它，正常采纳。
        registry.touch(PEER_ID, "10.0.0.2", 17878, profile(rev = 0, tcpPort = 17879), profileTs = 1_000, confirmed = true)
        assertEquals(17879, portOf(registry))

        // 2. 一条乱序的定向 entry（同 rev、时间戳不前进）—— 仍然被采纳（confirmed 优先），
        //    但从此这个节点被记进 confirmationOnly。
        registry.touch(PEER_ID, "10.0.0.2", 17878, profile(rev = 0, tcpPort = 1111), profileTs = 900, confirmed = true)
        assertEquals(1111, portOf(registry))

        // 3. 同 rev 的普通帧：ordered 成立，但封印在 ⇒ 不采纳。这正是封印该干的事。
        registry.touch(PEER_ID, "10.0.0.2", 17878, profile(rev = 0, tcpPort = 2222), profileTs = 2_000)
        assertEquals(1111, portOf(registry), "同 rev 的普通帧不该改写资料")

        // 4. 对端把 rev 提上去（= 它的资料真的变了）⇒ 必须采纳。
        registry.touch(PEER_ID, "10.0.0.2", 17878, profile(rev = 1, tcpPort = 3333), profileTs = 3_000)
        assertEquals(1, revOf(registry))
        assertEquals(3333, portOf(registry))

        // 5. **关键断言**：封印已经被解除，所以 rev 1 之后同 rev 的普通帧又能生效了。
        //    漏掉 `if (newer) remove(...)` 时这一步会失败 —— 封印会永久留在集合里。
        registry.touch(PEER_ID, "10.0.0.2", 17878, profile(rev = 1, tcpPort = 4444), profileTs = 4_000)
        assertEquals(
            4444,
            portOf(registry),
            "收到更新版本后封印必须解除，否则该节点此后再也无法更新资料（上游 peer-registry.ts:83）",
        )
    }

    private fun portOf(registry: PeerRegistry): Int =
        assertNotNull(registry.get(PEER_ID), "节点应当还在表里").profile.tcpPort

    private fun revOf(registry: PeerRegistry): Int =
        assertNotNull(registry.get(PEER_ID), "节点应当还在表里").profile.profileRev

    private fun profile(rev: Int, tcpPort: Int): Profile = Profile.agent(
        nodeId = PEER_ID,
        nick = "对端",
        host = "peer",
        tcpPort = tcpPort,
        ver = "test",
        profileRev = rev,
    )

    private companion object {
        const val SELF_ID = "11111111-1111-4111-8111-111111111111"
        const val PEER_ID = "22222222-2222-4222-8222-222222222222"
    }
}
