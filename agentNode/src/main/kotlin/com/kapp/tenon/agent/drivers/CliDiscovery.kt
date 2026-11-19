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

    /**
     * 发现参数的**唯一真源**在 `agent-contract/driver-specs.json`（每家的 discovery 声明，
     * 经生成物投影，`:driverSpecDrift` 判漂移）。下面这些常量保留原标识符供全仓引用，
     * 值改从投影派生——加一家驱动只改 JSON，不再有一份散落的 env 名/bin 名清单。
     *
     * 搜索机制（显式 env → PATH → 应用包 → 额外目录，可执行文件判定）仍在本对象代码里。
     */
    private fun specOf(key: String): CliDriverSpecs.DiscoverySpec =
        CliDriverSpecs.discoveryOf(DriverKeys.cliDriverId(key))
            ?: error("driver-specs.json 缺 $key 的 discovery 声明")

    /** 显式指定 Claude Code 可执行文件的 env 名。 */
    val ENV_CLAUDE_CLI get() = specOf("claude").envVar
    val ENV_CODEX_CLI get() = specOf("codex").envVar

    val CLAUDE_BIN get() = specOf("claude").bin
    val CODEX_BIN get() = specOf("codex").bin

    /** Antigravity CLI（形态 A）：Google 登录态继承，无 Key 可配。 */
    val ENV_AGY_CLI get() = specOf("agy").envVar
    val AGY_BIN get() = specOf("agy").bin

    /** opencode CLI：配置文件走 `OPENCODE_CONFIG`（见 [OpenCodeConfig]）。 */
    val ENV_OPENCODE_CLI get() = specOf("opencode").envVar
    val OPENCODE_BIN get() = specOf("opencode").bin

    /** DeepSeek Harness CLI：key 走 `~/.dsh/.credentials.yaml`（见 [DshCredentials]）。 */
    val ENV_DSH_CLI get() = specOf("dsh").envVar
    val DSH_BIN get() = specOf("dsh").bin

    /** CodeBuddy CLI：独立安装的 `codebuddy`，也随 WorkBuddy.app 内置。 */
    val ENV_CODEBUDDY_CLI get() = specOf("codebuddy").envVar
    val CODEBUDDY_BIN get() = specOf("codebuddy").bin

    /**
     * 应用包内相对路径，**仅供展示/测试拼路径**；生产候选构造直接读 nullable 值，
     * 因为 `orEmpty()` 会把「没声明」变成空串、拼出一个 `/Applications/` 目录候选。
     */
    val CODEBUDDY_APP_BIN_RELATIVE get() = specOf("codebuddy").extraBinRelative.orEmpty()

    /** ZCode CLI：PATH 上的 `zcode`，或桌面应用包里的 [ZCODE_CJS_RELATIVE]。 */
    val ENV_ZCODE_CLI get() = specOf("zcode").envVar
    val ZCODE_BIN get() = specOf("zcode").bin

    /** 同 [CODEBUDDY_APP_BIN_RELATIVE]：对外是展示值，生产内部走 nullable。 */
    val ZCODE_CJS_RELATIVE get() = specOf("zcode").extraBinRelative.orEmpty()

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
        val home = env["HOME"]?.takeIf { it.isNotBlank() }
        for (rel in specOf("opencode").extraPathDirs) {
            if (home != null) dirs.add(home + "/" + rel)
        }
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
        // 没声明应用包副本就不该造候选：`orEmpty()` 会拼出 `/Applications/`（目录），
        // 虽然被下面的 `isFile` 挡住，但那属于「拿空串冒充路径」，不做。
        val rel = specOf("codebuddy").extraBinRelative ?: return null
        val candidates = listOfNotNull(
            env["HOME"]?.takeIf { it.isNotBlank() }?.let { "$it/Applications/$rel" },
            "/Applications/$rel",
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
        // 同 codebuddy：没声明就不造 `/Applications/` 这种空串候选。
        val rel = specOf("zcode").extraBinRelative ?: return null
        val candidates = listOfNotNull(
            env["HOME"]?.takeIf { it.isNotBlank() }?.let { "$it/Applications/$rel" },
            "/Applications/$rel",
        )
        return candidates.firstNotNullOfOrNull { path -> File(path).takeIf { it.isFile }?.absolutePath }
    }

    /**
     * 档案键 → 该家 CLI 的路径，**这一张表就是「键 → 发现器」的唯一映射**。
     *
     * 存在的理由：装配侧与诊断侧都要按同一套键去取路径。以前各自写一个 `when (key)`，
     * 加一家忘了补其中一处，症状是「装是装了，诊断面说找不到」——两边都能编译。
     * 认不出的键返回 null（不猜、不回落到别人的二进制）。
     */
    fun resolveFor(
        key: String,
        env: Map<String, String> = System.getenv(),
        pathDirs: List<String> = pathDirsOf(env),
    ): String? = when (key) {
        // 分支条件必须是 **driver key 字面量**，不能写 `CLAUDE_BIN` 这类投影属性：
        // 那个值来自 JSON 的 `discovery.bin`，一旦某家把 bin 改名（`agy` → `antigravity`），
        // 这里会静默匹配不到 ⇒ 诊断面说「没装」、装配面（直接调 resolveXxxCli）却正常，
        // 两边结论相反且都不报错。bin 名回答「找什么文件」，key 回答「这是哪家」，别混用。
        "claude" -> resolveClaudeCli(env, pathDirs)
        "codex" -> resolveCodexCli(env, pathDirs)
        "agy" -> resolveAgyCli(env, pathDirs)
        "opencode" -> resolveOpencodeCli(env, pathDirs)
        "dsh" -> resolveDshCli(env, pathDirs)
        "codebuddy" -> resolveCodebuddyCli(env, pathDirs)
        "zcode" -> resolveZcodeCli(env, pathDirs)
        else -> null
    }

    /**
     * 启动形态：`.cjs` / `.js` 运行时不能自己执行，要交给 `node`；
     * 普通 bin 原样返回。调用方把前缀插在参数最前面（prompt 之前）。
     */
    fun launchOf(cliPath: String): Pair<String, List<String>> {
        // node 后缀判定来自 JSON 真源的 launch 声明（加一种脚本形态只改 JSON）。
        val isNodeScript = CliDriverSpecs.launch.nodeExtensions.any {
            cliPath.endsWith(it, ignoreCase = true)
        }
        return if (isNodeScript) "node" to listOf(cliPath) else cliPath to emptyList()
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
