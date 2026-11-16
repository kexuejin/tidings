// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

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
 * [probe] 的 runner 可注入 ⇒ 测试给合成输出，不依赖本机装没装 CLI。解析 [classify]
 * 是纯函数，输出串的判据只有一处。STRONG 只给命令真跑成功的 codex / claude；
 * opencode 的 `stored` 只证明「存过凭证」，不证明还有效 ⇒ 由映射层降级为 HINT。
 */
object CliAuthProbes {

    enum class Verdict { LOGGED_IN, LOGGED_OUT, UNKNOWN }

    data class Output(val exitCode: Int, val text: String)

    /** 这三家才有只读查询子命令；其余驱动走各自的凭证文件/env 探针。 */
    private val QUERYABLE = setOf("codex", "claude", "opencode")

    fun supports(key: String): Boolean = key in QUERYABLE

    /**
     * 只读查询的 argv（首项是 CLI 可执行文件路径）。命令来自各家 `--help` 与本机实测，
     * 不凭印象编：codex 有 `login` 子命令且 `status` 是其只读分支，claude 有 `auth status`
     * 输出 JSON，opencode 有 `auth list` 列出已存凭证。
     */
    fun queryOf(key: String, cliPath: String): List<String>? = when (key) {
        "codex" -> listOf(cliPath, "login", "status")
        "claude" -> listOf(cliPath, "auth", "status")
        "opencode" -> listOf(cliPath, "auth", "list")
        else -> null
    }

    /**
     * 纯解析：输出串 → 三态。合成输入可测，不读本机。
     *
     * **退出码不是判据**：codex / claude 在「未登录」时故意返回非 0（本机实测 exit=1），
     * 若按 `exitCode != 0 ⇒ UNKNOWN` 过滤，正好把唯一确定的状态全丢掉 —— 界面又变回
     * 「全未判定」。只有输出串对不上任何已知口径时才 UNKNOWN（含命令缺失 / 参数错）。
     */
    fun classify(key: String, out: Output): Verdict {
        return when (key) {
            "codex" -> when {
                CODE_LOGGED_OUT.containsMatchIn(out.text) -> Verdict.LOGGED_OUT
                CODE_LOGGED_IN.containsMatchIn(out.text) -> Verdict.LOGGED_IN
                else -> Verdict.UNKNOWN
            }
            "claude" -> when {
                CLAUDE_FALSE.containsMatchIn(out.text) -> Verdict.LOGGED_OUT
                CLAUDE_TRUE.containsMatchIn(out.text) -> Verdict.LOGGED_IN
                else -> Verdict.UNKNOWN
            }
            "opencode" -> when {
                OPENCODE_STORED.containsMatchIn(out.text) -> Verdict.LOGGED_IN
                out.text.isBlank() -> Verdict.LOGGED_OUT
                else -> Verdict.UNKNOWN
            }
            else -> Verdict.UNKNOWN
        }
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
     */
    fun jvmRunner(timeoutMs: Long = 5_000L): suspend (List<String>) -> Output = { argv ->
        withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                val proc = ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .directory(File(System.getProperty("user.home")))
                    .start()
                try {
                    val text = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val code = if (proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) proc.exitValue() else -1
                    Output(code, text)
                } finally {
                    if (proc.isAlive) proc.destroyForcibly()
                }
            }
        }
    }

    private val CODE_LOGGED_OUT = Regex("not\\s+logged\\s+in", RegexOption.IGNORE_CASE)
    private val CODE_LOGGED_IN = Regex("logged\\s+in", RegexOption.IGNORE_CASE)
    private val CLAUDE_FALSE = Regex("\"loggedIn\"\\s*:\\s*false")
    private val CLAUDE_TRUE = Regex("\"loggedIn\"\\s*:\\s*true")
    private val OPENCODE_STORED = Regex("stored", RegexOption.IGNORE_CASE)
}
