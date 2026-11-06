// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.TicketVerification

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收门（P4）的判据：**交了补丁不等于干完了**。
 *
 * 真机现场（2026-11-03）：注入一个恒失败的测试后派单，补丁只改了目标文件、
 * `npm test` 仍是红的，工单照样翻「待合并」—— 因为自愈的判据只看「有没有补丁」。
 * 这组用例把「补丁合格」变成一道真的门：同一基线上重放补丁、跑仓库测试，红了就拦。
 *
 * 四条纪律，缺一条门就形同虚设：
 * 1. 认出仓库的测试入口（`package.json.scripts.test`）；
 * 2. 绿 ⇒ 放行（null），红 ⇒ 拦下并带上**退出码与尾部输出**（下一轮提示词要用它）；
 * 3. 补丁对不上基线 ⇒ 单独说清「重放失败」，那和「测试红了」是两种不同的下一步；
 * 4. 验收自身跑不起来（没有 test 脚本 / 命令不在 PATH）⇒ **跳过**，不许把环境问题
 *    判成产物不合格（真机 PATH 缺 node 那次的教训）。
 */
class TicketVerificationTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun git(cwd: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val err = proc.errorStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "git ${args.joinToString(" ")} 失败: $err")
    }

    private fun makeRepo(dir: File) {
        dir.mkdirs()
        git(dir, "init")
        git(dir, "config", "user.email", "t@t.invalid")
        git(dir, "config", "user.name", "t")
        File(dir, "app.txt").writeText("line1\nline2\nline3\n")
        git(dir, "add", ".")
        git(dir, "commit", "-m", "init")
    }

    /** 在「沙箱视角」的克隆里制造改动，diff 出一份真补丁（与真机同形）。 */
    private fun makePatch(hostRepo: File, target: File): String {
        val clone = target.resolve("clone")
        git(target, "clone", "--quiet", hostRepo.absolutePath, clone.absolutePath)
        git(clone, "config", "user.email", "t@t.invalid")
        git(clone, "config", "user.name", "t")
        File(clone, "app.txt").writeText("line1\nCHANGED\nline3\n")
        git(clone, "commit", "-am", "sandbox change")
        val proc = ProcessBuilder(listOf("git", "format-patch", "HEAD~1", "--stdout"))
            .directory(clone).start()
        val diff = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val patch = target.resolve("patch.diff")
        patch.writeText(diff)
        return patch.absolutePath
    }

    @Test
    fun theTestCommandIsRecognisedFromPackageJson() {
        val repo = tempDir("verify-cmd")
        assertEquals(null, TicketVerification.testCommandFor(repo), "没有 package.json ⇒ 无入口")

        File(repo, "package.json").writeText("""{"name":"x","scripts":{"build":"tsc"}}""")
        assertEquals(null, TicketVerification.testCommandFor(repo), "没有 test 脚本 ⇒ 无入口")

        File(repo, "package.json").writeText("""{"name":"x","scripts":{"test":"node --test"}}""")
        assertEquals(
            listOf("npm", "test", "--silent"),
            TicketVerification.testCommandFor(repo),
            "有 test 脚本 ⇒ 跑 npm test",
        )
    }

    @Test
    fun aGreenSuitePassesAndARedOneIsStoppedWithItsOutput() {
        val repo = tempDir("verify-repo")
        makeRepo(repo)
        val patch = makePatch(repo, tempDir("verify-patch"))

        assertNull(
            TicketVerification.verify(repo.absolutePath, patch, command = listOf("true")),
            "绿的验收必须放行",
        )

        val red = TicketVerification.verify(
            repo.absolutePath,
            patch,
            command = listOf("sh", "-c", "echo boom-from-suite; exit 3"),
        )
        val failure = assertNotNull(red, "红的验收必须拦下 —— 否则红灯会原样带进工作区")
        assertTrue(failure.reason.contains("exited 3"), "要带退出码：${failure.reason}")
        assertTrue(failure.reason.contains("boom-from-suite"), "要带尾部输出：${failure.reason}")
    }

    // ------------------------------------------------- 报告面（Step 5：检查表上卡）

    @Test
    fun aGreenSuiteYieldsAChecklistRowAndPerItemEvidence() {
        val repo = tempDir("verify-report-green")
        makeRepo(repo)
        val patch = makePatch(repo, tempDir("verify-report-patch"))

        val report = TicketVerification.verifyReport(repo.absolutePath, patch, command = listOf("true"))
        assertEquals(
            "验收" to "done",
            assertNotNull(report.checklistRow(), "真跑过的验收要上卡"),
        )
        assertTrue(report.failure == null)
        assertTrue(
            report.items.all { it.passed && !it.skipped },
            "绿报告里不许混进失败或跳过项：${report.items}",
        )
        assertTrue(
            report.items.any { it.name.contains("仓库测试") },
            "检查项要能看出跑的是什么：${report.items.map { it.name }}",
        )
    }

    @Test
    fun aRedSuiteYieldsAFailureWhoseReasonIsTheLegacyString() {
        val repo = tempDir("verify-report-red")
        makeRepo(repo)
        val patch = makePatch(repo, tempDir("verify-report-patch2"))
        val cmd = listOf("sh", "-c", "echo boom-from-suite; exit 3")

        val viaVerify = assertNotNull(
            TicketVerification.verify(repo.absolutePath, patch, command = cmd),
            "门判据（旧入口）仍然要拦",
        )
        val report = TicketVerification.verifyReport(repo.absolutePath, patch, command = cmd)
        assertEquals(
            viaVerify.reason,
            assertNotNull(report.failure).reason,
            "门判据与报告面必须同源（reason 字符串逐字一致，自愈提示词靠它）",
        )
        assertEquals("验收" to "blocked", report.checklistRow())
    }

    @Test
    fun aSkippedVerificationIsRecordedButNeverShowsAsDone() {
        val repo = tempDir("verify-report-skip")
        makeRepo(repo) // 没有 package.json ⇒ 无测试入口
        val patch = makePatch(repo, tempDir("verify-report-patch3"))

        assertNull(TicketVerification.verify(repo.absolutePath, patch), "跳过 = 不适用，不是失败")
        val report = TicketVerification.verifyReport(repo.absolutePath, patch)
        assertEquals(null, report.checklistRow(), "全跳过的报告不许在卡上冒充「验过了」")
        assertTrue(report.items.all { it.skipped && it.passed }, "跳过项要如实标记：${report.items}")
        assertTrue(
            report.items.first().detail.orEmpty().contains("no test script"),
            "跳过原因要留痕：${report.items.first().detail}",
        )
    }

    @Test
    fun aPatchThatNoLongerReplaysIsSaidAsSuchAndTheHostRepoStaysClean() {
        val repo = tempDir("verify-conflict")
        makeRepo(repo)
        val patch = makePatch(repo, tempDir("verify-patch2"))
        // 把宿主仓改到补丁上下文对不上：这不是「测试红了」，是合并时才会炸的冲突。
        File(repo, "app.txt").writeText("line1\ndiverged\nline3\n")
        git(repo, "commit", "-am", "diverge")

        val failure = assertNotNull(
            TicketVerification.verify(repo.absolutePath, patch, command = listOf("true")),
            "重放不了必须如实说，不能当通过",
        )
        assertTrue(
            failure.reason.contains("replay the patch") || failure.reason.contains("apply the patch"),
            "原因要指向「补丁对不上」：${failure.reason}",
        )
        assertEquals(
            "line1\ndiverged\nline3\n",
            File(repo, "app.txt").readText(),
            "验收阶段不许动宿主工作区",
        )
    }
}
