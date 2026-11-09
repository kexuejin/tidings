// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.source.StandardSourceEvent
import com.kapp.tenon.agent.testutil.Await
import com.kapp.tenon.core.crypto.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceWebhookRouteTest {

    private val testSecret = "super-secret-token-12345"

    /** 测试替身：等价于「Job Table 落盘成功」。没装的 registry 一律 503 no-consumer。 */
    private fun SourceWebhookRegistry.alwaysStored() {
        durableSink = { _, _ -> WebhookPersistence.Stored }
    }

    /** 一条可直接投递的「mail」通用 webhook：body + 对应 HMAC 头。 */
    private fun signed(eventId: String): Pair<ByteArray, Map<String, List<String>>> {
        val body = """{"id":"$eventId","type":"mail.inbound"}""".toByteArray(Charsets.UTF_8)
        val headers = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))
        return body to headers
    }

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
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
        registry.registerSecret("github", testSecret)

        var capturedEvent: StandardSourceEvent? = null
        registry.addListener { _, event -> capturedEvent = event }

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
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
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
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
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
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }

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
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
        registry.registerSecret("mail", testSecret)
        registry.addListener { _, _ -> /* 无操作消费者：满足 no-consumer 门 */ }

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
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
        registry.registerSecret("mail", testSecret)

        // listener 阻塞在 latch 上：第一次投递派发期间，第二次投递的幂等判定
        // 与「恰一次分发」都变成确定性的（不赌派发线程的时序）。
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var listenerCalls = 0
        registry.addListener { _, _ ->
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
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
        registry.registerSecret("github", testSecret)
        registry.addListener { _, _ -> }

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
        val registry = SourceWebhookRegistry(nowMillis = { now }).also { it.alwaysStored() }
        registry.registerSecret("mail", testSecret)
        registry.addListener { _, _ -> }

        val body = """{"id":"evt-ttl","type":"mail.inbound"}""".toByteArray(Charsets.UTF_8)
        val headers = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))

        assertTrue((registry.handleWebhook("mail", headers, body) as WebhookResult.Accepted).event.id == "evt-ttl")

        now += 15 * 60_000L + 1 // 越过 TTL
        val third = registry.handleWebhook("mail", headers, body)
        assertFalse((third as WebhookResult.Accepted).duplicate, "TTL 过期后同 id 视为新事件重新受理")
        registry.dispose()
    }

    /**
     * fail-closed：没有**持久化**出口时一律 503，哪怕注册了 listener。
     *
     * 旧实现只看 listeners 是否为空就回 200，而落盘在异步 listener 里、异常被
     * runCatching 吞掉 —— 「发送侧一切正常、接收侧什么都没有」。现在判据是
     * durableSink：没装 Job Table 就不许假装受理。
     */
    @Test
    fun noDurableSinkIsRejectedNoConsumer() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("mail", testSecret)
        registry.addListener { _, _ -> }

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
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
        registry.registerSecret("mail", testSecret)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        registry.addListener { _, _ ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        val body = """{"id":"evt-ack","type":"mail.inbound"}""".toByteArray(Charsets.UTF_8)
        val headers = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))

        val t0 = System.nanoTime()
        val result = registry.handleWebhook("mail", headers, body)
        val ackMillis = (System.nanoTime() - t0) / 1_000_000

        assertIs<WebhookResult.Accepted>(result)
        // 旧断言是 `started.getCount() > 0 || ackMillis < 10_000` —— 恒真：
        // 只要 ACK 在 10 秒内返回（任何机器都成立），后半句就成立。
        //
        // 真正的判据必须能**区分**同步与异步：listener 被 latch 挡住最长 10 秒，
        // 如果 ACK 等它跑完，这次调用就会耗掉约 10 秒。所以阈值取 5 秒 ——
        // 它把「解耦」和「同步」分开，而不是给一个谁都能过的上界。
        assertTrue(
            ackMillis < 5_000,
            "ACK 不应等待 listener 完成（实测 " + ackMillis + "ms；listener 会阻塞 10s，" +
                "同步实现必然接近 10s）",
        )
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
            .also { it.alwaysStored() }
        registry.registerSecret("mail", testSecret)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        registry.addListener { _, _ ->
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

    /**
     * 幂等键必须按 sourceId 分域：两个不同的源各自投递 id 相同的两条事件，都要被受理。
     *
     * 旧实现只拿 `event.id` 当键，于是第二个源的事件被判成「第一个源的重放」，
     * 直接 `200 duplicate=true` 掉 —— 一个源静默吞掉另一个源的事件，而且回的是
     * 成功码，发送方完全看不出异常。这条用例在旧实现下必然红。
     */
    @Test
    fun idempotencyIsScopedPerSourceNotJustPerEventId() {
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
        registry.registerSecret("mail", testSecret)
        registry.registerSecret("calendar", "another-secret-9876")

        val delivered = java.util.concurrent.CopyOnWriteArrayList<String>()
        registry.addListener { sourceId, event -> delivered += sourceId + "/" + event.id }

        val body = """{"id":"evt-shared","type":"shared.event"}""".toByteArray(Charsets.UTF_8)
        val mailHeaders = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, testSecret)))
        val calHeaders = mapOf("x-webhook-signature" to listOf(computeHmacSha256(body, "another-secret-9876")))

        val first = registry.handleWebhook("mail", mailHeaders, body)
        assertIs<WebhookResult.Accepted>(first)
        assertFalse(first.duplicate, "第一次投递不是重放")

        val second = registry.handleWebhook("calendar", calHeaders, body)
        assertIs<WebhookResult.Accepted>(second)
        assertFalse(
            second.duplicate,
            "同 id 但不同 source 的事件不是重放——旧实现会在这里返回 duplicate=true，" +
                "等于 calendar 源的事件被静默吞掉",
        )

        Await.condition("两个源的事件都派发到了 listener", timeoutMs = 10_000L) { delivered.size >= 2 }
        assertEquals(
            setOf("mail/evt-shared", "calendar/evt-shared"),
            delivered.toSet(),
            "两个源的事件都必须派发，实际：" + delivered,
        )
        registry.dispose()
    }

    /**
     * 请求体读取必须有界：`readBytes()` 会把整段拉进堆再判断，判出来时内存已经付出去了。
     *
     * 直接测 [readBoundedBody]：用 4 KiB 的流 + 1 KiB 上限，确认返回 null 而不是
     * 「先收下 4 KiB 再拒」。
     */
    @Test
    fun readBoundedBodyRejectsOversizedInput() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        assertEquals(
            null,
            readBoundedBody(payload.inputStream(), 1024),
            "超限必须返回 null（调用方回 413），不是截断后当成合法 body",
        )
        assertTrue(
            readBoundedBody(payload.inputStream(), 4096)!!.contentEquals(payload),
            "刚好等于上限要原样放行（判据是 > limit，不是 >= limit）",
        )
    }

    // ---- ACK 之前的持久化判据 ----

    /**
     * 落盘失败必须是**拒收**而不是「收下了」。
     *
     * 旧链路：验签 → `trySend` 内存队列 → 立刻 200；落盘在异步 listener 里，
     * 异常被 `runCatching` 吞掉、`ingest` 的 false 也被忽略。症状是发送方拿到
     * 200、宿主一行台账都没有。现在 503 + 撤销内存账，把重试权还给发送方。
     */
    @Test
    fun persistenceFailureIsRejectedWith503AndRetryIsAccepted() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("mail", testSecret)
        val delivered = CopyOnWriteArrayList<String>()
        registry.addListener { _, event -> delivered += event.id }

        val sinkCalls = AtomicInteger(0)
        registry.durableSink = { _, _ ->
            if (sinkCalls.incrementAndGet() == 1) {
                WebhookPersistence.Failed("disk full (simulated)")
            } else {
                WebhookPersistence.Stored
            }
        }
        val (body, headers) = signed("evt-persist-fail")

        val first = registry.handleWebhook("mail", headers, body)
        assertIs<WebhookResult.Rejected>(first)
        assertEquals(503, first.statusCode)
        assertEquals("persist-failed", first.errorCode)
        assertTrue(
            first.message.contains("disk full"),
            "拒收原因必须把落盘侧的错误透出来，便于排障：" + first.message,
        )
        assertEquals(0, delivered.size, "还没受理的事件不许 fan-out 到消费侧")

        val retry = registry.handleWebhook("mail", headers, body)
        assertIs<WebhookResult.Accepted>(retry)
        assertFalse(
            retry.duplicate,
            "落盘失败不许留内存账：重试必须按首次受理落盘，不能被 200 duplicate 吃掉",
        )
        assertEquals(2, sinkCalls.get(), "重试必须真的再落一次盘")
        Await.condition("重试后事件派发给 listener", timeoutMs = 10_000L) {
            delivered.contains("evt-persist-fail")
        }
        registry.dispose()
    }

    /** 与 [persistenceFailureIsRejectedWith503AndRetryIsAccepted] 同缺陷类：sink 抛错不许被吞。 */
    @Test
    fun persistenceSinkExceptionIsRejectedWith503AndRetryIsAccepted() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("mail", testSecret)
        val sinkCalls = AtomicInteger(0)
        registry.durableSink = { _, _ ->
            if (sinkCalls.incrementAndGet() == 1) {
                throw IllegalStateException("SQLITE_BUSY (simulated)")
            }
            WebhookPersistence.Stored
        }
        val (body, headers) = signed("evt-sink-throws")

        val first = registry.handleWebhook("mail", headers, body)
        assertIs<WebhookResult.Rejected>(first)
        assertEquals(503, first.statusCode)
        assertEquals("persist-failed", first.errorCode)
        assertTrue(first.message.contains("SQLITE_BUSY"), "抛错原因应出现在拒收消息里：" + first.message)

        val retry = registry.handleWebhook("mail", headers, body)
        assertIs<WebhookResult.Accepted>(retry)
        assertFalse(retry.duplicate)
        registry.dispose()
    }

    /**
     * 持久化台账说「已有这一行」是合法重投（重启后内存账失忆），必须 200 停下重试，
     * 但**不许再 fan-out**。派发单线程 + 第三条事件作 FIFO 栅栏，让「没收到的
     * 事件」成为可观测事实，而不是一句睡完就断言的空话。
     */
    @Test
    fun durableLedgerDuplicateIsAckedWithoutFanOut() {
        val registry = SourceWebhookRegistry(queueCapacity = 8, dispatchParallelism = 1)
        registry.registerSecret("mail", testSecret)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<String>()
        registry.addListener { _, event ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            delivered += event.id
        }
        registry.durableSink = { _, event ->
            if (event.id == "evt-dup") WebhookPersistence.Duplicate else WebhookPersistence.Stored
        }

        val (storedBody, storedHeaders) = signed("evt-stored")
        val first = registry.handleWebhook("mail", storedHeaders, storedBody)
        assertIs<WebhookResult.Accepted>(first)
        assertFalse(first.duplicate)
        Await.condition("第一条已进入阻塞的 listener", timeoutMs = 10_000L) { started.count == 0L }

        val (dupBody, dupHeaders) = signed("evt-dup")
        val duplicate = registry.handleWebhook("mail", dupHeaders, dupBody)
        assertIs<WebhookResult.Accepted>(duplicate)
        assertTrue(duplicate.duplicate, "台账里已有的一行是合法重投，200 让发送方停止重试")

        // 单线程派发 + FIFO：栅栏事件到 listener 时，排在它前面的每一条都已经处理完。
        val (markerBody, markerHeaders) = signed("evt-marker")
        assertIs<WebhookResult.Accepted>(registry.handleWebhook("mail", markerHeaders, markerBody))
        release.countDown()
        Await.condition("栅栏事件到达 listener", timeoutMs = 10_000L) { delivered.contains("evt-marker") }
        assertEquals(
            setOf("evt-stored", "evt-marker"),
            delivered.toSet(),
            "Duplicate 不许再次唤醒 listener（实际：" + delivered + "）",
        )
        registry.dispose()
    }

    /**
     * 并发重投的丢事件窗口：内存账**不许**排在落盘之前。
     *
     * 旧顺序「先 rememberEvent 再 sink」下，第二条在内存命中就直接 200 duplicate，
     * 一次都不问台账；若第一条此刻还卡在 sink 里并随后失败，两个请求都以为对方
     * 成功，事件永久丢失。这里用顺序重投把「每条投递都要问台账」钉成可观测事实
     * （不需要赌线程时序）：sink 调用数必须是 2。
     */
    @Test
    fun everyDeliveryAsksTheDurableLedgerBeforeAck() {
        val registry = SourceWebhookRegistry()
        registry.registerSecret("mail", testSecret)
        val sinkCalls = AtomicInteger(0)
        registry.durableSink = { _, _ ->
            if (sinkCalls.incrementAndGet() == 1) WebhookPersistence.Stored
            else WebhookPersistence.Duplicate
        }
        val (body, headers) = signed("evt-ledger-replay")

        val first = registry.handleWebhook("mail", headers, body)
        assertIs<WebhookResult.Accepted>(first)
        assertFalse(first.duplicate)

        val replay = registry.handleWebhook("mail", headers, body)
        assertIs<WebhookResult.Accepted>(replay)
        assertTrue(replay.duplicate)
        assertEquals(
            2,
            sinkCalls.get(),
            "重投必须再问一次台账：内存表不是权威。旧的「先记内存账再落盘」顺序" +
                "会让第二条在第一条仍卡在 sink 里时直接 200 duplicate",
        )
        registry.dispose()
    }

    /** fan-out 的异常隔离：一个 listener 抛错不许吞掉其余 listener，也不许改写 ACK。 */
    @Test
    fun aThrowingListenerDoesNotSwallowTheRestOfTheFanOut() {
        val registry = SourceWebhookRegistry().also { it.alwaysStored() }
        registry.registerSecret("mail", testSecret)
        val delivered = CopyOnWriteArrayList<String>()
        registry.addListener { _, _ -> throw IllegalStateException("listener boom (simulated)") }
        registry.addListener { _, event -> delivered += event.id }

        val (body, headers) = signed("evt-listener-isolation")
        assertIs<WebhookResult.Accepted>(registry.handleWebhook("mail", headers, body))
        Await.condition("抛错的 listener 不许吞掉后续 listener 的派发", timeoutMs = 10_000L) {
            delivered.contains("evt-listener-isolation")
        }
        registry.dispose()
    }
}
