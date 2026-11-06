// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * M7-T1 CLI 子进程驱动的全组用例：**全程用假脚本替代真 CLI**，不依赖本机装没装
 * claude / codex（envDependentTestGate）。
 *
 * 钉的四件事：
 * 1. 发现器是纯函数：显式 env > `$PATH` > 公开约定目录 > null；坏指针宁可不注册也不悄悄换人；
 * 2. 驱动诚信：退出码 0 才 Finished；非 0 / spawn 失败只报 Error、绝不补 Finished；
 * 3. 大声失败：未知会话答 Error 事件不静默执行；卡片动作直接抛（CLI 从不发卡片）；
 * 4. 装配：没装的 CLI 跳过并给出**可操作**提示，不 crash、不静默。
 */
class CliSubprocessDriverTest {

    private fun tempDir(): java.io.File = Files.createTempDirectory("cli-driver-test").toFile()

    private fun script(dir: java.io.File, name: String, body: String): String {
        val f = java.io.File(dir, name)
        f.writeText("#!/bin/sh\n$body\n")
        assertTrue(f.setExecutable(true), "假 CLI 必须可执行")
        return f.absolutePath
    }

    // ---------------------------------------------------------------- CliDiscovery

    @Test
    fun discoveryFindsExecutableOnPath() {
        val dir = tempDir()
        val bin = script(dir, "claude", "exit 0")
        val env = mapOf("PATH" to dir.absolutePath)
        assertEquals(bin, CliDiscovery.resolveClaudeCli(env = env, pathDirs = CliDiscovery.pathDirsOf(env)))
    }

    @Test
    fun explicitEnvWinsAndABrokenPointerIsNotSilentlyReplaced() {
        val dirA = tempDir()
        val onPath = script(dirA, "codex", "exit 0")
        val dirB = tempDir()
        val explicit = script(dirB, "codex", "exit 0")
        assertEquals(
            explicit,
            CliDiscovery.resolveCodexCli(
                env = mapOf(CliDiscovery.ENV_CODEX_CLI to explicit, "PATH" to dirA.absolutePath),
                pathDirs = CliDiscovery.pathDirsOf(mapOf("PATH" to dirA.absolutePath)),
            ),
            "显式 env 优先于 PATH",
        )
        // 用户 pin 的那一份不可执行 = 配置错误：宁可不注册（装配层打印提示），
        // 也不悄悄改用 PATH 上另一份二进制 —— pin 的就是这一份。
        assertEquals(
            null,
            CliDiscovery.resolveCodexCli(
                env = mapOf(CliDiscovery.ENV_CODEX_CLI to "/nonexistent/codex", "PATH" to dirA.absolutePath),
                pathDirs = CliDiscovery.pathDirsOf(mapOf("PATH" to dirA.absolutePath)),
            ),
            "坏指针不许静默回退到 PATH 上的另一份",
        )
    }

    @Test
    fun childPathAddsInterpreterDirsThatAGuiLaunchLacks() {
        // GUI（LaunchServices）拉起的宿主只有最小 PATH，而 codex 是
        // `#!/usr/bin/env node` 的脚本 —— 不补 node 的 bin，子进程直接 127。
        val home = tempDir()
        val nvmBin = java.io.File(home, ".nvm/versions/node/v20.1.0/bin").apply { mkdirs() }
        val parts = CliDiscovery.childPath(
            mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.absolutePath),
        ).split(":")
        assertTrue(nvmBin.absolutePath in parts, "必须补上 nvm 的 node bin：$parts")
        assertTrue("/usr/bin" in parts, "宿主原有 PATH 不能丢")
        assertTrue(parts.none { it.isBlank() }, "不能塞空路径（等于把 cwd 放进 PATH）")
    }

    @Test
    fun nothingFoundIsNullNotAnException() {
        val empty = tempDir()
        assertEquals(null, CliDiscovery.resolveClaudeCli(env = emptyMap(), pathDirs = listOf(empty.absolutePath)))
    }

    // ---------------------------------------------------------------- 驱动行为

    @Test
    fun streamingTaskEmitsTokenThenFinishedOnExitZero() = runBlocking {
        val dir = tempDir()
        val cli = script(
            dir, "claude",
            "printf '%s\\n' '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}'\n" +
                "exit 0",
        )
        val driver = CliSubprocessDriver("claude-cli", "Claude Code (CLI)", cli, argSpec = { listOf(it) })
        val handle = driver.openSession("", null)
        val events = mutableListOf<DriverEvent>()
        driver.executeTask(handle.sessionId, "go") { events.add(it) }
        assertTrue(
            events.any { it is DriverEvent.TokenDelta && it.delta == "hello" },
            "assistant 行嵌套文本要映射成 TokenDelta",
        )
        assertTrue(events.last() is DriverEvent.Finished, "退出码 0 ⇒ 收在 Finished")
    }

    @Test
    fun nonZeroExitReportsErrorAndNeverFinished() = runBlocking {
        val dir = tempDir()
        val cli = script(
            dir, "claude",
            "printf '%s\\n' '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}}'\n" +
                "exit 1",
        )
        val driver = CliSubprocessDriver("claude-cli", "Claude Code (CLI)", cli, argSpec = { listOf(it) })
        val handle = driver.openSession("", null)
        val events = mutableListOf<DriverEvent>()
        driver.executeTask(handle.sessionId, "go") { events.add(it) }
        assertTrue(events.any { it is DriverEvent.Error }, "非零退出必须报 Error")
        assertTrue(events.none { it is DriverEvent.Finished }, "驱动诚信：没完成的活不许报 Finished")
    }

    @Test
    fun codexSandboxModeDefaultsToWorkspaceWriteAndRejectsJunk() {
        assertEquals("workspace-write", CliSubprocessDriver.codexSandboxMode(emptyMap()))
        assertEquals(
            "danger-full-access",
            CliSubprocessDriver.codexSandboxMode(mapOf("TIDINGS_CODEX_SANDBOX" to "danger-full-access")),
            "嵌不进沙箱的宿主需要一个显式逃生口",
        )
        assertEquals(
            "workspace-write",
            CliSubprocessDriver.codexSandboxMode(mapOf("TIDINGS_CODEX_SANDBOX" to "yes-please")),
            "认不出的值不许静默放开权限",
        )
    }

    @Test
    fun spawnFailureReportsErrorWithoutThrowingToTheCaller() = runBlocking {
        val driver = CliSubprocessDriver(
            "claude-cli", "Claude Code (CLI)", "/nonexistent/cli-binary", argSpec = { listOf(it) },
        )
        val handle = driver.openSession("", null)
        val events = mutableListOf<DriverEvent>()
        driver.executeTask(handle.sessionId, "go") { events.add(it) }
        assertTrue(events.any { it is DriverEvent.Error }, "spawn 失败如实上报，不上抛")
        assertTrue(events.none { it is DriverEvent.Finished }, "没跑起来的活更不许报 Finished")
    }

    @Test
    fun unknownSessionGetsAnErrorEventNotASilentExecution() = runBlocking {
        val driver = CliSubprocessDriver("claude-cli", "Claude Code (CLI)", "/bin/true", argSpec = { listOf(it) })
        val events = mutableListOf<DriverEvent>()
        driver.executeTask("no-such-session", "go") { events.add(it) }
        assertTrue(
            events.any { it is DriverEvent.Error && it.message.contains("unknown session") },
            "未知会话要大声说，不是当作空任务跑掉",
        )
    }

    @Test
    fun resumeKeepsTheSameSessionIdAndMarksResumed() = runBlocking {
        val driver = CliSubprocessDriver("claude-cli", "Claude Code (CLI)", "/bin/true", argSpec = { listOf(it) })
        val first = driver.openSession("ws", null)
        val resumed = driver.openSession("ws", first.sessionId)
        assertEquals(first.sessionId, resumed.sessionId, "接续沿用同一会话键")
        assertTrue(resumed.resumed)
        assertEquals("ws", resumed.wsPath)
    }

    @Test
    fun cardActionsFailLoudlyAndProgressIsHonestlyNull() = runBlocking {
        val driver = CliSubprocessDriver("claude-cli", "Claude Code (CLI)", "/bin/true", argSpec = { listOf(it) })
        assertFailsWith<IllegalStateException> { driver.handleAction("s", "a", "me") }
        assertEquals(null, driver.queryProgress("s"), "没有进度就说没有（null 是合同内语义）")
    }

    // ---------------------------------------------------------------- 装配

    @Test
    fun unresolvedClisAreSkippedWithActionableHints() {
        val hints = mutableListOf<String>()
        val drivers = cliDriverRegistrations(
            resolveClaude = { null },
            resolveCodex = { null },
            resolveAgy = { null },
            resolveOpencode = { null },
            resolveDsh = { null },
            hint = { hints.add(it) },
        )
        assertTrue(drivers.isEmpty(), "没装就不注册")
        assertEquals(5, hints.size, "每个没装的 CLI 一条提示（claude/codex/agy/opencode/dsh）")
        assertTrue(hints[0].contains(CliDiscovery.ENV_CLAUDE_CLI), "提示必须可操作：写清 env 名")
    }

    @Test
    fun resolvedClisRegisterBothKindsInOrder() {
        val drivers = cliDriverRegistrations(
            resolveClaude = { "/bin/true" },
            resolveCodex = { "/bin/true" },
            resolveAgy = { "/bin/true" },
            resolveOpencode = { "/bin/true" },
            resolveDsh = { "/bin/true" },
            hint = {},
        )
        assertEquals(listOf("claude-cli", "codex-cli", "agy-cli", "opencode-cli", "dsh-cli"), drivers.map { it.driverId })
    }
}
