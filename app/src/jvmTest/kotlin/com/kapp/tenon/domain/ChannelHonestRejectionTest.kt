// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.platform.MdnsAdvertiser
import com.kapp.tenon.platform.MdnsBrowser
import com.kapp.tenon.platform.NetworkMonitor
import com.kapp.tenon.platform.NetworkSnapshot
import com.kapp.tenon.platform.Transport
import com.kapp.tenon.testutil.awaitUntil
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * M7-T2 手机侧的**诚实拒绝**（D-017：MOBILE_CAPS 声明 agc1 的语义兑现）：
 * 手机被误指名收到一条 `agent-channel` ⇒ 回一帧 `agent-channel-result`
 * `status=no-executor`，而不是沉默到发起方租约超时。
 *
 * 为什么必须是**线上**回路而不是把 handleOnAgentChannel 单调一遍：这条测试钉的是
 * 「帧真的过线了」（AGENTS §4 的教训——`recordInbound(makeEnvelope(...))` 会把
 * "帧根本到不了"的缺陷测成绿）。夹具照 RelayControllerAgentSessionTest：
 * manualPeers 直连真端口、双向握手，对端从自己的 `inbound` 收回帧。
 */
class ChannelHonestRejectionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var agentRuntime: NodeRuntime
    private lateinit var controller: RelayController
    private val received = CopyOnWriteArrayList<JsonObject>()

    private val clientNodeId = "11111111-0000-4000-8000-000000000001"
    private val agentNodeId = "22222222-0000-4000-8000-000000000002"

    @BeforeTest
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 别家的宿主（声明 agc1）：它发委派过来，等手机的回帧。
        agentRuntime = NodeRuntime(
            profile = Profile.agent(
                nodeId = agentNodeId, nick = "别家的宿主", host = "test-agent-host",
                ver = "relay-0.1.0", platform = "mac",
            ),
            udpPort = 0, bindHost = "127.0.0.1",
        )
        agentRuntime.start(scope)
        scope.launch {
            agentRuntime.inbound.collect {
                if (it.type == MsgTypes.AGENT_CHANNEL_RESULT) received += it.payload as JsonObject
            }
        }
        // 手机（本端）：没有 channelOrchestrator 桥 ⇒ 必须诚实拒绝。
        controller = RelayController(
            platform = InMemoryAppPlatform(
                networkMonitor = StaticNetworkMonitor,
                mdnsAdvertiser = NoopAdvertiser,
                mdnsBrowser = NoopBrowser,
            ),
            store = InMemorySettingsStore(
                RelaySettings(
                    nodeId = clientNodeId,
                    nick = "我的手机",
                    udpPort = 0,
                    tcpPort = 0,
                    manualPeers = listOf(ManualPeerEntry("127.0.0.1", agentRuntime.boundPort)),
                ),
            ),
            scope = scope,
            version = "relay-test",
        )
        controller.start()
        awaitUntil("客户端与 Agent 完成双向握手") {
            val peer = peer(agentNodeId, controller.state.value.toNodeSlice(), controller.state.value.toSettingsSlice())
            peer != null && peer.online && agentRuntime.getPeer(clientNodeId)?.online == true
        }
    }

    @AfterTest
    fun tearDown() = runBlocking {
        controller.stop()
        agentRuntime.stop()
        scope.cancel()
    }

    @Test
    fun aMisaddressedChannelFrameGetsAnHonestNoExecutorBack() = runBlocking {
        assertTrue(
            MsgTypes.CAP_AGENT_CHANNEL in MsgTypes.MOBILE_CAPS,
            "前置：手机声明 agc1（D-017）；不声明这条帧根本到不了",
        )
        fun assignEnvelope(): Envelope = makeEnvelope(
            MsgTypes.AGENT_CHANNEL,
            agentNodeId,
            Codec.wireJson.parseToJsonElement(
                "{\"channelId\":\"ch-1\",\"op\":\"assign\",\"fromAgent\":\"caller-agent\",\"toAgent\":\"worker-agent\",\"task\":\"do things\",\"deadlineMs\":60000}",
            ),
        )
        // 回执是唯一判据（确定事件）。委派帧本身按**重新委派**的节奏重投（新信封 id、
        // 同一 channelId，与真实宿主在租约超时后的重派同形），回执一到立即停 —— 既有
        // 单发路径被回执 ACK 之前的一次接收侧交接丢失时，下一次重投仍能把判据带回来。
        var anySendFailed = false
        val redrive = launch {
            while (received.isEmpty()) {
                if (!agentRuntime.sendReliableNoQueue(clientNodeId, assignEnvelope())) {
                    anySendFailed = true
                }
                delay(REDRIVE_PACE_MS)
            }
        }
        awaitUntil("手机对误指名委派回 no-executor 回执") { received.isNotEmpty() }
        redrive.cancel()
        assertFalse(anySendFailed, "每次委派重投都应拿到传输层 ACK")
        val back: JsonObject = received.first()
        assertEquals("ch-1", back["channelId"]?.jsonPrimitive?.content, "回执回指同一 channelId")
        assertEquals("no-executor", back["status"]?.jsonPrimitive?.content, "手机没有执行器 ⇒ 如实说 no-executor")
        assertEquals("false", back["ok"]?.jsonPrimitive?.content)
    }

    private companion object {
        /** 重投节奏：与可靠发送的首档 ACK 退避同量级；只影响重试密度，不是判定依据。 */
        const val REDRIVE_PACE_MS = 1_000L
    }
}

private object StaticNetworkMonitor : NetworkMonitor {
    override fun rawSnapshot(): NetworkSnapshot = NetworkSnapshot(
        online = true,
        transport = Transport.Wifi,
        localAddresses = listOf("127.0.0.1"),
        vpnActive = false,
    )
    override fun observe(onChange: (NetworkSnapshot) -> Unit): () -> Unit = {}
}

private object NoopAdvertiser : MdnsAdvertiser {
    override suspend fun start(serviceName: String, port: Int): Boolean = true
    override fun stop() = Unit
}

private object NoopBrowser : MdnsBrowser {
    override fun startBrowsing(
        onServiceFound: (String, String, Int) -> Unit,
        onServiceLost: (String) -> Unit,
    ) = Unit
    override fun stopBrowsing() = Unit
}
