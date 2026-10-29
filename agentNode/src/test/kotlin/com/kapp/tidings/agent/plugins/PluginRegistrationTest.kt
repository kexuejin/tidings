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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 插件注册契约（方案 §2.2 / §2.3，M2-T2）。
 *
 * 三组钉条目，对应注册端点的三条硬规则：
 *
 * 1. **token 强制**：无 token、错 token 一律 401；宿主一个 token 都没配时整个端点
 *    关闸（fail-closed，方案："本机任意进程不能无 token 自我入驻"）。token 的两种
 *    来源（环境变量 / 0600 受控文件）都验。
 * 2. **清单校验**：agentId 必须能过 [AgentSenderId] 的 slug 字符集与长度预算
 *    （slug 化后 ≤ 21 字符，超限拒绝 —— 超限的 senderId 会被整帧拒收，而发送侧拿不到
 *    任何错误）；pluginId 必须是安全的目录名；leader 必填。
 * 3. **注册后生命周期**：注册即在线；进程被 kill（夹具关服务器）→ 探测翻离线；
 *    重注册（新端口）→ 覆盖旧条目并回在线；unregister 销户。
 */
class PluginRegistrationTest {

    private lateinit var scope: CoroutineScope
    private lateinit var runtime: NodeRuntime
    private lateinit var plane: ControlPlane
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
        plane = ControlPlane(
            runtime, httpPort = 0, token = "",
            sessions = AgentSessionRegistry(),
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

    // ------------------------------------------------------------ token 强制

    @Test
    fun registrationWithoutTokenIsRejected() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val (code, body) = plugin.registerWith(planeBaseUrl, token = null)
            assertEquals(401, code, "无 token 注册必须被拒：" + body)
            assertRegisteredCount(0)
        }
    }

    @Test
    fun registrationWithWrongTokenIsRejected() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val (code, body) = plugin.registerWith(planeBaseUrl, token = "wrong-token")
            assertEquals(401, code, "错 token 注册必须被拒：" + body)
            assertRegisteredCount(0)
        }
    }

    @Test
    fun registrationWithNoTokenConfiguredAnywhereIsFailClosed() {
        // 宿主侧一个 token 来源都没有（env 未配 + 文件不存在）⇒ 端点整体关闸，
        // 谁都注册不进来 —— 而不是"没配 token 就放行"。
        val tightPlane = ControlPlane(
            runtime, httpPort = 0, token = "",
            sessions = AgentSessionRegistry(),
            pluginTokenGate = PluginTokenGate.fromExplicit(null),
        )
        try {
            tightPlane.start()
            MockExternalPlugin().withAutoStop { plugin ->
                plugin.start()
                val (code, body) = plugin.registerWith("http://127.0.0.1:" + tightPlane.boundPort, token = token)
                assertEquals(503, code, "宿主没配 token 时必须整体关闸：" + body)
            }
        } finally {
            tightPlane.stop()
        }
    }

    @Test
    fun tokenCanBeSourcedFromTheControlledFile() {
        // 守护进程模式的 token 来源：宿主数据目录下的受控文件（0600）。
        // 环境变量不在测试里碰（读 env 的断言会撞 envDependentTestGate），文件来源在这里验。
        val dataDir = Files.createTempDirectory("relay-plugin-data-").toFile()
        val expectedFile = File(dataDir, "plugins" + File.separator + "plugin-token")
        val gate = PluginTokenGate.fromFile(expectedFile)
        assertEquals(null, gate.expectedToken(), "文件不存在 = 未配置")
        val created = PluginTokenGate.ensureTokenFile(dataDir)
        assertEquals(expectedFile.absolutePath, created.absolutePath, "受控文件固定落在 <数据目录>/plugins/plugin-token")
        val hex = created.readText().trim()
        assertEquals(64, hex.length, "自动生成的 token 是 64 位十六进制")
        runCatching {
            val perms = Files.getPosixFilePermissions(created.toPath())
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                perms,
                "受控文件必须 0600（非 POSIX 文件系统上自动跳过这条）",
            )
        }
        // 指向同一个文件的门闸读到了它：注册放行。
        val filePlane = ControlPlane(
            runtime, httpPort = 0, token = "",
            sessions = AgentSessionRegistry(),
            pluginTokenGate = PluginTokenGate.fromFile(created),
        )
        try {
            filePlane.start()
            MockExternalPlugin().withAutoStop { plugin ->
                plugin.start()
                val (code, _) = plugin.registerWith("http://127.0.0.1:" + filePlane.boundPort, token = hex)
                assertEquals(200, code, "文件来源的 token 必须能完成注册")
            }
        } finally {
            filePlane.stop()
        }
    }

    // ------------------------------------------------------------ 清单校验

    @Test
    fun registrationWithCorrectTokenRegistersAndLists() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val (code, body) = plugin.registerWith(planeBaseUrl, token = token)
            assertEquals(200, code, body)
            val resp = json.parseToJsonElement(body).jsonObject
            assertEquals("echo-team", resp["pluginId"]?.jsonPrimitive?.content)
            // 注册回执带完整的 agent senderId（slug 归一化 + host 前缀）——发送侧不变量的证据。
            val senderIds = resp["agentSenderIds"]?.toString().orEmpty()
            assertTrue(senderIds.contains("echo-lead"), "回执应含 leader 的 senderId：" + senderIds)
            assertRegisteredCount(1)

            val listed = getJson("/v1/plugins").jsonObject["plugins"].toString()
            assertTrue(listed.contains("echo-team"), "GET /v1/plugins 应列出已注册插件：" + listed)
            assertTrue(listed.contains("online"), "注册成功的插件状态应为 online：" + listed)
        }
    }

    @Test
    fun agentIdSlugLongerThanBudgetIsRejected() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val bad = MockExternalPlugin.Default.echoTeamManifest().let { m ->
                buildJsonObject {
                    m.forEach { (k, v) -> put(k, v) }
                    put("leader", buildJsonObject {
                        put("agentId", "a".repeat(22)) // 21 是预算上限（Limits.ID - agent: - uuid - :）
                        put("name", "too-long")
                        put("role", "Lead")
                    })
                }
            }
            val (code, body) = plugin.registerWith(planeBaseUrl, token = token, manifestOverride = bad)
            assertEquals(400, code, "超预算 agentId 必须拒绝注册：" + body)
            assertTrue(body.contains("21"), "拒绝理由应给出预算数字：" + body)
            assertRegisteredCount(0)
        }
    }

    @Test
    fun agentIdThatCannotBeSlugifiedIsRejected() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val bad = MockExternalPlugin.Default.echoTeamManifest().let { m ->
                buildJsonObject {
                    m.forEach { (k, v) -> put(k, v) }
                    put("leader", buildJsonObject {
                        put("agentId", "纯中文名字没有ASCII")
                        put("name", "no-slug")
                        put("role", "Lead")
                    })
                }
            }
            val (code, body) = plugin.registerWith(planeBaseUrl, token = token, manifestOverride = bad)
            assertEquals(400, code, "slug 化后为空的 agentId 必须拒绝（AgentSenderIdShapeTest 同一契约）：" + body)
            assertRegisteredCount(0)
        }
    }

    @Test
    fun unsafePluginIdIsRejected() {
        for (bad in listOf("../evil", "Upper", "has space", "a/b")) {
            MockExternalPlugin().withAutoStop { plugin ->
                plugin.start()
                val badManifest = MockExternalPlugin.Default.echoTeamManifest().let { m ->
                    buildJsonObject {
                        m.forEach { (k, v) -> put(k, v) }
                        put("pluginId", bad)
                    }
                }
                val (code, body) = plugin.registerWith(planeBaseUrl, token = token, manifestOverride = badManifest)
                assertEquals(400, code, "pluginId「" + bad + "」必须拒绝（它同时是数据目录名）：" + body)
            }
        }
        assertRegisteredCount(0)
    }

    @Test
    fun missingLeaderIsRejected() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val bad = MockExternalPlugin.Default.echoTeamManifest().let { m ->
                buildJsonObject {
                    m.forEach { (k, v) -> if (k != "leader") put(k, v) }
                }
            }
            val (code, body) = plugin.registerWith(planeBaseUrl, token = token, manifestOverride = bad)
            assertEquals(400, code, "没有 leader 的插件无法被 @ 呼叫，必须拒绝：" + body)
        }
    }

    @Test
    fun missingHttpPortIsRejected() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val body = buildJsonObject {
                put("manifest", MockExternalPlugin.Default.echoTeamManifest())
                // 不带 httpPort：宿主不知道往哪转发 IPC
            }.toString()
            val (code, resp) = post("/v1/plugin/register", body, token)
            assertEquals(400, code, resp)
        }
    }

    @Test
    fun outOfRangeHttpPortIsRejected() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val body = buildJsonObject {
                put("manifest", MockExternalPlugin.Default.echoTeamManifest())
                put("httpPort", 70000)
            }.toString()
            val (code, resp) = post("/v1/plugin/register", body, token)
            assertEquals(400, code, resp)
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Test
    fun pluginGoingDownFlipsStatusOfflineAndTheHostStaysAlive() {
        lateinit var plugin: MockExternalPlugin
        try {
            plugin = MockExternalPlugin()
            plugin.start()
            val (code, body) = plugin.registerWith(planeBaseUrl, token = token)
            assertEquals(200, code, body)
            assertStatus("online")

            // 热拔：插件进程没了（服务器关闭）。探测循环必须在预算内把它翻成离线，
            // 而主程序（控制面）继续正常应答。
            plugin.stop()
            Await.condition("插件离线（探测翻状态）", timeoutMs = 8000) { readStatus() == "offline" }
            val (healthCode, _) = get("/healthz")
            assertEquals(200, healthCode, "插件热拔后控制面必须照常应答")
        } finally {
            plugin.stop()
        }
    }

    @Test
    fun reRegistrationReplacesTheEntryAndComesBackOnline() {
        val first = MockExternalPlugin()
        first.start()
        val (code1, body1) = first.registerWith(planeBaseUrl, token = token)
        assertEquals(200, code1, body1)
        first.stop()
        Await.condition("第一个实例离线", timeoutMs = 8000) { readStatus() == "offline" }

        // 同 pluginId 在新端口重注册 = 覆盖旧条目（升级 / 重启的自然路径），
        // 而不是长出第二个 echo-team。
        val second = MockExternalPlugin()
        try {
            second.start()
            val (code2, body2) = second.registerWith(planeBaseUrl, token = token)
            assertEquals(200, code2, body2)
            assertRegisteredCount(1)
            assertStatus("online")
            assertTrue(
                second.boundPort != first.boundPort,
                "夹具两个实例应落在不同端口（端口 0 各自分配）",
            )
        } finally {
            second.stop()
        }
    }

    @Test
    fun unregisterRemovesTheEntry() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            val (code, body) = plugin.registerWith(planeBaseUrl, token = token)
            assertEquals(200, code, body)
            val (unregCode, unregBody) = post(
                "/v1/plugin/unregister",
                "{\"pluginId\":\"echo-team\"}",
                token,
            )
            assertEquals(200, unregCode, unregBody)
            assertRegisteredCount(0)
        }
    }

    @Test
    fun unregisterIsAlsoTokenGated() {
        MockExternalPlugin().withAutoStop { plugin ->
            plugin.start()
            plugin.registerWith(planeBaseUrl, token = token)
            val (code, _) = post("/v1/plugin/unregister", "{\"pluginId\":\"echo-team\"}", token = null)
            assertEquals(401, code, "无 token 的注销同样必须被拒")
            assertRegisteredCount(1)
        }
    }

    // ------------------------------------------------------------ 夹具与助手

    private val planeBaseUrl: String get() = "http://127.0.0.1:" + plane.boundPort

    private fun MockExternalPlugin.withAutoStop(block: (MockExternalPlugin) -> Unit) {
        try {
            block(this)
        } finally {
            stop()
        }
    }

    private fun post(path: String, body: String, token: String?): Pair<Int, String> {
        val conn = URI(planeBaseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
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

    private fun assertStatus(expected: String) {
        assertEquals(expected, readStatus(), "GET /v1/plugins 的插件状态")
    }

    private fun assertRegisteredCount(expected: Int) {
        val obj = getJson("/v1/plugins").jsonObject
        val total = obj["total"]?.jsonPrimitive?.int
        assertEquals(expected, total, "注册表条数（GET /v1/plugins）")
    }
}
