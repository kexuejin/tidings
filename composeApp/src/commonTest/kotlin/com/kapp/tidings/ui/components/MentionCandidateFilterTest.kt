// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「@ 候选按光标前缀过滤、删除 @ 收起」两条判据的单测。
 *
 * 真身：候选只在「敲入 @」那一刻打开，之后**不过滤也不收起** —— 用户 @ 后面继续
 * 打字，全体成员照旧摊在那里；把 @ 删掉了，候选还赖着盖住正文。
 *
 * 反向验证：把 [mentionQueryBeforeCursor] 改成恒返回 null（或恒返回空串），
 * 「过滤」与「收起」的判据全部失效，本组测试立刻变红。
 */
class MentionCandidateFilterTest {

    // ---- mentionQueryBeforeCursor：光标前正在进行的那段 @ 输入 ------------------

    @Test
    fun justTypedAtYieldsAnEmptyQuery() {
        // 刚敲完 @（光标在 @ 后面）：候选全量展示，查询词是空串而不是 null。
        assertEquals("", mentionQueryBeforeCursor("你好 @", 4))
        assertEquals("", mentionQueryBeforeCursor("@", 1))
    }

    @Test
    fun partialTokenIsTheQuery() {
        assertEquals("ds", mentionQueryBeforeCursor("你好 @ds", 7))
        assertEquals("dsh", mentionQueryBeforeCursor("@dsh", 4))
        // 多个 @ 取**离光标最近**的那个。
        assertEquals("b", mentionQueryBeforeCursor("@a @b", 5))
    }

    @Test
    fun noAtSignBeforeCursorYieldsNull() {
        assertNull(mentionQueryBeforeCursor("没有@", 2), "光标在 @ 左边 = 没有进行中的输入")
        assertNull(mentionQueryBeforeCursor("纯文本", 3))
        assertNull(mentionQueryBeforeCursor("", 0))
    }

    @Test
    fun whitespaceBetweenAtAndCursorYieldsNull() {
        // @ 与光标之间隔了空白：那一段输入已经结束，候选该收起。
        assertNull(mentionQueryBeforeCursor("@老周 你好", 7))
        assertNull(mentionQueryBeforeCursor("@ \n", 3))
    }

    @Test
    fun deletingTheAtSignYieldsNull() {
        // 「删除 @ 收起」的判据：把 @ 删掉后（光标落在原 @ 的位置），查询变 null。
        assertNull(mentionQueryBeforeCursor("你好 dsh", 3))
    }

    @Test
    fun outOfRangeCursorIsClampedNotCrashing() {
        assertEquals("", mentionQueryBeforeCursor("@", 99))
        assertNull(mentionQueryBeforeCursor("abc", -5))
    }

    // ---- filterMentionTargets：按前缀过滤 --------------------------------------

    private val targets = listOf(
        MentionTarget(token = "@DSH", label = "DSH", isAgent = true),
        MentionTarget(token = "@老周", label = "老周"),
        MentionTarget(token = "@opencode", label = "OpenCode", isAgent = true),
    )

    @Test
    fun emptyQueryShowsEveryone() {
        assertEquals(targets, filterMentionTargets(targets, ""), "刚敲完 @：全体候选")
    }

    @Test
    fun prefixFiltersCaseInsensitively() {
        // 用户打小写 dsh 也该叫得出 @DSH（与身份解析器「名字只有一份」一致）。
        assertEquals(listOf("@DSH"), filterMentionTargets(targets, "ds").map { it.token })
        assertEquals(listOf("@opencode"), filterMentionTargets(targets, "OPEN").map { it.token })
        assertEquals(listOf("@老周"), filterMentionTargets(targets, "老").map { it.token })
    }

    @Test
    fun nonMatchingQueryFiltersEverythingOut() {
        assertTrue(filterMentionTargets(targets, "xyz").isEmpty())
        // 前缀判据，不是子串判据：中间命中不算。
        assertTrue(filterMentionTargets(targets, "sh").isEmpty(), "dsh 的中段不算前缀命中")
    }
}
