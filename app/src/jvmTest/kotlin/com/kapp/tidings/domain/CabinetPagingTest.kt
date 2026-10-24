// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.CabinetEntryUi
import com.kapp.tidings.domain.model.CabinetUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文件柜翻页：**追加**而不是替换。
 *
 * 现场：目录超过一页时服务端只下发前 100 项（`truncated=true`，总条数在 `total`）。
 * 上一版收到 `list.ok` 一律"替换"，于是点「加载更多」的结果是**前 100 行消失、
 * 只剩后一页** —— 用户以为文件丢了。
 *
 * 反向验证：把 [cabinetEntriesAfterPage] 的 `if (offset <= 0) page else …` 改成恒 `page`
 * （即回到"总是替换"），[secondPageAppendsInsteadOfReplacing] 与
 * [appendKeepsStableOrderAndDropsOverlap] 变红。
 */
class CabinetPagingTest {

    private fun entry(name: String, dir: Boolean = false) =
        CabinetEntryUi(name = name, isDir = dir, size = 1, mtime = 0)

    private fun firstPage() = CabinetUiState(
        activePeerId = "peer-1",
        currentPath = "",
        entries = listOf(entry("a.txt"), entry("b"), entry("c.txt")),
        totalEntries = 6,
        currentPerm = "read",
        loading = true,
    )

    @Test
    fun secondPageAppendsInsteadOfReplacing() {
        val prev = firstPage()
        val after = cabinetEntriesAfterPage(
            prev = prev,
            offset = 3,
            total = 6,
            perm = "read",
            page = listOf(entry("d.txt"), entry("e.txt"), entry("f.txt")),
        )
        assertEquals(
            listOf("a.txt", "b", "c.txt", "d.txt", "e.txt", "f.txt"),
            after.entries.map { it.name },
            "页 2 必须**追加**：替换会让用户以为前 100 个文件丢了",
        )
        assertEquals(6, after.totalEntries)
    }

    @Test
    fun firstPageReplaces() {
        val stale = firstPage().copy(entries = listOf(entry("old.txt")), totalEntries = 99)
        val after = cabinetEntriesAfterPage(
            prev = stale,
            offset = 0,
            total = 2,
            perm = "write",
            page = listOf(entry("x.txt"), entry("y.txt")),
        )
        assertEquals(listOf("x.txt", "y.txt"), after.entries.map { it.name }, "换目录/首屏：必须以这一页为准")
        assertEquals(2, after.totalEntries, "总数也要换成这一页的（旧目录的数字会污染截断提示）")
        assertEquals("write", after.currentPerm)
    }

    @Test
    fun appendKeepsStableOrderAndDropsOverlap() {
        // 两次请求之间对端新增/删除文件：页 2 的头部会与页 1 的尾部重叠。
        val prev = firstPage()
        val after = cabinetEntriesAfterPage(
            prev = prev,
            offset = 3,
            total = 6,
            perm = "read",
            page = listOf(entry("c.txt"), entry("d.txt")),
        )
        assertEquals(
            listOf("a.txt", "b", "c.txt", "d.txt"),
            after.entries.map { it.name },
            "重叠的那条只能出现一次，且既有顺序不被重排",
        )
    }

    @Test
    fun totalNeverShrinksBelowLoadedRows() {
        // 服务端报 2、但两页加起来已有 6 行：界面不能显示"共 2 项"却列 6 行。
        val prev = firstPage()
        val after = cabinetEntriesAfterPage(prev, offset = 3, total = 2, perm = "read", page = listOf(entry("d.txt")))
        assertEquals(4, after.entries.size)
        assertTrue(after.totalEntries >= after.entries.size)
    }

    @Test
    fun pageArrivalClearsLoadingAndError() {
        val prev = firstPage().copy(errorMessage = "timeout")
        val after = cabinetEntriesAfterPage(prev, offset = 3, total = 6, perm = "read", page = listOf(entry("d.txt")))
        assertTrue(!after.loading, "回执到了就不再转圈")
        assertNull(after.errorMessage, "拿到数据就该把上一次的错误抹掉，否则界面同时说「成功」和「失败」")
    }
}
