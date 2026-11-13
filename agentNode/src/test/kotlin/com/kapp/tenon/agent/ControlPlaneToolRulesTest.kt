// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.agent.testutil.Await
import com.kapp.tenon.agent.udpTestTransport
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.core.session.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 免批规则读撤（本端增补 cap `atr1`）的进程内端到端测试：真的两个 [NodeRuntime]、
 * 真的 UDP 握手、真的走 [ControlPlane.observeInboundToolRules] 那条就地应答 ——
 * 与 [ControlPlaneTest] 同一套判法（mock 掉这层就只剩「我调用了我的函数」）。
 *
 * 守的三件事：
 * 1. 读请求回**全量快照**（行带 nodeId/tool/agentKey —— 撤销身份的原料）；
 * 2. 撤请求按身份四元组逐字摘行，回**更新后的**快照（对账只有这一份来源）；
 * 3. 身份对不上 = 一行不摘、快照原样回（不猜不造 —— total 不变就是「没撤掉」）。
 */
class ControlPlaneToolRulesTest {

    private lateinit var scope: CoroutineScope
    private lateinit var agent: NodeRuntime
    private lateinit var desktop: NodeRuntime
    private lateinit var plane: ControlPlane
    private lateinit var toolStore: AgentToolApprovalStore
    private val toolFile: File = File.createTempFile("relay-tool-rules-test-", ".json").apply { delete() }
    private val desktopFrames = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** 借控制面的 /v1/send 握手：desktop 的节点表里有 agent 才发得出请求帧（见 grant 测试同注释）。 */
    private fun handshake() {
        val res = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:" + plane.boundPort + "/v1/send"))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"to\":\"" + ControlPlane.LOOPBACK + "\",\"port\":" + desktop.boundPort + ",\"text\":\"握手\"}"),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode(), res.body())
    }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        desktop = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = Profile.mobile(nodeId = newId(), nick = "桌面端", host = "MacBook-Pro", ver = "0.61.1"),
            udpPort = 0,
            bindHost = ControlPlane.LOOPBACK,
        )
        agent = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = Profile.mobile(nodeId = newId(), nick = "Tenon Agent", host = "relay-agent", ver = "relay-0.1.0"),
            udpPort = 0,
            bindHost = ControlPlane.LOOPBACK,
        )
        runBlocking {
            desktop.start(scope)
            agent.start(scope)
        }
        desktopFrames.clear()
        toolStore = AgentToolApprovalStore(File(toolFile.absolutePath + "." + System.nanoTime()))
        scope.launch {
            desktop.inbound.collect { env ->
                desktopFrames += Codec.wireJson.parseToJsonElement(Codec.encode(env)).jsonObject
            }
        }
        plane = ControlPlane(
            agent, httpPort = 0, token = "", loadProbe = { null },
            sessions = AgentSessionRegistry(),
            toolApprovals = toolStore,
        )
        plane.start()
        plane.observeInbound(scope)
    }

    @AfterTest
    fun tearDown() {
        desktopFrames.clear()
        plane.stop()
        runBlocking {
            agent.stop()
            desktop.stop()
        }
        scope.cancel()
    }

    @Test
    fun readRequestAnswersWithTheFullSnapshot() {
        toolStore.remember(
            nodeId = desktop.nodeId,
            agentKey = "dsh-main",
            tool = "bash",
            grantedBy = agent.nodeId,
            at = 1_000L,
        )
        // 握手是这条链路的前提（与 grant 那条测试同一条注释）：desktop 的节点表里
        // 没有 agent 时帧只入队，请求永远到不了。
        handshake()
        runBlocking { desktop.sendAgentToolRulesRead(agent.nodeId, at = 2_000L) }

        val lists = Await.value("桌面收到 " + MsgTypes.AGENT_TOOL_RULES_LIST) {
            desktopFrames.filter { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_TOOL_RULES_LIST }
                .takeIf { it.isNotEmpty() }
        }
        val payload = lists.last()["payload"]!!.jsonObject
        assertEquals(1, payload["total"]!!.jsonPrimitive.int, "快照应是全量（这条种下的 1 行）")
        val row = payload["rules"]!!.jsonArray.single().jsonObject
        assertEquals(desktop.nodeId, row["nodeId"]?.jsonPrimitive?.content, "行带规则归属的设备（撤销身份的一半）")
        assertEquals("bash", row["tool"]?.jsonPrimitive?.content)
        assertEquals("dsh-main", row["agentKey"]?.jsonPrimitive?.content)
        assertEquals(agent.nodeId, row["grantedBy"]?.jsonPrimitive?.content, "谁批的进线上键（排查来源的唯一线索）")
    }

    @Test
    fun revokeRequestRemovesTheExactRuleAndAnswersWithTheFreshSnapshot() {
        toolStore.remember(nodeId = desktop.nodeId, agentKey = "dsh-main", tool = "bash", grantedBy = agent.nodeId, at = 1_000L)
        toolStore.remember(nodeId = desktop.nodeId, agentKey = null, tool = "web_search", grantedBy = agent.nodeId, at = 1_100L)
        handshake()
        runBlocking {
            desktop.sendAgentToolRulesRevoke(
                peerId = agent.nodeId,
                nodeId = desktop.nodeId,
                tool = "bash",
                agentKey = "dsh-main",
                at = 2_000L,
            )
        }
        val lists = Await.value("撤销后桌面收到新快照") {
            desktopFrames.filter { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_TOOL_RULES_LIST }
                .takeIf { it.isNotEmpty() }
        }
        val payload = lists.last()["payload"]!!.jsonObject
        assertEquals(1, payload["total"]!!.jsonPrimitive.int, "只摘身份匹配的那一行")
        val row = payload["rules"]!!.jsonArray.single().jsonObject
        assertEquals("web_search", row["tool"]?.jsonPrimitive?.content, "另一条规则原样保留")
        assertTrue(
            toolStore.snapshot().none { it.tool == "bash" },
            "宿主表里那条真的被摘掉 —— 回执快照不是摆设，是对账来源",
        )
    }

    @Test
    fun revokeWithMismatchedIdentityLeavesTheRuleAlone() {
        toolStore.remember(nodeId = desktop.nodeId, agentKey = "dsh-main", tool = "bash", grantedBy = agent.nodeId, at = 1_000L)
        handshake()
        // agentKey 对不上：宿主的 revokeExactly 一行不摘，快照原样回（total 不变 = 没撤掉）。
        runBlocking {
            desktop.sendAgentToolRulesRevoke(
                peerId = agent.nodeId,
                nodeId = desktop.nodeId,
                tool = "bash",
                agentKey = "dsh-other",
                at = 2_000L,
            )
        }
        val lists = Await.value("桌面收到快照回执") {
            desktopFrames.filter { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_TOOL_RULES_LIST }
                .takeIf { it.isNotEmpty() }
        }
        assertEquals(1, lists.last()["payload"]!!.jsonObject["total"]!!.jsonPrimitive.int, "身份对不上 = 一行不摘")
        assertEquals(
            1,
            toolStore.snapshot().size,
            "错账只出现在设备侧一次刷新里 —— 宿主表纹丝不动",
        )
    }
}
