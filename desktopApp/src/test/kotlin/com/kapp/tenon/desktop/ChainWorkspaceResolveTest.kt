// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.orchestration.ChainWorkspaceResolve
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 链卡合并的**重启兜底**判据：谁拥有 `chain/<id>` 分支，谁就是这条链改过的仓。
 *
 * 钉四件事（全部真 git，git 是构建链硬前置）：
 * 1. 唯一命中 ⇒ 选它（真机现场：重启后登记簿为空、分支还在仓里）；
 * 2. **零命中 ⇒ null**：没有仓跑过这条链时不许随便指一个；
 * 3. **多命中 ⇒ 也 null**：两个仓都有同名分支时「选一个」就是掷骰子，而往错的仓里
 *    写工作树是不可撤销的错 —— 与 `resolveWorkspacePath` 删掉「第一个工作区」
 *    兜底同一条纪律；
 * 4. 坏路径（不存在 / 不是 git 仓）只跳过、不抛 —— 判据函数不许把一节坏数据变成
 *    一次「点了没反应」。
 */
class ChainWorkspaceResolveTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun git(cwd: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val err = proc.errorStream.bufferedReader().readText()
        proc.waitFor()
        assertEquals(0, proc.exitValue(), "git ${args.joinToString(" ")} 失败: $err")
    }

    /** 造一个真仓；[branch] 非空时再在它上面建一条空提交的分支。 */
    private fun makeRepo(branch: String? = null): File {
        val dir = tempDir("chain-resolve-")
        git(dir, "init", "-b", "main")  // 显式定初始分支：runner 的 git 默认还是 master（真机走查抓不到、CI 首跑抓到）
        git(dir, "config", "user.email", "t@t.invalid")
        git(dir, "config", "user.name", "t")
        File(dir, "README.md").writeText("base\n")
        git(dir, "add", ".")
        git(dir, "commit", "-m", "init")
        if (branch != null) {
            git(dir, "checkout", "-b", branch)
            File(dir, "stage.txt").writeText("stage\n")
            git(dir, "add", ".")
            git(dir, "commit", "-m", "stage")
            git(dir, "checkout", "main")
        }
        return dir
    }

    @Test
    fun picksTheOnlyRepoThatOwnsTheChainBranch() {
        val owner = makeRepo(branch = "chain/tmw79h7vw")
        val bystander = makeRepo()
        val picked = ChainWorkspaceResolve.workspaceOwning(
            "chain/tmw79h7vw",
            listOf(bystander.absolutePath, owner.absolutePath),
        )
        assertEquals(owner.absolutePath, picked, "分支只在一个仓里 ⇒ 必须选它（重启兜底的判据就是它）")
    }

    @Test
    fun noOwnerResolvesToNothing() {
        val a = makeRepo()
        val b = makeRepo()
        assertNull(
            ChainWorkspaceResolve.workspaceOwning("chain/never-ran", listOf(a.absolutePath, b.absolutePath)),
            "没有任何仓拥有这条分支时必须如实返回 null，不许指一个仓去合",
        )
    }

    @Test
    fun twoOwnersAreAmbiguousAndRefused() {
        val a = makeRepo(branch = "chain/dup")
        val b = makeRepo(branch = "chain/dup")
        assertNull(
            ChainWorkspaceResolve.workspaceOwning("chain/dup", listOf(a.absolutePath, b.absolutePath)),
            "两个仓都有同名分支 ⇒ 歧义不猜（往错的仓写工作树不可撤销）",
        )
    }

    @Test
    fun brokenCandidatesAreSkippedNotFatal() {
        val owner = makeRepo(branch = "chain/x")
        val notARepo = tempDir("chain-resolve-plain-")
        val picked = ChainWorkspaceResolve.workspaceOwning(
            "chain/x",
            listOf(
                "",
                "/no/such/path/for/chain-resolve",
                notARepo.absolutePath,
                owner.absolutePath,
            ),
        )
        assertEquals(owner.absolutePath, picked, "空串 / 不存在 / 非 git 目录都只跳过，不影响真命中的那个")
    }

    @Test
    fun blankBranchResolvesToNothing() {
        val owner = makeRepo(branch = "chain/x")
        assertNull(
            ChainWorkspaceResolve.workspaceOwning("   ", listOf(owner.absolutePath)),
            "空分支名不该去问 git（会退化成「随便问问」），直接 null",
        )
    }
}
