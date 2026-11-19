// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 卡片动作 `labelKey` 的三条兼容判据（卡片内层 JSON，不是协议白名单字段）。
 *
 * 这三条对应三种真实组合，缺一条这条链就不算兼容：
 * 1. 新卡（带 key）+ 新端 → 用本端词条；
 * 2. 旧卡（只有 label）+ 新端 → 逐字显示 label，不因模块升级而变空；
 * 3. 新卡（本端不认识的 key）+ 新端 → 回退 label；label 也没有 → actionId。
 */
class CardActionLabelKeyTest {

    private fun parse(json: String): AgentCardUi =
        requireNotNull(parseAgentCard(Json.parseToJsonElement(json) as JsonObject))

    @Test
    fun knownKeyWinsOverPayloadLabel() {
        val card = parse(
            """{"cardId":"c","rev":1,"actions":[
                 {"actionId":"start-task","label":"Start now","labelKey":"card.action.start_task",
                  "labelArgs":["3"]}
               ]}""",
        )
        val action = card.actions.single()
        assertEquals("card.action.start_task", action.labelKey)
        assertEquals(listOf("3"), action.labelArgs)
        assertTrue(CardActionLabelKeys.keys.containsKey(action.labelKey), "本端必须登记这个键")
        assertEquals("本地化后的文案", cardActionLabel(action, "本地化后的文案"))
    }

    @Test
    fun legacyPayloadWithPlainLabelStillRenders() {
        val card = parse(
            """{"cardId":"c","rev":1,"actions":[{"actionId":"pause-task","label":"暂停任务"}]}""",
        )
        val action = card.actions.single()
        assertNull(action.labelKey, "旧 payload 没有键，不能凭空造一个")
        assertTrue(action.labelArgs.isEmpty())
        assertEquals("暂停任务", cardActionLabel(action, null))
    }

    @Test
    fun unknownKeyFallsBackToLabelOrActionId() {
        val card = parse(
            """{"cardId":"c","rev":1,"actions":[
                 {"actionId":"future-action","label":"Future copy","labelKey":"card.action.future_unknown"}
               ]}""",
        )
        val action = card.actions.single()
        assertFalse(CardActionLabelKeys.keys.containsKey(action.labelKey), "这条测的是本端不认识的键")
        assertEquals("Future copy", cardActionLabel(action, null))
        // 连 label 都没有时也必须给出非空文案 —— actionId 是最后一道兜底。
        assertEquals(
            "future-action",
            cardActionLabel(AgentCardAction(actionId = "future-action", label = ""), null),
        )
    }

    @Test
    fun retryAndPrototypeAndHealKeysAreRegistered() {
        assertTrue(CardActionLabelKeys.keys.containsKey("card.action.retry"))
        assertTrue(CardActionLabelKeys.keys.containsKey("card.action.approve_prototype"))
        assertTrue(CardActionLabelKeys.keys.containsKey("card.action.heal_steer"))
        assertTrue(CardActionLabelKeys.keys.containsKey("card.action.heal_abandon"))
    }

    /**
     * 单兵终态卡的「请 TA 复检」：文案带一个占位符（组员名），名字随 `labelArgs` 下来。
     *
     * 这条钉的是**两端接线**：宿主在 `StageChain.cardOf` 里发的键是
     * `card.action.request_review`、参数是那一位组员的名字；本端若不登记这个键，
     * 按钮会静默退化成英文 "Request review" —— 用户看不出「该找谁」，
     * 而那正是这颗按钮存在的唯一理由。
     */
    @Test
    fun theReviewActionIsRegisteredTogetherWithItsNameArgument() {
        val card = parse(
            """{"cardId":"c","rev":1,"actions":[
                 {"actionId":"review-patch|qa","label":"Request review",
                  "labelKey":"card.action.request_review","labelArgs":["质量"]}
               ]}""",
        )
        val action = card.actions.single()
        assertEquals("review-patch|qa", action.actionId, "动作串原样保留（宿主靠它解回组员名）")
        assertEquals(listOf("质量"), action.labelArgs)
        assertTrue(
            CardActionLabelKeys.keys.containsKey("card.action.request_review"),
            "本端必须登记这个键，否则「请谁」退化成一句固定英文",
        )
    }
}
