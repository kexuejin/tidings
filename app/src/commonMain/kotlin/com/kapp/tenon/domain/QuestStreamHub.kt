// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.CodecWire
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.Limits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一个 quest 日志流在界面上的状态（[QuestStreamHub.streamStateOf]）。
 *
 * - [LOCAL]：任务就跑在本机（作战室在桌面端），日志直接读本地环形缓冲，不经协议。
 * - [STREAMING]：订阅已建立（`stream-sub` 得到 ACK），实时块正在进来。
 * - [UNAVAILABLE]：找不到宿主、链路不通或对端是旧版（sub 的 ACK 窗口落空）——
 *   界面如实显示「日志流不可用」，绝不假装在流。
 * - [IDLE]：没有意图也没有订阅。
 */
enum class QuestStreamState { IDLE, LOCAL, STREAMING, UNAVAILABLE }

/**
 * [QuestStreamState.UNAVAILABLE] 的原因细分（面板文案据此如实）：
 * - [NO_HOST]：解析不到执行体宿主（questPairNodes 为空/对端不在节点表）；
 * - [HOST_UNREACHABLE]：宿主在册但通道不可达（离线/旧版——sub 探测窗落空）。
 */
enum class QuestStreamUnavailableReason { NO_HOST, HOST_UNREACHABLE }

/**
 * 支线日志流枢纽（Phase 1「轻量流式日志信道」）—— **一个类、两个角色**，与
 * [RelayController] 在手机/桌面共用的纪律一致：
 *
 * - **宿主角色**（quest 在本机跑）：[acceptLine] 吃进 CLI 驱动的原始行（stderr 并入、
 *   含事件映射器认不出的行），按 [DEFAULT_FLUSH_INTERVAL_MS] 合批、批序号 `seq`
 *   单调、每任务保留最近 [MAX_RING_LINES] 行的环形缓冲；`stream-sub` 进来先补存量
 *   （`seq > sinceSeq`）再接实时，`stream-cancel` 只摘除**该订阅者**一路，
 *   最后一个订阅者退订才停泵。
 * - **订阅角色**：维护「期望集」（作战室开着 = 想看）与「已订阅集」的对账
 *   （[reconcile]）——进作战室发 sub、关弹窗/切后台发 cancel；收到的块进订阅端
 *   环形缓冲供 UI 读取。
 *
 * ## 投递语义（为什么发帧一律「不入队、只走长连接」）
 *
 * 发送侧 lambda 由 [RelayController] 注入，背后是 `NodeOutbound.sendStreamFrame`
 * （`LinkDownlink.send`，加密长连接直达）。ACK 不到 = 链路不通或对端旧版 ——
 * 过期的订阅/积压块被离线队列 TTL 重放出去是垃圾流量，还会挤占真实待发的额度；
 * 所以这里**绝不重试入队**，失败的订阅如实落进 [QuestStreamState.UNAVAILABLE]。
 * 日志块的丢失容忍是设计语义（与 `AgentEventKinds.EPHEMERAL` 同一条 T-G3 纪律：
 * 增量不留底，终局事实走通知帧）。
 *
 * ## 并发与读侧
 *
 * 全部可变状态在一把 [Mutex] 后面（commonMain 不许 JVM 原语，见 common-jvm-api-guard）。
 * 发送（suspend 网络 IO）一律在锁外执行：锁内只做「取快照 / 记结果」。
 * **读侧零锁**：可变状态变更后在锁内整体发布成不可变 [Snapshot]（MutableStateFlow），
 * UI 组合路径上的同步读只是字段读取 —— ArrayDeque 的并发迭代会当场炸，不做
 * 「最坏拿到旧帧」这种赌。（`AgentLiveLog` 用 AtomicReference CAS 是同一个理由。）
 */
class QuestStreamHub(
    private val scope: CoroutineScope,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val sendSub: suspend (peerId: String, questId: String, sinceSeq: Long) -> Boolean,
    private val sendChunk: suspend (peerId: String, questId: String, seq: Long, text: String) -> Boolean,
    private val sendCancel: suspend (peerId: String, questId: String) -> Boolean,
    /** questId → 宿主 nodeId；null = 找不到；[LOCAL_HOST] = 任务就在本机（桌面作战室）。 */
    private val questHostResolver: (questId: String) -> String?,
    /** 诊断钩子（订阅/回放/入桶归属）：作战室串流问题的排查读数，测试可传空。 */
    private val onDiagnostic: (tag: String, message: String) -> Unit = { _, _ -> },
) {
    /** 读侧一次性发布的不可变视图（UI 以 [revision] 驱动重组后整帧读取）。 */
    class Snapshot(
        val revision: Long,
        val states: Map<String, QuestStreamState>,
        /** UNAVAILABLE 的原因细分（面板文案据此如实，不笼统说“不在线”）。 */
        val unavailableReasons: Map<String, QuestStreamUnavailableReason>,
        val recvLines: Map<String, List<String>>,
        val localLines: Map<String, List<String>>,
    )

    companion object {
        /** 宿主角色每个环形缓冲保留的**行数**上限（方案 v3：2000 行轻量环形缓冲）。 */
        const val MAX_RING_LINES = 2_000

        /** 实时合批节拍（与 `WorktreeSandboxExecutor` 的 100ms 轮询同族）。 */
        const val DEFAULT_FLUSH_INTERVAL_MS = 100L

        /** 防抖缓冲的字节数上限（没人订阅时 pending 不能无限长）。 */
        private const val PENDING_MAX_BYTES = 64 * 1024

        /** questHostResolver 的哨兵值：任务就跑在本机。 */
        const val LOCAL_HOST = "self"

        private fun countLines(text: String): Int = text.count { it == '\n' } + 1
    }

    private class StreamEntry(val seq: Long, val text: String, val lines: Int)

    private class HostQuestState {
        val ring = ArrayDeque<StreamEntry>()
        var ringLines = 0
        val pending = StringBuilder()
        var nextSeq = 0L
        /** peerId → 已下发到的 seq（订阅者水位）。 */
        val subscribers = LinkedHashMap<String, Long>()
    }

    private class RecvState {
        val entries = ArrayDeque<String>()
        var lines = 0
        var lastSeq = 0L
    }

    private val mutex = Mutex()

    // ---- 宿主角色状态 ----
    private val hostQuests = HashMap<String, HostQuestState>()

    // ---- 订阅角色状态 ----
    private val recvQuests = HashMap<String, RecvState>()
    /** UI 的「想看」集合（作战室开着的那几个 quest）。 */
    private val desired = LinkedHashSet<String>()
    /** questId → 已订阅的宿主 nodeId（sub 得到 ACK 之后才进这张表）。 */
    private val activeSubs = HashMap<String, String>()
    /** 对账失败的 quest（找不到宿主 / sub 没有 ACK）。 */
    private val unavailable = LinkedHashSet<String>()
    /** 解析结果缓存（宿主归属一次解析终身有效；null 也缓存 —— 别每帧都打 SQLite）。 */
    private val hostCache = HashMap<String, String?>()
    private val unavailableReasons = HashMap<String, QuestStreamUnavailableReason>()

    private val _snapshot = MutableStateFlow(Snapshot(0L, emptyMap(), emptyMap(), emptyMap(), emptyMap()))

    /** 读侧唯一入口：UI collect 它驱动重组，然后整帧读 [Snapshot]。 */
    val snapshot: StateFlow<Snapshot> = _snapshot

    private var pumpJob: Job? = null

    /** 锁内：把全部可变状态物化成不可变快照发布。 */
    private fun publishLocked() {
        val states = HashMap<String, QuestStreamState>()
        val reasons = HashMap<String, QuestStreamUnavailableReason>()
        for (questId in desired) {
            when (hostCache[questId]) {
                LOCAL_HOST -> states[questId] = QuestStreamState.LOCAL
                null -> {
                    states[questId] = QuestStreamState.UNAVAILABLE
                    reasons[questId] = QuestStreamUnavailableReason.NO_HOST
                }
                else -> if (questId in activeSubs) {
                    states[questId] = QuestStreamState.STREAMING
                } else if (questId in unavailable) {
                    states[questId] = QuestStreamState.UNAVAILABLE
                    reasons[questId] = QuestStreamUnavailableReason.HOST_UNREACHABLE
                } else {
                    states[questId] = QuestStreamState.IDLE
                }
            }
        }
        for (questId in unavailable) if (questId !in states) {
            states[questId] = QuestStreamState.UNAVAILABLE
            reasons[questId] = QuestStreamUnavailableReason.HOST_UNREACHABLE
        }
        for (questId in activeSubs.keys) if (questId !in states) states[questId] = QuestStreamState.STREAMING
        for (questId in hostQuests.keys) if (questId !in states && hostCache[questId] == LOCAL_HOST) {
            states[questId] = QuestStreamState.LOCAL
        }
        unavailableReasons.clear()
        unavailableReasons.putAll(reasons.filterValues { it == QuestStreamUnavailableReason.HOST_UNREACHABLE })
        val recv = HashMap<String, List<String>>()
        for ((questId, st) in recvQuests) {
            if (st.entries.isEmpty()) continue
            val lines = ArrayList<String>(st.lines)
            for (e in st.entries) lines.addAll(e.split('\n'))
            recv[questId] = lines
        }
        val local = HashMap<String, List<String>>()
        for ((questId, st) in hostQuests) {
            if (st.ring.isEmpty()) continue
            val lines = ArrayList<String>(st.ringLines)
            for (e in st.ring) lines.addAll(e.text.split('\n'))
            local[questId] = lines
        }
        _snapshot.value = Snapshot(
            revision = _snapshot.value.revision + 1,
            states = states,
            unavailableReasons = reasons,
            recvLines = recv,
            localLines = local,
        )
    }

    // ---------------------------------------------------------------- 宿主角色

    /**
     * 宿主侧吃进一行原始输出（来自 CLI 驱动的行泵；stderr 已并入 stdout）。
     *
     * 只积累不发送：合批与下发由泵协程（[pumpTick]）按节拍做。没人订阅时 pending
     * 也不许无限长 —— 超 [PENDING_MAX_BYTES] 就从头部丢（丢的是最老的日志，不是状态）。
     */
    suspend fun acceptLine(questId: String, line: String) {
        mutex.withLock {
            val st = hostQuests.getOrPut(questId) { HostQuestState() }
            if (st.pending.isNotEmpty()) st.pending.append('\n')
            st.pending.append(line)
            trimPendingLocked(st)
            // 有订阅者或本机在产日志都要泵在跑（LOCAL 视图靠无订阅者的冲缓冲）。
            ensurePumpLocked()
            if (hostCache[questId] == null) publishLocked()
        }
    }

    /** 任务终局（完成/失败/中止）时调用：把残留的半批冲进环形缓冲，供事后补看。 */
    suspend fun questSettled(questId: String) {
        mutex.withLock {
            val st = hostQuests[questId] ?: return@withLock
            flushPendingLocked(st)
            publishLocked()
        }
    }

    private fun trimPendingLocked(st: HostQuestState) {
        val bytes = st.pending.toString().encodeToByteArray().size
        if (bytes <= PENDING_MAX_BYTES) return
        // 按字节从头部丢，切点避开代理对（与 clampUtf8 同一条「不产出 U+FFFD」纪律）。
        val text = st.pending.toString()
        var cut = text.length - ((bytes - PENDING_MAX_BYTES) / 2).coerceAtLeast(0)
        cut = cut.coerceIn(0, text.length)
        while (cut > 0 && cut < text.length && text[cut - 1].isHighSurrogate()) cut--
        if (cut <= 0) st.pending.clear() else st.pending.delete(0, cut)
    }

    /** 把 pending 冲成一个 ring 批（带 seq），返回冲出的批（无积压返回 null）。调用方持锁。 */
    private fun flushPendingLocked(st: HostQuestState): StreamEntry? {
        if (st.pending.isEmpty()) return null
        val text = st.pending.toString()
        st.pending.clear()
        val seq = ++st.nextSeq
        val entry = StreamEntry(seq, text, countLines(text))
        appendRingLocked(st, entry)
        return entry
    }

    private fun appendRingLocked(st: HostQuestState, entry: StreamEntry) {
        st.ring.addLast(entry)
        st.ringLines += entry.lines
        while (st.ringLines > MAX_RING_LINES) {
            val oldest = st.ring.firstOrNull() ?: break
            if (st.ring.size > 1) {
                st.ring.removeFirst()
                st.ringLines -= oldest.lines
            } else {
                // 单笔批自己就超行帽（一拍进了 3000 行）：截它的**尾部**保最新 2000 行，
                // 批序号不动 —— 「保留最近 2000 行」不因单批巨大而失效。
                val keep = oldest.text.split('\n').takeLast(MAX_RING_LINES)
                st.ring.clear()
                st.ring.addLast(StreamEntry(oldest.seq, keep.joinToString("\n"), keep.size))
                st.ringLines = keep.size
            }
        }
    }

    private fun ensurePumpLocked() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (true) {
                delay(flushIntervalMs)
                if (pumpTick()) return@launch // pumpTick 已在锁内置 null（review P2-3）
            }
        }
    }

    /** 一拍合批：true = 全场没有订阅者，泵可以退出。 */
    /**
     * 一拍合批：true = 全场既没有订阅者也没有积压，泵可以退出。
     *
     * **缓冲的推进与订阅者无关**：桌面作战室的 LOCAL 视图（本机任务不经协议）
     * 靠的就是无订阅者时环形缓冲照样按节拍吃进产出 —— 否则本机面板直到任务
     * 终局才看到第一批日志，「实时」二字无从谈起。只有**下发**跟着订阅者走。
     *
     * 停止判据与置空在**同一把锁内**完成：acceptLine 的 ensurePump 与本处的退出
     * 互斥串行 —— 决定退出时 job 已置 null，之后到来的行必然重新拉起泵，
     * 不存在「最后一行滞留在 pending 里没人冲」的竞态。
     */
    private suspend fun pumpTick(): Boolean {
        val batches = ArrayList<Triple<String, Long, String>>()
        val stop = mutex.withLock {
            var anySubscribers = false
            for ((questId, st) in hostQuests) {
                val entry = flushPendingLocked(st)
                if (entry != null) batches.add(Triple(questId, entry.seq, entry.text))
                if (st.subscribers.isNotEmpty()) anySubscribers = true
            }
            if (!anySubscribers) pumpJob = null
            publishLocked()
            !anySubscribers
        }
        if (stop) return true
        for ((questId, seq, text) in batches) {
            val peers = mutex.withLock {
                hostQuests[questId]?.subscribers?.keys?.toList() ?: emptyList()
            }
            for (peer in peers) {
                val ok = runCatching { sendChunk(peer, questId, seq, text) }.getOrDefault(false)
                recordChunkResult(questId, peer, seq, ok)
            }
        }
        return false
    }

    private suspend fun recordChunkResult(questId: String, peer: String, seq: Long, ok: Boolean) {
        mutex.withLock {
            val st = hostQuests[questId] ?: return@withLock
            // 失败不推进该订阅者的水位（下一批重试时块序号仍连续）；死链路上的
            // 反复失败开销是一次 isConnected 短路，不摘除 —— 摘除交给 cancel/重连对账。
            if (ok) st.subscribers[peer] = seq
        }
    }

    // ---------------------------------------------------- 入站帧处置（两角色共用）

    /** 入站 `stream-sub`（宿主侧）：登记订阅者 + 按档补存量 + 接实时。 */
    suspend fun handleStreamSub(envelope: Envelope) {
        val p = envelope.payload as? kotlinx.serialization.json.JsonObject ?: return
        val questId = CodecWire.strOrNull(p["questId"], Limits.ID) ?: return
        val since = CodecWire.intOf(p["sinceSeq"]) ?: 0L
        if (since < 0) return
        val peer = envelope.from
        val replay = ArrayList<StreamEntry>()
        mutex.withLock {
            val st = hostQuests.getOrPut(questId) { HostQuestState() }
            flushPendingLocked(st)
            st.subscribers[peer] = since
            for (e in st.ring) if (e.seq > since) replay.add(e)
            ensurePumpLocked()
            publishLocked()
        }
        // 订阅归属读数：作战室跨 quest 串流的排查钉子（订阅的是谁、回放多少）。
        onDiagnostic(
            "RELAY-QUEST",
            "stream sub: quest=" + questId + " from=" + (peer?.take(8) ?: "?") +
                " since=" + since + " replay=" + replay.size,
        )
        for (e in replay) {
            val ok = runCatching { sendChunk(peer, questId, e.seq, e.text) }.getOrDefault(false)
            recordChunkResult(questId, peer, e.seq, ok)
        }
        onDiagnostic(
            "RELAY-QUEST",
            "stream replay sent: quest=" + questId + " to=" + (peer?.take(8) ?: "?") + " entries=" + replay.size,
        )
    }

    /** 入站 `stream-chunk`（订阅侧）：seq 去重 + 进订阅端环形缓冲。 */
    suspend fun handleStreamChunk(envelope: Envelope) {
        val p = envelope.payload as? kotlinx.serialization.json.JsonObject ?: return
        val questId = CodecWire.strOrNull(p["questId"], Limits.ID) ?: return
        val seq = CodecWire.intOf(p["seq"]) ?: return
        if (seq < 1) return
        val text = CodecWire.strOrNull(p["text"], Limits.STREAM_CHUNK_BODY) ?: return
        var gapDetected = false
        mutex.withLock {
            val st = recvQuests.getOrPut(questId) { RecvState() }
            // seq 断档重同步（review 设计级①）：跳号 = 丢了中间块（序列化竞争或链路丢块），
            // 锁外触发 reconcile 重发 sub（sinceSeq=lastSeq）拉缺口。HostQuestState 重启
            // seq 归零的冻结问题由此闭合（重连后 lastSeq 高但新 seq 从 0 来）。
            if (seq > st.lastSeq + 1) gapDetected = true
            if (seq <= st.lastSeq) return@withLock
            st.lastSeq = seq
            st.entries.addLast(text)
            st.lines += countLines(text)
            while (st.lines > MAX_RING_LINES) {
                val oldest = st.entries.firstOrNull() ?: break
                if (st.entries.size > 1) {
                    st.entries.removeFirst()
                    st.lines -= countLines(oldest)
                } else {
                    // 单笔块自己超行帽：同 appendRingLocked，截尾部保最新 2000 行。
                    val keep = oldest.split('\n').takeLast(MAX_RING_LINES)
                    st.entries.clear()
                    st.entries.addLast(keep.joinToString("\n"))
                    st.lines = keep.size
                }
            }
            unavailable.remove(questId)
            publishLocked()
        }
        if (gapDetected) scope.launch { reconcile() }
        onDiagnostic(
            "RELAY-QUEST",
            "stream chunk recv: quest=" + questId + " seq=" + seq + " lines=" + countLines(text),
        )
    }

    /** 入站 `stream-cancel`（宿主侧）：只摘除发送方这一路；最后一个退订才停泵。 */
    suspend fun handleStreamCancel(envelope: Envelope) {
        val p = envelope.payload as? kotlinx.serialization.json.JsonObject ?: return
        val questId = CodecWire.strOrNull(p["questId"], Limits.ID) ?: return
        mutex.withLock {
            hostQuests[questId]?.subscribers?.remove(envelope.from)
            publishLocked()
        }
    }

    // ---------------------------------------------------------------- 订阅角色

    /** UI 意图：作战室开 = true，关/切后台 = false。改变即触发一次对账。 */
    fun setDesired(questId: String, wanted: Boolean) {
        scope.launch {
            val added = ArrayList<String>()
            mutex.withLock {
                if (wanted) {
                    desired.add(questId)
                    added.add(questId)
                } else {
                    desired.remove(questId)
                }
                publishLocked()
            }
            // 解析在锁外（resolver 可能做 SQLite 查询）；失败 null 不缓存——下次重解析。
            for (questId in added) {
                if (questId !in hostCache) {
                    val host = runCatching { questHostResolver(questId) }.getOrNull()
                    mutex.withLock { if (questId !in hostCache) hostCache[questId] = host }
                }
            }
            reconcile()
        }
    }

    /** 切后台：意图清空 + 对账（全部退订）。 */
    fun cancelAllDesired() {
        scope.launch {
            mutex.withLock {
                desired.clear()
                publishLocked()
            }
            reconcile()
        }
    }

    /**
     * 对账「想看」与「已订阅」：缺的补 sub（带 `sinceSeq` 续传），多的发 cancel。
     * 发送在锁外；结果（成功进 activeSubs / 失败进 unavailable）回锁内落账。
     */
    suspend fun reconcile() {
        val toSub = ArrayList<Triple<String, String, Long>>()
        val toCancel = ArrayList<Pair<String, String>>()
        mutex.withLock {
            for (questId in desired) {
                if (questId in activeSubs) continue
                val host = hostCache.getOrPut(questId) {
                    runCatching { questHostResolver(questId) }.getOrNull()
                }
                if (host != null && host != LOCAL_HOST) {
                    toSub.add(Triple(questId, host, recvQuests[questId]?.lastSeq ?: 0L))
                }
            }
            for ((questId, peer) in activeSubs) {
                if (questId !in desired) toCancel.add(questId to peer)
            }
            for ((questId, _) in toCancel) activeSubs.remove(questId)
            publishLocked()
        }
        for ((questId, peer, since) in toSub) {
            val ok = runCatching { sendSub(peer, questId, since) }.getOrDefault(false)
            mutex.withLock {
                if (ok) {
                    activeSubs[questId] = peer
                    unavailable.remove(questId)
                } else {
                    unavailable.add(questId)
                }
                publishLocked()
            }
        }
        for ((questId, peer) in toCancel) {
            runCatching { sendCancel(peer, questId) }
        }
    }

    /** 读侧状态（纯快照读取，不触发解析器与锁 —— UI 组合路径安全）。 */
    fun streamStateOf(questId: String): QuestStreamState =
        _snapshot.value.states[questId] ?: QuestStreamState.IDLE

    /** UNAVAILABLE 的原因细分（纯快照读取）。 */
    fun unavailableReasonOf(questId: String): QuestStreamUnavailableReason? =
        _snapshot.value.unavailableReasons[questId]

    /** 订阅端快照（作战室日志面板的正文来源）。 */
    fun snapshotLines(questId: String): List<String> =
        _snapshot.value.recvLines[questId] ?: emptyList()

    /** 本机（宿主）快照：桌面作战室直接读本地环形缓冲。 */
    fun localSnapshotLines(questId: String): List<String> =
        _snapshot.value.localLines[questId] ?: emptyList()
}
