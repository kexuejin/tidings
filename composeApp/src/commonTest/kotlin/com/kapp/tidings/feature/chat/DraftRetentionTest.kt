// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「草稿跨会话不丢」的判据单测。
 *
 * 真身：`ChatScreen` 自己的 `draft` 是 `remember(peerId)` 的 —— 切会话就重建。
 * 用户写到一半去另一个会话确认一件事，回来字数全没了。那是丢用户的输入。
 *
 * 修法是把草稿提到壳里按会话存（[updateDraft] / [draftOf]）。
 * 抽成纯函数是为了让「切走再回来还在」「空串=删除」这两条纪律能被 jvmTest 钉住 ——
 * 组合测试要跑设备，而这类状态搬迁正是不该只靠肉眼发现的。
 */
class DraftRetentionTest {

    /** 切到另一个会话再切回来，草稿还在。 */
    @Test
    fun aDraftSurvivesLeavingAndComingBack() {
        var drafts = emptyMap<String, String>()
        drafts = updateDraft(drafts, "conv-a", "写到一半")
        // 切到 B，B 没有草稿；再切回 A，A 的草稿必须还在。
        assertEquals("", draftOf(drafts, "conv-b"), "B 没有草稿就是空串，不返回 null")
        assertEquals("写到一半", draftOf(drafts, "conv-a"), "切走再回来不能丢用户的输入")
    }

    /** 两个会话各留各的，互不串。 */
    @Test
    fun twoConversationsKeepSeparateDrafts() {
        var drafts = emptyMap<String, String>()
        drafts = updateDraft(drafts, "conv-a", "A 的草稿")
        drafts = updateDraft(drafts, "conv-b", "B 的草稿")
        assertEquals("A 的草稿", draftOf(drafts, "conv-a"))
        assertEquals("B 的草稿", draftOf(drafts, "conv-b"))
    }

    /**
     * **空串 = 删除这一条**（发送之后）。
     *
     * 不能留一个空串条目：那会让「切回来是空白」与「这个会话根本没有草稿」
     * 在表里无法区分，表也会越攒越大。
     */
    @Test
    fun clearingADraftRemovesTheEntry() {
        var drafts = emptyMap<String, String>()
        drafts = updateDraft(drafts, "conv-a", "要说的话")
        drafts = updateDraft(drafts, "conv-a", "")
        assertTrue(drafts.isEmpty(), "发送后表里不该残留空草稿")
        assertEquals("", draftOf(drafts, "conv-a"))
    }

    /** 没有会话键（还没进聊天）时取到空串，而不是炸掉。 */
    @Test
    fun noConversationYieldsAnEmptyDraft() {
        assertEquals("", draftOf(emptyMap(), null))
    }
}
