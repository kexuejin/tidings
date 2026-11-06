// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.core.protocol.ScreenPhase
import com.kapp.tenon.core.protocol.ScreenRole
import com.kapp.tenon.domain.model.ChatRowKind
import com.kapp.tenon.domain.model.ChatRowKinds
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.NoticeKind
import com.kapp.tenon.domain.model.ScreenRecordUi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 注册表的运行期真值表（轮 16 / WP3）。
 *
 * 断言的是「判定结果 ≡ 改造前那六支 if/else」，而不是实现细节：
 * 系统行 > 屏幕历史卡 > 卡片三态 > 状态横幅 > 普通气泡。
 * 反向验证：把 `CardRowAdapter` 的 Hidden 分支改成 Render，旧 rev 那两条会红。
 */
class MessageRowRegistryContractTest {

    private val unreadable = "卡片内容读不出来"

    private fun base(id: String = "m1", text: String = "hello"): MessageUi = MessageUi(
        id = id,
        peerId = "p",
        mine = false,
        text = text,
        at = 0L,
    )

    private fun card(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("cardId", "c1")
        put("rev", 1)
    }

    private fun brokenCard(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("oops", true)
    }

    private fun resolve(message: MessageUi, visible: Set<String> = emptySet()): RowDecision =
        resolveRow(message, visible, unreadable)

    @Test
    fun adaptersFollowTheDeclaredPriority() {
        assertEquals(ChatRowKind.entries.toList(), ChatRowKinds.ordered, "新增 kind 必须同时进 ordered")
        assertEquals(
            ChatRowKinds.ordered,
            MessageRowRegistry.adapters.map { it.kind },
            "注册顺序不许另写一份",
        )
    }

    @Test
    fun noticeScreenAndAgentStateRenderTheirOwnKind() {
        val notice = assertIs<RowDecision.Render>(resolve(base().copy(notice = NoticeKind.RecalledBySelf)))
        assertEquals(ChatRowKind.Notice, notice.kind)

        val screen = ScreenRecordUi(
            v = 1,
            role = ScreenRole.Viewer,
            phase = ScreenPhase.Ended,
            requestedAt = 0L,
        )
        val screenDecision = assertIs<RowDecision.Render>(resolve(base().copy(screen = screen)))
        assertEquals(ChatRowKind.Screen, screenDecision.kind)

        val state = assertIs<RowDecision.Render>(resolve(base().copy(agentState = "accept")))
        assertEquals(ChatRowKind.AgentState, state.kind)

        val receiptNotice = assertIs<RowDecision.Render>(resolve(base().copy(text = "branch=main changed=1 untracked=0")))
        assertEquals(ChatRowKind.Notice, receiptNotice.kind)
    }

    @Test
    fun visibleCardRevisionRendersTheCard() {
        val decision = assertIs<RowDecision.Render>(resolve(base().copy(card = card()), setOf("m1")))
        assertEquals(ChatRowKind.AgentCard, decision.kind)
        assertTrue(decision.card != null, "渲染态必须把解析后的卡片交给渲染块")
    }

    @Test
    fun staleCardRevisionIsHidden() {
        assertIs<RowDecision.Hidden>(resolve(base().copy(card = card()), emptySet()))
    }

    @Test
    fun unreadableCardDelegatesToBubbleKeepingOriginalText() {
        val decision = assertIs<RowDecision.Delegate>(
            resolve(base(text = "原始正文").copy(card = brokenCard()), setOf("m1")),
        )
        assertEquals(ChatRowKind.Bubble, decision.kind)
        assertEquals("原始正文", decision.message.text)
    }

    @Test
    fun unreadableCardWithBlankTextUsesTheInjectedFallback() {
        val decision = assertIs<RowDecision.Delegate>(
            resolve(base(text = "   ").copy(card = brokenCard()), setOf("m1")),
        )
        assertEquals(unreadable, decision.message.text)
    }

    @Test
    fun plainMessageDelegatesToBubbleUnchanged() {
        val message = base(text = "plain")
        val decision = assertIs<RowDecision.Delegate>(resolve(message))
        assertEquals(ChatRowKind.Bubble, decision.kind)
        assertEquals(message, decision.message)
    }

    @Test
    fun priorityComesFromOrderNotFromFieldPresence() {
        // 同一帧既带 notice 又带卡又带状态：系统行是主体，卡片与横幅都不重复画。
        val all = base().copy(
            card = card(),
            agentState = "accept",
            notice = NoticeKind.NudgedByPeer,
        )
        assertEquals(ChatRowKind.Notice, resolve(all, setOf("m1")).renderKind())

        // 去掉 notice 后卡片胜过状态横幅。
        val cardAndState = base().copy(card = card(), agentState = "accept")
        assertEquals(ChatRowKind.AgentCard, resolve(cardAndState, setOf("m1")).renderKind())
    }

    @Test
    fun hiddenHasNoRenderKind() {
        assertNull(RowDecision.Hidden.renderKind())
    }
}
