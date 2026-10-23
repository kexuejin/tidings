// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 表情调色板的形状测试。
 *
 * 面板长什么样截图门禁管得着，但"最后一行只有 7 个"这件事截图**看不出**是不是有意的：
 * 少了那一格空位，7 个格子会被 `weight(1f)` 拉宽，画面照样绿。所以这里钉住总数与列数的关系。
 *
 * 另一条是重复：同一个表情出现两次，用户点两次得到一样的字节，但面板看起来"少了一个"，
 * 而且真要合并时没人知道哪个是多余的。
 */
class EmojiPaletteTest {

    /**
     * 39 个、每行 8 个 ⇒ 末行 7 个。数字写死是刻意的：表情表变了这条就该失败，
     * 提醒去看一眼面板截图，而不是让断言跟着数据一起漂。
     */
    @Test
    fun theGridIsEightColumnsWideAndTheLastRowIsShort() {
        val rows = EMOJI_PALETTE.chunked(EMOJI_COLUMNS).map { it.size }
        assertEquals(listOf(8, 8, 8, 8, 7), rows)
    }

    @Test
    fun noEmojiAppearsTwice() {
        assertEquals(EMOJI_PALETTE.size, EMOJI_PALETTE.toSet().size)
    }

    /** 空字符串会画成一个看不见的格子：点下去什么也没发生，用户以为坏了。 */
    @Test
    fun noEmojiIsBlank() {
        assertTrue(EMOJI_PALETTE.all { it.isNotBlank() })
    }
}
