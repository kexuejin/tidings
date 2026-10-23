// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IpAddressTest {

    @Test
    fun ipv4Validation() {
        assertTrue(IpAddressUtils.isIpv4("192.168.1.1"))
        assertTrue(IpAddressUtils.isIpv4("10.0.0.1"))
        assertTrue(IpAddressUtils.isIpv4("0.0.0.0"))
        assertTrue(IpAddressUtils.isIpv4("255.255.255.255"))

        assertFalse(IpAddressUtils.isIpv4("256.0.0.1"))
        assertFalse(IpAddressUtils.isIpv4("192.168.1"))
        assertFalse(IpAddressUtils.isIpv4("192.168.1.1.1"))
        assertFalse(IpAddressUtils.isIpv4("abc"))
        assertFalse(IpAddressUtils.isIpv4("::1"))
    }

    @Test
    fun unbracketAndBracket() {
        assertEquals("2001:db8:123::1", IpAddressUtils.unbracket("[2001:db8:123::1]"))
        assertEquals("2001:db8:123::1", IpAddressUtils.unbracket("2001:db8:123::1"))
        assertEquals("[2001:db8:123::1]", IpAddressUtils.bracketIfIpv6("2001:db8:123::1"))
        assertEquals("[2001:db8:123::1]", IpAddressUtils.bracketIfIpv6("[2001:db8:123::1]"))
        assertEquals("192.168.1.1", IpAddressUtils.bracketIfIpv6("192.168.1.1"))
    }

    @Test
    fun ipv6ScopeClassification() {
        // GUA: 2000::/3
        val gua = "2001:db8:398:3201:1:2:3:4"
        assertEquals(Ipv6Scope.GLOBAL, IpAddressUtils.getIpv6Scope(gua))
        assertTrue(IpAddressUtils.isPublicGuaIpv6(gua))
        assertTrue(IpAddressUtils.isPublicGuaIpv6("[$gua]"))

        val gua2 = "2001:da8::1"
        assertEquals(Ipv6Scope.GLOBAL, IpAddressUtils.getIpv6Scope(gua2))
        assertTrue(IpAddressUtils.isPublicGuaIpv6(gua2))

        // ULA: fc00::/7 (fc00:: or fd00::)
        val ula1 = "fc00::1"
        val ula2 = "fd12:3456:789a::1"
        assertEquals(Ipv6Scope.UNIQUE_LOCAL, IpAddressUtils.getIpv6Scope(ula1))
        assertEquals(Ipv6Scope.UNIQUE_LOCAL, IpAddressUtils.getIpv6Scope(ula2))
        assertFalse(IpAddressUtils.isPublicGuaIpv6(ula1))
        assertFalse(IpAddressUtils.isPublicGuaIpv6(ula2))

        // Link-Local: fe80::/10
        val linkLocal = "fe80::1"
        assertEquals(Ipv6Scope.LINK_LOCAL, IpAddressUtils.getIpv6Scope(linkLocal))
        assertFalse(IpAddressUtils.isPublicGuaIpv6(linkLocal))

        // Loopback: ::1
        val loopback = "::1"
        assertEquals(Ipv6Scope.LOOPBACK, IpAddressUtils.getIpv6Scope(loopback))
        assertFalse(IpAddressUtils.isPublicGuaIpv6(loopback))

        // Scope with % should be rejected for public GUA
        assertFalse(IpAddressUtils.isPublicGuaIpv6("2001:db8:398::1%wlan0"))
    }

    @Test
    fun ipv6ByteParsing() {
        val loopbackBytes = IpAddressUtils.parseIpv6Bytes("::1")
        assertNotNull(loopbackBytes)
        assertEquals(16, loopbackBytes.size)
        assertEquals(1.toByte(), loopbackBytes[15])
        for (i in 0..14) assertEquals(0.toByte(), loopbackBytes[i])

        val full = "2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        val bytes = IpAddressUtils.parseIpv6Bytes(full)
        assertNotNull(bytes)
        assertEquals(0x20.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertEquals(0x0d.toByte(), bytes[2])
        assertEquals(0xb8.toByte(), bytes[3])
    }
}
