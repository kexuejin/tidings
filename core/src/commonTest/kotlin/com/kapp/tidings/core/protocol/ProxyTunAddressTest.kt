// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **代理软件的 TUN 虚拟地址不能被当成可达地址。**
 *
 * ## 真机现场（2026-10-22）
 *
 * 代理开 TUN 增强模式后，本机多出 `utun5 = 198.18.0.1`，并装上 `0.0.0.0/1` + `128.0.0.0/1`
 * 两条路由 —— 等于接管全部 IPv4。后果是 Tidings 的两端互相拿到对方的地址、却都拨不通：
 * 红米 8 次拨号全失败，队列堆到 594 条；而 Tailscale 自己的 ping 是通的（DERP 中继）。
 *
 * 关掉那个 TUN 之后 `ping` 立刻恢复到 416ms，`/v1/announce` 一次就把红米拉回在线。
 *
 * 所以这类地址必须**排除在宣告之外**：它只是代理自己的虚拟口，不对应任何可达网络。
 */
class ProxyTunAddressTest {

    /** `198.18.0.0/15`（RFC 2544 基准测试段，被代理软件借用作 TUN 地址）。 */
    @Test
    fun theProxyTunRangeIsRecognised() {
        assertTrue(IpAddressUtils.isProxyTunAddress("198.18.0.1"))
        assertTrue(IpAddressUtils.isProxyTunAddress("198.18.255.255"))
        assertTrue(IpAddressUtils.isProxyTunAddress("198.19.0.1"))
    }

    /**
     * **真实地址不能被误判成代理口** —— 误判的后果是那台设备的 VPN 地址永远学不到。
     */
    @Test
    fun realAddressesAreNotMistakenForProxyTun() {
        assertFalse(IpAddressUtils.isProxyTunAddress("100.66.238.121"), "Tailscale")
        assertFalse(IpAddressUtils.isProxyTunAddress("10.233.138.167"), "ZeroTier")
        assertFalse(IpAddressUtils.isProxyTunAddress("192.168.2.82"), "局域网")
        assertFalse(IpAddressUtils.isProxyTunAddress("198.20.0.1"), "段外同前缀")
        assertFalse(IpAddressUtils.isProxyTunAddress("fd7a:115c:a1e0::1"), "IPv6 不该被这个判据命中")
        assertFalse(IpAddressUtils.isProxyTunAddress(""), "空串")
    }
}
