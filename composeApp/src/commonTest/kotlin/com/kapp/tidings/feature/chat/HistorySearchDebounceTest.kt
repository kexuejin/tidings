// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.HistorySearchFilter
import com.kapp.tidings.domain.MessageSearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 会话内历史搜索「静默后再查」的单测。
 *
 * 真身：浮层里 `LaunchedEffect(query, …)` 逐字直查，每敲一个字符就把当次检索跑一遍。
 * 与全局搜索（GlobalSearchDebounceTest）同一条纪律的会话内版本。
 *
 * 反向验证：把 [debouncedHistorySearch] 里的 `delay(debounceMillis)` 删掉（退回同步直查），
 * [rapidKeystrokesOnlyRunOneSearch] 立刻变红（第一次击键当场落地）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistorySearchDebounceTest {

    @Test
    fun rapidKeystrokesOnlyRunOneSearch() = runTest {
        val searched = mutableListOf<String>()
        var pending: Job? = null
        // 模拟连续两次击键：每次新击键都取消上一个还没过静默期的检索（LaunchedEffect 的行为）。
        for (q in listOf("协", "协议")) {
            pending?.cancel()
            pending = launch {
                debouncedHistorySearch(q, HistorySearchFilter()) { query, _ ->
                    searched += query
                    listOf(
                        MessageSearchResult(
                            id = "m1",
                            convId = "conv-1",
                            peerId = "conv-1",
                            senderId = null,
                            mine = false,
                            timestamp = 0L,
                            snippetText = query,
                        ),
                    )
                }
            }
            advanceTimeBy(50)
        }
        advanceUntilIdle()
        assertEquals(
            listOf("协议"),
            searched,
            "200ms 静默期内连续两次击键只该落一次检索；落在两次说明又退回逐字直查",
        )
    }

    @Test
    fun dateFiltersStillSearchWithAnEmptyQuery() {
        // 与全局搜索刻意不同的一条口径：会话内的日期/类型筛选在空关键词下仍然有效
        // （「只看某几天」），所以空关键词**必须**照常落检索，不能在这里被拦。
        runTest {
            var calls = 0
            debouncedHistorySearch(
                "",
                HistorySearchFilter(fromAt = 1L, toAt = 2L),
                debounceMillis = HISTORY_SEARCH_DEBOUNCE_MILLIS,
            ) { _, _ ->
                calls++
                emptyList()
            }
            assertEquals(1, calls, "空关键词 + 日期筛选是一次有效检索")
        }
    }
}
