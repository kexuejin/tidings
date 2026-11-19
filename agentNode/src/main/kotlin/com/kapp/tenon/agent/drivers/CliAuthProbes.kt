// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File

/**
 * 交互式 CLI 的**只读登录态探针**（跑一次厂家自己的 status 子命令）。
 *
 * ## 为什么要有它
 *
 * 之前 codex / claude / opencode 的登录态被写死成 UNKNOWN —— 界面于是永远只能说
 * 「未判定」，用户一条确切状态都拿不到。三家其实都提供只读查询（本机实测）：
 *
 * - `codex login status` → `Not logged in` / `Logged in as …`
 * - `claude auth status` → `{"loggedIn": false|true, …}`
 * - `opencode auth list` → 有 `stored` 行 = 存过凭证；空 = 没存
 *
 * 这些都是**查询**，不写配置、不弹登录、不上传任何东西。跑不动 / 超时 / 解析不了
 * 一律 [Verdict.UNKNOWN]（放行），绝不替正式派单做决定 —— 判据与 `AgyAuthProbe` 同口径。
 *
 * ## 注入与证据强度
 *
 * [probe] 的 runner 可注入 ⇒ 测试给合成输出，不依赖本机装没装 CLI。判定**声明**
 * （argv + 有序正则信号 + golden 夹具）住在 `driver-specs.json` 的 `authProbe`，经
 * [CliDriverSpecs.authProbeOf] 投影读取；这里只执行判定（信号顺序、退出码纪律是机制）。
 * STRONG 只给命令真跑成功的 codex / claude；opencode 的 `stored` 只证明「存过凭证」，
 * 由映射层降级为 HINT。
 */
object CliAuthProbes {

    enum class Verdict { LOGGED_IN, LOGGED_OUT, UNKNOWN }

    data class Output(val exitCode: Int, val text: String)

    /**
     * 是否接了厂家只读 status 查询：声明为 CLI_CMD 的驱动才算（codex/claude/opencode）。
     * agy 走 CLI_MARKER（`agy models` + marker），由 `AgyAuthProbe` 单独判，不进这里。
     */
    fun supports(key: String): Boolean =
        CliDriverSpecs.authProbeOf(key)?.kind == CliDriverSpecs.AuthProbeKind.CLI_CMD

    /**
     * 只读查询的 argv（首项是 CLI 可执行文件路径）。子命令骨架来自 JSON 投影的
     * `authProbe.argv`，`${cliPath}` 由发现器给的绝对路径替换——命令出处可在真源核对，
     * 不在代码里另写一份。
     */
    fun queryOf(key: String, cliPath: String): List<String>? {
        val argv = CliDriverSpecs.authProbeOf(key)?.argv?.takeIf { it.isNotEmpty() } ?: return null
        return argv.map { if (it == AUTH_CLI_TOKEN) cliPath else it }
    }

    /**
     * 纯解析：输出串 → 三态。声明（有序信号）从 JSON 投影读，合成输入可测，不读本机。
     *
     * **退出码不是判据**：codex / claude 在「未登录」时故意返回非 0（本机实测 exit=1），
     * 若按 `exitCode != 0 ⇒ UNKNOWN` 过滤，正好把唯一确定的状态全丢掉 —— 界面又变回
     * 「全未判定」。只有输出串对不上任何已知信号时才 UNKNOWN（含命令缺失 / 参数错）。
     * 信号**顺序**是声明的一部分（codex 的 `not logged in` 必须先于 `logged in`，否则
     * 后者永远命中）——投影按 JSON 数组顺序给出，这里不重排。
     */
    fun classify(key: String, out: Output): Verdict {
        val spec = CliDriverSpecs.authProbeOf(key) ?: return Verdict.UNKNOWN
        if (spec.kind != CliDriverSpecs.AuthProbeKind.CLI_CMD) return Verdict.UNKNOWN
        for (signal in spec.signals) {
            val hit = if (signal.blank) {
                out.text.isBlank()
            } else {
                val regex = signal.regex ?: continue
                val opts = if (signal.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(regex, opts).containsMatchIn(out.text)
            }
            if (hit) {
                return when (signal.verdict) {
                    CliDriverSpecs.AuthVerdict.AUTHENTICATED -> Verdict.LOGGED_IN
                    CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED -> Verdict.LOGGED_OUT
                    CliDriverSpecs.AuthVerdict.UNKNOWN -> Verdict.UNKNOWN
                }
            }
        }
        return Verdict.UNKNOWN
    }

    /**
     * 跑一次只读查询。任何异常 / 不支持 / 找不到 CLI 都降级 UNKNOWN，不抛到 UI。
     * [runner] 超时由实现保证；这里只做异常兜底，保证「读不到」与「没有」分开。
     */
    suspend fun probe(
        key: String,
        cliPath: String?,
        runner: suspend (List<String>) -> Output,
    ): Verdict {
        if (cliPath.isNullOrBlank()) return Verdict.UNKNOWN
        val argv = queryOf(key, cliPath) ?: return Verdict.UNKNOWN
        return runCatching { classify(key, runner(argv)) }
            .getOrDefault(Verdict.UNKNOWN)
    }

    /**
     * JVM 子进程执行器：合并 stderr、限定超时、读完后回收进程。
     * cwd 设为 home —— 各家的凭证配置都在用户目录，别让调用方的临时 cwd 影响判定。
     *
     * 超时由 [ProbeProcess] 的看门狗杀进程实现（阻塞读上 `waitFor(timeout)` 是假超时）。
     * 被杀时**抛**而不是把残片当输出：`opencode auth list` 的「空输出」语义是「没存凭证」，
     * 若把超时混成空串，界面会显示一个确切但错误的结论 —— 这里宁可降级 UNKNOWN。
     */
    fun jvmRunner(timeoutMs: Long = 5_000L): suspend (List<String>) -> Output = { argv ->
        val result = ProbeProcess.run(argv, timeoutMs, File(System.getProperty("user.home")))
        if (result.timedOut) {
            throw IllegalStateException("probe timed out after " + timeoutMs + "ms: " + argv.firstOrNull())
        }
        Output(result.exitCode, result.text)
    }

    /** argv 模板里的 CLI 路径占位符；真值由发现器给的绝对路径替换。 */
    private const val AUTH_CLI_TOKEN = "\${cliPath}"
}
