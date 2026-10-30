// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import java.io.File

/**
 * CLI 子进程驱动的可执行文件发现（M7-T1）。
 *
 * 顺序（AGENTS.md §2.7 条款 2 的「显式 env → 约定目录 → 找不到就大声说」纪律）：
 * 1. 显式环境变量（[ENV_CLAUDE_CLI] / [ENV_CODEX_CLI]）；
 * 2. `$PATH` 逐目录探测；
 * 3. 常见安装约定目录（`$HOME/.local/bin`、`/usr/local/bin`、`/opt/homebrew/bin`）——
 *    全部是**公开约定**，绝不写死个人绝对路径；
 * 4. 找不到 = null：装配方（`cliDriverRegistrations`）跳过注册并打印可操作提示，
 *    不静默退回、不 crash。
 *
 * env 与目录清单可注入 —— 测试不依赖本机装没装（envDependentTestGate）。
 */
object CliDiscovery {

    /** 显式指定 Claude Code 可执行文件的 env 名。 */
    const val ENV_CLAUDE_CLI = "TIDINGS_CLAUDE_CLI"

    /** 显式指定 Codex 可执行文件的 env 名。 */
    const val ENV_CODEX_CLI = "TIDINGS_CODEX_CLI"

    const val CLAUDE_BIN = "claude"
    const val CODEX_BIN = "codex"

    fun resolveClaudeCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? = resolve(env[ENV_CLAUDE_CLI], CLAUDE_BIN, pathDirs)

    fun resolveCodexCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? = resolve(env[ENV_CODEX_CLI], CODEX_BIN, pathDirs)

    /** 探测目录：`$PATH` 全量 + 公开约定目录（去重，保序 = 优先级）。 */
    fun pathDirsOf(env: Map<String, String>): List<String> {
        val dirs = mutableListOf<String>()
        env["PATH"]?.split(':')?.filter { it.isNotBlank() }?.let { dirs.addAll(it) }
        env["HOME"]?.takeIf { it.isNotBlank() }?.let { dirs.add(it + "/.local/bin") }
        dirs.add("/usr/local/bin")
        dirs.add("/opt/homebrew/bin")
        return dirs.distinct()
    }

    /** 显式 env 指向的文件必须**可执行**才算数；否则按 PATH 逐目录找第一份可执行的。 */
    private fun resolve(explicit: String?, binaryName: String, pathDirs: List<String>): String? {
        explicit?.takeIf { it.isNotBlank() }?.let { path ->
            return path.takeIf { File(it).canExecute() }
        }
        return pathDirs.firstNotNullOfOrNull { dir ->
            File(dir, binaryName).takeIf { it.isFile && it.canExecute() }?.absolutePath
        }
    }
}
