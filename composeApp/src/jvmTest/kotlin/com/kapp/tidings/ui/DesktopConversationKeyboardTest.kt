// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import com.kapp.tidings.ui.desktop.nextConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 会话列表的 **Alt+↑/↓** 键盘移动（列表级）：纯判据 [nextConversationId]。
 *
 * ## 为什么用 Alt+方向键，而不是裸方向键
 *
 * 壳的 `onPreviewKeyEvent` 在真实事件到达输入框**之前**看到按键。若把裸 ↑/↓ 当成列表移动，
 * 用户在消息输入框里挪光标就会变成切换会话 —— 那是把最常用的编辑动作弄坏。带 Alt 的组合
 * 不与任何文本编辑快捷键冲突（macOS 的 ⌥↑/↓ 在单行输入框里没有语义）。
 *
 * ## 这些断言在守什么
 *
 * 边界才是这条交互的全部：到头要**停住**（不循环）、当前选中被删掉之后要能**重新进入**列表、
 * 空列表不要消费按键。改错任何一条，用户都会遇到"按了没反应"或"选中在两端来回跳"。
 */
class DesktopConversationKeyboardTest {

    private val ids = listOf("a", "b", "c")

    @Test
    fun movingDownWalksTheListAndStopsAtTheEnd() {
        assertEquals("b", nextConversationId("a", ids, +1))
        assertEquals("c", nextConversationId("b", ids, +1))
        assertEquals("c", nextConversationId("c", ids, +1), "到头必须停住，不许循环回第一条")
    }

    @Test
    fun movingUpWalksBackAndStopsAtTheTop() {
        assertEquals("b", nextConversationId("c", ids, -1))
        assertEquals("a", nextConversationId("b", ids, -1))
        assertEquals("a", nextConversationId("a", ids, -1), "到顶必须停住")
    }

    @Test
    fun nothingSelectedEntersTheListFromTheFirstRow() {
        assertEquals("a", nextConversationId(null, ids, +1), "第一次按 ↓ 要从第一条开始，而不是跳过它")
        assertEquals("a", nextConversationId(null, ids, -1))
    }

    @Test
    fun aSelectionThatNoLongerExistsReentersFromTheFirstRow() {
        // 会话被删/被筛掉之后，旧 id 还在状态里：此时按方向键必须能重新落到一条真实的行上。
        assertEquals("a", nextConversationId("gone", ids, +1))
        assertEquals("a", nextConversationId("gone", ids, -1))
    }

    @Test
    fun anEmptyListNeverMoves() {
        assertNull(nextConversationId(null, emptyList(), +1), "空列表不该消费按键")
        assertNull(nextConversationId("a", emptyList(), -1))
    }
}