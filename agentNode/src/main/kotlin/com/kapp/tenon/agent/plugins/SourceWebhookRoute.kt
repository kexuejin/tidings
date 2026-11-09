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
     * @param duplicate 同一 (sourceId, event.id) 的重放投递（网络重试等）。仍是 200——
     *   让发送方停止重试；事件不再分发给 listener。进程重启后内存幂等表失忆、
     *   但持久化台账里已有这一行时也会走这里。
     */
    data class Accepted(val event: StandardSourceEvent, val duplicate: Boolean = false) : WebhookResult
    data class Rejected(val statusCode: Int, val errorCode: String, val message: String) : WebhookResult
}

/**
 * ACK **之前**的持久化受理结果（触发式编排提案 §9 阶段二）。
 *
 * 旧链路：验签 → `trySend` 到内存队列 → 立刻回 200；落盘发生在异步 listener 里，
 * 且 listener 异常被 `runCatching` 吞掉、`ingest` 的 false 也被忽略。症状是
 * 发送方拿到 200、宿主一行台账都没有、日志里什么都没有 —— 事件永久丢失。
 *
 * 现在「这一行真的进了持久化台账」是回 200 的**唯一**前提。注册表不自己假装
 * 有队列：它只问持久化消费者「这次受理成了没有」，失败就回 503 并把重试权
 * 还给发送方。`Duplicate` 与 `Failed` 必须分开——把落盘失败当成重放，等于用
 * 一个成功码把丢事件盖住。
 */
sealed interface WebhookPersistence {
    /** 新事件已持久化：可以继续 fan-out 并回 200。 */
    data object Stored : WebhookPersistence

    /** 台账里已有这一行：回 200 duplicate=true 让发送方停止重试，不再 fan-out。 */
    data object Duplicate : WebhookPersistence

    /** 落盘失败：回 503 并撤掉内存幂等账，把重试权还给发送方。 */
    data class Failed(val reason: String) : WebhookPersistence
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
    // listener 带上 sourceId：路由解析（哪个触发器要接这条事件）需要它，而
    // StandardSourceEvent 的 6 字段权威模型不携带投递来源——在 fan-out 处把
    // 事实来源一起交出去，比让消费方猜（或给事件模型加字段）都诚实。
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(sourceId: String, StandardSourceEvent) -> Unit>()

    /**
     * (sourceId, event.id) → 台账**确认受理**的时刻。只用于可选 fan-out 的去重，
     * 不再是「这一条该不该收」的判据：受理判据完全交给 [durableSink]／持久化台账。
     *
     * 旧实现先写这张表再落盘，于是有个丢事件窗口：并发重投在第一条仍卡在 sink
     * 里时命中内存账，直接回 200 duplicate；第一条随后落盘失败，两个请求都以为
     * 对方成功 —— 谁也没落盘。现在只有 sink 回了 Stored/Duplicate 才记账。
     *
     * 进程内、重启即失：重启后迟到的重投走到 sink，由台账的
     * (source_id, event_id) 复合主键回 [WebhookPersistence.Duplicate]。
     */
    private val seenEvents = ConcurrentHashMap<String, Long>()

    /**
     * ACK 之前的持久化出口。**null = 没有持久化消费者，一律 503**：没有落盘出口
     * 还回 200，就是「发送侧一切正常、接收侧什么都没有」——本仓最贵的缺陷形态。
     *
     * 生产装配在 DesktopExtension（注入 Job Table 时）；schedulerStore 为 null 时
     * 整组不装配，webhook 保持 503 no-consumer，如实不可用，不装假队列。
     * listener 是可选的异步 fan-out，不承担持久化责任。
     */
    @Volatile
    var durableSink: ((sourceId: String, event: StandardSourceEvent) -> WebhookPersistence)? = null

    // ---- 异步解耦（提案 §6.1 待建项）：验签后立即 ACK，事件先入有界队列再派发 ----
    // 队列与消费循环由注册表自带（ControlPlane 的 dispatchScope 初始化晚于本属性，
    // 注入会导致构造顺序倒置）；dispose() 由宿主 stop() 调用收口。
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatchChannel = Channel<QueuedEvent>(queueCapacity)
    private val dispatchPermits = Semaphore(dispatchParallelism)

    init {
        dispatchScope.launch {
            while (true) {
                // 先占配额再取事件：取出的那一刻起事件要么在派发、要么还在通道里，
                // 背压严格等于 queueCapacity——不能让「取出待派」绕开有界队列。
                dispatchPermits.acquire()
                val queued = dispatchChannel.receive()
                dispatchScope.launch {
                    try {
                        dispatchToListeners(queued.sourceId, queued.event)
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

    /** 队列元素：sourceId 是投递事实的一部分，跟事件一起入队。 */
    private data class QueuedEvent(val sourceId: String, val event: StandardSourceEvent)

    private fun dispatchToListeners(sourceId: String, event: StandardSourceEvent) {
        listeners.forEach { listener ->
            // 旧写法 `runCatching { ... }` 把异常整个吞掉：一个 listener 抛错时
            // 发送方已经拿到 200，日志里却一行都没有。持久化不再经这条路，但
            // fan-out 失败仍然必须可见，否则「收下了、没人看见」又是同一种静默。
            runCatching { listener(sourceId, event) }.onFailure { t ->
                java.util.logging.Logger.getLogger(SourceWebhookRegistry::class.java.name)
                    .warning(
                        "[webhook] listener failed for " + sourceId + "/" + event.id +
                            ": " + (t.message ?: t.toString()),
                    )
            }
        }
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

    fun addListener(listener: (sourceId: String, event: StandardSourceEvent) -> Unit) {
        listeners.add(listener)
    }

    /** 幂等表条目存活时长：覆盖主流 webhook 提供方的重试窗口（GitHub 最长约数小时，此处取保守下限）。 */
    private companion object {
        val SEEN_TTL_MS: Long = 15 * 60_000L
        const val SEEN_MAX_ENTRIES: Int = 4096
    }

    /**
     * 幂等表的键：**sourceId + event.id**。
     *
     * 旧实现只用 `event.id`：两个不同的 source 各自投递 id 为 `evt-1` 的事件时，
     * 第二条会被判成「第一条的重放」直接 ACK 掉 —— 一个源静默吞掉另一个源的事件，
     * 而且回的是 `200 duplicate=true`，发送方完全看不出异常。sourceId 是路由的
     * 一部分，不是事件内容的一部分，必须进键。
     */
    private fun idempotencyKey(sourceId: String, eventId: String): String =
        sourceId.trim().lowercase() + "\u0000" + eventId

    /**
     * 原子记账「这一投递已被台账确认」：返回 true = 本线程负责 fan-out，
     * false = 另一路已经确认过（并发重投），本次只回 200 duplicate。
     *
     * 调用点**必须在 sink 返回成功之后**；否则就是上面那个「两个请求互相以为
     * 对方落盘成功」的窗口。调用方若因 fan-out 容量拒收本条，必须调用
     * [forgetEvent] 撤账。命中旧账时即时判 TTL 过期（清扫只防御容量，不承担过期语义）。
     */
    private fun rememberEvent(key: String): Boolean {
        if (seenEvents.size >= SEEN_MAX_ENTRIES) sweepSeenEvents()
        val now = nowMillis()
        val existing = seenEvents.putIfAbsent(key, now) ?: return true
        if (now - existing > SEEN_TTL_MS) {
            return seenEvents.remove(key, existing) && seenEvents.putIfAbsent(key, now) == null
        }
        return false
    }

    /** 撤账：fan-out 未能入队时调用，让发送方的重试重新走一次台账判定。 */
    private fun forgetEvent(key: String) {
        seenEvents.remove(key)
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

        // 无**持久化**消费者的诚实出口：没有落盘出口还回 200，等于「ACK 了但没人
        // 消费」——本仓最贵的缺陷形态（发送侧一切正常、接收侧什么都没有）。宁可
        // 503 让发送方重试，也不收下注定石沉大海的事件。生产出口由宿主装配 Job
        // Table 时注入；listener 空不空不再是判据：它只是可选的异步 fan-out。
        val sink = durableSink
            ?: return WebhookResult.Rejected(
                503,
                "no-consumer",
                "no durable webhook consumer installed (job table not assembled)",
            )

        val normalizedSourceId = sourceId.trim().lowercase()
        val idemKey = idempotencyKey(normalizedSourceId, event.id)

        // **落盘前移到 ACK 之前**：回 200 的唯一前提是台账确认受理。内存幂等表
        // 不参与「收不收」，只在台账给出 Stored/Duplicate 之后给 fan-out 去重。
        // 旧顺序「先记住、再落盘」有个丢事件窗口：并发重投在第一条仍卡在 sink
        // 里时命中内存账，直接回 200 duplicate；第一条随后落盘失败，两个请求
        // 都以为对方成功，事件谁也没落。
        //
        // sink 抛错与返回 Failed 同等对待；两者都不许被当成「重放」——用成功码
        // 盖住一次落盘失败，比 503 更贵。`Duplicate` 是重启后的合法迟到重投，
        // 与失败不是一回事，所以单独一档。
        val persisted = try {
            sink(normalizedSourceId, event)
        } catch (t: Throwable) {
            WebhookPersistence.Failed("durable ingest threw " + (t.message ?: t.toString()))
        }
        if (persisted is WebhookPersistence.Failed) {
            return WebhookResult.Rejected(503, "persist-failed", persisted.reason)
        }

        // 台账确认之后才记内存账：原子拿到 true 的那一路负责 fan-out。
        // 判据是 **(sourceId, event.id)**（见 [idempotencyKey]）：event.id 来自
        // 投递头 / 载荷 id / body 哈希，不同 source 之间并不共享命名空间。
        val firstFanOut = rememberEvent(idemKey)
        if (persisted is WebhookPersistence.Duplicate || !firstFanOut) {
            return WebhookResult.Accepted(event, duplicate = true)
        }

        // 落盘之后再 fan-out。生产形态下持久化桥是唯一消费者、listener 为空，
        // 这条内存队列不会为已落盘的事件占位。仍保留 fan-out 的背压语义：
        // 队列满回 503 并撤账；发送方重试时台账会把它吸收成 duplicate——
        // 权威消费走 TriggerEventDrain 的表驱动循环，不靠这条队列。
        if (listeners.isNotEmpty() &&
            dispatchChannel.trySend(QueuedEvent(normalizedSourceId, event)).isFailure
        ) {
            forgetEvent(idemKey)
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
    // 有界读取，**先于验签**：验签需要完整 body，所以上限必须卡在读取这一步。
    // 旧写法 `exchange.requestBody.readBytes()` 对长度零限制 —— HttpServer 也不设
    // 请求体上限，于是一个超大 POST 就是一次不需要密钥的 OOM。
    val rawBody = readBoundedBody(exchange.requestBody, MAX_WEBHOOK_BODY_BYTES)
    if (rawBody == null) {
        respond(
            exchange,
            413,
            errorJson(
                "payload-too-large",
                "webhook body exceeds " + MAX_WEBHOOK_BODY_BYTES + " bytes",
            ),
        )
        return
    }
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

/**
 * 单条 webhook 请求体的上限。
 *
 * `HttpExchange.requestBody` 的长度是**没有上限**的，而旧代码直接 `readBytes()`
 * 整段进堆：一个几百 MB 的 POST 就是一次**不需要认证**的 OOM —— 验签发生在读完
 * body 之后，所以这条路径连密钥都不需要。
 *
 * 8 MiB 对 webhook 事件是宽裕的（GitHub 官方上限 25 MB，但单条事件实际远小于此），
 * 同时把内存占用钉死。改这个值要连带改 [SourceWebhookRouteTest] 的越界用例。
 */
internal const val MAX_WEBHOOK_BODY_BYTES: Int = 8 * 1024 * 1024

/**
 * 读一个请求体，超过 [limit] 字节返回 null（调用方回 413）。
 *
 * 分块读而不是 `readBytes()`：后者先把整段拉进堆再判断长度，判出来的时候
 * 内存已经付出去了。这里最多持有 limit + 一个缓冲区。
 *
 * 不看 `Content-Length`：它可以缺失、可以撒谎，真正的判据只能是「我读到了多少」。
 */
internal fun readBoundedBody(input: java.io.InputStream, limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buf = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
