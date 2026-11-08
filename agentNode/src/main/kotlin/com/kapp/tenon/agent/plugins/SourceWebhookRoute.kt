// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.ControlPlane
import com.kapp.tenon.agent.source.StandardSourceEvent
import com.kapp.tenon.core.crypto.sha256Hex
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface WebhookResult {
    /**
     * @param duplicate 同一 event.id 的重放投递（网络重试等）。仍是 200——让发送方
     *   停止重试；事件不再分发给 listener。
     */
    data class Accepted(val event: StandardSourceEvent, val duplicate: Boolean = false) : WebhookResult
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
        /** 投递 id；调用方保证非空——缺头的确定性兜底（body 哈希）在 handleWebhook 一处做。 */
        deliveryId: String,
        eventHeader: String?,
        payload: JsonObject,
    ): StandardSourceEvent {
        val eventType = eventHeader?.trim()?.ifBlank { null } ?: "github.unknown"
        val id = deliveryId.trim().ifBlank { "gh-unknown" }

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

class SourceWebhookRegistry(
    /** 有界队列容量：溢出即 503，让发送方的重试机制当背压用。 */
    val queueCapacity: Int = 64,
    /** 派发并发上限（提案 §2.3 工作池：桌面默认 2~4）。 */
    val dispatchParallelism: Int = 2,
    /** 时钟注入：幂等表 TTL 的测试钉子。 */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val secrets = ConcurrentHashMap<String, String>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(StandardSourceEvent) -> Unit>()

    /**
     * event.id → 首次受理时刻。网络重试是常态：发送方等不到 ACK 会重发同一投递，
     * 没有这张表，一次外部事件会唤醒宿主两次。内存表是阶段二持久化 Job Table
     * 落地前的过渡（届时 event_id 主键就是持久幂等键）。
     */
    private val seenEvents = ConcurrentHashMap<String, Long>()

    // ---- 异步解耦（提案 §6.1 待建项）：验签后立即 ACK，事件先入有界队列再派发 ----
    // 队列与消费循环由注册表自带（ControlPlane 的 dispatchScope 初始化晚于本属性，
    // 注入会导致构造顺序倒置）；dispose() 由宿主 stop() 调用收口。
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatchChannel = Channel<StandardSourceEvent>(queueCapacity)
    private val dispatchPermits = Semaphore(dispatchParallelism)

    init {
        dispatchScope.launch {
            while (true) {
                // 先占配额再取事件：取出的那一刻起事件要么在派发、要么还在通道里，
                // 背压严格等于 queueCapacity——不能让「取出待派」绕开有界队列。
                dispatchPermits.acquire()
                val event = dispatchChannel.receive()
                dispatchScope.launch {
                    try {
                        dispatchToListeners(event)
                    } finally {
                        dispatchPermits.release()
                    }
                }
            }
        }
    }

    /** 停机收口：关闭队列并取消消费循环。宿主 stop() 必须调用。 */
    fun dispose() {
        dispatchChannel.close()
        dispatchScope.cancel()
    }

    private fun dispatchToListeners(event: StandardSourceEvent) {
        listeners.forEach { runCatching { it(event) } }
    }

    /**
     * 注册 sourceId → secret 的唯一显式入口。空白密钥在配置这一刻就拒：
     * 收下空白密钥等于让 fail-closed 门空转——后面每条请求都会以
     * invalid-signature 退回，而操作者还以为配置成功了。
     */
    fun registerSecret(sourceId: String, secret: String) {
        val key = sourceId.trim().lowercase()
        val trimmed = secret.trim()
        require(key.isNotEmpty()) { "sourceId must not be blank" }
        require(trimmed.isNotEmpty()) { "secret for \"$key\" must not be blank" }
        secrets[key] = trimmed
    }

    /**
     * 密钥解析：显式注册表优先，环境变量 `TENON_WEBHOOK_SECRET_<SOURCEID>` 兜底
     * （`-` 归一为 `_` 后大写）。环境变量设成空白视同未配置——半配置状态必须
     * 落到与「未配置」同一个 fail-closed 出口，不能装作配好了。
     */
    fun getSecret(sourceId: String): String? {
        val key = sourceId.trim().lowercase()
        return secrets[key]
            ?: System.getenv("TENON_WEBHOOK_SECRET_${key.replace('-', '_').uppercase()}")?.takeIf { it.isNotBlank() }
    }

    fun addListener(listener: (StandardSourceEvent) -> Unit) {
        listeners.add(listener)
    }

    /** 幂等表条目存活时长：覆盖主流 webhook 提供方的重试窗口（GitHub 最长约数小时，此处取保守下限）。 */
    private companion object {
        val SEEN_TTL_MS: Long = 15 * 60_000L
        const val SEEN_MAX_ENTRIES: Int = 4096
    }

    /**
     * 原子记账：返回 true = 首次受理（应分发），false = 重放投递（不再分发）。
     * 记账发生在受理判定**之前**——调用方若因容量拒收本条，必须调用
     * [forgetEvent] 把这笔账撤掉，否则合法重试会被这张表吞掉。
     * 命中旧账时即时判 TTL 过期（清扫只防御容量，不承担过期语义）。
     */
    private fun rememberEvent(eventId: String): Boolean {
        if (seenEvents.size >= SEEN_MAX_ENTRIES) sweepSeenEvents()
        val now = nowMillis()
        val existing = seenEvents.putIfAbsent(eventId, now) ?: return true
        if (now - existing > SEEN_TTL_MS) {
            return seenEvents.remove(eventId, existing) && seenEvents.putIfAbsent(eventId, now) == null
        }
        return false
    }

    /** 撤账：事件未能真正受理（队列满等）时调用，把重试权还给发送方。 */
    private fun forgetEvent(eventId: String) {
        seenEvents.remove(eventId)
    }

    private fun sweepSeenEvents() {
        val cutoff = nowMillis() - SEEN_TTL_MS
        seenEvents.entries.removeIf { it.value < cutoff }
        if (seenEvents.size >= SEEN_MAX_ENTRIES) {
            // TTL 清完还超限：淘汰最老的一半（正常负载下到不了这里——这是防御线）。
            val eldest = seenEvents.entries.sortedBy { it.value }.take(seenEvents.size - SEEN_MAX_ENTRIES / 2)
            eldest.forEach { seenEvents.remove(it.key, it.value) }
        }
    }

    fun handleWebhook(
        sourceId: String,
        headers: Map<String, List<String>>,
        rawBody: ByteArray,
    ): WebhookResult {
        // fail-closed：没配密钥的 sourceId 一律拒收，不进入解析。旧实现「没密钥就跳过
        // 验签直接 Accepted」意味着任何知道端口的人都能伪造事件喂进宿主——本地回环
        // 不是边界（反代/同机进程都在回环上）。密钥来源见 [getSecret]。
        val secret = getSecret(sourceId)
            ?: return WebhookResult.Rejected(
                403,
                "secret-not-configured",
                "source \"$sourceId\" has no webhook secret configured; set TENON_WEBHOOK_SECRET_" +
                    sourceId.trim().lowercase().replace('-', '_').uppercase() +
                    " (or call registerSecret) before posting to it",
            )

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

        val jsonObject = SourceEventNormalizer.parseJsonObject(rawBody)
            ?: return WebhookResult.Rejected(400, "bad-json", "Request body is not valid JSON")

        val event = when {
            sourceId.equals("github", ignoreCase = true) -> {
                val delivery = headers.entries.firstOrNull { it.key.equals("x-github-delivery", ignoreCase = true) }?.value?.firstOrNull()
                val eventHeader = headers.entries.firstOrNull { it.key.equals("x-github-event", ignoreCase = true) }?.value?.firstOrNull()
                // 缺 delivery 头的兜底从 UUID 换成确定性哈希：同一投递体重发两次，
                // UUID 会造出两个事件（重试即重复触发），sha256 天然幂等。
                SourceEventNormalizer.normalizeGitHub(delivery ?: ("gh-" + sha256Hex(rawBody)), eventHeader, jsonObject)
            }

            else -> {
                val fallbackId = "wh-" + sha256Hex(rawBody)
                SourceEventNormalizer.normalizeGeneric(fallbackId, jsonObject)
            }
        }

        // 无消费者的诚实出口：listener 空转等于「ACK 了但没人消费」——本仓最贵的
        // 缺陷形态（发送侧一切正常、接收侧什么都没有）。宁可 503 让发送方重试，
        // 也不收下注定石沉大海的事件。生产消费者（编排侧持久化桥）由宿主装配。
        if (listeners.isEmpty()) {
            return WebhookResult.Rejected(503, "no-consumer", "no listener registered for webhook events")
        }

        // 幂等闸：命中重放直接 ACK（duplicate=true），不再分发。判据是 event.id，
        // 它来自发送方的投递头 / 载荷 id / body 哈希——三种来源都是「同一次投递同
        // 一个 id」的确定性形状，UUID 兜底已不存在。
        if (!rememberEvent(event.id)) {
            return WebhookResult.Accepted(event, duplicate = true)
        }

        // 入队失败（背压到顶）= 本条未受理：撤掉幂等账，把重试权还给发送方。
        if (dispatchChannel.trySend(event).isFailure) {
            forgetEvent(event.id)
            return WebhookResult.Rejected(
                503,
                "queue-full",
                "webhook dispatch queue is full (capacity " + queueCapacity + "); retry later",
            )
        }

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
                    // 重放投递如实标注：发送方的网络重试被幂等闸吸收了，它应该知道。
                    put("duplicate", result.duplicate)
                },
            )
        }
        is WebhookResult.Rejected -> {
            respond(exchange, result.statusCode, errorJson(result.errorCode, result.message))
        }
    }
}

