// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.persist.KnownPeer
import com.kapp.tidings.core.transport.KnownPeerStore
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

        override fun opener(host: String, port: Int): ScreenWireOpener = ScreenWireOpener {
            openedHost = host
            openedPort = port
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

    /** 只读的已知对端名录（测试用；core 里只有落盘实现与空实现）。 */
    private class FixedKnownPeers(private val peers: List<KnownPeer>) : KnownPeerStore {
        override fun load(): List<KnownPeer> = peers
        override fun save(peers: List<KnownPeer>) = Unit
    }

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
                    KnownPeer(
                        profile = profile(peerId, listOf(MsgTypes.CAP_SCREEN_CHANNEL), screenPort = screenPort),
                        ip = "127.0.0.1",
                        udpPort = freePort(),
                        lastSeen = 1_000L,
                    ),
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
                    KnownPeer(
                        profile = profile(peerId, emptyList(), screenPort = freePort()),
                        ip = "127.0.0.1",
                        udpPort = freePort(),
                        lastSeen = 1_000L,
                    ),
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
}
