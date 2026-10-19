// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.nowMillis
import app.relay.core.crypto.TlsPin
import app.relay.core.protocol.LinkFrame
import app.relay.core.protocol.LinkFrames
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * task-40：「有数据免心跳」**必须有保底**。
 *
 * 真机症状：稳定后 `linkConnected` 每 ~9–12s 在 True/False 间跳。机制是 ——
 * 一侧持续收到对端 Ping ⇒ 它的 `lastActivity` 被不断刷新 ⇒ **永远不发自己的 Ping**
 * ⇒ 对端在 `deadAfterMs`(45s) 内收不到任何字节 ⇒ 判死、关连接 ⇒ 重拨。
 *
 * 这条测试构造的正是那个场景：入站帧**永不间断**（`receiveFrame` 一直有数据）。
 * 修复前心跳会被无限跳过（一个 Ping 都不发）；修复后必须**保底**发出 Ping。
 */
class LinkHeartbeatFloorTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "h",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_LINK),
    )

    /** 入站永不空闲的连接替身：每次 receiveFrame 都立刻给一帧 Ping。 */
    private class AlwaysBusyConnection : LinkConnection {
        override val remoteHost: String = "192.168.2.140"
        override val remotePort: Int = 17878
        override val isSecure: Boolean = false
        override val peerPin: TlsPin? = null
        // 泵在另一条协程上写、测试在读 —— commonTest 里不能用 java.* 的并发容器，
        // 用 Mutex 保护（写成 suspend 快照，避免 ConcurrentModificationException）。
        private val sentLock = Mutex()
        private val sentFrames = mutableListOf<ByteArray>()
        private var first = true

        suspend fun sentSnapshot(): List<ByteArray> = sentLock.withLock { sentFrames.toList() }

        override suspend fun send(frame: ByteArray) {
            sentLock.withLock { sentFrames += frame }
        }

        /** 首帧必须是 Hello（LinkServer.handle 靠它鉴权），之后**永不空闲**地回 Ping。 */
        override suspend fun receiveFrame(): ByteArray {
            if (first) {
                first = false
                return LinkFrames.body(LinkFrame.Hello("peer")).encodeToByteArray()
            }
            return LinkFrames.body(LinkFrame.Ping).encodeToByteArray()
        }

        override fun close() = Unit
    }

    @Test
    fun aSideWithNonStopInboundTrafficStillSendsAPingEventually() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", "192.168.2.140", 17878, profile("peer"), 1L, confirmed = true)
        // 心跳 40ms：测试里把「2 轮保底」压到 ~80ms 就能观察到。
        val server = LinkServer(
            registry = registry,
            scope = scope,
            heartbeatMs = 40,
            deadAfterMs = 5_000,
        )
        val connection = AlwaysBusyConnection()
        try {
            server.handle(connection)
            // 入站一直有数据；修复前这里永远等不到 Ping（心跳被无限跳过）。
            val deadline = nowMillis() + 3_000
            while (nowMillis() < deadline &&
                connection.sentSnapshot().none { it.decodeToString().contains("link-ping") }
            ) {
                delay(20)
            }
            val sent = connection.sentSnapshot()
            assertTrue(
                sent.any { it.decodeToString().contains("link-ping") },
                "入站不停也必须保底发 Ping —— 否则对端会在 deadAfterMs 内收不到任何字节而判死（task-40）；" +
                    "实际发出 " + sent.size + " 帧：" +
                    sent.take(3).joinToString { it.decodeToString().take(60) },
            )
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
