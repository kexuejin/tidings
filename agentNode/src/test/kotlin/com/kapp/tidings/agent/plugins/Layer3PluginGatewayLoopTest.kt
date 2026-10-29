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
 * **Layer 3 · 插件宿主网关回环**（方案 §8）：整条生命周期在**一个进程内**走通 ——
 *
 *   注册（带 token，像真插件一样自报家门）→ 派单（模拟群内派发任务）→
 *   插件收到标准事件 → 插件回发卡片 → 本地台账正确接收 → 热拔 → 重启回在线。
 *
 * 对端是 [MockExternalPlugin]：进程内回环 HTTP 服务器，走与真插件**完全相同**的
 * 注册/事件契约。**不 spawn node/python** —— 那会让测试结论依赖这台机器装没装，
 * 直接撞 envDependentTestGate；进程级语义（子进程崩溃、僵尸回收）用第二个 JVM 验，
 * 不在本层（本仓的 echo 插件另在桌面冒烟里以真进程验过一次，证据进 runtime-evidence）。
 *
 * 分步断言分散在 PluginRegistrationTest（token/清单）与 PluginDispatchTest
 * （派单/台账游标）；本类是**全回路验收物** —— 每一步都在同一条链上发生，
 * 任何一步的接线断了（而不是行为错了）只有全链路能看出来。
 */
class Layer3PluginGatewayLoopTest {

    private lateinit var scope: CoroutineScope
    private lateinit var runtime: NodeRuntime
    private lateinit var plane: ControlPlane
    private lateinit var sessions: AgentSessionRegistry
    private val token = "layer3-plugin-token-0123456789abcdef"
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runtime = NodeRuntime(
            profile = Profile.mobile(nodeId = newId(), nick = "Layer3 Host", host = "relay-host", ver = "relay-0.1.0"),
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
    fun theWholeLoopRegistersDispatchesStreamsCardsAndRecordsTheLedger() {
        val plugin = MockExternalPlugin()
        try {
            plugin.start()

            // ① 注册：像真插件一样带 token 自报家门（清单 + 自己的 IPC 端口）。
            val (regCode, regBody) = plugin.registerWith(planeBaseUrl, token)
            assertEquals(200, regCode, regBody)
            val agentSenderIds = json.parseToJsonElement(regBody).jsonObject["agentSenderIds"]
            assertNotNull(agentSenderIds, "注册回执带完整 senderId（发送侧不变量的证据）：" + regBody)

            // ② 派单（模拟群内派发任务）。
            val (dispatchCode, dispatchBody) = post(
                "/v1/plugin/task",
                "{\"pluginId\":\"echo-team\",\"prompt\":\"build the thing\"}",
            )
            assertEquals(200, dispatchCode, dispatchBody)
            val dispatch = json.parseToJsonElement(dispatchBody).jsonObject
            val sessionId = dispatch["sessionId"]?.jsonPrimitive?.content
            assertNotNull(sessionId)

            // ③ 插件收到的是标准事件（sessionId + prompt 都在）。
            val execute = plugin.received.firstOrNull { it.path == "/task/execute" }
            assertNotNull(execute, "插件必须收到 task/execute")
            assertTrue(execute.body.contains(sessionId) && execute.body.contains("build the thing"), execute.body)

            // ④ 插件回发了卡片，⑤ 台账正确接收（会话 + card 事件 + 单调游标）。
            assertTrue(
                dispatch["events"]!!.jsonArray.any { it.jsonObject["eventType"]?.jsonPrimitive?.content == "card" },
                "派单回执的事件流里有卡片：" + dispatch,
            )
            val ledger = getJson("/v1/agent/sessions?sessionId=" + sessionId).jsonObject
            assertEquals(1, ledger["total"]?.jsonPrimitive?.int, "会话进台账：" + ledger)
            val events = ledger["events"]!!.jsonArray.map { it.jsonObject }
            val cardRow = events.firstOrNull { it["eventType"]?.jsonPrimitive?.content == "card" }
            assertNotNull(cardRow, "卡片以 card 事件进台账：" + events)
            assertTrue(
                (cardRow["body"]?.jsonPrimitive?.content ?: "").contains("card-echo-1"),
                "卡片 JSON 原样落账",
            )
            val cursors = events.map { it["cursor"]?.jsonPrimitive?.int ?: 0 }
            assertEquals((1..cursors.size).toList(), cursors, "台账游标从 1 单调递增：" + cursors)

            // 会话身份来自注册的 manifest（agentId = leader，agentKind = pluginId）。
            val sessionRow = (ledger["sessions"] as kotlinx.serialization.json.JsonArray).first().jsonObject
            assertEquals("echo-lead", sessionRow["agentId"]?.jsonPrimitive?.content)
            assertEquals("echo-team", sessionRow["agentKind"]?.jsonPrimitive?.content)
        } finally {
            plugin.stop()
        }
    }

    @Test
    fun hotUnplugSensesDisconnectionFlipsOfflineAndTheHostSurvivesThenRestartComesBackOnline() {
        val plugin = MockExternalPlugin()
        try {
            plugin.start()
            assertEquals(200, plugin.registerWith(planeBaseUrl, token).first)
            awaitStatus("online", "注册后在线")

            // 热拔：插件进程没了（服务器关闭）。驱动感知（断连回调）+ 探测感知
            // （healthz 探不到）两条路都通向同一个状态翻转。
            plugin.stop()
            awaitStatus("offline", "热拔后翻离线")

            // 主程序绝不崩溃：控制面所有面都还活着。
            assertEquals(200, get("/healthz").first)
            assertEquals(200, get("/v1/plugins").first)
            assertEquals(502, post("/v1/plugin/task", "{\"pluginId\":\"echo-team\",\"prompt\":\"anyone?\"}").first)

            // 重启（同 pluginId 重注册）= 覆盖条目、状态回在线 —— 全生命周期热插拔。
            val restarted = MockExternalPlugin()
            try {
                restarted.start()
                assertEquals(200, restarted.registerWith(planeBaseUrl, token).first)
                awaitStatus("online", "重启后回在线")
                assertEquals(1, registeredCount(), "重注册是替换不是新增：" + pluginListBody())
            } finally {
                restarted.stop()
            }
            awaitStatus("offline", "再次热拔后翻离线（宿主依旧不崩）")
            assertEquals(200, get("/healthz").first)
        } finally {
            plugin.stop()
        }
    }

    @Test
    fun aPluginWithoutTheTokenNeverJoinsTheLoop() {
        // 环路的第一环（注册）没 token 就接不上 —— 后面所有环节无从发生。
        // 负路径的完整矩阵在 PluginRegistrationTest；这里钉的是回路的入口闭锁。
        val plugin = MockExternalPlugin()
        try {
            plugin.start()
            val (code, _) = plugin.registerWith(planeBaseUrl, token = null)
            assertEquals(401, code)
            assertEquals(0, registeredCount(), "无 token 的插件不得进入回路")
        } finally {
            plugin.stop()
        }
    }

    // ---------------------------------------------------------------- 夹具

    private val planeBaseUrl: String get() = "http://127.0.0.1:" + plane.boundPort

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

    private fun registeredCount(): Int =
        getJson("/v1/plugins").jsonObject["total"]?.jsonPrimitive?.int ?: -1

    private fun pluginListBody(): String = get("/v1/plugins").second

    private fun awaitStatus(expected: String, what: String) {
        Await.condition(what + "（GET /v1/plugins 状态翻 " + expected + "）", timeoutMs = 10000) {
            val (_, body) = get("/v1/plugins")
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@condition false
            val arr = obj["plugins"] as? kotlinx.serialization.json.JsonArray ?: return@condition false
            (arr.firstOrNull() as? JsonObject)?.get("status")?.jsonPrimitive?.content == expected
        }
    }
}
