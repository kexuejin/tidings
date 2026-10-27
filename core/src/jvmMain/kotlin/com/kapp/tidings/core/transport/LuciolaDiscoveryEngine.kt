// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import io.github.kexuejin.luciola.DiscoveryEventUi
import io.github.kexuejin.luciola.PeerUiModel
import io.github.kexuejin.luciola.UniffiEngineAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

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
    selfProfile: () -> Profile,
    udpPort: Int,
) : DiscoveryEngine {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val self = selfProfile()

    // luciola nodeId -> Tidings nodeId(lost 事件只带 luciola 侧 id)
    private val idMap = HashMap<String, String>()
    private val idMapLock = Any()

    private val adapter = UniffiEngineAdapter(
        sessionName = self.nick,
        discoveryPort = udpPort.toUShort(),
        product = "tidings",
        extra = json.encodeToString(Profile.serializer(), self),
    )

    private var started = false

    override fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        adapter.start { event ->
            when (event) {
                is DiscoveryEventUi.Found -> applyPeer(event.peer)
                is DiscoveryEventUi.Updated -> applyPeer(event.peer)
                is DiscoveryEventUi.Lost -> onLuciolaLost(event.nodeId)
                is DiscoveryEventUi.Error -> Unit // 阶段三:接 NodeRuntime 的错误面
            }
        }
    }

    override fun stop() {
        started = false
        adapter.stop() // Rust 侧广播 exit 后收尾。
    }

    override suspend fun announce() {
        // 引擎自治:start 即宣告,presence 循环周期保活(语义已覆盖)。
    }

    override suspend fun announceTo(host: String, port: Int) {
        adapter.dial(host, port.toUShort())
    }

    override suspend fun announceExit() {
        adapter.stop()
    }

    private fun applyPeer(peer: PeerUiModel) {
        val raw = peer.extra ?: return
        val profile = runCatching { json.decodeFromString(Profile.serializer(), raw) }.getOrNull() ?: return
        synchronized(idMapLock) { idMap[peer.nodeId] = profile.nodeId }
        // 源地址用 luciola 观察到的可达地址;地址表随 Tidings 资料走。
        registry.touch(profile.nodeId, peer.ip, peer.udpPort, profile, confirmed = true)
    }

    private fun onLuciolaLost(luciolaNodeId: String) {
        val tidingsId = synchronized(idMapLock) { idMap.remove(luciolaNodeId) } ?: return
        registry.markOffline(tidingsId)
    }
}
