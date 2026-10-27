// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.kapp.tidings.core.crypto.secureRandomBytes
import com.kapp.tidings.core.createCoreLock
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.withLock
import kotlin.concurrent.Volatile
import uniffi.luciola_uniffi.TrustStore as RustTrustStore
import uniffi.luciola_uniffi.TrustedDeviceInfo
import uniffi.luciola_uniffi.TrustLevel
import uniffi.luciola_uniffi.identityFingerprint
import uniffi.luciola_uniffi.identityFromSeed
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 工厂状态的落盘位置(宿主提供:桌面受限文件 / Android Keystore 包装的
 * SharedPreferences / 测试内存)。**只存一个字符串** —— 内部是本文件的 JSON 封套。
 */
interface SecureStateStore {
    fun load(): String?
    fun save(value: String)
}

/** 默认内存实现(进程重启即丢,只用于测试/预览)。 */
class InMemorySecureStateStore(initial: String? = null) : SecureStateStore {
    @Volatile
    private var value: String? = initial
    override fun load(): String? = value
    override fun save(value: String) {
        this.value = value
    }
}

@Serializable
private data class FactoryStateJson(
    /** luciola TrustStore 自己的 JSON(含 deviceId/name/pk/fingerprint/level/pinnedAt)。 */
    val trust: String? = null,
    /** "host:port" → base64 Ed25519 public key:外拨 pin 的查找表。 */
    val endpoints: Map<String, String> = emptyMap(),
    /** 只有指纹、没有公钥的信任项(监听侧 TOFU 配对用)。 */
    val trustedFingerprints: List<String> = emptyList(),
    /** 二维码里带出去的配对密钥(128 位随机数,base64);见 [PairingAdmission]。 */
    val pairSecret: String? = null,
    /** 发起侧:扫描到的对端 "host:port" → 它二维码里的 secret(base64)。 */
    val pairingSecrets: Map<String, String> = emptyMap(),
)

/**
 * 阶段三:luciola 全栈装配工厂(发现 + 信道 + 信任)。
 *
 * 用法(JVM 宿主):
 * ```kotlin
 * val factory = LuciolaTransportFactory(profile, udpPort, stateStore = fileStore)
 * val runtime = NodeRuntime(
 *     profile = profile,
 *     extraDiscovery = factory.createDiscovery(...),
 *     messengerTransportFactory = { registry -> factory.createTransport(registry) },
 * )
 * // 扫码(luciola://pair v2)拿到 host / port / host 公钥之后:
 * factory.pairPeer(host, port, hostPublicKey)
 * ```
 *
 * ## 信任模型(为什么两个表)
 *
 * - **endpoints**:配对时拿到的是「地址 + Ed25519 公钥」,没有 nodeId。外拨必须按
 *   (host, port) 找到 pin —— 这张表就是查找键,**落盘**,重启后照旧生效。
 * - **TrustStore**:luciola 的分级信任(Full/Partial)与指纹账本;入站信道按指纹
 *   验信任。它按公钥去重,所以同一个对端换地址不会多出一条。
 *
 * 存量 PeerPins 是 P-256 SPKI;luciola 身份是 Ed25519 —— **无自动转换**。
 * 新配对走 luciola QR(重扫码);过渡期两套并存。
 */
@OptIn(ExperimentalEncodingApi::class)
class LuciolaTransportFactory(
    private val profile: Profile,
    /** 发现 UDP 端口(广播/组播收发)。 */
    private val udpPort: Int = DEFAULT_DISCOVERY_PORT,
    /**
     * 加密信道的 **TCP 监听端口**，默认与 [udpPort] 相同（"同号不同协议"）。
     *
     * 为什么允许分开：同一台机器上跑两个实例时（典型：桌面 App + iOS 模拟器，
     * 或两台桌面实例），它们的"自己的 IP"是同一个，若信道端口也相同，对端拨
     * `<ip>:<port>` 会连到**它自己**而不是你 —— 模拟器同机验证时实测到：
     * 互发现成功、拨号却打到本机。分开之后，本机把不同的 luciolaPort 广播出去，
     * 对端就能拨到正确的那一个。
     */
    private val channelListenPort: Int = udpPort,
    private val legacyPinStore: PeerPinStore? = null,
    /**
     * Ed25519 seed 的持久化回调(32 字节 hex)——存 Keychain / Keystore /
     * 受限文件。首次调用返回 null 会生成新身份并存回。
     */
    private val seedPersist: (String?) -> String,
    private val stateStore: SecureStateStore = InMemorySecureStateStore(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /**
     * 保护信任账本的四张表。原来是 JVM 的 `@Synchronized` 方法修饰 + `ConcurrentHashMap`，
     * 两者在 Kotlin/Native 都不存在。CoreLock 两端都可重入（ReentrantLock / NSRecursiveLock），
     * 所以"加了锁的方法里再调另一个加锁方法"（如 pairPeer → trustDevice）不会死锁。
     */
    private val stateLock = createCoreLock()

    val identity = identityFromSeed(seedHexToBytes(seedPersist(null)))

    val publicKey: ByteArray get() = identity.publicKey
    val fingerprint: String get() = identity.fingerprint
    val shortPin: String get() = identity.shortPin

    @Volatile
    private var state: FactoryStateJson = loadState()

    private val rustTrustStore = RustTrustStore(state.trust)

    /** "host:port" → public key;配对后立即生效,重启从 stateStore 恢复。 */
    private val endpoints = SharedMap<String, ByteArray>().apply {
        state.endpoints.forEach { (key, b64) ->
            runCatching { put(key, Base64.UrlSafe.decode(b64)) }
        }
    }

    /** "host:port" → 扫到的对端配对密钥(发起侧用);缺 = 这个对端没扫过码。 */
    private val pairingSecrets = SharedMap<String, ByteArray>().apply {
        state.pairingSecrets.forEach { (key, b64) ->
            runCatching { put(key, Base64.UrlSafe.decode(b64)) }
        }
    }

    /** 仅指纹的信任(监听侧 TOFU);与 [endpoints] 一起决定入站放行。 */
    private val trustedFingerprints =
        SharedSet<String>().apply { state.trustedFingerprints.forEach { add(it) } }

    /**
     * 配对密钥:128 位随机数,随二维码出去(不进发现广播)。首次生成后落盘,
     * 使二维码在重启后仍然有效;想强制所有已扫码设备重新配对 = 换一个 secret。
     */
    private val pairSecret: ByteArray = state.pairSecret
        ?.let { runCatching { Base64.UrlSafe.decode(it) }.getOrNull() }
        ?.takeIf { it.size >= MIN_PAIR_SECRET_BYTES }
        ?: secureRandomBytes(DEFAULT_PAIR_SECRET_BYTES)

    init {
        if (state.pairSecret == null) persist()
    }

    /** 二维码/luciola 配对页要展示的 secret(base64)。 */
    fun pairSecretBase64(): String = Base64.UrlSafe.encode(pairSecret)

    /** 原始 secret(16 字节) —— 二维码构造器要字节形态。返回副本,调用方改不到内部状态。 */
    fun pairSecretBytes(): ByteArray = pairSecret.copyOf()

    /**
     * 主机的配对准入实现:三关(公钥指纹绑定会话、secret、落信任)由
     * [SecureChannelTransport] 校验,这里只提供期望的 secret 与落盘动作。
     */
    fun pairingAdmission(): PairingAdmission = object : PairingAdmission {
        override fun expectedPairSecret(): ByteArray = pairSecret

        override fun onPaired(nodeId: String, publicKey: ByteArray, name: String?) {
            trustDevice(nodeId = nodeId, name = name ?: nodeId, publicKey = publicKey, full = true)
            // 真机/桌面排障:配对是"看不见结果"的一步,必须有可检索的一行日志。
            println("[luciola] paired node=" + nodeId + " fp=" + identityFingerprint(publicKey).take(18))
        }

        override fun onPairingRejected(nodeId: String?, reason: String) {
            println("[luciola] pairing rejected node=" + nodeId + " reason=" + reason)
        }
    }

    // ------------------------------------------------------------------ 配对

    /**
     * 扫码/外部配对成功后的唯一入口:绑定「对端地址 ↔ 已核对的公钥」。
     *
     * @param alsoReachableAt 配对码里同一台设备的其他地址(IPv4 + IPv6 多候选);
     *        它们共用同一个端口与公钥,任一条可达就能拨通。
     */
    fun pairPeer(
        host: String,
        port: Int,
        publicKey: ByteArray,
        name: String = host,
        full: Boolean = true,
        alsoReachableAt: List<String> = emptyList(),
        /** 对端二维码里的配对密钥(发起侧);给了才能用 [SecureChannelTransport.requestPairing]。 */
        secret: ByteArray? = null,
    ) = stateLock.withLock {
        require(publicKey.size == ED25519_PUBLIC_KEY_SIZE) {
            "Ed25519 public key must be $ED25519_PUBLIC_KEY_SIZE bytes, got ${publicKey.size}"
        }
        (listOf(host) + alsoReachableAt).forEach { key ->
            endpoints[endpointKey(key, port)] = publicKey
            if (secret != null) pairingSecrets[endpointKey(key, port)] = secret.copyOf()
        }
        trustDevice(nodeId = endpointKey(host, port), name = name, publicKey = publicKey, full = full)
    }

    /** 只写 TrustStore(分级/账本),不绑定地址;一般用 [pairPeer]。 */
    fun trustDevice(nodeId: String, name: String, publicKey: ByteArray, full: Boolean) = stateLock.withLock {
        rustTrustStore.trustDevice(
            TrustedDeviceInfo(
                deviceId = nodeId, name = name, publicKey = publicKey,
                fingerprint = identityFingerprint(publicKey),
                level = if (full) TrustLevel.FULL else TrustLevel.PARTIAL,
                pinnedAtMs = nowMillis().toULong(),
            )
        )
        persist()
    }

    /**
     * 监听侧 TOFU 配对:只知道指纹、拿不到公钥时登记信任。
     *
     * luciola 的入站回调只报对端指纹(不暴露 socket 地址/公钥),所以手机主动连
     * 进来这条路径只能先按指纹信任;真正要与 TrustStore 的指纹账本对齐,得等
     * 应用层配对帧把公钥带上来(见迁移手册阶段三备注)。
     */
    fun trustInboundFingerprint(fingerprint: String) = stateLock.withLock {
        trustedFingerprints.add(fingerprint)
        persist()
    }

    fun untrustInboundFingerprint(fingerprint: String) = stateLock.withLock {
        trustedFingerprints.remove(fingerprint)
        persist()
    }

    /** 撤销一个地址的配对(公钥账本按公钥去重,别处的同一设备条目不受影响)。 */
    fun unpair(host: String, port: Int) = stateLock.withLock {
        val key = endpointKey(host, port)
        val removed = endpoints.remove(key) ?: return
        pairingSecrets.remove(key)
        val fingerprint = identityFingerprint(removed)
        if (endpoints.values().none { identityFingerprint(it) == fingerprint }) {
            rustTrustStore.remove(fingerprint)
            // 撤销必须两侧一起撤:只删 pin 表、留着入站指纹信任,等于没撤。
            trustedFingerprints.remove(fingerprint)
        }
        persist()
    }

    fun isPaired(host: String, port: Int): Boolean =
        stateLock.withLock { endpoints.containsKey(endpointKey(host, port)) }

    /** 当前信任账本(按公钥去重)。 */
    fun entries(): List<TrustedDeviceInfo> = rustTrustStore.entries()

    /**
     * 入站放行判据:配对过的公钥指纹 ∪ TrustStore 账本 ∪ 仅指纹的 TOFU 信任。
     * **deny-by-default** —— 三者都没有就是陌生人。
     */
    fun isTrustedFingerprint(fingerprint: String): Boolean =
        fingerprint in trustedFingerprints ||
            rustTrustStore.entries().any { it.fingerprint == fingerprint } ||
            endpoints.values().any { identityFingerprint(it) == fingerprint }

    // ------------------------------------------------------------------ 装配

    /**
     * 发现引擎。`selfProfile` 用函数而不是快照:tlsPin / 本机地址是运行时才定下的
     * （见 [LuciolaDiscoveryEngine.start]）。
     */
    fun createDiscovery(
        registry: PeerRegistry,
        selfProfile: () -> Profile = { profile },
    ): LuciolaDiscoveryEngine = LuciolaDiscoveryEngine(registry, selfProfile, udpPort)

    /**
     * 加密信道。**必须传 registry**:入站帧只有查到对端地址才递得进
     * Messenger(见 [SecureChannelTransport] 的 KDoc)。
     */
    fun createTransport(registry: PeerRegistry): SecureChannelTransport =
        SecureChannelTransport(
            identitySeed = identity.seed,
            peerKey = { host, port -> pinFor(host, port, registry) },
            listenPort = channelListenPort,
            isTrustedFingerprint = { fingerprint -> isTrustedFingerprint(fingerprint) },
            endpointOf = { nodeId ->
                registry.get(nodeId)?.let { PeerEndpoint(it.ip, it.udpPort) }
            },
            pairing = pairingAdmission(),
            pairingSecretOf = { host, port -> stateLock.withLock { pairingSecrets[endpointKey(host, port)] } },
            selfPublicKey = identity.publicKey,
            peerOfFingerprint = { fingerprint -> endpointOfFingerprint(fingerprint, registry) },
        )

    /**
     * 画面信道的宿主（Sharer 监听 + Viewer 拨号）。
     *
     * 与消息/文件同一个身份、同一份信任库、同一套指纹 → 端点归属：画面是**另一条会话**，
     * 不是另一套安全模型。
     */
    fun createScreenChannelHost(
        registry: PeerRegistry,
        scope: kotlinx.coroutines.CoroutineScope,
        /** 画面监听端口；<= 0 = 只拨不听（手机端按平台能力决定）。 */
        listenPort: Int = -1,
    ): SecureScreenChannelHost = SecureScreenChannelHost(
        channel = SecureScreenChannel(
            identitySeed = identity.seed,
            peerKey = { host, port -> pinFor(host, port, registry) },
            listenPort = listenPort,
            isTrustedFingerprint = { fingerprint -> isTrustedFingerprint(fingerprint) },
            peerOfFingerprint = { fingerprint -> endpointOfFingerprint(fingerprint, registry) },
        ),
        scope = scope,
    )

    /**
     * 握手指纹 → 对端在 registry 里的地址。
     *
     * **deviceId 有两种形态**（实测踩过，回环 5MB 验收时收端一个字节都没收到）：
     *  - 被对方扫我们的码配对时，deviceId = 对方的 nodeId（正常）；
     *  - 我们扫对方的码配对时，deviceId = "host:port" 端点键（pairPeer 的写法）。
     * 只按 nodeId 查的话，第二种形态在 registry 里查不到 ⇒ 入站二进制帧全被丢。
     * 所以端点键形态要反查：按 ip/port 找 registry 里的对端。
     */
    internal fun endpointOfFingerprint(fingerprint: String, registry: PeerRegistry): PeerEndpoint? {
        val deviceId = rustTrustStore.entries()
            .firstOrNull { it.fingerprint == fingerprint }
            ?.deviceId
        if (deviceId != null) {
            registry.get(deviceId)?.let { return PeerEndpoint(it.ip, it.udpPort) }
            val host = deviceId.substringBeforeLast(':', "")
            val port = deviceId.substringAfterLast(':', "").toIntOrNull() ?: 0
            if (host.isNotEmpty() && port != 0) {
                registry.findByEndpoint(host, port)?.let { return PeerEndpoint(it.ip, it.udpPort) }
            }
        }
        // **兜底：本机的「地址 ↔ 公钥」配对表本身就是一份归属证据。**
        //
        // 扫码配对（[pairPeer]）把对端写进 [endpoints]，但 Rust 的 TrustStore 里那条
        // 只有在**对端反向来配对**时才会出现。而 app 端只扫过码的场景很常见（手机只扫
        // 桌面二维码）—— 那时会话能连上（信任判据查 endpoints），二进制帧却归属不到
        // 对端（原先只查 TrustStore）⇒ 文件/画面字节被整条静默丢弃，
        // 用户看到的是"对方说发了、我这边一直卡在接收中"。真机 50MB 验收踩过这一条。
        endpoints.entries().forEach { (key, publicKey) ->
            if (identityFingerprint(publicKey) != fingerprint) return@forEach
            val host = key.substringBeforeLast(':', "")
            val port = key.substringAfterLast(':', "").toIntOrNull() ?: 0
            if (host.isEmpty() || port == 0) return@forEach
            registry.findByEndpoint(host, port)?.let { return PeerEndpoint(it.ip, it.udpPort) }
            // 端口可能对不上（扫码时印在码里的端口 vs 发现上报的端口）。指纹已经**唯一**
            // 认定了这台设备，所以按地址兜底不会串台；精确端口仍然优先。
            registry.values().firstOrNull { it.ip == host }
                ?.let { return PeerEndpoint(it.ip, it.udpPort) }
        }
        return null
    }

    /**
     * 拨号用的对端公钥：先查配对表；查不到再走「registry 端点 → nodeId → TrustStore」——
     * 入站配对 (对方扫了我的码) 只把信任写进 TrustStore，没有端点映射，不补这条
     * 主机就永远拨不回去（真机实测：发送 no-ack，最后退回 legacy TCP）。
     */
    private val reportedPins = SharedSet<String>()

    /**
     * 测试入口：验证「同一台设备其他端口也能取到 pin」这条判据。
     * 生产路径只经由 [createTransport] / [createScreenChannelHost] 进入。
     */
    internal fun pinForTest(host: String, port: Int): ByteArray? =
        pinFor(host, port, PeerRegistry("pin-test"))

    private fun pinFor(host: String, port: Int, registry: PeerRegistry): ByteArray? {
        endpoints[endpointKey(host, port)]?.let { return it }
        // **同一台设备的其他端口**：身份是那把公钥，不是端口。
        // 配对写进表里的是**一条**端口的键（消息/文件那条），而画面信道在 udpPort+10、
        // 跨网文件在 tlsPort+1 —— 只按 (host, port) 精确查会得到「未配对」⇒ 新数据面
        // 在这台设备上永远拨不通（现场踩过：`pin lookup <ip>:<port> pinned=false`）。
        // 只在**该 host 下所有条目公钥一致**时才采用：同一地址出现两个不同身份时
        // 宁可拒绝（deny-by-default），也不猜。
        val sameHost = endpoints.entries().filter { (key, _) -> key.substringBeforeLast(':', "") == host }
        if (sameHost.isNotEmpty()) {
            val first = sameHost.first().second
            val unique = sameHost.all { it.second.contentEquals(first) }
            if (unique) return first
        }
        var record = registry.findByEndpoint(host, port)
        if (record == null) {
            // 端口可能属于同一节点的独立数据面（如 screenPort 或 luciolaPort）
            record = registry.values().firstOrNull { rec ->
                (rec.ip == host || com.kapp.tidings.core.protocol.IpAddressUtils.isSameHost(rec.ip, host)) &&
                    (rec.profile.screenPort == port || rec.profile.luciolaPort == port)
            } ?: registry.values().filter {
                it.ip == host || com.kapp.tidings.core.protocol.IpAddressUtils.isSameHost(it.ip, host)
            }.let { matches ->
                if (matches.size == 1) matches.first() else null
            }
        }
        val pin = record?.let { rec ->
            rustTrustStore.entries().firstOrNull { it.deviceId == rec.profile.nodeId }?.publicKey
        }
        // 拨号排障:第一次需要反查时打一行(含 registry 是否认识该端点)。
        val key = endpointKey(host, port)
        if (reportedPins.add(key)) {
            println(
                "[luciola] pin lookup " + key + " registry=" + (record?.profile?.nodeId ?: "none") +
                    " pinned=" + (pin != null),
            )
        }
        return pin
    }

    // ------------------------------------------------------------------ 内部

    private fun loadState(): FactoryStateJson {
        val raw = runCatching { stateStore.load() }.getOrNull() ?: return FactoryStateJson()
        return runCatching { json.decodeFromString(FactoryStateJson.serializer(), raw) }
            .getOrElse { FactoryStateJson() }
    }

    private fun persist() = stateLock.withLock<Unit> {
        val next = FactoryStateJson(
            trust = runCatching { rustTrustStore.toJson() }.getOrNull(),
            endpoints = endpoints.snapshot().mapValues { Base64.UrlSafe.encode(it.value) },
            trustedFingerprints = trustedFingerprints.toList(),
            pairSecret = Base64.UrlSafe.encode(pairSecret),
            pairingSecrets = pairingSecrets.snapshot().mapValues { Base64.UrlSafe.encode(it.value) },
        )
        state = next
        runCatching { stateStore.save(json.encodeToString(FactoryStateJson.serializer(), next)) }
    }

    private fun endpointKey(host: String, port: Int): String = "$host:$port"

    private fun seedHexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length == 64) { "seed must be 32 bytes of hex (64 chars), got ${clean.length}" }
        return ByteArray(32) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        const val DEFAULT_DISCOVERY_PORT: Int = 17878
        private const val ED25519_PUBLIC_KEY_SIZE = 32
        private const val DEFAULT_PAIR_SECRET_BYTES = 16
        private const val MIN_PAIR_SECRET_BYTES = 16
    }
}