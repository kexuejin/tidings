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

    /** Antigravity CLI（形态 A）：Google 登录态继承，无 Key 可配。 */
    const val ENV_AGY_CLI = "TIDINGS_AGY_CLI"
    const val AGY_BIN = "agy"

    fun resolveClaudeCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? = resolve(env[ENV_CLAUDE_CLI], CLAUDE_BIN, pathDirs)

    fun resolveCodexCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? = resolve(env[ENV_CODEX_CLI], CODEX_BIN, pathDirs)

    fun resolveAgyCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? = resolve(env[ENV_AGY_CLI], AGY_BIN, pathDirs)

    /** 探测目录：`$PATH` 全量 + 公开约定目录（去重，保序 = 优先级）。 */
    fun pathDirsOf(env: Map<String, String>): List<String> {
        val dirs = mutableListOf<String>()
        env["PATH"]?.split(':')?.filter { it.isNotBlank() }?.let { dirs.addAll(it) }
        env["HOME"]?.takeIf { it.isNotBlank() }?.let { dirs.add(it + "/.local/bin") }
        dirs.add("/usr/local/bin")
        dirs.add("/opt/homebrew/bin")
        return dirs.distinct()
    }

    /**
     * 拉子进程时该给它的 `PATH`。
     *
     * 为什么不能直接继承宿主进程的 `PATH`：macOS 上从 Finder/Dock（LaunchServices）启动的
     * app 只拿到最小 `PATH`（`/usr/bin:/bin:/usr/sbin:/sbin`）。而 CLI 经常是
     * `#!/usr/bin/env node` 的脚本（codex 就是 `@openai/codex/bin/codex.js`），
     * 于是**发现得到二进制、却拉不起来**：子进程以 127 退出，`env: node: No such file
     * or directory`。实测就是这样：同一个 codex，从终端起宿主能跑，从 GUI 起宿主直接
     * `cli exit=127`。
     *
     * 所以补三类目录：宿主现有 `PATH` + 发现器已用的公开约定目录 + **Node 版本管理器的
     * bin**（nvm/volta/bun 与 homebrew 的 node 公式）。只留真实存在的目录，避免给子进程
     * 塞一串死路径。
     */
    fun childPath(env: Map<String, String> = System.getenv()): String {
        val dirs = mutableListOf<String>()
        dirs.addAll(pathDirsOf(env))
        val home = env["HOME"].orEmpty()
        if (home.isNotBlank()) {
            java.io.File(home, ".nvm/versions/node").listFiles()
                ?.sortedByDescending { it.name }
                ?.forEach { dirs.add(java.io.File(it, "bin").absolutePath) }
            dirs.add(java.io.File(home, ".volta/bin").absolutePath)
            dirs.add(java.io.File(home, ".bun/bin").absolutePath)
        }
        dirs.add("/opt/homebrew/opt/node/bin")
        dirs.add("/usr/local/opt/node/bin")
        return dirs.filter { it.isNotBlank() && File(it).isDirectory }.distinct().joinToString(":")
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
