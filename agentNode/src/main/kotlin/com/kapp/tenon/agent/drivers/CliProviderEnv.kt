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
 * 1. **这里是「主动注入集」，不是「子进程能看到的全部」**。下面每张表列的是本驱动需要宿主
 *    **显式给**的键（凭证 / 自定义上游 / 代理 / 配置目录）。除这些之外，子进程还继承宿主
 *    全量环境 —— 那是刻意的：清空继承会把 `git`/`ssh` 通道（`SSH_AUTH_SOCK`、`GIT_*`）、
 *    编码（`LANG`/`LC_ALL`）、临时目录（`TMPDIR`）一起掐掉，症状是「命令跑通了、推不上去」，
 *    而 CLI 只报它自己的话，没有一处会说是环境缺了东西。顺序与代价见 [CliChildEnv]。
 *    所以「注入面」与「隔离面」是两件事：隔离靠沙箱工作目录、沙箱 HOME 交换
 *    （只链凭证目录）与 `TENON_OS_SANDBOX=strict`，不靠这张表。
 * 2. **值只进子进程环境**。不写日志、不进台账、不进卡片、不落盘 —— 与 T-3a
 *    `envOverrides` 同一条纪律。要说明「注入了什么」时只报**变量名**
 *    （见 [describe]）。
 * 3. **空集是合法值**。宿主没配自定义上游时返回空 map，行为与注入前逐字一致
 *    （凭证仍走 `HOME`/`XDG_*` 继承）。
 */
object CliProviderEnv {

    /** 直接以 node 启动 zcode.cjs 时必须显式给出的两只 provider 配置路径。 */
    const val ENV_ZCODE_BUILTIN_CONFIG = "ZCODE_BUILTIN_PROVIDER_CONFIG_FILE"
    const val ENV_ZCODE_PERSONAL_CONFIG = "ZCODE_PERSONAL_PROVIDER_CONFIG_FILE"

    /**
     * 凭证白名单的**唯一真源**在 `agent-contract/driver-specs.json`（经生成物投影，
     * `:driverSpecDrift` 判漂移）。这里只留「为什么会是这张清单」的理由，值由投影提供。
     *
     * 改 JSON 前先读这些理由：
     * - Claude：`ANTHROPIC_BASE_URL` 不带 `/v1`（SDK 自己拼，多写变 `/v1/v1/messages` 报 404）；
     *   `ANTHROPIC_CUSTOM_HEADERS` 带网关要的 `x-opencode-session`。
     * - Codex：要配置目录 `CODEX_HOME`（上游写在 config.toml），加 `OPENCODE_API_KEY`。
     * - opencode：走 `OPENCODE_CONFIG` 指向的 json；必须带 `*_PROXY`（它不读系统代理，
     *   缺了直连网关报「模型不可用」）；刻意不递 `OPENCODE_BASE_URL`（会重建 provider 丢 headers）。
     * - agy：Google 登录态继承，无可配上游 ⇒ 空集是刻意的。
     * - dsh：出厂认 `DEEPSEEK_API_KEY`，自配路由认 `OPENCODE_API_KEY`，补代理。
     * - codebuddy：`CODEBUDDY_INTERNET_ENVIRONMENT` 决定端点（漏了中国版报认证失败）；
     *   `CODEBUDDY_IS_SANDBOX` 只透传不主动设值（避免静默放宽安全面）。
     * - zcode：`ZCODE_API_KEY`/`ZHIPU_API_KEY` + 两只 provider 配置路径 + `ZCODE_` 代理前缀。
     */
    private val CLAUDE get() = CliDriverSpecs.envKeysOf(DriverKeys.cliDriverId("claude"))
    private val CODEX get() = CliDriverSpecs.envKeysOf(DriverKeys.cliDriverId("codex"))
    private val OPENCODE get() = CliDriverSpecs.envKeysOf(DriverKeys.cliDriverId("opencode"))
    private val AGY get() = CliDriverSpecs.envKeysOf(DriverKeys.cliDriverId("agy"))
    private val DSH get() = CliDriverSpecs.envKeysOf(DriverKeys.cliDriverId("dsh"))
    private val CODEBUDDY get() = CliDriverSpecs.envKeysOf(DriverKeys.cliDriverId("codebuddy"))
    private val ZCODE get() = CliDriverSpecs.envKeysOf(DriverKeys.cliDriverId("zcode"))

    fun forClaude(env: Map<String, String> = System.getenv()): Map<String, String> = pick(CLAUDE, env)

    fun forCodex(env: Map<String, String> = System.getenv()): Map<String, String> = pick(CODEX, env)

    fun forOpencode(env: Map<String, String> = System.getenv()): Map<String, String> = pick(OPENCODE, env)

    fun forAgy(env: Map<String, String> = System.getenv()): Map<String, String> = pick(AGY, env)

    fun forDsh(env: Map<String, String> = System.getenv()): Map<String, String> = pick(DSH, env)

    fun forCodebuddy(env: Map<String, String> = System.getenv()): Map<String, String> = pick(CODEBUDDY, env)

    fun forZcode(env: Map<String, String> = System.getenv()): Map<String, String> = pick(ZCODE, env)

    /**
     * driverId → 该家的凭证白名单，从身份卡键集派生（真源在 JSON）。
     * [forDriver] 与 [declaredKeys] 都读它。加一家驱动只要 JSON + 投影，这里自动跟上，
     * 不再有「值取到了、诊断面却显示没声明」的双份映射漂移。
     */
    private val allowlistsByDriverId: Map<String, List<String>> =
        CliDriverSpecs.builtins.associate { spec ->
            val driverId = DriverKeys.cliDriverId(spec.key)
            driverId to CliDriverSpecs.envKeysOf(driverId)
        }

    /** 按 driverId 取该 CLI 的凭证；认不出的 driverId = 空集（不猜、不乱给）。 */
    fun forDriver(driverId: String, env: Map<String, String> = System.getenv()): Map<String, String> =
        pick(allowlistsByDriverId[driverId].orEmpty(), env)

    /**
     * 该驱动**声明**的白名单键名（只有名字，永远不含值）。
     *
     * 诊断面要回答的是「宿主给了几只 / 声明了几只」——声明 10 只、宿主只给了 1 只，
     * 与「这家本来就不需要凭证」，运维动作完全不同。认不出的 driverId = 空集。
     */
    fun declaredKeys(driverId: String): List<String> = allowlistsByDriverId[driverId].orEmpty()

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
