// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **「宣告列表收哪些地址」的判据，用合成地址钉住。**
 *
 * ## 为什么不能只靠网卡枚举的集成测试
 *
 * 用户开不开代理 TUN 是**环境变量**，测试无从得知：
 *  - 没开 TUN 时，那条集成测试对代理地址的断言退化成空断言（假绿）；
 *  - 开了 TUN 时，如果判据把 `198.18.0.1` 当成「该宣告的隧道地址」，它又会报假红。
 *
 * 所以把判据抽成 `shouldAdvertiseAddress`，用**合成的 InetAddress** 直接断言 ——
 * 与网卡枚举解耦，任何机器上结论一致。集成测试只保留它真正独有的价值：
 * 「本机真实存在的隧道地址是否被接线用上」。
 *
 * ## 反向验证
 *
 * 把 `isProxyTunAddress` 那一行删掉 → `proxyTunGatewayIsNotAdvertised` 立刻变红；
 * 把链路本地判断删掉 → `linkLocalAndLoopbackAreNotAdvertised` 变红。
 */
class AdvertisedAddressFilterTest {

    private fun addr(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun proxyTunGatewayIsNotAdvertised() {
        // Clash/mihomo 开 TUN 增强模式时的 fake-IP 网关，接管全部 IPv4 路由。
        assertFalse(shouldAdvertiseAddress(addr("198.18.0.1")), "代理 TUN 网关")
        assertFalse(shouldAdvertiseAddress(addr("198.18.255.255")), "RFC 2544 段内上界")
        assertFalse(shouldAdvertiseAddress(addr("198.19.0.1")), "RFC 2544 段内")
    }

    @Test
    fun realTunnelAndLanAddressesAreAdvertised() {
        // 这些才是「VPN 上的对端要能拨到」的地址，少一条就死锁。
        assertTrue(shouldAdvertiseAddress(addr("100.66.238.121")), "Tailscale")
        assertTrue(shouldAdvertiseAddress(addr("10.233.138.167")), "ZeroTier")
        assertTrue(shouldAdvertiseAddress(addr("192.168.2.82")), "局域网")
        assertTrue(shouldAdvertiseAddress(addr("2001:db8:2020:cb44::1")), "公网 IPv6")
        // 段外同前缀不能被误判（否则会把正常地址吞掉）。
        assertTrue(shouldAdvertiseAddress(addr("198.20.0.1")), "198.20/16 不是基准段")
    }

    @Test
    fun linkLocalAndLoopbackAreNotAdvertised() {
        assertFalse(shouldAdvertiseAddress(addr("127.0.0.1")), "回环")
        assertFalse(shouldAdvertiseAddress(addr("169.254.1.1")), "IPv4 链路本地")
        assertFalse(shouldAdvertiseAddress(addr("fe80::1")), "IPv6 链路本地")
        assertFalse(shouldAdvertiseAddress(addr("0.0.0.0")), "未指定")
    }
}
