// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * 支线日志流里的「CLI 在等登录授权」检测（真机验收现场：quest 挂了 60 秒，
 * 机主只看到一句 Authentication required —— 卡片上没有任何解释，也不知道该干什么）。
 *
 * ## 为什么是「失败原因改写」而不是「挂起问卡等粘码」
 *
 * 曾经的设想是检测到 auth 行 → 翻 WAITING_INPUT 问卡 → 把授权码写进子进程 stdin。
 * 两条路都走不通，如实记录：
 *
 * 1. **子进程 stdin 在内核层接的是 /dev/null**（`CliSubprocessDriver` 的既定决策，
 *    有注释与真机回归钉住：stdin 开管道会让「读 stdin 等 EOF」的 CLI 永久挂死）——
 *    没有一条能写进去的管道；
 * 2. 观测到的 CLI 授权流是**云端回调 + 自身 60s 硬超时**，过程里并不读 stdin ——
 *    就算有管道，码也送不到。
 *
 * 所以这里只做**如实检测 + 失败改写**：日志流出现 auth 等待行时记下来；任务以
 * 非 finished 结束时，把 FAILED 摘要改写成「需要登录 → 在宿主本机完成该 CLI 的
 * 登录 → 点重试」。重试是真实可用的动作：它会用同一份 prompt/agentKey 重新拉起
 * 子进程，新进程带着登录后的凭据，跑得动。绝不画一颗「粘贴授权码」的假按钮 ——
 * 按钮在骗人比没有按钮糟得多（DeadButtonGuard 同一条纪律）。
 */
internal object QuestAuthWaitDetector {

    /**
     * 触发判据：日志行包含以下任一短语（大小写不敏感）。清单来自真机终端流
     * 的实际输出；保持短而准 —— 误报会把正常失败改写成登录指引。
     */
    private val PATTERNS = listOf(
        "authentication required",
        "waiting for authentication",
        "paste the authorization code",
        "authentication timed out",
    )

    /** 一条日志流是不是「CLI 在等登录授权」。 */
    fun isAuthWaitLine(line: String): Boolean {
        val lower = line.lowercase()
        return PATTERNS.any { lower.contains(it) }
    }

    /**
     * 检测命中时的 FAILED 摘要改写：补救指引在前（用户第一眼看到该干什么），
     * CLI 的原始错误尾巴在后（A1 的可追溯链路不丢）。
     */
    fun decorateError(authWaitSeen: Boolean, errorTail: String?, fallback: String): String? =
        if (!authWaitSeen) {
            errorTail
        } else {
            REMEDIATION + " · " + (errorTail ?: fallback)
        }

    private const val REMEDIATION =
        "Authentication required: the CLI was waiting for login. " +
            "Sign in with this CLI on the host machine, then retry this quest."
}
