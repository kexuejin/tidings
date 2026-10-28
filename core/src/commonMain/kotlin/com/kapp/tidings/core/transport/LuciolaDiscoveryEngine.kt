// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.withLock
import io.github.kexuejin.luciola.DiscoveryEventUi
import io.github.kexuejin.luciola.PeerUiModel
import io.github.kexuejin.luciola.UniffiEngineAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * 阶段二:luciola 发现引擎(JVM 桌面宿主;Android 在阶段三接入)。
 *
 * 映射(MIGRATION-tidings.md):
 * - found/updated → extra 反序列化为 Tidings Profile → [PeerRegistry.touch]
 *   (confirmed = true:luciola 引擎侧即定向握手语义)——registry 自发
 *   Updated/Online,Messenger 积压补发免费获得触发。
 * - lost → luciola nodeId → Tidings nodeId 映射表 → [PeerRegistry.markOffline]。
 * - announce/announceExit:引擎自治(start 宣告、presence 保活、stop 挥手)。
 * - announceTo(host, port) → [UniffiEngineAdapter.dial](manualPeers 定向重宣告)。
 *
 * 纯 luciola 节点(无 extra)被忽略:不是 Tidings 节点。
 * 监督(看门狗/换网重建)阶段三包 DiscoveryController 时补上。
 */
class LuciolaDiscoveryEngine(
    private val registry: PeerRegistry,
    private val selfProfile: () -> Profile,
    private val udpPort: Int,
    /**
     * 心跳间隔:luciola 只在 found/updated/lost 时推事件,而 registry 的在线判定是
     * "lastSeen 超时即离线"(legacy 心跳语义)。不补心跳时,一个**明明在线**的对端会在
     * 一个 sweep 窗口后显示离线,发送直接进队列(真机实测:主机看到手机 online=false)。
     */
    private val heartbeatMs: Long = 5_000,
    /**
     * 把信标身份钉到宿主自己的零信任体系上(luciola 0.6.0):
     * - [engineNodeId]:信标携带宿主自己的 nodeId,信标身份 = 信道身份,不再由
     *   引擎随机生成第二套;
     * - [channelTcpPort]:加密信道的真实监听端口,对端从发现层直接拿到可拨端口,
     *   不再猜 udp+1;
     * - [publicKey]:本机 Ed25519 公钥,与外拨/入站握手出示的身份一致。
     * 三者都可空 = 维持旧行为(引擎自动生成)。
     */
    private val engineNodeId: String? = null,
    private val channelTcpPort: Int? = null,
    private val publicKey: ByteArray? = null,
) : DiscoveryEngine {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    // 档案在 **start() 那一刻**取：tlsPin / 地址是运行时才知道的，构造时快照会把它们冻住。
    private var adapter: UniffiEngineAdapter? = null

    // luciola nodeId -> Tidings nodeId(lost 事件只带 luciola 侧 id)
    private val idMap = HashMap<String, String>()
    private val idMapLock = com.kapp.tidings.core.createCoreLock()

    private var started = false
    private var heartbeatJob: Job? = null

    /** luciola nodeId -> 最近一次 found/updated 的原始 peer(心跳按它重放 touch)。 */
    // 跨平台：ConcurrentHashMap 只在 JVM 有。这里用 CoreLock + HashMap ——
    // 访问都是短操作（读多写少），粗粒度锁的代价远小于把整类锁死在 jvmSharedMain。
    private val peersLock = com.kapp.tidings.core.createCoreLock()
    private val knownPeers = HashMap<String, PeerUiModel>()

    @Volatile
    var lastError: String? = null
        private set

    override fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        val self = selfProfile()
        val adapter = UniffiEngineAdapter(
            sessionName = self.nick,
            discoveryPort = udpPort.toUShort(),
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), self),
            nodeId = engineNodeId ?: self.nodeId,
            tcpPort = channelTcpPort?.toUShort(),
            publicKey = publicKey,
        ).also { this.adapter = it }
        adapter.start { event ->
            when (event) {
                is DiscoveryEventUi.Found -> applyPeer(event.peer)
                is DiscoveryEventUi.Updated -> applyPeer(event.peer)
                is DiscoveryEventUi.Lost -> onLuciolaLost(event.nodeId)
                is DiscoveryEventUi.Error -> {
                    // bind_failed 之类必须可见:静默吞掉时表现为"扫不到/端口悄悄换了",
                    // 而加密信道仍按配置端口在听,两边端口从此对不上。
                    lastError = event.code + ": " + event.message
                    println("[luciola] engine error " + lastError)
                }
            }
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                peersLock.withLock { knownPeers.values.toList() }.forEach { applyPeer(it) }
            }
        }
    }

    override fun stop() {
        started = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        knownPeers.clear()
        adapter?.stop() // Rust 侧广播 exit 后收尾。
        adapter = null
    }

    override suspend fun announce() {
        // 引擎自治:start 即宣告,presence 循环周期保活(语义已覆盖)。
    }

    override suspend fun announceTo(host: String, port: Int) {
        // 越界端口不能进 toUShort()：它会**静默截断**成另一个端口（70000 → 4464），
        // 于是"拨了但没人应答"，日志里只有一次超时。
        if (port !in 1..65535) {
            println("[luciola] refuse announceTo with bad port=" + port)
            return
        }
        adapter?.dial(host, port.toUShort())
    }

    override suspend fun announceExit() {
        adapter?.stop()
    }

    private val loggedPeers = HashSet<String>()

    private fun applyPeer(peer: PeerUiModel) {
        peersLock.withLock { knownPeers[peer.nodeId] = peer }
        val raw = peer.extra ?: return
        val profile = runCatching { json.decodeFromString(Profile.serializer(), raw) }.getOrNull() ?: return
        // **两个 id 都要登记**：引擎在 Found/Updated 里报的是它自己的 nodeId，而 Lost 里
        // 报的 id 在部分引擎版本里是信标里那个（我们自己的 Tidings nodeId）。只登记前者时，
        // Lost 查不到映射 ⇒ `onLuciolaLost` 直接 return ⇒ 对端明明下线了，
        // 本端节点表**永远显示在线**（这正是 §3 那类"看起来正常、实际收不到"的形态）。
        idMapLock.withLock {
            idMap[peer.nodeId] = profile.nodeId
            idMap[profile.nodeId] = profile.nodeId
        }
        if (loggedPeers.add(profile.nodeId)) {
            println("[luciola] discovered " + profile.nodeId + " at " + peer.ip + ":" + peer.udpPort)
        }
        // 端口优先用档案里的 luciolaPort:发现引擎上报的 udpPort 可能是临时端口,
        // 而加密信道监听在固定端口(真机实测 52213 vs 17878)。老档案没这个键才回退。
        //
        // **这里必须自己校验范围**:这段 JSON 来自 native 引擎的 `peer.extra`,
        // **不经过** CodecProfile.validateProfile(那条路只覆盖信封里的 profile)。
        // 越界值若原样进 registry,会被 toUShort() 截断成另一个端口 —— 发不出去、也没有报错。
        val advertisedPort = profile.luciolaPort
        val sendPort = if (advertisedPort != null && advertisedPort !in 1..65535) {
            println("[luciola] ignore out-of-range luciolaPort=" + advertisedPort)
            peer.udpPort
        } else {
            advertisedPort ?: peer.udpPort
        }
        registry.touch(profile.nodeId, peer.ip, sendPort, profile, confirmed = true)
    }

    private fun onLuciolaLost(luciolaNodeId: String) {
        peersLock.withLock { knownPeers.remove(luciolaNodeId) }
        val tidingsId = idMapLock.withLock { idMap.remove(luciolaNodeId) }
        if (tidingsId == null) {
            // 不许静默 return：那条分支会让"对端下线了但节点表永远在线"变成无声故障。
            println("[luciola] lost event for unknown node id=" + luciolaNodeId + "（未找到映射，节点表不会翻转）")
            return
        }
        registry.markOffline(tidingsId)
    }
}