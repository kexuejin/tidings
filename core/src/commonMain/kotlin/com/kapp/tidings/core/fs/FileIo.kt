// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.fs

/** 随机访问的文件句柄。 */
interface RandomAccessFile {
    val size: Long
    /** 从 atOffset 读最多 count 字节；返回实际读到的字节数，-1 表示已到末尾。 */
    fun read(buffer: ByteArray, atOffset: Long, count: Int): Int
    /** 追加写入（永远写在当前末尾）。 */
    fun append(buffer: ByteArray, count: Int)

    /**
     * 在 [atOffset] 处写入（随机写；越过末尾会在中间留空洞，与 POSIX pwrite 同语义）。
     *
     * 为什么需要它：加密信道的分块数据面按**窗口 + 整窗重传**跑，帧到达顺序由
     * "哪条 TCP 先排空"决定，乱序与重复都是常态。只按到达顺序 append，乱序块会
     * 被写到错误的位置 —— 端到端表现为"字节数对得上、sha256 却对不上"
     * （回环 50MB 验收实测过：finish() 报 hash-mismatch）。按帧里的 offset 落盘
     * 才能让"重传补洞"这件事成立。
     */
    fun writeAt(atOffset: Long, buffer: ByteArray, count: Int)
    /** 把缓冲区刷到磁盘。 */
    fun flush()
    fun close()
}

expect fun openRandomAccess(path: String, forWrite: Boolean): RandomAccessFile
expect fun fileSize(path: String): Long?      // null = 不存在
expect fun fileExists(path: String): Boolean
expect fun deleteFile(path: String): Boolean
expect fun renameFile(from: String, to: String): Boolean
expect fun makeDirectories(path: String): Boolean
/** 文件或目录属性（共享文件柜/条目列举使用）。 */
data class FileAttrs(
    val exists: Boolean,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

expect fun fileAttributes(path: String): FileAttrs?
expect fun listDirectory(path: String): List<String>
/** 拼接路径。用 '/' 分隔；不要用平台的路径分隔符，协议里的相对路径永远是 '/'。 */
expect fun pathJoin(vararg parts: String): String

/**
 * [RandomAccessFile] 的 try/finally 版本。
 *
 * 落盘 store 全都要「打开 → 读/写 → 关」，而 [RandomAccessFile] 是本项目自己声明的
 * 最小接口（Kotlin 的 `Closeable` 用的是 `java.io.Closeable`，在 commonMain 里拿不到）。
 */
inline fun <T> RandomAccessFile.use(block: (RandomAccessFile) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

/**
 * 把对端给的相对路径变成可安全落盘的路径。
 * 返回 null 表示这个路径不能要（绝对路径、盘符、.. 逃逸、空段）。
 * 这不是"防呆"，是协议要求：FileMeta.path 是对端给的字符串，接收侧必须先 sanitize。
 */
fun sanitizeRelativePath(raw: String): String? {
    if (raw.isBlank()) return null
    // NUL 会在 JVM 侧被截断、在 native 侧被 NSString 提前终止 —— 两端看到的不是同一个路径。
    if (raw.contains('\u0000')) return null

    // 先归一化反斜杠：Windows 端发来的 `dir\file` 若不转换，整串会被当成一个合法文件名。
    val normalized = raw.replace('\\', '/')

    if (normalized.startsWith('/')) return null
    if (WINDOWS_DRIVE.containsMatchIn(normalized)) return null

    val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    // `..` 直接拒绝而不是折叠：折叠会把 `a/../../etc/x` 悄悄变成 `etc/x`，
    // 接收侧宁可让整条 FileMeta 失败，也不要猜对端的意图。
    if (segments.any { it == ".." }) return null
    if (segments.isEmpty()) return null
    return segments.joinToString("/")
}

/** `C:` / `c:` 这类盘符前缀。锚定在开头，只认 ASCII 字母。 */
private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

/**
 * 读一个小文件的全部字节。文件不存在、读不动、空文件、超过 [maxBytes]，一律返回 null。
 *
 * 为什么要有上限：调用方拿它去解码（图片内联预览就是第一个），而"先整个读进来再说"
 * 对一张 200MB 的"图片"就是直接 OOM。在这里拒绝比在解码器里崩好，而且对界面来说
 * 「太大了」和「文件没了」本来就是同一种结果 —— 画不出预览，退回普通文件卡。
 *
 * 读到 -1 就停，返回已经读到的部分：文件在对端还在写、或用户刚把它删了，都不是异常，
 * 是这条路径的常态。整个函数不抛异常（与 [directoryUsageBytes] 同一条纪律）。
 */
fun readFileBytes(path: String, maxBytes: Long = MAX_READ_BYTES): ByteArray? {
    val handle = runCatching { openRandomAccess(path, forWrite = false) }.getOrNull() ?: return null
    try {
        val size = handle.size
        if (size <= 0L || size > maxBytes) return null
        val buffer = ByteArray(size.toInt())
        var filled = 0
        while (filled < buffer.size) {
            val read = handle.read(buffer, filled.toLong(), buffer.size - filled)
            if (read <= 0) break
            filled += read
        }
        return if (filled <= 0) null else buffer.copyOf(filled)
    } catch (_: Throwable) {
        return null
    } finally {
        runCatching { handle.close() }
    }
}

/** [readFileBytes] 的默认上限：16MiB。预览用不到更大的，再大的图解码本身也是 OOM。 */
const val MAX_READ_BYTES: Long = 16L * 1024 * 1024

/**
 * 递归统计 [path] 下所有普通文件的字节数合计。目录不存在、读不到，都按 0 计。
 *
 * 这是给设置页「占用」那一行用的数字，所以它的第一要求是**不拖垮界面**：
 * - 用显式栈而不是递归，深目录不会把调用栈撑爆；
 * - 下探到 [maxDepth] 层就停 —— 正常的数据目录只有两三层，这条是给软链接成环兜底；
 * - 只用 `listDirectory` / `fileSize`，两者在三个平台上都是「失败返回空 / null」而不抛。
 *
 * 数字不追求与文件管理器逐字节一致：读不到的目录就是不计数。宁可少报，也不要在
 * 设置页上因为一个读不动的目录把整屏卡住。
 */
fun directoryUsageBytes(path: String, maxDepth: Int = MAX_USAGE_DEPTH): Long {
    var total = 0L
    val pending = ArrayDeque<Pair<String, Int>>()
    pending.addLast(path to 0)
    while (pending.isNotEmpty()) {
        val (dir, depth) = pending.removeLast()
        if (depth >= maxDepth) continue
        for (name in listDirectory(dir)) {
            val child = pathJoin(dir, name)
            val size = fileSize(child)
            if (size != null) {
                total += size
            } else {
                // fileSize 对目录返回 null（三平台一致），所以 null 就是要下探。
                pending.addLast(child to depth + 1)
            }
        }
    }
    return total
}

/** 目录下探上限。正常数据目录只有两三层，这条只是为了给软链接成环兜底。 */
const val MAX_USAGE_DEPTH: Int = 32
