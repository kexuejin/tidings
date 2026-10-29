// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.AgentSenderId
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgKinds
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.transport.SendOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 「群里 agent 说的话」这条路的**收发**端到端（两个真实 [NodeRuntime] 跑在回环上）。
 *
 * 为什么需要它：这条路的失败形态全是无声的，而且每一半都已经各有单测。
 * 发送侧有 `GroupTextCapabilityGatingTest`（按成员 `gta1` 门控、同一信封 id、队列变体），
 * 接收侧有 `SenderIdentityTest`（`agent:<host>:<kind>` 怎么显示成人名）。但两边**接起来**
 * 没有任何测试：把帧真的放到 UDP/TCP 上、让对端的 `inbound` 收到、再确认那个 `senderId`
 * 还认得出来 —— 这一段没有断言。而它恰好是本仓最贵的一类缺陷的所在地：
 * 发送侧 `SendOutcome.SENT`、本地落库成功、单测全绿，接收侧一条都没有。
 *
 * 所以这里的判据不是"编码器能产出什么"，而是**对端的 `inbound` 到底收到了什么**：
 *  1. agent 转发的群文本到达对端，正文/群引用/会话归属齐全；
 *  2. 那个 `senderId` 在对端**还能解析回 (宿主, agent)** —— 否则手机端会把 agent 的话
 *     显示成「桌面端发的」（真机缺陷原形）；
 *  3. 对端是**不认识 `gta1` 的老节点**时，帧照样到达（正文不丢），只是没有归属 ——
 *     降级是"少一个键"，不是"整帧消失"；
 *  4. 反方向（手机上用户自己发的群文本）同样到得了宿主，且 `senderId` 就是用户自己的 nodeId
 *     —— 用户说的话不能被算成 agent 说的。
 */
class GroupAgentMessageRoundTripTest {

    private val host = "host-node"
    private val phone = "phone-node"
    private val legacy = "legacy-node"
    private val groupId = "grp-agent-1"

    private fun profile(nodeId: String, caps: List<String>) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-test",
        caps = caps,
    )

    private suspend fun awaitTrue(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    private fun isGroupTextFrom(envelope: Envelope, from: String): Boolean {
        if (envelope.from != from) return false
        val payload = envelope.payload as? JsonObject ?: return false
        return payload["kind"]?.jsonPrimitive?.content == MsgKinds.GROUP_TEXT
    }

    /** 起两个节点并握手到「彼此都认识、且都知道对方声明了 gta1」。 */
    private suspend fun handshake(
        scope: CoroutineScope,
        sender: NodeRuntime,
        receiver: NodeRuntime,
        senderId: String,
        receiverId: String,
    ) {
        receiver.announceTo("127.0.0.1", sender.boundPort)
        awaitTrue("握手后 $senderId 仍不认识 $receiverId") { sender.getPeer(receiverId) != null }
        awaitTrue("$senderId 没学到 $receiverId 的资料") {
            sender.getPeer(receiverId)?.profile?.nodeId == receiverId
        }
    }

    private fun node(nodeId: String, caps: List<String>) = NodeRuntime(
        profile = profile(nodeId, caps),
        udpPort = 0,
        bindHost = "127.0.0.1",
        tcpPort = 0,
    )

    @Test
    fun agentAttributedGroupTextReachesThePeerAndKeepsItsSpeaker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val desktop = node(host, MsgTypes.AGENT_CAPS)
        val mobile = node(phone, MsgTypes.MOBILE_CAPS)
        desktop.start(scope)
        mobile.start(scope)
        try {
            handshake(scope, desktop, mobile, host, phone)
            awaitTrue("宿主没学到手机的 gta1") {
                desktop.getPeer(phone)?.profile?.caps?.contains(MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED) == true
            }

            val received = CompletableDeferred<Envelope>()
            val watch = scope.launch {
                mobile.inbound.collect { envelope ->
                    if (isGroupTextFrom(envelope, host)) received.complete(envelope)
                }
            }

            val outcome = desktop.sendGroupText(
                text = "agent 说的正文",
                groupId = groupId,
                groupRev = 4,
                members = listOf(host, phone),
                senderId = AgentSenderId.of(host, "dsh"),
                reasoning = "先看 A 再看 B",
                agentSessionId = "sess-1",
                agentCursor = 7L,
                agentSessionTitle = "上线任务",
            )
            assertEquals(SendOutcome.SENT, outcome, "群消息没送达：SENT 要求成员真的 ACK")
            val got = withTimeout(5_000) { received.await() }
            watch.cancel()

            assertEquals(host, got.from, "信封 from 永远是发送设备，不该被 senderId 顶掉")
            val payload = assertNotNull(got.payload as? JsonObject, "收到的不是对象载荷")
            assertEquals("agent 说的正文", payload["text"]?.jsonPrimitive?.content, "正文没到 ⇒ 群里少一条")
            assertEquals(groupId, payload["groupId"]?.jsonPrimitive?.content)
            assertEquals(4, payload["groupRev"]?.jsonPrimitive?.content?.toInt())

            val senderId = payload["senderId"]?.jsonPrimitive?.content
            assertEquals(
                AgentSenderId.of(host, "dsh"),
                senderId,
                "带归属的那一份才有 senderId；丢了它手机端会显示成「桌面端发的」",
            )
            assertEquals(
                host to "dsh",
                AgentSenderId.parse(senderId),
                "接收端必须还能把这条归给宿主上的 dsh，而不是宿主本人",
            )
            assertEquals("先看 A 再看 B", payload["reasoning"]?.jsonPrimitive?.content, "思考块没跟着进群")
            assertEquals("sess-1", payload["agentSessionId"]?.jsonPrimitive?.content, "缺它就没有任务分段线")
            assertEquals(7L, payload["agentCursor"]?.jsonPrimitive?.content?.toLong())
            assertEquals("上线任务", payload["agentSessionTitle"]?.jsonPrimitive?.content)
        } finally {
            mobile.stop()
            desktop.stop()
            scope.cancel()
        }
    }

    @Test
    fun aLegacyPeerStillGetsTheTextAndOnlyLosesTheSpeaker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val desktop = node(host, MsgTypes.AGENT_CAPS)
        val old = node(legacy, listOf(MsgTypes.CAP_MOBILE_TEXT))
        desktop.start(scope)
        old.start(scope)
        try {
            handshake(scope, desktop, old, host, legacy)
            awaitTrue("宿主把老节点误认成 gta1") {
                desktop.getPeer(legacy)?.profile?.caps?.contains(MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED) != true
            }

            val received = CompletableDeferred<Envelope>()
            val watch = scope.launch {
                old.inbound.collect { envelope ->
                    if (isGroupTextFrom(envelope, host)) received.complete(envelope)
                }
            }

            val outcome = desktop.sendGroupText(
                text = "老节点也要收到正文",
                groupId = groupId,
                groupRev = 2,
                members = listOf(host, legacy),
                senderId = AgentSenderId.of(host, "dsh"),
                reasoning = "先看 A",
            )
            assertEquals(SendOutcome.SENT, outcome)
            val got = withTimeout(5_000) { received.await() }
            watch.cancel()

            val payload = assertNotNull(got.payload as? JsonObject)
            assertEquals(
                "老节点也要收到正文",
                payload["text"]?.jsonPrimitive?.content,
                "老节点收不到正文 = 群里少一条，而降级只该少一个键",
            )
            assertNull(
                payload["senderId"],
                "对不认识 gta1 的成员必须裁剪：多一个键会被上游白名单整帧拒收",
            )
            assertNull(payload["reasoning"], "reasoning 同理")
        } finally {
            old.stop()
            desktop.stop()
            scope.cancel()
        }
    }

    @Test
    fun theOtherDirectionKeepsTheUserAsTheSpeaker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val desktop = node(host, MsgTypes.AGENT_CAPS)
        val mobile = node(phone, MsgTypes.MOBILE_CAPS)
        desktop.start(scope)
        mobile.start(scope)
        try {
            handshake(scope, desktop, mobile, host, phone)
            awaitTrue("手机没学到宿主的 gta1") {
                mobile.getPeer(host)?.profile?.caps?.contains(MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED) == true
            }

            val received = CompletableDeferred<Envelope>()
            val watch = scope.launch {
                desktop.inbound.collect { envelope ->
                    if (isGroupTextFrom(envelope, phone)) received.complete(envelope)
                }
            }

            // 手机端用户自己打的字：senderId 就是用户自己的 nodeId（见 sendGroupTextOptimistic）。
            val outcome = mobile.sendGroupText(
                text = "我自己的话",
                groupId = groupId,
                groupRev = 5,
                members = listOf(host, phone),
                senderId = phone,
            )
            assertEquals(SendOutcome.SENT, outcome)
            val got = withTimeout(5_000) { received.await() }
            watch.cancel()

            val payload = assertNotNull(got.payload as? JsonObject)
            assertEquals("我自己的话", payload["text"]?.jsonPrimitive?.content)
            assertEquals(
                phone,
                payload["senderId"]?.jsonPrimitive?.content,
                "用户说的话必须归给用户自己 —— 归成 agent 就是真机上那个「谁说的」错位",
            )
            assertNull(AgentSenderId.parse(payload["senderId"]?.jsonPrimitive?.content), "人的 nodeId 不是 agent")
        } finally {
            mobile.stop()
            desktop.stop()
            scope.cancel()
        }
    }
}
