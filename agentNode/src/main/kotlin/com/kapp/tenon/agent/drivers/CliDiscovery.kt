// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

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
    const val ENV_CLAUDE_CLI = "TENON_CLAUDE_CLI"

    /** 显式指定 Codex 可执行文件的 env 名。 */
    const val ENV_CODEX_CLI = "TENON_CODEX_CLI"

    const val CLAUDE_BIN = "claude"
    const val CODEX_BIN = "codex"

    /** Antigravity CLI（形态 A）：Google 登录态继承，无 Key 可配。 */
    const val ENV_AGY_CLI = "TENON_AGY_CLI"
    const val AGY_BIN = "agy"

    /** opencode CLI：配置文件走 `OPENCODE_CONFIG`（见 [OpenCodeConfig]）。 */
    const val ENV_OPENCODE_CLI = "TENON_OPENCODE_CLI"
    const val OPENCODE_BIN = "opencode"

    /** DeepSeek Harness CLI：key 走 `~/.dsh/.credentials.yaml`（见 [DshCredentials]）。 */
    const val ENV_DSH_CLI = "TENON_DSH_CLI"
    const val DSH_BIN = "dsh"

    /** CodeBuddy CLI：独立安装的 `codebuddy`，也随 WorkBuddy.app 内置。 */
    const val ENV_CODEBUDDY_CLI = "TENON_CODEBUDDY_CLI"
    const val CODEBUDDY_BIN = "codebuddy"
    const val CODEBUDDY_APP_BIN_RELATIVE = "WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin/codebuddy"

    /** ZCode CLI：PATH 上的 `zcode`，或桌面应用包里的 [ZCODE_CJS_RELATIVE]。 */
    const val ENV_ZCODE_CLI = "TENON_ZCODE_CLI"
    const val ZCODE_BIN = "zcode"
    const val ZCODE_CJS_RELATIVE = "ZCode.app/Contents/Resources/glm/zcode.cjs"

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

    /**
     * opencode 的安装位置不在 `$PATH` 的正路上：官方安装脚本落在 `$HOME/.opencode/bin`。
     *
     * 实测（v2.2.5，本机）：`~/.opencode/bin/opencode` 存在、`$PATH` 里没有它 ——
     * 若只按 [pathDirsOf] 找，驱动会「装是装了，注册不上」，与提示行说的
     * 「install it」自相矛盾。所以这里把那条约定目录补进探测序列（仍是公开约定，
     * 不写死任何个人绝对路径）。
     */
    fun resolveOpencodeCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? {
        val dirs = mutableListOf<String>()
        env["HOME"]?.takeIf { it.isNotBlank() }?.let { dirs.add(it + "/.opencode/bin") }
        dirs.addAll(pathDirs)
        return resolve(env[ENV_OPENCODE_CLI], OPENCODE_BIN, dirs)
    }

    /** dsh 与 opencode 同款：官方安装落在 `$HOME/.opencode` 之外的自有目录，但 bin 在 PATH。 */
    fun resolveDshCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? = resolve(env[ENV_DSH_CLI], DSH_BIN, pathDirs)

    /**
     * CodeBuddy 的桌面宿主自带一份 CLI，通常**不在 PATH 上**（从 Dock/Finder 启动的宿主
     * 只拿到最小 PATH）。只按 PATH + 约定 bin 目录找，会出现「应用装了、驱动没注册」——
     * 提示行还让用户去装一个本机已经有的东西。所以补应用包这条公开安装约定，顺序仍是
     * 显式 env → PATH → 应用包，与 ZCode 同一纪律。
     */
    fun resolveCodebuddyCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? {
        resolve(env[ENV_CODEBUDDY_CLI], CODEBUDDY_BIN, pathDirs)?.let { return it }
        val candidates = listOfNotNull(
            env["HOME"]?.takeIf { it.isNotBlank() }?.let { "$it/Applications/$CODEBUDDY_APP_BIN_RELATIVE" },
            "/Applications/$CODEBUDDY_APP_BIN_RELATIVE",
        )
        return candidates.firstNotNullOfOrNull { path ->
            File(path).takeIf { it.isFile && it.canExecute() }?.absolutePath
        }
    }

    /**
     * ZCode 的官方运行时落在桌面应用包里（`zcode.cjs`），PATH 上常常没有 `zcode` 命令。
     * 这里按「显式 env → PATH → 应用包约定目录」的顺序找，找不到就 null 并让装配方提示。
     * 显式 env 指向 `.cjs` 也认（它本来就不是可执行文件，按文件路径判存在即可）。
     */
    fun resolveZcodeCli(
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? {
        val explicit = env[ENV_ZCODE_CLI]?.trim().takeUnless { it.isNullOrEmpty() }
        if (explicit != null) return File(explicit).takeIf { it.isFile }?.absolutePath
        resolve(null, ZCODE_BIN, pathDirs)?.let { return it }
        val candidates = listOfNotNull(
            env["HOME"]?.takeIf { it.isNotBlank() }?.let { "$it/Applications/$ZCODE_CJS_RELATIVE" },
            "/Applications/$ZCODE_CJS_RELATIVE",
        )
        return candidates.firstNotNullOfOrNull { path -> File(path).takeIf { it.isFile }?.absolutePath }
    }

    /**
     * 启动形态：`.cjs` / `.js` 运行时不能自己执行，要交给 `node`；
     * 普通 bin 原样返回。调用方把前缀插在参数最前面（prompt 之前）。
     */
    fun launchOf(cliPath: String): Pair<String, List<String>> = when {
        cliPath.endsWith(".cjs", ignoreCase = true) || cliPath.endsWith(".js", ignoreCase = true) ->
            "node" to listOf(cliPath)
        else -> cliPath to emptyList()
    }

    /**
     * 直接 `node zcode.cjs` 时，CLI 不再从宿主应用进程继承内嵌的 provider 配置路径，
     * 必须由环境显式给出两只文件路径，否则它在第一次提请求前就以非零码退出
     * （帮助/info 命令反而能跑，所以「--help 通过」证明不了可派单）。
     *
     * 内嵌配置位于应用包 Resources 下的公开约定目录，按入口 cjs 的位置反推；
     * 个人配置位于登录态目录。显式 env 由装配处的白名单先取，这里只补缺省值。
     */
    fun zcodeBundledProviderConfigOf(cliPath: String): String? {
        if (!cliPath.endsWith(".cjs", ignoreCase = true)) return null
        // absoluteFile（不 canonicalize）：macOS 的临时目录 canonical 后是 /private/var/...，
        // 与装配期传进来的路径写法不同会让「同一份配置」被当成两份。
        val entry = File(cliPath).absoluteFile
        val resources = entry.parentFile?.parentFile ?: return null
        val candidate = File(resources, "config/provider/zcode-builtin.json")
        return candidate.takeIf { it.isFile }?.absolutePath
    }

    fun zcodePersonalProviderConfigOf(env: Map<String, String>): String? {
        val home = env["HOME"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = File(home, ".zcode/v2/provider_config.json")
        return candidate.takeIf { it.isFile }?.absolutePath
    }

    /**
     * zcode 应用 `setting.json` 路径（登录态判据用：里面记着选过哪个模型）。
     *
     * 与个人 provider 配置同一存储目录约定（`~/.zcode/v2`），保持单一来源；
     * `ZCODE_STORAGE_DIR` 是 CLI 自己的覆盖位，显式给出时优先。只读判定用，
     * 不要求文件必须存在——不存在由探针判「没选过」。
     */
    fun zcodeSettingPathOf(env: Map<String, String>): String {
        env["ZCODE_STORAGE_DIR"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return File(it, "setting.json").absolutePath
        }
        val home = env["HOME"]?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        return File(home, ".zcode/v2/setting.json").absolutePath
    }

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
