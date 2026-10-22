// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.search

import app.relay.app.GlobalSearchResult
import app.relay.app.GuardSourceScan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守卫「全局搜索不再逐字打一次全库」这条纪律。
 *
 * 为什么需要它：`onSearch` 是同步读内存表，改回同步执行不会有任何报错、界面也照常出结果 ——
 * 只是每敲一个字都跑一次全库检索。这是「测试绿 ≠ 测到了」那一族，必须由行为断言钉住。
 *
 * 反向验证：把 [debouncedGlobalSearch] 里的 `delay(debounceMillis)` 删掉（退回同步执行），
 * [rapidKeystrokesSearchTheWholeStoreOnlyOnce] 立刻变红（第一次击键会当场落地，
 * 于是 `searched` 变成 ["协", "协议"] 而不是 ["协议"]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchDebounceTest {

    @Test
    fun rapidKeystrokesSearchTheWholeStoreOnlyOnce() = runTest {
        val searched = mutableListOf<String>()
        var pending: Job? = null
        // 模拟连续两次击键：每次新击键都取消上一个还没过静默期的检索（LaunchedEffect 的行为）。
        for (q in listOf("协", "协议")) {
            pending?.cancel()
            pending = launch {
                debouncedGlobalSearch(q, debounceMillis = 200) { query ->
                    searched += query
                    GlobalSearchResult(query = query)
                }
            }
            advanceTimeBy(50)
        }
        advanceUntilIdle()
        assertEquals(
            listOf("协议"),
            searched,
            "200ms 静默期内连续两次击键只该落一次检索；落在两次说明又退回逐字全库搜索了",
        )
    }

    @Test
    fun aThrowingSearchIsReportedAsFailedInsteadOfCrashing() = runTest {
        val outcome = debouncedGlobalSearch("协议", debounceMillis = 200) {
            throw IllegalStateException("search blew up")
        }
        assertEquals(
            GlobalSearchOutcome.Failed,
            outcome,
            "检索抛错必须折成 Failed 态（界面据此画失败说明），而不是把异常抛穿",
        )
    }

    @Test
    fun aBlankQueryNeverRunsASearch() = runTest {
        var calls = 0
        val outcome = debouncedGlobalSearch("   ", debounceMillis = 200) {
            calls++
            GlobalSearchResult(query = it)
        }
        assertEquals(GlobalSearchOutcome.Idle, outcome, "空关键词是初始态，不是一次检索")
        assertEquals(0, calls, "空关键词一次检索都不该发")
    }

    @Test
    fun aCompletedSearchCarriesItsHits() = runTest {
        val outcome = debouncedGlobalSearch("协议", debounceMillis = 200) {
            GlobalSearchResult(query = it)
        }
        assertTrue(outcome is GlobalSearchOutcome.Done, "正常检索必须落成 Done")
        assertEquals("协议", (outcome as GlobalSearchOutcome.Done).result.query)
    }

    /**
     * 守卫**生产接线**：界面真的走 debounce，而不是每敲一个字直接查全库。
     *
     * 上面四条只测 [debouncedGlobalSearch] 这个 helper —— 有人把界面改回同步
     * `remember(query) { onSearch(query) }`，helper 还在、测试仍全绿。这条补上那个缺口。
     *
     * 反向验证：把界面改回同步检索 → 本测试红（调用点 regex 不再匹配，
     * 裸 onSearch(...) 也可能不止静态帧接缝那一处）。
     */
    @Test
    fun theScreenActuallyDebouncesInsteadOfSearchingOnEveryKeystroke() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(SEARCH_SCREEN))
        // 必须是**调用点**（把实时 query 交进去），不是文件末尾那个 helper 定义：
        // `contains("debouncedGlobalSearch(")` 会被定义本身满足，守不住接线。
        assertTrue(
            Regex("debouncedGlobalSearch\\s*\\(\\s*query\\b").containsMatchIn(live),
            "全局搜索屏没有把实时 query 交给 debouncedGlobalSearch：它又变成每敲一个字直接查全库了",
        )
        // 静态帧接缝那一次同步检索是允许的（截图门禁要当帧出结果）；
        // 除此之外不许再有裸的 onSearch(...) 调用。
        val directSearches = Regex("onSearch\\s*\\(").findAll(live).count()
        assertEquals(
            1,
            directSearches,
            "除了 initialQuery 静态帧接缝，界面里不该再有裸的 onSearch(...)：" +
                "那意味着击键绕过 debounce 直接打全库",
        )
        // 静态帧接缝那次调用现在**带上筛选范围**（默认全部，见 GlobalSearchScope）。
        // 守卫跟着改成认这个形态 —— 要点没变：同步检索只允许出现在接缝里，且用的必须是
        // `initialQuery`（不是实时 query），否则它就成了绕过 debounce 的后门。
        assertTrue(
            live.contains("onSearch(initialQuery, GlobalSearchScope.All)"),
            "唯一允许的那次同步检索必须是静态帧接缝，且用 initialQuery 而不是实时 query",
        )
        assertTrue(live.contains("global_search_loading"), "搜索中那一句必须画出来")
        assertTrue(live.contains("global_search_failed"), "搜索失败那一句必须画出来")
    }

    private companion object {
        const val SEARCH_SCREEN =
            "composeApp/src/commonMain/kotlin/app/relay/feature/search/GlobalSearchScreen.kt"
    }
}
