// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.core.nowMillis
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 断点续跑形态：从 CLI 自己的输出里捕获它这次的会话 id，重试时以**续跑参数**重放，
 * 让重试从上一跳的断点继续而不是从零再读一遍上下文。null = 该 CLI 无此能力（重试从头跑）。
 */
data class CliResumeSpec(
    /** 从一行原始输出里解析 CLI 侧会话 id；解析不出返回 null（继续等后面的行）。 */
    val sessionIdFromLine: (line: String) -> String?,
    /** 重试跳的完整启动参数（含续跑标记与 prompt）。 */
    val args: (cliSessionId: String, prompt: String) -> List<String>,
)

/**
 * CLI 子进程驱动（M7-T1）：把用户**已登录**的 Claude Code / Codex CLI 拉起为 agent，
 * 凭证继承 `HOME`/`XDG_*`，不索要任何 Key。
 *
 * ## 一次任务一个子进程
 *
 * 会话表只记 `sessionId → wsPath`；[executeTask] 每次为该会话 spawn 一个子进程
 * （cwd = 工作区路径），stdout 的 NDJSON/JSONL 行流式映射成 [DriverEvent]，
 * 进程退出码是**完成的唯一裁决**。
 *
 * ## 驱动诚信（对标 `DriverHonestyTest` 的纪律）
 *
 * - 退出码 0 ⇒ 恰好一条 [DriverEvent.Finished]；
 * - 退出码非 0 / spawn 失败 ⇒ 只报 [DriverEvent.Error]，**绝不补 Finished** ——
 *   「不为没发生的活报完成」；
 * - 认不出的行（CLI 的 stderr 噪声、私有格式、未来新增的行型）如实跳过，绝不 crash 会话；
 * - 异常不上抛调用方（与 `ExternalPluginDriver` 同一纪律）。
 *
 * ## 大声失败的两处
 *
 * - 未知 `sessionId` 的 executeTask ⇒ [DriverEvent.Error]（不静默执行）；
 * - [handleAction] ⇒ 直接抛：CLI 驱动从不发卡片，误达的动作说明路由出了错。
 */
class CliSubprocessDriver(
    override val driverId: String,
    override val displayName: String,
    private val cliPath: String,
    /** 启动参数（prompt 如何交给 CLI）。真 CLI 的参数变更只动装配处一处。 */
    private val argSpec: (prompt: String) -> List<String>,
    private val newSessionId: () -> String = { java.util.UUID.randomUUID().toString() },
    /**
     * 每次任务注入子进程的**模型凭证环境变量**。
     * 默认空 = 凭证继承 HOME/XDG_*（既有行为零变化）；非空时逐键 put 进
     * ProcessBuilder.environment() —— 值只进子进程环境，绝不进日志/台账/报文。
     */
    private val envOverrides: Map<String, String> = emptyMap(),
    /**
     * 瞬时失败判别：`exitCode < 0` 表示进程没起来（spawn 失败）。默认按 provider
     * 断流 / HTTP 5xx 标记的**白名单**判 —— 配额耗尽这类确定性失败不在名单里，绝不重试。
     */
    private val transientOf: (exitCode: Int, output: String) -> Boolean = ::defaultTransientOf,
    /** 瞬时失败时的追加尝试上限（不含首跳）。 */
    private val maxExtraAttempts: Int = DEFAULT_MAX_EXTRA_ATTEMPTS,
    /** 重试退避（可注入：测试传零延迟，不许用真实 sleep 赌时序）。 */
    private val retryBackoff: suspend (attempt: Int) -> Unit = { attempt ->
        delay(if (attempt <= 1) DEFAULT_BACKOFF_FIRST_MS else DEFAULT_BACKOFF_NEXT_MS)
    },
    /** 断点续跑形态（dsh 有、其余 CLI 暂无）：重试跳以续跑参数重放。 */
    private val resumeSpec: CliResumeSpec? = null,
) : AgentDriver {

    /** sessionId → wsPath（空串 = 无工作区上下文，合法）。 */
    private val sessions = ConcurrentHashMap<String, String>()

    override suspend fun listCapabilities(): List<DriverCapability> = listOf(
        DriverCapability(
            id = "cli-task",
            name = "CLI delegation",
            description = "Run one task in the $displayName subprocess and stream its output",
            category = "model",
        ),
    )

    override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle {
        val id = resumeSessionId ?: newSessionId()
        sessions[id] = wsPath
        return SessionHandle(
            sessionId = id,
            wsPath = wsPath.ifBlank { null },
            resumed = resumeSessionId != null,
            createdAt = nowMillis(),
        )
    }

    override suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    ) {
        val wsPath = sessions[sessionId]
        if (wsPath == null) {
            onEvent(DriverEvent.Error("unknown session \"$sessionId\" — call openSession first"))
            return
        }
        var resumedCliSessionId: String? = null
        val totalAttempts = 1 + maxExtraAttempts.coerceAtLeast(0)
        var lastFailure: String? = null
        // 终局错误必须带上 CLI 自己的 stderr 尾巴（`dsh: <code>: <message>` 这类
        // 故障诊断行）—— 行映射器认不出的行会被如实跳过，但「exit 1」 alone 让
        // 根因不可见（AGENTS §2.5：诊断要么如实、要么不存在）。只取最后 3 行防刷屏。
        fun failureWithStderrTail(failure: String, tail: StringBuilder): String =
            failure + stderrTailOf(tail)
        for (attempt in 1..totalAttempts) {
            // 近邻判别的输出累积：瞬时判别与续跑 id 都从这里取（CLI 输出量有限，全量可承受）。
            val outputTail = StringBuilder()
            val attemptArgs = if (attempt > 1 && resumeSpec != null && resumedCliSessionId != null) {
                resumeSpec.args(resumedCliSessionId!!, prompt)
            } else {
                argSpec(prompt)
            }
            var exitCode = SPAWN_FAILED_EXIT
            var lineLimitBroken = false
            try {
                exitCode = withContext(Dispatchers.IO) {
                    val pb = ProcessBuilder(listOf(cliPath) + attemptArgs)
                    File(wsPath).takeIf { it.isDirectory }?.let {
                        pb.directory(it)
                        // 同 WorktreeSandbox：`directory()` 不改继承来的 PWD，而 opencode
                        // 是按 `$PWD` 解析工作区的（否则产物写到宿主目录去）。
                        pb.environment()["PWD"] = it.absolutePath
                    }
                    // T-3a：模型凭证注入（节点本地 → 子进程环境）。值绝不进日志/台账/报文。
                    // PATH 必须先补齐：GUI 启动的宿主只有最小 PATH，脚本型 CLI 会因为找不到
                    // 解释器直接 127（见 CliDiscovery.childPath）。envOverrides 在其后覆盖，
                    // 显式注入仍然赢。
                    pb.environment()["PATH"] = CliDiscovery.childPath(pb.environment())
                    envOverrides.forEach { (key, value) -> pb.environment()[key] = value }
                    // 会话对齐键（提案 §9 阶段三）：hook 脚本读到它才把 sessionId 透传给
                    // 审批路由，审批卡与只读策略因此挂在派单会话上。
                    //
                    // **所有**子进程都要注入，不能只在 SandboxedCliDriver 的沙箱分支给：
                    // 触发式只读通道走 wsPath=null，SandboxedCliDriver.executeTask 把它
                    // 委托给本裸驱动，那里漏这一只变量 ⇒ hook 不带 sessionId ⇒
                    // hookSessionTrusted 为假 ⇒ 执行前的只读否决整条被跳过。症状是
                    // 「看起来两层拦截，其实只剩事后判负」。
                    //
                    // 放在 envOverrides 之后：宿主派单的 sessionId 是安全判据，不许被配置
                    // 层的同名变量盖掉（盖掉就等于拿一个对不上 readOnlyToolPolicies 的键
                    // 去问审批，只读约束静默失效）。与 SandboxedCliDriver、
                    // ProcessAgentDriver 的「后写者胜」口径一致。
                    pb.environment()[DriverKeys.ENV_HOOK_SESSION_ID] = sessionId
                    // stderr 合并进 stdout：认不出的行会被 mapLine 如实跳过，同时避免双流对拷的死锁。
                    pb.redirectErrorStream(true)
                    // 子进程的 stdin **在内核层**接 /dev/null：会读 stdin 的 CLI 会一直等 EOF，
                    // codex 就是这种形态（`codex exec` 支持管道喂 prompt），只打一行
                    // 「Reading additional input from stdin...」就再无下文：连会话都不建、
                    // 占着 worktree 不放。prompt 已经在 argv 里，无需 stdin。
                    //
                    // 用 `Redirect.from(/dev/null)` 而不是启动后 `outputStream.close()`：
                    // 后者在真机上**没兜住**（责任链首棒曾在此路径长期挂起
                    // 零输出，杀掉才刷出那行提示）。关流给出的是"我们不再写"，不等于子进程
                    // 立刻读到 EOF；/dev/null 从打开那一刻就是 EOF，与 JDK 的缓冲实现无关。
                    // 注：这条**只能真机验**——Gradle 测试进程给子进程的 stdin 已经是 EOF，
                    // 单测复现不出这个挂起。
                    pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                    val proc = pb.start()
                    // 有界读取（§8.2）：这个驱动此前**完全没有**行上限，`useLines` 会把
                    // 一行任意长度的输出整段读进堆。超限行在这里等于「CLI 输出的东西宿主
                    // 理解不了」，按响亮失败收场，不重试（重试只会再喂一次同样的 prompt）。
                    val reader = BoundedLineReader(proc.inputStream.bufferedReader())
                    while (true) {
                        when (val read = reader.readLine()) {
                            BoundedLine.Eof -> break
                            BoundedLine.TooLong -> {
                                lineLimitBroken = true
                                runCatching { proc.destroyForcibly() }
                                break
                            }
                            is BoundedLine.Text -> {
                                val line = read.value
                                outputTail.append(line).append('\n')
                                if (resumedCliSessionId == null) {
                                    resumeSpec?.sessionIdFromLine?.invoke(line)?.let { resumedCliSessionId = it }
                                }
                                mapLine(line)?.let { onEvent(it) }
                            }
                        }
                    }
                    if (lineLimitBroken) return@withContext SPAWN_FAILED_EXIT
                    proc.waitFor()
                }
                // 只在这里报一次错并收场：若交给下面的 `transientOf` 分支，同一个缺陷
                // 会被判成「可重试」再喂一次 prompt，并多报一条 Error。
                if (lineLimitBroken) {
                    onEvent(
                        DriverEvent.Error(
                            "cli \"" + cliPath + "\" emitted a line over " +
                                DriverLineLimits.MAX_LINE_CHARS + " chars; process killed, not retrying",
                        ),
                    )
                    return
                }
                if (exitCode == 0) {
                    onEvent(DriverEvent.Finished())
                    return
                }
                lastFailure = "cli exited with code $exitCode"
                if (!transientOf(exitCode, outputTail.toString())) {
                    // 非瞬时（业务失败 / 配额耗尽 / 输出没有瞬时标记）：一次终局，绝不重试。
                    onEvent(DriverEvent.Error(failureWithStderrTail(lastFailure!!, outputTail)))
                    return
                }
            } catch (e: Exception) {
                lastFailure = "cli subprocess failed: ${e.message ?: e::class.simpleName}"
                if (!transientOf(SPAWN_FAILED_EXIT, outputTail.toString())) {
                    onEvent(DriverEvent.Error(lastFailure!!))
                    return
                }
            }
            if (attempt == totalAttempts) {
                onEvent(
                    DriverEvent.Error(
                        failureWithStderrTail(
                            lastFailure + " — gave up after " + totalAttempts +
                                " attempts (transient retry exhausted)",
                            outputTail,
                        ),
                    ),
                )
                return
            }
            retryBackoff(attempt)
        }
    }

    override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) {
        throw IllegalStateException("cli driver \"$driverId\" does not support card actions")
    }

    override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? = null

    /**
     * 行 → 事件。容忍两类真实形态：claude 的 stream-json（assistant 行嵌
     * `message.content[].text`、result 行收尾）与 codex 的 JSONL（`type` 顶层判别）。
     * 完成与否**不由行裁决** —— 行级的 "result"/"done" 只做参考，诚信裁决在退出码。
     */
    private fun mapLine(line: String): DriverEvent? = CliLineEventMapper.map(line)

    companion object {
        /**
         * 瞬时失败的输出标记**白名单**：provider 断流与 HTTP 5xx 形态。
         * 配额耗尽（`QUOTA` / `Insufficient Balance`）是确定性失败，**不在名单里** ——
         * 没余额时重试只会三连败，还把每一次都真金白银地打上游一遍。
         */
        internal val TRANSIENT_OUTPUT_MARKERS = listOf(
            "STREAM_CLOSED",
            "Internal Server Error",
            "overloaded",
            "rate limit",
        )

        /** 终局错误统一携带的 CLI stderr 尾巴（最后 3 行，行内 200 字上限）——根因可见性。 */
        internal fun stderrTailOf(tail: StringBuilder): String {
            val lines = tail.lines().filter { it.isNotBlank() }.takeLast(3)
                .map { it.trim().take(200) }
            return if (lines.isEmpty()) "" else " ⏎ " + lines.joinToString(" ⏎ ")
        }

        internal const val DEFAULT_MAX_EXTRA_ATTEMPTS = 2
        internal const val DEFAULT_BACKOFF_FIRST_MS = 1_000L
        internal const val DEFAULT_BACKOFF_NEXT_MS = 3_000L

        /** spawn 失败（进程没起来）在判别里的代位退出码。 */
        internal const val SPAWN_FAILED_EXIT = -1

        /** 瞬时判别的默认实现：spawn 失败恒判瞬时；其余按输出标记白名单。 */
        fun defaultTransientOf(exitCode: Int, output: String): Boolean =
            if (exitCode < 0) {
                true
            } else {
                TRANSIENT_OUTPUT_MARKERS.any { output.contains(it, ignoreCase = true) }
            }

        /**
         * dsh headless 的断点续跑形态：NDJSON 首个事件是
         * `{"type":"session","sessionId":…}`；重试跳以 `--session-id` 重放。
         */
        val DSH_RESUME = CliResumeSpec(
            sessionIdFromLine = { line -> RESUME_SESSION_LINE.find(line)?.groupValues?.getOrNull(1) },
            args = { cliSessionId, prompt -> dshResumeArgs(cliSessionId, prompt) },
        )

        fun dshResumeArgs(cliSessionId: String, prompt: String): List<String> =
            listOf("--profile", "headless", "--json", "--session-id", cliSessionId, prompt)

        private val RESUME_SESSION_LINE =
            Regex("\"type\"\\s*:\\s*\"session\".*?\"sessionId\"\\s*:\\s*\"([^\"]+)\"")

        fun claude(
            cliPath: String,
            envOverrides: Map<String, String> = emptyMap(),
        ): CliSubprocessDriver = CliSubprocessDriver(
            driverId = DriverKeys.cliDriverId("claude"),
            displayName = "Claude Code (CLI)",
            cliPath = cliPath,
            argSpec = { prompt -> listOf("-p", prompt, "--output-format", "stream-json", "--verbose") },
            envOverrides = envOverrides,
        )

        /**
         * codex 的命令行参数（两处装配共用，防漂）。
         *
         * `--sandbox workspace-write` 是必需的，不是可选优化：`codex exec` **默认
         * read-only**，agent 连自己 worktree 里的文件都写不了，工具调用全部被拒
         * （`writing is blocked by read-only sandbox`），于是每一次派单都「跑完了但
         * 没有交付物」。写权限的边界由**本仓的 worktree 沙箱**决定（一次性 worktree +
         * 分支，收割后无痕清理），codex 自己那层再设成只读等于让驱动报废。
         */
        fun codexArgs(prompt: String, env: Map<String, String> = System.getenv()): List<String> =
            listOf("exec", "--json", "--sandbox", codexSandboxMode(env), prompt)

        /**
         * opencode 的 headless 参数（实测 v2.2.5）。
         *
         * - `run`：非交互入口（不带子命令会进 TUI，headless 下等于卡住）；
         * - `--standalone`：起私有 server。沙箱把 `HOME` 换成隔离目录，后台 service
         *   那条路在隔离目录里没有意义，也留不下可复用的 state；
         * - `--auto`：自动放行权限，写操作只落一次性 worktree（与 claude 的
         *   `--permission-mode bypassPermissions` 同一条理由）；
         * - `--format json`：行协议，交给 [CliLineEventMapper] 的 opencode 分支。
         */
        fun opencodeArgs(prompt: String): List<String> =
            listOf("run", "--standalone", "--auto", "--format", "json", prompt)

        /**
         * dsh 的 headless 参数（实测 v0.2.1-rc.2，`dsh --help`）。
         *
         * `--profile headless` 是出厂自带的一次性 profile（boot 即答、答完退出）；
         * `--json` 把运行事件打成 NDJSON 行协议（session/status/text/tool_call/
         * tool_result/final），喂给 [CliLineEventMapper] 的 dsh 分支。任务文本是
         * 最后一个位置参数；`-` 读 stdin 我们用不上。
         */
        fun dshArgs(prompt: String): List<String> =
            listOf("--profile", "headless", "--json", prompt)

        /**
         * codex 自己的沙箱模式，默认 `workspace-write`。
         *
         * 唯一需要改的理由是**嵌不进**：宿主进程本身已经在 seatbelt 里跑时（例如集成测试
         * 装置、或被别的沙箱包着启动），codex 再套一层会拿到
         * `sandbox-exec: sandbox_apply: Operation not permitted`，于是它退化成一个连
         * 自己 worktree 都写不了的只读会话 —— 派单永远是「跑完但没交付物」。
         * 这种情况下把 `TENON_CODEX_SANDBOX=danger-full-access` 显式打开，隔离边界
         * 只剩本仓的一次性 worktree（一次性分支 + 收割后无痕清理）。
         * 认不出的值一律回落默认，不猜、不静默放开。
         */
        fun codexSandboxMode(env: Map<String, String> = System.getenv()): String {
            val raw = env["TENON_CODEX_SANDBOX"]?.trim()
            return when (raw) {
                "read-only", "workspace-write", "danger-full-access" -> raw
                else -> "workspace-write"
            }
        }

        fun codex(
            cliPath: String,
            envOverrides: Map<String, String> = emptyMap(),
        ): CliSubprocessDriver = CliSubprocessDriver(
            driverId = DriverKeys.cliDriverId("codex"),
            displayName = "Codex (CLI)",
            cliPath = cliPath,
            argSpec = { prompt -> codexArgs(prompt) },
            envOverrides = envOverrides,
        )
    }
}

/**
 * 按发现结果装配 CLI 驱动清单：找到的装配，没找到的**跳过并打印可操作提示**——
 * 提示里写清怎么补（装 CLI 或设 env），不静默、不 crash。`DriverRegistry.withCliDrivers` 消费它。
 *
 * [sandbox] 非空（T-3b）= 每个 CLI 装配成 [SandboxedCliDriver]（Git Worktree 沙箱 +
 * diff 产物）；null = 裸 [CliSubprocessDriver]（T-3a 语义，既有测试的确定形状）。
 *
 * [env] 是**凭证来源**（默认宿主进程环境）：每个 CLI 只拿到 [CliProviderEnv] 白名单里
 * 属于它的那几只变量。沙箱把 `HOME` 换成隔离目录，登录态因此看不见 —— 不注入的话
 * 真 CLI 在沙箱里永远「装是装了，一跑就没凭证」。测试注入合成 env，结论与本机无关。
 */
fun cliDriverRegistrations(
    resolveClaude: () -> String? = { CliDiscovery.resolveClaudeCli() },
    resolveCodex: () -> String? = { CliDiscovery.resolveCodexCli() },
    resolveAgy: () -> String? = { CliDiscovery.resolveAgyCli() },
    hint: (String) -> Unit = { println(it) },
    sandbox: WorktreeSandboxExecutor? = null,
    env: Map<String, String> = System.getenv(),
    resolveOpencode: () -> String? = { CliDiscovery.resolveOpencodeCli() },
    resolveDsh: () -> String? = { CliDiscovery.resolveDshCli() },
): List<AgentDriver> {
    val drivers = mutableListOf<AgentDriver>()

    /**
     * 「注入了什么」只报**变量名** —— 打印值等于把 key 抄进日志。
     * 空集不打印：没配自定义上游是常态，不需要一行噪声。
     */
    fun announce(driverId: String, injected: Map<String, String>) {
        CliProviderEnv.describe(injected)?.let { names ->
            hint("[drivers] $driverId ← provider env: $names (values stay in the child process)")
        }
    }

    val claude = resolveClaude()
    if (claude != null) {
        val providerEnv = CliProviderEnv.forClaude(env)
        announce(DriverKeys.cliDriverId("claude"), providerEnv)
        drivers.add(
            if (sandbox != null) {
                SandboxedCliDriver(
                    driverId = DriverKeys.cliDriverId("claude"),
                    displayName = "Claude Code (CLI)",
                    cliPath = claude,
                    // `--permission-mode bypassPermissions`：headless（`-p`）下没有人能点
                    // 「允许」，默认权限模式会把每一次写文件都拒掉 —— 派单永远「跑完了但
                    // 没有交付物」。与 agy 的 `--dangerously-skip-permissions` 同一条理由：
                    // 写操作只落在**一次性 worktree**（宿主仓零接触，收割后无痕清理），
                    // 人的守门点是合流审查（merge-patch）。**裸路径不加** —— 那里的工作区
                    // 是机主自己的仓，不该被静默放开。
                    argSpec = { prompt ->
                        listOf(
                            "-p", prompt, "--output-format", "stream-json", "--verbose",
                            "--permission-mode", "bypassPermissions",
                        )
                    },
                    sandbox = sandbox,
                    envOverrides = providerEnv,
                )
            } else {
                CliSubprocessDriver.claude(claude, providerEnv)
            },
        )
    } else {
        hint(
            "[drivers] claude CLI not found; skipping claude-cli driver " +
                "(install it or set ${CliDiscovery.ENV_CLAUDE_CLI})",
        )
    }
    val codex = resolveCodex()
    if (codex != null) {
        val providerEnv = CliProviderEnv.forCodex(env)
        announce(DriverKeys.cliDriverId("codex"), providerEnv)
        drivers.add(
            if (sandbox != null) {
                SandboxedCliDriver(
                    driverId = DriverKeys.cliDriverId("codex"),
                    displayName = "Codex (CLI)",
                    cliPath = codex,
                    argSpec = { prompt -> CliSubprocessDriver.codexArgs(prompt) },
                    sandbox = sandbox,
                    envOverrides = providerEnv,
                )
            } else {
                CliSubprocessDriver.codex(codex, providerEnv)
            },
        )
    } else {
        hint(
            "[drivers] codex CLI not found; skipping codex-cli driver " +
                "(install it or set ${CliDiscovery.ENV_CODEX_CLI})",
        )
    }
    val agy = resolveAgy()
    if (agy != null) {
        // agy 走 Google 登录态，没有可配的自定义上游 ⇒ 白名单为空，注入为空。
        val providerEnv = CliProviderEnv.forAgy(env)
        announce(DriverKeys.cliDriverId("agy"), providerEnv)
        drivers.add(
            if (sandbox != null) {
                SandboxedCliDriver(
                    driverId = DriverKeys.cliDriverId("agy"),
                    displayName = "Antigravity (agy CLI)",
                    cliPath = agy,
                    // 真 CLI 实测（agy 1.2.x）：无 --workspace 旗标 —— cwd 即工作区
                    //（沙箱 worktree 的 directory() 已就位）；stream-json 信封见
                    // CliLineEventMapper 的实测注记。
                    //
                    // --dangerously-skip-permissions：agy 在 headless 下无法弹内部权限
                    // 提示，--mode auto 也压不住（E2E 两次实测 auto-denied）—— 这个
                    // 旗标**只在此处安全**：所有写操作落在一次性 worktree（宿主仓
                    // 零接触），人的守门点 = 合并审查（merge-patch）。隔离边界是我们的
                    // worktree + 合流审批，不是 agy 的交互提示层。
                    argSpec = { prompt ->
                        listOf(
                            "-p", prompt, "--output-format", "stream-json",
                            "--mode", "auto", "--dangerously-skip-permissions",
                        )
                    },
                    sandbox = sandbox,
                    envOverrides = providerEnv,
                )
            } else {
                CliSubprocessDriver(
                    driverId = DriverKeys.cliDriverId("agy"),
                    displayName = "Antigravity (agy CLI)",
                    cliPath = agy,
                    argSpec = { prompt -> listOf("-p", prompt, "--output-format", "stream-json") },
                    envOverrides = providerEnv,
                )
            },
        )
    } else {
        hint(
            "[drivers] agy CLI not found; skipping agy-cli driver " +
                "(install it or set ${CliDiscovery.ENV_AGY_CLI})",
        )
    }
    // opencode：第三个**能真写代码**的 CLI（claude 要登录态、agy 要 Google 网络）。
    //
    // 三种 CLI 里它最依赖外部配置：provider / model 全在 `$OPENCODE_CONFIG` 里，而沙箱
    // 换了 `HOME` 就看不见机主那份 ⇒ 由 [OpenCodeConfig] 递一份指针配置进去。
    // 没有可用配置时仍然注册：那时它的失败是**它自己的**（「没有 provider」），
    // 比「驱动不存在」更好查 —— 但要在装配期就提示如何补齐。
    val opencode = resolveOpencode()
    if (opencode != null) {
        val providerEnv = CliProviderEnv.forOpencode(env).toMutableMap()
        OpenCodeConfig.ensure(env)?.let { providerEnv[OpenCodeConfig.ENV_CONFIG] = it }
        announce(DriverKeys.cliDriverId("opencode"), providerEnv)
        if (providerEnv[OpenCodeConfig.ENV_CONFIG] == null) {
            hint(
                "[drivers] opencode-cli has no provider config; the sandboxed CLI will not reach a model — " +
                    "set OPENCODE_BASE_URL + ${OpenCodeConfig.ENV_MODEL} (or ${OpenCodeConfig.ENV_CONFIG})",
            )
        }
        drivers.add(
            if (sandbox != null) {
                SandboxedCliDriver(
                    driverId = DriverKeys.cliDriverId("opencode"),
                    displayName = "OpenCode (CLI)",
                    cliPath = opencode,
                    // `run` = 非交互入口；`--standalone` 起私有 server（沙箱 HOME 是隔离的，
                    // 后台 service 那条路在隔离目录里没有意义）；`--auto` 自动放行权限
                    // —— 与 claude 的 `--permission-mode bypassPermissions` 同一条理由：
                    // 写操作只落在一次性 worktree。`--format json` 是行协议，喂给
                    // [CliLineEventMapper]。
                    argSpec = { prompt -> CliSubprocessDriver.opencodeArgs(prompt) },
                    sandbox = sandbox,
                    envOverrides = providerEnv,
                )
            } else {
                CliSubprocessDriver(
                    driverId = DriverKeys.cliDriverId("opencode"),
                    displayName = "OpenCode (CLI)",
                    cliPath = opencode,
                    argSpec = { prompt -> CliSubprocessDriver.opencodeArgs(prompt) },
                    envOverrides = providerEnv,
                )
            },
        )
    } else {
        hint(
            "[drivers] opencode CLI not found; skipping opencode-cli driver " +
                "(install it or set ${CliDiscovery.ENV_OPENCODE_CLI})",
        )
    }
    // dsh（DeepSeek Harness CLI）：headless profile 是标准的一次性形态
    // （`dsh --profile headless --json`，NDJSON 行协议 + 退出码诚信），与 codex exec
    // 同类。凭证两路：出厂路由（deepseek-official）只认 env 的 DEEPSEEK_API_KEY
    // （headless 不读 ~/.dsh 凭证服务，实测 MISSING_CREDENTIAL）——由 [DshCredentials]
    // 从凭证文件解析后递进子进程；用户自配路由（如 dsh-opencode-go）走
    // credentials-local 服务直接读同一份 ~/.dsh/.credentials.yaml（沙箱经 .dsh
    // 软链同样可见）——所以装配门槛是「文件存在」，具体哪路凭证由 profile 决定。
    val dsh = resolveDsh()
    if (dsh != null) {
        val providerEnv = CliProviderEnv.forDsh(env).toMutableMap()
        DshCredentials.resolve(env)?.let { providerEnv[DshCredentials.ENV_KEY] = it }
        // dsh 内部的 bash 工具还有**一层自己的沙箱**（SANDBOX_UNAVAILABLE：macOS 上要
        // sandbox-exec；宿主在嵌套环境里 sandbox-exec 不可用 ⇒ 每条命令都被它拒）。
        // 与 TENON_CODEX_SANDBOX=danger-full-access 同一条纪律：写操作只落在
        // 一次性 worktree（我们的沙箱就是隔离面），内层不再要求后端。
        providerEnv.putIfAbsent("DSH_PERMISSION_MODE", "danger-full-access")
        announce(DriverKeys.cliDriverId("dsh"), providerEnv)
        if (providerEnv[DshCredentials.ENV_KEY] == null && !DshCredentials.hasCredentialFile(env)) {
            hint(
                "[drivers] dsh-cli has no provider credential (no DEEPSEEK_API_KEY env and no " +
                    "~/.dsh/.credentials.yaml); the headless route will fail without one",
            )
        }
        drivers.add(
            if (sandbox != null) {
                SandboxedCliDriver(
                    driverId = DriverKeys.cliDriverId("dsh"),
                    displayName = "DeepSeek Harness (CLI)",
                    cliPath = dsh,
                    argSpec = { prompt -> CliSubprocessDriver.dshArgs(prompt) },
                    sandbox = sandbox,
                    envOverrides = providerEnv,
                    resumeSpec = CliSubprocessDriver.DSH_RESUME,
                )
            } else {
                CliSubprocessDriver(
                    driverId = DriverKeys.cliDriverId("dsh"),
                    displayName = "DeepSeek Harness (CLI)",
                    cliPath = dsh,
                    argSpec = { prompt -> CliSubprocessDriver.dshArgs(prompt) },
                    envOverrides = providerEnv,
                    resumeSpec = CliSubprocessDriver.DSH_RESUME,
                )
            },
        )
    } else {
        hint(
            "[drivers] dsh CLI not found; skipping dsh-cli driver " +
                "(install it or set ${CliDiscovery.ENV_DSH_CLI})",
        )
    }
    return drivers
}
