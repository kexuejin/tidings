// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 驱动自检（doctor）：**一次跑完全部探测，把「这家驱动到底会怎么被拉起」摊开**。
 *
 * ## 为什么需要它
 *
 * 驱动层的缺陷几乎都是静默的：`--model` 被追加到 prompt 之后、沙箱分支漏拼模型、
 * 发现得到却没装配、HOME 白名单缺一项导致沙箱里必死认证 —— 这些的共同形态是
 * 「退出码 0 / 事件流正常 / 全链路无一处报错」，只有真机派单才暴露。于是改一行
 * `argSpec` 就要走一遍装信任、配对、真派单的流程，验证成本远高于改动成本。
 *
 * 本对象把三件事放在同一张纸上：
 *
 * 1. **发现**：路径、启动形态（直接可执行 / 经 node 跑 `.cjs`）、版本；
 * 2. **装配**：这条路径有没有真的变成一个驱动实例（发现了没装配 = 静默不可用）；
 * 3. **argv**：**生产装配出来的那个实例**在 `withModel(...)` 之后要执行的命令行。
 *
 * ## 一条硬纪律：argv 只能取自已装配的实例
 *
 * 不许直接调 `codexArgs()` 这类助手函数——那正是假绿的来源：助手函数一直是对的，
 * 错的是装配处没把模型传给它。所以 [Row.argv] 全部来自 `attemptArgv`，
 * 也就是 `executeTask` 喂给 `ProcessBuilder` 的那个列表本身。
 *
 * ## 判定由代码做，不由人看
 *
 * [Row.findings] 是**自动判红**：模型声明支持却没进 argv、prompt 出现两次、
 * 裸路径带提权旗标……都写成机器可读的结论。人只需要读 findings，不用逐行比对 argv。
 *
 * ## 输出语言
 *
 * 渲染结果是**英文**：这是开发者终端输出，与驱动层现有的装配提示行同一口径，
 * 不是产品文案（产品文案走 `Res.string.*` 与设计门禁）。
 */
object DriverDoctor {

    /** `--json` 的契约版本。字段只增不改；重命名等同破坏（`:cli doctor --json` 会依赖它）。 */
    const val SCHEMA_VERSION: Int = 1

    /** argv 预览里的占位 prompt（真 prompt 有换行/引号，印出来反而看不清结构）。 */
    const val PROBE_PROMPT: String = "doctor-probe-prompt"

    /** argv 预览里的占位模型。故意取一个不可能撞上真模型的名字。 */
    const val PROBE_MODEL: String = "doctor-probe-model"

    /** 续跑预览里的占位会话 id。 */
    private const val PROBE_SESSION: String = "doctor-probe-session"

    /** 登录态四态里的第四态：**没接探针**。与 `UNKNOWN`（接了、但判不出）严格分开。 */
    const val AUTH_NOT_PROBED: String = "NOT_PROBED"

    /**
     * 子进程环境的继承模式 —— 唯一来源是 [CliChildEnv.MODE_INHERIT]。
     *
     * 报告里带上它是因为这是**安全语义**：一旦有人把继承改成 `clear()`，那一列必须同时改，
     * doctor 的输出会立刻把改动摆出来，而不是等真机派单失败。
     */
    val ENV_MODE_INHERIT: String get() = CliChildEnv.MODE_INHERIT

    private const val LAUNCH_DIRECT: String = "direct"
    private const val LAUNCH_NODE: String = "node"

    /** 版本探测上限：只读的 `--version`，慢机器冷启动也够。 */
    const val VERSION_TIMEOUT_MS: Long = 5_000L

    enum class Severity { ERROR, WARN, INFO }

    /** 一条自动结论。[driverKey] 为 null = 全局（不归某一家）。 */
    @Serializable
    data class Finding(
        val severity: Severity,
        val driverKey: String?,
        val message: String,
    )

    /**
     * 登录态的**原始**两维（三态 + 证据强度）。
     *
     * 这里刻意不用 UI 的那套枚举：doctor 报告的是「探针观察到了什么」，
     * 而「界面该画什么颜色」是另一层的事（映射住 `:orchestration`）。
     */
    @Serializable
    data class AuthView(
        val state: DriverAuthProbes.State,
        val evidence: DriverAuthProbes.Evidence,
    )

    /**
     * 探测面。全部可注入 ⇒ 测试给合成输入即可，不依赖本机装没装任何 CLI
     * （`envDependentTestGate` 的口径）。
     *
     * 缺省实现故意**什么都不探**：`authOf` 返回 null 表示「没接探针」而不是「未判定」。
     * 把「没接」画成「未判定」正是本仓最贵的静默失效，所以这两态在本报告里分开。
     */
    interface Probe {
        /** 该家 CLI 的可执行路径；null = 本机没有。 */
        fun resolveCli(key: String): String?

        fun env(): Map<String, String> = System.getenv()

        fun home(): File = File(System.getProperty("user.home"))

        /** 登录态；null = 未接探针（报告里显示 `-`，不是 UNKNOWN）。 */
        suspend fun authOf(key: String, cliPath: String?): AuthView? = null

        /** 模型目录；null = 没探（报告里显示 `-`）。 */
        suspend fun modelEntryOf(key: String, cliPath: String?): ModelCatalogEntry? = null

        /** 版本串；null = 没探到（不编一个 "unknown" 冒充结论）。 */
        suspend fun versionOf(key: String, cliPath: String): String? = null
    }

    /** 一家驱动的一行结论。 */
    @Serializable
    data class Row(
        val key: String,
        val displayName: String,
        /** 装配里真的有这个实例。false + [cliPath] 非空 = 发现了却没装配。 */
        val assembled: Boolean,
        val cliPath: String? = null,
        /** `direct` = 直接执行；`node` = 经 node 跑 `.cjs`。null = 没找到可执行文件。 */
        val launch: String? = null,
        val version: String? = null,
        /** AUTHENTICATED / NOT_AUTHENTICATED / UNKNOWN / NOT_PROBED。 */
        val authState: String = AUTH_NOT_PROBED,
        val authEvidence: String? = null,
        /** FLAG / CONFIG_PATCH / NONE。 */
        val modelMechanism: String,
        val modelFlag: String? = null,
        /** 读到的可选模型数；null = 目录未判定（读不到 ≠ 没有）。 */
        val modelCount: Int? = null,
        val modelNote: String? = null,
        /** 该驱动声明要宿主给的键数（[CliProviderEnv.declaredKeys] 的条数）。 */
        val envDeclared: Int = 0,
        /** 声明过、宿主真给了的键名（**只有名字**，值绝不进报告）。 */
        val envPresent: List<String> = emptyList(),
        /** 首跳 argv（取自生产装配实例）。空 = 没装配出实例。 */
        val argv: List<String> = emptyList(),
        /** 续跑 argv；null = 该驱动无续跑能力（不是「没探」）。 */
        val argvResume: List<String>? = null,
        val findings: List<Finding> = emptyList(),
    )

    /** 沙箱 HOME 白名单里的一项：名字 + 本机真 HOME 里在不在。 */
    @Serializable
    data class HomeLink(val name: String, val present: Boolean)

    @Serializable
    data class Report(
        val schemaVersion: Int,
        val generatedAt: Long,
        /** argv 取的是沙箱分支还是裸路径分支（生产默认沙箱）。 */
        val sandboxed: Boolean,
        /** 子进程环境的继承模式；见 [ENV_MODE_INHERIT]。 */
        val envMode: String = ENV_MODE_INHERIT,
        val rows: List<Row>,
        val homeLinks: List<HomeLink>,
        val findings: List<Finding>,
    ) {
        val errorCount: Int get() = findings.count { it.severity == Severity.ERROR }
        val warnCount: Int get() = findings.count { it.severity == Severity.WARN }
    }

    /**
     * 跑一遍自检。[drivers] 必须是**生产装配**产出的实例（`cliDriverRegistrations` /
     * `DriverRegistry.withCliDrivers`），否则 argv 那一列又变回「测了实现没测装配」。
     */
    suspend fun diagnose(
        drivers: List<AgentDriver>,
        probe: Probe,
        sandboxed: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Report {
        val byKey = drivers.associateBy { DriverKeys.stripCliSuffix(it.driverId) }
        val rows = CliDriverSpecs.builtins.map { spec -> rowOf(spec, byKey[spec.key], probe, sandboxed) }
        val homeLinks = homeLinksOf(probe.home())
        val global = globalFindings(rows, homeLinks, sandboxed)
        return Report(
            schemaVersion = SCHEMA_VERSION,
            generatedAt = now,
            sandboxed = sandboxed,
            rows = rows,
            homeLinks = homeLinks,
            findings = rows.flatMap { it.findings } + global,
        )
    }

    private suspend fun rowOf(
        spec: CliDriverSpecs.Spec,
        driver: AgentDriver?,
        probe: Probe,
        sandboxed: Boolean,
    ): Row {
        val cliPath = probe.resolveCli(spec.key)
        val launch = cliPath?.let { path ->
            if (CliDiscovery.launchOf(path).first == path) LAUNCH_DIRECT else LAUNCH_NODE
        }
        val version = cliPath?.let { probe.versionOf(spec.key, it) }
        val auth = probe.authOf(spec.key, cliPath)
        val entry = probe.modelEntryOf(spec.key, cliPath)
        val driverId = DriverKeys.cliDriverId(spec.key)
        val declared = CliProviderEnv.declaredKeys(driverId)

        val model = if (spec.supportsPerTaskModel) PROBE_MODEL else null
        val argv = driver?.let { argvOf(it, model) }.orEmpty()
        val resume = driver?.let { argvOf(it, model, PROBE_SESSION) }
            ?.takeIf { it != argv }

        val findings = buildList {
            if (driver == null && cliPath != null) {
                add(
                    Finding(
                        Severity.ERROR,
                        spec.key,
                        "found at $cliPath but no driver instance was assembled — it is invisible to dispatch",
                    ),
                )
            }
            if (driver != null && cliPath == null) {
                add(
                    Finding(
                        Severity.WARN,
                        spec.key,
                        "a driver instance exists but this probe cannot resolve the executable — " +
                            "the doctor and the assembly are looking at different resolvers",
                    ),
                )
            }
            if (driver != null) addAll(argvFindings(spec, argv, model))
            if (resume != null) addAll(argvFindings(spec, resume, model, resumed = true))
            if (sandboxed && spec.key == "codebuddy" && !argv.contains("-y")) {
                // 沙箱分支缺 -y 不一定是缺陷（取决于装配意图），但它是「沙箱与裸路径分叉」的信号。
                add(
                    Finding(
                        Severity.INFO,
                        spec.key,
                        "sandboxed argv does not carry -y; verify that is intended for this driver",
                    ),
                )
            }
        }

        return Row(
            key = spec.key,
            displayName = spec.displayName,
            assembled = driver != null,
            cliPath = cliPath,
            launch = launch,
            version = version,
            authState = auth?.state?.name ?: AUTH_NOT_PROBED,
            authEvidence = auth?.evidence?.name,
            modelMechanism = spec.effectiveModelMechanism.name,
            modelFlag = spec.modelFlag,
            modelCount = entry?.takeIf { it.state == ModelCatalogState.KNOWN }?.models?.size,
            modelNote = entry?.note?.takeIf { it.isNotBlank() },
            envDeclared = declared.size,
            envPresent = CliProviderEnv.forDriver(driverId, probe.env()).keys.sorted(),
            argv = argv,
            argvResume = resume,
            findings = findings,
        )
    }

    /**
     * argv 的自动判据。
     *
     * 位置判据不靠「猜哪家的 prompt 是末尾位置参数」，而是从 argv 自己推：
     * **prompt 前一格是不是 `-p`**——是则 prompt 是旗标值，模型排后面也安全；
     * 否则 prompt 是裸位置参数，排在它后面的旗标会被当成 prompt 的一部分吞掉。
     * 这条推导正是 codex / opencode 那两处缺陷的形态。
     */
    private fun argvFindings(
        spec: CliDriverSpecs.Spec,
        argv: List<String>,
        model: String?,
        resumed: Boolean = false,
    ): List<Finding> {
        if (argv.isEmpty()) return emptyList()
        val tag = if (resumed) "resume argv" else "argv"
        val out = mutableListOf<Finding>()

        val promptHits = argv.count { it == PROBE_PROMPT }
        if (promptHits != 1) {
            out += Finding(
                Severity.ERROR,
                spec.key,
                "$tag carries the prompt $promptHits times (must be exactly 1) — argv=$argv",
            )
        }

        if (model == null) {
            if (argv.contains(PROBE_MODEL)) {
                out += Finding(
                    Severity.ERROR,
                    spec.key,
                    "$tag carries a model although this driver has no per-task model channel — argv=$argv",
                )
            }
            return out
        }

        when (spec.effectiveModelMechanism) {
            CliDriverSpecs.ModelMechanism.FLAG -> {
                val flag = spec.modelFlag
                val at = argv.indexOf(model)
                val flagAt = if (at >= 1) argv.getOrNull(at - 1) else null
                when {
                    at < 0 -> out += Finding(
                        Severity.ERROR,
                        spec.key,
                        "$tag has no model although the driver declares support — configured model is silently ignored; argv=$argv",
                    )
                    flagAt != flag -> out += Finding(
                        Severity.ERROR,
                        spec.key,
                        "$tag carries the model but not as $flag's value (preceded by '$flagAt') — argv=$argv",
                    )
                    else -> {
                        val promptAt = argv.indexOf(PROBE_PROMPT)
                        val promptIsFlagValue = promptAt >= 1 && argv[promptAt - 1] == "-p"
                        if (!promptIsFlagValue && promptAt >= 0 && at > promptAt) {
                            out += Finding(
                                Severity.ERROR,
                                spec.key,
                                "$tag puts $flag after the prompt, and the prompt is a trailing positional argument here — " +
                                    "the CLI will swallow the flag as part of the prompt; argv=$argv",
                            )
                        }
                    }
                }
            }
            CliDriverSpecs.ModelMechanism.CONFIG_PATCH -> {
                val patchAt = argv.indexOf("--patch")
                when {
                    patchAt < 0 -> out += Finding(
                        Severity.ERROR,
                        spec.key,
                        "$tag has no --patch although a model was requested — the CLI will silently run its default; argv=$argv",
                    )
                    else -> {
                        val patchPath = argv.getOrNull(patchAt + 1)
                        val text = patchPath?.let { runCatching { File(it).readText() }.getOrNull() }
                        if (text != null && !text.contains(model)) {
                            out += Finding(
                                Severity.ERROR,
                                spec.key,
                                "$tag points --patch at a file that does not mention the model — argv=$argv",
                            )
                        }
                    }
                }
            }
            CliDriverSpecs.ModelMechanism.NONE -> Unit
        }
        return out
    }

    /** 普通路径 / 沙箱路径都要能取到；两种实现各有 `attemptArgv`，这是生产的同一出口。 */
    internal fun argvOf(driver: AgentDriver, model: String?, resumedCliSessionId: String? = null): List<String>? {
        val withModel = driver.withModel(model)
        return when (withModel) {
            is CliSubprocessDriver -> withModel.attemptArgv(PROBE_PROMPT, resumedCliSessionId)
            is SandboxedCliDriver -> withModel.attemptArgv(PROBE_PROMPT, resumedCliSessionId)
            else -> null
        }
    }

    /**
     * 沙箱 HOME 白名单的实况。**只报「在不在」**，不读内容、不列子项 ——
     * 这份清单里全是凭据目录，报告本身不能变成泄露面。
     */
    internal fun homeLinksOf(home: File): List<HomeLink> =
        WorktreeSandboxExecutor.SANDBOX_CREDENTIAL_ENTRIES.map { name ->
            HomeLink(name, File(home, name).exists())
        }

    private fun globalFindings(
        rows: List<Row>,
        homeLinks: List<HomeLink>,
        sandboxed: Boolean,
    ): List<Finding> = buildList {
        if (!sandboxed) return@buildList
        val missing = homeLinks.filterNot { it.present }.map { it.name }
        if (missing.isNotEmpty()) {
            // 只陈述事实 + 条件，**不断言后果**：`agy` 走真实 HOME（useRealHome），
            // 这些条目对它是空操作；真正会死在认证上的是「用沙箱 HOME 又住在这里面」的驱动。
            add(
                Finding(
                    Severity.WARN,
                    null,
                    "sandbox HOME whitelist entries absent on this machine: ${missing.joinToString(", ")} " +
                        "(mounting them is a no-op; only drivers that read credentials from the sandbox HOME are affected)",
                ),
            )
        }
        val notAssembled = rows.filter { !it.assembled }.map { it.key }
        if (notAssembled.isNotEmpty()) {
            add(
                Finding(
                    Severity.INFO,
                    null,
                    "not assembled: ${notAssembled.joinToString(", ")} — " +
                        "install the CLI or point the TENON_*_CLI override at it",
                ),
            )
        }
    }

    // ---------------------------------------------------------------- rendering

    /**
     * `--json` 的编码口径：**null 也写出来**。
     *
     * 契约是给别的产品消费的，字段缺席与字段为 null 在消费侧是两件事：前者读出来是
     * 「这个版本没有这个字段」，后者是「有，但这次是空的」。省掉 null 会让
     * `argvResume == null`（这家没有续跑能力）与「老版本没有这个字段」混成一件事。
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun renderJson(report: Report): String = json.encodeToString(report)

    /**
     * 人读版：面板一行 + 每家一个详情块。
     *
     * 不做宽表：列宽对齐在 CJK 与不同终端字体下必错位，而读的人真正要看的是
     * 「这家会怎么被拉起」——详情块比表格更适合这个形状。
     */
    fun renderText(report: Report): String = buildString {
        appendLine("tenon driver doctor  (branch=" + (if (report.sandboxed) "sandbox" else "plain") + ", env=" + report.envMode + ")")
        appendLine("=".repeat(72))
        for (row in report.rows) {
            appendLine(
                pad(row.key, 11) +
                    pad(if (row.assembled) "ok" else "MISSING", 9) +
                    pad(row.cliPath?.let { row.launch ?: "-" } ?: "-", 7) +
                    pad(row.version ?: "-", 14) +
                    pad(row.authState + (row.authEvidence?.let { "/$it" } ?: ""), 18) +
                    "models=" + (row.modelCount?.toString() ?: "?") +
                    "  env=" + row.envPresent.size + "/" + row.envDeclared +
                    "  issues=" + row.findings.count { it.severity != Severity.INFO },
            )
        }
        appendLine()
        for (row in report.rows) appendLine(detailOf(row))
        appendLine("sandbox HOME whitelist (" + report.homeLinks.count { it.present } + "/" + report.homeLinks.size + " present)")
        appendLine("  " + report.homeLinks.joinToString("  ") { it.name + if (it.present) "" else "*" })
        appendLine()
        if (report.findings.isEmpty()) {
            appendLine("no findings")
        } else {
            appendLine("findings (ERROR " + report.errorCount + ", WARN " + report.warnCount + ")")
            for (f in report.findings.sortedBy { it.severity }) {
                appendLine("  " + pad(f.severity.name, 6) + (f.driverKey?.let { "[$it] " } ?: "") + f.message)
            }
        }
        appendLine()
        appendLine("(*) = entry absent in this machine's HOME; missing entries break the matching CLI inside the sandbox.")
    }

    private fun detailOf(row: Row): String = buildString {
        appendLine("[" + row.key + "] " + row.displayName)
        appendLine("  path    : " + (row.cliPath ?: "<not found>"))
        appendLine("  launch  : " + (row.launch ?: "-") + "   version: " + (row.version ?: "-"))
        appendLine(
            "  auth    : " + row.authState + (row.authEvidence?.let { " (" + it + ")" } ?: ""),
        )
        appendLine(
            "  model   : " + row.modelMechanism + (row.modelFlag?.let { " " + it } ?: "") +
                "   catalog: " + (row.modelCount?.let { "" + it + " models" } ?: "unknown") +
                (row.modelNote?.let { " (" + it + ")" } ?: ""),
        )
        appendLine(
            "  env     : " + row.envPresent.size + "/" + row.envDeclared + " injected" +
                (row.envPresent.takeIf { it.isNotEmpty() }?.let { "  [" + it.joinToString(", ") + "]" } ?: ""),
        )
        if (row.argv.isEmpty()) {
            appendLine("  argv    : <none — no driver instance>")
        } else {
            appendLine("  argv    : " + row.argv.joinToString(" "))
            row.argvResume?.let { appendLine("  resume  : " + it.joinToString(" ")) }
        }
    }

    private fun pad(text: String, width: Int): String =
        if (text.length >= width) text + " " else text + " ".repeat(width - text.length)

    // ---------------------------------------------------------------- machine probe

    /**
     * 本机默认探测面：发现 + 版本 + 模型目录。
     *
     * **不含登录态**：登录态的逐家判定住在 `:orchestration` 的映射层（那是唯一口径），
     * 在这里再写一份就是两条判据。装配方把真探针注入 [Probe.authOf]。
     */
    class MachineProbe(
        private val env: Map<String, String> = System.getenv(),
        private val home: File = File(System.getProperty("user.home")),
        private val catalog: DriverModelCatalog = DriverModelCatalog(),
        private val versionRunner: suspend (List<String>) -> String? = ::jvmVersion,
    ) : Probe {

        override fun resolveCli(key: String): String? = CliDiscovery.resolveFor(key, env)

        override fun env(): Map<String, String> = env

        override fun home(): File = home

        override suspend fun modelEntryOf(key: String, cliPath: String?): ModelCatalogEntry? {
            if (cliPath == null) return null
            return catalog.snapshot(listOf(key))[key]
        }

        override suspend fun versionOf(key: String, cliPath: String): String? {
            val (cmd, prefix) = CliDiscovery.launchOf(cliPath)
            return versionRunner(listOf(cmd) + prefix + listOf("--version"))
        }
    }

    /**
     * `--version` 的第一行非空输出。
     *
     * 退出码不参与判定：有的 CLI 印完版本以非 0 退出（把 `--version` 当未知子命令）。
     * 读不出任何一行就是 null —— **不编 "unknown" 冒充结论**。
     *
     * 超时同样给 null：`--version` 挂住的 CLI（比如等 stdin 的那种）不能把宿主线程一起挂住，
     * 也不能让残片冒充版本号。超时手段见 [ProbeProcess]（看门狗杀进程，不是 `readText` 之后
     * 才判 `waitFor` —— 那种写法等于没有超时）。
     */
    suspend fun jvmVersion(argv: List<String>, timeoutMs: Long = VERSION_TIMEOUT_MS): String? {
        val result = runCatching { ProbeProcess.run(argv, timeoutMs) }.getOrNull() ?: return null
        if (result.timedOut) return null
        return result.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }
}
