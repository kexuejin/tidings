// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.orchestration.TicketPatchLedger
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 卡片 → 补丁的**磁盘账簿**（D6 的兜底）：宿主重启后「合并此补丁」靠它找回产物。
 *
 * 钉五件事：
 * 1. 记了就能查回来（含会话键 —— 重启后翻面卡要靠它找回会话）；
 * 2. **产物没了就等于没记录**：账簿活得比 patch 久时不许把用户送到一次注定失败的合并；
 * 3. 同一张卡多次记账以最后一条为准（重跑/重试不改 cardId）；
 * 4. 含制表符/换行的路径**拒记** —— 写进去就是一行读不回来的脏账；
 * 5. 空 cardId 不产生记录。
 */
class TicketPatchLedgerTest {

    private fun tempRoot(): File = Files.createTempDirectory("ticket-ledger-").toFile()

    private fun patchFile(dir: File, name: String = "patch.diff"): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeText("diff --git a/x b/x\n")
        }

    @Test
    fun recordsAndReadsBackWithTheConversationKey() {
        val root = tempRoot()
        val ws = tempRoot()
        val patch = patchFile(tempRoot())
        TicketPatchLedger.record(
            cardId = "chain-tmw79h7vw",
            convKey = "25222a45-4b84-4e9a-9870-d13f86b2a8de",
            workspacePath = ws.absolutePath,
            patchPath = patch.absolutePath,
            atMs = 1_700_000_000_000,
            root = root,
        )
        val entry = assertNotNull(TicketPatchLedger.lookup("chain-tmw79h7vw", root))
        assertEquals("25222a45-4b84-4e9a-9870-d13f86b2a8de", entry.convKey, "会话键必须原样存回来")
        assertEquals(ws.absolutePath, entry.workspacePath)
        assertEquals(patch.absolutePath, entry.patchPath)
        assertEquals(1_700_000_000_000, entry.atMs)
    }

    @Test
    fun aVanishedPatchFileCountsAsNoRecord() {
        val root = tempRoot()
        val patch = patchFile(tempRoot())
        TicketPatchLedger.record(
            cardId = "chain-gone",
            convKey = "peer",
            workspacePath = tempRoot().absolutePath,
            patchPath = patch.absolutePath,
            root = root,
        )
        assertNotNull(TicketPatchLedger.lookup("chain-gone", root), "文件还在时应当查得到")
        patch.delete()
        assertNull(
            TicketPatchLedger.lookup("chain-gone", root),
            "补丁文件没了 ⇒ 与没记录同一个事实（否则用户点到一次注定失败的合并）",
        )
    }

    @Test
    fun theLastRecordForACardWins() {
        val root = tempRoot()
        val first = patchFile(tempRoot(), "p1.diff")
        val second = patchFile(tempRoot(), "p2.diff")
        TicketPatchLedger.record("chain-retry", "peer", "/ws/one", first.absolutePath, 1L, root)
        TicketPatchLedger.record("chain-retry", "peer", "/ws/two", second.absolutePath, 2L, root)
        val entry = assertNotNull(TicketPatchLedger.lookup("chain-retry", root))
        assertEquals("/ws/two", entry.workspacePath, "重跑后必须用最新那条（旧补丁可能已被清理）")
        assertEquals(2L, entry.atMs)
    }

    @Test
    fun pathsWithControlCharactersAreRefused() {
        val root = tempRoot()
        val patch = patchFile(tempRoot())
        TicketPatchLedger.record("chain-bad", "peer", "/ws/with\ttab", patch.absolutePath, 1L, root)
        assertNull(
            TicketPatchLedger.lookup("chain-bad", root),
            "含制表符的路径写进去会让整行读不回来 —— 宁可没有记录",
        )
        val ledger = TicketPatchLedger.file(root)
        assertTrue(!ledger.exists() || ledger.readText().isBlank(), "拒记时不该留下半行")
    }

    @Test
    fun blankCardIdIsIgnored() {
        val root = tempRoot()
        val patch = patchFile(tempRoot())
        TicketPatchLedger.record("  ", "peer", "/ws", patch.absolutePath, 1L, root)
        assertNull(TicketPatchLedger.lookup("  ", root))
        assertTrue(!TicketPatchLedger.file(root).exists(), "空 cardId 不该建出账簿文件")
    }
}
