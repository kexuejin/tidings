// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.DriverEvent
import com.kapp.tenon.agent.drivers.DriverKeys
import com.kapp.tenon.agent.drivers.WorktreeSandboxExecutor
import com.kapp.tenon.agent.drivers.cliDriverRegistrations
import com.kapp.tenon.core.protocol.QuestStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 宿主面验收（ACCEPTANCE §6b 机制半边）：**真实 CLI 驱动栈**跑完一条支线的完整
 * 生命周期——真沙箱、真 CLI 进程（本机安装的 codex/claude/dsh…）、真 patch 收割、
 * 真提交、真门禁、真 ff 合流。不是单测替身。
 *
 * 显式启用：`TENON_QUEST_E2E=1 ./gradlew :orchestration:test --tests "*HostFace*"`。
 * 不设则大声跳过（缺输入=没验到，不是通过）——LLM CLI 依赖本机安装与凭证，
 * 日常批不赌它。驱动栈用生产装配函数 [cliDriverRegistrations]（真凭证注入、
 * 真沙箱 HOME），不用合成替身。
 */
class QuestRunnerHostFaceAcceptanceTest {

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
    fun realCliQuestLifecycleEndToEnd(): Unit = runBlocking {
        // 显式启用 + 本机有可用的 CLI 驱动才跑（缺一即大声跳过，附补装指引）。
        if (System.getenv("TENON_QUEST_E2E") == null) {
            println("[hostface] SKIP —— 设 TENON_QUEST_E2E=1 显式启用（需要本机 CLI 与凭证）。跳过不是通过。")
            Assume.assumeTrue(false)
        }
        val sandboxRoot = File.createTempFile("tenon-hostface-sandbox-", "").apply { delete(); mkdirs() }
        val sandbox = WorktreeSandboxExecutor(sandboxRoot)
        val drivers = cliDriverRegistrations(sandbox = sandbox)
        if (drivers.isEmpty()) {
            println("[hostface] SKIP —— 本机没有发现任何 CLI 驱动（装 codex/claude/dsh 之一）。跳过不是通过。")
            Assume.assumeTrue(false)
        }

        // 驱动优先序：按本机「有可用凭证与配置」的实况排（dsh 为开发版 CLI 面时
        // 与驱动实测的 v0.2.1-rc.2 参数不兼容——探针实证，证据见 runtime-evidence）。
        val wanted = listOf("codex", "claude", "dsh", "agy", "opencode")
        val chosenKey = wanted.firstNotNullOfOrNull { key ->
            drivers.firstOrNull { it.driverId == DriverKeys.cliDriverId(key) }?.let { key }
        }
        if (chosenKey == null) {
            println("[hostface] SKIP —— 发现的驱动 ${drivers.map { it.driverId }} 不在候选序 $wanted 内")
            Assume.assumeTrue(false)
        }
        val agentKey = chosenKey!!
        println("[hostface] driver = $agentKey (${drivers.first { it.driverId == DriverKeys.cliDriverId(agentKey) }.driverId})")

        val ws = File.createTempFile("tenon-hostface-ws-", "").apply { delete(); mkdirs() }
        val root = File.createTempFile("tenon-hostface-root-", "").apply { delete(); mkdirs() }
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        try {
            runGit(ws, "init", "-b", "main")
            runGit(ws, "config", "user.name", "AcceptanceRunner")
            runGit(ws, "config", "user.email", "acceptance@tenon.local")
            File(ws, "README.md").writeText("# Host face acceptance\n")
            runGit(ws, "add", "-A")
            runGit(ws, "commit", "-m", "init")

            val runner = QuestRunner(
                worktreeRoot = root,
                onNotice = { notices.add(it) },
                gitTimeoutMs = 30_000L,
                runTask = { _, worktreeDir, prompt, key ->
                    // 真驱动栈：与生产同一条路（openSession → executeTask → patch 收割）。
                    val d = drivers.first { it.driverId == DriverKeys.cliDriverId(key) }
                    val session = d.openSession(worktreeDir.absolutePath, null)
                    var patchPath: String? = null
                    var finished = false
                    var error: String? = null
                    d.executeTask(session.sessionId, prompt) { event ->
                        when (event) {
                            is DriverEvent.Artifact -> if (event.name == "patch.diff") patchPath = event.path
                            is DriverEvent.Error -> error = event.message
                            is DriverEvent.Finished -> finished = true
                            else -> Unit
                        }
                    }
                    QuestRunner.CliTaskRun(ok = finished, patchPath = patchPath, summary = null, error = error)
                },
            )

            val result = kotlinx.coroutines.withTimeout(10L * 60L * 1000L) {
                runner.dispatch(
                    prompt = "Create a file named accepted.txt in the current directory containing exactly one line: OK. " +
                        "Do not modify any other file. Then stop.",
                    workspacePath = ws.absolutePath,
                    title = "Host face acceptance quest",
                    agentKey = agentKey,
                    autoMerge = true,
                    gateCommand = "test -f accepted.txt",
                )
            }

            println("[hostface] notices = " + notices.joinToString(" | ") { it.status.name + " rev" + it.rev })
            val started = result as? QuestRunner.DispatchResult.Started
                ?: throw AssertionError("派发未被受理: " + result)
            println("[hostface] terminalStatus = " + started.terminalStatus)
            assertEquals(
                "整条生命周期必须走到 COMPLETED（真 CLI、真提交、真合流）",
                "COMPLETED",
                started.terminalStatus,
            )
            assertTrue(
                "合流必须真落盘（accepted.txt 进主仓）",
                File(ws, "accepted.txt").isFile && File(ws, "accepted.txt").readText().contains("OK"),
            )
            assertEquals(
                "通知序列 = RUNNING → COMPLETED（一任务一卡原位翻面）",
                listOf(QuestStatus.RUNNING, QuestStatus.COMPLETED),
                notices.map { it.status },
            )
            assertFalse("终态后 worktree 清场", File(root, started.questId).exists())
        } finally {
            safeDelete(ws)
            safeDelete(root)
            safeDelete(sandboxRoot)
        }
    }
}
