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
    /** 每个节点学到的候选端点（配对码里那一池地址）。 */
    val peerEndpointsLog: String,
    /** 每个节点记住的 TLS pin（配对码里带的 tlsPin）。 */
    val peerPinsLog: String,
    /**
     * 「已知对端」名录（task-80 ①）：nodeId → 昵称 / 平台 / 最近端点。
     *
     * 与 [peerEndpointsLog] 分工：那个是"换网兜底的一池地址"（30 天过期），
     * 这个是**身份**（90 天过期）—— 重启后节点表里那台机器叫什么、是什么系统，从这里恢复。
     */
    val knownPeersLog: String,
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
                peerEndpointsLog = pathJoin(dir, "peer-endpoints.json"),
                peerPinsLog = pathJoin(dir, "peer-pins.json"),
                knownPeersLog = pathJoin(dir, "known-peers.json"),
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

/**
 * 一行一个 UTF-8 字段，读到末尾为止。
 *
 * ## 为什么按**字节**找行边界，再对整行解码
 *
 * 这里曾经写的是 `chunk[i].toInt().toChar()`。`Byte.toInt()` 会**符号扩展**：0xE7 这个
 * 字节是 -25，`(-25).toChar()` 得到 U+FFE7。于是任何 ≥ 0x80 的字节都变成 `U+FF00 | b`，
 * 一条中文群消息读回来就是「￧ﾾﾤ￦ﾶﾈ…」——真机反馈里那串 `U+FFxx` 乱码的出处。
 * 更坏的是结构（花括号、引号、键名）全是 ASCII，JSON 照样解析得动，乱码于是被当成
 * 正文补发/落库，一路传到手机上。
 *
 * 修法：`\n`（0x0A）**不会出现在 UTF-8 多字节序列内部**，所以可以放心地在字节层找行，
 * 再对每一行的完整字节做一次 `decodeToString()`（UTF-8）。跨 64KiB 块被切开的那半行
 * 必须按**原始字节**留到下一块 —— 先解码再拼的话，半个多字节字符解出来是 U+FFFD，
 * 只是把乱码换了个样子。
 */
internal fun readLines(file: RandomAccessFile): List<String> {
    val out = ArrayList<String>()
    val size = file.size
    if (size <= 0) return out
    val chunk = ByteArray(minOf(size, CHUNK).toInt())
    var at = 0L
    // 当前这一行还没写完的字节，**增量**累积（ByteArrayOutputStream）。
    //
    // 为什么不是 carry + chunk 那种"上一块的尾巴拼上新块"：文件里长时间没有换行时，
    // 那个写法每轮都要把越来越长的尾巴重新拷贝+重扫一遍 ⇒ O(n²)。桌面端 1.24GB 的
    // messages.log 恰好几乎没有换行（旧 append 的截断 bug 会把行尾的 \n 一起吃掉），
    // 于是启动时在 98% CPU 上空转、运行时永远起不来（真机现场实测）。
    // 这一行的字节分片；行结束时拼一次再解码。每块只拷一次，整体仍是 O(n)。
    val parts = ArrayList<ByteArray>()
    var lineBytes = 0
    // 这一行是不是已经超过上限、正在被丢弃（见 MAX_LINE_BYTES）。
    var oversized = false
    while (at < size) {
        val want = minOf(chunk.size.toLong(), size - at).toInt()
        val got = file.read(chunk, at, want)
        if (got <= 0) break
        var start = 0
        var i = 0
        while (i < got) {
            if (chunk[i] == NEWLINE) {
                if (!oversized) {
                    val add = i - start
                    if (lineBytes + add > MAX_LINE_BYTES) {
                        oversized = true
                        parts.clear()
                        lineBytes = 0
                    } else {
                        if (add > 0) {
                            parts += chunk.copyOfRange(start, i)
                            lineBytes += add
                        }
                        out += concatParts(parts).decodeToString()
                    }
                }
                parts.clear()
                lineBytes = 0
                oversized = false
                start = i + 1
            }
            i++
        }
        // 段尾那半行留到下一块；仍按原始字节，先解码再拼会把多字节字符切成 U+FFFD。
        if (!oversized) {
            val add = got - start
            if (lineBytes + add > MAX_LINE_BYTES) {
                oversized = true
                parts.clear()
                lineBytes = 0
            } else if (add > 0) {
                parts += chunk.copyOfRange(start, got)
                lineBytes += add
            }
        }
        at += got
    }
    // 末尾没有换行符的那一行照样算一条 —— 进程被 kill 时的常态。
    if (!oversized && lineBytes > 0) out += concatParts(parts).decodeToString()
    return out
}

/** 把一行的分片拼成一个数组。单片时直接返回，不做多余拷贝。 */
private fun concatParts(parts: List<ByteArray>): ByteArray {
    if (parts.size == 1) return parts[0]
    var total = 0
    for (p in parts) total += p.size
    val out = ByteArray(total)
    var at = 0
    for (p in parts) {
        p.copyInto(out, at)
        at += p.size
    }
    return out
}

/**
 * 单行的字节上限。
 *
 * 正常一行（一条消息的信封）是几百字节到几 KB。超过 8MiB 只有两种可能：文件已经被写坏，
 * 或者有人把二进制塞了进来。这时**丢掉这一行**而不是把它拼成一个几亿字符的 String ——
 * 后者会直接 OOM，把"文件坏了"升级成"应用起不来"。
 */
private const val MAX_LINE_BYTES: Int = 8 * 1024 * 1024

/** 行分隔符的字节值。UTF-8 里 0x0A 只可能是换行本身，不会出现在多字节字符内部。 */
private const val NEWLINE: Byte = 0x0A

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
 * 待发队列的淘汰策略。
 *
 * ## 为什么必须有它
 *
 * 队列原先**既没有上限也没有淘汰**：`enqueue` 只追加，`remove` 只在发送成功时调用。
 * 于是「目标节点已经不存在」的帧会永久留在队列里。
 *
 * 真机读数：`pending: 476`，其中 201 条的目标 `05607a4e` **不在 peers、
 * 也不在 known-peers 里** —— 那台设备早就不用了。它们永远发不出去，却每次启动都被
 * 重扫一遍，并且一直占着磁盘。
 *
 * ## 两条判据（缺一不可）
 *
 * 1. **目标设备已消失**：peer 既不在「已知对端」里，条目又已经超过 [ttlMillis]。
 *    单独用「不在已知对端里」会误删**刚配对、还没见过面**的对端 —— 那是合法待发。
 *    单独用 TTL 会删掉**只是长期离线**的对端 —— 它回来时那条消息本该还在。
 * 2. **硬上限**：任何情况下条目数不超过 [maxItems]，超出按入队先后砍最旧的。
 *    这条是兜底：判据 1 依赖外部喂进来的「已知对端」，那个集合本身可能因为别的原因
 *    为空（比如还没完成发现），那时只有上限能防失控。
 *
 * ## 为什么是 3 天
 *
 * 用户的取舍：「更短，3天」。三天内回来的设备还能收到积压的消息；
 * 超过三天仍未出现的对端，那条消息对用户来说已经过期了。
 */
data class QueueRetention(
    /** 目标对端不在已知集合里、且条目比这更旧 ⇒ 淘汰。 */
    val ttlMillis: Long = 3L * 24 * 60 * 60 * 1000,
    /** 硬上限（按条目）。超出按入队先后砍最旧的。 */
    val maxItems: Int = 500,
)

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
    /** 淘汰策略；默认值与真机现状一致（见 [QueueRetention] 的 KDoc）。 */
    private val retention: QueueRetention = QueueRetention(),
) : QueueStore {

    /** key = 转义后的 `peerId \t msgId`，值 = 入队时刻。 */
    private val items = LinkedHashMap<String, QueuedMessage>()
    private var appendsSinceCompact = 0
    private var log: RandomAccessFile? = null

    init {
        load()
        // **这里不清理。** 判断「这台设备还可能回来吗」需要上层掌握的信息（peers 与
        // known-peers 名录），而 store 构造时那些还没加载好。所以淘汰由
        // `RelayController.pruneVanishedQueuedMessages()` 在启动后与周期里各跑一次。
        //
        // 上下文：队列原先既没有「目标已消失」的判据也没有上限 —— 真机上攒到 478 条，
        // 其中 201 条的目标节点早就不存在了，永远发不出去、也永远新鲜到碰不到 TTL。
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


    /**
     * **外部喂进来的「这台设备还可能回来吗」的判据**。
     *
     * 由 `RelayController` 在启动时设入（它同时掌握 `peers` 与 `known-peers`）。
     * null = 还没拿到这份信息 ⇒ **不做这一路淘汰**（只留硬上限）：宁可多留，
     * 也不要在「我还没加载完」的时候把合法消息删掉。
     */
    var knownPeer: ((String) -> Boolean)? = null

    /**
     * **淘汰「目标设备已消失」的条目。**
     *
     * ## 它与 [prune] 的分工
     *
     * [prune] 只看**时间**与**每 peer 条数**。两者都拦不住真机上的一类积压：目标节点
     * 早就不存在了，但条目本身不满 7 天、也没超过每 peer 200 条 —— 于是它永远留在队列里。
     * 真机读数：`pending: 476`，其中 201 条的目标 `05607a4e` **不在 peers、
     * 也不在 known-peers 里**；它恰好卡在 200 条的限额上，TTL 也还没到期。
     *
     * 判据是**两个条件的合取**：`超过 TTL` **且** `目标不在已知集合里`。
     *  - 只用前者会删掉「只是长期离线」的设备的消息 —— 它回来时那条本该还在；
     *  - 只用后者会删掉「刚配对、还没见过面」的对端 —— 那是合法待发。
     *
     * @param now 当前时刻（测试注入）
     * @return 删掉的条数
     */
    override fun pruneVanishedPeers(now: Long, ttl: Long, knownPeer: (String) -> Boolean): Int {
        val before = items.size
        // ① 目标设备已消失 + 超时。
        val cutoff = now - ttl
        items.entries.removeAll { (_, m) -> m.created < cutoff && !knownPeer(m.peerId) }
        // ② **总量硬上限**：`queueMaxPerPeer` 只管每台设备，管不住「设备数 × 每台上限」，
        //    而真机上出现过「一堆早已消失的设备各占 200 条」的形态。这条兜底不依赖
        //    任何人喂进来的信息，所以它总能生效。按入队先后砍最旧的。
        if (items.size > retention.maxItems) {
            val overflow = items.size - retention.maxItems
            val oldest = items.values.sortedBy { it.created }.take(overflow)
            for (m in oldest) items.remove(logKey(m.peerId, m.msgId))
        }
        val removed = before - items.size
        if (removed > 0) rewrite()
        return removed
    }

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
            // 长度必须是**字节数**，不能给 `line.length`（字符数）：一条中文信封的 JSON
            // 180 字符 / 194 字节，给字符数会把行尾 13 个字节切掉，落盘的就是半条 JSON，
            // 重启后 `Codec.decode` 失败、队列直接把这条丢掉（消息静默丢失）。
            val bytes = (line + "\n").encodeToByteArray()
            handle.append(bytes, bytes.size)
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

    /**
     * 丢掉**过期的**（`at < now - ttl`）与**超量的**（只保留最新的 [maxRows] 条）。
     *
     * 两个条件**一次** `rewrite()` 完成 —— 分开做会把整份文件写两遍，而这份文件在真机上
     * 可以到几十 MB。
     *
     * `ttl <= 0` = 不做时间裁剪。丢掉的都是**最旧的**：`/v1/inbox?since=` 是「从某个时刻
     * 往后读」，砍掉最旧的一段只会让一个太旧的 `since` 少读到东西，不会读到错的。
     */
    override fun trim(now: Long, ttl: Long, maxRows: Int): Int {
        if (rows.isEmpty()) return 0
        val useTtl = ttl > 0
        val cutoff = if (useTtl) now - ttl else Long.MIN_VALUE
        val expired = if (useTtl) rows.count { it.at < cutoff } else 0
        val overCap = (rows.size - expired - maxRows).coerceAtLeast(0)
        if (expired == 0 && overCap == 0) return 0

        val doomed = HashSet<Long>()
        if (expired > 0) {
            rows.asSequence().filter { it.at < cutoff }.forEach { doomed += it.seq }
        }
        if (overCap > 0) {
            rows.asSequence()
                .filter { it.seq !in doomed }
                .sortedBy { it.at }
                .take(overCap)
                .forEach { doomed += it.seq }
        }
        val before = rows.size
        rows.removeAll { it.seq in doomed }
        rewrite()
        return before - rows.size
    }

    /** 只按条数裁剪（不含 TTL）。生产路径用 [trim]；这条留给测试与手动整理。 */
    fun trimTo(maxRows: Int): Int = trim(now = 0L, ttl = 0L, maxRows = maxRows)

    fun compact() = rewrite()

    private fun load() {
        val handle = openIfPresent(path) ?: return
        for (line in readLines(handle)) {
            val parts = line.split('\t')
            if (parts.size < 5) continue
            val seq = parts[0].toLongOrNull()?.takeIf { it > 0L } ?: continue
            val at = parts[1].toLongOrNull() ?: continue
            val peerId = unescapeField(parts[2])
            val direction = unescapeField(parts[3])
            if (peerId.isEmpty()) continue
            rows += StoredMessage(seq, peerId, direction, parts.drop(4).joinToString("\t"), at)
        }
        runCatching { handle.close() }
        // `seq` **照读文件里写的那个**，不再按行号重排。
        //
        // 为什么：`seq` 是 `since(peerId, sinceSeq)` 的游标。按行号重排有两个后果 ——
        //   ① 裁剪（`trim`）之后行号整体前移 ⇒ 调用方手里的游标指向错的行（重复或漏读）；
        //   ② 每次重启都重排 ⇒ 同一份数据在两次运行里 `latestSeq` 不一样。
        // 读文件里那个值、`nextSeq` 取最大值 + 1，`seq` 就成了跨重启、跨裁剪都稳定的游标。
        nextSeq = (rows.maxOfOrNull { it.seq } ?: 0L) + 1L
    }

    private fun rewrite() {
        val body = StringBuilder()
        // 写**行自己的 seq**，不是行号：裁剪后行号会前移，而 seq 是给游标用的（见 [load]）。
        rows.forEach { m ->
            body.append(m.seq).append('\t').append(m.at).append('\t')
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
            // 与 [FileQueueStore.appendLine] 同一条：给字节数，不给字符数。
            val bytes = (line + "\n").encodeToByteArray()
            handle.append(bytes, bytes.size)
            handle.flush()
        }
    }

    private companion object {
        const val DEFAULT_COMPACT_EVERY = 512
    }
}

/** [FileDedupStore] 的同款落盘实现搬到这里，与另外两个 store 并排。 */
internal fun fileDedupStore(path: String): DedupStore = app.relay.core.transport.FileDedupStore(path)
