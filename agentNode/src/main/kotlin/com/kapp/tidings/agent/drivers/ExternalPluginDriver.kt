// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import com.kapp.tidings.agent.plugins.PluginManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * 主程序内置**唯一的**插件驱动（方案 §2.4）：它不包含任何业务代码，只把
 * [AgentDriver] 的会话生命周期成员转发给进程外插件的回环 HTTP IPC。
 *
 * ## 与插件之间的契约（插件一侧的实现见 examples/plugins 与本仓夹具）
 *
 * | AgentDriver 成员 | 插件侧路由 | 语义 |
 * |---|---|---|
 * | [openSession] | `POST /session/open` | 开/续会话，返回 [SessionHandle] |
 * | [executeTask] | `POST /task/execute` | **流式** NDJSON 事件行（思考/正文/工具/卡片/完成/错误） |
 * | [handleAction] | `POST /task/action` | 卡片操作回传（审批防重在宿主，[decidedBy] 原样透传） |
 * | [queryProgress] | `POST /task/queryProgress` | 点查进度快照；插件回 `null` = 没有可报告的进度 |
 * | （探测） | `GET /healthz` | 宿主台账的低频健康探测 |
 *
 * ## 能力声明不经插件进程
 *
 * [listCapabilities] 直接读 manifest 的 `capabilities` 声明（注册时已进宿主台账），
 * **不向插件进程发请求**；能力 ACL 也收在宿主（[DriverRegistry]，见 T1 收编）——
 * 插件驱动从头到尾不会把 ACL 调用转发出去。
 *
 * ## 热拔的两种说法（都不撒谎、都不崩）
 *
 * - **executeTask** 有事件通道：断连以 [DriverEvent.Error] 如实上报并触发
 *   [onConnectionLost]（宿主据此把插件翻成离线），**绝不抛给调用方** —— 派单路径
 *   的调用方是群聊桥，抛异常等于把「插件没了」变成「主程序炸了」。
 * - **其余三个成员**没有事件通道：抛 [IOException]（控制面转 502），同样先触发
 *   [onConnectionLost]。null 的 [queryProgress] 返回值只属于活着的插件
 *   （「没有可报告的进度」），不可达时绝不冒充它。
 *
 * agent 的发送者标签唯一来源仍是 `AgentSenderId.of`（宿主在注册时算好并随台账保存，
 * 本类不拼任何 `agent:` 前缀 —— 拼接是 SenderIdentity 纪律明令禁止的第二来源）。
 */
class ExternalPluginDriver(
    /** = manifest 的 pluginId（宿主台账的键）。 */
    override val driverId: String,
    /** = manifest 的 name。 */
    override val displayName: String,
    /** 插件自己的回环 IPC 基座（`http://127.0.0.1:<httpPort>`，注册时登记）。 */
    private val baseUrl: String,
    /** 注册时校验过的清单；能力声明读它，不再向插件要。 */
    private val manifest: PluginManifest,
    /** 断连回调（宿主传 `plugins::markOffline`）：任何 IPC 失败都会触发一次。 */
    private val onConnectionLost: ((String) -> Unit)? = null,
) : AgentDriver {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listCapabilities(): List<DriverCapability> =
        manifest.capabilities.map { cap ->
            DriverCapability(
                id = cap,
                name = cap,
                description = "declared by plugin manifest (" + manifest.pluginId + " v" + manifest.version + ")",
                category = "plugin",
                enabled = true,
            )
        }

    override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("wsPath", wsPath)
                if (resumeSessionId != null) put("resumeSessionId", resumeSessionId)
            }
            val element = try {
                postForJson("/session/open", body.toString())
            } catch (e: IOException) {
                onConnectionLost?.invoke(driverId)
                throw e
            }
            SessionHandle(
                sessionId = element.jsonObject["sessionId"]?.jsonPrimitive?.content
                    ?: throw IOException("plugin session/open reply has no sessionId"),
                wsPath = wsPath,
                resumed = !resumeSessionId.isNullOrEmpty(),
                createdAt = System.currentTimeMillis(),
            )
        }

    override suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    ) {
        try {
            withContext(Dispatchers.IO) {
                val body = buildJsonObject {
                    put("sessionId", sessionId)
                    put("prompt", prompt)
                }
                val conn = openConnection("/task/execute")
                try {
                    writeBody(conn, body.toString())
                    if (conn.responseCode !in 200..299) {
                        val detail = readError(conn)
                        throw IOException("plugin task/execute failed (" + conn.responseCode + "): " + detail)
                    }
                    conn.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            onEvent(parseEventLine(line))
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: IOException) {
            // 流式成员的诚实路径：经由事件通道说「这次没做成」，绝不抛给派单方。
            onConnectionLost?.invoke(driverId)
            onEvent(DriverEvent.Error("plugin unreachable or failed: " + (e.message ?: e::class.simpleName)))
        }
    }

    override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("sessionId", sessionId)
                put("actionId", actionId)
                put("decidedBy", decidedBy)
            }
            try {
                postForJson("/task/action", body.toString())
            } catch (e: IOException) {
                onConnectionLost?.invoke(driverId)
                throw e
            }
            Unit
        }

    override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("sessionId", sessionId) }
            val raw = try {
                postForRaw("/task/queryProgress", body.toString())
            } catch (e: IOException) {
                onConnectionLost?.invoke(driverId)
                throw e
            }
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "null") return@withContext null
            val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return@withContext null
            TaskProgressSnapshot(
                sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: sessionId,
                state = obj["state"]?.jsonPrimitive?.content ?: "unknown",
                stage = obj["stage"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content,
                progressPercent = obj["progressPercent"]?.jsonPrimitive?.intOrNull,
                updatedAt = System.currentTimeMillis(),
            )
        }

    // ---------------------------------------------------------------- 事件行解析

    /**
     * 一行 NDJSON → [DriverEvent]。插件 speaks 插件方言，宿主只做**翻译**不做解释：
     * 认识的映射到总线事件，不认识的以 [DriverEvent.Error] 原样上报（诊断要么如实、
     * 要么不存在 —— 静默丢弃一行会让「插件说了什么」永远查不到）。
     */
    internal fun parseEventLine(line: String): DriverEvent {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return DriverEvent.Error("plugin event is not a JSON object: " + line.take(200))
        val type = obj["type"]?.jsonPrimitive?.content
        return when (type) {
            "thought" -> DriverEvent.ThoughtDelta(deltaOf(obj))
            "token" -> DriverEvent.TokenDelta(deltaOf(obj))
            "tool" -> DriverEvent.ToolCall(
                toolCallId = obj["toolCallId"]?.jsonPrimitive?.content ?: "call-" + System.nanoTime(),
                tool = obj["tool"]?.jsonPrimitive?.content ?: "unknown",
                inputJson = obj["inputJson"]?.jsonPrimitive?.content ?: "{}",
                riskLevel = obj["riskLevel"]?.jsonPrimitive?.content ?: "L2",
            )
            "card" -> {
                val card = obj["card"] as? JsonObject
                    ?: return DriverEvent.Error("plugin card event has no card object: " + line.take(200))
                DriverEvent.Card(cardJson = card.toString())
            }
            "finished" -> DriverEvent.Finished(
                finishReason = obj["finishReason"]?.jsonPrimitive?.content ?: "stop",
                totalTokens = obj["totalTokens"]?.jsonPrimitive?.intOrNull ?: 0,
            )
            "error" -> DriverEvent.Error(obj["message"]?.jsonPrimitive?.content ?: "plugin reported an error")
            else -> DriverEvent.Error("unknown plugin event type \"" + (type ?: "<missing>") + "\": " + line.take(200))
        }
    }

    private fun deltaOf(obj: JsonObject): String =
        obj["delta"]?.jsonPrimitive?.content ?: ""

    // ---------------------------------------------------------------- HTTP 底座

    private fun openConnection(path: String): HttpURLConnection {
        val conn = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = IPC_TIMEOUT_MS
        conn.readTimeout = IPC_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: String) {
        conn.outputStream.use { it.write(body.encodeToByteArray()) }
    }

    private fun readError(conn: HttpURLConnection): String =
        runCatching { conn.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull()
            ?.take(200) ?: ""

    /** 非 2xx 抛 [IOException]；2xx 返回解析后的 JSON。 */
    private fun postForJson(path: String, body: String): JsonObject {
        val raw = postForRaw(path, body)
        return runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IOException("plugin reply is not a JSON object: " + raw.take(200)) }
    }

    private fun postForRaw(path: String, body: String): String {
        val conn = openConnection(path)
        try {
            writeBody(conn, body)
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("plugin " + path + " failed (" + code + "): " + readError(conn))
            }
            return conn.inputStream.use { it.readAllBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /**
         * 回环 IPC 的等待上限：这是**观测预算**不是断言（AGENTS §2.7-6 的纪律）。
         * 插件与宿主同机，正常路径毫秒级；上限给宽到任务执行的自然长度
         * （task/execute 是流式的，头部到达即开始消费，本超时只影响无响应的连接）。
         */
        const val IPC_TIMEOUT_MS: Int = 30_000
    }
}
