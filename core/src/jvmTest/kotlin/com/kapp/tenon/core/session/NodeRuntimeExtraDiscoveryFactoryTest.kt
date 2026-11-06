// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.session

import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.core.transport.DiscoveryEngine
import com.kapp.tenon.core.transport.PeerRegistry
import com.kapp.tenon.core.transport.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * luciola 的发现要拿 registry(它把 found/updated/lost 直接写进去),而 registry 由
 * NodeRuntime 自己创建 ⇒ 宿主只能给工厂。这里钉住:
 *  - 工厂拿到的是**同一个** registry(否则发现写进去的对端,发送面看不见);
 *  - start/stop 会把它带起来/收掉(漏一处就是"发现了但永远不刷新");
 *  - 实例参数优先于工厂(老调用点行为不变)。
 */
class NodeRuntimeExtraDiscoveryFactoryTest {

    private class FakeDiscovery(private val registry: PeerRegistry) : DiscoveryEngine {
        var started = false
        var stopped = false
        var announcements = 0
        val directedDials = mutableListOf<Pair<String, Int>>()
        fun seenRegistry(): PeerRegistry = registry

        override fun start(scope: CoroutineScope) { started = true }
        override fun stop() { stopped = true }
        override suspend fun announce() { announcements += 1 }
        override suspend fun announceTo(host: String, port: Int) { directedDials += host to port }
        override suspend fun announceExit() {}
    }

    @Test
    fun factoryReceivesTheRuntimeRegistryAndLifecycleIsWired() = runBlocking {
        val profile = Profile.mobile(
            nodeId = newId(), nick = "discovery-factory", host = "localhost", tcpPort = 0, ver = "test",
        )
        var fake: FakeDiscovery? = null
        val runtime = NodeRuntime(
            profile = profile,
            udpPort = 0,
            tcpPort = 0,
            messengerTransportFactory = null,
            extraDiscoveryFactory = { registry, _ ->
                FakeDiscovery(registry).also { fake = it }
            },
        )
        // 独立 scope:不能用 runBlocking 的 coroutineContext —— cancel 它会连测试协程一起取消。
        val scope = CoroutineScope(Dispatchers.Default)
        runtime.start(scope)
        try {
            val discovery = fake
            assertTrue(discovery?.started == true, "工厂产出的发现引擎必须被 start")
            // 同一个 registry:发现写进去的对端,发送面必须看得见(实例身份而不是"等值")。
            discovery!!.seenRegistry().touch(
                "peer-x", "127.0.0.1", 17878,
                Profile.mobile(nodeId = "peer-x", nick = "p", host = "localhost", tcpPort = 0, ver = "test"),
            )
            assertEquals(1, runtime.peerList().size, "工厂拿到的必须是运行时自己那个 registry")
        } finally {
            runtime.stop()
            scope.cancel()
            assertTrue(fake?.stopped == true, "stop 必须把工厂产出的引擎也收掉")
        }
    }

    /**
     * luciola 模式下主 discovery 是 no-op 哑实现：定向宣告若只转发给它，扫码 / 填地址 /
     * 按 IP 解析对端之后两端就永远互发现不了，对端信标里的完整 profile 进不了 registry，
     * 节点表会一直停在 `node-xxxx` 合成名上 —— 而消息因为走配对密钥直拨仍然畅通，
     * 看起来只是「显示问题」。真机复验：把名字改回合成名后等 75 秒不恢复。
     */
    @Test
    fun directedAnnounceReachesExtraDiscoveryInLuciolaMode() = runBlocking {
        val profile = Profile.mobile(
            nodeId = newId(), nick = "directed-dial", host = "localhost", tcpPort = 0, ver = "test",
        )
        var fake: FakeDiscovery? = null
        val runtime = NodeRuntime(
            profile = profile,
            udpPort = 0,
            tcpPort = 0,
            messengerTransportFactory = { _ -> UdpTransport(0, "127.0.0.1") },
            extraDiscoveryFactory = { registry, _ ->
                FakeDiscovery(registry).also { fake = it }
            },
        )
        runtime.announceTo("127.0.0.2", 17878)
        assertEquals(
            listOf("127.0.0.2" to 17878), fake!!.directedDials,
            "luciola 模式下 announceTo 必须转发给 extraDiscovery，否则定向宣告静默丢失",
        )
        runtime.stop()
    }
}
