// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin
import app.relay.core.nowMillis
import app.relay.core.protocol.LinkFrame
import app.relay.core.protocol.LinkFrames
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 链路心跳必须把节点维持在「在线」，**即使 UDP 一帧都收不到、也没有 Msg 流经链路**。
 *
 * 真机现场（诊断包 relay-diag-20261020-075417，transport=Wifi）：同一台对端
 * linkConnected=true / dialFailures=0 / closeCount=0，而 online=false。
 * 双方都以为对方离线 ⇒ 消息全部进排队 ⇒ 没有 Msg ⇒ setLinkedAddress 不再被调用
 * ⇒ 一直离线。用户体感「移动网能收、连上 WiFi 收不到、断开又能收」
 * （断 WiFi 走 NetworkWatch 换网分支：重拨 + 排空队列）。
 *
 * ## 为什么这样断言（而不是「先断言离线、再断言恢复」）
 *
 * 心跳是每 heartbeatMs 一轮。`sweep` 把节点扫成离线之后，**下一次心跳随时**会把它救回，
 * 所以「sweep 完立刻读 online 必须是 false」是一个**竞态断言** —— 单独跑能抢到、
 * 全量跑（机器更忙）就抢不到，实测两种结果都出现过。
 *
 * 改成断言**最终状态**：只要链路还在、心跳还在，一段时间之后节点必须在线。
 * 修前（心跳不碰 registry）它永远回不来 ⇒ 超时 ⇒ 红；修后 ⇒ 绿。
 */
class LinkPresenceFromHeartbeatTest {

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

    /** 只发心跳：首帧 Hello 过握手，之后**只** Ping，永远没有 Msg。 */
    private class HeartbeatOnlyConnection : LinkConnection {
        override val remoteHost: String = "192.168.2.140"
        override val remotePort: Int = 17878
        override val isSecure: Boolean = false
        override val peerPin: TlsPin? = null
        private var first = true

        override suspend fun send(frame: ByteArray) = Unit

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
    fun heartbeatKeepsThePeerOnlineWhenUdpNeverArrives() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", "192.168.2.140", 17878, profile("peer"), 1L, confirmed = true)

        val server = LinkServer(
            registry = registry,
            scope = scope,
            heartbeatMs = 40,
            deadAfterMs = 5_000,
        )
        try {
            scope.launch { server.handle(HeartbeatOnlyConnection()) }

            // 等建连完成，否则后面的 sweep 与建连的 setLinkedAddress 抢时序。
            val connDeadline = nowMillis() + 5_000
            while (nowMillis() < connDeadline && "peer" !in server.connectedPeers()) delay(10)
            assertTrue(
                "peer" in server.connectedPeers(),
                "前提不成立：链路没建起来，这条测试测不到东西",
            )

            // 模拟「UDP 再也没来过、也没有 Msg」：把 lastSeen 推到过去再扫一次。
            // 不断言扫完是否离线（那是竞态）—— 只看最终能不能回到在线。
            registry.sweep(-1)

            // 心跳还在 ⇒ 一段时间之后必须在线。修前它永远回不来。
            val deadline = nowMillis() + 3_000
            while (nowMillis() < deadline && registry.get("peer")?.online != true) delay(20)
            assertTrue(
                registry.get("peer")?.online == true,
                "链路心跳一直在 ⇒ 节点必须回到在线。否则两端互相判离线、消息全部进排队：" +
                    "这正是真机上「连着 WiFi 收不到、断开又能收」的机制" +
                    "（诊断包 linkConnected=true 而 online=false）。",
            )
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
