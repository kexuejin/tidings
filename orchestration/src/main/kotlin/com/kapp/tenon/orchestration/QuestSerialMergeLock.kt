// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 支线任务主分支串行合流锁 (QuestSerialMergeLock)。
 *
 * 核心原则与硬约束：
 * 1. 独立 Worktree 预合流与门禁自检：
 *    Rebase 与门禁测试全部在支线自身的独立 Worktree 内执行（git rebase main + 跑测试），
 *    绝不提前触碰主工作区。
 * 2. 严禁破坏主工作区现场：
 *    合流前严格断言主工作区当前分支必须是 [targetBranch] 且 `git status --porcelain` 绝对干净。
 *    若存在任何未提交修改，直接阻断并报警，严禁静默 `git stash`。
 * 3. 产生真快进提交 (Fast-Forward Commit)：
 *    规格同源执行 `git merge --ff-only`，确保主分支 HEAD 真实向前推进，杜绝只改工作区不产生 commit 却谎报 COMPLETED。
 * 4. 子进程防管道死锁与超时防御：
 *    异步并发读取 stdout/stderr 流，并设置 30 秒硬超时，杜绝管道缓冲区溢出或进程卡死挂起协程。
 */
class QuestSerialMergeLock(
    private val mainWorkspace: File,
    private val runGateChecks: suspend (worktreeDir: File) -> Boolean = { true },
    /**
     * 单条 git 命令的硬超时（毫秒）。默认 30s（生产口径）；测试注入小值，
     * 让「hook 派生孙进程握住管道」的超时路径可以在秒级复现 —— 超时本身就是
     * 「观测预算不是断言」的那类参数，注入是为了让缺陷可以**确定性地**走到。
     */
    private val gitTimeoutMs: Long = 30_000L,
) {
    private val mergeMutex = Mutex()

    sealed interface MergeResult {
        data class Success(val mergedCommit: String) : MergeResult
        data class PreMergeFailed(val reason: String) : MergeResult
        data class HostWorkspaceDirty(val dirtyFiles: List<String>) : MergeResult
        data class Conflict(val reason: String) : MergeResult
    }

    /**
     * 将支线 Worktree ([questWorktreeDir]) 上的提交快进合入主分支 ([targetBranch])。
     */
    suspend fun rebaseAndMerge(
        questId: String,
        questWorktreeDir: File,
        targetBranch: String = "main",
    ): MergeResult = mergeMutex.withLock {
        if (!questWorktreeDir.isDirectory) {
            return MergeResult.PreMergeFailed("Quest worktree directory missing: " + questWorktreeDir.absolutePath)
        }
        if (!mainWorkspace.isDirectory) {
            return MergeResult.PreMergeFailed("Main workspace directory missing: " + mainWorkspace.absolutePath)
        }

        // 1. 检查主工作区当前分支是否等于目标分支
        val currentBranchRes = git(mainWorkspace, "rev-parse", "--abbrev-ref", "HEAD")
        if (currentBranchRes.exitCode != 0) {
            return MergeResult.PreMergeFailed("Failed to inspect main workspace branch: " + currentBranchRes.stderr)
        }
        val currentBranch = currentBranchRes.stdout.trim()
        if (currentBranch != targetBranch) {
            return MergeResult.PreMergeFailed("Main workspace is on branch '$currentBranch', expected '$targetBranch'")
        }

        // 2. 检查主工作区是否绝对干净 (Porcelain 检测)
        val statusRes = git(mainWorkspace, "status", "--porcelain")
        if (statusRes.exitCode != 0) {
            return MergeResult.PreMergeFailed("Failed to inspect main workspace status: " + statusRes.stderr)
        }
        val dirtyLines = statusRes.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (dirtyLines.isNotEmpty()) {
            return MergeResult.HostWorkspaceDirty(dirtyLines)
        }

        // 3. 在支线 Worktree 内执行 git rebase
        val rebaseRes = git(questWorktreeDir, "rebase", targetBranch)
        if (rebaseRes.exitCode != 0) {
            // abort 失败 = worktree 卡在 mid-rebase 现场必须说出来，否则机主看到的是
            // 原始 rebase 报错，而真正阻塞下一次合流的是没退干净的 rebase 状态。
            val abortRes = git(questWorktreeDir, "rebase", "--abort")
            val abortNote = if (abortRes.exitCode != 0) {
                " (rebase --abort also failed: " + abortRes.stderr.takeLast(200).trim() + " — worktree may be stuck mid-rebase)"
            } else {
                ""
            }
            return MergeResult.Conflict("Rebase failed in quest worktree: " + rebaseRes.stderr.takeLast(300).trim() + abortNote)
        }

        // 4. 在支线 Worktree 内跑门禁自检
        val gatePassed = runGateChecks(questWorktreeDir)
        if (!gatePassed) {
            return MergeResult.PreMergeFailed("Gate checks failed in quest worktree for quest: " + questId)
        }

        // 5. 获取支线最新 HEAD commit SHA
        val questHeadRes = git(questWorktreeDir, "rev-parse", "HEAD")
        if (questHeadRes.exitCode != 0) {
            return MergeResult.PreMergeFailed("Failed to read quest HEAD commit: " + questHeadRes.stderr)
        }
        val questCommitSha = questHeadRes.stdout.trim()

        // 6. 在主工作区执行真快进合并 (git merge --ff-only)
        val mergeRes = git(mainWorkspace, "merge", "--ff-only", questCommitSha)
        if (mergeRes.exitCode != 0) {
            return MergeResult.Conflict("Fast-forward merge failed in main workspace: " + mergeRes.stderr.takeLast(300).trim())
        }

        // 7. 再次断言主工作区 HEAD 确实已指向该 commit
        val mainHeadRes = git(mainWorkspace, "rev-parse", "HEAD")
        if (mainHeadRes.exitCode != 0) {
            return MergeResult.PreMergeFailed("Failed to verify main workspace HEAD after merge: " + mainHeadRes.stderr)
        }
        val mainHeadCommit = mainHeadRes.stdout.trim()
        if (mainHeadCommit != questCommitSha) {
            return MergeResult.PreMergeFailed("Main workspace HEAD ($mainHeadCommit) does not match merged commit ($questCommitSha)")
        }

        return MergeResult.Success(questCommitSha)
    }

    private suspend fun git(cwd: File, vararg args: String): QuestGitCli.GitOutput =
        // 收编到 [QuestGitCli]（共享执行器：超时取消 reader、关流、环境加固、进程回收
        // 都只有一份实现 —— 本类此前的私有版就是它的来源）。
        QuestGitCli.run(cwd, timeoutMs = gitTimeoutMs, args = args)
}

