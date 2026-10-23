// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.kapp.tidings.domain.model.PeerUi

/**
 * 手动节点的处境判定。
 *
 * 这条判定的用户可见后果是「加入」页上那行小字，以及「清理没有回应的」按钮到底删不删
 * 一个条目。两条规则必须同时成立，而它们会互相打架：
 * 1. **有过回应**的（哪怕现在离线）绝不能删 —— 对方只是关机了，删掉等于每次他重启
 *    都要用户重填一次 IP；
 * 2. **从来没有回应**的必须能被找出来 —— 换了网段之后旧地址永远连不上，
 *    界面上一句解释都没有，用户只会觉得"我明明配过了"。
 *
 * 所以"没有回应"和"离线"是两个字段，不是一个。真机上正好有一个能验证第 3 条的例子：
 * 手机里存着 `192.168.2.82:17879`，而桌面节点监听的是 **17878** —— 同一个 IP、
 * 不同端口，只看 IP 会把这条死地址误判成"有过回应"，于是永远清不掉。
 */
class ManualPeerStatusTest {

    @Test
    fun aPeerThatAnsweredIsNotUnreachableEvenWhenOffline() {
        val entry = ManualPeerEntry("192.168.2.82", 17878)
        val statuses = manualPeerStatuses(
            entries = listOf(entry),
            peers = listOf(peer(nodeId = "mac-mini", ip = "192.168.2.82", udpPort = 17878, online = false)),
        ).single()

        assertEquals("mac-mini", statuses.nodeId)
        assertTrue(statuses.offline, "对方关机了：是离线，不是没有回应")
        assertFalse(statuses.unreachable, "有过回应的地址不能进清理名单")
    }

    @Test
    fun anOnlinePeerIsNeitherOfflineNorUnreachable() {
        val statuses = manualPeerStatuses(
            entries = listOf(ManualPeerEntry("192.168.2.82", 17878)),
            peers = listOf(peer("mac-mini", "192.168.2.82", 17878, online = true)),
        ).single()

        assertFalse(statuses.offline)
        assertFalse(statuses.unreachable)
    }

    /**
     * 这条就是手机上的真实情况：`192.168.2.82:17879` 是早先填错的端口
     * （桌面节点在 **17878** 上收 UDP）。同一个 IP 上确实有节点，但**不是这个端口**，
     * 所以这条地址从来没有回应过 —— 只看 IP 的话它会一直伪装成"配好了"。
     */
    @Test
    fun sameHostDifferentPortIsStillUnreachable() {
        val statuses = manualPeerStatuses(
            entries = listOf(
                ManualPeerEntry("192.168.2.82", 17878),
                ManualPeerEntry("192.168.2.82", 17879),
            ),
            peers = listOf(peer("mac-mini", "192.168.2.82", 17878, online = true)),
        )

        assertFalse(statuses[0].unreachable, "端口对得上的那条是通的")
        assertTrue(statuses[1].unreachable, "端口对不上的那条必须被标出来，否则永远清不掉")
        assertNull(statuses[1].nodeId)
    }

    @Test
    fun anAddressNobodyEverAnsweredOnIsUnreachable() {
        val statuses = manualPeerStatuses(
            entries = listOf(ManualPeerEntry("192.168.2.99", 17878)),
            peers = listOf(peer("mac-mini", "192.168.2.82", 17878, online = true)),
        ).single()

        assertTrue(statuses.unreachable)
        assertNull(statuses.nodeId)
        assertFalse(statuses.offline, "从来没学到过节点，谈不上离线")
    }

    @Test
    fun anEmptyPeerTableMakesEverythingUnreachable() {
        val statuses = manualPeerStatuses(
            entries = listOf(ManualPeerEntry("192.168.2.82", 17878), ManualPeerEntry("10.0.0.5", 17878)),
            peers = emptyList(),
        )

        assertEquals(2, statuses.count { it.unreachable })
    }

    /** 用户填主机名而不是 IP 时也要能对上号（协议里 `host` 是设备名）。 */
    @Test
    fun aManualEntryCanMatchByDeviceName() {
        val statuses = manualPeerStatuses(
            entries = listOf(ManualPeerEntry("desktop-node.local", 17878)),
            peers = listOf(peer("mac-mini", "192.168.2.82", 17878, online = true, host = "desktop-node.local")),
        ).single()

        assertEquals("mac-mini", statuses.nodeId)
        assertFalse(statuses.unreachable)
    }

    /**
     * 端口未知（`0`）时**不能**说"没有回应"。
     *
     * 「没有回应」是一个强断言：界面上会这么写，还会把这条地址放进清理名单。
     * 不知道端口就说不知道 —— 同「不知道就别说话」。
     */
    @Test
    fun anUnknownPortIsNotEvidenceThatNobodyAnswered() {
        val statuses = manualPeerStatuses(
            entries = listOf(ManualPeerEntry("192.168.2.82", 17879)),
            peers = listOf(peer("mac-mini", "192.168.2.82", udpPort = 0, online = true)),
        ).single()

        assertEquals("mac-mini", statuses.nodeId)
        assertFalse(statuses.unreachable, "端口未知不等于没有回应")
    }

    private fun peer(
        nodeId: String,
        ip: String,
        udpPort: Int,
        online: Boolean,
        host: String = "",
    ) = PeerUi(nodeId = nodeId, nick = nodeId, host = host, ip = ip, udpPort = udpPort, online = online)
}
