// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AssistantQuestNotice
import com.kapp.tenon.core.protocol.QuestStatus
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

class QuestAutomationExecutorTest {

    private fun runGit(dir: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList())
            .directory(dir)
            .start()
        proc.waitFor()
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
    fun confirmationPolicyAwaitsHumanConfirmationWithoutMerging(): Unit = runBlocking {
        val tempDir = File.createTempFile("tenon-quest-conf-", "").apply {
            delete()
            mkdirs()
        }
        try {
            val notices = mutableListOf<AssistantQuestNotice>()
            val mergeLock = QuestSerialMergeLock(tempDir)
            val executor = QuestAutomationExecutor(mergeLock = mergeLock) { notice ->
                notices.add(notice)
            }

            val outcome = executor.handleQuestCompletion(
                questId = "quest-conf-1",
                convId = "assistant",
                title = "Build feature",
                worktreeDir = tempDir,
                autoMerge = false,
                summary = "All tests passed",
            )

            assertEquals(QuestAutomationExecutor.AutomationOutcome.AwaitingConfirmation, outcome)
            assertEquals(1, notices.size)
            // 变更前确认模式状态必须是 WAITING_INPUT 等待审批，绝不撒谎已完成
            assertEquals(QuestStatus.WAITING_INPUT, notices[0].status)
            assertEquals("quest-conf-1", notices[0].questId)
        } finally {
            safeDelete(tempDir)
        }
    }

    @Test
    fun autoMergePolicyMergesDirectlyWhenClean(): Unit = runBlocking {
        val baseDir = File.createTempFile("tenon-auto-merge-base-", "").apply {
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

            val worktreeDir = File.createTempFile("tenon-auto-merge-wt-", "").apply {
                delete()
                mkdirs()
            }
            runGit(baseDir, "worktree", "add", "-b", "quest-auto", worktreeDir.absolutePath, "main")
            File(worktreeDir, "auto.txt").writeText("auto content\n")
            runGit(worktreeDir, "add", "-A")
            runGit(worktreeDir, "commit", "-m", "add auto.txt")

            val notices = mutableListOf<AssistantQuestNotice>()
            val mergeLock = QuestSerialMergeLock(baseDir)
            val executor = QuestAutomationExecutor(mergeLock = mergeLock) { notice ->
                notices.add(notice)
            }

            val outcome = executor.handleQuestCompletion(
                questId = "quest-auto-1",
                convId = "assistant",
                title = "Auto task",
                worktreeDir = worktreeDir,
                autoMerge = true,
                summary = "Completed automatically",
            )

            assertTrue(outcome is QuestAutomationExecutor.AutomationOutcome.Merged)
            assertTrue(File(baseDir, "auto.txt").exists())
            assertEquals(1, notices.size)
            assertEquals(QuestStatus.COMPLETED, notices[0].status)

            runGit(baseDir, "worktree", "remove", "--force", worktreeDir.absolutePath)
        } finally {
            safeDelete(baseDir)
        }
    }
}
