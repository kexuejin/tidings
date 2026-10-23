// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import com.kapp.tidings.core.transport.plaintextLinkForbidden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * core 侧的能力声明守卫（task-48 F2）。
 *
 * 今天两次「静默不生效」（`lnk1` / `att1`）都是"cap 常数在、但没进生效的那份清单"。
 * 这条测试把每个 `CAP_*` 逼进两档之一：被 core 的两份 profile 清单声明，或在台账里带理由。
 * 它**不**替代 composeApp 那份 `RelaySettings.caps()` 的对拍（那份管顺序与开关收窄），
 * 两份角度不同、并存。
 */
class CapabilityDeclarationTest {

    @Test
    fun everyCapConstantIsDeclaredByAProfileOrExplicitlyExcused() {
        val known = CapabilityInventory.constants.values.toSet()
        val unexplained = known - CapabilityInventory.declaredByCoreProfiles - CapabilityInventory.excused.keys
        assertEquals(
            emptySet(),
            unexplained,
            "有 cap 常数既没被 MOBILE_CAPS/AGENT_CAPS 声明、也没进台账白名单 —— " +
                "这正是 lnk1/att1 那类\"代码支持但永远不生效\"的形状：加 cap 必须在 CapabilityInventory 表态",
        )
    }

    @Test
    fun anExcusedConstantMustNotAlsoBeDeclared() {
        // 白名单记的是"不在清单里"的账。既写进白名单、又其实被声明了 = 账错了。
        val wronglyExcused = CapabilityInventory.excused.keys intersect CapabilityInventory.declaredByCoreProfiles
        assertEquals(emptySet(), wronglyExcused, "被声明了的 cap 不该再挂在\"已豁免\"名下")
    }

    @Test
    fun theExcusedEntriesCarryAReason() {
        val emptyReasons = CapabilityInventory.excused.filterValues { it.isBlank() }.keys
        assertEquals(emptySet(), emptyReasons, "豁免必须写得出理由，否则它只是\"忘了声明\"的遮羞布")
    }

    /**
     * F3 的裁决（lead 2026-10-18）：**私网 ∨ 本机同 /64 ∨ link-local/ULA 都算局域网**。
     *
     * 这条钉住复查侧（`plaintextLinkForbidden` 走 `isLanAddress`）：GUA 的家庭 LAN 不能因为
     * "按地址分类它像公网"就被摘掉一条合法明文连接（task-40 真机：`2001:db8:2020:cb44::/64`）。
     * 同时钉住反方向：真公网上的明文**必须**被摘（fail-closed，明文不许出网）。
     */
    @Test
    fun guaLanPlaintextIsLanAndRealPublicPlaintextIsForbidden() {
        val locals = listOf("2001:db8:2020:cb44::1")
        val guaLanPeer = "2001:db8:2020:cb44:1234::9"
        assertTrue(
            IpAddressUtils.isLanAddress(guaLanPeer, locals),
            "同 /64 的 GUA 就是同一段局域网 —— 这是 task-40 真机踩出来的判据",
        )
        assertFalse(
            plaintextLinkForbidden(isPlaintext = true, peerAddress = guaLanPeer, localAddresses = locals),
            "GUA-LAN 上的明文连接不该被摘（复查侧要保守）",
        )
        assertTrue(
            plaintextLinkForbidden(
                isPlaintext = true,
                peerAddress = "2001:4860:4860::8888",
                localAddresses = locals,
            ),
            "真公网上的明文必须被摘：公网只许 TLS + 双向 pin",
        )
    }
}
