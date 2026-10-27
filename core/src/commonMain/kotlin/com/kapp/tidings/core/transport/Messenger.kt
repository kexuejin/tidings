// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.createCoreLock
import com.kapp.tidings.core.withLock
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.DecodeResult
import com.kapp.tidings.core.protocol.DEFAULT_TIMINGS
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgKinds
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.core.protocol.Timings
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
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

    /**
     * 上一次失败的**原因**（task-63 诊断）：`connect-failed: …` / `closed-before-ack` / `timeout`。
     *
     * 为什么需要：真机上「回退 TCP 连不上」与「连上了但对端不回」在诊断里都只显示 `no-ack`，
     * 读者分不清 —— 而这两件事的修法完全不同（前者是端口/地址可达性，后者是对端进程）。
     */
    val lastError: String? get() = null
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
    private val transport: DatagramTransport,
    private val registry: PeerRegistry,
    private val selfNodeId: String,
    private val queueStore: QueueStore,
    private val dedupStore: DedupStore,
    private val timings: Timings = DEFAULT_TIMINGS,
    private val tcpFallback: TcpFallback? = null,
    /**
     * 积压队列的**定时重试**间隔（毫秒）。
     *
     * 为什么必须有它：[flushQueue] 原来只有两个触发点 —— `PeerEvent.Online` 那一次
     * 「离线→在线」转变，以及显式的 [requeue]。于是"对端在 registry 里一直是在线、
     * 但数据路径已经不通"的那段时间里，既没有转变可等、也没有任何定时器，
     * 积压就永远卡着（真机现场：`/v1/peers` 显示在线，`queue.log` 压着 28 条十几分钟）。
     *
     * 只重试"registry 认为在线"的对端：离线时 [sendAwaitAck] 会立刻返回（根本发不出去），
     * 而它真正上线时 `Online` 事件会自己触发一次补发 —— 两条路各管一段。
     */
    private val queueRetryIntervalMs: Long = DEFAULT_QUEUE_RETRY_INTERVAL_MS,
    /**
     * **该不该为这个 peer 排队。** 返回 false ⇒ 那一帧**不入队**（直接丢弃）。
     *
     * ## 为什么需要它
     *
     * 队列原先无条件入队。于是「目标设备早就不存在」的那类帧会**持续累积**：真机现场
     * `pending` 里 165 条是把群元数据发给一台已消失设备的（`05607a4e`），而且它还在
     * 某个群的成员表里 —— 每一轮群广播就给队列添一条，永远发不出去、永远不会被删。
     *
     * 单纯靠 `prune` 拦不住：那批条目总是**新鲜的**（最新一条只有十几分钟），TTL 永远
     * 碰不到它们。所以必须在**入队那一刻**就判断「这个对端还值得为它留消息吗」。
     *
     * null = 不做判断（测试与老调用点行为一字不变）。
     */
    private val acceptQueueFor: ((String) -> Boolean)? = null,
    /**
     * luciola 模式:消息**优先走传输面**(加密信道),而不是先试 legacy K1 长连接。
     *
     * 真机归因实验(2026-10-26):发现换成 luciola 后,消息仍先走 K1
     * (`outcome=sent-via-link`),加密信道只是配对握手用了 —— 传输没真正替换。
     * 打开本开关后,链路不再是第一选择(仍保留为失败回退),数据面才真正落在信道上。
     */
    private val preferTransport: Boolean = false,
) {

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 128)
    val incoming: SharedFlow<Envelope> = _incoming

    private val _status = MutableSharedFlow<MessageStatus>(extraBufferCapacity = 128)
    val status: SharedFlow<MessageStatus> = _status

    private val pendingLock = createCoreLock()
    private val pending = LinkedHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * 每个对端**正在进行的**一次排空：nodeId → 完成信号。
     *
     * 为什么不是一个 `HashSet` 去重：调用方（在线事件、15s 定时重试、「重发」）都要知道
     * "这一轮排空**结束了没有**"。旧写法「已经在排空就立刻 return」会让「重发」把一条
     * **正在被送出去**的消息报成 [SendOutcome.QUEUED] —— 用户看到"重发没反应"，
     * 而对端其实毫秒后收到了；队列与界面状态从此各说一套。
     */
    private val flushing = HashMap<String, CompletableDeferred<Unit>>()
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var queueRetryJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        // 同 Discovery：一条坏帧不能把可靠消息的收包协程带走（否则 ACK/投递全线停摆）。
        job = scope.launch { transport.incoming.collect { runCatching { onDatagram(it) } } }
        registry.addListener { event ->
            if (event is PeerEvent.Online) {
                scope.launch { flushQueue(event.nodeId) }
            }
        }
        // 事件驱动的补发只覆盖"离线→在线"那一次转变；这条定时重试覆盖"一直显示在线、
        // 但路径已经不通"的那一段（见 [queueRetryIntervalMs] 的 KDoc）。
        queueRetryJob = scope.launch {
            while (isActive) {
                delay(queueRetryIntervalMs)
                retryPendingQueues()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        queueRetryJob?.cancel()
        queueRetryJob = null
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

    /**
     * 长连接下行（K1）。连着就用它 —— lead 裁决是「长连接优先」，不是"UDP 失败后才用"：
     * 后者会继续被运营商对 UDP 的过滤拖累（task-21 现场）。失败/没连就立即退回原路径。
     */
    internal var linkDownlink: LinkDownlink? = null

    /**
     * 长连接的**冷却窗口**（task-63 追加）：某条链路刚 ACK 超时过，这段时间内不再先试它。
     *
     * 真机现场：`LinkClient.send` 要等 ACK 5s 才返回 false（链路条目还在、对端已经不回），
     * 而半死链路在 LinkClient 里最长要 45s 才被判 dead —— 这期间**每一次**发送都先付 5s，
     * 才轮到 UDP/TCP。连续几次报价就都停在 offering/queued 里出不去。
     * 这里用一个短冷却把这段重复代价压掉：冷却期内直接走 UDP/TCP（长连接恢复后自然再被选中）。
     */
    private val linkCooldownUntil = mutableMapOf<String, Long>()
    private val linkCooldownLock = createCoreLock()

    /**
     * **立刻重拨一条长连接**（task-64）。宿主接上（`LinkClient.redialNow`）；没接就不重拨。
     *
     * 为什么需要：真机实测蜂窝侧 **17879（明文 TCP 回退）不可达**、**17880（TLS 长连接）可达** ——
     * 链路半死时，唯一可能成功的路是**重拨一条新长连接**，而不是回退那条死路。
     */
    internal var linkRedial: (suspend (peerId: String) -> Boolean)? = null

    /**
     * 对端是否在**公网**（不是同段局域网）。宿主用 `isLanAddress` 判（与今天那条统一判据同源）。
     *
     * 为什么要有这条判据：**只对公网对端**跳过 17879 回退 —— 局域网里 17879 是通的，
     * 那里必须保留回退。别把 17879 硬编码成禁用：用户清掉路由器规则后它应当自然回到可用。
     */
    internal var peerIsPublic: (peerId: String) -> Boolean = { false }

    /** nodeId → 本冷却窗口里是否已经重拨过（保证「每次冷却最多一次」）。 */
    private val linkRedialAt = mutableMapOf<String, Long>()
    private val linkRedialLock = createCoreLock()

    /**
     * 最近一条可靠投递的逐端点诊断（task-62，只读、与 link* 同口径）。
     *
     * 存在的理由：`/v1/send-file` 的阻塞就发生在「逐端点等 ACK + TCP 兜底」这一段，
     * 而失败时调用方此前只拿到一句 `no-transfer-channel` —— 看不出**卡在哪个端点、
     * 等了多久**。这里记下最后一次投递的每一跳，供控制面的只读出口读出去。
     */
    @Volatile
    private var lastSendDiagnostics: ReliableSendDiagnostics? = null

    /** 最近一次可靠投递的诊断；没发过返回 null。 */
    fun lastReliableSend(): ReliableSendDiagnostics? = lastSendDiagnostics

    /** 链路这条路的结论：[sent] 非空 = 已送出；[tcpSkipped] 非空 = 别再回退 17879。 */
    private class LinkFirstOutcome(
        val sent: ReliableSendDiagnostics?,
        val tcpSkipped: String?,
        /** 这次**试过**重拨（哪怕没成）—— 诊断要如实写出来。 */
        val redialTried: Boolean = false,
        val redialMs: Long? = null,
    )

    /**
     * **链路优先**：先试现有链路；半死（或冷却期）且是**公网对端**时重拨一条新链路再试。
     *
     * 两条调用方共用（task-64）：`sendUserMessage`（消息）与 `sendReliableNoQueue`（报价的快速投递）。
     * 抽出来是因为**报价走的正是后者** —— 只在消息那条路上做重拨，报价就还是回退 17879（蜂窝上死的）。
     *
     * 判据（实测）：蜂窝侧 17880（TLS 长连接）可达、17879（明文回退）不可达。所以：
     *  · 只对公网对端跳过 17879（局域网它是通的，保留回退；也不硬编码禁用，用户清掉路由器规则即恢复）；
     *  · **每次冷却窗口最多重拨一次**（别把「半死」变成重拨风暴）；
     *  · 重拨成功 ⇒ 清冷却（否则一条**好的**链路会被继续挡在外面）。
     */
    private suspend fun tryLinkFirst(peerId: String, envelope: Envelope, started: Long): LinkFirstOutcome {
        val link = linkDownlink
        val cooled = linkCooldownLock.withLock { (linkCooldownUntil[peerId] ?: 0L) > started }
        val linkUsable = link != null && !cooled && link.isConnected(peerId)
        if (linkUsable && link!!.send(peerId, envelope)) {
            return LinkFirstOutcome(
                sent = ReliableSendDiagnostics(
                    peerId = peerId,
                    envelopeId = envelope.id,
                    outcome = "sent-via-link",
                    elapsedMs = nowMillis() - started,
                    endpoints = emptyList(),
                    tcpTried = false,
                    tcpOk = false,
                    tcpElapsedMs = 0L,
                    linkConnected = true,
                    peerOnline = registry.get(peerId)?.online == true,
                ),
                tcpSkipped = null,
            )
        }
        if (linkUsable) {
            // 链路在、但没 ACK ⇒ 半死。记一个短冷却，别让后续每一次发送都重复付那 5s。
            linkCooldownLock.withLock { linkCooldownUntil[peerId] = nowMillis() + LINK_COOLDOWN_MS }
            // 新冷却窗口 ⇒ 允许一次重拨。
            linkRedialLock.withLock { linkRedialAt.remove(peerId) }
        }
        // 「刚刚试过链路但没成」也算半死：这一次 cooled 还是 false（冷却刚设上）。
        val linkHalfDead = linkUsable
        if ((cooled || linkHalfDead) && peerIsPublic(peerId)) {
            val alreadyRedialed = linkRedialLock.withLock { (linkRedialAt[peerId] ?: 0L) > 0L }
            val redial = linkRedial
            if (!alreadyRedialed && redial != null) {
                linkRedialLock.withLock { linkRedialAt[peerId] = nowMillis() }
                val t0 = nowMillis()
                val dialed = runCatching { redial(peerId) }.getOrDefault(false)
                val redialMs = nowMillis() - t0
                if (dialed && link != null && link.isConnected(peerId) && link.send(peerId, envelope)) {
                    linkCooldownLock.withLock { linkCooldownUntil.remove(peerId) }
                    linkRedialLock.withLock { linkRedialAt.remove(peerId) }
                    return LinkFirstOutcome(
                        sent = ReliableSendDiagnostics(
                            peerId = peerId,
                            envelopeId = envelope.id,
                            outcome = "sent-via-redial",
                            elapsedMs = nowMillis() - started,
                            endpoints = emptyList(),
                            tcpTried = false,
                            tcpOk = false,
                            tcpElapsedMs = 0L,
                            redialTried = true,
                            redialMs = redialMs,
                            linkConnected = true,
                            peerOnline = registry.get(peerId)?.online == true,
                        ),
                        tcpSkipped = null,
                    )
                }
                // 重拨也没成 ⇒ 这条路（公网 + 半死）**不再回退 17879**：它在蜂窝上不可达。
                return LinkFirstOutcome(sent = null, tcpSkipped = "public-peer-17879-unreachable", redialTried = true, redialMs = redialMs)
            }
        }
        return LinkFirstOutcome(sent = null, tcpSkipped = null)
    }

    /** 发一条可靠消息：ACK 成功 → SENT；否则入队并标记对端离线 → QUEUED。 */
    /**
     * 发送通道归因(真机排障用):同一条消息可能走 长连接(LINK) / legacy TCP / 传输面
     * (luciola 模式下就是加密信道)。每个对端只在**通道发生变化**时打一行,
     * 避免刷屏;诊断页/日志据此能回答"消息到底走了哪条路"。
     */
    enum class SendChannel { LINK, TCP, TRANSPORT }

    private val lastSendChannel = HashMap<String, SendChannel>()

    private fun noteSendChannel(peerId: String, channel: SendChannel) {
        val changed = synchronized(lastSendChannel) {
            lastSendChannel.put(peerId, channel) != channel
        }
        if (changed) println("[send] peer=" + peerId.take(8) + " channel=" + channel)
    }

    suspend fun sendUserMessage(peerId: String, envelope: Envelope): SendOutcome {
        val started = nowMillis()
        // 链路优先 + 半死时重拨（见 tryLinkFirst）：消息与报价走**同一条**判定。
        // luciola 模式（preferTransport）下跳过这一步，直接走传输面（加密信道）；
        // 传输失败仍会在下面回退 TCP/队列，链路不再抢在加密信道前面。
        val linkTry = if (preferTransport) {
            LinkFirstOutcome(sent = null, tcpSkipped = null)
        } else {
            tryLinkFirst(peerId, envelope, started)
        }
        linkTry.sent?.let { sent ->
            noteSendChannel(peerId, SendChannel.LINK)
            lastSendDiagnostics = sent
            _status.tryEmit(MessageStatus(peerId, envelope.id, "sent"))
            return SendOutcome.SENT
        }
        val bytes = Codec.encodeToBytes(envelope)
        val attempts = ArrayList<ReliableEndpointAttempt>()
        var tcpTried = false
        var tcpOk = false
        var tcpElapsed = 0L
        // 失败原因（连不上 / 连上不回）—— 真机上这两种在旧诊断里都只是 no-ack，分不清。
        var tcpError: String? = null
        // 链路那条路的结论（含「这次试过重拨」与「别再回退 17879」）。
        val redialTried = linkTry.redialTried
        val redialMs = linkTry.redialMs
        val tcpSkipped = linkTry.tcpSkipped
        val tcpAllowed = tcpSkipped == null
        val ok = if (bytes.size > Protocol.UDP_MAX_PAYLOAD) {
            if (!tcpAllowed) {
                false
            } else {
                tcpTried = true
                val t0 = nowMillis()
                val r = sendViaTcp(peerId, envelope)
                tcpElapsed = nowMillis() - t0
                tcpOk = r
                tcpError = if (r) null else tcpFallback?.lastError
                r
            }
        } else {
            // 注意：`sendAwaitAck` **内部已经含一次 TCP 回退**（TS 同样只回退一次）。
            // 所以这里不再自己再调一次 sendViaTcp —— 那会让同一条消息被 TCP 送两遍，
            // 而且会把「内部回退成功」误标成 `sent-via-udp`（诊断说谎）。
            sendAwaitAck(peerId, envelope, bytes, attempts, allowTcpFallback = tcpAllowed)
        }
        // 「UDP 有没有被 ACK」才是判定走没走 TCP 的依据：一个端点都没 ack 却成功了 ⇒ 内部回退。
        val viaTcp = ok && attempts.none { it.acked }
        if (viaTcp) {
            tcpTried = true
            tcpOk = true
            tcpError = null
            tcpElapsed = nowMillis() - started
        } else if (!ok && tcpTried) {
            tcpError = tcpError ?: tcpFallback?.lastError
        }
        lastSendDiagnostics = ReliableSendDiagnostics(
            peerId = peerId,
            envelopeId = envelope.id,
            outcome = when {
                ok -> if (tcpOk) "sent-via-tcp" else "sent-via-udp"
                else -> "queued"
            },
            elapsedMs = nowMillis() - started,
            endpoints = attempts,
            tcpTried = tcpTried,
            tcpOk = tcpOk,
            tcpElapsedMs = tcpElapsed,
            tcpError = tcpError,
            redialTried = redialTried,
            redialMs = redialMs,
            tcpSkipped = tcpSkipped,
            linkConnected = linkDownlink?.isConnected(peerId) == true,
            peerOnline = registry.get(peerId)?.online == true,
        )
        if (ok) {
            noteSendChannel(peerId, if (tcpOk) SendChannel.TCP else SendChannel.TRANSPORT)
            _status.tryEmit(MessageStatus(peerId, envelope.id, "sent"))
            return SendOutcome.SENT
        }
        // **入队前的闸门**：目标设备若已永久消失，这一帧就不该占队列 —— 否则每一轮群广播
        // 都给一台不存在的设备添一条，永远发不出去、也永远新鲜到碰不到 TTL。
        // 见 `acceptQueueFor` 的 KDoc。注意**只拦这一处**：下面 `requeueOne` 那条是重放
        // 已经排过队的条目，拦它会丢用户的消息。
        if (acceptQueueFor?.invoke(peerId) != false) {
            queueStore.enqueue(envelope.id, peerId, Codec.encode(envelope), nowMillis())
        }
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
    suspend fun sendReliableNoQueue(peerId: String, envelope: Envelope, quick: Boolean = false): Boolean {
        // task-64：**与 [sendUserMessage] 同一条链路判定**（链路优先 + 半死时重拨）。
        // 为什么必须一致：**报价走的正是这条**（宿主把 `sendReliableQuick` 接到这里）——
        // 只在消息那条路上做重拨，报价就还是回退 17879（蜂窝上实测不可达）。
        val started = nowMillis()
        val linkTry = tryLinkFirst(peerId, envelope, started)
        linkTry.sent?.let { sent ->
            lastSendDiagnostics = sent
            return true
        }
        val bytes = Codec.encodeToBytes(envelope)
        val attempts = ArrayList<ReliableEndpointAttempt>()
        val allowTcp = linkTry.tcpSkipped == null
        val ok = if (bytes.size > Protocol.UDP_MAX_PAYLOAD) {
            if (allowTcp) sendViaTcp(peerId, envelope) else false
        } else {
            sendAwaitAck(peerId, envelope, bytes, attempts, quick = quick, allowTcpFallback = allowTcp)
        }
        val viaTcp = ok && attempts.none { it.acked }
        lastSendDiagnostics = ReliableSendDiagnostics(
            peerId = peerId,
            envelopeId = envelope.id,
            outcome = when {
                !ok -> "queued"
                viaTcp -> "sent-via-tcp"
                else -> "sent-via-udp"
            },
            elapsedMs = nowMillis() - started,
            endpoints = attempts,
            tcpTried = viaTcp || (!ok && allowTcp),
            tcpOk = viaTcp,
            tcpElapsedMs = if (viaTcp) nowMillis() - started else 0L,
            tcpError = if (!ok && allowTcp) tcpFallback?.lastError else null,
            redialTried = linkTry.redialTried,
            redialMs = linkTry.redialMs,
            tcpSkipped = linkTry.tcpSkipped,
            linkConnected = linkDownlink?.isConnected(peerId) == true,
            peerOnline = registry.get(peerId)?.online == true,
        )
        return ok
    }

    /**
     * 一个 peer 要按顺序试哪些端点：**当前地址在前**，然后是配对码存下来的候选池。
     *
     * 为什么要有候选兜底：registry 只存一个"当前地址"，而手机在「局域网 ↔ 移动网」之间切换
     * 时那个地址可能已经不可达；对端的另一个地址（公网 IPv6）明明通。没有这一条，
     * 一次失败就只能等它下一次被采纳（见 task-20 的真机现场）。
     */
    private fun endpointsFor(peerId: String): List<PeerEndpoint> {
        val record = registry.get(peerId) ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<PeerEndpoint>()
        for (endpoint in listOf(PeerEndpoint(record.ip, record.udpPort)) + registry.candidatesOf(peerId)) {
            if (endpoint.host.isBlank() || endpoint.port !in 1..65535) continue
            if (seen.add("${endpoint.host}:${endpoint.port}")) out += endpoint
        }
        return out
    }

    private suspend fun sendAwaitAck(
        peerId: String,
        envelope: Envelope,
        bytes: ByteArray,
        /** 逐端点留痕（task-62 诊断）；调用方不需要时传一个临时表。 */
        attempts: MutableList<ReliableEndpointAttempt>,
        /**
         * true = 只走最短退避（[quickSchedule]，每端点一次快试）。
         *
         * task-63：**报价**用这条 —— 蜂窝下长连接会抖，报价不该在请求/交互路径上等满
         * 「候选数 × 满退避」（真机实测 48s）；快速失败 + 由报价方自己排队重试才是对的形状。
         * 默认 false ⇒ 消息那条路逐字不变。
         */
        quick: Boolean = false,
        /**
         * 允许内部回退 TCP（17879）。task-64：公网 + 链路半死时**传 false** —— 那条路在蜂窝上
         * 不可达，回退它只是白等；选路交给「重拨链路」。默认 true ⇒ 其它调用方逐字不变。
         */
        allowTcpFallback: Boolean = true,
        /**
         * 即使"对端离线且没有候选地址"也**试着打当前端点一次**。
         *
         * 只给「网络路径变了」那一条用（见 [retryQueuedAfterNetworkChange]）：那个 offline
         * 判定是**上一张网**下作出的，换网之后它已经不是事实了。不放开的话，一个没有候选池
         * 的对端会永远停在这句 `return false` 上 —— 队列只涨不降，而用户看到的是"一直排队"。
         */
        tryCurrentEndpointEvenIfOffline: Boolean = false,
    ): Boolean {
        val record = registry.get(peerId) ?: return false
        val candidates = registry.candidatesOf(peerId)
        if (!record.online && candidates.isEmpty() && !tryCurrentEndpointEvenIfOffline) {
            // 旧行为：对端离线、又没有别的地址可试 —— 一个包都不发，直接入队。
            return false
        }
        val endpoints = endpointsFor(peerId)
        // 离线但有候选：**照样试**（task-21 ③）。"离线"只说明当前地址那条路最近没通，
        // 不代表候选也不通 —— 跨网时正是候选（公网地址）通、当前地址（局域网地址）不通。
        // 每个端点只试一次（完整退避留给在线路径），试通就 setActiveEndpoint 把它翻回在线。
        val schedule = if (quick) quickSchedule() else if (record.online) timings.ackRetrySchedule else quickSchedule()
        for (endpoint in endpoints) {
            val t0 = nowMillis()
            val acked = sendUdpAwaitAck(
                peerId = peerId,
                envelope = envelope,
                bytes = bytes,
                host = endpoint.host,
                port = endpoint.port,
                schedule = schedule,
                requireOnline = record.online,
            )
            attempts += ReliableEndpointAttempt(
                host = endpoint.host,
                port = endpoint.port,
                acked = acked,
                elapsedMs = nowMillis() - t0,
            )
            if (acked) {
                registry.setActiveEndpoint(peerId, endpoint.host, endpoint.port)
                return true
            }
        }
        // UDP 在所有端点上都没 ACK：回退 TCP 一次（TS 同样只回退一次），也按候选顺序试。
        // UDP 在所有端点上都没 ACK：回退 TCP 一次（TS 同样只回退一次），也按候选顺序试。
        if (!allowTcpFallback) return false
        return sendViaTcp(peerId, envelope)
    }

    /** 离线兜底用的最短退避：只试一次，别让"发一条给离线联系人"等满 7 秒。 */
    private fun quickSchedule(): List<Long> = timings.ackRetrySchedule.take(1).ifEmpty { listOf(ACK_GRACE_MS) }

    /**
     * 在**一个**端点上跑一次 UDP 重试退避。
     *
     * @param schedule 退避序列。在线走完整 [Timings.ackRetrySchedule]，离线兜底只走 [quickSchedule]。
     * @param requireOnline true 时对端一旦被判离线就中止（在线路径的旧语义）；
     *   false 用于"离线但还有候选要试"的那条路 —— 否则第一轮就 return，候选永远试不到。
     */
    private suspend fun sendUdpAwaitAck(
        peerId: String,
        envelope: Envelope,
        bytes: ByteArray,
        host: String,
        port: Int,
        schedule: List<Long>,
        requireOnline: Boolean,
    ): Boolean {
        val key = "$peerId|${envelope.id}"
        for (timeoutMs in schedule) {
            val record = registry.get(peerId) ?: return false
            if (requireOnline && !record.online) return false
            val deferred = CompletableDeferred<Boolean>()
            val prev = pendingLock.withLock {
                val p = pending.remove(key)
                pending[key] = deferred
                p
            }
            prev?.complete(false)
            runCatching { transport.send(bytes, host, port) }
            // try/finally：调用方**取消**这次投递时（报价的单次尝试有上限）也必须把
            // pending 里的占位摘掉，否则每取消一次就漏一条（长跑泄漏）。
            val acked = try {
                withTimeoutOrNull(timeoutMs + ACK_GRACE_MS) { deferred.await() }
            } finally {
                pendingLock.withLock { pending.remove(key) }
            }
            if (acked == true) return true
        }
        return false
    }

    /**
     * 发一条**不可靠、不入队**的 TCP 帧：不发也不重试，失败就是失败。
     *
     * 为身份恢复 L2 的 `recovery-data` 而加（Q5：fire-and-forget + 不进可靠队列）。
     * 与 [sendViaTcp] 共用一条路（长连接优先，失败退 TCP 回退）；TCP 帧的
     * 传输层 `msg-ack` 对方永远会回（Link 泵的纪律），所以这里的返回值只说明
     * "这一帧送到了对方传输层"，不说明对方应用层认不认这个类型 —— 旧端会把
     * 未知类型静默丢掉，发送方无从得知，这正是 fire-and-forget 的边界。
     */
    suspend fun sendUnreliableTcp(peerId: String, envelope: Envelope): Boolean = sendViaTcp(peerId, envelope)

    private suspend fun sendViaTcp(peerId: String, envelope: Envelope): Boolean {
        val fallback = tcpFallback ?: return false
        val record = registry.get(peerId) ?: return false
        // TCP 端口来自对端的 profile（一个节点只有一个），地址才按候选顺序换。
        val tcpPort = record.profile.tcpPort
        for (endpoint in endpointsFor(peerId)) {
            val ok = runCatching { fallback.sendAndAwaitAck(endpoint.host, tcpPort, envelope) }
                .getOrDefault(false)
            if (ok) {
                registry.setActiveEndpoint(peerId, endpoint.host, endpoint.port)
                return true
            }
        }
        return false
    }

    /**
     * 补发一个 peer 的队列。**保序**：第一个未 ACK 就停下，剩下的等下次上线再发。
     */
    suspend fun flushQueue(
        peerId: String,
        /**
         * 网络路径刚变过 ⇒ 上一张网下"对端离线、没有候选"的结论作废，允许打当前端点一次。
         * 见 [retryQueuedAfterNetworkChange]。
         */
        tryCurrentEndpointEvenIfOffline: Boolean = false,
    ): Boolean {
        // 已经有人在排空这个对端 ⇒ **等它结束**再回答，而不是立刻回一个假的"还在排队"
        // （「重发」按这个返回值决定界面状态，见 [flushing] 的 KDoc）。
        val inFlight = pendingLock.withLock { flushing[peerId] }
        if (inFlight != null) {
            inFlight.await()
            return queueStore.listByPeer(peerId).isEmpty()
        }
        val done = CompletableDeferred<Unit>()
        pendingLock.withLock { flushing[peerId] = done }
        try {
            for (item in queueStore.listByPeer(peerId)) {
                // **必须用 TCP 的上限解码，不能用 `Codec.decode`（UDP 那套）**。
                // 队列里存的是**已经发过一次**的信封，重放走的是链路 / TCP（见下面的分支），
                // 不是数据报。用 UDP 的 800/4096 去卡它，会把一条 4239 字节的群消息判成
                // 坏帧然后 `queueStore.remove` —— 比「发不出去」更糟：**它被悄悄删了**，
                // 用户永远等不到，日志里也只有一次 decode 失败。
                val decoded = Codec.decodeTcp(item.envelopeJson)
                if (decoded !is DecodeResult.Ok) {
                    queueStore.remove(item.msgId, peerId)
                    continue
                }
                val original = decoded.env
                val envelope = original.copy(payload = withResendFlag(original.payload))
                val bytes = Codec.encodeToBytes(envelope)
                val link = linkDownlink
                val ok = if (link != null && link.isConnected(peerId) && link.send(peerId, envelope)) {
                    true
                } else if (bytes.size > Protocol.UDP_MAX_PAYLOAD) {
                    sendViaTcp(peerId, envelope)
                } else {
                    sendAwaitAck(
                        peerId, envelope, bytes, ArrayList(),
                        tryCurrentEndpointEvenIfOffline = tryCurrentEndpointEvenIfOffline,
                    )
                }
                if (!ok) {
                    // 补发失败 = 这条数据路径现在不通。必须把对端标离线（与
                    // [sendUserMessage] 失败时同一条纪律）：
                    //  · 在线状态才是诚实的（否则 /v1/peers 一直显示在线，用户以为没问题）；
                    //  · 对端下一次 presence/entry 会产生一次「离线→在线」转变，补发被重新触发
                    //    —— 这正是"在线但卡住"能自愈的关键。
                    // 不标的话，flush 失败后 online 永远留在 true，再也不会有转变，
                    // 队列就卡到天荒地老（真机 28 条十几分钟的现场）。
                    registry.markOffline(peerId)
                    return false
                }
                queueStore.remove(item.msgId, peerId)
                _status.tryEmit(MessageStatus(peerId, envelope.id, "sent"))
            }
            // 判据是**队列真的空了**，不是"这一轮我发了几个"：中途有人 requeue 也算如实。
            return queueStore.listByPeer(peerId).isEmpty()
        } finally {
            pendingLock.withLock { flushing.remove(peerId) }
            // 叫醒所有在等这一轮结束的调用方（包括「重发」那条）。
            done.complete(Unit)
        }
    }

    /**
     * 给「有积压」的对端各补发一次 —— [queueRetryIntervalMs] 的落点。
     *
     * 两条都要覆盖：
     *  · **在线**的：治"对端一直显示在线、但数据路径已经不通"（既等不到转变、也没人重试）；
     *  · **离线但有候选地址**的：治"跨网时被上一次失败补发标成 offline 之后，再也没有人把它
     *    翻回 online"（task-21 ②）。presence 只有发往候选那一路，而那条反向路径在运营商侧
     *    可能被过滤 —— 只能靠我们主动按候选再试一次（见 [sendAwaitAck] 的离线分支）。
     *
     * 失败时 [flushQueue] 会把对端标离线，所以这条循环不会把在线状态留在假的 true 上；
     * 离线且**没有**候选的对端仍然一个包都不发（旧语义）。
     */
    private suspend fun retryPendingQueues() {
        for (peerId in queueStore.countByPeer().keys) {
            val online = registry.get(peerId)?.online == true
            if (online || registry.candidatesOf(peerId).isNotEmpty()) flushQueue(peerId)
        }
    }

    /**
     * **网络路径变了**：所有带积压的对端各重试一次，**包括被判离线的那些**。
     *
     * ## 为什么这条不能靠 15s 的定时重试
     *
     * [retryPendingQueues] 只碰 `online || 有候选地址` 的对端。而一次失败的发送会
     * `markOffline`，离线 + 没有候选池（配对码只给了一个地址的常见情形）之后，
     * [sendAwaitAck] 里那句 `!online && candidates.isEmpty() ⇒ return false` 就让它
     * **一个包都不发**。那个 offline 判定是**上一张网**下作出的：换网之后，同一个地址可能
     * 又通了，而我们已经不再尝试 —— 真机上就是「消息一直排队、界面上对方一直离线」。
     *
     * 所以换网是一个**独立的触发点**：它不改变任何「在线」结论（那不是我们能宣布的），
     * 只是把「这张网下没试过」这件事重新试一次。失败照旧 `markOffline`，队列里一条不丢。
     *
     * 与 [retryPendingQueues] 的分工：那条是稳态下的定期重试，这条只在**网络变化**时跑。
     */
    suspend fun retryQueuedAfterNetworkChange() {
        for (peerId in queueStore.countByPeer().keys) {
            flushQueue(peerId, tryCurrentEndpointEvenIfOffline = true)
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
        // 与 [flushQueue] 同一条理由：落盘的信封是「重放素材」，按 TCP 上限读。
        // 用 UDP 的上限读会把长群消息判成没救（`return false` ⇒ 调用方标 failed）。
        val decoded = Codec.decodeTcp(envelopeJson)
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

    /**
     * 队列里某条消息的整行（信封 JSON + 原始入队时刻）；不在队列里返回 null。
     *
     * 「重发」要先问它：队列里那一帧是当初真的发过的，比任何重建都忠实 ——
     * 重建只能靠调用方交回来的字段，而队列里有整条信封。**入队时刻也要一起拿**：
     * 重放是对同一条的补发，不该把它排到队尾、也不该把 TTL 从今天重新算。
     */
    fun queuedMessage(peerId: String, msgId: String): QueuedMessage? =
        queueStore.listByPeer(peerId).firstOrNull { it.msgId == msgId }

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
        /**
         * 链路 ACK 超时后的冷却（task-63）。
         *
         * 15s 是权衡：太短 ⇒ 半死链路期间仍在反复付 5s 的 ACK 超时；
         * 太长 ⇒ 真正重建好的链路要等它过期才被重新选中（好在换网/链路恢复都会 nudge 报价，
         * 而消息本来就会走 UDP/TCP 送出去，不会因为跳过链路而丢）。
         */
        const val LINK_COOLDOWN_MS: Long = 15_000L
        /** 给 ACK 留一点网络回程余量，避免本地回环上卡在边界。 */
        const val ACK_GRACE_MS = 120L

        /**
         * 积压队列的默认定时重试间隔。
         *
         * 取 15s 是因为一次失败的补发要先把 UDP 退避（1+2+4s）走完、再回退 TCP（7s），
         * 大约 14s —— 比它短只会在上一次还没跑完时又排一次（`flushing` 会挡住，纯属浪费）。
         */
        const val DEFAULT_QUEUE_RETRY_INTERVAL_MS: Long = 15_000
    }
}

/** 一次可靠投递里对**某个端点**的一次尝试（task-62 只读诊断）。 */
data class ReliableEndpointAttempt(
    val host: String,
    val port: Int,
    val acked: Boolean,
    val elapsedMs: Long,
)

/**
 * 最近一条可靠投递的逐端点诊断（task-62，与 `link*` 同口径：只读、无副作用）。
 *
 * 为什么要有它：`/v1/send-file` 的阻塞就发生在「逐端点等 ACK + TCP 兜底」这一段，
 * 而失败时调用方此前只拿到一句 `no-transfer-channel` —— 看不出**卡在哪个端点、等了多久**。
 * 有了它，控制面的只读出口（`/v1/transfers`）就能把「为什么没送到」如实说出来。
 *
 * ## `linkConnected` 与 `peerOnline` 是**两件不同的事**（task-62 真机证据）
 *
 * 真机上出现过 `link=true` 而 `peerOnline=false` **同时成立**，读的人会以为自相矛盾：
 *
 *  · [linkConnected] 说的是 **K1 长连接**（TLS/TCP）这条承载此刻通不通；
 *  · [peerOnline] 说的是 **registry 的 UDP/入站视角** —— 收到过它的 presence/帧为真；
 *    最近一次可靠投递失败后会被 `markOffline` 置假。
 *
 * 两者同时成立并不矛盾：手机在蜂窝上时长连接可能还活着（所以「长连接优先」那条路能成），
 * 而 UDP 那条路已经很久没有回包（所以 online=false）。真机里那一次成功的投递正是
 * `sent-via-link` + `peerOnline=false`。**别把 `peerOnline=false` 读成「对端不在」** ——
 * 它只否证 UDP 那条路。
 */
data class ReliableSendDiagnostics(
    val peerId: String,
    val envelopeId: String,
    /** `sent-via-link` / `sent-via-udp` / `sent-via-tcp` / `queued`。 */
    val outcome: String,
    val elapsedMs: Long,
    val endpoints: List<ReliableEndpointAttempt>,
    val tcpTried: Boolean,
    val tcpOk: Boolean,
    val tcpElapsedMs: Long,
    /** 回退 TCP 失败的原因（成功时为 null）：见 [TcpFallback.lastError]。 */
    val tcpError: String? = null,
    /** 这次是否**重拨过链路**（task-64：半死时重拨 17880，而不是回退 17879）。 */
    val redialTried: Boolean = false,
    /** 重拨耗时（毫秒）；没重拨是 null。 */
    val redialMs: Long? = null,
    /** 这次**故意跳过**了 TCP 回退的原因（task-64）；没跳过是 null。 */
    val tcpSkipped: String? = null,
    val linkConnected: Boolean,
    val peerOnline: Boolean,
) {
    /** 一行摘要：直接进响应/日志（`端点 host:port acked/耗时`）。 */
    fun summary(): String {
        val eps = if (endpoints.isEmpty()) {
            "-"
        } else {
            endpoints.joinToString(",") { it.host + ":" + it.port + (if (it.acked) " acked " else " no-ack ") + it.elapsedMs + "ms" }
        }
        val tcpText = when {
            tcpTried -> (if (tcpOk) "ok " else "fail(" + (tcpError ?: "?") + ") ") + tcpElapsedMs + "ms"
            tcpSkipped != null -> "skipped(" + tcpSkipped + ")"
            else -> "not-tried"
        }
        // 重拨与「跳过回退」都写出来（task-64）：读的人要知道这次是重拨了链路、还是回退 TCP。
        val redialText = if (redialTried) "tried " + (redialMs ?: 0L) + "ms" else "no"
        return "outcome=" + outcome + " elapsed=" + elapsedMs + "ms endpoints=[" + eps + "]" +
            " tcp=" + tcpText +
            " redial=" + redialText +
            " link=" + linkConnected + " peerOnline=" + peerOnline
    }
}
