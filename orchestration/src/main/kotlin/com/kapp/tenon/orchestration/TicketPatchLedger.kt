// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.WorktreeSandboxExecutor
import java.io.File

/**
 * **卡片 → 沙箱补丁**的磁盘账簿：只为一件事存在 —— 宿主重启后，「合并此补丁」还点得动。
 *
 * ## 为什么内存登记簿不够
 *
 * [DesktopWorkerOrchestrator.patchByCard] 是发卡那一刻的账。真机现场：宿主重启后点上一轮
 * 留下的工单卡，得到
 * `patch merge FAILED … cannot resolve workspace for chain card chain-tmw79h7vw` ——
 * 补丁文件还在 `/tmp/tenon-ws/artifacts/t-1-80af6774/patch.diff` 里躺着，
 * 而**知道它在哪的只有那份内存表**。
 *
 * ## 为什么账簿放在沙箱根下
 *
 * 这是本设计唯一需要解释的决定：账簿写在 `<sandboxRoot>/ticket-patches.tsv`，
 * 而不是应用数据目录。
 *
 * 理由是**寿命必须对齐**：账簿指向的是磁盘上的 patch 文件，`/tmp` 被清理时产物
 * 已经不在了 —— 一本活得更久的账簿只会指向空气（还会让「账簿有记录」被误读成
 * 「补丁还可用」）。放在同一片目录下，两者的生死由同一个事实决定，不需要
 * 「过期清理」这种第二套机制，也不需要把数据目录路径一路穿到编排层。
 *
 * 沙箱根本身是 env 可覆盖的（[WorktreeSandboxExecutor.ENV_SANDBOX_ROOT]），所以这条路径
 * 跟着既有约定走，不新增配置项。
 *
 * ## 形态：追加式 TSV
 *
 * 一行一次发卡：`cardId \t convKey \t workspacePath \t patchPath \t atMs`。
 * 只追加、不重写（同一张卡多条时以**最后一条仍指向存在文件的**为准），
 * 因为这里的失败模式是「崩溃在写一半」—— 追加式最坏丢最后一行，重写式最坏整本没了。
 * 路径含空格不会出错（字段分隔是制表符，不是空格）；含制表符/换行的路径直接拒记
 * （那说明路径本身不正常，宁可没有记录，也不写一行读不回来的脏账）。
 */
object TicketPatchLedger {

    /** 账簿的一条记录。`convKey` 是会话键（群 `group:<id>` / 1:1 对端 nodeId）。 */
    data class Entry(
        val cardId: String,
        val convKey: String,
        val workspacePath: String,
        val patchPath: String,
        val atMs: Long,
    )

    private const val FILE_NAME = "ticket-patches.tsv"

    /**
     * 账簿文件（沙箱根下，与产物同寿命）。
     *
     * [root] 有默认值只为可测：生产调用一律不传，跟着 [WorktreeSandboxExecutor.defaultRoot] 走
     * （它自己认 `TENON_SANDBOX_ROOT`）；用例传临时目录，才不会往真机上写脏账。
     */
    fun file(root: File = WorktreeSandboxExecutor.defaultRoot()): File = File(root, FILE_NAME)

    /**
     * 记一笔。**失败只留痕不抛** —— 记不上账的后果是「重启后合不了」，
     * 而不是「这次派单失败」；把异常抛回发卡路径等于用一个小毛病换一次不派单。
     */
    fun record(
        cardId: String,
        convKey: String?,
        workspacePath: String,
        patchPath: String,
        atMs: Long = System.currentTimeMillis(),
        root: File = WorktreeSandboxExecutor.defaultRoot(),
    ) {
        if (cardId.isBlank() || workspacePath.isBlank() || patchPath.isBlank()) return
        if (listOf(cardId, convKey.orEmpty(), workspacePath, patchPath).any { it.contains('\t') || it.contains('\n') }) {
            RelayLog.info("RELAY-INVOKE", "ticket patch ledger: refusing record with control chars (card $cardId)")
            return
        }
        runCatching {
            val f = file(root)
            f.parentFile?.mkdirs()
            f.appendText(
                listOf(cardId, convKey.orEmpty(), workspacePath, patchPath, atMs.toString()).joinToString("\t") + "\n",
            )
        }.onFailure { t ->
            RelayLog.info(
                "RELAY-INVOKE",
                "ticket patch ledger write FAILED (card $cardId): " + (t.message ?: t::class.simpleName),
            )
        }
    }

    /**
     * 查这张卡的补丁。**只认仍存在的 patch 文件** —— 账簿里有记录但文件没了，
     * 与没有记录是同一个事实（补丁不可用），返回 null 让调用方如实报受阻，
     * 不要让用户点到一次注定失败的合并。
     */
    fun lookup(cardId: String, root: File = WorktreeSandboxExecutor.defaultRoot()): Entry? {
        if (cardId.isBlank()) return null
        val f = file(root)
        if (!f.isFile) return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        return lines.asReversed()
            .asSequence()
            .mapNotNull { parse(it) }
            .firstOrNull { it.cardId == cardId && File(it.patchPath).isFile }
    }

    private fun parse(line: String): Entry? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 5) return null
        val at = parts[4].toLongOrNull() ?: return null
        return Entry(
            cardId = parts[0],
            convKey = parts[1],
            workspacePath = parts[2],
            patchPath = parts[3],
            atMs = at,
        )
    }
}
