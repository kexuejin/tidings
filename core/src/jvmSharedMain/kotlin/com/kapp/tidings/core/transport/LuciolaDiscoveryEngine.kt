// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import io.github.kexuejin.luciola.DiscoveryEventUi
import io.github.kexuejin.luciola.PeerUiModel
import io.github.kexuejin.luciola.UniffiEngineAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

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
) : DiscoveryEngine {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    // 档案在 **start() 那一刻**取：tlsPin / 地址是运行时才知道的，构造时快照会把它们冻住。
    private var adapter: UniffiEngineAdapter? = null

    // luciola nodeId -> Tidings nodeId(lost 事件只带 luciola 侧 id)
    private val idMap = HashMap<String, String>()
    private val idMapLock = Any()

    private var started = false
    private var heartbeatJob: Job? = null

    /** luciola nodeId -> 最近一次 found/updated 的原始 peer(心跳按它重放 touch)。 */
    private val knownPeers = ConcurrentHashMap<String, PeerUiModel>()

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
                knownPeers.values.forEach { applyPeer(it) }
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
        adapter?.dial(host, port.toUShort())
    }

    override suspend fun announceExit() {
        adapter?.stop()
    }

    private val loggedPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun applyPeer(peer: PeerUiModel) {
        knownPeers[peer.nodeId] = peer
        val raw = peer.extra ?: return
        val profile = runCatching { json.decodeFromString(Profile.serializer(), raw) }.getOrNull() ?: return
        synchronized(idMapLock) { idMap[peer.nodeId] = profile.nodeId }
        if (loggedPeers.add(profile.nodeId)) {
            println("[luciola] discovered " + profile.nodeId + " at " + peer.ip + ":" + peer.udpPort)
        }
        // 源地址用 luciola 观察到的可达地址;地址表随 Tidings 资料走。
        registry.touch(profile.nodeId, peer.ip, peer.udpPort, profile, confirmed = true)
    }

    private fun onLuciolaLost(luciolaNodeId: String) {
        knownPeers.remove(luciolaNodeId)
        val tidingsId = synchronized(idMapLock) { idMap.remove(luciolaNodeId) } ?: return
        registry.markOffline(tidingsId)
    }
}
