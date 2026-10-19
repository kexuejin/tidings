// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.makeEnvelope
import app.relay.core.session.NodeRuntime
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
import kotlin.test.assertTrue

/**
 * K1 端到端（两个真实 NodeRuntime 跑在回环上）：手机建长连接、主机经它下行文本。
 *
 * 这条测试同时钉住验收里的两条：
 *  · 连接建立后，主机 registry 里该 peer 的地址变成**连接的对端地址**；
 *  · 主机下行的一条文本走长连接到达手机（不是走 UDP/TCP 回退）。
 */
class LinkK1Test {

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
        ver = "relay-0.1.0",
        caps = caps,
    )

    private suspend fun awaitTrue(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    @Test
    fun thePhoneOpensALinkAndTheHostDownlinksTextOverIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = NodeRuntime(
            profile = profile("host-node", listOf(MsgTypes.CAP_LINK, MsgTypes.CAP_MOBILE_TEXT)),
            udpPort = 0,
            bindHost = "127.0.0.1",
            tcpPort = 0,
        )
        val phone = NodeRuntime(
            profile = profile("phone-node", emptyList()),
            udpPort = 0,
            bindHost = "127.0.0.1",
            tcpPort = 0,
        )
        // 开关默认关；这条测试显式打开（回滚那条路径另测）。
        phone.linkEnabled = true
        host.start(scope)
        phone.start(scope)
        try {
            // 先握手，让两边互相认识（host 的 caps 里带 lnk1）。
            phone.announceTo("127.0.0.1", host.boundPort)
            awaitTrue("主机没认识到手机") { host.getPeer("phone-node") != null }
            awaitTrue("手机没认识到主机（带 lnk1）") {
                phone.getPeer("host-node")?.profile?.caps?.contains(MsgTypes.CAP_LINK) == true
            }

            awaitTrue("手机侧长连接没建起来") { phone.linkClient?.isConnected("host-node") == true }
            awaitTrue("主机侧没接管这条长连接") { host.linkServer?.isConnected("phone-node") == true }

            // 验收：连接建立后，主机 registry 里该 peer 的地址 = 连接的对端地址。
            assertEquals("127.0.0.1", host.getPeer("phone-node")!!.ip)
            assertTrue(host.getPeer("phone-node")!!.online)

            // 主机下行一条文本：长连接优先 → 手机侧收到。
            val received = CompletableDeferred<Envelope>()
            val watch = scope.launch {
                phone.inbound.collect { envelope ->
                    if (envelope.from == "host-node") received.complete(envelope)
                }
            }
            val outgoing = makeEnvelope(
                MsgTypes.MSG,
                "host-node",
                Codec.textPayload("走长连接的下行"),
            )
            assertEquals(SendOutcome.SENT, host.messenger.sendUserMessage("phone-node", outgoing))
            val got = withTimeout(5_000) { received.await() }
            val text = ((got.payload as? JsonObject)?.get("text"))?.jsonPrimitive?.content
            assertEquals("走长连接的下行", text)
            watch.cancel()
        } finally {
            phone.stop()
            host.stop()
            scope.cancel()
        }
    }

    @Test
    fun turningTheSwitchOffClosesTheLinkAndFallsBackToTheOldPath() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = NodeRuntime(profile = profile("host-2", listOf(MsgTypes.CAP_LINK)), udpPort = 0, bindHost = "127.0.0.1", tcpPort = 0)
        val phone = NodeRuntime(profile = profile("phone-2", emptyList()), udpPort = 0, bindHost = "127.0.0.1", tcpPort = 0)
        phone.linkEnabled = true
        host.start(scope)
        phone.start(scope)
        try {
            phone.announceTo("127.0.0.1", host.boundPort)
            awaitTrue("长连接没建起来") { phone.linkClient?.isConnected("host-2") == true }

            // 关掉开关 = 回滚开关：连接立刻断开，之后走今天的 UDP/TCP。
            phone.linkEnabled = false
            awaitTrue("关掉开关后长连接必须断开") { phone.linkClient?.isConnected("host-2") != true }
        } finally {
            phone.stop()
            host.stop()
            scope.cancel()
        }
    }
}
