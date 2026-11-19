// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只读探测子进程的**唯一执行器**（`--version`、`auth status`、`models` 这类）。
 *
 * ## 为什么不能直接 `readText()` 再 `waitFor(timeout)`
 *
 * 这是本层以前两处探针共同的写法，看起来有超时，实际没有：`readText()` 要读到 **EOF**
 * 才返回，而 EOF 只在子进程关闭 stdout 时才来。于是
 *
 * 1. 一个读 stdin、或干脆挂住的 CLI ⇒ `readText()` 永远不返回，`waitFor(timeout)` 那行
 *    永远执行不到（超时判断排在阻塞读**之后**，等于没写）；
 * 2. 协程层的 `withTimeout` 也救不了：它只能在线程回到挂起点时抛，而线程一直卡在阻塞
 *    读里 —— 超时到了，探测仍在跑，调用方（设置页 / 诊断）看着像卡死。
 *
 * 正确形态是**看门狗杀进程**：超时由独立线程执行 `destroyForcibly()`，子进程一死
 * stdout 就关，阻塞读拿到 EOF 自然醒。所以「读」永远只等 EOF，「超时」只负责杀。
 *
 * ## 语义
 *
 * - 启动失败 / 超时被杀 ⇒ [Result.timedOut] 或异常，由调用方决定降级成什么；
 *   **绝不返回「空输出 = 正常」**，否则 `opencode auth list` 的空输出（= 没存凭证）
 *   会跟「根本没跑起来」混成同一件事。
 * - 退出码原样带回：探针的判据是输出串，不是退出码（各家「未登录」时故意非 0）。
 */
object ProbeProcess {

    /** 一次探测的结果。[timedOut] 为真时 [text] 只是被杀之前的残片，不可当结论。 */
    data class Result(val exitCode: Int, val text: String, val timedOut: Boolean)

    /** 超时被杀时的退出码：POSIX `128 + SIGKILL(9)`。与「CLI 自己 exit 137」同值，只用于显示。 */
    const val KILLED_EXIT: Int = 137

    /**
     * 跑一次并读到 EOF，最多等 [timeoutMs]。
     *
     * [dir] 是 cwd（各家的凭证配置在用户目录，探测不该受调用方临时 cwd 影响）。
     * 超时被杀**不抛**，返回 [Result.timedOut] = true，让调用方按自己的口径降级。
     */
    suspend fun run(argv: List<String>, timeoutMs: Long, dir: File? = null): Result =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            dir?.let { pb.directory(it) }
            val proc = pb.start()
            val timedOut = AtomicBoolean(false)
            // 看门狗：唯一能在「阻塞读」上生效的超时手段（见类注释）。
            val watchdog = Thread {
                runCatching {
                    if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        timedOut.set(true)
                        proc.destroyForcibly()
                    }
                }
            }
            watchdog.isDaemon = true
            watchdog.start()
            try {
                val text = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                // EOF 之后这里立刻返回；留一点余量是为了等退出码落定（被杀时也是立刻的）。
                if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    timedOut.set(true)
                    proc.destroyForcibly()
                    proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                }
                val code = if (timedOut.get()) KILLED_EXIT else runCatching { proc.exitValue() }.getOrDefault(-1)
                Result(code, text, timedOut.get())
            } finally {
                // 正常路径到这里子进程已经退出；异常路径（读中断、调用方取消）必须收尾，
                // 不然看门狗线程要陪它等满整个超时。
                if (proc.isAlive) proc.destroyForcibly()
                watchdog.interrupt()
            }
        }
}
