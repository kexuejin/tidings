// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.renameFile

/**
 * 入站去重日志的文件名。与聊天记录 `messages.json` 同级，落在同一个数据根下。
 *
 * 名字只在这一处定义：仓库里已经吃过一次亏 —— `messages.json` 这个名字曾在三个平台
 * 各写一遍，改一处漏两处没人发现。路径请一律走 [dedupLogPath]。
 */
const val DEDUP_LOG_NAME: String = "dedup.log"

/** 去重日志的完整路径。 */
fun dedupLogPath(dataDirectory: String): String = pathJoin(dataDirectory, DEDUP_LOG_NAME)

/**
 * 去重表一次最多读回的字节数。
 *
 * 正常水位是几十 KB（24 小时 TTL + 每次 [FileDedupStore.prune] 压一次），这个上限是给
 * "日志被别的东西撑大了"兜底。超过时读**末尾**这一段而不是整份：去重看的是最近收过什么，
 * 尾部才是有效的那一半。
 */
private const val MAX_LOAD_BYTES: Long = 8L * 1024 * 1024

/**
 * 落盘的入站去重表（协议：同一 `msg_id` + `peer_id` 只投递一次）。
 *
 * ## 为什么需要它
 *
 * 去重表原来只有内存实现（[InMemoryDedupStore]），应用一重启就空了。后果不是"少去重几条"
 * 而是**对方重放同一帧会被当成新消息再收一次** —— 对端在没收到 ACK 时本来就会重传
 * （见 `Messenger.sendAwaitAck`），重启窗口正好落在重传窗口里，于是同一条消息在聊天里
 * 出现两次。出站队列与聊天记录都已落盘，去重表是这条链上最后一块漏的。
 *
 * ## 磁盘格式
 *
 * 每行一条 `at \t escaped(peerId) \t escaped(msgId)`，**追加**写；[prune] 时整份重写。
 * 追加而不是每条都重写：收一条消息就重写一次整份文件，收得越多写得越慢，最后变成
 * 平方级。追加是 O(1)，重写只发生在剪枝和每 [compactEvery] 条之后的兜底压缩。
 *
 * `peerId` / `msgId` 都来自**对端发的帧**，没有格式校验，里面完全可能有 `\t`、换行、
 * 反斜杠。所以两侧都要转义（见 [escape]）—— 否则对端发一个带换行的 id 就能伪造出
 * 一整行假记录，把去重表污染成"某些消息永远收不到"。**这不是防呆，是输入校验。**
 *
 * ## 坏数据不拦路
 *
 * 与 `FileMessageStore` 同一条纪律：存储读不出来、写不进去、单行解析不了，一律降级为
 * 「这条没落盘 / 这份当空表」，绝不让聊天因此起不来。丢的是去重能力，不是消息。
 *
 * @param path 日志文件的完整路径，用 [dedupLogPath] 拼。
 * @param compactEvery 追加多少条之后兜底压一次；[prune] 无论如何都会压。
 */
class FileDedupStore(
    private val path: String,
    private val compactEvery: Int = DEFAULT_COMPACT_EVERY,
) : DedupStore {

    /**
     * 键是**转义后**的 `peerId \t msgId`，值是收到时刻。
     *
     * 键里直接存转义后的形式而不是原始 id：重写文件时不用再转义一遍，[has] 与 [add]
     * 查表也是同一把键，不会出现"查用原始 id、存用转义 id"这种对不上的错。
     */
    private val seen = LinkedHashMap<String, Long>()

    private var appendsSinceCompact = 0

    init {
        load()
    }

    override fun has(msgId: String, peerId: String): Boolean = seen.containsKey(logKey(peerId, msgId))

    override fun add(msgId: String, peerId: String, at: Long) {
        val key = logKey(peerId, msgId)
        seen[key] = at
        appendLine("$at\t$key")
        appendsSinceCompact++
        if (appendsSinceCompact >= compactEvery) compact()
    }

    override fun prune(now: Long, ttl: Long): Int {
        val before = seen.size
        val cutoff = now - ttl
        seen.entries.removeAll { it.value < cutoff }
        val dropped = before - seen.size
        // 只在真删掉东西时重写：只从内存里删、文件里还留着，这个文件只会一直长；
        // 但**没删掉**也重写就是每次心跳都把整份日志写一遍 —— 剪枝会被周期调用，那才是真浪费。
        if (dropped > 0) compact()
        return dropped
    }

    // ------------------------------------------------------------------ 磁盘

    private fun load() {
        val text = readTail() ?: return
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val at = line.substring(0, tab).toLongOrNull() ?: continue
            val key = line.substring(tab + 1)
            if (!isWellFormedKey(key)) continue
            val previous = seen[key]
            // 同一个键写了两遍就取更晚的那次：重放窗口里同一帧可能被 add 两次。
            if (previous == null || at > previous) seen[key] = at
        }
        // 读回来的行数比有效条目多，说明有重复行或坏行 —— 顺手压一次，别把它带回磁盘。
        if (seen.size != countNonEmptyLines(text)) compact()
    }

    private fun appendLine(line: String) {
        val bytes = (line + "\n").encodeToByteArray()
        val handle = runCatching { openRandomAccess(path, forWrite = true) }.getOrNull() ?: return
        try {
            handle.append(bytes, bytes.size)
        } catch (t: Throwable) {
            // 写不进去只是这条没落盘：本次会话的内存表里它已经在，去重照常。
        } finally {
            runCatching { handle.close() }
        }
    }

    /** 整份重写：先写 `.tmp` 再改名，避免写到一半崩掉留下半份日志。 */
    private fun compact() {
        appendsSinceCompact = 0
        val tmp = "$path.tmp"
        if (!writeWholeFile(tmp, render())) {
            deleteFile(tmp)
            return
        }
        if (renameFile(tmp, path)) return
        // 目标已存在时 rename 会失败（JVM 的 `File.renameTo`、iOS 的 `moveItemAtPath`
        // 都是这个语义，Windows 上尤其明显）：删掉旧的再改名。
        // 先确认 tmp 真在，否则这一删就把好端端的日志删没了。
        if (!fileExists(tmp)) return
        deleteFile(path)
        if (!renameFile(tmp, path)) deleteFile(tmp)
    }

    private fun render(): String = buildString {
        for ((key, at) in seen) {
            append(at).append('\t').append(key).append('\n')
        }
    }

    private fun writeWholeFile(target: String, text: String): Boolean {
        deleteFile(target)
        val handle = runCatching { openRandomAccess(target, forWrite = true) }.getOrNull() ?: return false
        return try {
            val bytes = text.encodeToByteArray()
            if (bytes.isNotEmpty()) handle.append(bytes, bytes.size)
            handle.flush()
            true
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { handle.close() }
        }
    }

    /**
     * 读回日志正文。
     *
     * 超过 [MAX_LOAD_BYTES] 时只读末尾那一段，并丢掉开头那个被截断的半行 ——
     * 一条半行解析不出来，代价是少一条去重记录，比整份读进内存好。
     */
    private fun readTail(): String? {
        val handle = runCatching { openRandomAccess(path, forWrite = false) }.getOrNull() ?: return null
        return try {
            val size = handle.size
            if (size <= 0L) return ""
            val from = if (size > MAX_LOAD_BYTES) size - MAX_LOAD_BYTES else 0L
            val buffer = ByteArray((size - from).toInt())
            var filled = 0
            while (filled < buffer.size) {
                val read = handle.read(buffer, from + filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            val text = buffer.decodeToString(0, filled)
            if (from == 0L) text else text.substringAfter('\n', missingDelimiterValue = "")
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { handle.close() }
        }
    }

    // ------------------------------------------------------------------ 行格式

    private fun logKey(peerId: String, msgId: String): String = escape(peerId) + '\t' + escape(msgId)

    /** 键必须正好是"两段转义文本、中间一个制表符"，且两段都解得开。 */
    private fun isWellFormedKey(key: String): Boolean {
        val tab = key.indexOf('\t')
        if (tab < 0) return false
        val peerId = unescape(key.substring(0, tab)) ?: return false
        val msgId = unescape(key.substring(tab + 1)) ?: return false
        return peerId.isNotEmpty() && msgId.isNotEmpty()
    }

    private companion object {
        const val DEFAULT_COMPACT_EVERY = 512
    }
}

private fun countNonEmptyLines(text: String): Int = text.count { it == '\n' } + if (text.isEmpty() || text.endsWith('\n')) 0 else 1

/**
 * 转义：反斜杠、制表符、换行、回车。
 *
 * 只转这四个就够 —— 它们正是行格式用到的分隔符与终止符。其余字符原样保留（含中文），
 * 日志因此仍然是人能直接读的。
 */
private fun escape(raw: String): String = buildString(raw.length) {
    for (c in raw) {
        when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }
}

/** [escape] 的逆运算。遇到不认识的转义序列返回 null（不猜、不吞）。 */
private fun unescape(raw: String): String? = buildString(raw.length) {
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\') {
            append(c)
            i++
            continue
        }
        if (i + 1 >= raw.length) return null
        when (raw[i + 1]) {
            '\\' -> append('\\')
            't' -> append('\t')
            'n' -> append('\n')
            'r' -> append('\r')
            else -> return null
        }
        i += 2
    }
}
