// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.platform

import android.content.Context
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.transport.DatagramTransport
import com.kapp.tidings.core.transport.DiscoveryEngine
import com.kapp.tidings.core.transport.LuciolaTransportFactory
import com.kapp.tidings.core.transport.PeerRegistry
import com.kapp.tidings.core.transport.SecureStateStore
import com.kapp.tidings.domain.LuciolaQrInfo
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Android 宿主的 luciola 实现:身份 seed 与信任库落在应用私有 filesDir(卸载即清)。
 *
 * 原生库来自 luciola-android AAR 的 jniLibs —— JNA 在 Android 上需要显式把
 * `nativeLibraryDir` 交给它(否则只会在 /system/lib 里找)。
 */
class AndroidLuciolaHost(
    private val context: Context,
    private val enabled: Boolean = true,
) : LuciolaHost {
    private val lock = Any()
    private var factory: LuciolaTransportFactory? = null
    private var configuredProfile: Profile? = null

    override fun configure(profile: Profile, udpPort: Int) {
        if (!enabled) return
        installNativeLibraryPath()
        synchronized(lock) {
            if (factory != null && configuredProfile == profile) return
            val dir = File(context.filesDir, "luciola").apply { mkdirs() }
            val seedFile = File(dir, "ed25519-seed.hex")
            val stateFile = File(dir, "state.json")
            factory = LuciolaTransportFactory(
                profile = profile,
                udpPort = udpPort,
                seedPersist = { readOrCreateSeed(seedFile) },
                stateStore = object : SecureStateStore {
                    override fun load(): String? = stateFile.takeIf { it.exists() }?.readText()
                    override fun save(value: String) {
                        stateFile.writeText(value)
                    }
                },
            )
            configuredProfile = profile
        }
    }

    override fun pairingInfo(): LuciolaQrInfo? =
        factory?.let { LuciolaQrInfo.of(it.publicKey, it.pairSecretBytes()) }

    override fun pairPeer(host: String, port: Int, info: LuciolaQrInfo): Boolean {
        val f = factory ?: return false
        // 二维码里是 base64url 无填充。
        val publicKey = runCatching { Base64.getUrlDecoder().decode(info.publicKey) }.getOrNull() ?: return false
        val secret = runCatching { Base64.getUrlDecoder().decode(info.secret) }.getOrNull() ?: return false
        return runCatching { f.pairPeer(host, port, publicKey, secret = secret) }.isSuccess
    }

    override fun transportFactory(): ((PeerRegistry) -> DatagramTransport)? =
        factory?.let { f -> ({ registry: PeerRegistry -> f.createTransport(registry) }) }

    override fun discoveryFactory(): ((PeerRegistry) -> DiscoveryEngine)? =
        factory?.let { f -> ({ registry: PeerRegistry -> f.createDiscovery(registry) }) }

    private fun installNativeLibraryPath() {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return
        if (nativeDir.isBlank()) return
        val existing = System.getProperty("jna.library.path")
        val path = listOfNotNull(nativeDir, existing?.takeIf { it.isNotBlank() })
            .joinToString(File.pathSeparator)
        System.setProperty("jna.library.path", path)
    }

    private fun readOrCreateSeed(file: File): String {
        if (file.exists()) return file.readText().trim()
        val hex = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        file.runCatching { writeText(hex) }
        return hex
    }
}
