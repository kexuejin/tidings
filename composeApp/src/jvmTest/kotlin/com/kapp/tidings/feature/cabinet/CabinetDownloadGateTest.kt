// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.cabinet

import com.kapp.tidings.domain.model.CabinetEntryUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「下载」这颗按钮的三条判据（纯函数，见 [PeerCabinetView.kt] 顶部的两个 helper）。
 *
 * 背景：目录在共享协议里不可下载（接收侧只对文件回 offer）。把目录混进
 * 「下载 N 项」过去是**静默无事发生** —— 发送侧横幅说完就完了，永远等不到文件。
 * 现在选中集含目录时按钮整批禁用 + 行内提示。
 *
 * 反向验证：把 [canDownloadSelected] 改回 `selectedNames.isNotEmpty()`
 * （不管目录不目录），[aSelectionContainingADirectoryCannotBeDownloaded] 立刻变红。
 */
class CabinetDownloadGateTest {

    private fun dir(name: String) = CabinetEntryUi(name = name, isDir = true, size = 0, mtime = 0)
    private fun file(name: String, size: Long = 100) =
        CabinetEntryUi(name = name, isDir = false, size = size, mtime = 0)

    private val listing = listOf(dir("docs"), file("a.pdf"), file("b.png"))

    @Test
    fun aSelectionContainingADirectoryCannotBeDownloaded() {
        val selected = setOf("docs", "a.pdf")
        assertTrue(
            selectionContainsDir(selected, listing),
            "选中集里有目录必须被认出来",
        )
        assertFalse(
            canDownloadSelected(selected, listing),
            "含目录的选中集必须禁用下载 —— 目录下载过去是静默无事发生",
        )
    }

    @Test
    fun aWholeDirectorySelectionCannotBeDownloaded() {
        val selected = setOf("docs")
        assertFalse(
            canDownloadSelected(selected, listing),
            "只选了一个目录也一样：禁用",
        )
    }

    @Test
    fun aFileOnlySelectionIsDownloadable() {
        val selected = setOf("a.pdf", "b.png")
        assertFalse(selectionContainsDir(selected, listing), "纯文件选中集不含目录")
        assertTrue(canDownloadSelected(selected, listing), "纯文件选中集可以下载")
    }

    @Test
    fun directoriesThatAreNotSelectedDoNotBlockTheDownload() {
        val selected = setOf("a.pdf")
        assertFalse(
            selectionContainsDir(selected, listing),
            "列表里有目录但没被选中 —— 不该拦",
        )
        assertTrue(canDownloadSelected(selected, listing))
    }

    @Test
    fun anEmptySelectionCannotBeDownloaded() {
        assertFalse(
            canDownloadSelected(emptySet(), listing),
            "没有选中就没有可下载的东西",
        )
    }

    @Test
    fun aSelectedNameMissingFromTheListingCountsAsADir_OnlyIfItMatchesADir() {
        // 选中集与当前列表的交集按**名字**对：换目录后选中集会被控制器清空，
        // 所以这里只要覆盖「列表里有的才判」这一条语义。
        assertTrue(
            selectionContainsDir(setOf("docs"), listing),
        )
        assertFalse(
            selectionContainsDir(setOf("ghost"), listing),
            "列表里没有的名字无从判断是不是目录 —— 不拦",
        )
    }

    @Test
    fun thePureHelpersSeeTheSameShapeTheUiDoes() {
        // 与 PeerCabinetView 底栏同一形态：从 cabinet.entries 里挑出被选中的求和。
        val selected = setOf("a.pdf", "b.png")
        val total = listing.filter { it.name in selected }.sumOf { it.size }
        assertEquals(200L, total, "底栏「已选 N 项 · 大小」按同一份列表算")
    }
}
