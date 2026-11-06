// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

// 免审动作 id 的编解码已收进 :app 的领域契约（编码侧手机、解码侧宿主共用一份）。
import com.kapp.tenon.domain.SYSTEM_ACTION_SESSION_EXEMPT
import com.kapp.tenon.domain.isSessionExemptAction
import com.kapp.tenon.domain.sessionExemptActionIdOf
import com.kapp.tenon.domain.sessionExemptCardIdOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L1 会话免审（§4.2 / §八.2）手机侧判据：单槽编码、已生效判定与渲染决策都是
 * **纯函数**，用合成输入钉死 —— 与策略层/打回编码同一条纪律（不读时钟、不碰 Compose）。
 *
 * 解码侧的镜像在桌面端 `SessionExemptFeedback`；改一侧必须同步另一侧（两侧测试各钉一份）。
 */
class AgentCardSessionExemptTest {

    // ── 单槽编码：session-exempt|<cardId> ─────────────────────────────────

    @Test
    fun encodingCarriesCardIdAfterPrefix() {
        val actionId = sessionExemptActionIdOf("chain-abc123")
        assertEquals("session-exempt|chain-abc123", actionId)
        assertTrue(isSessionExemptAction(actionId))
        assertEquals("chain-abc123", sessionExemptCardIdOf(actionId))
    }

    @Test
    fun encodingTrimsWhitespaceAroundCardId() {
        val actionId = sessionExemptActionIdOf("  chain-x  ")
        assertEquals("session-exempt|chain-x", actionId)
        assertEquals("chain-x", sessionExemptCardIdOf(actionId))
    }

    @Test
    fun blankCardIdEncodesAsBarePrefix() {
        // 空 cardId 退化成裸前缀（动作帧仍然合法），解码侧如实判 null ——
        // 宿主拿不到卡就不落表，不会把"不知道哪张卡"当成"全部卡"。
        assertEquals(SYSTEM_ACTION_SESSION_EXEMPT, sessionExemptActionIdOf("   "))
        assertNull(sessionExemptCardIdOf(SYSTEM_ACTION_SESSION_EXEMPT))
        // 裸前缀（无竖线）不算免审动作：isSessionExemptAction 认整段前缀，
        // 别的动作串（session-exempt-x|…）与裸前缀都不冒充 —— 免审分支只接编码器产出的形状。
        assertFalse(isSessionExemptAction(SYSTEM_ACTION_SESSION_EXEMPT))
    }

    @Test
    fun decodingIgnoresNonExemptActions() {
        assertNull(sessionExemptCardIdOf(SYSTEM_ACTION_MERGE_PATCH))
        assertNull(sessionExemptCardIdOf("approve"))
        assertNull(sessionExemptCardIdOf("reject-revision|补单测"))
        assertFalse(isSessionExemptAction(SYSTEM_ACTION_ABORT_TASK))
        // 前缀必须整段匹配：同前缀的其他动作串不冒充免审。
        assertFalse(isSessionExemptAction("session-exempt-x|chain-1"))
        assertNull(sessionExemptCardIdOf("session-exempt-x|chain-1"))
    }

    // ── 已生效判定：宿主回放 active 或本机动作在途 ────────────────────────

    private fun exemptCard(cardId: String, style: String? = null) = AgentCardUi(
        cardId = cardId,
        rev = 1,
        subActions = listOf(AgentCardAction(actionId = SYSTEM_ACTION_SESSION_EXEMPT, label = "exempt", style = style)),
    )

    @Test
    fun hostEchoedActiveStyleCountsAsActive() {
        val card = exemptCard("chain-1", style = "active")
        assertTrue(isSessionExemptActive(card, emptySet()))
    }

    @Test
    fun pendingLocalActionCountsAsActive() {
        val card = exemptCard("chain-1")
        val actionId = sessionExemptActionIdOf("chain-1")
        assertTrue(isSessionExemptActive(card, setOf("chain-1|$actionId")))
        // 记账键是 (cardId, actionId)：别的卡在途不连坐这一张。
        assertFalse(isSessionExemptActive(card, setOf("chain-9|" + sessionExemptActionIdOf("chain-9"))))
    }

    @Test
    fun offeredWithoutEchoOrPendingIsInactive() {
        val card = exemptCard("chain-1")
        assertFalse(isSessionExemptActive(card, emptySet()))
    }

    @Test
    fun cardWithoutExemptSubActionIsNeverActive() {
        val card = AgentCardUi(cardId = "chain-1", rev = 1)
        assertFalse(isSessionExemptActive(card, setOf("chain-1|" + sessionExemptActionIdOf("chain-1"))))
    }

    // ── 渲染决策：开关的画与点 ────────────────────────────────────────────

    @Test
    fun exemptRowDoesNotFlipOtherRenderPlanBlocks() {
        // 免审行是 subAction 语义，不该改写按钮组的任何判定：同一张卡带/不带免审
        // subAction，渲染决策必须逐字段相同（差分断言，不赌某一档的绝对值）。
        val plain = AgentCardUi(cardId = "chain-1", rev = 1, actions = listOf(AgentCardAction(actionId = "approve", label = "ok", approval = true)))
        val exempted = plain.copy(subActions = listOf(AgentCardAction(actionId = SYSTEM_ACTION_SESSION_EXEMPT, label = "exempt")))
        val planPlain = cardRenderPlanOf(plain, CardActionEligibility.Actionable, hasActionChannel = true, now = 0L)
        val planExempt = cardRenderPlanOf(exempted, CardActionEligibility.Actionable, hasActionChannel = true, now = 0L)
        assertEquals(planPlain, planExempt)
    }
}