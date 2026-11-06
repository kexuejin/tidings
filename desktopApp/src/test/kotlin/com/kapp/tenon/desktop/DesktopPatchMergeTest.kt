// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.DesktopPatchMerge

import com.kapp.tenon.agent.drivers.MergeBaseRef
import com.kapp.tenon.agent.drivers.WorktreeSandboxExecutor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 审查合流（T-G1）执行件的全组用例：真 git + 真补丁（git 是构建链硬前置）。
 *
 * 钉四件事：
 * 1. 干净应用：上下文对得上 ⇒ Applied，patch 内容真实落进宿主工作树；
 * 2. 冲突 fail-closed：上下文对不上 ⇒ Conflict，**宿主工作树零变化**（--check 干跑段拦截）；
 * 3. 大声失败：patch 文件 / 工作区不存在 ⇒ Conflict 且信息指向真原因；
 * 4. 滚动基线（D5）：合并成功后打出快照提交，下一轮沙箱以它为起点 —— 且快照
 *    不动机主 HEAD、不动机主真实索引。
 */
class DesktopPatchMergeTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun git(cwd: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val err = proc.errorStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "git ${args.joinToString(" ")} 失败: $err")
    }

    private fun gitOut(cwd: File, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out.trim()
    }

    private fun makeRepo(dir: File) {
        dir.mkdirs()
        git(dir, "init")
        git(dir, "config", "user.email", "t@t.invalid")
        git(dir, "config", "user.name", "t")
        File(dir, "app.txt").writeText("line1\nline2\nline3\n")
        git(dir, "add", ".")
        git(dir, "commit", "-m", "init")
    }

    /** 在「沙箱视角」的克隆里制造改动，diff 出一份真补丁。 */
    private fun makePatch(hostRepo: File, target: File): String {
        git(target, "clone", "--quiet", hostRepo.absolutePath, target.resolve("clone").absolutePath)
        val clone = target.resolve("clone")
        git(clone, "config", "user.email", "t@t.invalid")
        git(clone, "config", "user.name", "t")
        File(clone, "app.txt").writeText("line1\nCHANGED\nline3\n")
        git(clone, "commit", "-am", "sandbox change")
        val proc = ProcessBuilder(listOf("git", "format-patch", "HEAD~1", "--stdout"))
            .directory(clone).start()
        val patch = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val patchFile = File(target, "patch.diff").apply { writeText(patch) }
        return patchFile.absolutePath
    }

    /** 造一份「新增未跟踪文件」的补丁 —— 阶段 B 的 `src/aqi.js` 就是这种形状。 */
    private fun makeNewFilePatch(
        hostRepo: File,
        target: File,
        relPath: String,
        content: String,
    ): String {
        git(target, "clone", "--quiet", hostRepo.absolutePath, target.resolve("clone").absolutePath)
        val clone = target.resolve("clone")
        git(clone, "config", "user.email", "t@t.invalid")
        git(clone, "config", "user.name", "t")
        File(clone, relPath).apply { parentFile.mkdirs(); writeText(content) }
        git(clone, "add", relPath)
        git(clone, "commit", "-m", "add $relPath")
        val proc = ProcessBuilder(listOf("git", "format-patch", "HEAD~1", "--stdout"))
            .directory(clone).start()
        val patch = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val patchFile = File(target, "patch-new-file.diff").apply { writeText(patch) }
        return patchFile.absolutePath
    }

    @Test
    fun cleanPatchAppliesIntoTheWorkspace() {
        val host = tempDir("merge-host")
        val scratch = tempDir("merge-scratch")
        makeRepo(host)
        val patchPath = makePatch(host, scratch)
        val outcome = DesktopPatchMerge.apply(patchPath = patchPath, workspacePath = host.absolutePath)
        assertTrue(outcome is DesktopPatchMerge.Outcome.Applied, "干净补丁应 Applied，实际 $outcome")
        assertEquals("line1\nCHANGED\nline3\n", File(host, "app.txt").readText(), "patch 内容真实落进宿主工作树")
        // D5：合并后必须打出滚动基线，内容 = 合并后的工作树。
        val snap = (outcome as DesktopPatchMerge.Outcome.Applied).baseSnapshot
        assertNotNull(snap, "合并成功后应打出滚动基线快照")
        assertEquals(
            "line1\nCHANGED\nline3",
            gitOut(host, "show", "$snap:app.txt"),
            "快照内容必须是合并后的工作树",
        )
        assertEquals(MergeBaseRef.REF, MergeBaseRef.baseFor(host), "HEAD 未动时下一轮沙箱应以快照为起点")
    }

    @Test
    fun mergedNewFileLandsInTheRollingBase() {
        val host = tempDir("merge-new-file")
        val scratch = tempDir("merge-new-file-scratch")
        makeRepo(host)
        val patchPath = makeNewFilePatch(host, scratch, "src/aqi.js", "export const aqi = 1\n")
        val outcome = DesktopPatchMerge.apply(patchPath = patchPath, workspacePath = host.absolutePath)
        assertTrue(outcome is DesktopPatchMerge.Outcome.Applied, "新增文件补丁应 Applied，实际 $outcome")
        assertEquals("export const aqi = 1\n", File(host, "src/aqi.js").readText())
        val snap = (outcome as DesktopPatchMerge.Outcome.Applied).baseSnapshot
        assertNotNull(snap, "新增未跟踪文件也必须能进快照")
        assertEquals(
            "export const aqi = 1",
            gitOut(host, "show", "$snap:src/aqi.js"),
            "未跟踪的新文件不进快照，下一轮沙箱就会看不见它",
        )
    }

    @Test
    fun snapshotLeavesHeadAndTheRealIndexAlone() {
        val host = tempDir("merge-snapshot-index")
        makeRepo(host)
        File(host, "wip.txt").writeText("human wip\n")
        val statusBefore = gitOut(host, "status", "--porcelain")
        val headBefore = gitOut(host, "rev-parse", "HEAD")
        val snap = MergeBaseRef.snapshot(host)
        assertNotNull(snap, "快照必须能打出来")
        assertEquals(headBefore, gitOut(host, "rev-parse", "HEAD"), "快照不许动 HEAD")
        assertEquals(statusBefore, gitOut(host, "status", "--porcelain"), "快照不许动机主的真实索引/工作树")
    }

    @Test
    fun baseFallsBackToHeadOnceTheHumanCommits() {
        val host = tempDir("merge-base-fallback")
        makeRepo(host)
        File(host, "merged.txt").writeText("merged\n")
        assertNotNull(MergeBaseRef.snapshot(host), "快照必须能打出来")
        assertEquals(MergeBaseRef.REF, MergeBaseRef.baseFor(host), "HEAD 未动时用快照")
        // 机主把合并结果提交了：HEAD 前移，快照的父提交对不上 ⇒ 必须回落 HEAD，
        // 否则下一轮沙箱会从一份旧树起步，看不见机主的新提交。
        git(host, "add", ".")
        git(host, "commit", "-m", "human commits the merge")
        assertEquals("HEAD", MergeBaseRef.baseFor(host), "HEAD 动过之后快照过时，必须回落到 HEAD")
    }

    /**
     * D5 的端到端证明：合并 A → 下一轮沙箱看得见 A → 合并 B → 宿主仓同时有 A 和 B。
     *
     * 这条链正是本轮验收失败的形状：每一轮都从 HEAD 起，上一轮的合并对下一轮不可见，
     * `npm test` 的基线一直是 71 而不是累计值。
     */
    @Test
    fun mergedWorkBecomesTheBaseForTheNextTask() = runBlocking {
        val host = tempDir("merge-chain-host")
        val scratch = tempDir("merge-chain-scratch")
        val sandboxRoot = tempDir("merge-chain-sandbox")
        makeRepo(host)

        // 第一轮：合并「新增 src/aqi.js」（未跟踪新文件，阶段 B 的形状）。
        val patchA = makeNewFilePatch(host, scratch, "src/aqi.js", "export const aqi = 1\n")
        val mergeA = DesktopPatchMerge.apply(patchA, host.absolutePath)
        assertTrue(mergeA is DesktopPatchMerge.Outcome.Applied, "第一轮合并应成功，实际 $mergeA")
        assertEquals(MergeBaseRef.REF, MergeBaseRef.baseFor(host), "第一轮合并后应以快照为起点")

        // 第二轮：沙箱任务必须看得见 src/aqi.js，并在 app.txt 上追加一行。
        val cli = File(sandboxRoot, "cli").apply {
            writeText(
                """
                #!/bin/sh
                if [ ! -f src/aqi.js ]; then
                  echo "missing aqi from previous merge"
                  exit 9
                fi
                printf 'stage-c\n' >> app.txt
                exit 0
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val outcome = WorktreeSandboxExecutor(sandboxRoot = sandboxRoot).run(
            taskId = "t-chain-2",
            hostRepo = host.absolutePath,
            command = listOf(cli.absolutePath),
            onLine = {},
        )
        assertTrue(
            outcome is WorktreeSandboxExecutor.Outcome.Finished,
            "第二轮任务应看得见第一轮的合并；自 HEAD 建会 exit 9，实际 $outcome",
        )
        val harvest = requireNotNull((outcome as WorktreeSandboxExecutor.Outcome.Finished).harvest)
        assertEquals(1, harvest.filesChanged, "第二轮补丁只该有 app.txt 一处")
        assertEquals(1, harvest.additions, "第二轮补丁只该有本轮的 1 行 —— 不是把 AQI 重新打一遍")

        // 合并第二轮：宿主仓必须同时有 A 和 B。
        val mergeB = DesktopPatchMerge.apply(harvest.patchPath.absolutePath, host.absolutePath)
        assertTrue(mergeB is DesktopPatchMerge.Outcome.Applied, "第二轮合并应成功，实际 $mergeB")
        assertEquals("export const aqi = 1\n", File(host, "src/aqi.js").readText(), "第一轮的合并结果必须还在")
        assertEquals("line1\nline2\nline3\nstage-c\n", File(host, "app.txt").readText(), "第二轮的改动必须落进同一份工作树")
        val snap = (mergeB as DesktopPatchMerge.Outcome.Applied).baseSnapshot
        assertNotNull(snap, "第二轮合并后必须打出新的滚动基线")
        assertEquals(
            "stage-c",
            gitOut(host, "show", "$snap:app.txt").lines().last(),
            "第二轮快照必须包含两轮的全部改动",
        )
    }

    @Test
    fun conflictingPatchLeavesTheWorkspaceUntouched() {
        val host = tempDir("merge-conflict")
        val scratch = tempDir("merge-conflict-scratch")
        makeRepo(host)
        val patchPath = makePatch(host, scratch)
        // 合流前宿主工作树先变了（同一行被机主改过）—— 补丁上下文对不上。
        File(host, "app.txt").writeText("line1\nHUMAN EDIT\nline3\n")
        val outcome = DesktopPatchMerge.apply(patchPath = patchPath, workspacePath = host.absolutePath)
        assertTrue(outcome is DesktopPatchMerge.Outcome.Conflict, "上下文冲突应 Conflict，实际 $outcome")
        assertEquals("line1\nHUMAN EDIT\nline3\n", File(host, "app.txt").readText(), "冲突路径宿主工作树零变化（fail-closed）")
        // 冲突不许推进滚动基线：失败路径上 HEAD 与 ref 都该原样。
        assertEquals("", gitOut(host, "rev-parse", "--verify", "--quiet", MergeBaseRef.REF), "冲突不应产生快照")
    }

    @Test
    fun missingInputsFailLoudly() {
        val outcome = DesktopPatchMerge.apply(patchPath = "/nonexistent/patch.diff", workspacePath = "/tmp")
        assertTrue(outcome is DesktopPatchMerge.Outcome.Conflict, "patch 缺失 = Conflict")
        assertTrue((outcome as DesktopPatchMerge.Outcome.Conflict).detail.contains("missing"))
        val outcome2 = DesktopPatchMerge.apply(patchPath = "/tmp", workspacePath = "/nonexistent/ws")
        assertTrue(outcome2 is DesktopPatchMerge.Outcome.Conflict, "工作区缺失 = Conflict")
    }

    // ---- applyBranch：多棒链的产物形态（chain/<id> 分支，真机 2026-11-03 补的洞）----

    /** 造一条链分支：分叉后两笔提交（新增文件 + 改既有文件），宿主仓停在原 HEAD。 */
    private fun makeChainBranch(host: File, branch: String) {
        git(host, "checkout", "-b", branch)
        File(host, "note.md").apply { parentFile.mkdirs(); writeText("hi\n") }
        git(host, "add", "note.md")
        git(host, "commit", "-m", "chain 1/2")
        File(host, "app.txt").writeText("line1\nCHAIN EDIT\nline3\n")
        git(host, "commit", "-am", "chain 2/2")
        git(host, "checkout", "-")
    }

    @Test
    fun branchMergeLandsEveryCommitAndLeavesHeadAlone() {
        val host = tempDir("merge-branch")
        makeRepo(host)
        makeChainBranch(host, "chain/test1")

        val outcome = DesktopPatchMerge.applyBranch(branch = "chain/test1", workspacePath = host.absolutePath)

        assertTrue(outcome is DesktopPatchMerge.Outcome.Applied, "链分支应 Applied，实际 $outcome")
        assertEquals("hi\n", File(host, "note.md").readText(), "链分支新增的文件要落进工作树")
        assertEquals("line1\nCHAIN EDIT\nline3\n", File(host, "app.txt").readText(), "链分支的既有文件改动也要落")
        // 与 patch 合并同一条纪律：只改工作树，不 commit —— 改动是未提交的工作树改动，
        // 新文件以未跟踪形态存在（?? note.md），HEAD 不许动（提交是机主的下一步）。
        // （gitOut 会 trim 头部空白，porcelain 的 " M" 前导空格保不住，只看文件名与形态。）
        val status = gitOut(host, "status", "--porcelain")
        assertTrue(status.contains("app.txt"), "既有文件的改动应出现在工作树状态里：$status")
        assertTrue(status.contains("?? note.md"), "链新增的文件应是未跟踪文件：$status")
        assertEquals("main", gitOut(host, "rev-parse", "--abbrev-ref", "HEAD"), "HEAD 不许被合并切走")
        // D5：滚动基线照打。
        assertTrue((outcome as DesktopPatchMerge.Outcome.Applied).baseSnapshot != null, "合并后要打滚动基线快照")
    }

    @Test
    fun branchMergeConflictsLeaveTheWorkspaceUntouched() {
        val host = tempDir("merge-branch-conflict")
        makeRepo(host)
        makeChainBranch(host, "chain/test2")
        // 机主在链跑期间改了同一行 —— 导出的补丁上下文对不上。
        File(host, "app.txt").writeText("line1\nHUMAN EDIT\nline3\n")

        val outcome = DesktopPatchMerge.applyBranch(branch = "chain/test2", workspacePath = host.absolutePath)

        assertTrue(outcome is DesktopPatchMerge.Outcome.Conflict, "上下文冲突应 Conflict，实际 $outcome")
        assertEquals("line1\nHUMAN EDIT\nline3\n", File(host, "app.txt").readText(), "冲突路径宿主仓零变化")
        assertTrue(!File(host, "note.md").exists(), "冲突路径不许把链的新文件落进来")
    }

    @Test
    fun branchMergeFailsLoudlyOnUnknownBranch() {
        val host = tempDir("merge-branch-missing")
        makeRepo(host)
        val outcome = DesktopPatchMerge.applyBranch(branch = "chain/nope", workspacePath = host.absolutePath)
        assertTrue(outcome is DesktopPatchMerge.Outcome.Conflict, "分支缺失 = Conflict")
        assertTrue((outcome as DesktopPatchMerge.Outcome.Conflict).detail.contains("branch missing"))
    }
}
