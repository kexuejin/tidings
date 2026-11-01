// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import com.kapp.tidings.core.nowMillis
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * 每次任务注入子进程的**模型凭证环境变量**（升级轮 T-3a，ModelEnvMapper 产物）。
     * 默认空 = 凭证继承 HOME/XDG_*（既有行为零变化）；非空时逐键 put 进
     * ProcessBuilder.environment() —— 值只进子进程环境，绝不进日志/台账/报文。
     */
    private val envOverrides: Map<String, String> = emptyMap(),
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
        try {
            val exitCode = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(listOf(cliPath) + argSpec(prompt))
                File(wsPath).takeIf { it.isDirectory }?.let { pb.directory(it) }
                // T-3a：模型凭证注入（节点本地 → 子进程环境）。值绝不进日志/台账/报文。
                // PATH 必须先补齐：GUI 启动的宿主只有最小 PATH，脚本型 CLI 会因为找不到
                // 解释器直接 127（见 CliDiscovery.childPath）。envOverrides 在其后覆盖，
                // 显式注入仍然赢。
                pb.environment()["PATH"] = CliDiscovery.childPath(pb.environment())
                envOverrides.forEach { (key, value) -> pb.environment()[key] = value }
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
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        mapLine(line)?.let { onEvent(it) }
                    }
                }
                proc.waitFor()
            }
            if (exitCode == 0) {
                onEvent(DriverEvent.Finished())
            } else {
                onEvent(DriverEvent.Error("cli exited with code $exitCode"))
            }
        } catch (e: Exception) {
            onEvent(DriverEvent.Error("cli subprocess failed: ${e.message ?: e::class.simpleName}"))
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
        fun claude(cliPath: String): CliSubprocessDriver = CliSubprocessDriver(
            driverId = "claude-cli",
            displayName = "Claude Code (CLI)",
            cliPath = cliPath,
            argSpec = { prompt -> listOf("-p", prompt, "--output-format", "stream-json", "--verbose") },
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
         * codex 自己的沙箱模式，默认 `workspace-write`。
         *
         * 唯一需要改的理由是**嵌不进**：宿主进程本身已经在 seatbelt 里跑时（例如集成测试
         * 装置、或被别的沙箱包着启动），codex 再套一层会拿到
         * `sandbox-exec: sandbox_apply: Operation not permitted`，于是它退化成一个连
         * 自己 worktree 都写不了的只读会话 —— 派单永远是「跑完但没交付物」。
         * 这种情况下把 `TIDINGS_CODEX_SANDBOX=danger-full-access` 显式打开，隔离边界
         * 只剩本仓的一次性 worktree（一次性分支 + 收割后无痕清理）。
         * 认不出的值一律回落默认，不猜、不静默放开。
         */
        fun codexSandboxMode(env: Map<String, String> = System.getenv()): String {
            val raw = env["TIDINGS_CODEX_SANDBOX"]?.trim()
            return when (raw) {
                "read-only", "workspace-write", "danger-full-access" -> raw
                else -> "workspace-write"
            }
        }

        fun codex(cliPath: String): CliSubprocessDriver = CliSubprocessDriver(
            driverId = "codex-cli",
            displayName = "Codex (CLI)",
            cliPath = cliPath,
            argSpec = { prompt -> codexArgs(prompt) },
        )
    }
}

/**
 * 按发现结果装配 CLI 驱动清单：找到的装配，没找到的**跳过并打印可操作提示**——
 * 提示里写清怎么补（装 CLI 或设 env），不静默、不 crash。`DriverRegistry.withCliDrivers` 消费它。
 *
 * [sandbox] 非空（T-3b）= 每个 CLI 装配成 [SandboxedCliDriver]（Git Worktree 沙箱 +
 * diff 产物）；null = 裸 [CliSubprocessDriver]（T-3a 语义，既有测试的确定形状）。
 */
fun cliDriverRegistrations(
    resolveClaude: () -> String? = { CliDiscovery.resolveClaudeCli() },
    resolveCodex: () -> String? = { CliDiscovery.resolveCodexCli() },
    resolveAgy: () -> String? = { CliDiscovery.resolveAgyCli() },
    hint: (String) -> Unit = { println(it) },
    sandbox: WorktreeSandboxExecutor? = null,
): List<AgentDriver> {
    val drivers = mutableListOf<AgentDriver>()
    val claude = resolveClaude()
    if (claude != null) {
        drivers.add(
            if (sandbox != null) {
                SandboxedCliDriver(
                    driverId = "claude-cli",
                    displayName = "Claude Code (CLI)",
                    cliPath = claude,
                    argSpec = { prompt -> listOf("-p", prompt, "--output-format", "stream-json", "--verbose") },
                    sandbox = sandbox,
                )
            } else {
                CliSubprocessDriver.claude(claude)
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
        drivers.add(
            if (sandbox != null) {
                SandboxedCliDriver(
                    driverId = "codex-cli",
                    displayName = "Codex (CLI)",
                    cliPath = codex,
                    argSpec = { prompt -> CliSubprocessDriver.codexArgs(prompt) },
                    sandbox = sandbox,
                )
            } else {
                CliSubprocessDriver.codex(codex)
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
        drivers.add(
            if (sandbox != null) {
                SandboxedCliDriver(
                    driverId = "agy-cli",
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
                )
            } else {
                CliSubprocessDriver(
                    driverId = "agy-cli",
                    displayName = "Antigravity (agy CLI)",
                    cliPath = agy,
                    argSpec = { prompt -> listOf("-p", prompt, "--output-format", "stream-json") },
                )
            },
        )
    } else {
        hint(
            "[drivers] agy CLI not found; skipping agy-cli driver " +
                "(install it or set ${CliDiscovery.ENV_AGY_CLI})",
        )
    }
    return drivers
}
