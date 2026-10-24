// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.search

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守卫「桌面全局搜索与手机共用同一条防抖」这条纪律（§2.6：一处一件事只许一份实现）。
 *
 * 背景：手机端全局搜索早就走 `debouncedGlobalSearch`（200ms 静默，行为测试在
 * [GlobalSearchDebounceTest]），桌面面板却保留着**每击键同步扫全库**的旧写法 ——
 * `onSearch` 是同步读内存表，改回去不会有任何报错、界面照常出结果，只是每个字
 * 都跑一次全库检索。这正是「测试绿 ≠ 测到了」的一族，必须由源码级守卫钉住。
 *
 * 反向验证：把 `DesktopGlobalSearchPanel` 里的 `debouncedGlobalSearch(query, …, onSearch)`
 * 改回同步 `onSearch(q)`，[theDesktopPanelDelegatesToTheSharedDebounce] 立刻变红。
 */
class DesktopGlobalSearchDebounceTest {

    @Test
    fun theDesktopPanelDelegatesToTheSharedDebounce() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(DESKTOP_SEARCH))
        // 必须是**调用点**（把实时 query 交进共享 helper），不是别处提到这个名字。
        assertTrue(
            Regex("debouncedGlobalSearch\\s*\\(\\s*query\\b").containsMatchIn(live),
            "桌面全局搜索没有把实时 query 交给共享的 debouncedGlobalSearch：" +
                "它又变成每敲一个字直接扫全库了（手机端同一链路是 200ms 防抖）",
        )
        // 不许再有裸的 onSearch(...) 调用 —— 桌面 helper 以参数身份传入
        // （`debouncedGlobalSearch(query, …, onSearch)`，后面没有括号）。
        assertEquals(
            0,
            Regex("onSearch\\s*\\(").findAll(live).count(),
            "桌面面板里出现了裸的 onSearch(...) 调用：击键绕过共享防抖直接打全库",
        )
        // 静默期数字不许在桌面另写一份（那正是分叉长回来的形状）。
        assertTrue(
            live.contains("SEARCH_DEBOUNCE_MILLIS"),
            "桌面必须复用共享的 SEARCH_DEBOUNCE_MILLIS，不许自己再写一个 delay 数字",
        )
    }

    private companion object {
        const val DESKTOP_SEARCH =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopGlobalSearch.kt"
    }
}
