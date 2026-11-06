// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File

/**
 * dsh（DeepSeek Harness CLI）的凭证来源。
 *
 * ## 为什么不能像 codex 那样靠 HOME 继承
 *
 * headless profile 的 LLM 路由（`deepseek-official`）只认**环境变量** `DEEPSEEK_API_KEY`：
 * 实测，即使 `HOME` 指向带 `~/.dsh/.credentials.yaml`
 * 的真实家目录，没有这只 env 也直接 `MISSING_CREDENTIAL`（rc=1，一行 patch 都不产出）；
 * 设上 env 就 200。错误原文自己也说了后一条路：**"or export DEEPSEEK_API_KEY"**。
 *
 * 而沙箱会把 `HOME` 换成隔离目录 ⇒ `~/.dsh` 不可见 ⇒ 必须由宿主把 key 递进子进程 env。
 *
 * ## 值从哪来
 *
 * 宿主 env 里通常没有这只 key；它躺在 `~/.dsh/.credentials.yaml` 的 refs 里
 * （web Models 页写的就是这份）。这里解析它、**只放进子进程环境**：
 * 不写日志、不进台账、不落卡 —— 与 [CliProviderEnv] 同一条纪律。
 */
object DshCredentials {

    const val ENV_KEY = "DEEPSEEK_API_KEY"
    private const val CREDENTIALS_FILE = ".dsh/.credentials.yaml"

    /**
     * 返回该递给子进程的 key；null = 两处都拿不到（调用方如实让 CLI 自己报缺凭证，
     * 不编一个空串把错误变成「401 迷惑行为」）。
     *
     * 优先级：显式 env > 凭证文件。文件解析失败（无文件 / 无该条目）⇒ null。
     */
    fun resolve(env: Map<String, String> = System.getenv(), home: String? = null): String? {
        env[ENV_KEY]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val homeDir = home ?: env["HOME"] ?: return null
        if (homeDir.isBlank()) return null
        val file = File(homeDir, CREDENTIALS_FILE)
        if (!file.isFile) return null
        return runCatching {
            // 行级解析就够：refs 是平铺的 `KEY: value`（通常带缩进）。不引 YAML 库 ——
            // 多一个依赖只为读一行，不值；先 trim 再按行首 `KEY:` 锚定，误配率趋零。
            val line = file.readLines().firstOrNull {
                it.trimStart().startsWith(ENV_KEY + ":")
            } ?: return@runCatching null
            line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
