// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 月历年月算术的单测。
 *
 * 真身：月历「下个月」按钮旧实现拿 `todayEpochDay + monthOffset * 30L` 冒充加一个月 ——
 * 1 月 31 日点「>」，+30 天直接落到 3 月 2 日，2 月整月被跳过。顺带的第二处：
 * 网格写死 5 行 35 格，「周六开月 + 31 天」的月份（2026-08）第 6 行的 29/30/31 整行被裁掉。
 *
 * 反向验证：把 [shiftYearMonth] 改回 `epochDay + offset * 30L` 的天号路径
 * （或在 [calendarRowCount] 里写死 5），下面对应的测试立刻变红。
 */
class CalendarMonthMathTest {

    /** 需求点名的那一天：1 月 31 日点「下个月」必须落在 2 月，不能跳到 3 月。 */
    @Test
    fun january31PlusOneMonthLandsInFebruary() {
        // 旧实现（+30 天）在这一天落到 3 月 2 日 —— 正是用户报的「跳月」。
        // 反向验证时把 shiftYearMonth 退回天号路径，这条最先红。
        val (y, m) = shiftYearMonth(2026, 1, 1)
        assertEquals(2026, y)
        assertEquals(2, m, "1 月的下一个月是 2 月，不是被 30 天漂过去的 3 月")
    }
    /** 返回值约定：Pair.first = year，Pair.second = month。 */
    @Test
    fun monthArithmeticTreatsMonthsAsMonthsNotThirtyDays() {
        assertEquals(Pair(2026, 2), shiftYearMonth(2026, 1, 1), "1 月 → 2 月")
        assertEquals(Pair(2026, 3), shiftYearMonth(2026, 2, 1), "2 月 → 3 月（不能再被 30 天冒充吞掉）")
        assertEquals(Pair(2024, 2), shiftYearMonth(2024, 1, 1), "闰年前夜的 1 月同样 → 2 月")
        assertEquals(Pair(2026, 8), shiftYearMonth(2026, 7, 1), "7 月 31 天也按月翻")
        // 多月偏移与反向翻月。
        assertEquals(Pair(2027, 1), shiftYearMonth(2026, 12, 1), "12 月 → 次年 1 月（跨年）")
        assertEquals(Pair(2025, 12), shiftYearMonth(2026, 1, -1), "1 月 → 去年 12 月（反向跨年）")
        assertEquals(Pair(2026, 1), shiftYearMonth(2026, 1, 0), "offset = 0 回到当月")
        assertEquals(Pair(2027, 3), shiftYearMonth(2026, 1, 14), "任意多月偏移也按月算")
    }

    /** 大小月与闰年：2 月 28/29 天由公历规则给，其余按惯例。 */
    @Test
    fun daysInMonthFollowsTheGregorianRules() {
        assertEquals(29, daysInMonth(2024, 2), "2024 闰年：2 月 29 天")
        assertEquals(28, daysInMonth(2023, 2), "2023 平年：2 月 28 天")
        assertEquals(28, daysInMonth(2100, 2), "2100 是平年（整百年不闰，除非能被 400 整除）")
        assertEquals(31, daysInMonth(2026, 1), "1 月 31 天")
        assertEquals(31, daysInMonth(2026, 8), "8 月 31 天")
        assertEquals(30, daysInMonth(2026, 4), "4 月 30 天")
        assertEquals(31, daysInMonth(2026, 12), "12 月 31 天")
    }

    /**
     * 网格行数：4 行起步的极限月存在，6 行的月份**必须给到第 6 行**。
     * 旧实现写死 5 行，2026-08 的 29/30/31 整行被裁掉。
     */
    @Test
    fun gridRowCountServesFourFiveAndSixRowMonths() {
        // 2021-02：周一开月 + 28 天 = 恰好 28 格 → 4 行（理论下限）。
        assertEquals(4, calendarRowCount(2021, 2))
        // 2026-02：周日开月 + 28 天 = 34 格 → 5 行。
        assertEquals(5, calendarRowCount(2026, 2))
        // 2026-08：周六开月 + 31 天 = 36 格 → 6 行（写死 5 行会裁掉 29/30/31）。
        assertEquals(6, calendarRowCount(2026, 8))
        // 2026-03：周日开月 + 31 天 = 37 格 → 6 行。
        assertEquals(6, calendarRowCount(2026, 3))
        // 2026-05：周五开月 + 31 天 = 35 格 → 恰好 5 行（贴着边界收进 5 行）。
        assertEquals(5, calendarRowCount(2026, 5))
    }

    /** 行数自洽：起点 + 行数 × 7 必须能覆盖本月最后一天。 */
    @Test
    fun gridCoversTheWholeMonthForEveryMonthOfAYearSpan() {
        for (year in 2024..2027) {
            for (month in 1..12) {
                val start = monthGridStartEpochDay(year, month)
                val rows = calendarRowCount(year, month)
                val lastDay = yearMonthToEpochDay(year, month, daysInMonth(year, month))
                assertTrue(
                    start + rows * 7L - 1 >= lastDay,
                    "$year-$month：$rows 行装不下本月最后一天",
                )
                assertTrue(rows in 4..6, "$year-$month：行数 $rows 超出月历的合理范围")
            }
        }
    }
}
