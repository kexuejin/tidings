// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ScreenPhase
import com.kapp.tenon.core.protocol.ScreenRole
import com.kapp.tenon.domain.model.BubbleContentKind
import com.kapp.tenon.domain.model.ChatRowKind
import com.kapp.tenon.domain.model.ChatRowKinds
import com.kapp.tenon.domain.model.FileCardUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.NoticeKind
import com.kapp.tenon.domain.model.PkCardUi
import com.kapp.tenon.domain.model.RecallTarget
import com.kapp.tenon.domain.model.SCREEN_RECORD_VERSION
import com.kapp.tenon.domain.model.ScreenRecordUi
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 唯一判别式的**运行期真值表**（轮 15 / WP1）。
 *
 * 源码守卫管「有没有问注册表」，这里管「问出来对不对」。这些断言逐条抄自改造前
 * 的 `ChatMessageList` if/else 链、`ChatInteractions` 与 `MessageBubble` 的字段判据；
 * 收口是纯重构，读数一个都不许变。
 *
 * 反向验证：删掉 `ChatRowKinds.of` 的 notice 分支 → [ofMatchesTheLegacyTruthTable] 红；
 * 把 `capabilities(Notice).recallBlocked` 改成 false → [capabilitiesCoverEveryKind] 红。
 */
class ChatRowKindContractTest {

    private fun msg(
        text: String = "",
        notice: NoticeKind? = null,
        screen: Boolean = false,
        card: Boolean = false,
        agentState: String? = null,
        file: FileCardUi? = null,
        pk: PkCardUi? = null,
        reasoning: String? = null,
    ) = MessageUi(
        id = "m",
        peerId = "p",
        mine = true,
        text = text,
        at = 0L,
        notice = notice,
        screen = if (screen) {
            ScreenRecordUi(
                v = SCREEN_RECORD_VERSION,
                role = ScreenRole.Viewer,
                phase = ScreenPhase.Ended,
                requestedAt = 0L,
            )
        } else null,
        card = if (card) JsonObject(emptyMap()) else null,
        agentState = agentState,
        file = file,
        pk = pk,
        reasoning = reasoning,
    )

    @Test
    fun orderedCoversEveryKindExactlyOnce() {
        assertEquals(ChatRowKind.entries.toSet(), ChatRowKinds.ordered.toSet())
        assertEquals(ChatRowKind.entries.size, ChatRowKinds.ordered.size)
    }

    @Test
    fun ofMatchesTheLegacyTruthTable() {
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(notice = NoticeKind.NudgedByPeer)))
        assertEquals(ChatRowKind.Screen, ChatRowKinds.of(msg(screen = true)))
        assertEquals(ChatRowKind.AgentCard, ChatRowKinds.of(msg(card = true)))
        assertEquals(ChatRowKind.AgentState, ChatRowKinds.of(msg(agentState = "failed")))
        assertEquals(ChatRowKind.Bubble, ChatRowKinds.of(msg()))
        // 一行只允许一种身份，顺序与旧 if/else 链一致。
        assertEquals(
            ChatRowKind.Notice,
            ChatRowKinds.of(msg(notice = NoticeKind.RecalledBySelf, screen = true, card = true, agentState = "failed")),
        )
        assertEquals(ChatRowKind.Screen, ChatRowKinds.of(msg(screen = true, card = true, agentState = "failed")))
        assertEquals(ChatRowKind.AgentCard, ChatRowKinds.of(msg(card = true, agentState = "failed")))
        // 气泡内载荷不改变行级身份。
        assertEquals(ChatRowKind.Bubble, ChatRowKinds.of(msg(file = FileCardUi("t", "a.txt", 1L))))
        assertEquals(ChatRowKind.Bubble, ChatRowKinds.of(msg(reasoning = "think")))
    }

    @Test
    fun quickActionReceiptsRenderAsNotice() {
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(text = "branch=main changed=2 untracked=0")))
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(text = "branch=feat/login changed=0 untracked=1 ticket=W-123")))
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(text = "files=3 +10 -2")))
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(text = "files=0 +0 -0")))
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(text = "ticket T-42 (card follows in this group)")))
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(text = "no git workspace bound to this group")))
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(text = "no worker available on this host")))
        assertEquals(ChatRowKind.Notice, ChatRowKinds.of(msg(text = "team config saved for this group: Lead / Reviewer")))
        assertEquals(ChatRowKind.Bubble, ChatRowKinds.of(msg(text = "hello world")))
        assertEquals(ChatRowKind.Bubble, ChatRowKinds.of(msg(text = "branching out is good")))
    }

    @Test
    fun bubbleContentOfMatchesTheLegacyTruthTable() {
        assertEquals(BubbleContentKind.Text, ChatRowKinds.bubbleContentOf(msg()))
        assertEquals(BubbleContentKind.File, ChatRowKinds.bubbleContentOf(msg(file = FileCardUi("t", "a.txt", 1L))))
        assertEquals(BubbleContentKind.Pk, ChatRowKinds.bubbleContentOf(msg(pk = PkCardUi("rps", "rock"))))
        assertEquals(BubbleContentKind.Thinking, ChatRowKinds.bubbleContentOf(msg(reasoning = "think")))
        // 旧空气泡防线是 file / pk / reasoning 三选一，file 优先。
        assertEquals(
            BubbleContentKind.File,
            ChatRowKinds.bubbleContentOf(msg(file = FileCardUi("t", "a", 1L), pk = PkCardUi("rps", "rock"), reasoning = "think")),
        )
        assertEquals(BubbleContentKind.Text, ChatRowKinds.bubbleContentOf(msg(reasoning = "   ")))
    }

    @Test
    fun capabilitiesCoverEveryKind() {
        for (kind in ChatRowKind.entries) {
            val caps = ChatRowKinds.capabilities(kind)
            if (kind == ChatRowKind.Notice || kind == ChatRowKind.Screen) {
                assertFalse(caps.groupsWithSender, kind.toString() + " 不该参与连续发件编组")
            } else {
                assertTrue(caps.groupsWithSender, kind.toString() + " 丢了编组能力")
            }
            assertEquals(kind == ChatRowKind.Notice, caps.recallBlocked, kind.toString() + " 撤回判据漂移")
        }
    }

    @Test
    fun recallTargetOfMatchesTheLegacyTruthTable() {
        assertEquals(RecallTarget.Text, ChatRowKinds.recallTargetOf(msg(text = "hi")))
        assertEquals(RecallTarget.Text, ChatRowKinds.recallTargetOf(msg(notice = NoticeKind.NudgedByPeer)))
        assertEquals(RecallTarget.Media, ChatRowKinds.recallTargetOf(msg(file = FileCardUi("t", "a.txt", 1L))))
        assertEquals("m", recallTargetIdOf(msg(text = "hi")))
        assertEquals("proto-1", recallTargetIdOf(msg(file = FileCardUi("t", "a.txt", 1L, msgId = "proto-1"))))
        assertEquals(null, recallTargetIdOf(msg(file = FileCardUi("t", "a.txt", 1L))))
    }

    @Test
    fun previewTextOfMatchesTheOldComposerLine() {
        assertEquals("hi", ChatRowKinds.previewTextOf(msg(text = "hi")))
        assertEquals("a.txt", ChatRowKinds.previewTextOf(msg(file = FileCardUi("t", "a.txt", 1L))))
        assertEquals("", ChatRowKinds.previewTextOf(msg()))
        // 正文优先于文件名，与旧 `text.ifBlank { file?.name }` 一致。
        assertEquals("hi", ChatRowKinds.previewTextOf(msg(text = "hi", file = FileCardUi("t", "a.txt", 1L))))
    }
}
