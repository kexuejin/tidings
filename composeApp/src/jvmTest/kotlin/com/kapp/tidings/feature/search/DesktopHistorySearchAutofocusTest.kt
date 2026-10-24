// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.search

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫「桌面会话内搜索打开即聚焦」。
 *
 * ## 真身
 *
 * 手机浮层 `HistorySearchOverlay` 有 `searchFocus` + `LaunchedEffect(Unit) { requestFocus() }`；
 * 桌面弹窗 `DesktopHistorySearch` 一直没有 —— 点开历史搜索后按键进不去（焦点还在聊天页的
 * 输入卡片上），用户必须先点一下那个 34dp 高的输入框才能打字。
 *
 * ## 判据
 *
 * 三件缺一不可：一个 `FocusRequester`、一条 `LaunchedEffect(Unit)` 的请求、
 * 以及把 requester **挂到搜索框上**（只建不挂 = 又回到"没有挂载点"的老毛病）。
 *
 * ## 反向验证
 *
 * 删掉 `LaunchedEffect(Unit) { searchFocus.requestFocus() }`，
 * [theDialogFocusesItsSearchFieldOnOpen] 立刻变红；只删 `focusRequester(searchFocus)`
 * 则 [theRequesterIsAttachedToTheSearchField] 红。
 */
class DesktopHistorySearchAutofocusTest {

    private val path = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopHistorySearch.kt"

    private fun src(): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun theDialogFocusesItsSearchFieldOnOpen() {
        val text = src()
        assertTrue(
            text.contains("val searchFocus = remember { FocusRequester() }"),
            "桌面历史搜索没有 FocusRequester —— 弹窗打开后按键进不去输入框",
        )
        assertTrue(
            text.contains("LaunchedEffect(Unit) { searchFocus.requestFocus() }"),
            "没有「打开即聚焦」的效果 —— 与手机浮层同一条纪律在这里缺失",
        )
    }

    /** 只建不挂就是又回到"requestFocus 打印警告返回 false"的老毛病。 */
    @Test
    fun theRequesterIsAttachedToTheSearchField() {
        assertTrue(
            src().contains(".focusRequester(searchFocus)"),
            "FocusRequester 没有挂到搜索框上 —— requestFocus() 会静默失败",
        )
    }
}
