// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.Reader

/**
 * 驱动行流的单行长度上限（触发式编排提案 §8.2 传输防火墙 / §9 阶段三）。
 *
 * 四处读取循环共用这一份常量：`ProcessAgentDriver`（CLI 子进程 stdout + stderr）、
 * `ExternalPluginDriver`（插件 NDJSON 流）、`WorktreeSandboxExecutor`（沙箱命令
 * 输出泵）、`CliSubprocessDriver`（CLI stdout 合流）。
 *
 * **光有这个常量不够**：`readLine()` / `useLines` / `forEachLine` 对行长零限制，
 * 一个失控子进程输出一行 1 GB 的「JSON」会**整行先读进宿主堆**，再拿 `length`
 * 去比——判据成立的时候 OOM 已经发生了。所以判据必须落在**读取方式**上：
 * 一律走 [BoundedLineReader]，它累积到上限就停止累积（该行剩余字节边读边丢，
 * 保持流同步），再返回 [BoundedLine.TooLong] 让调用方止损。
 *
 * 预算依据：协议侧最大事件形状是 64 KiB 帧（`Limits.MAX_FRAME_BYTES`），卡 24 KiB；
 * 这里给 4 倍余量。超限不是静默跳过——调用方必须以各自的方式**响亮地**失败
 * （`DriverEvent.Error` / 杀进程 / 断开连接），与「认不出的行优雅跳过」的安全红线
 * 区分开：超长行是宿主无法理解的事件，继续读等于把 OOM 风险留给下一行。
 */
object DriverLineLimits {
    /** 单行字符数上限（≈ 1 MiB 的 UTF-8，事件帧预算的 4 倍余量）。 */
    const val MAX_LINE_CHARS: Int = 256 * 1024
}

/** [BoundedLineReader.readLine] 的结果。 */
sealed interface BoundedLine {
    /** 一条完整的行（不含行终止符）。 */
    data class Text(val value: String) : BoundedLine

    /**
     * 这一行超过了上限：**已丢弃该行剩余字节**，流仍停在下一条行的开头。
     *
     * 为什么要丢完而不是立刻返回：不丢的话，剩下的半行会被当成下一条「新行」的开头，
     * 后续每一条都会被拼歪——那比直接失败更难查。丢完的代价是这一行读得慢（要读到
     * 下一个换行为止），换来的是「返回之后流一定是同步的」这条可依赖的保证。
     */
    data object TooLong : BoundedLine

    /** 流结束（且没有残留字符）。 */
    data object Eof : BoundedLine
}

/**
 * 有界行读取器：替代 `BufferedReader.readLine()` / `useLines` / `forEachLine`。
 *
 * 与 `readLine()` 的语义差异只有一条、且是刻意的：**超过 [limit] 的行不返回内容**，
 * 返回 [BoundedLine.TooLong] 并把该行剩余字节读完丢掉。其余（`\n` / `\r\n` / 裸 `\r`
 * 三种行终止、末尾无换行的最后一行、EOF 判据）与 `readLine()` 一致。
 *
 * 裸 `\r` 必须当成行终止符：进度条类输出（curl / npm）用 `\r` 原地刷新，若只认 `\n`，
 * 一整段进度刷新会被拼成一条超长「行」，于是误判 [BoundedLine.TooLong] 并把正常进程
 * 杀掉 —— 这是「为了防 OOM 引入误杀」，比原缺陷更糟。
 *
 * 非线程安全：一个实例只在一个读循环里用。
 */
class BoundedLineReader(
    private val reader: Reader,
    private val limit: Int = DriverLineLimits.MAX_LINE_CHARS,
) {
    /** 上一行以裸 `\r` 结尾时，本行开头可能跟一个 `\n`（即 `\r\n`），需要吞掉。 */
    private var pendingLineFeed = false

    /** 裸 `\r` 之后多读到的那个字符（不是 `\n` 时留到下一行用）。 */
    private var pushback: Int = -1

    init {
        require(limit > 0) { "limit must be positive, was $limit" }
    }

    fun readLine(): BoundedLine {
        if (pendingLineFeed) {
            pendingLineFeed = false
            val next = reader.read()
            // `\r` 后面不是 `\n`：那是一个独立的裸 `\r` 行终止符，这个字符属于下一行。
            if (next >= 0 && next != '\n'.code) pushback = next
        }

        // 上限之后不再增长：容量先按小步给，避免为一条短行预留 256 KiB。
        val sb = StringBuilder(minOf(limit, 4096))
        var overflowed = false

        while (true) {
            val ch = nextChar()
            if (ch < 0) {
                return when {
                    overflowed -> BoundedLine.TooLong
                    sb.isEmpty() -> BoundedLine.Eof
                    else -> BoundedLine.Text(sb.toString())
                }
            }
            val c = ch.toChar()
            when {
                c == '\n' -> return if (overflowed) BoundedLine.TooLong else BoundedLine.Text(sb.toString())
                c == '\r' -> {
                    pendingLineFeed = true
                    return if (overflowed) BoundedLine.TooLong else BoundedLine.Text(sb.toString())
                }
                // 已经超限：只把本行剩余字符读掉，不累积。
                overflowed -> Unit
                sb.length >= limit -> overflowed = true
                else -> sb.append(c)
            }
        }
    }

    private fun nextChar(): Int {
        val pending = pushback
        if (pending >= 0) {
            pushback = -1
            return pending
        }
        return reader.read()
    }
}
