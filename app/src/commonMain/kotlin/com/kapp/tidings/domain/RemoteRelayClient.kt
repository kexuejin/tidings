// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.platform.SimpleHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 远程中继客户端（与 agentNode 控制面 HTTP API 对齐）。
 */
class RemoteRelayClient(
    val config: RemoteRelayConfig,
    private val httpClient: SimpleHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val baseUrl: String = config.url.trim().removeSuffix("/")
    val token: String = config.token.trim()

    private fun authHeaders(): Map<String, String> =
        if (token.isNotEmpty()) mapOf("Authorization" to "Bearer $token") else emptyMap()

    suspend fun checkHealth(): Boolean = runCatching {
        val resp = httpClient.get("$baseUrl/healthz", authHeaders())
        resp.statusCode in 200..299
    }.getOrDefault(false)

    suspend fun fetchInbox(since: Long, limit: Int = 50): List<RelayInboxItem> = runCatching {
        val resp = httpClient.get("$baseUrl/v1/inbox?since=$since&limit=$limit", authHeaders())
        if (resp.statusCode !in 200..299) return emptyList()
        val parsed = json.parseToJsonElement(resp.body).jsonObject
        val messages = parsed["messages"]?.jsonArray ?: return emptyList()
        messages.mapNotNull { element ->
            val obj = element.jsonObject
            val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val peerId = obj["peerId"]?.jsonPrimitive?.content ?: ""
            val direction = obj["direction"]?.jsonPrimitive?.content ?: "in"
            val at = obj["at"]?.jsonPrimitive?.longOrNull ?: 0L
            val envelopeObj = obj["envelope"]?.jsonObject
            RelayInboxItem(seq, peerId, direction, at, envelopeObj)
        }
    }.getOrDefault(emptyList())

    suspend fun sendMessage(to: String, text: String): Boolean = runCatching {
        val body = """{"to":"$to","text":${json.encodeToString(text)}}"""
        val resp = httpClient.postJson("$baseUrl/v1/send", body, authHeaders())
        resp.statusCode in 200..299
    }.getOrDefault(false)
}

data class RelayInboxItem(
    val seq: Long,
    val peerId: String,
    val direction: String,
    val at: Long,
    val envelope: JsonObject?,
)
