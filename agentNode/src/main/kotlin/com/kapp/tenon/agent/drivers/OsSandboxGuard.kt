// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OS 级轻量沙箱的守卫与包装（触发式编排提案 §9 阶段三）。
 *
 * **opt-in，不默认开启**——嵌套环境（本仓自己的沙箱 / CI 容器）里
 * `sandbox-exec: sandbox_apply: Operation not permitted` 会让每条命令被拒
 * （CliSubprocessDriver 的既有注释记录了这个决定）；因此：
 * - 只在 `TENON_OS_SANDBOX=strict` 显式要求时启用；
 * - 启用前**探活**，探不过就大声失败（D-024：不静默回退到裸跑——
 *   「要求了隔离却拿到裸跑」比「明确失败」危险得多）；
 * - 当前覆盖 **WorktreeSandboxExecutor**（写路径的沙箱执行体）。只读触发通道
 *   （`wsPath = null` 的裸驱动）不套 OS 沙箱：只读任务需要网络（读邮件/查资料），
 *   全网隔离会打断它的正当工作——那一路的约束是工具白名单 + 只读前导。
 *
 * **网络策略的如实边界**：本档落地的是「OS 级网络隔离基建 + 可选 IP 白名单」。
 * per-plugin 的域名白名单（提案 §4 的 `permissions.network`）需要清单支持
 * `permissions` 字段才能成为数据源——该字段仍是声明式审计标记（严格解析拒绝），
 * 域名→IP 的注册期解析与缓存属后续演进（见提案文档 §0 矩阵沙箱行）。
 */
object OsSandboxGuard {

    const val ENV_OS_SANDBOX: String = "TENON_OS_SANDBOX"
    const val STRICT_VALUE: String = "strict"

    /** Linux 侧用 unshare -n；不可用（无 userns/CAP_SYS_ADMIN）即大声失败。 */
    private const val LINUX_UNSHARE: String = "unshare"

    fun isStrictRequested(env: Map<String, String>): Boolean =
        env[ENV_OS_SANDBOX]?.trim()?.equals(STRICT_VALUE, ignoreCase = true) == true

    /** 命令执行结果（探活用；注入以避免测试依赖真机环境）。 */
    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    sealed interface ProbeResult {
        /** 可用，附包装所需的 runner 形状描述（macOS = seatbelt，Linux = unshare）。 */
        data class Available(val kind: Kind) : ProbeResult

        data class Unavailable(val reason: String) : ProbeResult
    }

    enum class Kind { SEATBELT, UNSHARE }

    /**
     * 探活当前环境能否拉起 OS 沙箱。探测本身跑一条真实命令（毫秒级）：
     * macOS 用 `sandbox-exec` 跑一条最小 profile；Linux 用 `unshare -n true`。
     */
    fun probe(
        osName: String = System.getProperty("os.name", ""),
        runCommand: (List<String>, File) -> CommandResult = { cmd, dir -> execForProbe(cmd, dir) },
        tempDir: File = File(System.getProperty("java.io.tmpdir", "/tmp")),
    ): ProbeResult {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> {
                val profile = File(tempDir, "tenon-os-sandbox-probe.sb")
                runCatching { profile.writeText(seatbeltProfile(emptyList())) }
                    .onFailure { return ProbeResult.Unavailable("cannot write probe profile: " + it.message) }
                try {
                    val result = runCommand(
                        listOf("sandbox-exec", "-f", profile.absolutePath, "/bin/echo", "tenon-probe-ok"),
                        tempDir,
                    )
                    if (result.exitCode == 0 && result.stdout.contains("tenon-probe-ok")) {
                        ProbeResult.Available(Kind.SEATBELT)
                    } else {
                        ProbeResult.Unavailable(
                            "sandbox-exec probe failed (exit " + result.exitCode + "): " +
                                result.stderr.trim().take(200).ifEmpty { result.stdout.trim().take(200) } +
                                " — nested environments commonly reject seatbelt (Operation not permitted)",
                        )
                    }
                } catch (t: Throwable) {
                    ProbeResult.Unavailable("sandbox-exec not runnable: " + (t.message ?: t.javaClass.name))
                } finally {
                    runCatching { profile.delete() }
                }
            }

            os.contains("linux") -> {
                try {
                    val result = runCommand(listOf(LINUX_UNSHARE, "-n", "true"), tempDir)
                    if (result.exitCode == 0) {
                        ProbeResult.Available(Kind.UNSHARE)
                    } else {
                        ProbeResult.Unavailable(
                            "unshare -n probe failed (exit " + result.exitCode + "): " +
                                result.stderr.trim().take(200) +
                                " — needs user namespaces or CAP_SYS_ADMIN",
                        )
                    }
                } catch (t: Throwable) {
                    ProbeResult.Unavailable("unshare not runnable: " + (t.message ?: t.javaClass.name))
                }
            }

            else -> ProbeResult.Unavailable("OS \"" + os + "\" has no sandbox wrapper (macOS seatbelt / Linux unshare only)")
        }
    }

    private fun execForProbe(cmd: List<String>, dir: File): CommandResult {
        val proc = ProcessBuilder(cmd).directory(dir).start()
        val done = proc.waitFor(5, TimeUnit.SECONDS)
        if (!done) {
            proc.destroyForcibly()
            return CommandResult(-1, "", "probe timed out")
        }
        return CommandResult(
            proc.exitValue(),
            proc.inputStream.bufferedReader().use { it.readText() },
            proc.errorStream.bufferedReader().use { it.readText() },
        )
    }

    /**
     * macOS seatbelt profile：默认放行、**单点收紧网络**。
     * `(allow default)` + `(deny network*)` 是刻意的最小侵入——任务要能写 worktree、
     * 读工具链、起子进程；被收走的只有出网。白名单 IP 显式放行出站。
     */
    fun seatbeltProfile(allowOutboundIps: List<String>): String = buildString {
        appendLine("(version 1)")
        appendLine("(allow default)")
        appendLine("(deny network*)")
        appendLine("(allow system-socket)")
        allowOutboundIps.forEach { ip ->
            appendLine("(allow network-outbound (remote ip \"$ip\"))")
        }
    }

    /**
     * 包装命令。macOS：`sandbox-exec -f <profile> <cmd...>`；Linux：
     * `unshare -n <cmd...>`（新 netns 内回环也不通——本地 dev server 类任务在 strict
     * 下会失败，这是隔离的代价，如实告知）。
     *
     * **[profileDir] 必须是 worktree 之外**的目录。旧实现写的是
     * `File(worktree, ".tenon-sandbox.sb")`：任务结束后 harvest 用 `git add -A`
     * 收集改动，于是这份**沙箱策略文件本身**被当成用户的改动收进 `patch.diff`，
     * 一个纯读任务也会产出「新增 .tenon-sandbox.sb」的假补丁。调用方传产物目录
     * （随任务清理），策略文件就既不进补丁也不残留。
     */
    fun wrap(
        command: List<String>,
        kind: Kind,
        allowOutboundIps: List<String> = emptyList(),
        profileDir: File = File(System.getProperty("java.io.tmpdir", "/tmp")),
    ): List<String> = when (kind) {
        Kind.SEATBELT -> {
            val profile = File(profileDir, PROFILE_FILE_NAME)
            profileDir.mkdirs()
            profile.writeText(seatbeltProfile(allowOutboundIps))
            listOf("sandbox-exec", "-f", profile.absolutePath) + command
        }

        Kind.UNSHARE -> listOf(LINUX_UNSHARE, "-n") + command
    }

    /** 策略文件名（落在调用方给的 profileDir 里，**不是** worktree）。 */
    const val PROFILE_FILE_NAME: String = "tenon-sandbox.sb"
}
