// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.nowMillis
import app.relay.core.createCoreLock
import app.relay.core.withLock
import app.relay.core.protocol.Codec
import app.relay.core.protocol.DecodeResult
import app.relay.core.protocol.DEFAULT_TIMINGS
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgKinds
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.Timings
import app.relay.core.protocol.makeEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** 发送结果。`QUEUED` 只表示"已暂存、等对端上线补发"，不表示已读。 */
enum class SendOutcome { SENT, QUEUED }

/** 出站状态事件，供 UI / 控制面展示。 */
data class MessageStatus(
    val peerId: String,
    val msgId: String,
    val status: String,
)

/**
 * 大于 UDP 单包上限的文本走 TCP 的抽象。
 *
 * 抽成接口是为了让 UDP 主路径可以在没有 TCP 实现、也不需要真实网络的情况下单测。
 */
interface TcpFallback {
    suspend fun sendAndAwaitAck(host: String, port: Int, envelope: Envelope): Boolean
}

/**
 * 可靠投递 + 离线补发。逐条对齐 TS `src/main/net/messenger.ts`。
 *
 * 三个容易做错的点：
 * - **ACK 的 pending 键是 `peerId|msgId`**，ACK 必须来自登记地址（`sendAwaitAck` 每次重试
 *   都重新读 registry 拿最新地址）；
 * - **补发保序**：`flushQueue` 遇到第一个未 ACK 就停下，不跳过它继续发后面的；
 * - **`attempts` 从不更新**，所以没有"最大重试次数"，失败只在 TTL / 每 peer 上限剪枝时
 *   以"延迟失败"的形式暴露。
 */
class Messenger(
    private val transport: UdpTransport,
    private val registry: PeerRegistry,
    private val selfNodeId: String,
    private val queueStore: QueueStore,
    private val dedupStore: DedupStore,
    private val timings: Timings = DEFAULT_TIMINGS,
    private val tcpFallback: TcpFallback? = null,
) {

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 128)
    val incoming: SharedFlow<Envelope> = _incoming

    private val _status = MutableSharedFlow<MessageStatus>(extraBufferCapacity = 128)
    val status: SharedFlow<MessageStatus> = _status

    private val pendingLock = createCoreLock()
    private val pending = LinkedHashMap<String, CompletableDeferred<Boolean>>()
    private val flushing = HashSet<String>()
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        job = scope.launch { transport.incoming.collect { onDatagram(it) } }
        registry.addListener { event ->
            if (event is PeerEvent.Online) {
                scope.launch { flushQueue(event.nodeId) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope = null
        val toCancel = pendingLock.withLock {
            val list = pending.values.toList()
            pending.clear()
            list
        }
        for (deferred in toCancel) deferred.cancel()
    }

    private suspend fun onDatagram(datagram: ReceivedDatagram) {
        val decoded = Codec.decode(datagram.bytes)
        if (decoded !is DecodeResult.Ok) return
        val envelope = decoded.env
        if (envelope.from == selfNodeId) return

        if (envelope.type == MsgTypes.ACK) {
            settleAck(envelope)
            return
        }
        if (envelope.type in MsgTypes.RELIABLE) {
            handleReliable(envelope, datagram)
        }
    }

    private fun settleAck(envelope: Envelope) {
        val payload = envelope.payload as? JsonObject ?: return
        val ackFor = (payload["ackFor"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val deferred = pendingLock.withLock { pending.remove("${envelope.from}|$ackFor") }
        deferred?.complete(true)
    }

    /**
     * 可靠帧**无条件先回 ACK（重复帧也回）**，再去重、再投递。
     * 先回 ACK 是因为对端只认 ACK 才停止重试；重复投递由去重表挡住。
     *
     * 覆盖全部 `RELIABLE` 类型，不只是 `msg` —— 只处理 `msg` 会让 `file-ctl` / `group` /
     * `avatar` / `update` / `share` 既不回 ACK 也不投递，对端会一直重传到队列剪枝为止。
     * 逐条对齐 TS `messenger.ts` 的 `handle()`：
     * - 已在线节点换源地址 → `touch` 返回 null → 整帧丢弃（地址漂移必须带完整 profile 重握手）；
     * - `screen` 例外：授权绑定原有地址，不能先 `touch` 把不可信来源变成当前地址。
     */
    private suspend fun handleReliable(envelope: Envelope, datagram: ReceivedDatagram) {
        if (envelope.type == MsgTypes.SCREEN) {
            // 投屏信令必须来自已握手的原地址：地址不符就整帧不作答（连 ACK 都不回，
            // 对端的重试会自己停下来），也不能先 `touch` 把不可信来源变成当前地址。
            val known = registry.get(envelope.from)
            if (known == null || known.ip != datagram.host) return
        } else if (registry.get(envelope.from) != null) {
            if (registry.touch(envelope.from, datagram.host, datagram.port, null, envelope.ts) == null) {
                return
            }
        }
        val ack = makeEnvelope(
            MsgTypes.ACK,
            selfNodeId,
            Codec.ackPayload(envelope.id),
        )
        runCatching {
            transport.send(Codec.encodeToBytes(ack), datagram.host, datagram.port)
        }
        if (dedupStore.has(envelope.id, envelope.from)) return
        dedupStore.add(envelope.id, envelope.from, nowMillis())
        _incoming.tryEmit(envelope)
    }

    /**
     * TCP 监听器收到 `msg` 帧时的入口（协议 §8：>800 字节文本走 TCP）。
     *
     * UDP 与 TCP 是同一套「去重 → 投递」纪律，只是 ACK 由 TCP 帧的 `msg-ack` 承担，
     * 所以这里不再回 UDP ACK。返回 true 表示已收下（含重复帧——重复也要回 `msg-ack`，
     * 否则对端会一直重试）。
     *
     * 已知节点换 IP 时拒绝：地址漂移必须带完整 profile 重新握手，与 UDP 路径同规矩。
     */
    fun acceptTcpMessage(envelope: Envelope, remoteAddress: String): Boolean {
        if (envelope.from == selfNodeId) return false
        val known = registry.get(envelope.from)
        if (known != null && known.ip != remoteAddress) return false
        if (dedupStore.has(envelope.id, envelope.from)) return true
        dedupStore.add(envelope.id, envelope.from, nowMillis())
        _incoming.tryEmit(envelope)
        return true
    }

    /** 发一条可靠消息：ACK 成功 → SENT；否则入队并标记对端离线 → QUEUED。 */
    suspend fun sendUserMessage(peerId: String, envelope: Envelope): SendOutcome {
        val bytes = Codec.encodeToBytes(envelope)
        val ok = if (bytes.size > Protocol.UDP_MAX_PAYLOAD) {
            sendViaTcp(peerId, envelope)
        } else {
            sendAwaitAck(peerId, envelope, bytes)
        }
        if (ok) {
            _status.tryEmit(MessageStatus(peerId, envelope.id, "sent"))
            return SendOutcome.SENT
        }
        queueStore.enqueue(envelope.id, peerId, Codec.encode(envelope), nowMillis())
        registry.markOffline(peerId)
        _status.tryEmit(MessageStatus(peerId, envelope.id, "queued"))
        return SendOutcome.QUEUED
    }

    /** 发一条尽力而为的帧（无 ACK、无重试、不改在线状态）。presence 之类走这条。 */
    suspend fun sendBestEffort(peerId: String, envelope: Envelope) {
        val record = registry.get(peerId) ?: return
        runCatching { transport.send(Codec.encodeToBytes(envelope), record.ip, record.udpPort) }
    }

    /**
     * 发一条**可靠但不排队**的帧：ACK（或 TCP 兜底）成功算成功，失败就失败，**不入队**。
     *
     * 存在的理由是 `nudge`：协议文档 §8 写它「发送走可靠 ACK；若对端无响应则失败且
     * **不进入离线补发队列**」。既不能走 [sendUserMessage]（失败会入队，等于把一次
     * 即时提醒变成隔夜骚扰），也不能走 [sendBestEffort]（没有 ACK，界面无法知道戳没戳到）。
     *
     * 与 [sendUserMessage] 的另一处差别：失败时**不改对端在线状态** —— 戳不到不等于离线，
     * 对端可能只是这一帧丢了。
     */
    suspend fun sendReliableNoQueue(peerId: String, envelope: Envelope): Boolean {
        val bytes = Codec.encodeToBytes(envelope)
        return if (bytes.size > Protocol.UDP_MAX_PAYLOAD) {
            sendViaTcp(peerId, envelope)
        } else {
            sendAwaitAck(peerId, envelope, bytes)
        }
    }

    private suspend fun sendAwaitAck(peerId: String, envelope: Envelope, bytes: ByteArray): Boolean {
        val key = "$peerId|${envelope.id}"
        var attempted = false
        for (timeoutMs in timings.ackRetrySchedule) {
            val record = registry.get(peerId) ?: break
            if (!record.online) break
            attempted = true
            val deferred = CompletableDeferred<Boolean>()
            val prev = pendingLock.withLock {
                val p = pending.remove(key)
                pending[key] = deferred
                p
            }
            prev?.complete(false)
            runCatching { transport.send(bytes, record.ip, record.udpPort) }
            val acked = withTimeoutOrNull(timeoutMs + ACK_GRACE_MS) { deferred.await() }
            pendingLock.withLock { pending.remove(key) }
            if (acked == true) return true
        }
        if (!attempted) return false
        // UDP 重试耗尽后回退 TCP 一次（TS 同样只回退一次）。
        return sendViaTcp(peerId, envelope)
    }

    private suspend fun sendViaTcp(peerId: String, envelope: Envelope): Boolean {
        val fallback = tcpFallback ?: return false
        val record = registry.get(peerId) ?: return false
        return fallback.sendAndAwaitAck(record.ip, record.profile.tcpPort, envelope)
    }

    /**
     * 补发一个 peer 的队列。**保序**：第一个未 ACK 就停下，剩下的等下次上线再发。
     */
    suspend fun flushQueue(peerId: String) {
        val added = pendingLock.withLock { flushing.add(peerId) }
        if (!added) return
        try {
            for (item in queueStore.listByPeer(peerId)) {
                val decoded = Codec.decode(item.envelopeJson)
                if (decoded !is DecodeResult.Ok) {
                    queueStore.remove(item.msgId, peerId)
                    continue
                }
                val original = decoded.env
                val envelope = original.copy(payload = withResendFlag(original.payload))
                val bytes = Codec.encodeToBytes(envelope)
                val ok = if (bytes.size > Protocol.UDP_MAX_PAYLOAD) {
                    sendViaTcp(peerId, envelope)
                } else {
                    sendAwaitAck(peerId, envelope, bytes)
                }
                if (!ok) return
                queueStore.remove(item.msgId, peerId)
                _status.tryEmit(MessageStatus(peerId, envelope.id, "sent"))
            }
        } finally {
            pendingLock.withLock { flushing.remove(peerId) }
        }
    }

    /**
     * 把一条**原封不动的**已编码信封重新塞回队列。
     *
     * [QueueStore] 默认是进程内的（[InMemoryQueueStore]），应用重启即空；但上层为了
     * 让"离线排队"在重启后仍然成立，会把当时那条信封的 JSON 一起落盘，启动时再喂回来。
     * 这里**不重建信封** —— 重建会换一个新的 `Envelope.id`，万一原信已经送达只是 ACK 丢了，
     * 对方就会收到两条。重放同一条则由对方的 `dedupStore` 挡掉重复并照常回 ACK。
     *
     * @param createdAt 最初入队的时刻。沿用它而不是现在，`listByPeer` 才能保持原来的发送顺序，
     *   `prune` 的 TTL 也才从最初排队时算起。
     * @return 信封能否解码；`false` 表示这份落盘数据已经没救（调用方应把它标成 failed）。
     */
    suspend fun requeue(peerId: String, envelopeJson: String, createdAt: Long): Boolean {
        val decoded = Codec.decode(envelopeJson)
        if (decoded !is DecodeResult.Ok) return false
        val id = decoded.env.id
        // enqueue 以 "peerId|msgId" 为键天然幂等；先看一眼只是为了不让重放把行"刷新"成新行。
        if (queueStore.listByPeer(peerId).none { it.msgId == id }) {
            queueStore.enqueue(id, peerId, envelopeJson, createdAt)
        }
        // 恢复时对端可能**已经**在线了（对方的 Online 事件早于本次重注入），所以显式补一次 flush，
        // 不能只靠事件驱动。
        if (registry.get(peerId)?.online == true) flushQueue(peerId)
        return true
    }

    /** 剪枝队列与去重表；返回被剪掉的队列项（上层据此标 failed）。 */
    fun prune(now: Long = nowMillis()): List<PrunedMessage> {
        val pruned = queueStore.prune(now, timings.queueTtl, timings.queueMaxPerPeer)
        dedupStore.prune(now, timings.dedupTtl)
        return pruned
    }

    fun pendingCount(): Int = queueStore.countByPeer().values.sum()

    /**
     * 每个对端各自的待发条数。
     *
     * UI 需要"这台电脑有 3 条待发"这种**分对端**的数字，而不是一个总数 —— 总数会把
     * "给 A 的积压"算到 B 头上。`attempts` 从不更新，所以这里只可能减少（ACK 后出队）
     * 或被 TTL/上限剪枝，没有"重试次数"可显示。
     */
    fun pendingByPeer(): Map<String, Int> = queueStore.countByPeer()

    /**
     * 全部待发项（含信封原文与入队时刻），供上层在启动时重建「已排队」的展示态。
     *
     * 这是 `MessageUi.outgoingEnvelope` 退休之后唯一的凭据来源 —— 上层不再自己
     * 存一份信封，而是问队列要。
     */
    fun pendingAll(): List<QueuedMessage> = queueStore.pendingAll()

    private fun withResendFlag(payload: JsonElement): JsonElement {
        if (payload !is JsonObject) return payload
        val kind = (payload["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (kind != MsgKinds.TEXT && kind != MsgKinds.GROUP_TEXT) return payload
        return buildJsonObject {
            for ((key, value) in payload) put(key, value)
            put("resend", JsonPrimitive(true))
        }
    }

    private companion object {
        /** 给 ACK 留一点网络回程余量，避免本地回环上卡在边界。 */
        const val ACK_GRACE_MS = 120L
    }
}
