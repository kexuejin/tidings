// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * K1 的边界判据：**明文长连接只允许局域网**。
 *
 * 判松了 = 公网也走明文（等于绕过 task-13 的裁决、留一条明文后门）；判紧了 = 局域网里
 * 也用不上（那 K1 就没有意义）。所以逐个地址族钉住。
 */
class IpAddressPrivateTest {

    @Test
    fun rfc1918AndLoopbackArePrivate() {
        for (host in listOf("127.0.0.1", "10.0.0.9", "172.16.0.1", "172.31.255.254", "192.168.2.82", "169.254.1.1")) {
            assertTrue(IpAddressUtils.isPrivateOrLocal(host), "$host 应该算局域网")
        }
    }

    @Test
    fun publicIpv4IsNotPrivate() {
        for (host in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "192.169.0.1")) {
            assertFalse(IpAddressUtils.isPrivateOrLocal(host), "$host 是公网，K1 不允许明文长连接")
        }
    }

    @Test
    fun ipv6LoopbackUlaAndLinkLocalArePrivateButGuaIsNot() {
        assertTrue(IpAddressUtils.isPrivateOrLocal("::1"))
        assertTrue(IpAddressUtils.isPrivateOrLocal("fd00::1"), "ULA")
        assertTrue(IpAddressUtils.isPrivateOrLocal("fe80::1"), "链路本地")
        assertTrue(IpAddressUtils.isPrivateOrLocal("fe80::1%wlan0"), "带 scope 的链路本地")
        assertFalse(IpAddressUtils.isPrivateOrLocal("2001:db8:2020:8494::9"), "公网 GUA 必须拒")
        assertFalse(IpAddressUtils.isPrivateOrLocal("2001:4860:4860::8888"))
    }

    @Test
    fun hostnamesAndGarbageAreNotPrivate() {
        assertFalse(IpAddressUtils.isPrivateOrLocal("mac.local"))
        assertFalse(IpAddressUtils.isPrivateOrLocal(""))
        assertFalse(IpAddressUtils.isPrivateOrLocal("999.1.1.1"))
    }
}
