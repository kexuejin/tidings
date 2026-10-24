// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.search

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「桌面会话内历史搜索走同一个防抖 helper」。
 *
 * ## 真身
 *
 * 手机浮层（`HistorySearchOverlay`）已经改成静默 200ms 再查一次；桌面弹窗
 * （`DesktopHistorySearch`）仍然逐字直查 —— 同一句话打三个字就是三次全库扫。
 * 两个壳必须共用同一条静默纪律，否则下一次有人调静默期，桌面上那份不会跟着变。
 *
 * 行为本身由 `HistorySearchDebounceTest`（同一批）钉住 helper；本守卫钉**接线**：
 * 桌面弹窗的 effect 里必须是那个 helper，而不是一行同步 `onSearch`。
 *
 * ## 反向验证
 *
 * 把 `results = debouncedHistorySearch(...)` 改回 `results = onSearch(query, filter)`，
 * [desktopHistorySearchIsDebouncedThroughTheSharedHelper] 立刻变红。
 */
class DesktopHistorySearchDebounceTest {

    private val path = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopHistorySearch.kt"

    private fun src(): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun desktopHistorySearchIsDebouncedThroughTheSharedHelper() {
        val text = src()
        assertTrue(
            text.contains("debouncedHistorySearch(query, filter, search = onSearch)"),
            "桌面会话内搜索没有走共享的 debouncedHistorySearch —— 又变成每敲一个字扫一次全库",
        )
        assertFalse(
            text.contains("results = onSearch(query, filter)"),
            "桌面会话内搜索仍有一行同步直查（击键绕过静默期）",
        )
    }

    /** helper 的静默期只有一处定义：桌面不许自己写一个 delay 数字。 */
    @Test
    fun theDesktopDialogDoesNotInventItsOwnDelay() {
        assertFalse(
            src().contains("delay("),
            "桌面弹窗里出现了自己的 delay —— 静默期应只由 debouncedHistorySearch 决定",
        )
    }
}
