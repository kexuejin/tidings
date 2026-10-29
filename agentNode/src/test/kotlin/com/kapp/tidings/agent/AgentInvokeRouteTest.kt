// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.session.NodeRuntime
import com.kapp.tidings.agent.testutil.Await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * `POST /v1/agent/invoke` 的**入口契约**（M4，规格 §6.2）。
 *
 * 职责对账裁定（追踪器 M4）：这条路由只做 HTTP 解包 + 如实的失败面；真正的派发
 * （P2P 网道、租约、看板卡）在装配了域层的那一侧（[AgentInvokeDispatcher]）。
 * 这里钉住四条入口语义：
 *  - 域层没装配（agentNode 单进程）⇒ 503，错误码如实 —— 不假装接了单；
 *  - 字段缺失 / deadlineMs 非正 ⇒ 400（没有期限的租约等于永远等，§7.3-1）；
 *  - 域层答 null（群里没有这个 agent）⇒ 404；
 *  - 委托是**逐字**转发：HTTP 层不添不删字段，invokeId 原样返回。
 *
 * 跨端派单的全回路（帧到对端、租约超时、零刷屏）在 :app 的 Layer 4 双节点仿真 ——
 * 这里是入口，不是网道。
 */
class AgentInvokeRouteTest {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private lateinit var scope: CoroutineScope
    private lateinit var agent: NodeRuntime
    private lateinit var plane: ControlPlane

    /** 记录最后一次委托收到的实参：入口「只解包、逐字转发」的证据。 */
    private var lastDispatch: List<String?>? = null

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        agent = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "Tidings Agent",
                host = "relay-agent",
                ver = "relay-test",
            ),
            udpPort = 0,
            bindHost = ControlPlane.LOOPBACK,
        )
        runBlocking { agent.start(scope) }
        plane = ControlPlane(agent, httpPort = 0, token = "", sessions = AgentSessionRegistry())
        plane.start()
    }

    @AfterTest
    fun tearDown() {
        plane.stop()
        runBlocking { agent.stop() }
        scope.cancel()
    }

    @Test
    fun invokeWithoutADomainLayerAnswers503InsteadOfPretending() {
        val res = post("/v1/agent/invoke", """{"groupId":"g-1","agentId":"gpu-runner","prompt":"run tests"}""")
        assertEquals(503, res.first, "域层没装配必须如实回 503：${res.second}")
        assertEquals("invoke-not-wired", obj(res.second)["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun invokeRequiresGroupIdAgentIdAndPrompt() {
        val res = post("/v1/agent/invoke", """{"groupId":"g-1","agentId":"gpu-runner"}""")
        assertEquals(400, res.first, "缺 prompt 的派单无从执行：${res.second}")
        assertEquals("bad-request", obj(res.second)["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun invokeRefusesANonPositiveDeadline() {
        // 「没有期限的租约等于永远等」—— 这不是入口的默认选择，是调用方的错误。
        val res = post(
            "/v1/agent/invoke",
            """{"groupId":"g-1","agentId":"a","prompt":"p","deadlineMs":0}""",
        )
        assertEquals(400, res.first, "deadlineMs=0 必须被拒：${res.second}")
        assertEquals("bad-request", obj(res.second)["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun invokeForwardsVerbatimAndReturnsTheDispatcherInvokeId() {
        val seen = mutableListOf<List<String?>>()
        plane.invokeDispatcher = { groupId, agentId, prompt, wsId, deadlineMs ->
            seen += listOf(groupId, agentId, prompt, wsId, deadlineMs.toString())
            "inv-from-domain"
        }
        val res = post(
            "/v1/agent/invoke",
            """{"groupId":"g-1","agentId":"gpu-runner","prompt":"run the suite","wsId":"ws-9","deadlineMs":45000}""",
        )
        assertEquals(200, res.first, "域层受理了派单：${res.second}")
        assertEquals("inv-from-domain", obj(res.second)["invokeId"]?.jsonPrimitive?.content)
        Await.condition("委托恰好一次且实参逐字一致") {
            seen == listOf(listOf("g-1", "gpu-runner", "run the suite", "ws-9", "45000"))
        }
    }

    @Test
    fun invokeWithoutOptionalFieldsUsesTheDefaults() {
        val seen = mutableListOf<List<String?>>()
        plane.invokeDispatcher = { groupId, agentId, prompt, wsId, deadlineMs ->
            seen += listOf(groupId, agentId, prompt, wsId, deadlineMs.toString())
            "inv-2"
        }
        val res = post("/v1/agent/invoke", """{"groupId":"g-2","agentId":"a2","prompt":"p2"}""")
        assertEquals(200, res.first, "缺省字段走入口默认：${res.second}")
        Await.condition("未指定的 wsId 传 null、期限用入口缺省") {
            seen == listOf(listOf("g-2", "a2", "p2", null, ControlPlane.DEFAULT_INVOKE_DEADLINE_MS.toString()))
        }
    }

    @Test
    fun invokeForAnUndeclaredAgentAnswers404() {
        plane.invokeDispatcher = { _, _, _, _, _ -> null }
        val res = post("/v1/agent/invoke", """{"groupId":"g-1","agentId":"ghost","prompt":"p"}""")
        assertEquals(404, res.first, "群里没有这个 agent 的声明：${res.second}")
        assertEquals("agent-not-found", obj(res.second)["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun invokeSurfacesADomainFailureAs500WithOneResponse() {
        plane.invokeDispatcher = { _, _, _, _, _ -> error("mesh is down") }
        val res = post("/v1/agent/invoke", """{"groupId":"g-1","agentId":"a","prompt":"p"}""")
        assertEquals(500, res.first, "域层抛错必须如实上抛：${res.second}")
        assertEquals("invoke-failed", obj(res.second)["error"]?.jsonPrimitive?.content)
        assertTrue(
            obj(res.second)["message"]?.jsonPrimitive?.content!!.contains("mesh is down"),
            "失败原因要能读：${res.second}",
        )
    }

    // ---- 小工具 ----

    private fun obj(body: String) = com.kapp.tidings.core.protocol.Codec.wireJson
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
