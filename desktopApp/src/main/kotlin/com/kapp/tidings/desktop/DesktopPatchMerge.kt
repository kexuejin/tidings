// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.drivers.MergeBaseRef
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
     * 把 git 的 stderr 尾巴翻成一句用户能照做的话。
     *
     * 为什么必须翻：`error: hello.txt: already exists in working directory` 是给写
     * 补丁的人看的，不是给点按钮的人看的 —— 他需要知道的是「下一步做什么」。
     */
    fun hintFor(detail: String): String {
        val d = detail.lowercase()
        return when {
            d.contains("already exists") ->
                "补丁要新建的文件在工作区里已经有了。先把它挪开或提交，再点一次合并"
            d.contains("does not apply") || d.contains("patch failed") || d.contains("corrupt patch") ->
                "补丁和当前文件对不上 —— 这些地方可能已经被改过，需要手工对一下"
            detail.startsWith("patch file missing") -> "补丁文件已经不在了，让执行方重跑一次"
            detail.startsWith("workspace missing") -> "工作区目录找不到了，确认它还在原位置"
            else -> "补丁没能应用：" + detail.take(160)
        }
    }

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
