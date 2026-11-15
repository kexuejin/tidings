// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import android.content.pm.ApplicationInfo
import com.kapp.tenon.core.protocol.AppFlavor
import com.kapp.tenon.core.protocol.FlavorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AndroidFlavorIsolationTest {

    private fun resolveFlavor(flags: Int): AppFlavor {
        return if ((flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            AppFlavor.Debug
        } else {
            AppFlavor.Release
        }
    }

    @Test
    fun debuggableFlagResolvesToDebugFlavor() {
        val flavor = resolveFlavor(ApplicationInfo.FLAG_DEBUGGABLE)
        assertEquals(FlavorMode.DEBUG, flavor.mode)
        assertEquals(27878, flavor.udpPort)
        assertEquals(27879, flavor.tcpPort)
        assertEquals("_relay-debug._udp", flavor.mdnsServiceType)
    }

    @Test
    fun nonDebuggableFlagResolvesToReleaseFlavor() {
        val flavor = resolveFlavor(0)
        assertEquals(FlavorMode.RELEASE, flavor.mode)
        assertEquals(17878, flavor.udpPort)
        assertEquals(17879, flavor.tcpPort)
        assertEquals("_relay._udp", flavor.mdnsServiceType)
    }

    @Test
    fun flavorsAreMutuallyExclusive() {
        val debug = resolveFlavor(ApplicationInfo.FLAG_DEBUGGABLE)
        val release = resolveFlavor(0)

        assertNotEquals(debug.udpPort, release.udpPort)
        assertNotEquals(debug.tcpPort, release.tcpPort)
        assertNotEquals(debug.mdnsServiceType, release.mdnsServiceType)
        assertNotEquals(debug.scheme, release.scheme)
    }
}
