// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File

/**
 * 链卡（`chain-<id>`）合并时，**这条链到底改的是哪个仓**——按仓库自己的 refs 回答。
 *
 * ## 为什么不能只问内存登记簿
 *
 * 合并分支原本的判据是 `groupForCard(cardId)`（发卡那一刻登记）→ `workspacePathForGroup`。
 * 宿主一重启，登记簿就空了；而对 1:1 会话连会话键都取不到
 * （`ConvId.groupIdOf(peerId)` 对私聊恒 null）。真机现场：
 * `patch merge FAILED … cannot resolve workspace for chain card chain-tmw79h7vw` ——
 * 仓里那条分支好端端躺着，宿主却说不知道往哪合。
 *
 * ## 判据取自磁盘，不取自记忆
 *
 * 链的每一棒都被 `landStage` 提交在 `chain/<id>` 分支上，worktree 收尾即删 ——
 * **分支是唯一存世形态**（见 [DesktopPatchMerge.applyBranch]）。所以「哪个仓拥有
 * 这条链」这个问题，仓库自己答得比任何内存表都准：`refs/heads/chain/<id>` 存在
 * 的那个仓就是它。
 *
 * ## 歧义不猜
 *
 * 0 个命中 ⇒ null（没有仓跑过这条链，交调用方如实报受阻）；
 * **≥2 个命中 ⇒ 也返回 null**，理由与 [DesktopWorkerOrchestrator.resolveWorkspacePath]
 * 里删掉「本机第一个工作区」兜底完全相同：往别人的仓库里写字是不可撤销的那类错，
 * 多候选时「选一个」就是掷骰子。
 */
object ChainWorkspaceResolve {

    /**
     * 在 [candidates] 里挑出唯一拥有 [branch] 的仓库路径。
     *
     * @param branch 完整分支名（`chain/<chainId>`，不是 cardId —— 前缀别搞混）
     * @param candidates 候选仓库路径（通常来自本机工作区表）；空串与非 git 目录会被跳过
     */
    fun workspaceOwning(branch: String, candidates: Collection<String>): String? {
        if (branch.isBlank()) return null
        val hits = candidates
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .filter { path -> hasBranch(path, branch) }
            .toList()
        return hits.singleOrNull()
    }

    /** `--verify --quiet` 以退出码回答「这个 ref 在不在」：不在时是 1，目录不是 git 仓也是非 0。 */
    private fun hasBranch(repoPath: String, branch: String): Boolean {
        if (!File(repoPath).isDirectory) return false
        return runCatching {
            val proc = ProcessBuilder(
                listOf("git", "-C", repoPath, "rev-parse", "--verify", "--quiet", "refs/heads/$branch"),
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitFor() == 0
        }.getOrDefault(false)
    }
}
