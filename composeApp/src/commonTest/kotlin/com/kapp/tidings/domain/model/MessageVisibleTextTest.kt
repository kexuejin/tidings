// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 零宽字符不算内容（真机反馈「部分消息还是显示空消息」的判据）。
 *
 * 这条测试盯的是 Kotlin 标准库的一个坑：isBlank() 只认 Char.isWhitespace()，而
 * U+200B 等零宽字符不在其中 —— 只含它们的字符串 isNotBlank() 是 true。产消息的一侧
 * （桌面 DSH 桥）与画消息的一侧（MessageBubble 的空气泡防线）都靠这一个函数，
 * 它一旦退回 isBlank()，空气泡就会在两端同时回来。
 */
class MessageVisibleTextTest {

    @Test
    fun onlyZeroWidthCharactersCountAsEmpty() {
        assertFalse(hasVisibleText(null))
        assertFalse(hasVisibleText(""))
        assertFalse(hasVisibleText("   "))
        assertFalse(hasVisibleText("\u200B"))
        assertFalse(hasVisibleText("\u200B\u200C\u200D\uFEFF"))
        assertFalse(hasVisibleText(" \u200B "))
    }

    @Test
    fun realTextCountsAsVisible() {
        assertTrue(hasVisibleText("hi"))
        assertTrue(hasVisibleText("\u200Bhi"))
        assertTrue(hasVisibleText("好"))
        assertTrue(hasVisibleText("✅ \u200B"))
    }
}
