// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2 规格 4 用例 4.1/4.2：3 轮熔断后**只追加 1 轮终验**，且这一轮机会用完就转人工。
 * 输入槽复用 composeApp 的既有抽屉（actionId 精确等于 reject-revision 才展开）。
 */
class HealSteeringTest {

    @Test
    fun threeRoundsExhaustedIsTaggedAndKeepsTheWorkspace() {
        val tag = HealSteering.exhaustedTag(HealSteering.BASE_ROUNDS)
        assertTrue(tag.contains("HEAL_EXHAUSTED"), "熔断要有可检索的终局标记：$tag")
        assertTrue(tag.contains("3"), "标记要写清是几轮熔断：$tag")
        assertEquals("keep", HealSteering.WORKSPACE_POLICY, "现场必须物理保留，不许清理 worktree")
    }

    @Test
    fun aHumanInstructionAddsExactlyOneFinalRound() {
        val grant = HealSteering.grantOnce("  忽略该单测，仅修改接口判空  ", alreadyUsed = false)
        assertEquals(4, grant?.attempt, "spec: attempt = 4")
        assertEquals(4, grant?.maxAllowed, "spec: maxAllowed = 4")
        assertEquals("忽略该单测，仅修改接口判空", grant?.instruction, "单句指引要 trim 后原样注入")
        assertEquals(1, grant!!.maxAllowed - HealSteering.BASE_ROUNDS, "相对 3 轮只多 1 轮")
    }

    @Test
    fun aSecondSteeringRequestIsRefusedInsteadOfLoopingForever() {
        assertNull(HealSteering.grantOnce("再试一次", alreadyUsed = true), "终验机会用过就没有第二次")
        assertTrue(HealSteering.terminal(alreadyUsed = true), "第二次请求必须转人工")
        assertFalse(HealSteering.terminal(alreadyUsed = false))
    }

    @Test
    fun blankOrOversizedInstructionsFailClosed() {
        assertNull(HealSteering.grantOnce("   ", alreadyUsed = false), "空指引不消费终验机会")
        val long = HealSteering.grantOnce("x".repeat(500), alreadyUsed = false)
        assertEquals(HealSteering.MAX_INSTRUCTION_CHARS, long?.instruction?.length, "单句指引要压进提示词预算")
    }

    @Test
    fun theExhaustedCardCarriesTheExistingDrawerTriggerAndAnAbandonAction() {
        val base = kotlinx.serialization.json.buildJsonObject {
            put("cardId", "chain-c1")
            put("state", "blocked")
        }
        val card = HealSteering.cardWithSteeringActions(base)
        val ids = card["actions"]!!.jsonArray.map { it.jsonObject["actionId"]!!.jsonPrimitive.content }
        assertEquals(listOf("reject-revision", "abandon-heal"), ids,
            "第一颗必须精确等于抽屉触发常量，第二颗保留现场但不重跑")
        assertEquals("chain-c1", card["cardId"]!!.jsonPrimitive.content, "改动作不许丢卡上的其它字段")
        assertTrue(card["actions"]!!.jsonArray.any {
            it.jsonObject["label"]!!.jsonPrimitive.content.isNotBlank()
        }, "按钮要有可读兜底文案")
    }
}
