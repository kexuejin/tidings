// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AssistantQuestNotice
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.QuestStatus
import com.kapp.tenon.core.protocol.toAgentCardState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 支线任务通知（[AssistantQuestNotice]）→ 端侧卡片载荷的**唯一构建方**。
 *
 * 为什么住在 :app 而不是 composeApp：通知落库发生在领域层
 * （[RelayController.handleAssistantQuestNotice]），渲染层只认 `renderCard` JSON ——
 * 构建方必须与落库同层，否则「编排层发出通知」与「会话里出现卡片」就断在模块边界上
 * （首轮评审 P1-1 的现场：整条通知链零调用方，UI 的介入/恢复/合并按钮永远不会出现）。
 *
 * 形状的**消费真源**是 composeApp 的 `parseAgentCard`；round-trip 由
 * composeApp 测试 `AssistantQuestCardPayloadTest` 用真解析器对拍钉住。
 *
 * 动作 ID 是 kebab-case 系统动作（与 ChatScreen 的卡片动作拦截、HostCardActions 同一词表）；
 * `labelKey` 是跨端文案键（见 composeApp `CardActionLabelKeys`），`label` 只是
 * 旧端/未知键的回退原文 —— 键一旦发布不许改语义。
 */
object AssistantQuestCardPayload {

    /** 卡片类型标：渲染与 ChatScreen 的动作拦截据此识别支线卡（群工单卡不是这个类型）。 */
    const val TYPE_ASSISTANT_QUEST: String = "assistant_quest"

    /** 卡片动作 ID 词表（系统动作；与卡片状态一样是闭集）。 */
    object Actions {
        const val OPEN_QUEST: String = "open-quest"
        const val REPLY_TUNE: String = "reply-tune"
        const val RESUME_QUEST: String = "resume-quest"
        const val RETRY_QUEST: String = "retry-quest"
        const val CANCEL_QUEST: String = "cancel-quest"
        val MERGE_PATCH: String = Limits.SYSTEM_ACTION_MERGE_PATCH
    }

    /**
     * 构建可被 `parseAgentCard` 解析的卡片载荷。
     *
     * 合流审批的判定顺序：**结构化 [AssistantQuestNotice.questionKind] 优先**；
     * 老通知缺省时才回退 questionText 关键词匹配 —— 那是兼容路径不是主路径，
     * 新代码发问必须带 questionKind（从自然语言里嗅探行为是评审点名的假语义）。
     */
    fun build(notice: AssistantQuestNotice): JsonObject {
        val state = notice.status.toAgentCardState()
        return buildJsonObject {
            put("cardId", JsonPrimitive(notice.questId))
            put("rev", JsonPrimitive(notice.rev.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()))
            put("type", JsonPrimitive(TYPE_ASSISTANT_QUEST))
            put("title", JsonPrimitive(notice.title))
            put("state", JsonPrimitive(state))
            if (notice.status == QuestStatus.COMPLETED) put("progress", JsonPrimitive(100))
            put("context", JsonPrimitive(notice.summary))
            put("actions", buildJsonArray {
                notice.actionsForOwner().forEach { action ->
                    add(buildJsonObject {
                        put("actionId", JsonPrimitive(action.actionId))
                        put("label", JsonPrimitive(action.label))
                        put("labelKey", JsonPrimitive(action.labelKey))
                        action.style?.let { put("style", JsonPrimitive(it)) }
                        put("approval", JsonPrimitive(action.approval))
                    })
                }
            })
            if (!notice.questionText.isNullOrBlank()) {
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive("step-question"))
                        put("label", JsonPrimitive(notice.questionText))
                        put(
                            "status",
                            JsonPrimitive(
                                if (notice.status == QuestStatus.WAITING_INPUT) "blocked" else "running",
                            ),
                        )
                        put("detail", JsonPrimitive(notice.summary))
                    })
                })
            }
            notice.diffStats?.takeIf { it.isNotBlank() }?.let { stats ->
                put("diff", buildJsonObject {
                    put("summary", JsonPrimitive(stats))
                    put("open", JsonPrimitive(false))
                })
            }
        }
    }

    private data class OwnerAction(
        val actionId: String,
        val label: String,
        val labelKey: String,
        val approval: Boolean = false,
        val style: String? = null,
    )

    private fun AssistantQuestNotice.actionsForOwner(): List<OwnerAction> = when (status) {
        QuestStatus.WAITING_INPUT ->
            if (questionKind == AssistantQuestNotice.QUESTION_MERGE_APPROVAL ||
                (questionKind == null && questionText?.contains("merge", ignoreCase = true) == true)
            ) {
                listOf(OwnerAction(Actions.MERGE_PATCH, "Merge to Main", "card.action.merge", approval = true))
            } else {
                listOf(OwnerAction(Actions.REPLY_TUNE, "Reply & Tune", "card.action.reply"))
            }
        QuestStatus.PAUSED -> listOf(OwnerAction(Actions.RESUME_QUEST, "Resume", "card.action.resume"))
        QuestStatus.COMPLETED -> listOf(OwnerAction(Actions.MERGE_PATCH, "Merge to Main", "card.action.merge", approval = true))
        QuestStatus.INTERRUPTED, QuestStatus.FAILED, QuestStatus.CONFLICT ->
            listOf(OwnerAction(Actions.RETRY_QUEST, "Retry", "card.action.retry", style = "danger"))
        QuestStatus.RUNNING -> listOf(
            OwnerAction(Actions.OPEN_QUEST, "Enter Quest War Room", "card.action.open_quest"),
            OwnerAction(Actions.CANCEL_QUEST, "Cancel Task", "card.action.cancel_task", style = "danger"),
        )
        // PENDING_DISPATCH / CANCELLED：只读展示
        else -> emptyList()
    }
}
