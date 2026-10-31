// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * T-3b Worktree 沙箱执行器（D-024）的全组用例：**真 git + 假 CLI 脚本**。
 *
 * git 是本仓自身的硬前置（clone 都靠它），测试用真 git 与 envDependentTestGate
 * 的口径一致——依赖的是「构建工具链在」，不是「某台机器的某个文件在」；CLI 一侧
 * 沿 [CliSubprocessDriverTest] 的假脚本惯例，不依赖本机装没装真 CLI。
 *
 * 钉的七件事（D-024 逐条）：
 * 1. 沙箱 = 宿主仓的独立 worktree + 独立分支 `task-<taskId>`，宿主工作树零变化；
 * 2. 产物提纯 = patch.diff + report.json 写到**沙箱外**的产物目录（无痕清理后仍在），
 *    report 是结构化摘要（SHA256 指针），**绝不内联 CLI 原始输出**（§7.7 数据指针层）；
 * 3. 诚信裁决：退出码非 0 只报 Failed，无产物无补写；
 * 4. 中止 = 进程树 SIGTERM → 3s → SIGKILL，随后 worktree 无痕清理；
 * 5. 协程取消（租约超时的真实路径）= 同一条中止路径，清理照样跑（NonCancellable）；
 * 6. 无痕：成功/失败/中止三条路径之后，宿主仓的 `git worktree list` 与分支清单都干净；
 * 7. taskId 是路径段与分支名：越界形状（路径穿越 / 分隔符 / 超长）在**任何 git 调用
 *    之前**就拒绝。
 */
class WorktreeSandboxExecutorTest {

    private fun tempDir(): File = Files.createTempDirectory("wt-sandbox-test").toFile()

    private fun script(dir: File, name: String, body: String): String {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\n$body\n")
        assertTrue(f.setExecutable(true), "假 CLI 必须可执行")
        return f.absolutePath
    }

    /** 建一个带首个提交的真 git 仓（worktree add 需要 HEAD 有效）。 */
    private fun gitRepo(dir: File) {
        dir.mkdirs()
        runGit(dir, "init")
        runGit(dir, "config", "user.email", "test@example.invalid")
        runGit(dir, "config", "user.name", "Sandbox Test")
        File(dir, "seed.txt").writeText("seed\n")
        runGit(dir, "add", ".")
        runGit(dir, "commit", "-m", "init")
    }

    private fun runGit(cwd: File?, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals(0, code, "git ${args.joinToString(" ")} 失败: $err")
    }

    private fun gitLines(cwd: File, vararg args: String): List<String> {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out.lines().filter { it.isNotBlank() }
    }

    private fun executor(root: File) = WorktreeSandboxExecutor(sandboxRoot = root)

    // ── 1. 沙箱隔离形状 ─────────────────────────────────────────────────────

    @Test
    fun taskRunsInAnIsolatedWorktreeAndHostWorktreeIsUntouched() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(root, "cli", """
            printf '{"type":"text","text":"hello from sandbox"}\n'
            echo "sandbox edit" >> "${'$'}TASK_FILE"
            exit 0
        """.trimIndent())
        val taskFile = File(host, "seed.txt")  // CLI 相对路径写 → 落在它的 cwd（worktree）
        val exe = executor(root)
        val outcome = exe.run(
            taskId = "t1",
            hostRepo = host.absolutePath,
            command = listOf(cli),
            env = mapOf("TASK_FILE" to "seed.txt"),
            onLine = {},
        )
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Finished, "退出码 0 = Finished，实际 $outcome")
        // 改动落在 worktree（隔离面），宿主工作树原样。worktree 在成功路径上已被
        // 无痕清理 —— 改动确实发生过的证据就是 harvest 的 patch（沙箱内的工作面
        // 交付后即拆台）。
        outcome as WorktreeSandboxExecutor.Outcome.Finished
        val harvest = requireNotNull(outcome.harvest) { "宿主仓有改动的任务必有 harvest" }
        assertEquals(1, harvest.filesChanged, "改动 = seed.txt 一处")
        assertEquals(1, harvest.additions)
        assertEquals("seed\n", taskFile.readText(), "宿主工作树不能被任务改写 —— 沙箱没隔离住")
        // 成功路径结束即无痕：worktree 与分支都清掉了。
        // 过滤用 worktree 的 canonical 路径精确匹配 —— 不能 contains("t1")：
        // 临时目录随机名（wt-sandbox-test1429…）自己就含 "t1" 子串（实测踩过）。
        val wtCanonical = File(root, "t1").canonicalPath
        val worktrees = gitLines(host, "worktree", "list").filter { it.contains(wtCanonical) }
        assertTrue(worktrees.isEmpty(), "成功路径后宿主仓不应再挂着 t1 的 worktree")
        val branches = gitLines(host, "branch", "--list", "task-t1")
        assertTrue(branches.isEmpty(), "task-t1 分支应随无痕清理删掉")
    }

    // ── 2. 产物提纯 ─────────────────────────────────────────────────────────

    @Test
    fun harvestWritesPureArtifactsOutsideTheSandbox() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(root, "cli", """
            printf '{"type":"text","text":"RAW_LOG_MARKER-should-not-leak-into-report"}\n'
            printf 'one\ntwo\n' >> feature.txt
            exit 0
        """.trimIndent())
        val exe = executor(root)
        val outcome = exe.run(
            taskId = "t2",
            hostRepo = host.absolutePath,
            command = listOf(cli),
            env = emptyMap(),
            onLine = {},
        )
        outcome as WorktreeSandboxExecutor.Outcome.Finished
        val harvest = requireNotNull(outcome.harvest) { "有改动的任务必有 harvest" }
        assertEquals(1, harvest.filesChanged, "一个新文件 = 1 changed")
        assertEquals(2, harvest.additions)
        assertEquals(0, harvest.deletions)
        val artifacts = harvest.patchPath.parentFile
        val patch = File(artifacts, "patch.diff")
        val report = File(artifacts, "report.json")
        assertTrue(patch.isFile, "patch.diff 必须落盘")
        assertTrue(report.isFile, "report.json 必须落盘")
        val patchText = patch.readText()
        assertTrue(patchText.contains("feature.txt") && patchText.contains("+one"), "patch 是全量 diff")
        val reportText = report.readText()
        assertTrue(reportText.contains("\"outcome\":\"finished\""), "report 记录终态")
        assertTrue(reportText.contains("patchSha256"), "report 带 patch 指针（SHA256）")
        // 提纯判据：原始输出**只**在日志文件里，report 不内联 —— §7.7 数据指针层。
        assertTrue(reportText.contains("logSha256"), "report 带日志指针")
        assertFalse(reportText.contains("RAW_LOG_MARKER"), "report.json 不得内联 CLI 原始输出（提纯）")
        val log = File(artifacts, "task.log")
        assertTrue(log.isFile && log.readText().contains("RAW_LOG_MARKER"), "原始输出归日志文件（数据指针的指向）")
        // worktree 清了，产物还在（无痕清的是工作面，不是交付物）。
        assertTrue(!File(root, "t2").exists(), "worktree 应已清理")
        assertTrue(artifacts.isDirectory, "产物目录在沙箱外存活")
    }

    // ── 3. 诚信：非 0 退出 ───────────────────────────────────────────────────

    @Test
    fun nonZeroExitMeansFailedWithoutArtifacts() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(root, "cli", "printf 'oops\\n'; exit 3")
        val exe = executor(root)
        val outcome = exe.run(
            taskId = "t3",
            hostRepo = host.absolutePath,
            command = listOf(cli),
            env = emptyMap(),
            onLine = {},
        )
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Failed, "退出码 3 = Failed，实际 $outcome")
        assertEquals(3, (outcome as WorktreeSandboxExecutor.Outcome.Failed).exitCode)
        assertTrue(
            !File(root, "artifacts/t3/patch.diff").isFile() && !File(root, "artifacts/t3/report.json").isFile(),
            "失败任务不产出交付物（不为没发生的活出 patch/report）",
        )
        assertTrue(!File(root, "t3").exists(), "失败路径同样无痕清理 worktree")
    }

    // ── 4. 中止：SIGTERM → 3s → SIGKILL + 无痕 ──────────────────────────────

    @Test
    fun abortTerminatesTheProcessTreeAndCleansTheWorktree() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val marker = File(root, "started.flag")
        val cli = script(root, "cli", "touch '${marker.absolutePath}'; sleep 30")
        val exe = executor(root)
        val scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.Default)
        val done = CompletableDeferred<WorktreeSandboxExecutor.Outcome>()
        scope.launch { done.complete(exe.run("t4", host.absolutePath, listOf(cli), emptyMap(), {})) }
        withTimeout(10_000) {
            while (!marker.exists()) delay(50)
        }
        val aborted = withTimeout(15_000) { exe.abort("t4") }
        assertTrue(aborted, "abort 命中了在跑的任务")
        val outcome = withTimeout(10_000) { done.await() }
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Aborted, "中止后的终局是 Aborted，实际 $outcome")
        assertTrue(!File(root, "t4").exists(), "中止后 worktree 无痕清理")
        assertTrue(gitLines(host, "branch", "--list", "task-t4").isEmpty(), "中止后分支无痕清理")
        scope.cancel()
    }

    // ── 5. 协程取消 = 同一条中止路径 ─────────────────────────────────────────

    @Test
    fun coroutineCancellationRunsTheSameAbortPath() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val marker = File(root, "started2.flag")
        val cli = script(root, "cli", "touch '${marker.absolutePath}'; sleep 30")
        val exe = executor(root)
        val scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.Default)
        val task = scope.launch { exe.run("t5", host.absolutePath, listOf(cli), emptyMap(), {}) }
        withTimeout(10_000) {
            while (!marker.exists()) delay(50)
        }
        task.cancel()
        // 终态轮询：清理是「remove → prune → branch -D」三步串行，目录消失只证明
        // 第一步完成 —— 分支的消失才是最后一步的因果事实，轮询它（不是赌时序）。
        withTimeout(15_000) {
            while (gitLines(host, "branch", "--list", "task-t5").isNotEmpty()) delay(50)
        }
        assertTrue(!File(root, "t5").exists(), "取消路径 worktree 无痕清理")
        scope.cancel()
    }

    // ── 6. 大声失败 ──────────────────────────────────────────────────────────

    @Test
    fun hostRepoWithoutGitFailsLoudly() = runBlocking {
        val root = tempDir()
        val notARepo = tempDir()
        val cli = script(root, "cli", "exit 0")
        val exe = executor(root)
        val outcome = exe.run("t6", notARepo.absolutePath, listOf(cli), emptyMap(), {})
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Failed, "不是 git 仓 = 建不出沙箱，大声失败，实际 $outcome")
        assertTrue(
            (outcome as WorktreeSandboxExecutor.Outcome.Failed).message.contains("worktree"),
            "失败信息要指向真原因（worktree 建不出来）",
        )
    }

    @Test
    fun staleWorktreeFromACrashSelfHealsOnCreate() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        // 预置「上次崩溃的残骸」：同分支 + 同路径 worktree 挂着（模拟 SIGKILL 后无痕清理没跑到）。
        runGit(host, "worktree", "add", "-b", "task-t7", File(root, "t7").absolutePath)
        File(root, "t7").resolve("stale.txt").writeText("stale\n")
        val cli = script(root, "cli", "echo recovered >> seed.txt; exit 0")
        val exe = executor(root)
        val outcome = exe.run("t7", host.absolutePath, listOf(cli), emptyMap(), {})
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Finished, "残骸自愈后正常执行，实际 $outcome")
        // 执行后无痕：不残留第二份。
        assertTrue(!File(root, "t7").exists())
        val worktrees = gitLines(host, "worktree", "list").filter { it.contains(File(root, "t7").canonicalPath) }
        assertTrue(worktrees.isEmpty())
    }

    @Test
    fun malformedTaskIdIsRejectedBeforeAnyGitCall() {
        val root = tempDir()
        val exe = executor(root)
        for (bad in listOf("../escape", "a/b", "", "a b", "t".repeat(65))) {
            runBlocking {
                assertFailsWith<IllegalArgumentException>("taskId=<$bad> 必须在任何 git 调用前被拒") {
                    exe.run(bad, tempDir().absolutePath, listOf("/bin/true"), emptyMap(), {})
                }
            }
        }
    }

    private fun assertFalse(actual: Boolean, message: String) {
        kotlin.test.assertFalse(actual, message)
    }
}
