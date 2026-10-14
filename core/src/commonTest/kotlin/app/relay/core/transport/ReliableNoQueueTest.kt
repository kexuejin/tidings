// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.Timings
import app.relay.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「可靠但不入队」这条通道的存在理由，钉成数据（2026-10-14，agent-workspace 首版踩的坑）。
 *
 * ## 它守的是什么
 *
 * `sendUserMessage` 失败时做两件事：`registry.markOffline` + 入离线补发队列。
 * 对**指令类**帧（msg / agent-session / grant）这是对的：丢了必须补投。
 * 对**状态快照类**帧这是错的 —— `agent-workspace` 声明"我这个工作区存在、此刻在线"，
 * 发给一台还不认识这帧的旧版手机时永远等不到 ACK，于是：
 *
 * 1. 一台**在线**设备被标成离线（症状：手机明明能收聊天，面板却显示离线）；
 * 2. 这条过期快照在队列里躺满重发窗口，几小时后突然补投给一台早就换了工作区的宿主。
 *
 * `sendReliableNoQueue` 两件事都不做：帧照发、对端认识就 ACK、不认识就算了 ——
 * 下一份快照自然盖掉它。这条测试钉的就是"不认识 ⇒ 既不离线、也不入队"。
 */
class ReliableNoQueueTest {

    private fun peerProfile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17999,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_MOBILE_TEXT),
    )

    @Test
    fun unackedStateFrameLeavesPeerOnlineAndQueueEmpty() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self-node")
        val queueStore = InMemoryQueueStore()
        val dedupStore = InMemoryDedupStore()
        // 快超时：没有真实对端在听，等不到 ACK 就是这条用例的前提。
        val fastTimings = Timings(ackRetrySchedule = listOf(30L, 30L), tcpAckTimeout = 60L)
        val messenger = Messenger(
            transport = transport,
            registry = registry,
            selfNodeId = "self-node",
            queueStore = queueStore,
            dedupStore = dedupStore,
            timings = fastTimings,
        )
        transport.start()
        messenger.start(scope)
        try {
            registry.touch(
                "old-phone", "127.0.0.1", 17998, peerProfile("old-phone"),
                profileTs = 1000L, confirmed = true,
            )
            assertTrue(registry.get("old-phone")!!.online, "前提：对端此刻在线")

            val env = makeEnvelope(
                MsgTypes.AGENT_WORKSPACE, "self-node",
                Codec.agentWorkspacePayload(wsId = "ws-1", wsTitle = "探针"),
            )
            // agent-workspace 必须在 RELIABLE 里 —— 不在的话对端根本不会把它投进 inbound，
            // 这条用例测的通道就没有意义（两个断言是一件事的两半，缺一半都会说谎）。
            kotlin.test.assertTrue(MsgTypes.AGENT_WORKSPACE in MsgTypes.RELIABLE)

            val acked = messenger.sendReliableNoQueue("old-phone", env)
            assertFalse(acked, "没人回 ACK ⇒ 如实报 false")
            assertTrue(registry.get("old-phone")!!.online, "失败**不得**把在线对端标成离线")
            assertEquals(0, queueStore.listByPeer("old-phone").size,
                "状态快照不得进离线补发队列（过期快照晚到比不到更坏）")

            // 对照组：同一台对端、同一份超时下，指令类帧走 sendUserMessage 必须入队并标离线
            // —— 没有这一半，上面的"没入队"可能只是因为整条链路根本没跑起来。
            val cmd = makeEnvelope(MsgTypes.AGENT_SESSION, "self-node", Codec.agentSessionPayload("s-1"))
            val outcome = messenger.sendUserMessage("old-phone", cmd)
            assertEquals(SendOutcome.QUEUED, outcome)
            assertFalse(registry.get("old-phone")!!.online, "指令帧失败 ⇒ 标离线是**对的**，别把这条也改了")
            assertEquals(1, queueStore.listByPeer("old-phone").size,
                "指令帧必须进补发队列（丢了要补投）")
        } finally {
            scope.cancel()
            transport.stop()
        }
    }
}
