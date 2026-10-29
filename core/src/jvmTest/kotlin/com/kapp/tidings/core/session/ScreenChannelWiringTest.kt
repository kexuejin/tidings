// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.persist.KnownPeer
import com.kapp.tidings.core.transport.KnownPeerStore
import com.kapp.tidings.core.transport.PeerEndpoint
import com.kapp.tidings.core.transport.PeerEndpointStore
import com.kapp.tidings.core.transport.PeerRegistry
import com.kapp.tidings.core.transport.ScreenChannelHost
import com.kapp.tidings.core.transport.ScreenWire
import com.kapp.tidings.core.transport.ScreenWireOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 画面信道在运行时里的**接线**（不是数据面本身）：
 *
 *  - Sharer 侧：`start()` 必须真的让宿主开始监听，`stop()` 必须收掉；
 *  - Viewer 侧：协商通过时新通路排在**最前**（优先加密、失败再落 legacy），
 *    协商不过时一条都不许插进去。
 *
 * 为什么要专门测这条：画面数据面与 legacy 通路**并存**，接线错了的两种后果都很安静 ——
 * 要么多拨一条永远握不上手的路（表现为"投屏转圈"），要么明明协商通过却还在走
 * 17879/17882（表现为「迁移完了但端口还在」）。
 */
class ScreenChannelWiringTest {

    private fun profile(id: String, caps: List<String>, screenPort: Int? = null) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "linux", tcpPort = 17879,
        ver = "test", caps = caps, screenPort = screenPort,
    )

    private class FakeHost : ScreenChannelHost {
        var listening = false
        var stopped = false
        var openedHost: String? = null
        var openedPort: Int = -1
        val openedHosts = mutableListOf<String>()

        override fun opener(host: String, port: Int): ScreenWireOpener = ScreenWireOpener {
            openedHost = host
            openedPort = port
            openedHosts += host
            null // 测试只关心"有没有试这条"，不真建会话
        }

        override suspend fun startListening(onWire: (ScreenWire) -> Unit) {
            listening = true
        }

        override fun stopListening() {
            stopped = true
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** 只读的候选端点表（测试用；真机上是 PeerEndpointFileStore）。 */
    private class FixedEndpointStore(
        private val entries: Map<String, List<PeerEndpoint>>,
    ) : PeerEndpointStore {
        override fun load(): Map<String, List<PeerEndpoint>> = entries
        override fun save(endpoints: Map<String, List<PeerEndpoint>>) = Unit
    }

    /** 只读的已知对端名录（测试用；core 里只有落盘实现与空实现）。 */
    private class FixedKnownPeers(private val peers: List<KnownPeer>) : KnownPeerStore {
        override fun load(): List<KnownPeer> = peers
        override fun save(peers: List<KnownPeer>) = Unit
    }

    /**
     * 一条"最近见过"的已知对端夹具。
     *
     * `lastSeen` **必须是最近**：NodeRuntime 启动时会把久未露面的离线身份剪掉
     * （REGISTRY_PRUNE_MS，7 天），夹具里写死一个 1970 的时间戳会让它在拨号之前就被剪掉，
     * 症状是"0 条通路"。这条测试关心的是**接线**，修剪有自己的测试
     * （PeerRegistryPruneTest），两者不该在这里互相绊住。
     */
    private fun knownPeer(
        peerId: String,
        caps: List<String>,
        ip: String,
        screenPort: Int? = null,
    ) = KnownPeer(
        profile = profile(peerId, caps, screenPort = screenPort),
        ip = ip,
        udpPort = freePort(),
        lastSeen = nowMillis(),
    )

    @Test
    fun secureScreenPathIsPreferredWhenNegotiatedAndListenerStarts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeHost()
        val peerId = newId()
        val screenPort = freePort()
        val runtime = NodeRuntime(
            profile = profile("self", MsgTypes.MOBILE_CAPS + MsgTypes.CAP_SCREEN_CHANNEL),
            udpPort = 0,
            tcpPort = 0,
            fileChannelEnabled = true,
            screenChannelPort = freePort(),
            screenChannelHostFactory = { _: PeerRegistry -> fake },
            // 对端从"已知对端"名录里恢复：档案带 scr1 + 画面端口，与真机重启后的形态一致。
            knownPeerStore = FixedKnownPeers(
                listOf(
                    knownPeer(peerId, listOf(MsgTypes.CAP_SCREEN_CHANNEL), "127.0.0.1", screenPort),
                ),
            ),
        )
        try {
            runtime.start(scope)
            assertTrue(fake.listening, "start() 必须让画面宿主开始监听（否则 scr1 是空话）")
            val attempts = runtime.screenWireAttempts(peerId)
            assertTrue(attempts.size >= 2, "协商通过时新通路必须排在 legacy 之前（实际 ${attempts.size} 条）")
            attempts.first().open()
            assertEquals(screenPort, fake.openedPort, "第一条必须是画面信道，且拨的是对端声称的端口")
            assertEquals("127.0.0.1", fake.openedHost)
        } finally {
            runtime.stop()
            scope.cancel()
        }
        assertTrue(fake.stopped, "stop() 必须把画面监听收掉")
    }

    @Test
    fun withoutScr1NothingIsInserted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeHost()
        val peerId = newId()
        val runtime = NodeRuntime(
            profile = profile("self", MsgTypes.MOBILE_CAPS + MsgTypes.CAP_SCREEN_CHANNEL),
            udpPort = 0,
            tcpPort = 0,
            fileChannelEnabled = true,
            screenChannelPort = freePort(),
            screenChannelHostFactory = { _: PeerRegistry -> fake },
            knownPeerStore = FixedKnownPeers(
                listOf(
                    knownPeer(peerId, emptyList(), "127.0.0.1"),
                ),
            ),
        )
        try {
            runtime.start(scope)
            // 对端没声明 scr1（老端）：一条都不许插进去，全走 legacy。
            runtime.screenWireAttempts(peerId).forEach { it.open() }
            assertFalse(fake.openedPort != -1, "对端没声明 scr1 时不许拨画面信道")
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }
    /**
     * **画面信道必须覆盖对端的公网候选地址**（退役 17882 的前提）。
     *
     * 17882 是跨网投屏 TLS 的入口。luciola 模式要退役它，前提是画面信道自己能走
     * 公网地址 —— 只拨 `record.ip`（局域网那个）的话，蜂窝场景就彻底没有投屏了。
     * 这条测试就是那个前提的证据。
     */
    @Test
    fun secureScreenCoversPublicCandidateAddresses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeHost()
        val peerId = newId()
        val screenPort = freePort()
        val runtime = NodeRuntime(
            profile = profile("self", MsgTypes.MOBILE_CAPS + MsgTypes.CAP_SCREEN_CHANNEL),
            udpPort = 0,
            tcpPort = 0,
            fileChannelEnabled = true,
            screenChannelPort = freePort(),
            screenChannelHostFactory = { _: PeerRegistry -> fake },
            knownPeerStore = FixedKnownPeers(
                listOf(
                    knownPeer(peerId, listOf(MsgTypes.CAP_SCREEN_CHANNEL), "192.168.2.22", screenPort),
                ),
            ),
            // 公网候选地址从候选池注入（与真机上"上一次配对/发现学到的地址"同一条路）。
            peerEndpointStore = FixedEndpointStore(
                mapOf(peerId to listOf(PeerEndpoint("203.0.113.9", freePort()))),
            ),
        )
        try {
            runtime.start(scope)
            val attempts = runtime.screenWireAttempts(peerId)
            // 局域网那条 + 公网候选 + legacy 的若干条
            assertTrue(
                attempts.size >= 3,
                "画面信道必须把公网候选也排进去（实际只有 " + attempts.size + " 条）——" +
                    "否则退役 17882 后蜂窝场景没有投屏",
            )
            // 逐条拨一下，看它试过哪些地址。
            attempts.forEach { it.open() }
            val tried = fake.openedHosts.toSet()
            assertTrue(tried.contains("192.168.2.22"), "局域网地址必须试过，实际 $tried")
            assertTrue(tried.contains("203.0.113.9"), "公网候选地址也必须试过，实际 $tried")
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }
}