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
}
