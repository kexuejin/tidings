// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 收割"有没有货"的判据（真机 cmvx822p7 第一棒整批丢改动的回归）。
 *
 * 事故形状：`parseStat` 当时只认单数 `"file changed"`，而 git 多文件时打的是
 * `3 files changed` —— `"files changed"` 里并不包含 `"file changed"` 子串，
 * 于是 3 个文件的真实改动被判成 `(0,0,0)` ⇒ 走「nothing to harvest」⇒ patch 被丢。
 * 单文件改动一直是好的，所以这个坑在单测与早期真机里都看不见，直到第一棒同时改了
 * provider/service/test 三个文件才暴露。
 *
 * 现在钉两件事：
 * 1. 判空看 **patch 本体**，统计口径（parseStat）没有权力决定丢不丢；
 * 2. `parseStat` / `countsFromPatch` 对单数、复数、乱码三种输入都给实数。
 */
class WorktreeHarvestCountTest {

    private fun tempDir(): File = Files.createTempDirectory("wt-harvest-count").toFile()

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
        runGit(dir, "config", "user.name", "Harvest Test")
        File(dir, "seed.txt").writeText("seed\n")
        runGit(dir, "add", ".")
        runGit(dir, "commit", "-m", "init")
    }

    private fun runGit(cwd: File?, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals(0, code, "git ${args.joinToString(" ")} 失败: $err")
    }

    // ── 1. 纯函数口径 ───────────────────────────────────────────────────────

    /** 单文件（git 用单数）—— 事故前唯一能走通的分支。 */
    @Test
    fun parseStatReadsTheSingularSummary() {
        val out = " src/a.js | 2 ++\n 1 file changed, 2 insertions(+)\n"
        assertEquals(Triple(1, 2, 0), WorktreeSandboxExecutor.parseStat(out))
    }

    /** 多文件（git 用复数）—— 正是这条被漏掉，导致 ≥2 文件的派单全被判无货。 */
    @Test
    fun parseStatReadsThePluralSummary() {
        val out = buildString {
            appendLine(" src/providers/weatherProvider.js | 16 +++++++++++++++-")
            appendLine(" src/services/weatherService.js   | 15 ++++++++++++---")
            appendLine(" test/weatherService.test.js      | 31 +++++++++++++++++++++++")
            append(" 3 files changed, 61 insertions(+), 4 deletions(-)\n")
        }
        assertEquals(Triple(3, 61, 4), WorktreeSandboxExecutor.parseStat(out))
    }

    /** 认不出来就如实返回零 —— 但这条零已经不再拥有"丢弃"的权力。 */
    @Test
    fun parseStatReturnsZerosForJunk() {
        assertEquals(Triple(0, 0, 0), WorktreeSandboxExecutor.parseStat(""))
        assertEquals(Triple(0, 0, 0), WorktreeSandboxExecutor.parseStat("fatal: ambiguous argument 'HEAD'"))
    }

    /** stat 认不出来时的兜底：从 unified diff 自数，不依赖任何本地化文案。 */
    @Test
    fun countsFromPatchCountsFilesAndLines() {
        val patch = buildString {
            appendLine("diff --git a/a.js b/a.js")
            appendLine("--- a/a.js")
            appendLine("+++ b/a.js")
            appendLine("@@ -1 +1,2 @@")
            appendLine("-old")
            appendLine("+new")
            appendLine("+more")
            appendLine("diff --git a/b.js b/b.js")
            appendLine("--- a/b.js")
            appendLine("+++ b/b.js")
            appendLine("@@ -0,0 +1 @@")
            append("+created\n")
        }
        assertEquals(Triple(2, 3, 1), WorktreeSandboxExecutor.countsFromPatch(patch))
    }

    // ── 2. 端到端：三个文件的改动必须落地成 harvest ─────────────────────────

    /**
     * 复刻事故现场：一次派单同时改 3 个文件。事故前这里 harvest 为 null（改动整批消失），
     * 修复后必须 filesChanged=3 且 patch 落盘。
     */
    @Test
    fun aThreeFileEditIsHarvestedInsteadOfBeingDropped() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(
            root,
            "cli",
            """
            printf 'export const provider = "live"\n' >> provider.js
            printf 'export const service = "query"\n' >> service.js
            printf 'test("caches", () => {})\n' >> service.test.js
            exit 0
            """.trimIndent(),
        )
        val outcome = WorktreeSandboxExecutor(sandboxRoot = root).run(
            taskId = "three",
            hostRepo = host.absolutePath,
            command = listOf(cli),
            env = emptyMap(),
            onLine = {},
        )
        assertTrue(outcome is WorktreeSandboxExecutor.Outcome.Finished, "退出码 0 = Finished，实际 $outcome")
        val harvest = requireNotNull((outcome as WorktreeSandboxExecutor.Outcome.Finished).harvest) {
            "3 个文件的改动被丢掉了 —— 判空又回到了「解析失败即无货」的老路"
        }
        assertEquals(3, harvest.filesChanged, "三个文件都要被计数：$harvest")
        assertEquals(3, harvest.additions)
        val patch = harvest.patchPath
        assertTrue(patch.isFile && patch.length() > 0L, "patch.diff 必须落盘且非空")
        val text = patch.readText()
        for (name in listOf("provider.js", "service.js", "service.test.js")) {
            assertTrue(text.contains(name), "patch 里必须含 $name")
        }
    }
}
