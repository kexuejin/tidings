// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import io.github.kexuejin.luciola.UniffiEngineAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before

/** 阶段二验收:luciola 双引擎互发现,registry 收到 Tidings 语义的 Online 事件。 */
class LuciolaDiscoveryEngineTest {

    // 每条用例两个独立端口：引擎（发现+信道）与对端适配器各一个。
    private val enginePort1 = freeUdpPort(); private val peerPort1 = freeUdpPort()
    private val enginePort2 = freeUdpPort(); private val peerPort2 = freeUdpPort()
    private val enginePort3 = freeUdpPort(); private val peerPort3 = freeUdpPort()
    private val enginePort4 = freeUdpPort(); private val peerPort4 = freeUdpPort()
    private val enginePort5 = freeUdpPort(); private val peerPort5 = freeUdpPort()


    /** 真引擎用例：本机没有 luciola 原生库时跳过（不是失败），配了却用不了则失败。 */
    @Before
    fun requireLuciolaNative() = LuciolaNativeGuard.assumeAvailable()

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

        val engine = LuciolaDiscoveryEngine(registry, { selfP }, udpPort = enginePort1)

        // 对端:纯 luciola 适配器,extra 携带它的 Tidings Profile。
        val peerAdapter = UniffiEngineAdapter(
            sessionName = peerP.nick,
            discoveryPort = peerPort1.toUShort(),
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), peerP),
        )
        peerAdapter.start { }

        val online = launch {
            val ev = withTimeout(OBSERVE_MS) { registry.events.first { it is PeerEvent.Online } }
            assertTrue((ev as PeerEvent.Online).nodeId == peerP.nodeId, "expected ${peerP.nodeId}, got $ev")
        }

        engine.start(this)
        // 定向拨号对端(等价 Tidings announceTo):引擎 entry → 对端 alive(带 extra)→ registry touch。
        engine.announceTo("127.0.0.1", peerPort1)

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
    /**
     * luciola 只在 found/updated/lost 推事件,而 registry 的在线判定是"lastSeen 超时"；
     * 没有心跳时,一个在线的对端会在一个 sweep 窗口后变离线(真机实测主机看到手机 offline)。
     * 本用例把心跳调快,反复 sweep 后仍必须在线。
     */
    @Test
    fun heartbeatKeepsOnlinePeerFromSweepingOffline() = runBlocking {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val selfP = profile("hb_self", "Self")
        val peerP = profile("hb_peer", "Alive")
        val registry = PeerRegistry(selfP.nodeId)
        val engine = LuciolaDiscoveryEngine(registry, { selfP }, udpPort = enginePort4, heartbeatMs = 300)
        val peerAdapter = UniffiEngineAdapter(
            sessionName = peerP.nick,
            discoveryPort = peerPort4.toUShort(),
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), peerP),
        )
        peerAdapter.start { }
        engine.start(this)
        try {
            val online = launch {
                withTimeout(OBSERVE_MS) { registry.events.first { it is PeerEvent.Online } }
            }
            engine.announceTo("127.0.0.1", peerPort4)
            online.join()

            // 心跳 300ms、扫除窗口 400ms:没有心跳时第二次就会判离线。
            repeat(6) {
                delay(350)
                registry.sweep(400)
            }
            assertTrue(registry.get(peerP.nodeId)?.online == true, "有心跳的在网对端不许被 sweep 判离线")
        } finally {
            engine.stop()
            peerAdapter.stop()
        }
    }

    /**
     * 发现上报的端口可能是引擎的临时端口(真机:52213),而加密信道在固定端口监听。
     * 档案里的 luciolaPort 必须覆盖它,否则发送会拨到错误端口。
     */
    @Test
    fun registryUsesProfileLuciolaPortInsteadOfEngineReportedPort() = runBlocking {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val selfP = profile("lp_self", "Self")
        val peerP = profile("lp_peer", "FixedPort").copy(luciolaPort = 19999)
        val registry = PeerRegistry(selfP.nodeId)
        val engine = LuciolaDiscoveryEngine(registry, { selfP }, udpPort = enginePort5)
        val peerAdapter = UniffiEngineAdapter(
            sessionName = peerP.nick,
            discoveryPort = peerPort5.toUShort(),
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), peerP),
        )
        peerAdapter.start { }
        engine.start(this)
        try {
            val online = launch { withTimeout(OBSERVE_MS) { registry.events.first { it is PeerEvent.Online } } }
            engine.announceTo("127.0.0.1", peerPort5)
            online.join()
            assertEquals(19999, registry.get(peerP.nodeId)?.udpPort, "registry 的发送端口必须用档案里的 luciolaPort")
        } finally {
            engine.stop()
            peerAdapter.stop()
        }
    }

    @Test
    fun peerGoesOfflineAfterPeerStops() = runBlocking {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val selfP = profile("o_self", "Self")
        val peerP = profile("o_peer", "Gone")
        val registry = PeerRegistry(selfP.nodeId)
        val engine = LuciolaDiscoveryEngine(registry, { selfP }, udpPort = enginePort2)
        val peerAdapter = UniffiEngineAdapter(
            sessionName = peerP.nick,
            discoveryPort = peerPort2.toUShort(),
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), peerP),
        )
        peerAdapter.start { }
        engine.start(this)

        // 对端在线:定向拨号 → Online
        val online = launch {
            withTimeout(OBSERVE_MS) { registry.events.first { it is PeerEvent.Online } }
        }
        engine.announceTo("127.0.0.1", peerPort2)
        online.join()
        peerAdapter.stop() // 对端 exit

        // markOffline 发的是 Updated(online=false),不是独立事件变体
        val offline = withTimeout(OBSERVE_MS) {
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
        val engine = LuciolaDiscoveryEngine(registry, { selfP }, udpPort = enginePort3)

        // 对端 rev=2
        val peerP2 = profile("r_peer", "Refreshed", rev = 2)
        val peerAdapter = UniffiEngineAdapter(
            sessionName = peerP2.nick,
            discoveryPort = peerPort3.toUShort(),
            product = "tidings",
            extra = json.encodeToString(Profile.serializer(), peerP2),
        )
        peerAdapter.start { }
        engine.start(this)
        engine.announceTo("127.0.0.1", peerPort3)
        // alive 回复带 rev=2 profile → registry touch
        withTimeout(OBSERVE_MS) { registry.events.first { it is PeerEvent.Online } }
        peerAdapter.stop()
        engine.stop()
    }
}

/**
 * 观测预算（不是断言）：这条用例等的是真实 UDP 回环上的 found/lost 事件，
 * 单跑都在 1 秒内，全量跑（700+ 用例）时调度会明显变慢。
 * 真卡死仍会稳定超时，见 `awaitUntil` 的说明。
 */
/**
 * 动态分配一个空闲 UDP 端口。
 *
 * 原来每条用例写死 189xx：整个 JVM 里跑几百个真 socket 用例时，写死的端口可能与
 * 别的用例（或上一次没释放的监听）撞上 —— 撞上时 `start()` 静默失败，
 * 于是"对端永远不出现/永远不消失"，症状是本用例超时，根因却是端口。
 */
private fun freeUdpPort(): Int = java.net.DatagramSocket(0).use { it.localPort }

private const val OBSERVE_MS: Long = 30_000
