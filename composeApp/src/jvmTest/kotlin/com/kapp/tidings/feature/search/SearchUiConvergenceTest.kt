// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.search

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「搜索结果区在手机与桌面只有一份实现」（AGENTS §2.6：内容共享、外壳参数化）。
 *
 * ## 真身
 *
 * 两个壳过去各写一套 loading / failed / empty / 三段结果，于是同一状态两端两种答案，
 * 而**没有任何量具会因此变红**：
 *  - 失败态：手机给「重试 + 清除」两个出口，桌面**只有清除**（想再试一次得删掉重打）；
 *  - 空态：手机 bodySmall+Bold / 桌面 titleSmall+SemiBold；
 *  - 加载态：手机顶部锚定 / 桌面垂直居中；
 *  - 结果行：桌面另写三份，只显示标题与时间。
 *
 * ## 判据
 *
 * 1. 两个壳都**调用** GlobalSearchResults；
 * 2. 三个状态的**文案 key** 只允许出现在共享文件里（壳里再出现 = 又长出一份）；
 * 3. 壳里不许再有自己那份命中行 / 状态实现（按函数名钉，能区分"调用共享实现"与"复制一份"）。
 *
 * ## 反向验证
 *
 * 把失败态那段 UI 放回任一壳 ⇒ stateCopiesAreGone 红；
 * 把壳里的 GlobalSearchResults( 调用删掉 ⇒ bothShellsRenderTheSharedResults 红。
 */
class SearchUiConvergenceTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun bothShellsRenderTheSharedResults() {
        // 判据是「这一行**本身就是**一次调用」，不是「文件里出现过这个标识符」：
        // `if (false) GlobalSearchResults(...)` 也能满足 contains，但它什么都不渲染
        // —— 那正是本仓反复踩过的「存在 ≠ 可达」假绿。
        val directCall = Regex("^[ \\t]*GlobalSearchResults\\(", RegexOption.MULTILINE)
        assertTrue(
            directCall.containsMatchIn(live(MOBILE)),
            "手机搜索屏没有真正调用共享结果区 —— 它又在自己画 loading/failed/empty",
        )
        assertTrue(
            directCall.containsMatchIn(live(DESKTOP)),
            "桌面搜索面板没有真正调用共享结果区 —— 两端会重新漂移",
        )
    }

    /**
     * 三个状态的文案 key 只许出现在共享文件里。
     *
     * 为什么用**文案 key** 而不是函数名：函数名可以换个名字再写一份，而"失败/加载/空结果
     * 这三句话"是用户真正看到的东西 —— 它们只要在壳里出现，就说明壳又自己实现了一遍。
     */
    @Test
    fun stateCopiesAreGone() {
        val ownedKeys = listOf(
            "global_search_failed",
            "global_search_loading",
            "global_search_empty",
            "global_search_empty_hint",
        )
        for (shell in listOf(MOBILE, DESKTOP)) {
            val src = live(shell)
            ownedKeys.forEach { key ->
                assertFalse(
                    src.contains(key),
                    "壳里又出现了「" + key + "」—— 这个状态应只在 GlobalSearchResults 里实现一次：" + shell,
                )
            }
        }
        // 共享文件必须真的拥有它们（否则上面两条会变成"两边都没有"的空断言）。
        val shared = live(SHARED)
        ownedKeys.forEach { key ->
            assertTrue(shared.contains(key), "共享结果区没有实现「" + key + "」这个状态")
        }
    }

    /** 壳里不许再有自己那份命中行（三段各一份是上一轮漂移的形状）。 */
    @Test
    fun hitRowsAreDefinedOnce() {
        for (shell in listOf(MOBILE, DESKTOP)) {
            val src = live(shell)
            listOf("ContactHitRow", "ChatHitRow", "FileHitRow", "GlobalSearchContactRow").forEach { row ->
                assertFalse(src.contains("fun " + row + "("), "壳里又定义了命中行 " + row + "：" + shell)
            }
        }
        val shared = live(SHARED)
        listOf("GlobalSearchContactRow", "GlobalSearchChatRow", "GlobalSearchFileRow").forEach { row ->
            assertTrue(shared.contains("fun " + row + "("), "共享结果区缺少 " + row + " —— 三段都该由它画")
        }
    }

    /**
     * 失败态必须有**两个**出口：重试同一个 query + 清除重输。
     *
     * 桌面过去只有清除 —— 那是把「再试一次」这条最自然的动作藏起来。
     */
    @Test
    fun failureOffersBothRetryAndClear() {
        val shared = live(SHARED)
        val failure = GuardSourceScan.bracedBlockAfter(shared, "private fun GlobalSearchFailed(")
        assertTrue(failure.contains("action_retry"), "失败态没有「重试」出口")
        assertTrue(failure.contains("action_clear_search"), "失败态没有「清除」出口")
    }

    /** 两个壳各自保留的外壳（输入框/筛选/快捷键）不该被顺手搬进共享层。 */
    @Test
    fun shellsKeepTheirOwnChrome() {
        assertTrue(live(MOBILE).contains("SearchTopBar("), "手机的顶部搜索框是外壳，应留在手机屏里")
        assertTrue(live(DESKTOP).contains("ic_search"), "桌面的搜索框是外壳，应留在桌面面板/页面里")
        // 共享结果区里不该出现任何一个壳的搜索框（那是外壳）。
        assertFalse(live(SHARED).contains("SearchTopBar("), "共享结果区里出现了手机的搜索框")
    }

    private companion object {
        const val MOBILE = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/search/GlobalSearchScreen.kt"
        const val SHARED = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/search/GlobalSearchResults.kt"
        const val DESKTOP = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopGlobalSearch.kt"
    }
}
