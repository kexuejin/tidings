// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.source.StandardSourceEvent
import com.kapp.tenon.agent.testutil.Await
import com.kapp.tenon.core.crypto.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        // 派发是异步的（C3 起）：ACK 与分发解耦，用轮询等可观测事实，不赌时序。
        val delivered = Await.value("listener 收到事件") { capturedEvent }
        assertEquals("delivery-123", delivered.id)
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

    /** fail-closed（提案 §6.1 待修复项）：没配密钥的 sourceId 一律拒收，绝不进入解析。 */
    @Test
    fun unconfiguredSecretIsRejectedFailClosed() {
        val registry = SourceWebhookRegistry()
        val body = """{"alert":"critical"}""".toByteArray(Charsets.UTF_8)

        val result = registry.handleWebhook("mail", emptyMap(), body)
        assertIs<WebhookResult.Rejected>(result)
        assertEquals(403, result.statusCode)
        assertEquals("secret-not-configured", result.errorCode)
        // 错误信息必须可操作：告诉操作者去配哪个环境变量。
        assertTrue(
            result.message.contains("TENON_WEBHOOK_SECRET_MAIL"),
            "拒收信息应给出密钥环境变量名：" + result.message,
        )
    }

    /** 半配置不是配置：空白密钥在注册这一刻就拒，不许留一个验签必败的假入口。 */
    @Test
    fun blankSecretRegistrationIsRejected() {
        val registry = SourceWebhookRegistry()

        assertFailsWith<IllegalArgumentException> { registry.registerSecret("mail", "   ") }
        assertFailsWith<IllegalArgumentException> { registry.registerSecret("mail", "") }
        assertFailsWith<IllegalArgumentException> { registry.registerSecret("   ", "secret") }

        // 拒绝之后 sourceId 仍处于未配置状态：后续请求走 fail-closed 403。
        val result = registry.handleWebhook("mail", emptyMap(), "{}".toByteArray(Charsets.UTF_8))
        assertIs<WebhookResult.Rejected>(result)
        assertEquals("secret-not-configured", result.errorCode)
    }

    /** 幂等键（提案 §6.1 待修复项）：无载荷 id 的通用入站，同一 body 必然同一事件 id。 */
    @Test
    fun genericWebhookWithoutPayloadIdIsDeterministic() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("mail", testSecret)
        registry.addListener { /* 无操作消费者：满足 no-consumer 门 */ }

        val body = """{"title":"disk almost full","text":"usage at 97%"}""".toByteArray(Charsets.UTF_8)
        val headers = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))

        val first = registry.handleWebhook("mail", headers, body)
        val second = registry.handleWebhook("mail", headers, body)

        val expectedId = "wh-" + sha256Hex(body)
        assertIs<WebhookResult.Accepted>(first)
        assertEquals(expectedId, first.event.id)
        assertFalse(first.duplicate)
        assertIs<WebhookResult.Accepted>(second)
        assertEquals(expectedId, second.event.id, "重试同一 body 不许生成新事件 id（旧实现 UUID 会）")
        assertTrue(second.duplicate, "第二次投递应被幂等闸吸收并如实标注 duplicate")
        registry.dispose()
    }

    /** 幂等闸挡的是分发，不是回执：重放投递 200 但 listener 只收到一次。 */
    @Test
    fun duplicateDeliveryDoesNotReTriggerListener() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("mail", testSecret)

        // listener 阻塞在 latch 上：第一次投递派发期间，第二次投递的幂等判定
        // 与「恰一次分发」都变成确定性的（不赌派发线程的时序）。
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var listenerCalls = 0
        registry.addListener {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            listenerCalls++
        }

        val body = """{"id":"external-evt-42","type":"mail.inbound","subject":"hi"}"""
            .toByteArray(Charsets.UTF_8)
        val headers = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))

        val first = registry.handleWebhook("mail", headers, body)
        Await.condition("第一次投递的派发已进入 listener", timeoutMs = 10_000L) { started.count == 0L }

        val second = registry.handleWebhook("mail", headers, body)
        assertEquals("external-evt-42", (first as WebhookResult.Accepted).event.id, "载荷自带 id 优先于 body 哈希兜底")
        assertTrue((second as WebhookResult.Accepted).duplicate)

        release.countDown()
        Await.condition("listener 完成恰好一次回调", timeoutMs = 10_000L) { listenerCalls == 1 }
        assertEquals(1, listenerCalls, "重放投递不许再次唤醒 listener（重复触发即并发风暴的种子）")
        registry.dispose()
    }

    /** GitHub 缺 x-github-delivery 头的兜底同样走确定性哈希（同缺陷类，一并修）。 */
    @Test
    fun githubMissingDeliveryHeaderUsesDeterministicId() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("github", testSecret)
        registry.addListener { }

        val body = """{"action":"opened"}""".toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "x-hub-signature-256" to listOf("sha256=" + computeHmacSha256(body, testSecret)),
            "x-github-event" to listOf("push"),
        )

        val first = registry.handleWebhook("github", headers, body)
        val second = registry.handleWebhook("github", headers, body)

        val expectedId = "gh-" + sha256Hex(body)
        assertEquals(expectedId, (first as WebhookResult.Accepted).event.id)
        assertTrue((second as WebhookResult.Accepted).duplicate)
        registry.dispose()
    }

    /** 幂等表条目过 TTL 后失效：迟到的合法重投（新事件）不被旧账吞掉。 */
    @Test
    fun seenEntriesExpireAfterTtl() {
        var now = 1_000_000L
        val registry = SourceWebhookRegistry(nowMillis = { now })
        registry.registerSecret("mail", testSecret)
        registry.addListener { }

        val body = """{"id":"evt-ttl","type":"mail.inbound"}""".toByteArray(Charsets.UTF_8)
        val headers = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))

        assertTrue((registry.handleWebhook("mail", headers, body) as WebhookResult.Accepted).event.id == "evt-ttl")

        now += 15 * 60_000L + 1 // 越过 TTL
        val third = registry.handleWebhook("mail", headers, body)
        assertFalse((third as WebhookResult.Accepted).duplicate, "TTL 过期后同 id 视为新事件重新受理")
        registry.dispose()
    }

    /** 异步解耦（提案 §6.1 待建项）：无消费者的诚实出口——503，不收下注定石沉大海的事件。 */
    @Test
    fun noListenerIsRejectedNoConsumer() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("mail", testSecret)

        val body = """{"id":"evt-1","type":"mail.inbound"}""".toByteArray(Charsets.UTF_8)
        val headers = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))

        val result = registry.handleWebhook("mail", headers, body)
        assertIs<WebhookResult.Rejected>(result)
        assertEquals(503, result.statusCode)
        assertEquals("no-consumer", result.errorCode)
        registry.dispose()
    }

    /** ACK 先于执行：验签受理立即返回，事件在后台队列里派发（外部服务不再被推理耗时拖到超时重试）。 */
    @Test
    fun ackReturnsBeforeListenerFinishes() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("mail", testSecret)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        registry.addListener {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        val body = """{"id":"evt-ack","type":"mail.inbound"}""".toByteArray(Charsets.UTF_8)
        val headers = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))

        val t0 = System.nanoTime()
        val result = registry.handleWebhook("mail", headers, body)
        val ackMillis = (System.nanoTime() - t0) / 1_000_000

        assertIs<WebhookResult.Accepted>(result)
        assertTrue(started.getCount() > 0 || ackMillis < 10_000, "ACK 不应等待 listener 完成")
        // 确定性判据：listener 尚未跑完时 ACK 已经到手（blocked latch 保证派发未完成）。
        Await.condition("派发已开始（listener 已被调起）", timeoutMs = 10_000L) { started.count == 0L }
        assertTrue(release.count > 0, "ACK 返回时 listener 仍在执行中——两者确已解耦")
        release.countDown()
        registry.dispose()
    }

    /** 背压（提案 §2.3 工作池）：并发 1 + 容量 1，消费者阻塞时第三条投递 503 queue-full。 */
    @Test
    fun queueFullIsRejectedWith503() {
        val registry = SourceWebhookRegistry(queueCapacity = 1, dispatchParallelism = 1)
        registry.registerSecret("mail", testSecret)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        registry.addListener {
            started.countDown()
            release.await(15, TimeUnit.SECONDS)
        }

        fun bodyOf(id: String) = """{"id":"$id","type":"mail.inbound"}""".toByteArray(Charsets.UTF_8)
        fun headersOf(body: ByteArray) = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))

        // 第一条：被消费者取走、permit 占满（listener 阻塞中）。
        assertIs<WebhookResult.Accepted>(registry.handleWebhook("mail", headersOf(bodyOf("evt-1")), bodyOf("evt-1")))
        Await.condition("第一条已进入阻塞的 listener", timeoutMs = 10_000L) { started.count == 0L }
        // 第二条：填满唯一的缓冲位。
        assertIs<WebhookResult.Accepted>(registry.handleWebhook("mail", headersOf(bodyOf("evt-2")), bodyOf("evt-2")))
        // 第三条：队列已满——503 让发送方稍后重试，而不是无声吞掉。
        val third = registry.handleWebhook("mail", headersOf(bodyOf("evt-3")), bodyOf("evt-3"))
        assertIs<WebhookResult.Rejected>(third)
        assertEquals(503, third.statusCode)
        assertEquals("queue-full", third.errorCode)

        release.countDown()
        Await.condition("积压排空后恢复受理", timeoutMs = 15_000L) {
            registry.handleWebhook("mail", headersOf(bodyOf("evt-4")), bodyOf("evt-4")) is WebhookResult.Accepted
        }
        registry.dispose()
    }
}
