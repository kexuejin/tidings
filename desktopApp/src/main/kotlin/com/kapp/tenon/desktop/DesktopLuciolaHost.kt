// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.desktop

import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.transport.DatagramTransport
import com.kapp.tenon.core.transport.DiscoveryEngine
import com.kapp.tenon.core.transport.LuciolaTransportFactory
import com.kapp.tenon.core.transport.PeerRegistry
import com.kapp.tenon.core.transport.ScreenChannelHost
import com.kapp.tenon.core.transport.SecureStateStore
import com.kapp.tenon.domain.LuciolaQrInfo
import com.kapp.tenon.platform.LuciolaHost
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * 桌面宿主的 luciola 实现。**恒启用** —— 这里没有开关。
 *
 * ## 为什么删掉了 `TENON_LUCIOLA` 门
 *
 * 曾经它默认关，只有设了 `TENON_LUCIOLA=1` 才建工厂。而手机侧
 * （`RelaySettings.experimentalLuciola`）默认**开** —— 两端各自一个开关、彼此不知道对方的
 * 值，真机上的结果就是「手机跑安全信道、桌面跑 legacy」：消息能通（都回落明文 UDP），
 * 在线状态却互相骗人（手机按加密链路判在线、桌面按 presence 帧判），排查起来像网络故障。
 *
 * 传输形态是**协议事实**，不是两端各自的偏好：一旦可以一端开一端关，就必然出现
 * 「谁也说不清现在跑的是哪条路」的状态。所以现在恒为 luciola，legacy 传输已从
 * `NodeRuntime` 移除。
 *
 * 身份 seed 与信任库都落在数据目录的 luciola/ 下(受限文件,不是 SharedPreferences)。
 */
class DesktopLuciolaHost(
    private val dataDirectory: () -> String,
) : LuciolaHost {
    private val lock = Any()
    private var factory: LuciolaTransportFactory? = null
    private var configuredProfile: Profile? = null

    override fun configure(profile: Profile, udpPort: Int) {
        installNativeLibraryPath()
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
            // 启动证据:真实进程日志里能看到这一行,冒烟/排障据此判断实验信道到底有没有装配。
            println("[luciola] host configured port=" + udpPort + " fp=" + factory!!.fingerprint.take(12))
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

    override fun discoveryFactory(): ((PeerRegistry, () -> Profile) -> DiscoveryEngine)? =
        factory?.let { f -> ({ registry: PeerRegistry, profile: () -> Profile -> f.createDiscovery(registry, profile) }) }

    override fun screenChannelHostFactory(
        scope: CoroutineScope,
        listenPort: Int?,
    ): ((PeerRegistry) -> ScreenChannelHost)? =
        factory?.let { f ->
            ({ registry: PeerRegistry -> f.createScreenChannelHost(registry, scope, listenPort ?: -1) })
        }

    /** 造一个「扫过码的手机」用的发起侧:配对信息 + 发起配对。 */
    suspend fun requestPairing(transport: DatagramTransport, host: String, port: Int, nodeId: String): Boolean {
        val f = factory ?: return false
        return transport.requestPairing(host, port, nodeId)
    }

    companion object {
        /**
         * 让 JNA/uniffi 找到原生库:安装包内置在 `compose.application.resources.dir/native`,
         * 开发机可用 TENON_LUCIOLA_NATIVE 指到 Rust 构建产物。必须在首次触碰
         * luciola 之前调用(否则 uniffi 已尝试加载并失败)。
         */
        internal fun installNativeLibraryPath() {
            val candidates = buildList {
                System.getenv("TENON_LUCIOLA_NATIVE")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
                System.getProperty("compose.application.resources.dir")
                    ?.let { File(it, "native") }
                    ?.takeIf { it.isDirectory }
                    ?.let { add(it.absolutePath) }
            }
            if (candidates.isEmpty()) return
            val existing = System.getProperty("jna.library.path")
            val path = (candidates + listOfNotNull(existing?.takeIf { it.isNotBlank() }))
                .joinToString(File.pathSeparator)
            System.setProperty("jna.library.path", path)
        }

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
