// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * T-3b 沙箱化 CLI 驱动的路由判据（真 git + 假 CLI，沿本包惯例）：
 *
 * 1. **有仓库的会话走沙箱**：事件 = 行映射 + 恰好一张 diff-review 卡（有 diff 才出卡）
 *    + Finished 收尾；产物落盘；worktree 无痕；
 * 2. **空工作区走裸路径**：沙箱目录零创建（行为与 T-3a 逐字节一致的那一面）；
 * 3. **非 git 的工作区大声失败**：Error 指向沙箱，CLI **没有被执行**（静默回退到裸跑
 *    = 沙箱开关失效而不告诉你，D-024 不允许这条档位）；
 * 4. 装配：registrations 带 sandbox ⇒ 装出来的是 SandboxedCliDriver。
 */
class SandboxedCliDriverTest {

    private fun tempDir(): File = Files.createTempDirectory("sandboxed-driver-test").toFile()

    private fun script(dir: File, name: String, body: String): String {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\n$body\n")
        assertTrue(f.setExecutable(true), "假 CLI 必须可执行")
        return f.absolutePath
    }

    private fun gitRepo(dir: File) {
        dir.mkdirs()
        runGit(dir, "init")
        runGit(dir, "config", "user.email", "test@example.invalid")
        runGit(dir, "config", "user.name", "Sandboxed Driver Test")
        File(dir, "seed.txt").writeText("seed\n")
        runGit(dir, "add", ".")
        runGit(dir, "commit", "-m", "init")
    }

    private fun runGit(cwd: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val err = proc.errorStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "git ${args.joinToString(" ")} 失败: $err")
    }

    private fun events(block: suspend (java.util.concurrent.LinkedBlockingQueue<DriverEvent>) -> Unit):
        List<DriverEvent> {
        val queue = java.util.concurrent.LinkedBlockingQueue<DriverEvent>()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Default)
            .launch {
                block(queue)
                done.complete(Unit)
            }
        runBlocking { kotlinx.coroutines.withTimeout(30_000) { done.await() } }
        return queue.toList()
    }

    @Test
    fun repoSessionRunsSandboxedAndEmitsOneDiffCard() {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(root, "cli", """
            printf '{"type":"text","text":"working"}\n'
            printf 'alpha\nbeta\n' >> feature.txt
            exit 0
        """.trimIndent())
        val sandbox = WorktreeSandboxExecutor(sandboxRoot = root)
        val driver = SandboxedCliDriver(
            driverId = "claude-cli",
            displayName = "Claude Code (CLI)",
            cliPath = cli,
            argSpec = { prompt -> listOf("-p", prompt) },
            sandbox = sandbox,
        )
        val collected = events { queue ->
            val session = driver.openSession(host.absolutePath, null)
            driver.executeTask(session.sessionId, "do things") { queue.put(it) }
        }
        val types = collected.map { it::class.simpleName }
        assertTrue(types.containsAll(listOf("TokenDelta", "Card", "Finished")), "三类事件都在：$types")
        assertEquals("Finished", types.last(), "Finished 收尾")
        assertEquals(1, types.count { it == "Card" }, "有 diff 才出卡，且恰好一张")
        val card = collected.filterIsInstance<DriverEvent.Card>().single()
        assertTrue(card.cardJson.contains("\"type\":\"diff-review\""), "卡类型 = diff-review")
        assertTrue(card.cardJson.contains("\"additions\":2"), "提纯统计进卡面")
        // 产物在、工作面无痕。
        assertTrue(
            File(root, "artifacts").list().orEmpty().any { it.startsWith("t-") },
            "沙箱产物目录已落盘",
        )
        assertTrue(gitLines(host, "branch", "--list", "task-*").isEmpty(), "任务分支无痕清理")
    }

    @Test
    fun blankWorkspaceDelegatesToThePlainPath() {
        val root = tempDir()
        val cli = script(root, "cli", "printf '{\"type\":\"text\",\"text\":\"plain\"}\\n'; exit 0")
        val sandbox = WorktreeSandboxExecutor(sandboxRoot = root)
        val driver = SandboxedCliDriver(
            driverId = "claude-cli",
            displayName = "Claude Code (CLI)",
            cliPath = cli,
            argSpec = { listOf(it) },
            sandbox = sandbox,
        )
        val collected = events { queue ->
            val session = driver.openSession("", null)
            driver.executeTask(session.sessionId, "no workspace task") { queue.put(it) }
        }
        val types = collected.map { it::class.simpleName }
        assertTrue("TokenDelta" in types, "裸路径的行事件照常：$types")
        assertTrue(types.last() == "Finished", "裸路径 Finished 收尾")
        assertTrue(
            File(root, "artifacts").list().orEmpty().isEmpty(),
            "空工作区任务不产生沙箱产物（根本没有沙箱）",
        )
    }

    @Test
    fun nonGitWorkspaceFailsLoudAndNeverRunsTheCli() {
        val root = tempDir()
        val notARepo = tempDir()
        val marker = File(root, "cli-ran.flag")
        val cli = script(root, "cli", "touch '${marker.absolutePath}'; exit 0")
        val sandbox = WorktreeSandboxExecutor(sandboxRoot = root)
        val driver = SandboxedCliDriver(
            driverId = "claude-cli",
            displayName = "Claude Code (CLI)",
            cliPath = cli,
            argSpec = { listOf(it) },
            sandbox = sandbox,
        )
        val collected = events { queue ->
            val session = driver.openSession(notARepo.absolutePath, null)
            driver.executeTask(session.sessionId, "should not run") { queue.put(it) }
        }
        val errors = collected.filterIsInstance<DriverEvent.Error>()
        assertEquals(1, errors.size, "恰好一条 Error：$collected")
        assertTrue(errors.single().message.contains("sandbox"), "失败信息指向真原因（沙箱建不出来）")
        assertTrue(!marker.exists(), "CLI 没有被执行 —— 非仓工作区不许静默裸跑")
    }

    private fun gitLines(cwd: File, vararg args: String): List<String> {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out.lines().filter { it.isNotBlank() }
    }
}
