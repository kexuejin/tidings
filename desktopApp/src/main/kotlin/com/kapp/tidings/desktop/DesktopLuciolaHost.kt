// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.desktop

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.transport.DatagramTransport
import com.kapp.tidings.core.transport.DiscoveryEngine
import com.kapp.tidings.core.transport.LuciolaTransportFactory
import com.kapp.tidings.core.transport.PeerRegistry
import com.kapp.tidings.core.transport.SecureStateStore
import com.kapp.tidings.domain.LuciolaQrInfo
import com.kapp.tidings.platform.LuciolaHost
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * 桌面宿主的 luciola 实现。**默认关**:必须显式 TIDINGS_LUCIOLA=1 才启用 ——
 * 开了之后 legacy 发现不再启动,未迁移设备只能靠二维码/手动地址找到本机。
 *
 * 身份 seed 与信任库都落在数据目录的 luciola/ 下(受限文件,不是 SharedPreferences)。
 */
class DesktopLuciolaHost(
    private val dataDirectory: () -> String,
    private val enabled: Boolean = envEnabled(),
) : LuciolaHost {
    private val lock = Any()
    private var factory: LuciolaTransportFactory? = null
    private var configuredProfile: Profile? = null

    override fun configure(profile: Profile, udpPort: Int) {
        if (!enabled) return
        synchronized(lock) {
            if (factory != null && configuredProfile == profile) return
            val dir = File(dataDirectory(), "luciola").apply { mkdirs() }
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
        // LuciolaQrInfo 里是 **base64url 无填充**（二维码省字节形态）,不是标准 base64。
        val publicKey = runCatching { Base64.getUrlDecoder().decode(info.publicKey) }.getOrNull() ?: return false
        val secret = runCatching { Base64.getUrlDecoder().decode(info.secret) }.getOrNull() ?: return false
        return runCatching { f.pairPeer(host, port, publicKey, secret = secret) }.isSuccess
    }

    override fun transportFactory(): ((PeerRegistry) -> DatagramTransport)? =
        factory?.let { f -> ({ registry: PeerRegistry -> f.createTransport(registry) }) }

    override fun discoveryFactory(): ((PeerRegistry) -> DiscoveryEngine)? =
        factory?.let { f -> ({ registry: PeerRegistry -> f.createDiscovery(registry) }) }

    /** 造一个「扫过码的手机」用的发起侧:配对信息 + 发起配对。 */
    suspend fun requestPairing(transport: DatagramTransport, host: String, port: Int, nodeId: String): Boolean {
        val f = factory ?: return false
        return transport.requestPairing(host, port, nodeId)
    }

    companion object {
        fun envEnabled(): Boolean =
            System.getenv("TIDINGS_LUCIOLA")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true

        private fun readOrCreateSeed(file: File): String {
            if (file.exists()) return file.readText().trim()
            val hex = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            file.runCatching {
                writeText(hex)
                // 尽力而为:POSIX 下收成 600(Windows 上 setReadable 返回 false,不影响功能)。
                setReadable(false, false)
                setReadable(true, true)
                setWritable(false, false)
                setWritable(true, true)
            }
            return hex
        }
    }
}
