// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

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
 */
object DesktopPatchMerge {

    sealed interface Outcome {
        /** 已应用到宿主工作树（未提交 —— 提交是机主的下一步，不是沙箱代办）。 */
        data object Applied : Outcome

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
        return Outcome.Applied
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
