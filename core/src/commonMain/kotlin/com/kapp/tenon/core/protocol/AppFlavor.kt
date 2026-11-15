// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * Application & network runtime flavor.
 *
 * Separates Debug and Release runtime environments cleanly across:
 * 1. Default network ports (UDP discovery & TCP transmission channel)
 * 2. Local control plane HTTP port (agent node)
 * 3. URI scheme and mDNS service type
 * 4. Desktop data directory suffix (to prevent single-instance lock and database collisions)
 */
enum class FlavorMode(val id: String) {
    RELEASE("release"),
    DEBUG("debug");

    companion object {
        fun fromIdOrNull(id: String?): FlavorMode? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

data class AppFlavor(
    val mode: FlavorMode = FlavorMode.RELEASE,
    val udpPort: Int = Protocol.DEFAULT_UDP_PORT,
    val tcpPort: Int = Protocol.DEFAULT_TCP_PORT,
    val controlPlanePort: Int = DEFAULT_CONTROL_PLANE_PORT,
    val mdnsServiceType: String = DEFAULT_MDNS_SERVICE_TYPE,
    val scheme: String = DEFAULT_SCHEME,
    val desktopDataDirSuffix: String = "",
) {
    val isDebug: Boolean get() = mode == FlavorMode.DEBUG
    val isRelease: Boolean get() = mode == FlavorMode.RELEASE

    companion object {
        const val DEFAULT_CONTROL_PLANE_PORT: Int = 8788
        const val DEBUG_CONTROL_PLANE_PORT: Int = 18788

        const val DEFAULT_DEBUG_UDP_PORT: Int = 27878
        const val DEFAULT_DEBUG_TCP_PORT: Int = 27879

        const val DEFAULT_MDNS_SERVICE_TYPE: String = "_relay._udp"
        const val DEBUG_MDNS_SERVICE_TYPE: String = "_relay-debug._udp"

        const val DEFAULT_SCHEME: String = "relay"
        const val DEBUG_SCHEME: String = "relay-debug"

        const val DEBUG_DATA_DIR_SUFFIX: String = " Debug"

        val Release: AppFlavor = AppFlavor(
            mode = FlavorMode.RELEASE,
            udpPort = Protocol.DEFAULT_UDP_PORT,
            tcpPort = Protocol.DEFAULT_TCP_PORT,
            controlPlanePort = DEFAULT_CONTROL_PLANE_PORT,
            mdnsServiceType = DEFAULT_MDNS_SERVICE_TYPE,
            scheme = DEFAULT_SCHEME,
            desktopDataDirSuffix = "",
        )

        val Debug: AppFlavor = AppFlavor(
            mode = FlavorMode.DEBUG,
            udpPort = DEFAULT_DEBUG_UDP_PORT,
            tcpPort = DEFAULT_DEBUG_TCP_PORT,
            controlPlanePort = DEBUG_CONTROL_PLANE_PORT,
            mdnsServiceType = DEBUG_MDNS_SERVICE_TYPE,
            scheme = DEBUG_SCHEME,
            desktopDataDirSuffix = DEBUG_DATA_DIR_SUFFIX,
        )

        fun of(mode: FlavorMode): AppFlavor = when (mode) {
            FlavorMode.RELEASE -> Release
            FlavorMode.DEBUG -> Debug
        }

        fun fromEnvironment(name: String? = null): AppFlavor {
            val mode = FlavorMode.fromIdOrNull(name) ?: FlavorMode.RELEASE
            return of(mode)
        }
    }
}
