// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.ControlPlane
import com.kapp.tenon.agent.source.StandardSourceEvent
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface WebhookResult {
    data class Accepted(val event: StandardSourceEvent) : WebhookResult
    data class Rejected(val statusCode: Int, val errorCode: String, val message: String) : WebhookResult
}

object SourceWebhookVerifier {

    private const val HMAC_SHA256_ALGORITHM = "HmacSHA256"

    /**
     * Timing-safe verification of HMAC-SHA256 signature using MessageDigest.isEqual.
     */
    fun verifyHmacSha256(rawBytes: ByteArray, secret: String, signatureHeaderValue: String?): Boolean {
        if (signatureHeaderValue.isNullOrBlank() || secret.isBlank()) return false

        val cleanHeader = signatureHeaderValue.trim()
        val expectedHex = if (cleanHeader.startsWith("sha256=", ignoreCase = true)) {
            cleanHeader.substringAfter("=").trim().lowercase()
        } else {
            cleanHeader.lowercase()
        }

        return try {
            val mac = Mac.getInstance(HMAC_SHA256_ALGORITHM)
            val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_SHA256_ALGORITHM)
            mac.init(secretKey)
            val computedBytes = mac.doFinal(rawBytes)
            val computedHex = computedBytes.joinToString("") { "%02x".format(it) }

            MessageDigest.isEqual(
                computedHex.toByteArray(Charsets.UTF_8),
                expectedHex.toByteArray(Charsets.UTF_8),
            )
        } catch (_: Throwable) {
            false
        }
    }
}

object SourceEventNormalizer {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun parseJsonObject(rawBody: ByteArray): JsonObject? {
        return runCatching {
            jsonParser.parseToJsonElement(rawBody.toString(Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    fun normalizeGitHub(
        deliveryId: String?,
        eventHeader: String?,
        payload: JsonObject,
    ): StandardSourceEvent {
        val eventType = eventHeader?.trim()?.ifBlank { null } ?: "github.unknown"
        val id = deliveryId?.trim()?.ifBlank { null } ?: ("gh-" + UUID.randomUUID().toString())

        val repoName = payload["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.contentOrNull.orEmpty()

        return when {
            eventType.startsWith("issue", ignoreCase = true) -> {
                val issue = payload["issue"]?.jsonObject
                val number = issue?.get("number")?.jsonPrimitive?.contentOrNull.orEmpty()
                val title = issue?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
                val body = issue?.get("body")?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = issue?.get("html_url")?.jsonPrimitive?.contentOrNull

                val subject = if (repoName.isNotEmpty() && number.isNotEmpty()) "$repoName#$number" else repoName.ifEmpty { "github-issue" }
                StandardSourceEvent.create(
                    id = id,
                    type = "issues." + (payload["action"]?.jsonPrimitive?.contentOrNull ?: "event"),
                    subject = subject,
                    summary = title.ifBlank { "GitHub issue update" },
                    detail = body,
                    link = url,
                )
            }

            eventType.startsWith("pull_request", ignoreCase = true) -> {
                val pr = payload["pull_request"]?.jsonObject
                val number = pr?.get("number")?.jsonPrimitive?.contentOrNull.orEmpty()
                val title = pr?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
                val body = pr?.get("body")?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = pr?.get("html_url")?.jsonPrimitive?.contentOrNull

                val subject = if (repoName.isNotEmpty() && number.isNotEmpty()) "$repoName#$number" else repoName.ifEmpty { "github-pr" }
                StandardSourceEvent.create(
                    id = id,
                    type = "pull_request." + (payload["action"]?.jsonPrimitive?.contentOrNull ?: "event"),
                    subject = subject,
                    summary = title.ifBlank { "GitHub PR update" },
                    detail = body,
                    link = url,
                )
            }

            else -> {
                val action = payload["action"]?.jsonPrimitive?.contentOrNull
                val type = if (action != null) "$eventType.$action" else eventType
                val subject = repoName.ifEmpty { "github" }
                val summary = "GitHub event: $type"
                StandardSourceEvent.create(
                    id = id,
                    type = type,
                    subject = subject,
                    summary = summary,
                    detail = payload.toString().take(StandardSourceEvent.MAX_DETAIL_CHARS),
                )
            }
        }
    }

    fun normalizeGeneric(
        fallbackId: String,
        payload: JsonObject,
    ): StandardSourceEvent {
        val id = payload["id"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: fallbackId
        val type = payload["type"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: "webhook.event"
        val subject = payload["subject"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: "external"
        val summary = payload["summary"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            ?: payload["title"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            ?: "External webhook notification"
        val detail = payload["detail"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: payload["text"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: payload["description"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: payload.toString()
        val link = payload["link"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: payload["url"]?.jsonPrimitive?.contentOrNull?.trim()

        return StandardSourceEvent.create(
            id = id,
            type = type,
            subject = subject,
            summary = summary,
            detail = detail,
            link = link,
        )
    }
}

class SourceWebhookRegistry {

    private val secrets = ConcurrentHashMap<String, String>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(StandardSourceEvent) -> Unit>()

    fun registerSecret(sourceId: String, secret: String) {
        val key = sourceId.trim().lowercase()
        secrets[key] = secret.trim()
    }

    fun getSecret(sourceId: String): String? {
        val key = sourceId.trim().lowercase()
        return secrets[key] ?: System.getenv("TENON_WEBHOOK_SECRET_${key.replace('-', '_').uppercase()}")
    }

    fun addListener(listener: (StandardSourceEvent) -> Unit) {
        listeners.add(listener)
    }

    fun handleWebhook(
        sourceId: String,
        headers: Map<String, List<String>>,
        rawBody: ByteArray,
    ): WebhookResult {
        val secret = getSecret(sourceId)
        if (secret != null) {
            // Find signature header: x-hub-signature-256 or x-signature-256
            val signatureHeader = headers.entries.firstOrNull {
                it.key.equals("x-hub-signature-256", ignoreCase = true) ||
                    it.key.equals("x-signature-256", ignoreCase = true) ||
                    it.key.equals("x-webhook-signature", ignoreCase = true)
            }?.value?.firstOrNull()

            if (signatureHeader.isNullOrBlank()) {
                return WebhookResult.Rejected(401, "missing-signature", "Signature header missing")
            }

            val valid = SourceWebhookVerifier.verifyHmacSha256(rawBody, secret, signatureHeader)
            if (!valid) {
                return WebhookResult.Rejected(401, "invalid-signature", "HMAC signature verification failed")
            }
        }

        val jsonObject = SourceEventNormalizer.parseJsonObject(rawBody)
            ?: return WebhookResult.Rejected(400, "bad-json", "Request body is not valid JSON")

        val event = when {
            sourceId.equals("github", ignoreCase = true) -> {
                val delivery = headers.entries.firstOrNull { it.key.equals("x-github-delivery", ignoreCase = true) }?.value?.firstOrNull()
                val eventHeader = headers.entries.firstOrNull { it.key.equals("x-github-event", ignoreCase = true) }?.value?.firstOrNull()
                SourceEventNormalizer.normalizeGitHub(delivery, eventHeader, jsonObject)
            }

            else -> {
                val fallbackId = "wh-" + UUID.randomUUID().toString()
                SourceEventNormalizer.normalizeGeneric(fallbackId, jsonObject)
            }
        }

        listeners.forEach { runCatching { it(event) } }
        return WebhookResult.Accepted(event)
    }
}

internal fun ControlPlane.sourceWebhook(exchange: HttpExchange, sourceId: String) {
    val rawBody = exchange.requestBody.readBytes()
    val headers = exchange.requestHeaders.toMap()
    val result = sourceWebhooks.handleWebhook(sourceId, headers, rawBody)
    when (result) {
        is WebhookResult.Accepted -> {
            respond(
                exchange,
                200,
                buildJsonObject {
                    put("ok", true)
                    put("eventId", result.event.id)
                    put("type", result.event.type)
                },
            )
        }
        is WebhookResult.Rejected -> {
            respond(exchange, result.statusCode, errorJson(result.errorCode, result.message))
        }
    }
}

