// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收门（[TicketVerification]）的判据测试。
 *
 * 验收门是「CLI 自述」与「工单翻面」之间唯一的宿主门 —— 这里钉四类判据：
 * 1. 测试入口优先级：package.json 的 test 脚本 > 生态探活（Gradle/Maven/Python/Rust/Go）> 优雅跳过；
 *    **不执行仓库内自声明命令**（`.tenon/verify.json` 可能被 Agent 写入 → 宿主 RCE 面）；
 * 2. [TicketVerification.Report] 的语义：第一个不合格项即 failure；全跳过既不是通过
 *    也不是失败，不许在卡上冒充「验过了」；
 * 3. 真 git 仓端到端：临时 worktree 重放补丁 + 跑命令的全链路判定；
 * 4. 环境问题不是产物问题：命令起不来按 skipped 处理，不把工单判死。
 *
 * 端到端用例起真 git 子进程（与 agentNode 的 worktree 用例同一口径）；
 * 命令用 `sh -c`，断言只看退出码与 detail 关键词，不赌时长。
 */
class TicketVerificationTest {

    // ---------- testCommandFor：测试入口优先级 ----------

    @Test
    fun packageJsonTestScriptWinsOverGradleWrapper() {
        val repo = tempDir("tenon-verify-npm-first")
        try {
            write(repo, "package.json", """{"scripts": {"test": "npm test"}}""")
            write(repo, "gradlew", "#!/bin/sh\n")

            assertEquals(
                listOf("npm", "test", "--silent"),
                TicketVerification.testCommandFor(repo),
                "package.json 的 test 脚本必须压过 gradlew",
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun packageJsonWithoutTestScriptFallsThroughToGradleWrapper() {
        val repo = tempDir("tenon-verify-npm-no-test")
        try {
            write(repo, "package.json", """{"scripts": {"build": "npm run build"}}""")
            write(repo, "gradlew", "#!/bin/sh\n")

            assertEquals(
                listOf("./gradlew", "test", "-q"),
                TicketVerification.testCommandFor(repo),
                "package.json 没声明 test 脚本时应继续探活 gradlew",
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun ecosystemProbesCoverGradleMavenPythonRustAndGo() {
        val cases = listOf(
            "gradlew" to listOf("./gradlew", "test", "-q"),
            "mvnw" to listOf("./mvnw", "test", "-q"),
            "pytest.ini" to listOf("pytest", "-q"),
            "pyproject.toml" to listOf("pytest", "-q"),
            "Cargo.toml" to listOf("cargo", "test", "--quiet"),
            "go.mod" to listOf("go", "test", "./..."),
        )
        for ((marker, expected) in cases) {
            val repo = tempDir("tenon-verify-eco")
            try {
                write(repo, marker, "")
                assertEquals(expected, TicketVerification.testCommandFor(repo), "生态探活应识别 " + marker)
            } finally {
                repo.deleteRecursively()
            }
        }
    }

    @Test
    fun repoWithoutAnyTestEntryReturnsNull() {
        val repo = tempDir("tenon-verify-none")
        try {
            assertNull(
                TicketVerification.testCommandFor(repo),
                "没有任何可识别测试入口的仓必须返回 null（优雅跳过，不是失败）",
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    // ---------- Report：纯语义 ----------

    @Test
    fun firstFailingItemIsTheFailureWithItsEvidenceAsReason() {
        val report = TicketVerification.Report(
            listOf(
                TicketVerification.Item("重放补丁", passed = true),
                TicketVerification.Item("仓库测试", passed = false, detail = "exit 3 — boom"),
                TicketVerification.Item("后面的失败项", passed = false, detail = "不会被取到"),
            ),
        )
        val failure = report.failure
        assertNotNull(failure, "有不合格项时必须给出 failure")
        assertEquals("exit 3 — boom", failure.reason, "failure 的 reason 必须是第一不合格项的证据，不是结论")
        assertEquals("blocked", report.checklistRow()?.second, "有不合格项时检查行必须是 blocked")
    }

    @Test
    fun allSkippedReportIsNeitherDoneNorBlocked() {
        val allSkipped = TicketVerification.Report(
            listOf(
                TicketVerification.Item("验收", passed = true, detail = "skipped: no test script", skipped = true),
            ),
        )
        assertNull(allSkipped.failure, "全跳过不是失败 —— 环境问题不判工单死")
        assertNull(allSkipped.checklistRow(), "全跳过不得在卡上冒充「验过了」")

        val mixed = TicketVerification.Report(
            listOf(
                TicketVerification.Item("验收", passed = true, detail = "skipped: x", skipped = true),
                TicketVerification.Item("仓库测试", passed = true),
            ),
        )
        assertEquals("done", mixed.checklistRow()?.second, "跳过项与真跑通过的项并存时按 done 计")
    }

    // ---------- verifyReport：真 git 仓端到端 ----------

    @Test
    fun passingPatchAndCommandProduceADoneChecklist() {
        val repo = newCommittedRepo("tenon-verify-pass")
        try {
            write(repo, "src/main.kt", "val answer = 42\n")
            val patch = writeUncommittedDiffAsPatch(repo, "pass.patch")

            val report = TicketVerification.verifyReport(
                repoPath = repo.absolutePath,
                patchPath = patch.absolutePath,
                command = listOf("sh", "-c", "exit 0"),
            )

            assertNull(report.failure, "补丁可重放 + 测试退出 0 = 通过，实际：" + report.items)
            assertEquals("done", report.checklistRow()?.second)
            assertEquals(2, report.items.size, "通过报告应有重放与测试两项")
            assertTrue(report.items[0].passed, "重放项必须先通过")
            assertTrue(report.items[1].passed && !report.items[1].skipped, "测试项必须是真跑通过的")
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun failingTestCommandLeavesEvidenceWithExitCode() {
        val repo = newCommittedRepo("tenon-verify-fail")
        try {
            write(repo, "src/main.kt", "val answer = 42\n")
            val patch = writeUncommittedDiffAsPatch(repo, "fail.patch")

            val report = TicketVerification.verifyReport(
                repoPath = repo.absolutePath,
                patchPath = patch.absolutePath,
                command = listOf("sh", "-c", "echo expected-boom; exit 3"),
            )

            val failure = report.failure
            assertNotNull(failure, "测试退出非 0 必须判本轮失败")
            assertTrue(
                failure.reason.contains("exited 3"),
                "失败证据必须带退出码，实际：" + failure.reason,
            )
            assertTrue(failure.reason.contains("expected-boom"), "失败证据必须带输出尾部")
            assertTrue(report.items[0].passed, "补丁本身能重放 —— 失败的是测试项，不是重放项")
            assertEquals("blocked", report.checklistRow()?.second)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun patchThatNoLongerAppliesIsReportedAsReplayFailure() {
        val repo = newCommittedRepo("tenon-verify-conflict")
        try {
            // 基线是 "hello"；conflict.patch 声称把 "hello" 改成 "hello world"，
            // 但基线推进后 HEAD 里已经是 "hello world" —— 补丁对不上基线。
            write(repo, "file.txt", "hello\n")
            git(repo, "add", "-A")
            git(repo, "commit", "-m", "base")
            write(repo, "file.txt", "hello world\n")
            val conflictPatch = File(repo, "conflict.patch").apply { writeText(git(repo, "diff")) }
            git(repo, "add", "-A")
            git(repo, "commit", "-m", "advance base")
            write(repo, "file.txt", "hello world\nmore\n")
            val goodPatch = File(repo, "good.patch").apply { writeText(git(repo, "diff")) }
            assertTrue(conflictPatch.length() > 0 && goodPatch.length() > 0, "两份补丁导出不能为空")

            val conflicted = TicketVerification.verifyReport(
                repoPath = repo.absolutePath,
                patchPath = conflictPatch.absolutePath,
                command = listOf("sh", "-c", "exit 0"),
            )
            val failure = conflicted.failure
            assertNotNull(failure, "对不上基线的补丁必须提前在验收门暴露")
            assertTrue(
                failure.reason.contains("could not replay the patch"),
                "重放失败要说清是对不上基线，实际：" + failure.reason,
            )

            val good = TicketVerification.verifyReport(
                repoPath = repo.absolutePath,
                patchPath = goodPatch.absolutePath,
                command = listOf("sh", "-c", "exit 0"),
            )
            assertNull(good.failure, "同一基线上可应用的补丁必须能过")
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun missingRepoOrPatchOrTestEntryAreSkippedNotFailed() {
        val repo = newCommittedRepo("tenon-verify-skip")
        try {
            val missingRepo = TicketVerification.verifyReport("/nonexistent/tenon-verify-repo", "irrelevant.patch")
            assertSingleSkip(missingRepo, "repo missing")

            val missingPatch = TicketVerification.verifyReport(repo.absolutePath, "/nonexistent/tenon-verify.patch")
            assertSingleSkip(missingPatch, "patch missing")

            // 「无测试入口」的探活发生在补丁存在性之后 —— 补丁文件必须真实存在
            val dummyPatch = File(repo, "dummy.patch").apply { writeText("dummy\n") }
            val noScript = TicketVerification.verifyReport(repo.absolutePath, dummyPatch.absolutePath)
            assertSingleSkip(noScript, "no test script")
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun commandThatCannotStartIsSkippedNotJudgedAsProductFailure() {
        val repo = newCommittedRepo("tenon-verify-nostart")
        try {
            write(repo, "src/main.kt", "val answer = 42\n")
            val patch = writeUncommittedDiffAsPatch(repo, "nostart.patch")

            val report = TicketVerification.verifyReport(
                repoPath = repo.absolutePath,
                patchPath = patch.absolutePath,
                command = listOf("tenon-no-such-binary-xyz"),
            )

            assertSingleSkip(report, "could not start")
        } finally {
            repo.deleteRecursively()
        }
    }

    // ---------- 夹具 ----------

    private fun assertSingleSkip(report: TicketVerification.Report, expectedReasonPart: String) {
        assertNull(report.failure, "环境性跳过不是失败：" + report.items)
        assertNull(report.checklistRow(), "跳过项不得上卡冒充通过")
        assertEquals(1, report.items.size, "跳过报告应只有一条留痕项")
        val item = report.items.single()
        assertTrue(item.skipped, "留痕项必须标记 skipped")
        assertTrue(item.passed, "留痕项不参与门判定（passed=true 才不会被 failure 取到）")
        assertTrue(
            item.detail.orEmpty().contains(expectedReasonPart),
            "跳过必须留痕说明原因（期望含「" + expectedReasonPart + "」），实际：" + item.detail,
        )
    }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), prefix + "-" + System.nanoTime()).apply { mkdirs() }

    private fun write(repo: File, relPath: String, content: String) {
        val f = File(repo, relPath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    /** 把工作树里未提交的改动（含新文件）导出成补丁文件（放在仓内，随夹具一起清理）。 */
    private fun writeUncommittedDiffAsPatch(repo: File, patchName: String): File {
        git(repo, "add", "-A")
        val patch = File(repo, patchName)
        patch.writeText(git(repo, "diff", "--cached"))
        assertTrue(patch.length() > 0, "补丁导出不能为空（" + patchName + "）")
        return patch
    }

    /** 一个有首提交、身份齐全的最小 git 仓（baseFor 无快照时回落 HEAD，无需打快照）。 */
    private fun newCommittedRepo(prefix: String): File {
        val repo = tempDir(prefix)
        git(repo, "init")
        git(repo, "config", "user.email", "test@tenon.local")
        git(repo, "config", "user.name", "TenonTest")
        write(repo, "README.md", "# probe\n")
        git(repo, "add", ".")
        git(repo, "commit", "-m", "init")
        return repo
    }

    private fun git(repo: File, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args.toList())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        assertTrue(proc.exitValue() == 0, "git " + args.joinToString(" ") + " 失败：" + out.takeLast(300))
        return out
    }
}
