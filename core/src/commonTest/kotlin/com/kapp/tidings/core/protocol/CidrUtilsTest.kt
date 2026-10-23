// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CidrUtilsTest {

    @Test
    fun normalizeValidCidrs() {
        assertEquals("192.168.1.0/24", CidrUtils.normalizeCidr("192.168.1.0/24"))
        assertEquals("192.168.1.0/24", CidrUtils.normalizeCidr("192.168.1.123/24"))
        assertEquals("10.0.0.0/8", CidrUtils.normalizeCidr("10.255.1.2/8"))
        assertEquals("172.16.0.0/16", CidrUtils.normalizeCidr("172.16.5.9/16"))
    }

    @Test
    fun rejectInvalidCidrs() {
        assertNull(CidrUtils.normalizeCidr("invalid"))
        assertNull(CidrUtils.normalizeCidr("192.168.1.1"))
        assertNull(CidrUtils.normalizeCidr("192.168.1.1/7")) // prefix < 8
        assertNull(CidrUtils.normalizeCidr("192.168.1.1/31")) // prefix > 30
        assertNull(CidrUtils.normalizeCidr("256.1.1.1/24")) // octet > 255
        assertNull(CidrUtils.parseCidrHosts("10.0.0.0/21")) // hosts > 1024 -> cannot expand hosts
    }

    @Test
    fun parseCidrHostsExpansion() {
        val hosts = CidrUtils.parseCidrHosts("192.168.1.0/30")
        // /30: hostCount = 2^(32-30) - 2 = 2
        assertEquals(listOf("192.168.1.1", "192.168.1.2"), hosts)

        val hosts24 = CidrUtils.parseCidrHosts("10.2.3.0/24")
        assertEquals(254, hosts24?.size)
        assertEquals("10.2.3.1", hosts24?.first())
        assertEquals("10.2.3.254", hosts24?.last())
    }

    @Test
    fun buildCidrHostPlanDeduplication() {
        val plan = CidrUtils.buildCidrHostPlan(
            listOf(
                "192.168.1.0/30",
                "192.168.1.2/30", // 同一个网段
                "invalid",
            )
        )
        assertEquals(listOf("192.168.1.1", "192.168.1.2"), plan)
    }
}
