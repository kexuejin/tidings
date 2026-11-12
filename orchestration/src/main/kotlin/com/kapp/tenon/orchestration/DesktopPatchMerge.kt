// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.MergeBaseRef
import com.kapp.tenon.domain.RelayUiTexts
import java.io.File

/**
 * 审查合流（T-G1）的宿主侧执行件：把沙箱 patch.diff 应用回宿主仓。
 *
 * ## 为什么是 `git apply` 而不是别的
 *
 * 沙箱 worktree 已按 D-024 无痕清理，patch 是改动的**唯一**存世形态 ——
 * `git apply` 直接消费它，不要求宿主仓处于任何特殊状态。
 *
 * ## 两段式（--check 先行）
 *
 * 先 `git apply --check` 干跑：冲突/上下文对不上在干跑段就被拒，**不会**把宿主仓
 * 写成半途而废的样子（apply 失败时 git 自身也不留部分写入，但 --check 让「能不
 * 能合」成为先答的事实）。返回值两态：Ok（已写入）或 Conflict（带 git 的 stderr
 * 尾巴）—— 调用方据此把结果如实告知用户，绝不把冲突包装成成功。
 *
 * ## 合并后打滚动基线（D5）
 *
 * `git apply` 只改工作树，**不 add / 不 commit**（提交是机主的下一步）。若不额外
 * 记录，下一张工单的沙箱自 HEAD 建起，就看不到这一轮合进去的代码。所以 apply
 * 成功后立刻把当前工作树打成一份快照提交（[MergeBaseRef]），后续沙箱以它为起点；
 * 全程不动 HEAD、不动真实索引。快照打不出来时合并**仍然算成功**，只是下一轮
 * 回落到 HEAD —— 调用方会把这件事记进日志。
 */
object DesktopPatchMerge {

    sealed interface Outcome {
        /**
         * 已应用到宿主工作树（未提交 —— 提交是机主的下一步，不是沙箱代办）。
         *
         * [baseSnapshot] 是合并后工作树的快照提交 sha（D5 滚动基线），后续沙箱以它
         * 为起点；为 null = 快照没打出来（不是 git 仓 / 无首个提交 / git 缺身份），
         * 下一轮回落到 HEAD。
         */
        data class Applied(val baseSnapshot: String?) : Outcome

        /** 干跑就失败（上下文对不上 / 补丁已应用过）：宿主仓零变化。 */
        data class Conflict(val detail: String) : Outcome
    }

    sealed interface PrOutcome {
        data class Created(val prUrl: String, val branch: String) : PrOutcome
        data class Failed(val detail: String) : PrOutcome
    }

    fun apply(patchPath: String, workspacePath: String): Outcome {
        val patch = File(patchPath)
        if (!patch.isFile) return Outcome.Conflict("patch file missing: $patchPath")
        val workspace = File(workspacePath)
        if (!workspace.isDirectory) return Outcome.Conflict("workspace missing: $workspacePath")
        // --check 干跑：git 以退出码回答「能不能合」。
        val check = git(workspace, "apply", "--check", patch.absolutePath)
        if (check.exitCode != 0) {
            return Outcome.Conflict(check.stderr.takeLast(400).trim().ifEmpty { "git apply --check failed" })
        }
        val apply = git(workspace, "apply", patch.absolutePath)
        if (apply.exitCode != 0) {
            // 干跑过了正式 apply 还失败 = 竞态（检查与写入之间仓变了）：如实报冲突，
            // git apply 的原子性保证宿主仓不会停在半途。
            return Outcome.Conflict(apply.stderr.takeLast(400).trim().ifEmpty { "git apply failed" })
        }
        return Outcome.Applied(MergeBaseRef.snapshot(workspace))
    }

    /**
     * 合入一条**分支**（多棒链的产物形态）。
     *
     * ## 为什么链卡走这里而不是 [apply]
     *
     * 链（[DesktopStageChain]）的产物**天生不是 patch.diff**：每一棒的改动被
     * `landStage` 提交在 `chain/<id>` 分支上（worktree 收尾即删，分支是唯一存世形态）。
     * 沙箱登记簿里没有它的 patch —— 点链卡的「合并此补丁」，
     * 宿主回 `no sandbox patch recorded for card chain-…`，合并永远点不动。
     *
     * ## 语义与 [apply] 严格同构
     *
     * 把「分支相对分叉点的全部改动」导成 patch，再走同一条
     * `--check 干跑 → apply → 打滚动基线` 的路：只改工作树、**不 add / 不 commit**
     * （提交是机主的下一步），冲突时宿主仓零变化。分叉点用
     * `merge-base HEAD <branch>` —— 机主在链跑期间自己提交过也不怕。
     */
    fun applyBranch(branch: String, workspacePath: String): Outcome {
        val workspace = File(workspacePath)
        if (!workspace.isDirectory) return Outcome.Conflict("workspace missing: $workspacePath")
        if (git(workspace, "rev-parse", "--verify", "--quiet", "refs/heads/$branch").exitCode != 0) {
            return Outcome.Conflict("branch missing: $branch")
        }
        val base = git(workspace, "merge-base", "HEAD", branch)
        if (base.exitCode != 0) {
            return Outcome.Conflict("no common ancestor with $branch: " + base.stderr.takeLast(200).trim())
        }
        val diff = git(workspace, "diff", "--binary", base.stdout.trim() + ".." + branch)
        if (diff.exitCode != 0) {
            return Outcome.Conflict("git diff failed: " + diff.stderr.takeLast(300).trim())
        }
        if (diff.stdout.isBlank()) {
            // 分支与基线零差异（全被机主后来的提交包含了）：如实说，不假装合了东西。
            return Outcome.Conflict("branch $branch has no changes relative to the workspace")
        }
        val patch = File.createTempFile("tenon-chain-", ".diff")
        return try {
            patch.writeText(diff.stdout)
            apply(patch.absolutePath, workspacePath)
        } finally {
            runCatching { patch.delete() }
        }
    }

    /**
     * 将分支推送至远端并创建 PR/MR。
     */
    fun pushBranchAndCreatePr(
        branch: String,
        workspacePath: String,
        title: String,
        body: String = "Created by Tenon Agent.",
    ): PrOutcome {
        val workspace = File(workspacePath)
        if (!workspace.isDirectory) return PrOutcome.Failed("workspace missing: $workspacePath")
        if (git(workspace, "rev-parse", "--verify", "--quiet", "refs/heads/$branch").exitCode != 0) {
            return PrOutcome.Failed("branch missing: $branch")
        }
        val remotes = git(workspace, "remote").stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val remote = remotes.firstOrNull { it == "origin" } ?: remotes.firstOrNull()
            ?: return PrOutcome.Failed("no git remote configured in workspace")

        val push = git(workspace, "push", "-u", remote, branch)
        if (push.exitCode != 0) {
            return PrOutcome.Failed("git push failed: " + push.stderr.takeLast(300).trim())
        }

        val ghProc = runCatching {
            ProcessBuilder("gh", "pr", "create", "--head", branch, "--title", title, "--body", body)
                .directory(workspace)
                .start()
        }.getOrNull()
        if (ghProc != null) {
            val ghOut = ghProc.inputStream.bufferedReader().readText().trim()
            val ghCode = ghProc.waitFor()
            if (ghCode == 0 && ghOut.startsWith("http")) {
                return PrOutcome.Created(prUrl = ghOut, branch = branch)
            }
        }

        val glabProc = runCatching {
            ProcessBuilder("glab", "mr", "create", "--source-branch", branch, "--title", title, "--description", body)
                .directory(workspace)
                .start()
        }.getOrNull()
        if (glabProc != null) {
            val glabOut = glabProc.inputStream.bufferedReader().readText().trim()
            val glabCode = glabProc.waitFor()
            if (glabCode == 0 && glabOut.startsWith("http")) {
                return PrOutcome.Created(prUrl = glabOut, branch = branch)
            }
        }

        val remoteUrl = git(workspace, "remote", "get-url", remote).stdout.trim()
        val webUrl = formatCompareUrl(remoteUrl, branch)
        return PrOutcome.Created(prUrl = webUrl ?: "$remote/$branch", branch = branch)
    }

    /**
     * 将独立补丁应用到临时 worktree 并推送到新分支创建 PR。
     * 宿主当前工作区不发生任何分支切换或文件污染。
     */
    fun pushPatchAndCreatePr(
        patchPath: String,
        workspacePath: String,
        branch: String,
        title: String,
        body: String = "Created by Tenon Agent.",
    ): PrOutcome {
        val patch = File(patchPath)
        if (!patch.isFile) return PrOutcome.Failed("patch file missing: $patchPath")
        val workspace = File(workspacePath)
        if (!workspace.isDirectory) return PrOutcome.Failed("workspace missing: $workspacePath")

        val tempDir = File.createTempFile("tenon-pr-worktree-", "").apply {
            delete()
            mkdirs()
        }
        return try {
            val addWorktree = git(workspace, "worktree", "add", "-b", branch, tempDir.absolutePath, "HEAD")
            if (addWorktree.exitCode != 0) {
                return PrOutcome.Failed("failed to create temporary worktree for PR: " + addWorktree.stderr.takeLast(300).trim())
            }
            val apply = git(tempDir, "apply", patch.absolutePath)
            if (apply.exitCode != 0) {
                return PrOutcome.Failed("failed to apply patch in PR worktree: " + apply.stderr.takeLast(300).trim())
            }
            git(tempDir, "add", "-A")
            val commit = git(tempDir, "commit", "-m", title)
            if (commit.exitCode != 0) {
                return PrOutcome.Failed("failed to commit patch in PR worktree: " + commit.stderr.takeLast(300).trim())
            }
            pushBranchAndCreatePr(branch = branch, workspacePath = workspace.absolutePath, title = title, body = body)
        } finally {
            git(workspace, "worktree", "remove", "--force", tempDir.absolutePath)
            git(workspace, "worktree", "prune")
            tempDir.deleteRecursively()
        }
    }

    internal fun formatCompareUrl(remoteUrl: String, branch: String): String? {
        val clean = remoteUrl.trim().removeSuffix(".git")
        return when {
            clean.contains("github.com") -> {
                val path = if (clean.startsWith("git@github.com:")) {
                    clean.removePrefix("git@github.com:")
                } else {
                    clean.substringAfter("github.com/").removePrefix("/")
                }
                "https://github.com/$path/compare/$branch?expand=1"
            }
            clean.contains("gitlab.com") -> {
                val path = if (clean.startsWith("git@gitlab.com:")) {
                    clean.removePrefix("git@gitlab.com:")
                } else {
                    clean.substringAfter("gitlab.com/").removePrefix("/")
                }
                "https://gitlab.com/$path/-/merge_requests/new?merge_request%5Bsource_branch%5D=$branch"
            }
            else -> null
        }
    }

    /**
     * 把 git 的 stderr 尾巴翻成一句用户能照做的话。
     *
     * 为什么必须翻：`error: hello.txt: already exists in working directory` 是给写
     * 补丁的人看的，不是给点按钮的人看的 —— 他需要知道的是「下一步做什么」。
     */
    suspend fun hintFor(detail: String, texts: RelayUiTexts): String =
        texts.patchMergeHint(kindOf(detail), detail)

    /**
     * 把 git 的输出归类成资源键（[RelayUiTexts.patchMergeHint] 的 kind）。
     *
     * 归类留在本模块而不是措辞层：只有这里知道 git apply 的 stderr 长什么样；
     * 而「用户该怎么读它」由 strings.xml 决定。两边都不许各自再猜一份。
     */
    private fun kindOf(detail: String): String {
        val d = detail.lowercase()
        return when {
            d.contains("already exists") -> KIND_ALREADY_EXISTS
            d.contains("does not apply") || d.contains("patch failed") || d.contains("corrupt patch") ->
                KIND_MISMATCH
            detail.startsWith("patch file missing") -> KIND_PATCH_MISSING
            detail.startsWith("workspace missing") -> KIND_WORKSPACE_MISSING
            else -> KIND_FAILED
        }
    }

    private const val KIND_ALREADY_EXISTS = "already-exists"
    private const val KIND_MISMATCH = "does-not-apply"
    private const val KIND_PATCH_MISSING = "patch-missing"
    private const val KIND_WORKSPACE_MISSING = "workspace-missing"
    private const val KIND_FAILED = "failed"

    private fun git(cwd: File, vararg args: String): GitOutput {
        val proc = ProcessBuilder(listOf("git") + args.toList())
            .directory(cwd)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        return GitOutput(code, out, err)
    }

    private data class GitOutput(val exitCode: Int, val stdout: String, val stderr: String)
}
