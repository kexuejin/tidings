// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.HealReport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D4：交回横幅不许丢首轮原因。
 *
 * 真机上首轮是 `cli exit=1`（模型侧根本没跑起来），末轮是
 * `No patch produced by worker`（跑起来了但没交东西）。这两个原因指向完全不同的修法，
 * 横幅只留末轮那句，等于把人往错的方向指。
 */
class HealReportTest {

    @Test
    fun theFirstReasonSurvivesEvenWhenTheLastRoundSaysSomethingElse() {
        val banner = HealReport.bannerOf(
            listOf("cli exit=1", "No patch produced by worker", "No patch produced by worker"),
            maxRounds = 3,
        )
        assertEquals(
            "Handed back: exceeded max heal rounds (3) — round 1: cli exit=1; round 2: No patch produced by worker",
            banner,
            "首因必须带轮次出现；重复的原因不重复说",
        )
    }

    @Test
    fun theSameReasonEveryRoundIsSaidOnce() {
        assertEquals(
            "Handed back: exceeded max heal rounds (3) — No patch produced by worker",
            HealReport.bannerOf(List(3) { "No patch produced by worker" }, maxRounds = 3),
            "三轮同一句话写三遍只会把横幅撑长到看不见重点",
        )
    }

    @Test
    fun threeDifferentReasonsKeepAllOfThemInOrder() {
        val banner = HealReport.bannerOf(
            listOf("cli exit=1", "npm test failed", "No patch produced by worker"),
            maxRounds = 3,
        )
        assertEquals(
            "Handed back: exceeded max heal rounds (3) — round 1: cli exit=1; " +
                "round 2: npm test failed; round 3: No patch produced by worker",
            banner,
        )
    }

    @Test
    fun nothingToHandBackMeansNoBanner() {
        assertNull(HealReport.bannerOf(emptyList(), maxRounds = 3), "没有失败就不该有交回横幅")
        assertNull(HealReport.bannerOf(listOf("   ", ""), maxRounds = 3), "空白原因不算原因")
    }
}
