// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.core.session.NodeRuntime
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.kapp.tenon.agent.udpTestTransport

/**
 * `POST /v1/agent/channel` 的**发起门**判据（M7-T2，决策包 §1.6；反向验证钉子：
 * 把 caps 门从处理器里摘掉 ⇒ 409 用例红 —— 对端会收到一条它永远拒收的帧）。
 *
 *  - 对端没声明 `agc1` ⇒ **409**，帧不构造（发送侧不产出注定被拒的帧）；
 *  - op 互斥 / 恰一 / 租约的形状面由 `CodecChannel` 的向量负例在构造器上钉住，
 *    这里只钉「老端对端到不了投递」。
 */
class AgentChannelGateTest {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private lateinit var scope: CoroutineScope
    private lateinit var agent: NodeRuntime
    private lateinit var oldPeer: NodeRuntime
    private lateinit var plane: ControlPlane

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        agent = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = Profile.mobile(nodeId = newId(), nick = "Tenon Agent", host = "relay-agent", ver = "relay-test"),
            udpPort = 0,
            bindHost = ControlPlane.LOOPBACK,
        )
        // 不声明 agc1 的对端（= MOBILE_CAPS 摘掉 agc1 的老端形状）。
        val withoutAgc1 = MsgTypes.MOBILE_CAPS.filter { it != MsgTypes.CAP_AGENT_CHANNEL }
        oldPeer = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = Profile(
                nodeId = newId(), nick = "old-peer", company = "", dept = "", team = "",
                avatar = 0, profileRev = 0, host = "old-host", platform = "linux",
                tcpPort = 0, ver = "relay-test", caps = withoutAgc1,
            ),
            udpPort = 0,
            bindHost = ControlPlane.LOOPBACK,
        )
        runBlocking {
            agent.start(scope)
            oldPeer.start(scope)
        }
        // 握手：agent 必须先认识 oldPeer（resolveTargetNodeId 要查 registry）。
        runBlocking { oldPeer.announceTo(ControlPlane.LOOPBACK, agent.boundPort) }
        com.kapp.tenon.agent.testutil.Await.condition("agent 没学到 oldPeer 的资料") {
            agent.getPeer(oldPeer.nodeId)?.profile?.nodeId == oldPeer.nodeId
        }
        plane = ControlPlane(agent, httpPort = 0, token = "", sessions = AgentSessionRegistry(), loadProbe = { null })
        plane.start()
    }

    @AfterTest
    fun tearDown() {
        plane.stop()
        runBlocking {
            agent.stop()
            oldPeer.stop()
        }
        scope.cancel()
    }

    @Test
    fun channelToAPeerWithoutAgc1IsRefusedWith409AndNeverSent() {
        val body = """
            {"to":"OLDPEER","op":"assign","fromAgent":"caller",
             "toAgent":"worker","task":"t","deadlineMs":60000}
        """.trimIndent().replace("OLDPEER", oldPeer.nodeId)
        val res = post("/v1/agent/channel", body)
        assertEquals(409, res.first, "对端不声明 agc1 必须 409 拒发：" + res.second)
        assertEquals("peer-unsupported", obj(res.second)["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun aTransferViolatingItsShapeNeverReachesDelivery() {
        // transfer 禁 deadlineMs：构造期 require 在 caps 门之后 —— 老端对端先吃 409。
        // 这条钉「到不了投递」（无论哪道门拦下），400 面由向量负例负责。
        val body = """
            {"to":"OLDPEER","op":"transfer","fromAgent":"caller",
             "toAgent":"worker","task":"t","deadlineMs":60000}
        """.trimIndent().replace("OLDPEER", oldPeer.nodeId)
        val res = post("/v1/agent/channel", body)
        assertEquals(409, res.first, "老端对端：caps 门先于形状门，帧不出门")
    }

    private fun obj(body: String) = com.kapp.tenon.core.protocol.Codec.wireJson
        .parseToJsonElement(body).jsonObject

    private fun post(path: String, body: String): Pair<Int, String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://" + ControlPlane.LOOPBACK + ":" + plane.boundPort + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.statusCode() to resp.body()
    }
}
