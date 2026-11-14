// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class QuestSerialMergeLockSafetyTest {

    private fun runGit(dir: File, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git") + args.toList())
            .directory(dir)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out.trim()
    }

    private fun safeDelete(dir: File) {
        if (!dir.exists()) return
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(d)
                return FileVisitResult.CONTINUE
            }
        })
    }

    @Test
    fun dirtyHostWorkspaceBlocksMergeWithoutStash(): Unit = runBlocking {
        val baseDir = File.createTempFile("tenon-test-repo-", "").apply {
            delete()
            mkdirs()
        }

        try {
            runGit(baseDir, "init", "-b", "main")
            runGit(baseDir, "config", "user.name", "Tester")
            runGit(baseDir, "config", "user.email", "tester@test.local")
            File(baseDir, "README.md").writeText("# Main\n")
            File(baseDir, "feature.txt").writeText("initial\n")
            runGit(baseDir, "add", "-A")
            runGit(baseDir, "commit", "-m", "init")

            val worktreeDir = File.createTempFile("tenon-test-wt-", "").apply {
                delete()
                mkdirs()
            }
            runGit(baseDir, "worktree", "add", "-b", "quest-branch", worktreeDir.absolutePath, "main")

            File(worktreeDir, "feature.txt").writeText("modified by quest\n")
            runGit(worktreeDir, "add", "-A")
            runGit(worktreeDir, "commit", "-m", "quest commit")

            // 主工作区存在未提交改动
            File(baseDir, "feature.txt").writeText("local uncommitted changes by user\n")

            val mergeLock = QuestSerialMergeLock(baseDir)
            val result = mergeLock.rebaseAndMerge("quest-1", worktreeDir, "main")

            assertTrue("主工作区脏时必须阻断合流", result is QuestSerialMergeLock.MergeResult.HostWorkspaceDirty)
            assertEquals("机主未提交修改必须原样保留，严禁静默 stash", "local uncommitted changes by user\n", File(baseDir, "feature.txt").readText())

            runGit(baseDir, "worktree", "remove", "--force", worktreeDir.absolutePath)
        } finally {
            safeDelete(baseDir)
        }
    }

    @Test
    fun cleanHostWorkspaceFastForwardMergesAndProducesRealCommit(): Unit = runBlocking {
        val baseDir = File.createTempFile("tenon-test-repo-clean-", "").apply {
            delete()
            mkdirs()
        }

        try {
            runGit(baseDir, "init", "-b", "main")
            runGit(baseDir, "config", "user.name", "Tester")
            runGit(baseDir, "config", "user.email", "tester@test.local")
            File(baseDir, "README.md").writeText("# Main\n")
            runGit(baseDir, "add", "-A")
            runGit(baseDir, "commit", "-m", "init")

            val worktreeDir = File.createTempFile("tenon-test-wt-clean-", "").apply {
                delete()
                mkdirs()
            }
            runGit(baseDir, "worktree", "add", "-b", "quest-clean", worktreeDir.absolutePath, "main")

            File(worktreeDir, "new_file.txt").writeText("new file from quest\n")
            runGit(worktreeDir, "add", "-A")
            runGit(worktreeDir, "commit", "-m", "add new file")

            val questCommit = runGit(worktreeDir, "rev-parse", "HEAD")

            val mergeLock = QuestSerialMergeLock(baseDir)
            val result = mergeLock.rebaseAndMerge("quest-clean-1", worktreeDir, "main")

            assertTrue("干净的工作区合流必须成功", result is QuestSerialMergeLock.MergeResult.Success)
            val success = result as QuestSerialMergeLock.MergeResult.Success
            assertEquals("合流结果返回的 commit 必须等于支线 commit", questCommit, success.mergedCommit)

            // 关键断言：主工作区 HEAD 必须真正推进到了支线 commit！不仅是文件存在！
            val mainHead = runGit(baseDir, "rev-parse", "HEAD")
            assertEquals("主工作区 HEAD 必须真实向前推进", questCommit, mainHead)
            assertEquals("合流后主工作区必须保持绝对干净", "", runGit(baseDir, "status", "--porcelain"))

            runGit(baseDir, "worktree", "remove", "--force", worktreeDir.absolutePath)
        } finally {
            safeDelete(baseDir)
        }
    }

    @Test
    fun branchMismatchFailsPreMerge(): Unit = runBlocking {
        val baseDir = File.createTempFile("tenon-test-repo-branch-", "").apply {
            delete()
            mkdirs()
        }

        try {
            runGit(baseDir, "init", "-b", "main")
            runGit(baseDir, "config", "user.name", "Tester")
            runGit(baseDir, "config", "user.email", "tester@test.local")
            File(baseDir, "README.md").writeText("# Main\n")
            runGit(baseDir, "add", "-A")
            runGit(baseDir, "commit", "-m", "init")

            // 切到 dev 分支
            runGit(baseDir, "checkout", "-b", "dev")

            val worktreeDir = File.createTempFile("tenon-test-wt-branch-", "").apply {
                delete()
                mkdirs()
            }
            runGit(baseDir, "worktree", "add", "-b", "quest-branch-test", worktreeDir.absolutePath, "dev")

            val mergeLock = QuestSerialMergeLock(baseDir)
            val result = mergeLock.rebaseAndMerge("quest-branch-1", worktreeDir, "main")

            assertTrue("主工作区分支不匹配时必须 PreMergeFailed", result is QuestSerialMergeLock.MergeResult.PreMergeFailed)

            runGit(baseDir, "worktree", "remove", "--force", worktreeDir.absolutePath)
        } finally {
            safeDelete(baseDir)
        }
    }

    @Test
    fun gitTimeoutReturnsBoundedAndLeavesMergeMutexHealthy(): Unit = runBlocking {
        // 超时路径的两条真性质（跨平台确定性，不赌管道语义）：
        // ① 单条 git 命令超时后有界返回、如实报 Conflict（exitCode -1 + 超时原因）；
        // ② 超时之后 mergeMutex 不中毒 —— 换一条正常合流必须照常成功（§3：怕的不是
        //    一次失败，是「失败一次、后续所有合流静默停摆」）。
        val baseDir = File.createTempFile("tenon-test-repo-", "").apply { delete(); mkdirs() }
        val worktreeDir = File.createTempFile("tenon-test-wt-", "").apply { delete(); mkdirs() }
        try {
            runGit(baseDir, "init", "-b", "main")
            runGit(baseDir, "config", "user.name", "Tester")
            runGit(baseDir, "config", "user.email", "tester@test.local")
            File(baseDir, "README.md").writeText("# Main\n")
            runGit(baseDir, "add", "-A")
            runGit(baseDir, "commit", "-m", "init")
            runGit(baseDir, "worktree", "add", "-b", "quest-branch", worktreeDir.absolutePath, "main")
            File(worktreeDir, "feature.txt").writeText("modified by quest\n")
            runGit(worktreeDir, "add", "-A")
            runGit(worktreeDir, "commit", "-m", "quest commit")
            // main 在支线分叉后再前进一格：rebase 有真活，pre-rebase hook 才会执行
            File(baseDir, "main-only.txt").writeText("moved on\n")
            runGit(baseDir, "add", "-A")
            runGit(baseDir, "commit", "-m", "main moves on")

            // 前景 hook：git 会等它跑完 —— 500ms 超时确定性地在 rebase 这一步触发
            val hooksDir = File(baseDir, ".git/hooks")
            hooksDir.mkdirs()
            val hook = File(hooksDir, "pre-rebase")
            hook.writeText("#!/bin/sh\nexec sleep 60\n")
            val chmod = ProcessBuilder(listOf("chmod", "+x", hook.absolutePath)).start()
            chmod.waitFor()

            val lock = QuestSerialMergeLock(mainWorkspace = baseDir, gitTimeoutMs = 500L)
            val result = kotlinx.coroutines.withTimeout(20_000L) {
                lock.rebaseAndMerge(questId = "quest-timeout", questWorktreeDir = worktreeDir)
            }
            assertTrue(
                "超时按 Conflict 如实报错，不得谎报成功: " + result,
                result is QuestSerialMergeLock.MergeResult.Conflict &&
                    result.reason.contains("timed out"),
            )

            // 锁不中毒：摘掉 hook，同一条合流立刻照常成功
            hook.delete()
            val second = lock.rebaseAndMerge(questId = "quest-timeout", questWorktreeDir = worktreeDir)
            assertTrue(
                "超时之后锁必须照常工作（§3：怕的是后续所有合流静默停摆）: " + second,
                second is QuestSerialMergeLock.MergeResult.Success,
            )
        } finally {
            val killer = ProcessBuilder(listOf("pkill", "-f", "exec sleep 60")).start()
            killer.waitFor()
            safeDelete(baseDir)
            safeDelete(worktreeDir)
        }
    }
}
