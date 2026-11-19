// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.core.protocol.AppFlavor
import com.kapp.tenon.core.protocol.FlavorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopFlavorIsolationTest {

    @Test
    fun releaseAndDebugDataDirectoriesAreIsolated() {
        val releaseDir = defaultDataDirectory(AppFlavor.Release)
        val debugDir = defaultDataDirectory(AppFlavor.Debug)

        assertTrue(releaseDir.endsWith("榫卯 Tenon"))
        assertTrue(debugDir.endsWith("榫卯 Tenon Debug"))
        assertNotEquals(releaseDir, debugDir)

        val releaseCache = defaultCacheDirectory(AppFlavor.Release)
        val debugCache = defaultCacheDirectory(AppFlavor.Debug)
        assertTrue(releaseCache.endsWith("榫卯 Tenon"))
        assertTrue(debugCache.endsWith("榫卯 Tenon Debug"))
        assertNotEquals(releaseCache, debugCache)
    }

    @Test
    fun portsAndControlPlaneAreCompletelySeparated() {
        val release = AppFlavor.Release
        val debug = AppFlavor.Debug

        assertNotEquals(release.udpPort, debug.udpPort)
        assertNotEquals(release.tcpPort, debug.tcpPort)
        assertNotEquals(release.controlPlanePort, debug.controlPlanePort)
        assertNotEquals(release.mdnsServiceType, debug.mdnsServiceType)
        assertNotEquals(release.scheme, debug.scheme)

        assertEquals(17878, release.udpPort)
        assertEquals(27878, debug.udpPort)

        assertEquals(8788, release.controlPlanePort)
        assertEquals(18788, debug.controlPlanePort)

        assertEquals("_relay._udp", release.mdnsServiceType)
        assertEquals("_relay-debug._udp", debug.mdnsServiceType)
    }

    @Test
    fun platformReflectsFlavorSettings() {
        val platformRelease = DesktopAppPlatform(flavor = AppFlavor.Release)
        val platformDebug = DesktopAppPlatform(flavor = AppFlavor.Debug)

        assertEquals(FlavorMode.RELEASE, platformRelease.flavor.mode)
        assertEquals(FlavorMode.DEBUG, platformDebug.flavor.mode)

        assertTrue(platformRelease.dataDirectory.dataDirectory().endsWith("榫卯 Tenon"))
        assertTrue(platformDebug.dataDirectory.dataDirectory().endsWith("榫卯 Tenon Debug"))
    }
}
