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
                envOverrides.forEach { (key, value) -> pb.environment()[key] = value }
                // stderr 合并进 stdout：认不出的行会被 mapLine 如实跳过，同时避免双流对拷的死锁。
                pb.redirectErrorStream(true)
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

        fun codex(cliPath: String): CliSubprocessDriver = CliSubprocessDriver(
            driverId = "codex-cli",
            displayName = "Codex (CLI)",
            cliPath = cliPath,
            argSpec = { prompt -> listOf("exec", "--json", prompt) },
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
                    argSpec = { prompt -> listOf("exec", "--json", prompt) },
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
