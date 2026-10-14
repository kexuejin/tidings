// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「没有可用地址就按没有网络说」的护栏。
 *
 * 这条规则住在 [NetworkMonitor] 的接口默认实现里（见 [withoutUsableAddress]），
 * 平台层只填原始快照。真机上验证过的缺陷：Android 在 Wi-Fi 掉线那一瞬间会给出
 * 「连接方式 Wifi、在线，但一个地址都没有」的自相矛盾快照，而且不会自己恢复 ——
 * 「发现」页于是一直显示 Wifi。下面这几条就是防止这个结论被绕过。
 */
class NetworkSnapshotTest {

    @Test
    fun noAddressMeansNoNetwork() {
        val raw = NetworkSnapshot(
            online = true,
            transport = Transport.Wifi,
            localAddresses = emptyList(),
            vpnActive = false,
        )

        val honest = raw.withoutUsableAddress()

        assertFalse(honest.online, "一个地址都没有却说在线，就是让用户以为消息发得出去")
        assertEquals(Transport.None, honest.transport, "连接方式必须说「没有网络」，不能还留着 Wifi")
    }

    @Test
    fun anAddressLeavesTheSnapshotAlone() {
        val raw = NetworkSnapshot(
            online = true,
            transport = Transport.Wifi,
            localAddresses = listOf("192.168.2.22"),
            vpnActive = false,
        )

        assertEquals(raw, raw.withoutUsableAddress(), "有地址时这条规则一个字都不该改")
    }

    @Test
    fun cellularAlsoCollapsesWhenItsAddressIsGone() {
        val raw = NetworkSnapshot(
            online = true,
            transport = Transport.Cellular,
            localAddresses = emptyList(),
            vpnActive = false,
        )

        assertEquals(Transport.None, raw.withoutUsableAddress().transport)
    }

    @Test
    fun vpnFlagSurvivesTheCorrection() {
        val raw = NetworkSnapshot(
            online = true,
            transport = Transport.Wifi,
            localAddresses = emptyList(),
            vpnActive = true,
        )

        assertTrue(
            raw.withoutUsableAddress().vpnActive,
            "VPN 在不在是另一回事：它决定「要不要提示对方只能单播」，跟有没有地址无关",
        )
    }

    @Test
    fun theInterfaceItselfAppliesTheCorrection() {
        val monitor = object : NetworkMonitor {
            override fun rawSnapshot() = NetworkSnapshot(
                online = true,
                transport = Transport.Wifi,
                localAddresses = emptyList(),
                vpnActive = false,
            )
        }

        assertEquals(
            Transport.None,
            monitor.snapshot().transport,
            "平台只实现 rawSnapshot 也必须拿到纠正后的结论 —— 否则又是「接口在、没人调」",
        )
    }
}
