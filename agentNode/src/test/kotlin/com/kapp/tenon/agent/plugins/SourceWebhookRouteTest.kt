// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.source.StandardSourceEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceWebhookRouteTest {

    private val testSecret = "super-secret-token-12345"

    private fun computeHmacSha256(rawBytes: ByteArray, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(rawBytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun verifierAcceptsValidSignature() {
        val payload = """{"action":"opened","issue":{"number":42,"title":"Bug in queue"}}""".toByteArray(Charsets.UTF_8)
        val validSig = computeHmacSha256(payload, testSecret)

        assertTrue(SourceWebhookVerifier.verifyHmacSha256(payload, testSecret, validSig))
        assertTrue(SourceWebhookVerifier.verifyHmacSha256(payload, testSecret, "sha256=$validSig"))
        assertTrue(SourceWebhookVerifier.verifyHmacSha256(payload, testSecret, "SHA256=${validSig.uppercase()}"))
    }

    @Test
    fun verifierRejectsTamperedPayloadOrWrongSecret() {
        val payload = """{"action":"opened"}""".toByteArray(Charsets.UTF_8)
        val validSig = computeHmacSha256(payload, testSecret)

        val tamperedPayload = """{"action":"closed"}""".toByteArray(Charsets.UTF_8)
        assertFalse(SourceWebhookVerifier.verifyHmacSha256(tamperedPayload, testSecret, validSig))

        assertFalse(SourceWebhookVerifier.verifyHmacSha256(payload, "wrong-secret", validSig))
        assertFalse(SourceWebhookVerifier.verifyHmacSha256(payload, testSecret, null))
        assertFalse(SourceWebhookVerifier.verifyHmacSha256(payload, testSecret, ""))
    }

    @Test
    fun normalizesGitHubIssuePayload() {
        val jsonStr = """
            {
              "action": "opened",
              "repository": {
                "full_name": "tenon/mobile"
              },
              "issue": {
                "number": 101,
                "title": "Fix memory leak in sqlite queue",
                "body": "Detailed repro instructions here",
                "html_url": "https://github.com/tenon/mobile/issues/101"
              }
            }
        """.trimIndent()
        val json = Json.parseToJsonElement(jsonStr).jsonObject

        val event = SourceEventNormalizer.normalizeGitHub(
            deliveryId = "del-uuid-001",
            eventHeader = "issues",
            payload = json,
        )

        assertEquals("del-uuid-001", event.id)
        assertEquals("issues.opened", event.type)
        assertEquals("tenon/mobile#101", event.subject)
        assertEquals("Fix memory leak in sqlite queue", event.summary)
        assertEquals("Detailed repro instructions here", event.detail)
        assertEquals("https://github.com/tenon/mobile/issues/101", event.link)
    }

    @Test
    fun normalizesGitHubPullRequestPayload() {
        val jsonStr = """
            {
              "action": "labeled",
              "repository": {
                "full_name": "tenon/mobile"
              },
              "pull_request": {
                "number": 202,
                "title": "feat: SQLite offline queue",
                "body": "PR description",
                "html_url": "https://github.com/tenon/mobile/pull/202"
              }
            }
        """.trimIndent()
        val json = Json.parseToJsonElement(jsonStr).jsonObject

        val event = SourceEventNormalizer.normalizeGitHub(
            deliveryId = "del-uuid-002",
            eventHeader = "pull_request",
            payload = json,
        )

        assertEquals("del-uuid-002", event.id)
        assertEquals("pull_request.labeled", event.type)
        assertEquals("tenon/mobile#202", event.subject)
        assertEquals("feat: SQLite offline queue", event.summary)
        assertEquals("https://github.com/tenon/mobile/pull/202", event.link)
    }

    @Test
    fun normalizerEnforcesSummaryAndDetailClamping() {
        val longSummary = "S".repeat(500)
        val longDetail = "D".repeat(8000)

        val event = StandardSourceEvent.create(
            id = "id-1",
            type = "custom.type",
            subject = "sub",
            summary = longSummary,
            detail = longDetail,
        )

        assertEquals(200, event.summary.length)
        assertEquals(4000, event.detail.length)
    }

    @Test
    fun webhookRegistryProcessesSignedWebhook() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("github", testSecret)

        var capturedEvent: StandardSourceEvent? = null
        registry.addListener { capturedEvent = it }

        val body = """
            {
              "action": "opened",
              "repository": { "full_name": "my/repo" },
              "issue": {
                "number": 5,
                "title": "Important task",
                "body": "Task details"
              }
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val sig = computeHmacSha256(body, testSecret)
        val headers = mapOf(
            "x-hub-signature-256" to listOf("sha256=$sig"),
            "x-github-delivery" to listOf("delivery-123"),
            "x-github-event" to listOf("issues"),
        )

        val result = registry.handleWebhook("github", headers, body)
        assertIs<WebhookResult.Accepted>(result)
        assertEquals("delivery-123", result.event.id)
        assertEquals("my/repo#5", result.event.subject)
        assertNotNull(capturedEvent)
        assertEquals("delivery-123", capturedEvent.id)
    }

    @Test
    fun webhookRegistryRejectsMissingOrInvalidSignature() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("github", testSecret)

        val body = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)

        // Missing signature
        val resMissing = registry.handleWebhook("github", emptyMap(), body)
        assertIs<WebhookResult.Rejected>(resMissing)
        assertEquals(401, resMissing.statusCode)
        assertEquals("missing-signature", resMissing.errorCode)

        // Invalid signature
        val resInvalid = registry.handleWebhook(
            "github",
            mapOf("x-hub-signature-256" to listOf("sha256=invalidhex00000000")),
            body,
        )
        assertIs<WebhookResult.Rejected>(resInvalid)
        assertEquals(401, resInvalid.statusCode)
        assertEquals("invalid-signature", resInvalid.errorCode)
    }
}
