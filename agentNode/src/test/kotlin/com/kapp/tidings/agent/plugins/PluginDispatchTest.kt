// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.plugins

import com.kapp.tidings.agent.AgentSessionRegistry
import com.kapp.tidings.agent.ControlPlane
import com.kapp.tidings.agent.testutil.Await
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.session.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 宿主侧派单路径（M2-T3）：`POST /v1/plugin/task` 是宿主把任务交给插件的入口
 * （Layer 3 的「模拟群内派发任务」走它；群聊桥接线后也走它）。
 *
 * 钉四件事：
 * 1. 派单 → 插件收到标准事件（task/execute 带 sessionId + prompt）；
 * 2. 插件回传的事件流**进台账**（卡片以 `card` 事件落 [AgentSessionRegistry]，这是
 *    「台账正确接收」的判据 —— 台账是本仓对宿主记录的唯一叫法）；
 * 3. 台账里会话的身份来自注册的 manifest（agentId = leader.agentId，agentKind =
 *    pluginId）—— 发送者标签的唯一来源仍是注册时算好的 AgentSenderId，派单路径
 *    不允许出现第二个拼接来源；
 * 4. 热拔后派单：回执如实报错（事件流里有 error 事件）、插件翻离线、控制面照常应答。
 */
class PluginDispatchTest {

    private lateinit var scope: CoroutineScope
    private lateinit var runtime: NodeRuntime
    private lateinit var plane: ControlPlane
    private lateinit var sessions: AgentSessionRegistry
    private val token = "test-plugin-token-0123456789abcdef"
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runtime = NodeRuntime(
            profile = Profile.mobile(nodeId = newId(), nick = "Plugin Host", host = "relay-host", ver = "relay-0.1.0"),
            udpPort = 0,
            bindHost = ControlPlane.LOOPBACK,
        )
        runBlocking { runtime.start(scope) }
        sessions = AgentSessionRegistry()
        plane = ControlPlane(
            runtime, httpPort = 0, token = "",
            sessions = sessions,
            pluginTokenGate = PluginTokenGate.fromExplicit(token),
            plugins = PluginRegistry(probeIntervalMs = 100, probeTimeoutMs = 1000),
        )
        plane.start()
    }

    @AfterTest
    fun tearDown() {
        plane.stop()
        runBlocking { runtime.stop() }
        scope.cancel()
    }

    @Test
    fun dispatchRunsTheTaskAndThePluginReceivesTheStandardEvent() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            assertEquals(200, plugin.registerWith(planeBaseUrl, token).first)
            val (code, body) = post(
                "/v1/plugin/task",
                "{\"pluginId\":\"echo-team\",\"prompt\":\"hello plugin\"}",
            )
            assertEquals(200, code, body)
            val execute = plugin.received.firstOrNull { it.path == "/task/execute" }
            assertNotNull(execute, "插件必须收到 task/execute")
            assertTrue(execute.body.contains("hello plugin"), execute.body)
            assertTrue(execute.body.contains("sessionId"), execute.body)
        }
    }

    @Test
    fun dispatchLandsTheCardAndTheSessionInTheLedger() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            assertEquals(200, plugin.registerWith(planeBaseUrl, token).first)
            val (code, body) = post(
                "/v1/plugin/task",
                "{\"pluginId\":\"echo-team\",\"prompt\":\"draw a card\"}",
            )
            assertEquals(200, code, body)
            val resp = json.parseToJsonElement(body).jsonObject
            val sessionId = resp["sessionId"]?.jsonPrimitive?.content
            assertNotNull(sessionId, "派单回执必须带 sessionId（台账寻址键）：" + body)

            // 台账接收：会话与事件都在 GET /v1/agent/sessions 的口径里。
            val ledger = getJson("/v1/agent/sessions?sessionId=" + sessionId).jsonObject
            assertEquals(1, ledger["total"]?.jsonPrimitive?.int, "会话要进台账：" + ledger)
            val sessionRow = (ledger["sessions"] as kotlinx.serialization.json.JsonArray).first().jsonObject
            assertEquals("echo-lead", sessionRow["agentId"]?.jsonPrimitive?.content, "身份来自注册的 manifest")
            assertEquals("echo-team", sessionRow["agentKind"]?.jsonPrimitive?.content)

            val events = ledger["events"]!!.jsonArray.map { it.jsonObject }
            assertTrue(events.any { it["eventType"]?.jsonPrimitive?.content == "card" }, "卡片必须以 card 事件进台账：" + events)
            val cardEvent = events.first { it["eventType"]?.jsonPrimitive?.content == "card" }
            val cardJson = cardEvent["body"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(cardJson.contains("card-echo-1"), "卡片 JSON 原样落账：" + cardJson)
            // 游标在同一会话内单调（台账按游标排序，重复游标会互相覆盖）。
            val cursors = events.map { it["cursor"]?.jsonPrimitive?.int ?: 0 }
            assertEquals(cursors.sorted(), cursors, "台账游标必须单调递增：" + cursors)
        }
    }

    @Test
    fun consecutiveDispatchesToTheSameSessionKeepCursorsMonotonic() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            assertEquals(200, plugin.registerWith(planeBaseUrl, token).first)
            val (code1, body1) = post("/v1/plugin/task", "{\"pluginId\":\"echo-team\",\"prompt\":\"first\"}")
            assertEquals(200, code1, body1)
            val sessionId = json.parseToJsonElement(body1).jsonObject["sessionId"]?.jsonPrimitive?.content!!
            val (code2, body2) = post(
                "/v1/plugin/task",
                "{\"pluginId\":\"echo-team\",\"prompt\":\"second\",\"sessionId\":\"" + sessionId + "\"}",
            )
            assertEquals(200, code2, body2)
            val ledger = getJson("/v1/agent/sessions?sessionId=" + sessionId).jsonObject
            val events = ledger["events"]!!.jsonArray.map { it.jsonObject }
            val cursors = events.map { it["cursor"]?.jsonPrimitive?.int ?: 0 }
            // 连续性判据必须能区分「接着走」与「重数后同游标覆盖」：
            // 接着走 ⇒ 两次派单 8 条事件、游标 1..8 各一次；重数 ⇒ 台账只剩 4 条。
            assertEquals(8, events.size, "两次派单的事件都必须在台账里（重数会让第二次覆盖第一次）：" + cursors)
            assertEquals((1..8).toList(), cursors, "游标必须跨派单单调递增：" + cursors)
        }
    }

    @Test
    fun dispatchToUnknownPluginIs404() {
        val (code, body) = post("/v1/plugin/task", "{\"pluginId\":\"ghost\",\"prompt\":\"hi\"}")
        assertEquals(404, code)
        assertTrue(body.contains("plugin-not-found"), "未知插件是 plugin-not-found，不是路由缺失：" + body)
    }

    @Test
    fun actionForwardsThroughTheDriver() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            assertEquals(200, plugin.registerWith(planeBaseUrl, token).first)
            val (code, body) = post(
                "/v1/plugin/action",
                "{\"pluginId\":\"echo-team\",\"sessionId\":\"echo-1\",\"actionId\":\"pause-task\",\"decidedBy\":\"alice\"}",
            )
            assertEquals(200, code, body)
            val action = plugin.received.firstOrNull { it.path == "/task/action" }
            assertNotNull(action, "插件必须收到 task/action")
            assertTrue(action.body.contains("pause-task") && action.body.contains("alice"), action.body)
        }
    }

    @Test
    fun dispatchToAHotUnpluggedPluginReportsHonestlyAndFlipsOffline() {
        val plugin = MockExternalPlugin()
        try {
            plugin.start()
            assertEquals(200, plugin.registerWith(planeBaseUrl, token).first)
            plugin.stop() // 热拔

            // 会话都开不出来（连接拒绝）⇒ 502 如实回，不编一个 sessionId 进台账。
            // （executeTask 中途断连的诚实路径 —— Error 事件 + 断连回调 —— 在驱动层
            // ExternalPluginDriverTest 钉住；路由层钉的是"开不出会话就 502"。）
            val (code, body) = post("/v1/plugin/task", "{\"pluginId\":\"echo-team\",\"prompt\":\"anyone?\"}")
            assertEquals(502, code, "热拔后派单如实报不可达：" + body)
            assertTrue(body.contains("plugin-unreachable"), body)
            // 断连回调已经把状态翻成离线（不等探测周期）。
            Await.condition("插件翻离线", timeoutMs = 8000) { readStatus() == "offline" }
            assertEquals(200, get("/healthz").first, "主程序不崩：控制面照常应答")
        } finally {
            plugin.stop()
        }
    }

    // ---------------------------------------------------------------- 夹具

    private val planeBaseUrl: String get() = "http://127.0.0.1:" + plane.boundPort

    private fun MockExternalPlugin.withAutoStop(block: (MockExternalPlugin) -> Unit) {
        try {
            block(this)
        } finally {
            stop()
        }
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val conn = URI(planeBaseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write(body.encodeToByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().decodeToString() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun get(path: String): Pair<Int, String> {
        val conn = URI(planeBaseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().decodeToString() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun getJson(path: String) = json.parseToJsonElement(get(path).second)

    private fun readStatus(): String? {
        val (code, body) = get("/v1/plugins")
        if (code != 200) return null
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val arr = obj["plugins"] as? kotlinx.serialization.json.JsonArray ?: return null
        return (arr.firstOrNull() as? JsonObject)?.get("status")?.jsonPrimitive?.content
    }
}
