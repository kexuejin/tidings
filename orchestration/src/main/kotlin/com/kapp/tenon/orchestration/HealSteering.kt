// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 自愈熔断后的**人机协助纠偏**（P2 规格 4）：3 轮跑满不推倒重来，给人一次、且仅一次
 * 「带着一句话再验一轮」的机会。现场（worktree / 分支 / 已写出的代码）一律保留。
 *
 * 刻意不造跨会话断点续跑协议：只有一份单次授权 + 一轮终验，失败就彻底转人工，
 * 不存在隐藏的循环，也不需要新协议键或卡片状态词。
 */
object HealSteering {

    /** 既有自愈上限；只有这一处说了「3」。 */
    const val BASE_ROUNDS: Int = 3

    /** 人工纠偏后的 attempt 与 maxAllowed（规格 4.2 的硬读数）。 */
    const val STEERED_ATTEMPT: Int = 4

    /** 单句指引的提示词预算，与棒摘要同一量级。 */
    const val MAX_INSTRUCTION_CHARS: Int = 200

    /** 熔断后的现场策略：保留，不清理。宿主动作分支不许反向调用清理。 */
    const val WORKSPACE_POLICY: String = "keep"

    /** 抽屉触发常量：与 composeApp 的既有输入抽屉判据逐字一致，不新造 UI。 */
    const val DRAWER_ACTION_ID: String = "reject-revision"

    /** 放弃并保留现场。 */
    const val ABANDON_ACTION_ID: String = "abandon-heal"

    data class Grant(val attempt: Int, val maxAllowed: Int, val instruction: String)

    /** 单次授权：已用过 / 空白指引一律 null（fail-closed，且不消费机会）。 */
    fun grantOnce(instruction: String, alreadyUsed: Boolean): Grant? {
        if (alreadyUsed) return null
        val cleaned = instruction.trim().take(MAX_INSTRUCTION_CHARS)
        if (cleaned.isEmpty()) return null
        return Grant(STEERED_ATTEMPT, STEERED_ATTEMPT, cleaned)
    }

    /** 终验机会用尽后是否必须转人工。 */
    fun terminal(alreadyUsed: Boolean): Boolean = alreadyUsed

    /** 熔断的可检索终局标记（进卡面横幅与盲区账本，不新增卡片状态词）。 */
    fun exhaustedTag(rounds: Int): String = "HEAL_EXHAUSTED(rounds=$rounds)"

    /**
     * 熔断卡动作列表的数据驱动替换：第一颗精确等于既有抽屉触发常量（点击展开输入框），
     * 第二颗放弃并保留现场。**只换 actions，卡上其它字段一字不动。**
     */
    fun cardWithSteeringActions(card: JsonObject): JsonObject {
        val actions: JsonElement = buildJsonArray {
            add(buildJsonObject {
                put("actionId", DRAWER_ACTION_ID)
                put("label", "Type one instruction & retry")
            })
            add(buildJsonObject {
                put("actionId", ABANDON_ACTION_ID)
                put("label", "Give up, keep worktree")
            })
        }
        return JsonObject(card.toMap() + ("actions" to actions))
    }
}
