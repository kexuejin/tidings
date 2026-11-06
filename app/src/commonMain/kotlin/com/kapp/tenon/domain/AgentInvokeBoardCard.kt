// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 看板卡上 Leader 自有动作的 actionId（超时卡的两个按钮）。 */
object InvokeBoardActions {
    const val RETRY = "invoke-retry"
    const val FALLBACK_LOCAL = "invoke-fallback-local"
}

/** 标签看板卡的动作（T-G4，R3 兜底）：重试路由 / 降级本机。 */
object TagBoardActions {
    const val RETRY = "tag-retry"
    const val FALLBACK_LOCAL = "tag-fallback-local"
}

/** 看板卡的构造键（发卡片那侧的纯函数，宿主是谁、任务是什么都从参数进）。 */
object InvokeBoardCardBuilder {
    fun build(
        invokeId: String,
        prompt: String,
        agentName: String,
        state: String,
        timeoutTitle: String,
        retryLabel: String,
        fallbackLabel: String,
        summary: String?,
        error: String?,
        workerCard: JsonObject?,
    ): JsonObject = buildInvokeBoardCard(
        invokeId, prompt, agentName, state, timeoutTitle, retryLabel, fallbackLabel, summary, error, workerCard,
    )
}

/**
 * Leader 的看板卡（§6.1 / §6.2）：
 * - `cardId = invoke-<invokeId>`：cardId 的命名域归 Leader —— 组员插件的原始 cardId
 *   直接进群会跟下一次派单的原位折叠撞车；
 * - 超时态带 [重试]/[降级为本地执行] 两个动作（§7.3-1）；其它终态不带按钮
 *   （重试一个必然再被拒的派单是给人挫败感，不是给出口）；
 * - 组员卡片的内容（title/progress/items/banner）原样收编 —— 全群看一张汇总卡，
 *   汇总的是事实，不是 Leader 的转述。
 */
internal fun buildInvokeBoardCard(
    invokeId: String,
    prompt: String,
    agentName: String,
    state: String,
    timeoutTitle: String,
    retryLabel: String,
    fallbackLabel: String,
    summary: String?,
    error: String?,
    workerCard: JsonObject?,
): JsonObject {
    val base = buildJsonObject {
        put("cardId", "invoke-" + invokeId)
        put("rev", 1)
        put("type", "team-task-board")
        put("title", prompt.take(80))
    }
    val withWorker = workerCard?.let { worker ->
        JsonObject(base.toMap() + worker.toMap().filterKeys { it != "cardId" && it != "rev" })
    } ?: base
    val builder = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
    builder.putAll(withWorker.toMap())
    when (state) {
        InvokeLeaseStates.TIMEOUT -> {
            builder["banner"] = buildJsonObject {
                put("text", timeoutTitle)
                put("tone", "warn")
            }
            builder["actions"] = kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("actionId", InvokeBoardActions.RETRY); put("label", retryLabel); put("style", "secondary") })
                add(buildJsonObject { put("actionId", InvokeBoardActions.FALLBACK_LOCAL); put("label", fallbackLabel); put("style", "primary") })
            }
        }
        InvokeLeaseStates.DONE -> {
            builder["progress"] = JsonPrimitive(100)
            builder["banner"] = buildJsonObject {
                put("text", summary ?: workerCard?.strField("title") ?: "")
                put("tone", "success")
            }
        }
        else -> {
            builder["banner"] = buildJsonObject {
                put("text", summary ?: agentName)
                put("tone", "error")
            }
            if (error != null) builder["error"] = JsonPrimitive(error)
        }
    }
    return JsonObject(builder)
}
