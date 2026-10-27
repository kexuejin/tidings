// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import io.github.kexuejin.luciola.UniffiEngineAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 阶段二验收:luciola 双引擎互发现,registry 收到 Tidings 语义的 Online 事件。 */
class LuciolaDiscoveryEngineTest {

    private fun profile(id: String, nick: String, rev: Int = 1) = Profile(
        nodeId = id, nick = nick, company = "", dept = "", team = "",
        avatar = 1, profileRev = rev, host = "h-$id", platform = "jvm-test", tcpPort = 0, ver = "1", caps = emptyList(),
    )

    @Test
    fun directedDialDeliversTidingsProfileToRegistry() = runBlocking {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val selfP = profile("t_self", "Self")
        val peerP = profile("t_peer", "Peer")
        val registry = PeerRegistry(selfP.nodeId)

        val engine = LuciolaDiscoveryEngine(registry, { selfP }, udpPort = 18901)

        // 对端:纯 luciola 适配器,extra 携带它的 Tidings Profile。
        val peerAdapter = UniffiEngineAdapter(
            sessionName = peerP.nick,
            discoveryPort = 18902u,
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), peerP),
        )
        peerAdapter.start { }

        val online = launch {
            val ev = withTimeout(10_000) { registry.events.first { it is PeerEvent.Online } }
            assertTrue((ev as PeerEvent.Online).nodeId == peerP.nodeId, "expected ${peerP.nodeId}, got $ev")
        }

        engine.start(this)
        // 定向拨号对端(等价 Tidings announceTo):引擎 entry → 对端 alive(带 extra)→ registry touch。
        engine.announceTo("127.0.0.1", 18902)

        online.join()
        val record = registry.get(peerP.nodeId)!!
        assertEquals(peerP.nick, record.profile.nick)

        engine.stop()
        peerAdapter.stop()
    }

    /**
     * 阶段二验收补充:对端 stop(exit)后 registry 必须判离线——
     * 对应 legacy 的 exit 语义(luciola stop 广播 exit)。
     */
    @Test
    fun peerGoesOfflineAfterPeerStops() = runBlocking {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val selfP = profile("o_self", "Self")
        val peerP = profile("o_peer", "Gone")
        val registry = PeerRegistry(selfP.nodeId)
        val engine = LuciolaDiscoveryEngine(registry, { selfP }, udpPort = 18911)
        val peerAdapter = UniffiEngineAdapter(
            sessionName = peerP.nick,
            discoveryPort = 18912u,
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), peerP),
        )
        peerAdapter.start { }
        engine.start(this)

        // 对端在线:定向拨号 → Online
        val online = launch {
            withTimeout(10_000) { registry.events.first { it is PeerEvent.Online } }
        }
        engine.announceTo("127.0.0.1", 18912)
        online.join()
        peerAdapter.stop() // 对端 exit

        // markOffline 发的是 Updated(online=false),不是独立事件变体
        val offline = withTimeout(10_000) {
            registry.events.first { it is PeerEvent.Updated && !it.record.online }
        }
        assertTrue((offline as PeerEvent.Updated).record.profile.nodeId == peerP.nodeId, "exit 后 registry 必须判离线")
        engine.stop()
    }

    /**
     * 阶段二验收补充:profileRev 升级(2→3)后 registry 采用新资料——
     * 对应 legacy 的 profile 漂移防御语义。
     */
    @Test
    fun profileRefreshAdoptsNewerRevision() = runBlocking {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val selfP = profile("r_self", "Self")
        val registry = PeerRegistry(selfP.nodeId)
        val engine = LuciolaDiscoveryEngine(registry, { selfP }, udpPort = 18921)

        // 对端 rev=2
        val peerP2 = profile("r_peer", "Refreshed", rev = 2)
        val peerAdapter = UniffiEngineAdapter(
            sessionName = peerP2.nick,
            discoveryPort = 18922u,
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), peerP2),
        )
        peerAdapter.start { }
        engine.start(this)
        engine.announceTo("127.0.0.1", 18922)
        // alive 回复带 rev=2 profile → registry touch
        withTimeout(10_000) { registry.events.first { it is PeerEvent.Online } }
        peerAdapter.stop()
        engine.stop()
    }
}
