// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.persist

import app.relay.core.fs.RandomAccessFile
import app.relay.core.fs.deleteFile
import app.relay.core.fs.fileExists
import app.relay.core.fs.makeDirectories
import app.relay.core.fs.openRandomAccess
import app.relay.core.fs.pathJoin
import app.relay.core.transport.DedupStore
import app.relay.core.transport.MessageStore
import app.relay.core.transport.PRUNE_REASON_OVERFLOW
import app.relay.core.transport.PRUNE_REASON_TTL
import app.relay.core.transport.PrunedMessage
import app.relay.core.transport.QueueStore
import app.relay.core.transport.QueuedMessage
import app.relay.core.transport.StoredMessage

/**
 * core 三个存储接口的**落盘实现**。
 *
 * ## 为什么需要这个模块
 *
 * [QueueStore] / [DedupStore] / [MessageStore] 的契约定义在 core，但 core 不该知道
 * 往哪儿写盘 —— 它甚至不知道有没有盘。契约的默认实现全是内存版，于是：
 *
 * - **待发队列重启即空**：一条「对方离线时发的消息」在重启后没有人会再去补发，
 *   卡片停在「已排队」直到用户手动重试。
 * - **去重表重启即空**（`FileDedupStore` 是个例外，但它住在 transport 包里，
 *   像是别人的实现而不是这个契约的实现）。
 * - **聊天历史只在 app 层落盘**（composeApp 的 `FileMessageStore` / SQLDelight），
 *   于是 agentNode 这个常驻进程的历史永远是空的。
 *
 * 上层为此付了两笔代价：
 *
 * 1. app 层的 `MessageUi.outgoingEnvelope` —— 把**整条已编码信封**塞进 UI 消息行，
 *    只为了让启动流程能原样喂回 `Messenger.requeue`。这是把协议级不变量
 *    （`Envelope.id` 跨重启必须同一个，否则对端收两条）泄漏进了一张数据库列。
 * 2. composeApp 的 `FileMessageStore` 在 jvm/android 两个源集里**逐行复制**，
 *    因为它落在 app 模块里。
 *
 * 这个模块不引入任何新依赖：落盘只用 core 自己的 [app.relay.core.fs.FileIo]
 * expect/actual（`FileDedupStore` 已经是同一个模式），因此 KMP 三端都能用，
 * composeApp 与 agentNode 共用同一份实现。
 *
 * ## 坏数据不拦路
 *
 * 与 `FileDedupStore` 同一条纪律：读不出来、写不进去、单行解析不了，一律降级为
 * 「这条没落盘 / 这份当空表」，绝不让聊天因此起不来。丢的是历史，不是消息。
 */

/** 数据目录：一个模块内所有文件的根。调用方决定它在哪。 */
data class PersistPaths(
    /** 队列日志，每行一条已编码信封。 */
    val queueLog: String,
    /** 去重表日志。 */
    val dedupLog: String,
    /** 消息历史日志。 */
    val messageLog: String,
) {
    companion object {
        /**
         * 在 `<dataDir>/stores/` 下摆齐三个文件。
         *
         * 单独一个 `stores/` 子目录而不是直接摊在 dataDir 根上：dataDir 里还有
         * node-id、配置、下载目录缓存，混在一起时 `sweepStaleParts` 那种目录级
         * 走查会扫到不属于本模块的东西。
         */
        fun under(dataDir: String): PersistPaths {
            val dir = pathJoin(dataDir, "stores")
            makeDirectories(dir)
            return PersistPaths(
                queueLog = pathJoin(dir, "queue.log"),
                dedupLog = pathJoin(dir, "dedup.log"),
                messageLog = pathJoin(dir, "messages.log"),
            )
        }
    }
}

/** 无条件打开（创建或追加）。文件打不开返回 null —— 磁盘满/权限不足时不拦路。 */
internal fun openFresh(path: String): RandomAccessFile? {
    if (!fileExists(path)) makeDirectories(path.substringBeforeLast('/', ""))
    return runCatching { openRandomAccess(path, forWrite = true) }.getOrNull()
}


/**
 * 转义一个来自对端的字段。
 *
 * `msgId` / `peerId` 都是**对端发的帧**里的字符串，没有格式校验，里面完全可能有
 * `\t`、换行、反斜杠。不转义的话，对端发一个带换行的 id 就能伪造出整行假记录。
 * **这不是防呆，是输入校验。**
 */
internal fun escapeField(raw: String): String {
    if (raw.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return raw
    val sb = StringBuilder(raw.length + 8)
    for (ch in raw) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** [escapeField] 的逆运算。`trailing` 为 true 表示这行被截断过（没有换行符收尾）。 */
internal fun unescapeField(raw: String): String {
    if (!raw.contains('\\')) return raw
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch != '\\' || i == raw.length - 1) {
            sb.append(ch)
            i++
            continue
        }
        when (raw[i + 1]) {
            '\\' -> sb.append('\\')
            't' -> sb.append('\t')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            else -> sb.append(raw[i + 1])
        }
        i += 2
    }
    return sb.toString()
}

/** 一行一个 UTF-8 字段，读到末尾为止。 */
internal fun readLines(file: RandomAccessFile): List<String> {
    val out = ArrayList<String>()
    val size = file.size
    if (size <= 0) return out
    val chunk = ByteArray(minOf(size, CHUNK).toInt())
    var at = 0L
    var cur = StringBuilder()
    while (at < size) {
        val want = minOf(chunk.size.toLong(), size - at).toInt()
        val got = file.read(chunk, at, want)
        if (got <= 0) break
        for (i in 0 until got) {
            val ch = chunk[i].toInt().toChar()
            if (ch == '\n') {
                out += cur.toString()
                cur = StringBuilder()
            } else {
                cur.append(ch)
            }
        }
        at += got
    }
    // 末尾没有换行符的那一行照样算一条 —— 进程被 kill 时的常态。
    if (cur.isNotEmpty()) out += cur.toString()
    return out
}

internal const val CHUNK: Long = 64L * 1024

/** 文件不存在就返回 null —— 这是「第一次启动」的常态，不是错误。 */
internal fun openIfPresent(path: String): RandomAccessFile? {
    if (!fileExists(path)) return null
    return runCatching { openRandomAccess(path, forWrite = true) }.getOrNull()
}

/**
 * 整份重写。
 *
 * **必须先关掉 append 句柄再删文件** —— 否则那个句柄仍指向已被 unlink 的 inode，
 * 之后所有追加都写进一个没人再读得到的文件，症状是「落盘成功但重启后一条都没有」。
 * 这就是 append 句柄必须由 store 自己持有、不能每次现开的原因。
 */
internal fun rewriteLog(log: RandomAccessFile?, path: String, body: StringBuilder) {
    if (log != null) {
        runCatching { log.close() }
    }
    runCatching { deleteFile(path) }
    val bytes = body.toString().encodeToByteArray()
    val opened = openFresh(path) ?: return
    try {
        opened.append(bytes, bytes.size)
    } finally {
        opened.close()
    }
}

/**
 * 出站可靠消息的落盘队列。
 *
 * ## 磁盘格式
 *
 * 每行一条 `at \t escaped(peerId) \t escaped(msgId) \t envelopeJson`，**追加**写。
 * 追加而不是每条都重写：发一条消息就重写一次整份文件，发得越多写得越慢，最后变成
 * 平方级。重写只发生在 [prune] 与进程退出时的兜底压缩。
 *
 * ## 为什么要「原封不动」地存信封
 *
 * 队列里的 `msgId` 必须与信封里的 `Envelope.id` 一致。重建信封会换一个新 id，
 * 于是重启后补发的那条在对端看起来是**一条全新消息**，用户会看到同一句话出现两次。
 * 所以这里存的是 `Codec.encode` 的原样输出，补发时也不重新编码
 * （见 `NodeRuntime.requeueOutgoing`）。
 */
class FileQueueStore(
    private val path: String,
    private val compactEvery: Int = DEFAULT_COMPACT_EVERY,
) : QueueStore {

    /** key = 转义后的 `peerId \t msgId`，值 = 入队时刻。 */
    private val items = LinkedHashMap<String, QueuedMessage>()
    private var appendsSinceCompact = 0
    private var log: RandomAccessFile? = null

    init {
        load()
    }

    override fun enqueue(msgId: String, peerId: String, envelopeJson: String, created: Long) {
        val key = logKey(peerId, msgId)
        val existing = items[key]
        if (existing != null) {
            // 同一条重发：换掉旧记录，别让列表里出现两份。
            items[key] = QueuedMessage(msgId, peerId, envelopeJson, created)
            rewrite()
            return
        }
        items[key] = QueuedMessage(msgId, peerId, envelopeJson, created)
        appendLine(row(created, peerId, msgId, envelopeJson))
        appendsSinceCompact++
        if (appendsSinceCompact >= compactEvery) rewrite()
    }

    override fun listByPeer(peerId: String): List<QueuedMessage> = items.values.filter { it.peerId == peerId }

    override fun remove(msgId: String, peerId: String) {
        if (items.remove(logKey(peerId, msgId)) != null) rewrite()
    }

    override fun countByPeer(): Map<String, Int> =
        items.values.groupingBy { it.peerId }.eachCount()

    override fun pendingAll(): List<QueuedMessage> = items.values.sortedBy { it.created }

    override fun prune(now: Long, ttl: Long, maxPerPeer: Int): List<PrunedMessage> {
        val pruned = ArrayList<PrunedMessage>()
        val cut = now - ttl
        // 1) TTL
        val expired = items.values.filter { it.created < cut }
        for (m in expired) {
            items.remove(logKey(m.peerId, m.msgId))
            pruned += PrunedMessage(m.msgId, m.peerId, PRUNE_REASON_TTL)
        }
        // 2) 每 peer 超额：按 created 升序丢最旧的
        val overflow = items.values
            .groupBy { it.peerId }
            .filterValues { it.size > maxPerPeer }
        for ((peerId, list) in overflow) {
            val drop = list.sortedBy { it.created }.take(list.size - maxPerPeer)
            for (m in drop) {
                items.remove(logKey(m.peerId, m.msgId))
                pruned += PrunedMessage(m.msgId, peerId, PRUNE_REASON_OVERFLOW)
            }
        }
        if (pruned.isNotEmpty()) rewrite()
        return pruned
    }

    /** 把当前内存状态整份重写。进程退出前调一次，把追加写的冗余压掉。 */
    fun compact() = rewrite()

    private fun load() {
        val read = openIfPresent(path) ?: return
        for (line in readLines(read)) {
            val parts = line.split('\t')
            if (parts.size < 4) continue
            val at = parts[0].toLongOrNull() ?: continue
            val peerId = unescapeField(parts[1])
            val msgId = unescapeField(parts[2])
            if (msgId.isEmpty() || peerId.isEmpty()) continue
            val json = parts.drop(3).joinToString("\t")
            items[logKey(peerId, msgId)] = QueuedMessage(msgId, peerId, json, at)
        }
        runCatching { read.close() }
    }

    private fun rewrite() {
        val body = StringBuilder()
        for (m in items.values) body.append(row(m.created, m.peerId, m.msgId, m.envelopeJson)).append('\n')
        rewriteLog(log, path, body)
        log = null
        appendsSinceCompact = 0
    }

    private fun appendLine(line: String) {
        val handle = log ?: runCatching { openRandomAccess(path, forWrite = true) }.getOrNull() ?: return
        log = handle
        runCatching {
            handle.append((line + "\n").encodeToByteArray(), line.length + 1)
            handle.flush()
        }
    }


    private fun row(at: Long, peerId: String, msgId: String, json: String) =
        "$at\t${escapeField(peerId)}\t${escapeField(msgId)}\t$json"

    private companion object {
        const val DEFAULT_COMPACT_EVERY = 512

        fun logKey(peerId: String, msgId: String) = "${escapeField(peerId)}\t${escapeField(msgId)}"
    }
}

/**
 * 落盘的会话历史。
 *
 * 每行 `seq \t at \t escaped(peerId) \t direction \t envelopeJson`，**追加**写；
 * [prune] 与达到 [compactEvery] 时整份重写。
 *
 * `seq` 在**加载时重建**，不落盘：它只是「全局单调递增的序号」，存下来就得处理
 * 「两份日志的 seq 撞了」这种问题，而它的唯一用途是 `GET /v1/inbox?since=` 的增量拉取
 * —— 日志是追加写的，行序就是写入序，从 1 开始重编号就够，且重启前后语义一致。
 */
class FileMessageStore(
    private val path: String,
    private val compactEvery: Int = DEFAULT_COMPACT_EVERY,
) : MessageStore {

    private val rows = ArrayList<StoredMessage>()
    private var nextSeq = 1L
    private var appendsSinceCompact = 0
    private var log: RandomAccessFile? = null

    init {
        load()
    }

    override fun append(peerId: String, direction: String, envelopeJson: String, at: Long): Long {
        val seq = nextSeq++
        rows += StoredMessage(seq, peerId, direction, envelopeJson, at)
        appendLine("$seq\t$at\t${escapeField(peerId)}\t${escapeField(direction)}\t$envelopeJson")
        appendsSinceCompact++
        if (appendsSinceCompact >= compactEvery) rewrite()
        return seq
    }

    override fun since(peerId: String, sinceSeq: Long, limit: Int): List<StoredMessage> =
        rows.asSequence()
            .filter { it.peerId == peerId && it.seq > sinceSeq }
            .sortedBy { it.seq }
            .take(limit)
            .toList()

    override fun latestSeq(peerId: String): Long =
        rows.filter { it.peerId == peerId }.maxOfOrNull { it.seq } ?: 0L

    override fun sinceAll(sinceAt: Long, limit: Int): List<StoredMessage> =
        rows.asSequence()
            .filter { it.at > sinceAt }
            .sortedBy { it.at }
            .take(limit)
            .toList()

    /** 超过 [maxRows] 条后丢最旧的（按 `at` 升序）。返回被丢掉的条数。 */
    fun trimTo(maxRows: Int): Int {
        if (rows.size <= maxRows) return 0
        val drop = rows.sortedBy { it.at }.take(rows.size - maxRows)
        for (r in drop) rows.remove(r)
        rewrite()
        return drop.size
    }

    fun compact() = rewrite()

    private fun load() {
        val handle = openIfPresent(path) ?: return
        val parsed = ArrayList<StoredMessage>()
        for (line in readLines(handle)) {
            val parts = line.split('\t')
            if (parts.size < 5) continue
            val at = parts[1].toLongOrNull() ?: continue
            val peerId = unescapeField(parts[2])
            val direction = unescapeField(parts[3])
            if (peerId.isEmpty()) continue
            parsed += StoredMessage(0L, peerId, direction, parts.drop(4).joinToString("\t"), at)
        }
        runCatching { handle.close() }
        // seq 按写入顺序重排：日志是追加写的，行序就是写入序。
        parsed.forEachIndexed { i, m -> rows += m.copy(seq = i + 1L) }
        nextSeq = rows.size + 1L
    }

    private fun rewrite() {
        val body = StringBuilder()
        rows.forEachIndexed { i, m ->
            body.append(i + 1).append('\t').append(m.at).append('\t')
                .append(escapeField(m.peerId)).append('\t').append(escapeField(m.direction))
                .append('\t').append(m.envelopeJson).append('\n')
        }
        rewriteLog(log, path, body)
        log = null
        appendsSinceCompact = 0
    }

    private fun appendLine(line: String) {
        val handle = runCatching { openRandomAccess(path, forWrite = true) }.getOrNull() ?: return
        runCatching {
            handle.append((line + "\n").encodeToByteArray(), line.length + 1)
            handle.flush()
        }
    }

    private companion object {
        const val DEFAULT_COMPACT_EVERY = 512
    }
}

/** [FileDedupStore] 的同款落盘实现搬到这里，与另外两个 store 并排。 */
internal fun fileDedupStore(path: String): DedupStore = app.relay.core.transport.FileDedupStore(path)
