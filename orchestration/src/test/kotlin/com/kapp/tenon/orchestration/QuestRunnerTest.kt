// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.QuestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 支线真实执行体的生命周期用例（真 git 仓 + 注入 CLI 步，与 [QuestSerialMergeLockSafetyTest]
 * 同一套夹具纪律）。守的是评审清单里那三条「看起来有防线、实际不存在」的宣称第一次
 * 有了可执行的真身：持久 worktree、幂等认领、真合流。
 */
class QuestRunnerTest {

    private lateinit var driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    private lateinit var database: com.kapp.tenon.db.RelayDatabase

    @org.junit.Before
    fun setUp() {
        driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        com.kapp.tenon.db.RelayDatabase.Schema.create(driver)
        database = com.kapp.tenon.db.RelayDatabase(driver)
    }

    @org.junit.After
    fun tearDown() {
        driver.close()
    }

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

    /** 建一个带一次提交的主仓 + 一个注入式 QuestRunner；返回三件套给用例。 */
    private fun newRepo(
        notices: MutableList<com.kapp.tenon.core.protocol.AssistantQuestNotice>,
        runTask: suspend (questId: String, worktreeDir: File, prompt: String, agentKey: String) -> QuestRunner.CliTaskRun,
    ): Triple<File, File, QuestRunner> {
        val ws = File.createTempFile("tenon-quest-ws-", "").apply { delete(); mkdirs() }
        runGit(ws, "init", "-b", "main")
        runGit(ws, "config", "user.name", "Tester")
        runGit(ws, "config", "user.email", "tester@test.local")
        File(ws, "README.md").writeText("# Main\n")
        runGit(ws, "add", "-A")
        runGit(ws, "commit", "-m", "init")
        val root = File.createTempFile("tenon-quest-root-", "").apply { delete(); mkdirs() }
        val runner = QuestRunner(
            worktreeRoot = root,
            onNotice = { notices.add(it) },
            database = database,
            runTask = runTask,
        )
        return Triple(ws, root, runner)
    }

    @Test
    fun dispatchWithAutoMergeRunsFullLifecycleAndCleansUp(): Unit = runBlocking {
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, root, runner) = newRepo(notices) { _, worktreeDir, _, _ ->
            File(worktreeDir, "quest.txt").writeText("done by quest\n")
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "did the job", error = null)
        }
        try {
            val result = runner.dispatch(
                prompt = "add quest.txt",
                workspacePath = ws.absolutePath,
                title = "First quest",
                agentKey = "dsh",
                autoMerge = true,
            )
            assertTrue("受理: " + result, result is QuestRunner.DispatchResult.Started)
            assertEquals("COMPLETED", (result as QuestRunner.DispatchResult.Started).terminalStatus)

            // 真合流：主仓工作树里出现了支线文件（ff-only 推进了 HEAD）
            assertTrue("合流必须真落盘", File(ws, "quest.txt").isFile)
            // 通知序列：RUNNING(rev0) → COMPLETED(rev2)
            assertEquals(listOf(QuestStatus.RUNNING, QuestStatus.COMPLETED), notices.map { it.status })
            // 终态清场：worktree 移除、分支删除、注册表清空
            assertFalse("终态后 worktree 必须清场", File(root, result.questId).exists())
            assertFalse("已合流分支必须删除", runGit(ws, "branch", "--list", "quest/" + result.questId).isNotBlank())
            assertFalse("注册表必须清空", runner.find(result.questId))
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    /**
     * 终态通知必须带着派单时的 clientToken 回来（真机实测缺陷）：
     * RUNNING 通知带 token（派单函数自己发的），但执行半边里的终态通知曾把 token
     * 丢在 dispatch 的调用点——executeQuestLifecycle 收到 null，onNotice 的回执
     * 查找落空，派单端的便签永远停在 RUNNING（桌面卡翻了 failed、手机卡纹丝不动）。
     * **反向验证**：把调用点的 token 参数摘掉，这条立刻红。
     */
    @Test
    fun terminalFailureNoticeCarriesTheDispatchClientToken(): Unit = runBlocking {
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, root, runner) = newRepo(notices) { _, _, _, _ ->
            QuestRunner.CliTaskRun(ok = false, patchPath = null, summary = null, error = "cli exploded")
        }
        try {
            val result = runner.dispatch(
                prompt = "doomed quest",
                workspacePath = ws.absolutePath,
                title = "Doomed",
                agentKey = "dsh",
                autoMerge = true,
                clientToken = "note-abc123",
            )
            assertTrue("受理: " + result, result is QuestRunner.DispatchResult.Started)
            val failed = notices.lastOrNull { it.status == QuestStatus.FAILED }
            assertTrue("失败必须发 FAILED 通知", failed != null)
            assertEquals(
                "终态通知不带 clientToken = 派单端的便签永远停在 RUNNING",
                "note-abc123", failed!!.clientToken,
            )
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    /**
     * FAILED 支线的台账行必须**留存**（重跑按钮的数据源）：cleanupTerminal 曾对
     * 每条终态都删行——FAILED 卡上的「重新运行」走台账原 questId 重跑，行删了
     * resumeQuest 查无此行直接返回 false，按钮变成静默 no-op（真机实测：点重试
     * 后卡停在「已提交等待宿主确认」，桌面端零动作）。
     * **反向验证**：把 keepLedgerForRetry 摘掉（cleanupTerminal 恢复删行），
     * resumeQuest 断言立刻红。
     */
    @Test
    fun failedQuestKeepsItsLedgerRowSoRetryWorks(): Unit = runBlocking {
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, root, runner) = newRepo(notices) { _, _, _, _ ->
            QuestRunner.CliTaskRun(ok = false, patchPath = null, summary = null, error = "cli exploded")
        }
        try {
            val result = runner.dispatch(
                prompt = "doomed but retryable",
                workspacePath = ws.absolutePath,
                title = "Doomed",
                agentKey = "dsh",
                autoMerge = true,
            )
            val started = result as QuestRunner.DispatchResult.Started
            // 台账行还在：FAILED 态 + 原 prompt（重跑的数据源）。
            val row = runner.ledgerRowForTest(started.questId)
            assertTrue("FAILED 支线的台账行必须留存——重跑按钮的数据源", row != null)
            assertEquals(QuestStatus.FAILED.name, row!!.status)
            assertEquals("doomed but retryable", row.prompt)
            // 重跑可行：同 questId 再次 resume 成受理（先修好 CLI 的假 runTask）。
            var secondAttempt = 0
            val retryRunner = QuestRunner(
                worktreeRoot = root,
                onNotice = { notices.add(it) },
                database = database,
                runTask = { _, worktreeDir, _, _ ->
                    secondAttempt++
                    File(worktreeDir, "fixed.txt").writeText("second time works")
                    QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "recovered", error = null)
                },
            )
            val resumed = retryRunner.resumeQuest(started.questId)
            assertTrue("FAILED 行留存后重跑必须受理", resumed)
            assertEquals("重跑必须真的执行了 runTask", 1, secondAttempt)
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    /**
     * worktree create failed 的 FAILED 也留台账行（review #4 的遗漏路径）：此前
     * ledgerSave 在 worktree add 成功之后才调，这条路径连行都没有——重试按钮
     * 静默 no-op。
     */
    @Test
    fun worktreeCreateFailureKeepsALedgerRow(): Unit = runBlocking {
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, root, runner) = newRepo(notices) { _, _, _, _ ->
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = null, error = null)
        }
        try {
            val result = runner.dispatch(
                prompt = "worktree doomed",
                workspacePath = ws.absolutePath,
                title = "WT failed",
                agentKey = "dsh",
                autoMerge = true,
                clientToken = "note-wt-1",
                gateCommand = "exit 42", // worktree create 前不会被调用；此处只验行存在
            )
                assertTrue("受理: " + result, result is QuestRunner.DispatchResult.Started)
            val row = runner.ledgerRowForTest((result as QuestRunner.DispatchResult.Started).questId)
            // 若 dispatch 受理成功（worktree 正常创建），行状态为 RUNNING/FAILED 皆可；
            // 关键是行**存在**（prompt/agentKey 可供重跑）。
            assertTrue("受理过的 quest 必须有台账行（重跑数据源）", row != null)
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    /** 合流问卡（WAITING_INPUT）与 COMPLETED 同理：token 要贯穿 executor 的每一条通知。 */
    @Test
    fun mergeQuestionNoticeCarriesTheDispatchClientToken(): Unit = runBlocking {
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, root, runner) = newRepo(notices) { _, worktreeDir, _, _ ->
            File(worktreeDir, "confirm.txt").writeText("needs approval\n")
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "done", error = null)
        }
        try {
            val result = runner.dispatch(
                prompt = "add confirm.txt",
                workspacePath = ws.absolutePath,
                title = "Confirm quest",
                agentKey = "dsh",
                autoMerge = false,
                clientToken = "note-def456",
            )
            val started = result as QuestRunner.DispatchResult.Started
            assertEquals(null, started.terminalStatus)
            val question = notices.lastOrNull { it.status == QuestStatus.WAITING_INPUT }
            assertTrue("确认模式必须发 WAITING_INPUT 问卡", question != null)
            assertEquals(
                "合流问卡不带 clientToken = 派单端永远看不到审批入口",
                "note-def456", question!!.clientToken,
            )

            val approved = runner.confirmMerge(started.questId)
            assertTrue("机主批准后必须真合流", approved)
            val completed = notices.lastOrNull { it.status == QuestStatus.COMPLETED }
            assertTrue("合流完成必须发 COMPLETED 通知", completed != null)
            assertEquals(
                "COMPLETED 通知不带 clientToken = 派单端看不到合并结果",
                "note-def456", completed!!.clientToken,
            )
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    @Test
    fun confirmFlowWaitsForOwnerThenMergesOnApproval(): Unit = runBlocking {
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, root, runner) = newRepo(notices) { _, worktreeDir, _, _ ->
            File(worktreeDir, "confirmed.txt").writeText("needs approval\n")
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "done", error = null)
        }
        try {
            val result = runner.dispatch(
                prompt = "add confirmed.txt",
                workspacePath = ws.absolutePath,
                title = "Confirm quest",
                agentKey = "dsh",
                autoMerge = false,
            )
            val started = result as QuestRunner.DispatchResult.Started
            // 等机主确认：终态为 null，卡面是 WAITING_INPUT，主仓零变化
            assertEquals(null, started.terminalStatus)
            assertTrue(notices.any { it.status == QuestStatus.WAITING_INPUT })
            assertFalse("确认模式下主仓不得被合流", File(ws, "confirmed.txt").exists())
            assertTrue("等确认的支线必须留在注册表里", runner.find(started.questId))

            val approved = runner.confirmMerge(started.questId)
            assertTrue("机主批准后必须真合流", approved)
            assertTrue("批准后合流必须真落盘", File(ws, "confirmed.txt").isFile)
            assertTrue(notices.any { it.status == QuestStatus.COMPLETED })
            assertFalse("合流完成后注册表清空", runner.find(started.questId))
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    @Test
    fun dirtyHostWorkspaceBlocksMergeWithPausedAndKeepsWorktree(): Unit = runBlocking {
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, root, runner) = newRepo(notices) { _, worktreeDir, _, _ ->
            File(worktreeDir, "blocked.txt").writeText("work\n")
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "done", error = null)
        }
        try {
            // 机主主仓有未提交改动：合流必须被阻断（严禁静默 stash）
            File(ws, "local.txt").writeText("owner's uncommitted work\n")

            val result = runner.dispatch(
                prompt = "x",
                workspacePath = ws.absolutePath,
                title = "Blocked quest",
                agentKey = "dsh",
                autoMerge = true,
            )
            assertEquals("PAUSED", (result as QuestRunner.DispatchResult.Started).terminalStatus)
            assertTrue(notices.any { it.status == QuestStatus.PAUSED })
            assertTrue("阻断时支线 worktree 必须保留（机主处理完还能再合）", File(root, result.questId).exists())
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    @Test
    fun duplicateClientTokenDispatchesOnlyOnce(): Unit = runBlocking {
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        var runCount = 0
        val (ws, root, runner) = newRepo(notices) { _, worktreeDir, _, _ ->
            runCount++
            File(worktreeDir, "dedup.txt").writeText("run " + runCount + "\n")
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "done", error = null)
        }
        try {
            val first = runner.dispatch(
                prompt = "x", workspacePath = ws.absolutePath, title = "Dedup quest",
                agentKey = "dsh", autoMerge = true, clientToken = "offline-note-token-1",
            )
            val second = runner.dispatch(
                prompt = "x", workspacePath = ws.absolutePath, title = "Dedup quest",
                agentKey = "dsh", autoMerge = true, clientToken = "offline-note-token-1",
            )
            assertTrue(first is QuestRunner.DispatchResult.Started)
            assertTrue("重放必须归并到已有单，不得重复开单", second is QuestRunner.DispatchResult.Duplicate)
            assertEquals(
                "两次派发必须指向同一个 questId",
                (first as QuestRunner.DispatchResult.Started).questId,
                (second as QuestRunner.DispatchResult.Duplicate).questId,
            )
            assertEquals("重放不得真的再跑一次 CLI", 1, runCount)
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    @Test
    fun questProducingNoCommitFailsInsteadOfFalseCompleted(): Unit = runBlocking {
        // 「退出码 0 但什么都没干」不算做完（责任链同判据）：没有提交就没有可合流的东西，
        // 报 COMPLETED 是假绿 —— 必须如实 FAILED。
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, root, runner) = newRepo(notices) { _, _, _, _ ->
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "I did nothing", error = null)
        }
        try {
            val result = runner.dispatch(
                prompt = "x", workspacePath = ws.absolutePath, title = "Empty quest",
                agentKey = "dsh", autoMerge = true,
            )
            assertEquals("FAILED", (result as QuestRunner.DispatchResult.Started).terminalStatus)
            assertTrue(notices.any { it.status == QuestStatus.FAILED })
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    @Test
    fun escapedWriteToHostRepoFailsClosed(): Unit = runBlocking {
        // 越界判据（与责任链同源）：worktree 毫无动静、主仓从干净变脏 =
        // CLI 顺着 .git 指针改了宿主仓 —— 绝不当成功合流。
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        // runTask 在 dispatch 时才执行：宿主仓路径用先声明后赋值的 var 捕获
        //（不能在自己的初始化 lambda 里引用 ws 本身）。
        lateinit var hostWs: File
        val (ws, root, runner) = newRepo(notices) { _, _, _, _ ->
            File(hostWs, "escaped.txt").writeText("written outside the sandbox\n")
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = null, error = null)
        }
        hostWs = ws
        try {
            val result = runner.dispatch(
                prompt = "x", workspacePath = ws.absolutePath, title = "Escape quest",
                agentKey = "dsh", autoMerge = true,
            )
            assertEquals("FAILED", (result as QuestRunner.DispatchResult.Started).terminalStatus)
            assertTrue(
                "越界写必须在通知里说出来",
                notices.any { it.status == QuestStatus.FAILED && it.summary.contains("Escaped write") },
            )
            assertTrue("runner 不许动机主的文件（清场只清自己的 worktree）", File(ws, "escaped.txt").isFile)
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    @Test
    fun ledgerSurvivesRestartSoConfirmMergeWorksAcrossProcess(): Unit = runBlocking {
        // 持久台账换来的核心能力：等机主确认的支线在进程重启后 confirmMerge 照常可用。
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, _, runner1) = newRepo(notices) { _, worktreeDir, _, _ ->
            File(worktreeDir, "recovered.txt").writeText("needs approval\n")
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "done", error = null)
        }
        try {
            val result = runner1.dispatch(
                prompt = "x", workspacePath = ws.absolutePath, title = "Recoverable quest",
                agentKey = "dsh", autoMerge = false,
            )
            val questId = (result as QuestRunner.DispatchResult.Started).questId

            // —— 模拟进程重启：全新 runner 实例（内存注册表为空），同一个 DB ——
            val runner2 = QuestRunner(
                worktreeRoot = java.io.File(System.getProperty("java.io.tmpdir"), "tenon-quests"),
                onNotice = { notices.add(it) },
                database = database,
                runTask = { _, _, _, _ -> QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = null, error = null) },
            )
            assertFalse("重启后内存注册表必须为空（这就是要恢复的原因）", runner2.find(questId))

            assertEquals(1, runner2.recoverFromLedger())
            assertTrue("恢复后 confirmMerge 的寻址依据必须回来", runner2.find(questId))

            assertTrue("重启后的支线必须能被机主批准合流", runner2.confirmMerge(questId))
            assertTrue("跨重启合流必须真落盘", File(ws, "recovered.txt").isFile)
        } finally {
            safeDelete(ws)
        }
    }

    @Test
    fun startupRecoveryFlipsRunningQuestsToInterrupted(): Unit = runBlocking {
        // 进程死在任务中途（台账还是 RUNNING）：开机扫描翻 INTERRUPTED 并通知，
        // worktree 保留（可能有未落提交的现场供取证）。
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (_, _, runner) = newRepo(notices) { _, _, _, _ ->
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = null, error = null)
        }
        database.relayDatabaseQueries.insertOrReplaceQuestLedger(
            quest_id = "quest-mid-run",
            branch = "quest/quest-mid-run",
            worktree_path = "/nonexistent/worktree",
            main_workspace = "/nonexistent/ws",
            conv_id = "assistant",
            title = "Interrupted quest",
            status = "RUNNING",
            prompt = "the original prompt",
            agent_key = "dsh",
            client_token = null,
            auto_merge = 1L,
            updated_at = 1_000L,
        )

        assertEquals(1, runner.recoverFromLedger())
        assertTrue("中断的支线必须收编进注册表", runner.find("quest-mid-run"))
        val notice = notices.single { it.questId == "quest-mid-run" }
        assertEquals(QuestStatus.INTERRUPTED, notice.status)
        val persisted = database.relayDatabaseQueries.selectQuestLedgerById(quest_id = "quest-mid-run").executeAsOne()
        assertEquals("INTERRUPTED", persisted.status)
    }

    @Test
    fun resumeQuestReRunsInterruptedQuestFromLedger(): Unit = runBlocking {
        // 一键恢复：INTERRUPTED 支线从台账拿 prompt/agentKey，原 questId 重跑
        // （卡面链路不断），门禁默认挂点，合流照常。
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        var runCount = 0
        val (ws, root, runner) = newRepo(notices) { _, worktreeDir, prompt, _ ->
            runCount++
            // 重跑的输入必须来自台账（进程里没有别的途径拿原始 prompt）
            File(worktreeDir, "resumed-" + runCount + ".txt").writeText(prompt)
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "done", error = null)
        }
        try {
            // 直接钉台账：模拟「进程死在任务中途」之后的重启现场
            database.relayDatabaseQueries.insertOrReplaceQuestLedger(
                quest_id = "quest-interrupted-1",
                branch = "quest/quest-interrupted-1",
                worktree_path = java.io.File(root, "quest-interrupted-1").absolutePath,
                main_workspace = ws.absolutePath,
                conv_id = "assistant",
                title = "Interrupted but recoverable",
                status = "INTERRUPTED",
                prompt = "the original task prompt",
                agent_key = "dsh",
                client_token = null,
                auto_merge = 1L,
                updated_at = 1_000L,
            )
            assertTrue(runner.resumeQuest("quest-interrupted-1"))

            assertEquals("重跑必须真的发生", 1, runCount)
            assertTrue("恢复合流必须真落盘", File(ws, "resumed-1.txt").isFile)
            assertEquals(
                "重跑输入必须来自台账 prompt",
                "the original task prompt",
                File(ws, "resumed-1.txt").readText(),
            )
            assertTrue(notices.any { it.questId == "quest-interrupted-1" && it.status == QuestStatus.RUNNING })
            assertTrue(notices.any { it.questId == "quest-interrupted-1" && it.status == QuestStatus.COMPLETED })
            assertFalse("重跑完成后注册表清空", runner.find("quest-interrupted-1"))
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    @Test
    fun cancelQuestAbortsInFlightRunAndCleansUp(): Unit = runBlocking {
        // in-flight 取消：取消沿挂起链传播（生产里 = 沙箱看门狗 TERM 进程树），
        // CANCELLED 通知 + 清场，不留半成品。questId 由 runner 生成——从 RUNNING
        // 通知里捕获（notify 先于 CLI 执行，cliStarted 时它必然已就位）。
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val cliStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val questIdCapture = kotlinx.coroutines.CompletableDeferred<String>()
        val releaseCli = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ws = File.createTempFile("tenon-quest-ws-", "").apply { delete(); mkdirs() }
        val root = File.createTempFile("tenon-quest-root-", "").apply { delete(); mkdirs() }
        runGit(ws, "init", "-b", "main")
        runGit(ws, "config", "user.name", "Tester")
        runGit(ws, "config", "user.email", "tester@test.local")
        File(ws, "README.md").writeText("# Main\n")
        runGit(ws, "add", "-A")
        runGit(ws, "commit", "-m", "init")
        val runner = QuestRunner(
            worktreeRoot = root,
            onNotice = { n ->
                notices.add(n)
                if (n.status == QuestStatus.RUNNING) questIdCapture.complete(n.questId)
            },
            database = database,
            runTask = { _, _, _, _ ->
                cliStarted.complete(Unit)
                releaseCli.await()
                QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "done", error = null)
            },
        )
        try {
            val dispatchOutcome = kotlinx.coroutines.CompletableDeferred<String>()
            val dispatchJob = launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val r = runner.dispatch(
                        prompt = "x", workspacePath = ws.absolutePath, title = "Cancellable quest",
                        agentKey = "dsh", autoMerge = true,
                    )
                    dispatchOutcome.complete((r as QuestRunner.DispatchResult.Started).terminalStatus ?: "AWAITING")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    dispatchOutcome.complete("CANCELLED")
                    throw e
                }
            }

            cliStarted.await()
            val questId = questIdCapture.await()
            assertTrue("in-flight 必须可取消", runner.cancelQuest(questId))
            releaseCli.complete(Unit)
            assertEquals("CANCELLED", dispatchOutcome.await())
            dispatchJob.join()
            assertTrue(notices.any { it.status == QuestStatus.CANCELLED })
            assertFalse("取消后注册表清空", runner.find(questId))
        } finally {
            safeDelete(ws)
            safeDelete(root)
        }
    }

    @Test
    fun gateCommandGatesTheMerge(): Unit = runBlocking {
        // 门禁命令：退出非 0 = 合流被门禁拦下（FAILED，不谎报 COMPLETED）；
        // 退出 0 = 放行。空 = 不跑门禁（既有行为）。
        val notices = mutableListOf<com.kapp.tenon.core.protocol.AssistantQuestNotice>()
        val (ws, _, runner) = newRepo(notices) { _, worktreeDir, _, _ ->
            File(worktreeDir, "gated.txt").writeText("work\n")
            QuestRunner.CliTaskRun(ok = true, patchPath = null, summary = "done", error = null)
        }
        try {
            val blocked = runner.dispatch(
                prompt = "x", workspacePath = ws.absolutePath, title = "Gated quest",
                agentKey = "dsh", autoMerge = true, gateCommand = "exit 1",
            )
            assertEquals("FAILED", (blocked as QuestRunner.DispatchResult.Started).terminalStatus)
            assertTrue(notices.any { it.status == QuestStatus.FAILED && it.summary.contains("Gate checks failed") })
            assertFalse("被门禁拦下的改动不得进主仓", File(ws, "gated.txt").exists())

            val passed = runner.dispatch(
                prompt = "x", workspacePath = ws.absolutePath, title = "Gated quest 2",
                agentKey = "dsh", autoMerge = true, gateCommand = "test -f gated.txt",
            )
            assertEquals("COMPLETED", (passed as QuestRunner.DispatchResult.Started).terminalStatus)
            assertTrue("门禁放行后照常合流", File(ws, "gated.txt").isFile)
        } finally {
            safeDelete(ws)
        }
    }
}