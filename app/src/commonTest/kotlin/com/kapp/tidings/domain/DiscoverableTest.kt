// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.kapp.tidings.domain.model.NetworkUi

/**
 * 「可被自动发现」这一行的真值表。
 *
 * 它是 [NetworkUi] 上的**派生结论**，不是存下来的状态：注册结果（`mdnsAdvertised`）
 * 与"本机有没有可用地址"（`localAddresses`）两件事都要成立才算"广播中"。
 *
 * 真机缺陷（§13.17）：Wi-Fi 关掉之后本机 IP 已经变成 `—`，这一行却还写着「广播中」，
 * 并且**不会自愈** —— 只在某一瞬间成立的状态被永久留在屏幕上。
 */
class DiscoverableTest {

    private fun network(advertised: Boolean?, addresses: List<String>) = NetworkUi(
        mdnsAdvertised = advertised,
        localAddresses = addresses,
    )

    @Test
    fun registeredWithAnAddressMeansDiscoverable() {
        assertEquals(true, network(advertised = true, addresses = listOf("192.168.2.22")).discoverable)
    }

    @Test
    fun registeredButNoAddressMustNotClaimToBeBroadcasting() {
        // 广播要有地址才发得出去；这里说"广播中"就是让用户以为对方能搜到自己。
        assertEquals(false, network(advertised = true, addresses = emptyList()).discoverable)
    }

    @Test
    fun aFailedRegistrationStaysFailedEvenWithAnAddress() {
        assertEquals(false, network(advertised = false, addresses = listOf("192.168.2.22")).discoverable)
    }

    @Test
    fun notStartedIsUnknownRatherThanFalse() {
        // null 表示"还没启动/已经停止"，和"启动过但失败了"是两件事，不能混成一句"未广播"。
        assertNull(network(advertised = null, addresses = listOf("192.168.2.22")).discoverable)
        assertNull(network(advertised = null, addresses = emptyList()).discoverable)
    }

    @Test
    fun theConclusionIsDerivedSoItRecoversWhenTheAddressComesBack() {
        // 换网只是让"地址"这一半变假，注册那一半还在 —— 地址回来就该自动回到"广播中"。
        // 这条是防止有人图省事把结论覆盖进 mdnsAdvertised：那样改小一次就再也回不来了。
        val offline = network(advertised = true, addresses = emptyList())
        val backOnline = offline.copy(localAddresses = listOf("192.168.2.22"))
        assertEquals(false, offline.discoverable)
        assertEquals(true, backOnline.discoverable)
    }
}
