// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * T-3b 沙箱化 CLI 驱动的路由判据（真 git + 假 CLI，沿本包惯例）：
 *
 * 1. **有仓库的会话走沙箱**：事件 = 行映射 + 恰好一张 diff-review 卡（有 diff 才出卡）
 *    + Finished 收尾；产物落盘；worktree 无痕；
 * 2. **空工作区走裸路径**：沙箱目录零创建（行为与 T-3a 逐字节一致的那一面）；
 * 3. **非 git 的工作区大声失败**：Error 指向沙箱，CLI **没有被执行**（静默回退到裸跑
 *    = 沙箱开关失效而不告诉你，D-024 不允许这条档位）；
 * 4. 装配：registrations 带 sandbox ⇒ 装出来的是 SandboxedCliDriver。
 */
class SandboxedCliDriverTest {

    private fun tempDir(): File = Files.createTempDirectory("sandboxed-driver-test").toFile()

    private fun script(dir: File, name: String, body: String): String {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\n$body\n")
        assertTrue(f.setExecutable(true), "假 CLI 必须可执行")
        return f.absolutePath
    }

    private fun gitRepo(dir: File) {
        dir.mkdirs()
        runGit(dir, "init")
        runGit(dir, "config", "user.email", "test@example.invalid")
        runGit(dir, "config", "user.name", "Sandboxed Driver Test")
        File(dir, "seed.txt").writeText("seed\n")
        runGit(dir, "add", ".")
        runGit(dir, "commit", "-m", "init")
    }

    private fun runGit(cwd: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val err = proc.errorStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "git ${args.joinToString(" ")} 失败: $err")
    }

    /** 从卡片 JSON 里取 cardId（断言「是不是同一张卡」用）。 */
    private fun cardIdOf(json: String): String? =
        Regex("\"cardId\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    private fun events(block: suspend (java.util.concurrent.LinkedBlockingQueue<DriverEvent>) -> Unit):
        List<DriverEvent> {
        val queue = java.util.concurrent.LinkedBlockingQueue<DriverEvent>()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Default)
            .launch {
                block(queue)
                done.complete(Unit)
            }
        runBlocking { kotlinx.coroutines.withTimeout(30_000) { done.await() } }
        return queue.toList()
    }

    @Test
    fun repoSessionRunsSandboxedAndEmitsOneDiffCard() {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(root, "cli", """
            printf '{"type":"text","text":"working"}\n'
            printf 'alpha\nbeta\n' >> feature.txt
            exit 0
        """.trimIndent())
        val sandbox = WorktreeSandboxExecutor(sandboxRoot = root)
        val driver = SandboxedCliDriver(
            driverId = "claude-cli",
            displayName = "Claude Code (CLI)",
            cliPath = cli,
            argSpec = { prompt -> listOf("-p", prompt) },
            sandbox = sandbox,
        )
        val collected = events { queue ->
            val session = driver.openSession(host.absolutePath, null)
            driver.executeTask(session.sessionId, "do things") { queue.put(it) }
        }
        val types = collected.map { it::class.simpleName }
        assertTrue(types.containsAll(listOf("TokenDelta", "Artifact", "Card", "Finished")), "四类事件都在：$types")
        assertEquals("Finished", types.last(), "Finished 收尾")
        val cards = collected.filterIsInstance<DriverEvent.Card>()
        // 两张卡事件 = **同一张卡的两个状态**：先受理（rev=1），后结论（rev=2）。
        // 派单后到结论之间必须已经有一张卡，否则用户在整个执行期间看不到任何东西
        // （真机 2026-11-01 18:47：派了活，群里连一张本轮任务卡都没有）。
        assertEquals(2, cards.size, "受理卡 + 结论卡，恰好两张事件")
        assertTrue(cards[0].cardJson.contains("\"state\":\"working\""), "第一张是受理卡：${cards[0].cardJson}")
        assertTrue(cards[1].cardJson.contains("\"state\":\"review_ready\""), "第二张是结论卡：${cards[1].cardJson}")
        assertEquals(
            cardIdOf(cards[0].cardJson),
            cardIdOf(cards[1].cardJson),
            "同一 cardId ⇒ 前端原位刷新，一次任务恒一张卡",
        )
        assertTrue(
            cards[0].cardJson.contains("\"actionId\":\"abort-task\""),
            "进行中必须给「停止」出口 —— 中止不该靠用户背 stop 这个词",
        )
        assertTrue(cards[0].cardJson.contains("\"rev\":1") && cards[1].cardJson.contains("\"rev\":2"), "rev 单调推进")
        // 产物事件先于卡片：桥按这个顺序「先收文件、再读说明」。
        assertEquals(types.lastIndexOf("Artifact"), types.lastIndexOf("Card") - 1, "Artifact 必须紧跟在结论卡之前")
        val artifact = collected.filterIsInstance<DriverEvent.Artifact>().single()
        assertEquals("patch.diff", artifact.name)
        assertEquals("text/x-diff", artifact.mime)
        assertEquals(64, artifact.sha256.length, "SHA256 指针进事件（对账用），路径只走进程内")
        assertTrue(artifact.path.contains("artifacts"), "path 指向沙箱外产物目录")
        val card = cards.last()
        assertTrue(card.cardJson.contains("\"type\":\"diff-review\""), "卡类型 = diff-review")
        assertTrue(card.cardJson.contains("\"additions\":2"), "提纯统计进卡面")
        assertTrue(card.cardJson.contains("patch.diff"), "产物条目进卡面（name/bytes/mime 展示元数据）")
        assertTrue(card.cardJson.contains("\"actionId\":\"merge-patch\""), "合流动作进卡（T-G1：审查闭环的最后一环）")
        assertTrue(!card.cardJson.contains(artifact.path), "本机路径绝不进协议卡（既有收口）")
        // summary 只放文件数：`+N −N` 由渲染侧按 additions/deletions 上色。
        // 两处都写 ⇒ 卡片显示「15 个文件 +1817 −449 +1817 −449」（真机 18:41 截图）。
        assertTrue(
            !card.cardJson.contains("+2") && !card.cardJson.contains("−2"),
            "summary 不得自带 +N −N：${card.cardJson}",
        )
        // 产物在、工作面无痕。
        assertTrue(
            File(root, "artifacts").list().orEmpty().any { it.startsWith("t-") },
            "沙箱产物目录已落盘",
        )
        assertTrue(gitLines(host, "branch", "--list", "task-*").isEmpty(), "任务分支无痕清理")
    }

    @Test
    fun blankWorkspaceDelegatesToThePlainPath() {
        val root = tempDir()
        val cli = script(root, "cli", "printf '{\"type\":\"text\",\"text\":\"plain\"}\\n'; exit 0")
        val sandbox = WorktreeSandboxExecutor(sandboxRoot = root)
        val driver = SandboxedCliDriver(
            driverId = "claude-cli",
            displayName = "Claude Code (CLI)",
            cliPath = cli,
            argSpec = { listOf(it) },
            sandbox = sandbox,
        )
        val collected = events { queue ->
            val session = driver.openSession("", null)
            driver.executeTask(session.sessionId, "no workspace task") { queue.put(it) }
        }
        val types = collected.map { it::class.simpleName }
        assertTrue("TokenDelta" in types, "裸路径的行事件照常：$types")
        assertTrue(types.last() == "Finished", "裸路径 Finished 收尾")
        assertTrue(
            File(root, "artifacts").list().orEmpty().isEmpty(),
            "空工作区任务不产生沙箱产物（根本没有沙箱）",
        )
    }

    @Test
    fun nonGitWorkspaceFailsLoudAndNeverRunsTheCli() {
        val root = tempDir()
        val notARepo = tempDir()
        val marker = File(root, "cli-ran.flag")
        val cli = script(root, "cli", "touch '${marker.absolutePath}'; exit 0")
        val sandbox = WorktreeSandboxExecutor(sandboxRoot = root)
        val driver = SandboxedCliDriver(
            driverId = "claude-cli",
            displayName = "Claude Code (CLI)",
            cliPath = cli,
            argSpec = { listOf(it) },
            sandbox = sandbox,
        )
        val collected = events { queue ->
            val session = driver.openSession(notARepo.absolutePath, null)
            driver.executeTask(session.sessionId, "should not run") { queue.put(it) }
        }
        val errors = collected.filterIsInstance<DriverEvent.Error>()
        assertEquals(1, errors.size, "恰好一条 Error：$collected")
        assertTrue(errors.single().message.contains("sandbox"), "失败信息指向真原因（沙箱建不出来）")
        assertTrue(!marker.exists(), "CLI 没有被执行 —— 非仓工作区不许静默裸跑")
    }

    /**
     * 中止必须产出 **state=aborted 的收尾卡**，而不是只丢一条 Error。
     *
     * 真机对照（2026-11-01 18:47–18:48）：用户在群里发 `stop` 后进程确实被摘掉了，
     * 但卡面没有任何变化、手机端还弹了一句「没有完成这次任务」—— 用户做的决定被
     * 报成了机器的故障。这里钉死两件事：① 中止有专属状态；② 受理卡一定被收尾，
     * 不会永远停在「进行中」。
     */
    @Test
    fun abortEndsTheRunningCardAsAborted() {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        // 忽略 SIGTERM：中止必须走到 SIGKILL 那一档，与真机上 codex 的表现一致。
        val cli = script(root, "cli", "trap '' TERM\nsleep 120")
        val sandbox = WorktreeSandboxExecutor(sandboxRoot = root)
        val driver = SandboxedCliDriver(
            driverId = "claude-cli",
            displayName = "Claude Code (CLI)",
            cliPath = cli,
            argSpec = { listOf("-p", it) },
            sandbox = sandbox,
        )
        val queue = java.util.concurrent.LinkedBlockingQueue<DriverEvent>()
        runBlocking {
            val session = driver.openSession(host.absolutePath, null)
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                driver.executeTask(session.sessionId, "long task") { queue.put(it) }
            }
            // 等受理卡出现（它必须在任务真正开跑时就发出来）。
            var sawWorking = false
            repeat(100) {
                if (sawWorking) return@repeat
                val ev = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (ev is DriverEvent.Card && ev.cardJson.contains("\"state\":\"working\"")) sawWorking = true
            }
            assertTrue(sawWorking, "任务开始就必须有受理卡，否则执行期间用户看到的是空白")
            // 受理卡比沙箱登记更早（卡先发、worktree 后建），要等真的跑起来再中止 ——
            // 否则测的是「空跑一次 abortAll」，什么也没证明。
            var waitRounds = 0
            while (waitRounds < 400 && driver.runningCount() == 0) {
                Thread.sleep(50)
                waitRounds++
            }
            assertEquals(1, driver.runningCount(), "沙箱任务已登记在跑")
            assertEquals(1, driver.abortAll(), "一个在跑的沙箱任务被摘掉")
            job.join()
        }
        val rest = generateSequence { queue.poll() }.toList()
        val aborted = rest.filterIsInstance<DriverEvent.Card>()
            .firstOrNull { it.cardJson.contains("\"state\":\"aborted\"") }
        assertNotNull(aborted, "中止要出 aborted 卡，而不是只丢一条 Error：$rest")
        assertTrue(
            aborted.cardJson.contains("\"cardId\":\"diff-"),
            "收尾卡与受理卡同一 cardId（原位刷新）：${aborted.cardJson}",
        )
        assertTrue(rest.any { it is DriverEvent.Error }, "终局事件仍要有（台账与 invoke 结果要靠它）")
        assertTrue(rest.none { it is DriverEvent.Finished }, "中止不发 Finished")
    }

    private fun gitLines(cwd: File, vararg args: String): List<String> {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out.lines().filter { it.isNotBlank() }
    }
}
