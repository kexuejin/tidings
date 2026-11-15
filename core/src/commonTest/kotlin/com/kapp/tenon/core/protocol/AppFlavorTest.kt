// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppFlavorTest {

    @Test
    fun releaseFlavorDefaults() {
        val flavor = AppFlavor.Release
        assertEquals(FlavorMode.RELEASE, flavor.mode)
        assertEquals(17878, flavor.udpPort)
        assertEquals(17879, flavor.tcpPort)
        assertEquals(8788, flavor.controlPlanePort)
        assertEquals("_relay._udp", flavor.mdnsServiceType)
        assertEquals("relay", flavor.scheme)
        assertEquals("", flavor.desktopDataDirSuffix)
        assertTrue(flavor.isRelease)
        assertFalse(flavor.isDebug)
    }

    @Test
    fun debugFlavorDefaults() {
        val flavor = AppFlavor.Debug
        assertEquals(FlavorMode.DEBUG, flavor.mode)
        assertEquals(27878, flavor.udpPort)
        assertEquals(27879, flavor.tcpPort)
        assertEquals(18788, flavor.controlPlanePort)
        assertEquals("_relay-debug._udp", flavor.mdnsServiceType)
        assertEquals("relay-debug", flavor.scheme)
        assertEquals(" Debug", flavor.desktopDataDirSuffix)
        assertTrue(flavor.isDebug)
        assertFalse(flavor.isRelease)
    }

    @Test
    fun fromEnvironmentParsing() {
        assertEquals(AppFlavor.Debug, AppFlavor.fromEnvironment("debug"))
        assertEquals(AppFlavor.Debug, AppFlavor.fromEnvironment("DEBUG"))
        assertEquals(AppFlavor.Release, AppFlavor.fromEnvironment("release"))
        assertEquals(AppFlavor.Release, AppFlavor.fromEnvironment(null))
        assertEquals(AppFlavor.Release, AppFlavor.fromEnvironment("unknown"))
    }
}
