// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import java.io.File
import java.nio.file.Files

/**
 * 合并基线的滚动快照（D5）。
 *
 * ## 问题
 *
 * 合流只把补丁 `git apply` 进宿主工作树，**不 add / 不 commit** —— 提交是机主的
 * 下一步，不是沙箱代办。于是 HEAD 停在原地，下一张工单的沙箱（`git worktree add`
 * 默认自 HEAD）看不到上一轮合进去的代码：阶段 B 的 AQI `+92 −0` 与阶段 C 的
 * `+31 −1` 各自独立、互不可见，`npm test` 的基线也一直是 71 而不是累计值。
 *
 * ## 做法（滚动基线）
 *
 * 每次合并成功后在**不动 HEAD、不动真实索引**的前提下，把当前工作树打成一份
 * 快照提交，存到 [REF]；沙箱建 worktree 时以它为起点。这样「合进去的改动」就
 * 成为后续迭代的起点，而宿主仓的 HEAD、分支、暂存区全都保持机主离开时的样子。
 *
 * 快照提交的父提交是打快照那一刻的 HEAD。于是「机主后来自己 commit 了」可以被
 * 识别出来：父提交 ≠ 当前 HEAD ⇒ 快照已过时（HEAD 已经包含那批改动），[baseFor]
 * 自动回落到 HEAD —— 不拿一份旧树去覆盖机主的新提交。
 *
 * ## 为什么用临时索引
 *
 * `git add -A` 会写真实索引，把机主的暂存区搅乱；`git stash create` 又会动 stash
 * 栈。这里用 `GIT_INDEX_FILE` 指向一次性索引：`read-tree HEAD` 装基线、
 * `add -A` 把工作树（含未跟踪的新文件）写进这棵树、`write-tree` 得到树对象、
 * `commit-tree` 得到提交对象 —— 全程只**新增对象**，不改任何既有引用。
 *
 * ## 失败语义
 *
 * [snapshot] 失败返回 null（不是 git 仓 / 还没有首个提交 / git 不认身份）。
 * 调用方只记日志，**不把合并判失败**：合并本身已经落地，快照是下一步的便利。
 */
object MergeBaseRef {

    /** 滚动基线的引用名。放在 `refs/tidings/` 下，不会出现在 `git branch` 列表里。 */
    const val REF = "refs/tidings/base"

    /** 快照提交的固定说明（`git log` 里能一眼看出它是编排器打的，不是人提交的）。 */
    const val SNAPSHOT_MESSAGE = "tidings: merged base snapshot"

    /**
     * 把 [workspace] 的当前工作树打成快照并推进 [REF]，返回快照提交 sha；失败返回 null。
     *
     * **幂等**：没有新改动时重复调用只会产生一个与上一份树相同的新提交（ref 前移，
     * 父提交仍是当前 HEAD），不会累积出分叉。
     */
    fun snapshot(workspace: File): String? {
        if (!workspace.isDirectory) return null
        // 还没有首个提交（unborn HEAD）时没有可打的基线 —— worktree add 本来也会失败。
        if (git(workspace, "rev-parse", "--verify", "--quiet", "HEAD").exitCode != 0) return null

        val tmpDir = runCatching { Files.createTempDirectory("tidings-snapshot").toFile() }.getOrNull()
            ?: return null
        // 指向一个**尚不存在**的索引文件：git 会自己创建它。若先建成空文件，
        // git 会把它当成损坏的索引（"index file smaller than expected"）。
        val index = File(tmpDir, "index")
        val env = mapOf("GIT_INDEX_FILE" to index.absolutePath)
        try {
            if (git(workspace, "read-tree", "HEAD", env = env).exitCode != 0) return null
            // add -A 尊重 .gitignore；沙箱产物这类未跟踪的新文件会进来 —— 那正是
            // 下一轮必须看得见的东西（阶段 B 的 src/aqi.js 就是未跟踪新文件）。
            if (git(workspace, "add", "-A", env = env).exitCode != 0) return null
            val tree = git(workspace, "write-tree", env = env)
            if (tree.exitCode != 0) return null
            val treeSha = tree.stdout.trim()
            if (treeSha.isEmpty()) return null
            // 身份显式给：机主可能没配 user.email，而 commit-tree 在缺身份时会直接失败。
            val commit = git(
                workspace,
                "-c", "user.name=tidings",
                "-c", "user.email=tidings@localhost",
                "commit-tree", treeSha, "-p", "HEAD", "-m", SNAPSHOT_MESSAGE,
            )
            if (commit.exitCode != 0) return null
            val sha = commit.stdout.trim()
            if (sha.isEmpty()) return null
            if (git(workspace, "update-ref", REF, sha).exitCode != 0) return null
            return sha
        } finally {
            runCatching { index.delete() }
            runCatching { tmpDir.deleteRecursively() }
        }
    }

    /**
     * 沙箱应当使用的起点：快照仍然有效且 HEAD 没动过时返回 [REF]，否则返回 `"HEAD"`。
     *
     * 「有效」= 引用存在，且快照的父提交就是当前 HEAD。机主提交过（或 reset 过）
     * 之后父提交对不上：此时 HEAD 已经包含（或替换了）那批改动，回落到 HEAD 才是
     * 正确起点 —— 不能拿一份旧树去盖住机主的新提交。
     */
    fun baseFor(workspace: File): String {
        if (!workspace.isDirectory) return "HEAD"
        val snap = git(workspace, "rev-parse", "--verify", "--quiet", REF)
        if (snap.exitCode != 0) return "HEAD"
        val parent = git(workspace, "rev-parse", "--verify", "--quiet", "$REF^")
        if (parent.exitCode != 0) return "HEAD"
        val head = git(workspace, "rev-parse", "--verify", "--quiet", "HEAD")
        if (head.exitCode != 0) return "HEAD"
        return if (parent.stdout.trim() == head.stdout.trim()) REF else "HEAD"
    }

    private fun git(
        cwd: File,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
    ): GitResult {
        val pb = ProcessBuilder(listOf("git") + args.toList()).directory(cwd)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        return GitResult(code, out, err)
    }
}
