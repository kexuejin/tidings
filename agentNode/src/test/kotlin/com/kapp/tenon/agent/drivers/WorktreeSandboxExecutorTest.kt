// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    // ── 2b. 修改型任务的全文快照 ─────────────────────────────────────────────

    /**
     * refine 轮的形态：CLI 改的是**已存在**的产物文件，diff 是多 hunk 修改 ——
     * 从 patch 拼不回整份文件（StageChain.extractFileFromPatch 按设计拒绝残缺重构）。
     * 收割时必须把改动文件的全文快照进 `files/`，沙箱无痕清理后调用方（编排器的
     * 产物投递）才有真全文可交 —— 否则「Refined prototype ready」这句话在手机上
     * 没有任何可打开的东西（真机 refine 轮实测踩到）。
     */
    @Test
    fun modifiedFileIsSnapshottedInFullForRefineDelivery() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        // 基线里放一份既有产物（refine 改的就是它，不是新文件）。
        File(host, "prototype.html").writeText("<html>v1 line1\nv1 line2\nv1 line3\n")
        runGit(host, "add", ".")
        runGit(host, "commit", "-m", "baseline with prototype")
        val cli = script(root, "cli", """
            printf 'v2 line1\nv2 line2\nv2 line3\nv2 line4\n' > prototype.html
            exit 0
        """.trimIndent())
        val exe = executor(root)
        val outcome = exe.run(
            taskId = "t2b",
            hostRepo = host.absolutePath,
            command = listOf(cli),
            env = emptyMap(),
            onLine = {},
        )
        outcome as WorktreeSandboxExecutor.Outcome.Finished
        val harvest = requireNotNull(outcome.harvest) { "有改动的任务必有 harvest" }
        val patchText = harvest.patchPath.readText()
        assertTrue(patchText.contains("--- a/prototype.html"), "改动型 diff 的旧侧是既有文件")
        assertFalse(patchText.contains("--- /dev/null"), "本用例没有新建文件")
        // 快照：files/prototype.html 存在、是**修改后的全文**（4 行 v2），
        // 且 patch.diff 里只有 hunk —— 没有快照的话谁也拼不出这 4 行。
        val snapshot = File(harvest.patchPath.parentFile, "files/prototype.html")
        assertTrue(snapshot.isFile, "被修改文件必须有全文快照")
        assertEquals("v2 line1\nv2 line2\nv2 line3\nv2 line4\n", snapshot.readText(), "快照是工作树里的全文")
        val reportText = harvest.reportPath.readText()
        assertTrue(reportText.contains("snapshots") && reportText.contains("prototype.html"), "report 记录快照清单")
        // 新建文件不进快照（diff 的 + 行就是全文，extractFileFromPatch 能还原）——
        // 同任务里再写一个新文件，快照清单里不该出现它。
        val root2 = tempDir()
        val host2 = tempDir()
        gitRepo(host2)
        val cli2 = script(root2, "cli", """
            printf 'brand\n' > new-file.txt
            exit 0
        """.trimIndent())
        val outcome2 = exe.run(
            taskId = "t2c",
            hostRepo = host2.absolutePath,
            command = listOf(cli2),
            env = emptyMap(),
            onLine = {},
        )
        outcome2 as WorktreeSandboxExecutor.Outcome.Finished
        val harvest2 = requireNotNull(outcome2.harvest)
        assertFalse(File(harvest2.patchPath.parentFile, "files/new-file.txt").exists(), "新建文件不占快照（diff 可整份还原）")
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

    /**
     * 中止必须收掉**忽略 SIGTERM 的子进程**，不能只看根进程死没死。
     *
     * ## 这条守的是哪个现场
     *
     * 真机现场：手机发「停」，卡片翻成 `Aborted`，而同一沙箱里的 `codex exec`
     * 七分钟后仍在跑 —— 直到第二次 stop 才收掉。
     *
     * 根因在 `destroyProcessTree` 的判据：它把「根进程 `proc.isAlive == false`」当成
     * 「整棵树没了」。而 `ProcessHandle.descendants()` 有两个性质（见该方法 KDoc）：
     * 是**快照**、且**只对活着的祖先有效**。根一死，后代被过继给 init，句柄再也枚举不到。
     *
     * ## 这条用例为什么能区分新旧实现
     *
     * 假 CLI 起一个 `trap "" TERM` 的子进程（忽略 SIGTERM，只能 SIGKILL 收）：
     * - 旧实现：SIGTERM 打给根 → 根立刻死 → 宽限循环只盯根，立即退出 →
     *   `forceKill(proc)` 因根已死而跳过 → **子进程存活**，用例红；
     * - 新实现：按 pid 逐个核对，宽限 3s 后对幸存者 SIGKILL → 子进程死，用例绿。
     */
    @Test
    fun abortKillsDescendantsThatIgnoreSigterm() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val pidFile = File(root, "pids.txt")
        // `trap "" TERM; exec sleep` —— 忽略 TERM 之后 exec，忽略处置会被继承，
        // 于是这个 `sleep` 只会被 SIGKILL 收掉。（多一条命令就不会 exec 优化，
        // 会多一层 fork，也让 `$!` 指向的不是它。）
        val cli = script(
            root,
            "cli",
            "sh -c 'trap \"\" TERM; exec sleep 120' &\n" +
                "echo \"\$\$ \$!\" > '${pidFile.absolutePath}'\n" +
                "wait",
        )
        val exe = executor(root)
        val scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.Default)
        val done = CompletableDeferred<WorktreeSandboxExecutor.Outcome>()
        scope.launch { done.complete(exe.run("t6", host.absolutePath, listOf(cli), emptyMap(), {})) }

        withTimeout(15_000) {
            while (!pidFile.isFile || pidFile.readText().isBlank()) delay(50)
        }
        val (rootPid, childPid) = pidFile.readText().trim().split(" ").map { it.toLong() }
        assertTrue(aliveOf(rootPid), "前置：根进程在跑")
        assertTrue(aliveOf(childPid), "前置：忽略 SIGTERM 的子进程在跑")

        val killed = withTimeout(20_000) { exe.abortAll() }
        assertTrue(killed >= 1, "abortAll 必须命中这条任务，实际 $killed")

        withTimeout(20_000) {
            while (aliveOf(rootPid) || aliveOf(childPid)) delay(50)
        }
        assertFalse(aliveOf(childPid), "忽略 SIGTERM 的子进程也必须被 SIGKILL 收掉 —— 只收根就是真机那次「stop 之后还在跑」")
        assertFalse(aliveOf(rootPid), "根进程必须被杀掉")

        val outcome = withTimeout(10_000) { done.await() }
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Aborted, "终局是 Aborted，实际 $outcome")
        // 产物目录也要跟着消失：中止的活没有 patch 可交，留着目录只是让沙箱根目录
        // 越积越多（真机实测一次中止留一个，验收 §10d 第 5 条要求「目录消失」）。
        assertFalse(
            File(root, "artifacts/t6").exists(),
            "中止后产物目录必须清掉：${File(root, "artifacts/t6").absolutePath}",
        )
        scope.cancel()
    }

    /** pid 还活着吗（进程不存在 / 已退出都算 false，不抛）。 */
    private fun aliveOf(pid: Long): Boolean =
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

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

    // ── 7.5 滚动基线（D5）：合并只改工作树，下一轮沙箱必须看得见 ─────────────

    /**
     * D5 的回归用例：合并（`git apply`）不提交，HEAD 不动；如果沙箱仍自 HEAD 建，
     * 下一轮任务就看不见上一轮合进去的代码（A/B/C 三轮各自独立、基线不累计）。
     *
     * 这里模拟「合并后打快照」，再让沙箱任务去读那个未提交的新文件：
     * 读得到 = 站在上一轮肩膀上；harvest 只含本任务新增的行 = 补丁是相对快照的，
     * 不是把整份合并结果重新打一遍。
     */
    @Test
    fun nextTaskStartsFromTheMergedSnapshotNotFromHead() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        // 合并的产物：一个**未提交、未跟踪**的新文件（阶段 B 的 src/aqi.js 就是这个形状）。
        File(host, "merged.txt").writeText("from stage B\n")
        val snap = MergeBaseRef.snapshot(host)
        assertTrue(snap != null, "合并后必须能打出滚动基线快照")
        assertEquals(MergeBaseRef.REF, MergeBaseRef.baseFor(host), "HEAD 未动时应以快照为起点")

        val cli = script(
            root,
            "cli",
            """
            if [ -f merged.txt ]; then
              printf 'task line\n' >> merged.txt
              exit 0
            fi
            exit 7
            """.trimIndent(),
        )
        val outcome = executor(root).run(
            taskId = "t-d5",
            hostRepo = host.absolutePath,
            command = listOf(cli),
            env = emptyMap(),
            onLine = {},
        )
        assertTrue(
            outcome is WorktreeSandboxExecutor.Outcome.Finished,
            "任务应看得到合并基线上的文件；自 HEAD 建会 exit 7，实际 $outcome",
        )
        val harvest = requireNotNull((outcome as WorktreeSandboxExecutor.Outcome.Finished).harvest)
        assertEquals(1, harvest.filesChanged, "相对快照只有 merged.txt 一处改动")
        assertEquals(1, harvest.additions, "补丁只该含本任务追加的 1 行，而不是整份合并结果")
        // 隔离仍在：任务写的是它自己的 worktree，宿主工作树原样。
        assertEquals("from stage B\n", File(host, "merged.txt").readText())
    }

    /**
     * 收割的基线是**开工时解析好的 sha**，不是 `HEAD`。CLI 若在沙箱里自己 commit，
     * `HEAD` 就前移了，`git diff HEAD` 会把它的活读成「没有改动」—— 整轮任务白干。
     */
    @Test
    fun harvestSurvivesACliThatCommitsItsOwnWork() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(
            root,
            "cli",
            """
            printf 'committed by cli\n' >> seed.txt
            git -c user.email=t@t.invalid -c user.name=t commit -am "cli commit" >/dev/null 2>&1
            exit 0
            """.trimIndent(),
        )
        val outcome = executor(root).run(
            taskId = "t-cli-commit",
            hostRepo = host.absolutePath,
            command = listOf(cli),
            env = emptyMap(),
            onLine = {},
        )
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Finished, "实际 $outcome")
        val harvest = requireNotNull((outcome as WorktreeSandboxExecutor.Outcome.Finished).harvest) {
            "CLI 自己 commit 过也必须收得到补丁（git diff HEAD 会把它的活读成没有改动）"
        }
        assertEquals(1, harvest.filesChanged, "seed.txt 一处")
        assertEquals(1, harvest.additions, "只该有 CLI 追加的那 1 行")
    }

    /**
     * PWD 必须指到 worktree，而不是宿主目录。
     *
     * `ProcessBuilder.directory()` 只改真实 cwd，**不动继承来的 PWD**。opencode 解析
     * 工作区读的是 `$PWD`（不是 getcwd）—— 实测它把 proof.txt 写进了宿主仓库根，
     * worktree 里空空如也。
     *
     * 观察点用 `/usr/bin/env` 而不是 shell 脚本：POSIX shell 会拿 getcwd 校验并**重写**
     * PWD，用它来测等于自证（不修也能过）。`env` 打出来的是进程拿到的那份原始环境。
     */
    @Test
    fun theChildSeesPwdPointingAtTheWorktreeNotTheHost() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        // onLine 在读线程上被调用，普通 list 不可靠。
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val outcome = executor(root).run(
            taskId = "t-pwd",
            hostRepo = host.absolutePath,
            command = listOf("/usr/bin/env"),
            env = emptyMap(),
            onLine = { lines += it },
        )
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Finished, "/usr/bin/env 退出码 0：$outcome")
        val pwd = lines.firstOrNull { it.startsWith("PWD=") }
            ?: error("/usr/bin/env 的输出里没有 PWD，无法判定")
        // absolutePath 而不是 canonicalPath：worktree 是按 absolutePath 传下去的，
        // canonicalPath 在 macOS 上会把 /var → /private/var，两边对不上。
        val expected = File(root, "t-pwd").absolutePath
        assertEquals(
            "PWD=$expected",
            pwd,
            "子进程的 PWD 必须是 worktree —— 否则按 \$PWD 解析工作区的 CLI 会把产物写到宿主目录",
        )
    }

    // ── 8. 派单止损（挂住的 CLI 不许无限期占着 worktree）────────────────────

    /**
     * 一个**一个字都不打、也永不退出**的 CLI —— 正是真机现场的形状（codex 挂住时
     * 只打一行 `Reading additional input from stdin...` 就再无下文）。没有止损时它会
     * 一直占着 worktree 与分支，本轮真机两次靠人工 kill 才释放。
     */
    @Test
    fun aHungCliIsStoppedAtTheDeadlineAndTheWorktreeIsCleaned() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(root, "cli", "sleep 300")
        val exe = executor(root)
        // 外层 withTimeout 只是兜底（防测试本身挂死），真正的判据是下面的 TimedOut。
        lateinit var outcome: WorktreeSandboxExecutor.Outcome
        val elapsed = kotlin.time.measureTime {
            outcome = withTimeout(20_000) {
                exe.run(
                    taskId = "t-deadline",
                    hostRepo = host.absolutePath,
                    command = listOf(cli),
                    env = emptyMap(),
                    onLine = {},
                    timeoutMs = 800L,
                )
            }
        }
        assertTrue(
            outcome is WorktreeSandboxExecutor.Outcome.TimedOut,
            "挂住的派单必须以 TimedOut 收场（不是 Aborted，也不是永远等下去），实际 $outcome",
        )
        val elapsedMs = elapsed.inWholeMilliseconds
        assertTrue(elapsedMs < 15_000, "止损要真的摘掉进程树，而不是「不再等」：实际 $elapsedMs ms")
        assertEquals(800L, outcome.timeoutMs)
        assertFalse(File(root, "t-deadline").exists(), "超时也要无痕清理 worktree")
        assertEquals(0, gitLines(host, "branch", "--list", "task-t-deadline").size, "分支也要清掉")
        val log = File(File(root, "artifacts/t-deadline"), "task.log")
        assertTrue(log.exists(), "超时是结局，产物目录要留证据")
        assertTrue(
            log.readText().contains("timeout"),
            "task.log 里要留下超时这条真因（外部取证只有产物目录）：${log.readText()}",
        )
    }

    /** 守反向：正常的快任务不许被止损误杀。 */
    @Test
    fun aFastRunInsideTheDeadlineIsNotKilled() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(root, "cli", "echo done >> seed.txt; exit 0")
        val outcome = executor(root).run(
            taskId = "t-inside-deadline",
            hostRepo = host.absolutePath,
            command = listOf(cli),
            env = emptyMap(),
            onLine = {},
            timeoutMs = 60_000L,
        )
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Finished, "60s 里跑完的不许被判超时：$outcome")
    }

    /** env 解析：认不出的值回落默认 —— 一个笔误不许变成「立刻杀掉」。 */
    @Test
    fun timeoutEnvFallsBackToTheDefaultOnJunkInsteadOfKillingImmediately() {
        val default = WorktreeSandboxExecutor.DEFAULT_TIMEOUT_MS
        assertEquals(default, WorktreeSandboxExecutor.timeoutMsOf(emptyMap()))
        assertEquals(60_000L, WorktreeSandboxExecutor.timeoutMsOf(mapOf("TENON_SANDBOX_TIMEOUT_MS" to "60000")))
        for (junk in listOf("0", "-1", "", "  ", "soon", "1e9")) {
            assertEquals(
                default,
                WorktreeSandboxExecutor.timeoutMsOf(mapOf("TENON_SANDBOX_TIMEOUT_MS" to junk)),
                "TENON_SANDBOX_TIMEOUT_MS=<$junk> 必须回落默认，不能变成立刻杀",
            )
        }
    }

    @Test
    fun prepareSandboxHomeCreatesIsolatedDirectory() {
        val root = tempDir()
        val artifacts = File(root, "artifacts-t-1").apply { mkdirs() }
        val sandboxHome = WorktreeSandboxExecutor.prepareSandboxHome(artifacts)
        assertTrue(sandboxHome.exists() && sandboxHome.isDirectory, "sandbox home must exist")
        assertEquals(File(artifacts, ".sandbox-home").absolutePath, sandboxHome.absolutePath)
        // Ensure sensitive directories like .workbuddy or .ssh are NOT exposed
        assertFalse(File(sandboxHome, ".workbuddy").exists(), ".workbuddy must NOT be exposed")
        assertFalse(File(sandboxHome, ".ssh").exists(), ".ssh must NOT be exposed")
    }

    /**
     * 凭据挂载白名单 = 各家 CLI 驱动的登录态所在目录，缺一个那个驱动就在沙箱里
     * 必死于认证（2026-11-06 真机验收：清单漏 `.agy` ⇒ antigravity 驱动全部
     * `authentication failed or timed out`，同机真 HOME 直跑通过 —— 红证
     * t-1-64fef602/task.log）。本条把「每家内置驱动都有挂载」钉成清单内容，
     * 同时钉住反向纪律：敏感目录不许进清单。
     */
    @Test
    fun sandboxCredentialEntriesCoverEveryBuiltinCliAndNoSensitiveDir() {
        val entries = WorktreeSandboxExecutor.SANDBOX_CREDENTIAL_ENTRIES
        // 内置五驱动（BuiltinPluginSeeder）：claude / codex / agy / dsh / opencode。
        // claude 的登录态在 ~/.claude（+ ~/.claude.json），codex 在 ~/.codex，
        // agy 在 ~/.agy，dsh 的 profiles 在 ~/.dsh；opencode 走 provider env（OPENCODE_API_KEY），
        // 不依赖 $HOME 凭据目录 —— 这一条要跟 BuiltinPluginSeeder 的种子清单对齐。
        for (required in listOf(".codex", ".claude", ".claude.json", ".agy", ".dsh")) {
            assertTrue(
                entries.contains(required),
                "凭据挂载清单缺 $required —— 该驱动在沙箱里会必死于认证（缺输入=失败不可见）",
            )
        }
        // 反向纪律：白名单不许扩进宿主敏感目录。
        for (forbidden in listOf(".ssh", ".gnupg", ".aws", ".workbuddy", ".workbuddy-ai")) {
            assertFalse(
                entries.contains(forbidden),
                "凭据挂载清单不许包含敏感目录 $forbidden —— 「能跑通」不能靠暴露宿主私有凭据来换",
            )
        }
    }

    @Test
    fun prepareSandboxHomeMountsHostOpencodeConfigIfPresent() {
        val fakeHost = tempDir()
        val fakeOpencodeDir = File(fakeHost, ".config/opencode").apply { mkdirs() }
        File(fakeOpencodeDir, "opencode.json").writeText("""{"model":"custom-test"}""")

        val artifacts = File(tempDir(), "artifacts").apply { mkdirs() }
        val sandboxHome = WorktreeSandboxExecutor.prepareSandboxHome(artifacts, hostHome = fakeHost)

        val mounted = File(sandboxHome, ".config/opencode")
        assertTrue(mounted.exists(), "宿主若有 .config/opencode，沙箱必须建立对应的配置目录挂载")
        assertTrue(java.nio.file.Files.isSymbolicLink(mounted.toPath()), "挂载必须是符号链接")
        assertEquals(
            """{"model":"custom-test"}""",
            File(mounted, "opencode.json").readText(),
            "沙箱内读取的配置内容必须与宿主完全一致",
        )
    }

    @Test
    fun deleteArtifactsSafelyDoesNotFollowSymlinksIntoHostHome() {
        val fakeHost = tempDir()
        val fakeDsh = File(fakeHost, ".dsh").apply { mkdirs() }
        val dshSecret = File(fakeDsh, ".credentials.yaml").apply { writeText("secret_token: live_key") }
        val fakeOpencode = File(fakeHost, ".config/opencode").apply { mkdirs() }
        val opencodeConf = File(fakeOpencode, "opencode.json").apply { writeText("""{"model":"m"}""") }

        val artifacts = File(tempDir(), "artifacts").apply { mkdirs() }
        WorktreeSandboxExecutor.prepareSandboxHome(artifacts, hostHome = fakeHost)

        // 验证挂载存在
        val sandboxHome = File(artifacts, ".sandbox-home")
        assertTrue(File(sandboxHome, ".dsh").exists())
        assertTrue(File(sandboxHome, ".config/opencode").exists())

        // 执行安全清理
        WorktreeSandboxExecutor.deleteArtifactsSafely(artifacts)

        // 断言产物目录被清理完毕
        assertFalse(artifacts.exists(), "产物目录必须被清理完毕")

        // 致命断言（AGENTS §2.8）：宿主物理目录及文件必须完好无损，绝不能被下钻删除
        assertTrue(fakeHost.exists(), "宿主目录必须存在")
        assertTrue(fakeDsh.exists(), "宿主 .dsh 必须存在")
        assertTrue(dshSecret.exists(), "宿主凭证文件必须存在且完好")
        assertEquals("secret_token: live_key", dshSecret.readText())
        assertTrue(fakeOpencode.exists(), "宿主 opencode 配置目录必须存在")
        assertTrue(opencodeConf.exists(), "宿主 opencode.json 必须存在且完好")
        assertEquals("""{"model":"m"}""", opencodeConf.readText())
    }

    private fun assertFalse(actual: Boolean, message: String) {
        kotlin.test.assertFalse(actual, message)
    }
}
