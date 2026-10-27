// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import uniffi.luciola_uniffi.TrustStore as RustTrustStore
import uniffi.luciola_uniffi.TrustedDeviceInfo
import uniffi.luciola_uniffi.TrustLevel
import uniffi.luciola_uniffi.generateDeviceIdentity
import uniffi.luciola_uniffi.identityFromSeed

/**
 * 阶段三收尾:luciola 全栈装配工厂(发现 + 信道 + 信任)。
 *
 * 用法(JVM 宿主):
 * ```kotlin
 * val factory = LuciolaTransportFactory(profile, udpPort, pinStore, seedFile)
 * NodeRuntime(
 *     profile = profile,
 *     extraDiscovery = factory.createDiscovery(registry),
 *     // 阶段四切传呼时:transport = factory.createTransport()
 * )
 * ```
 *
 * 信任迁移策略:存量 PeerPins 是 P-256 SPKI;luciola 身份是 Ed25519——
 * **无自动转换**。新配对走 luciola QR(重扫码);过渡期两套并存。
 */
class LuciolaTransportFactory(
    private val profile: Profile,
    private val udpPort: Int = 17878,
    private val legacyPinStore: PeerPinStore? = null,
    /**
     * Ed25519 seed 的持久化回调(32 字节 hex)——存 Keychain / Keystore /
     * 受限文件。首次调用返回 null 会生成新身份并存回。
     */
    private val seedPersist: (String?) -> String,
) {
    private val identity: uniffi.luciola_uniffi.DeviceIdentityInfo =
        identityFromSeed(seedPersist(null).chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    val fingerprint: String get() = identity.fingerprint
    val shortPin: String get() = identity.shortPin

    private val rustTrustStore = RustTrustStore(null) // JSON 恢复见阶段四落盘

    /** 信任一个设备(配对确认后调用)。 */
    fun trustDevice(nodeId: String, name: String, publicKey: ByteArray, full: Boolean) {
        rustTrustStore.trustDevice(
            TrustedDeviceInfo(
                deviceId = nodeId, name = name, publicKey = publicKey,
                fingerprint = uniffi.luciola_uniffi.identityFingerprint(publicKey),
                level = if (full) TrustLevel.FULL else TrustLevel.PARTIAL,
                pinnedAtMs = System.currentTimeMillis().toULong(),
            )
        )
    }

    fun createDiscovery(registry: PeerRegistry): LuciolaDiscoveryEngine =
        LuciolaDiscoveryEngine(registry, { profile }, udpPort)

    fun createTransport(): SecureChannelTransport =
        SecureChannelTransport(
            identitySeed = identity.seed,
            peerKey = { host, port ->
                // deny-by-default:只有 TrustStore 里有的对端才能建连
                val entry = rustTrustStore.entries().firstOrNull {
                    it.deviceId == "$host:$port" // 简化映射;阶段四按 registry 查
                }
                entry?.publicKey
            },
        )
}
