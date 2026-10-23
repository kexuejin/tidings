// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.fs.listDirectory
import com.kapp.tidings.core.fs.pathJoin

/**
 * `.part` 半成品清扫：磁盘上的半截文件怎么认、怎么删。
 *
 * 切出来的理由：这组逻辑的演化动力只有一个 —— **下载目录被配错了怎么办**。
 * 它不读 [TransferManager] 的任何在途状态表（唯一耦合是「正在拉的 `.part` 要保护」，
 * 由调用方当参数传进来），因此能独立推演、独立测试，也不会因为传输协议变动而跟着改。
 *
 * 删 `.part` 的规则只有一处（[discardPart]）—— 清扫、用户放弃、在途取消都走它。
 * 这是 `x.part.part` 那个 bug 换来的纪律（见 [discardPartFile]）。
 */

object TransferPartSweeper {

/** `.part` 清扫的走查上限（深度 / 条目）。防的是"下载目录被指到家目录"这种配置事故。 */
    const val SWEEP_MAX_DEPTH: Int = 8
    const val SWEEP_MAX_ENTRIES: Int = 5_000

/** `C:/` 这类 Windows 绝对路径前缀；协议里的相对路径永远用 '/'。 */
    val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:/")

/** 删掉半截的 `.part`（用户明确放弃时用；普通失败要留着续传）。
 *
 * 参数是**正式文件的路径**（与 [existingPartSize] 同一个坐标系），后缀由本方法补上。
 *
 * 调用点（**真实路径**，不是留给未来的空 API）：
 * - [sweepStaleParts]：启动清扫（[start] 自动调）/ 上层主动清理残留；
 * - [cancelIncoming] 与 [cancelActivePulls]：用户放弃一份正在拉的传输。
 */
    fun discardPart(path: String) {
    deleteFile("$path$PART_SUFFIX")
}

/**
 * 删一个**已经带 `.part` 后缀**的全路径。
 *
 * 存在的唯一理由是坐标系差一层：清扫与在途登记表里的路径是"磁盘上那个文件"（`x.part`），
 * 而 [discardPart] 收的是"正式文件"（`x`）。第一版在这两处直接调 [discardPart]，
 * 后缀被叠成 `.part.part`，于是"放弃下载"什么都没删掉 —— 这个 bug 被
 * `TransferLifecycleTest.cancelIncomingDiscardsThePartOfARunningPull` 钉住了。
 *
 * 之所以不是 `private`：[leftoverParts] 交给上层的就是这种带后缀的全路径，
 * 上层要"清掉这些残片"时手里只有它 —— 让上层自己去 `removeSuffix(".part")`
 * 等于把坐标系知识复制出去，那正是这个 bug 的成因。
 */
    fun discardPartFile(partPath: String) {
    discardPart(partPath.removeSuffix(PART_SUFFIX))
}

/** 目标文件已存在时的大小，UI 想显示「续传中」可以问它。 */
    fun existingPartSize(path: String): Long = fileSize("$path$PART_SUFFIX") ?: 0L

/** 现在盘上还剩哪些半截文件（只读，不删）。上层要显示「有 N 个未完成的下载」可以问它。 */
    fun leftoverParts(downloadDir: () -> String): List<PartFile> =
    sweepRootOrNull(downloadDir)?.let { listPartsUnder(it) } ?: emptyList()

/**
 * 扫一遍下载目录，删掉**没有任何在途传输认领**的 `.part`，返回清单。
 *
 * [protected] 是「正在拉的传输将来会写的那些 `.part`」—— 由调用方在 `stateLock` 内
 * 取快照传进来，因为「当前在拉什么」只有 [TransferManager] 知道，本文件不碰它的锁。
 *
 * ## 「过期」在本层是怎么定义的
 *
 * 这里**不看时间戳**，看的是"有没有人能认领它"：传输计划是进程内的内存表，重启即空，
 * 所以**进程启动时存在的任何一个 `.part` 都永远不会被续传**——它已经与任何可能的
 * transferId 失去关联，删掉不损失任何可恢复的信息。这比"按文件年龄删"更保守。
 *
 * ## 故意不扫的根目录（返回 `scanned = false`）
 *
 * - 空串 / `.` / `..`：根就是进程 CWD，`.part` 这个后缀太普通（浏览器、下载器都用它），
 *   在其中删文件等于替用户清理**别人**的残留。宿主没配下载目录（默认值就是 `.`）时，
 *   宁可什么都不做。
 * - 相对路径：同上。
 *
 * 深度与条目数都有上限（[SWEEP_MAX_DEPTH] / [SWEEP_MAX_ENTRIES]），配置错误不会变成
 * 一次全盘遍历。
 */
    fun sweepStaleParts(
    downloadDir: String,
    keep: Set<String> = emptySet(),
    protected: Set<String> = emptySet(),
    discard: (String) -> Unit,
): PartSweepReport {
    val root = sweepRootOrNull { downloadDir }
        ?: return PartSweepReport(root = downloadDir, scanned = false, deleted = emptyList(), freedBytes = 0L, failed = emptyList())
    val guard = protected + keep
    val deleted = mutableListOf<String>()
    val failed = mutableListOf<String>()
    var freed = 0L
    for (part in listPartsUnder(root)) {
        if (part.path in guard) continue
        // 真的走 discard（而不是在这里再写一次 deleteFile）：删 `.part` 的规则只有一处。
        discard(part.path)
        if (fileExists(part.path)) failed += part.path else { deleted += part.path; freed += part.size }
    }
    return PartSweepReport(root = root, scanned = true, deleted = deleted, freedBytes = freed, failed = failed)
}

// ---------------------------------------------------------------- 清扫内部

/** 安全可扫的根目录；`null` = 不扫（理由见 [sweepStaleParts]）。 */
    private fun sweepRootOrNull(downloadDir: () -> String): String? {
    val raw = downloadDir().trim()
    if (raw.isEmpty()) return null
    val normalized = raw.trimEnd('/')
    if (normalized.isEmpty() || normalized == "." || normalized == "..") return null
    if (!normalized.startsWith("/") && !WINDOWS_ABSOLUTE.containsMatchIn(normalized)) return null
    return normalized
}

/**
 * 走查根目录下的 `.part`。
 *
 * 判定"是文件"用 [fileSize]：它在目录上返回 null（两端实现都这样），所以不用另开一个
 * `isDirectory` 的平台 API；返回 null 的就当目录继续往下走（坏链接的代价只是白走一趟）。
 */
    private fun listPartsUnder(root: String): List<PartFile> {
    if (!fileExists(root)) return emptyList()
    val found = mutableListOf<PartFile>()
    var visited = 0
    val queue = ArrayDeque<Pair<String, Int>>()
    queue.addLast(root to 0)
    while (queue.isNotEmpty()) {
    val (dir, depth) = queue.removeFirst()
    for (name in listDirectory(dir)) {
        if (visited >= SWEEP_MAX_ENTRIES) return found
        visited++
        val child = pathJoin(dir, name)
        val size = fileSize(child)
        if (size != null) {
        // 普通文件。名字是 `<final>.part` 才算半成品。
        if (name.endsWith(PART_SUFFIX) && name.length > PART_SUFFIX.length) found += PartFile(child, size)
        continue
        }
        if (depth + 1 <= SWEEP_MAX_DEPTH) queue.addLast(child to (depth + 1))
    }
    }
    return found
}
}
