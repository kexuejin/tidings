// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.plugins

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Layer 3 夹具：**进程内**模拟一个进程外插件（方案 §8 Layer 3）。
 *
 * ## 为什么不 spawn node / python
 *
 * 真插件是独立进程，但那会让测试结论依赖"这台机器装没装 node/python"—— 直接撞
 * `envDependentTestGate`。这里用 JDK 自带的 HttpServer 在回环端口上起**同一个 HTTP
 * 契约**（healthz / session/open / task/execute 流式 NDJSON / task/action /
 * task/queryProgress），并像真插件一样主动 `POST /v1/plugin/register`。契约一致，
 * 进程模型不同 —— 进程级语义（子进程崩溃、僵尸回收）另用第二个 JVM 验，不在本夹具。
 *
 * ## 它站在插件一侧
 *
 * 控制面是本夹具的**客户端**（派单、操作、点查）；本夹具是控制面的**对端**：
 * 记录收到的每个请求，按脚本（[executeScript] 等可注入字段）回事件流。默认脚本是
 * 回声（echo）：收到什么 prompt，就回思考增量 + 正文增量 + 一张卡片 + 完成。
 */
class MockExternalPlugin(
    /** 注册时上报的清单（与真插件的 manifest.json 同形，方案 §2.2）。 */
    val manifest: JsonObject = Default.echoTeamManifest(),
) {

    /** 插件侧收到的请求（路径 + JSON body），测试据此断言"插件收到了标准事件"。 */
    class Received(val path: String, val body: String)

    val received = CopyOnWriteArrayList<Received>()

    /** task/execute 的应答脚本：入参 = (sessionId, prompt)，出参 = NDJSON 事件行列表。 */
    var executeScript: (sessionId: String, prompt: String) -> List<String> = { _, prompt ->
        Default.echoScript(prompt)
    }

    /** task/execute 的 HTTP 状态码（默认 200；模拟插件内部错误时改它）。 */
    var executeStatus: Int = 200

    /** queryProgress 的应答（null = 回 `null`，即"没有可报告的进度"）。 */
    var queryProgressReply: String? = null

    private var server: HttpServer? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** 实际监听端口（[start] 之后有效）。 */
    var boundPort: Int = 0
        private set

    /** 启动插件侧 HTTP 服务器（回环，端口 0 = 系统分配）。 */
    fun start() {
        check(server == null) { "mock plugin already started" }
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
        s.createContext("/") { exchange -> route(exchange) }
        s.executor = Executors.newFixedThreadPool(2)
        s.start()
        server = s
        boundPort = s.address.port
    }

    /** 关闭服务器 —— 模拟插件进程被 kill / 崩溃（热拔场景）。 */
    fun stop() {
        server?.stop(0)
        server = null
    }

    val baseUrl: String get() = "http://127.0.0.1:$boundPort"

    /**
     * 像真插件一样向控制面注册自己：带 token 头，body = {manifest, httpPort}。
     * 返回 (HTTP status, response body)。
     */
    fun registerWith(controlPlaneBaseUrl: String, token: String?, manifestOverride: JsonObject? = null): Pair<Int, String> {
        val body = buildJsonObject {
            put("manifest", manifestOverride ?: manifest)
            put("httpPort", boundPort)
        }.toString()
        val conn = URI(controlPlaneBaseUrl + "/v1/plugin/register").toURL().openConnection()
            as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (!token.isNullOrEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.outputStream.use { it.write(body.encodeToByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().decodeToString() } ?: ""
        conn.disconnect()
        return code to text
    }

    // ------------------------------------------------------------------ routing

    private fun route(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.trimEnd('/')
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            received += Received(path, body)
            val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            when {
                path == "/healthz" -> reply(exchange, 200, "{\"ok\":true}")
                path == "/session/open" -> reply(exchange, 200, sessionOpenReply(obj))
                path == "/task/execute" -> replyStreaming(exchange, executeStatus, executeScript(sessionIdOf(obj), promptOf(obj)))
                path == "/task/action" -> reply(exchange, 200, "{\"ok\":true}")
                path == "/task/queryProgress" -> reply(exchange, 200, queryProgressReply ?: "null")
                else -> reply(exchange, 404, "{\"error\":\"not-found\"}")
            }
        } catch (t: Throwable) {
            runCatching { reply(exchange, 500, "{\"error\":\"internal\"}") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    /** 会话开启的应答：接续时按 resumeSessionId 复用 id（契约行为与真插件一致）。 */
    private fun sessionOpenReply(obj: JsonObject): String {
        val resumed = obj["resumeSessionId"]?.let { it !is kotlinx.serialization.json.JsonNull } ?: false
        val base = if (resumed) "echo-resumed-session" else "echo-" + System.nanoTime()
        return buildJsonObject {
            put("sessionId", base)
            put("resumed", resumed)
            put("createdAt", System.currentTimeMillis())
        }.toString()
    }

    private fun sessionIdOf(obj: JsonObject): String =
        (obj["sessionId"] as? JsonPrimitive)?.content ?: ""

    private fun promptOf(obj: JsonObject): String =
        (obj["prompt"] as? JsonPrimitive)?.content ?: ""

    private fun reply(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** NDJSON 流式应答：每行一个事件，逐行刷给客户端（宿主驱动逐行解析成 DriverEvent）。 */
    private fun replyStreaming(exchange: HttpExchange, code: Int, lines: List<String>) {
        exchange.responseHeaders.add("Content-Type", "application/x-ndjson; charset=utf-8")
        exchange.sendResponseHeaders(code, 0) // chunked：流式
        exchange.responseBody.use { out ->
            lines.forEach { line ->
                out.write((line + "\n").encodeToByteArray())
                out.flush()
            }
        }
    }

    /** 夹具共享的默认值（清单与回声脚本 —— 与 examples 的 echo 插件逐键同形）。 */
    object Default {
        fun echoTeamManifest(): JsonObject = buildJsonObject {
            put("pluginId", "echo-team")
            put("name", "Echo Team")
            put("version", "1.0.0")
            put("type", "team")
            put("entrypoint", "python3 main.py")
            put("leader", buildJsonObject {
                put("agentId", "echo-lead")
                put("name", "echo-lead")
                put("role", "Team Orchestrator")
            })
            put("members", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("agentId", "echo-coder")
                    put("name", "echo-coder")
                    put("role", "Engineer")
                })
                add(buildJsonObject {
                    put("agentId", "echo-tester")
                    put("name", "echo-tester")
                    put("role", "QA")
                })
            })
            put("allowedWorkspaces", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("wsId", "demo-ws")
                    put("title", "Demo Workspace")
                })
            })
            put("defaultWsId", "demo-ws")
            put("capabilities", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("task-dag"))
                add(kotlinx.serialization.json.JsonPrimitive("progress-stream"))
                add(kotlinx.serialization.json.JsonPrimitive("card"))
            })
        }

        /** 默认回声脚本：思考 + 正文 + 卡片 + 完成 —— 一条流里全齐，Layer 3 各断言共用。 */
        fun echoScript(prompt: String): List<String> = listOf(
            eventLine("thought", buildJsonObject { put("delta", "echo thinking about: " + prompt.take(20)) }),
            eventLine("token", buildJsonObject { put("delta", "echo: " + prompt) }),
            eventLine(
                "card",
                buildJsonObject {
                    put(
                        "card",
                        buildJsonObject {
                            put("type", "team-task-board")
                            put("version", 1)
                            put("cardId", "card-echo-1")
                            put("rev", 1)
                            put("title", "Echo task")
                            put("progress", 100)
                        },
                    )
                },
            ),
            eventLine("finished", buildJsonObject { put("finishReason", "stop"); put("totalTokens", 0) }),
        )

        /** 事件行 = {"type":"<kind>", ...事件字段}；字段由调用方给（夹具只定信封）。 */
        fun eventLine(type: String, fields: JsonObject): String =
            buildJsonObject {
                put("type", type)
                fields.forEach { (k, v) -> put(k, v) }
            }.toString()
    }
}
