// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「像不像局域网」这个近似判据的边界。认不出来的一律 false。 */
class LanAddressTest {

    @Test
    fun privateIpv4RangesAreLan() {
        assertTrue(isLanAddressLiteral("10.0.0.5"))
        assertTrue(isLanAddressLiteral("172.16.3.4"))
        assertTrue(isLanAddressLiteral("172.31.255.254"))
        assertTrue(isLanAddressLiteral("192.168.2.22"))
        assertTrue(isLanAddressLiteral("127.0.0.1"))
        // 带端口的形态也要认（luciola 的远程地址就是 "ip:port"）。
        assertTrue(isLanAddressLiteral("192.168.2.22:27878"))
    }

    @Test
    fun publicIpv4IsNotLan() {
        assertFalse(isLanAddressLiteral("8.8.8.8"))
        assertFalse(isLanAddressLiteral("172.32.0.1"), "172.32 已经出了 172.16/12")
        assertFalse(isLanAddressLiteral("100.64.1.1"), "覆盖面隧道要单独判，这里不算局域网")
        assertFalse(isLanAddressLiteral("1.1.1.1:443"))
    }

    @Test
    fun ipv6UlaAndLinkLocalAreLan() {
        assertTrue(isLanAddressLiteral("fd00::1"))
        assertTrue(isLanAddressLiteral("fc12:3456::1"))
        assertTrue(isLanAddressLiteral("fe80::1"))
        assertTrue(isLanAddressLiteral("[fe80::1]:27878"))
        assertTrue(isLanAddressLiteral("fe80::1%en0"))
        assertFalse(isLanAddressLiteral("2001:4860:4860::8888"), "公网 v6 不是局域网")
    }

    @Test
    fun junkIsNeverLan() {
        assertFalse(isLanAddressLiteral(null))
        assertFalse(isLanAddressLiteral(""))
        assertFalse(isLanAddressLiteral("   "))
        assertFalse(isLanAddressLiteral("kedeMac-mini-156.local"), "主机名不猜")
        assertFalse(isLanAddressLiteral("192.168.2"), "不是四段")
        assertFalse(isLanAddressLiteral("192.168.2.999"))
        assertFalse(isLanAddressLiteral("192.168.2.22:0"), "端口越界不算合法端点")
    }
    @Test
    fun ipv4MappedIpv6IsJudgedByTheEmbeddedIpv4() {
        // luciola 入站回调给的就是这种形态：同网段的手机必须算局域网，
        // 否则 `auto` 档永远退回「问人」。
        assertTrue(isLanAddressLiteral("::ffff:192.168.2.22"))
        assertTrue(isLanAddressLiteral("[::ffff:192.168.2.22]:43852"))
        assertFalse(isLanAddressLiteral("::ffff:8.8.8.8"))
    }
}
