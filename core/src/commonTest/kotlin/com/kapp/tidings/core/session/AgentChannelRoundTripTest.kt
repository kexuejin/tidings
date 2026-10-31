// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.transport.SendOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M7-T2 发送门与回路的**接线**判据（编解码在向量测试里）：
 *
 *  1. **caps 门**：对端没声明 `agc1` ⇒ 发送方必须**拒发**（不得 SENT）——RELIABLE 帧发进
 *     老端会被 ACK 后丢弃，队列反复重传一条永远到不了语义的帧（AGENTS §3 第 2 种
 *     「没收到」的时间形态）；
 *  2. **回路**：两端声明 `agc1` ⇒ `agent-channel` 到对端 inbound、`agent-channel-result`
 *     能回指发起方 —— 委派不是单程票。
 *
 * 两个真实 [NodeRuntime] 跑在回环上（GroupAgentMessageRoundTripTest 同一夹具形状）。
 */
class AgentChannelRoundTripTest {

    private val hostId = "host-node"
    private val peerId = "peer-node"

    private fun profile(nodeId: String, caps: List<String>) = Profile(
        nodeId = nodeId, nick = nodeId, company = "", dept = "", team = "",
        avatar = 0, profileRev = 0, host = "127.0.0.1", platform = "linux",
        tcpPort = 17879, ver = "relay-test", caps = caps,
    )

    private fun node(nodeId: String, caps: List<String>) = NodeRuntime(
        profile = profile(nodeId, caps), udpPort = 0, bindHost = "127.0.0.1", tcpPort = 0,
    )

    private suspend fun awaitTrue(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(20) }
    }

    /** 起两节点并握手到「两边都学到对方的资料与 caps」（result 回程需要双向路由）。 */
    private suspend fun handshake(scope: CoroutineScope, caller: NodeRuntime, callee: NodeRuntime) {
        callee.announceTo("127.0.0.1", caller.boundPort)
        awaitTrue("握手后 caller 仍不认识 callee") { caller.getPeer(peerId) != null }
        awaitTrue("caller 没学到 callee 的资料") {
            caller.getPeer(peerId)?.profile?.nodeId == peerId
        }
        awaitTrue("callee 没学到 caller 的资料") {
            callee.getPeer(hostId)?.profile?.nodeId == hostId
        }
    }

    @Test
    fun channelRoundTripDeliversAssignAndResultToEachSide() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val caller = node(hostId, MsgTypes.AGENT_CAPS)
        val callee = node(peerId, MsgTypes.AGENT_CAPS)
        caller.start(scope)
        callee.start(scope)
        try {
            handshake(scope, caller, callee)
            awaitTrue("caller 没学到 callee 的 agc1") {
                MsgTypes.CAP_AGENT_CHANNEL in caller.getPeer(peerId)!!.profile.caps
            }
            val assignArrived = CompletableDeferred<JsonObject>()
            val watch: Job = scope.launch {
                callee.inbound.collect {
                    if (it.type == MsgTypes.AGENT_CHANNEL) assignArrived.complete(it.payload as JsonObject)
                }
            }
            val channelId = newId()
            val outcome = caller.sendAgentChannel(
                peerId = peerId,
                channelId = channelId,
                op = "assign",
                fromAgent = "caller-agent",
                task = "do the thing",
                toAgent = "worker-agent",
                expectedResult = "it is done",
                resources = listOf("gpu"),
                deadlineMs = 60_000,
            )
            assertEquals(SendOutcome.SENT, outcome, "双端声明 agc1 ⇒ 帧出门并拿到 ACK")
            val payload = withTimeout(5_000) { assignArrived.await() }
            watch.cancel()
            assertEquals(channelId, payload["channelId"]?.jsonPrimitive?.content, "channelId 原样到达")
            assertEquals("assign", payload["op"]?.jsonPrimitive?.content)

            // result 回指：接收方把结果回给发起方，发起方在 inbound 里收到同一 channelId。
            val resultArrived = CompletableDeferred<JsonObject>()
            val watchBack: Job = scope.launch {
                caller.inbound.collect {
                    if (it.type == MsgTypes.AGENT_CHANNEL_RESULT) resultArrived.complete(it.payload as JsonObject)
                }
            }
            val resultOutcome = callee.sendAgentChannelResult(
                peerId = hostId,
                channelId = channelId,
                ok = true,
                status = "done",
                summary = "it is done",
            )
            assertEquals(SendOutcome.SENT, resultOutcome, "result 帧同样过 cap 门（对端声明了 agc1）")
            val back = withTimeout(5_000) { resultArrived.await() }
            watchBack.cancel()
            assertEquals(channelId, back["channelId"]?.jsonPrimitive?.content, "result 回指同一 channelId")
            assertEquals("done", back["status"]?.jsonPrimitive?.content)
        } finally {
            callee.stop()
            caller.stop()
            scope.cancel()
        }
    }
}
