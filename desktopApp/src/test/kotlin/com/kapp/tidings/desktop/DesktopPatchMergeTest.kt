// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 审查合流（T-G1）执行件的全组用例：真 git + 真补丁（git 是构建链硬前置）。
 *
 * 钉三件事：
 * 1. 干净应用：上下文对得上 ⇒ Applied，patch 内容真实落进宿主工作树；
 * 2. 冲突 fail-closed：上下文对不上 ⇒ Conflict，**宿主工作树零变化**（--check 干跑段拦截）；
 * 3. 大声失败：patch 文件 / 工作区不存在 ⇒ Conflict 且信息指向真原因。
 */
class DesktopPatchMergeTest {

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

    private fun makePatch(hostRepo: File, target: File): String {
        // 在「沙箱视角」的克隆里制造改动，diff 出一份真补丁。
        git(target, "clone", "--quiet", hostRepo.absolutePath, target.resolve("clone").absolutePath)
        val clone = target.resolve("clone")
        git(clone, "config", "user.email", "t@t.invalid")
        git(clone, "config", "user.name", "t")
        File(clone, "app.txt").writeText("line1\nCHANGED\nline3\n")
        git(clone, "commit", "-am", "sandbox change")
        val proc = ProcessBuilder(listOf("git", "format-patch", "HEAD~1", "--stdout"))
            .directory(clone).start()
        val patch = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val patchFile = File(target, "patch.diff").apply { writeText(patch) }
        return patchFile.absolutePath
    }

    @Test
    fun cleanPatchAppliesIntoTheWorkspace() {
        val host = tempDir("merge-host")
        val scratch = tempDir("merge-scratch")
        makeRepo(host)
        val patchPath = makePatch(host, scratch)
        val outcome = DesktopPatchMerge.apply(patchPath = patchPath, workspacePath = host.absolutePath)
        assertEquals(DesktopPatchMerge.Outcome.Applied, outcome, "干净补丁应 Applied")
        assertEquals("line1\nCHANGED\nline3\n", File(host, "app.txt").readText(), "patch 内容真实落进宿主工作树")
    }

    @Test
    fun conflictingPatchLeavesTheWorkspaceUntouched() {
        val host = tempDir("merge-conflict")
        val scratch = tempDir("merge-conflict-scratch")
        makeRepo(host)
        val patchPath = makePatch(host, scratch)
        // 合流前宿主工作树先变了（同一行被机主改过）—— 补丁上下文对不上。
        File(host, "app.txt").writeText("line1\nHUMAN EDIT\nline3\n")
        val outcome = DesktopPatchMerge.apply(patchPath = patchPath, workspacePath = host.absolutePath)
        assertTrue(outcome is DesktopPatchMerge.Outcome.Conflict, "上下文冲突应 Conflict，实际 $outcome")
        assertEquals("line1\nHUMAN EDIT\nline3\n", File(host, "app.txt").readText(), "冲突路径宿主工作树零变化（fail-closed）")
    }

    @Test
    fun missingInputsFailLoudly() {
        val outcome = DesktopPatchMerge.apply(patchPath = "/nonexistent/patch.diff", workspacePath = "/tmp")
        assertTrue(outcome is DesktopPatchMerge.Outcome.Conflict, "patch 缺失 = Conflict")
        assertTrue((outcome as DesktopPatchMerge.Outcome.Conflict).detail.contains("missing"))
        val outcome2 = DesktopPatchMerge.apply(patchPath = "/tmp", workspacePath = "/nonexistent/ws")
        assertTrue(outcome2 is DesktopPatchMerge.Outcome.Conflict, "工作区缺失 = Conflict")
    }
}
