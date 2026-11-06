// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * overlay-transport-design.md §5.2 落地纪律的测试形态（「写成测试，不是写成文档」）：
 *
 * 1. 承载是 OVERLAY ⇒ 明文永远不批 —— **无论** `lanVerdict` 是什么；
 * 2. LAN 直连 ⇒ 判据与今天的 `isLanAddress` 逐字相同（零行为变化）；
 * 3. 反向验证（§5.2-2）：把「回环特权」放回去（只看 `lanVerdict`、忽略承载）——
 *    本组用例必须变红，否则没在测东西。
 */
class OverlayCarrierPolicyTest {

    @Test
    fun overlayCarrierNeverCarriesPlaintextEvenForLoopback() {
        // 「回环特权泄漏」的形态 = 拨号方把 isLanAddress(127.0.0.1) == true 的结论
        // 直接带进明文路径。承载是 OVERLAY 时一票否决，verdict 是什么都拦下。
        assertFalse(
            OverlayCarrierPolicy.mayCarryPlaintext(Carrier.OVERLAY, lanVerdict = true),
            "overlay 端点（回环代理背后是公网隧道）永远不算局域网明文承载",
        )
        assertFalse(OverlayCarrierPolicy.mayCarryPlaintext(Carrier.OVERLAY, lanVerdict = false))
    }

    @Test
    fun lanCarrierKeepsTodaysJudgementExactly() {
        assertTrue(
            OverlayCarrierPolicy.mayCarryPlaintext(Carrier.LAN_DIRECT, lanVerdict = true),
            "LAN 直连维持今天的裁决：isLanAddress 说行就行",
        )
        assertFalse(OverlayCarrierPolicy.mayCarryPlaintext(Carrier.LAN_DIRECT, lanVerdict = false))
        assertFalse(
            OverlayCarrierPolicy.mayCarryPlaintext(Carrier.PUBLIC_DIRECT, lanVerdict = true),
            "公网直连永远不走明文（fail-closed）—— 即便地址形似私网",
        )
    }

    @Test
    fun overlayVirtualIpRangeStaysOutOfThePlaintextPath() {
        // 100.64/10（CGNAT，overlay 虚拟 IP 的落点）必须继续被判成「公网」：
        // isPrivateOrLocal 的 IPv4 分支不含它 —— 这条保持原样，overlay 走自己的承载，
        // 不靠「地址恰好像私网」混进任何明文裁决。
        val lanVerdictForCgnat = false
        assertFalse(
            OverlayCarrierPolicy.mayCarryPlaintext(Carrier.OVERLAY, lanVerdict = lanVerdictForCgnat),
        )
    }
}
