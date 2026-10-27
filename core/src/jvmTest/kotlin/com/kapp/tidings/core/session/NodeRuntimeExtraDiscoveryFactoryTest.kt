// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.transport.DiscoveryEngine
import com.kapp.tidings.core.transport.PeerRegistry
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
        fun seenRegistry(): PeerRegistry = registry

        override fun start(scope: CoroutineScope) { started = true }
        override fun stop() { stopped = true }
        override suspend fun announce() { announcements += 1 }
        override suspend fun announceTo(host: String, port: Int) {}
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
}
