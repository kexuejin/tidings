// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.DriverAuthRemediationUi

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
     *
     * [remediationDetail] 是该 CLI **确切**的补凭证动作（`codex login` / `DEEPSEEK_API_KEY`），
     * 来自 [DriverAuthStateMapping.remediationDetailOf] 的同一份词表。仿 multica：它不在
     * 配置页摆登录态表，而是失败后只给一句"该怎么修"——这里把那句话写实到可直接照做。
     * 空串（识别不出驱动）时退回通用措辞，绝不印一条空命令。
     *
     * @param remediationKind 动作类别决定动词：终端命令用 "Run"，环境变量/应用内动作
     *   用 "Set"。由 [DriverAuthRemediationUi] 归一，不在本文件按驱动名另写 if 链。
     */
    fun decorateError(
        authWaitSeen: Boolean,
        errorTail: String?,
        fallback: String,
        remediationDetail: String = "",
        remediationKind: DriverAuthRemediationUi = DriverAuthRemediationUi.NONE,
    ): String? {
        if (!authWaitSeen) return errorTail
        val detail = remediationDetail.trim()
        val remediation = if (detail.isEmpty()) {
            REMEDIATION
        } else {
            val verb = when (remediationKind) {
                DriverAuthRemediationUi.TERMINAL_LOGIN -> "Run"
                // TOKEN_ENV / APP_MODEL 的值都是环境变量名，措辞只能是 Set。
                DriverAuthRemediationUi.TOKEN_ENV, DriverAuthRemediationUi.APP_MODEL -> "Set"
                DriverAuthRemediationUi.NONE -> "Run"
            }
            REMEDIATION_PREFIX + verb + " '" + detail + "' on the host machine, then retry."
        }
        return remediation + " · " + (errorTail ?: fallback)
    }

    private const val REMEDIATION_PREFIX =
        "Authentication required: the CLI was waiting for login. "
    private const val REMEDIATION =
        REMEDIATION_PREFIX +
            "Sign in with this CLI on the host machine, then retry this quest."
}
