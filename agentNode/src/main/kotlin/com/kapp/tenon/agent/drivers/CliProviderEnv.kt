// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 子进程的**上游凭证**注入（白名单）：把 CLI 指向自定义 base URL / 自定义模型。
 *
 * ## 为什么需要它
 *
 * 沙箱把 `HOME` 换成了隔离目录（[WorktreeSandboxExecutor.prepareSandboxHome]），
 * 于是 CLI 在 `~/.claude`、Keychain 里的登录态**看不见了** —— 隔离是对的，但
 * 隔离之后 CLI 拿什么认证？答案只能是显式注入，而不是把真 `HOME` 放回去
 * （那会把 `.ssh`、`.workbuddy` 一起递给子进程）。
 *
 * 同一份机制也让「统一走网关」成为可能：把 `ANTHROPIC_BASE_URL` /
 * `ANTHROPIC_MODEL` 指向 opencode-go，三家 CLI 都能用同一个 key 跑（见证据
 * §13.14）。没有它，真 CLI 在沙箱里永远是「装是装了，但一跑就没凭证」。
 *
 * ## 三条纪律
 *
 * 1. **白名单，不是全量环境**。只搬下面列出的变量；`AWS_SECRET_*`、`PATH`、
 *    `HOME` 之类一概不带。给子进程的东西越少，泄漏面越小。
 * 2. **值只进子进程环境**。不写日志、不进台账、不进卡片、不落盘 —— 与 T-3a
 *    `envOverrides` 同一条纪律。要说明「注入了什么」时只报**变量名**
 *    （见 [describe]）。
 * 3. **空集是合法值**。宿主没配自定义上游时返回空 map，行为与注入前逐字一致
 *    （凭证仍走 `HOME`/`XDG_*` 继承）。
 */
object CliProviderEnv {

    /**
     * Claude Code：Anthropic 兼容面。
     *
     * `ANTHROPIC_BASE_URL` **不带 `/v1`** —— SDK 自己拼 `/v1/messages`，多写一段
     * 会变成 `/v1/v1/messages`（实测 404）。`ANTHROPIC_CUSTOM_HEADERS` 用来带
     * 网关要求的 `x-opencode-session`（缺了直接 400）。
     */
    private val CLAUDE = listOf(
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_MODEL",
        "ANTHROPIC_SMALL_FAST_MODEL",
        "ANTHROPIC_CUSTOM_HEADERS",
    )

    /**
     * Codex：上游写在 `$CODEX_HOME/config.toml` 的 `model_providers` 里，所以这里
     * 要的是**配置目录**（不是 base URL），加上 `env_key` 指向的那只 key。
     *
     * 注意 `CODEX_HOME` 指到真目录 = 把 codex 自己的 config/auth 递给 codex 自己，
     * 这是它拿到凭证的正路；`.ssh` 之类不在其中（沙箱 `HOME` 仍是隔离的）。
     */
    private val CODEX = listOf(
        "CODEX_HOME",
        "OPENCODE_API_KEY",
    )

    /**
     * opencode：自定义 provider 走 `OPENCODE_CONFIG` 指向的 json，key 从环境取。
     *
     * 另外要**出网路径**（`*_PROXY`）：这不是凭证，但不给它就连不上模型。
     * 实测：沙箱只递白名单 env ⇒ opencode 直连 zen 网关，
     * 网关回 `Model is unavailable`（同一份配置、同一个 key，走宿主代理时是 200）。
     * 也就是说它自己**不读系统代理设置**：少这两只变量，看起来像「模型不可用」，
     * 其实是「根本没走到那条线路」。代理变量不含密文，递进去不扩大泄漏面。
     *
     * **刻意不递 `OPENCODE_BASE_URL`**（实测，同一分钟两次对照）：它在子进程里会让
     * opencode 自己按这只变量重建 provider，把配置里的 `headers` 丢掉 —— 网关随即回
     * `Model is unavailable`（同一份配置，去掉这只变量就 200）。上游地址由宿主读它、
     * 写进 [OpenCodeConfig] 生成的那份 json，子进程只需要路径。
     */
    private val OPENCODE = listOf(
        "OPENCODE_API_KEY",
        "OPENCODE_CONFIG",
        "HTTPS_PROXY",
        "HTTP_PROXY",
        "ALL_PROXY",
        "NO_PROXY",
        "https_proxy",
        "http_proxy",
        "all_proxy",
        "no_proxy",
    )

    /**
     * agy（Antigravity）：Google 登录态继承，**没有**可配的自定义上游。
     * 显式留空而不是「顺手把整份 env 塞进去」—— 空集才说明这里确实不需要。
     */
    private val AGY = emptyList<String>()

    /**
     * dsh（DeepSeek Harness CLI）：出厂路由认 env 的 `DEEPSEEK_API_KEY`；自配路由
     * （如 dsh-opencode-go）认 `OPENCODE_API_KEY`；出网同 opencode：补代理变量。
     */
    private val DSH = listOf(
        "DEEPSEEK_API_KEY",
        "OPENCODE_API_KEY",
        "DSH_PERMISSION_MODE",
        "HTTPS_PROXY",
        "HTTP_PROXY",
        "ALL_PROXY",
        "NO_PROXY",
        "https_proxy",
        "http_proxy",
        "all_proxy",
        "no_proxy",
    )

    fun forClaude(env: Map<String, String> = System.getenv()): Map<String, String> = pick(CLAUDE, env)

    fun forCodex(env: Map<String, String> = System.getenv()): Map<String, String> = pick(CODEX, env)

    fun forOpencode(env: Map<String, String> = System.getenv()): Map<String, String> = pick(OPENCODE, env)

    fun forAgy(env: Map<String, String> = System.getenv()): Map<String, String> = pick(AGY, env)

    fun forDsh(env: Map<String, String> = System.getenv()): Map<String, String> = pick(DSH, env)

    /** 按 driverId 取该 CLI 的凭证；认不出的 driverId = 空集（不猜、不乱给）。 */
    fun forDriver(driverId: String, env: Map<String, String> = System.getenv()): Map<String, String> =
        when (driverId) {
            DriverKeys.cliDriverId("claude") -> forClaude(env)
            DriverKeys.cliDriverId("codex") -> forCodex(env)
            DriverKeys.cliDriverId("opencode") -> forOpencode(env)
            DriverKeys.cliDriverId("agy") -> forAgy(env)
            DriverKeys.cliDriverId("dsh") -> forDsh(env)
            else -> emptyMap()
        }

    /**
     * 供提示行使用的**变量名**摘要（绝不含值）。
     * 空集时返回 null —— 调用方据此决定「不必打印这一行」。
     */
    fun describe(injected: Map<String, String>): String? =
        injected.keys.sorted().takeIf { it.isNotEmpty() }?.joinToString(", ")

    /** 白名单过滤：只留存在的键，空白值当没有（空字符串送进去等于把 CLI 指向空上游）。 */
    private fun pick(allowlist: List<String>, env: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in allowlist) {
            val value = env[key]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            out[key] = value
        }
        return out
    }
}
