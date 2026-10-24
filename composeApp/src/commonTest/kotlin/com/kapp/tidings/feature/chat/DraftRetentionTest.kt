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

    /**
     * **发送之后，外部草稿表里的这一条也要清掉**（[notifyDraftCleared] 的契约）。
     *
     * 真身：发送侧只复位了 ChatScreen 自己的 `draft`，外部表（两个壳共用的
     * `updateDraft` 表）里留着一份**已经发出去的正文**；下次回到这个会话，
     * `initialDraft` 把它喂回输入框 —— 已发内容「复活」。旧行为就是这条测试的反面。
     *
     * 这条只钉 helper 的契约；**接线**（onSend 真的调了它）由
     * `DraftClearOnSendWiringTest`（jvmTest 的源码级守卫）钉 —— 删掉调用点时
     * helper 还在、本条仍绿，守卫那条才会红。
     */
    @Test
    fun sendingClearsTheExternalDraftEntry() {
        // 走 updateDraft 的真实语义建表：发送前外部表里确实有一条草稿。
        var drafts = mapOf("peer-1" to "已经发出去的话")
        val notified = mutableListOf<Pair<String, String>>()
        // 通知必须以「空串」落到**这一个会话键**上 —— updateDraft 的语义是
        // 「空串 = 删除」，表里不该残留已发送的正文。
        notifyDraftCleared("peer-1") { convId, text ->
            notified += convId to text
            drafts = updateDraft(drafts, convId, text)
        }
        assertEquals(listOf("peer-1" to ""), notified, "发送后必须通知外部表清掉这一条草稿")
        assertTrue(drafts.isEmpty(), "外部表不该残留已发送的正文（否则切回来会「复活」）")
    }

    /** 没有会话键、或壳没接外部表时，复位动作必须安静地什么都不做（不炸、不误投）。 */
    @Test
    fun clearingWithoutAConvOrSinkIsANoop() {
        var called = false
        notifyDraftCleared(null) { _, _ -> called = true }
        notifyDraftCleared("peer-1", null)
        assertTrue(!called, "没有会话键时不该通知任何回调")
    }
}
