// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.Serializable

/**
 * 支线任务状态全量枚举。
 */
@Serializable
enum class QuestStatus {
    PENDING_DISPATCH, // 离线暂存态 (便签)
    RUNNING,          // 正在 Worktree 沙箱运行
    WAITING_INPUT,    // 门禁受阻或歧义，挂起等待机主输入 (协程挂起中)
    PAUSED,           // 人工输入等待超时 (已挂起入库释放协程，可一键恢复重新拉起)
    COMPLETED,        // 门禁全绿，成功合入主分支
    FAILED,           // 失败且无法自愈
    CANCELLED,        // 机主主动叫停
    CONFLICT,         // 主分支存在交集未提交改动，阻断合流
    INTERRUPTED,      // 桌面端意外重启导致的中断 (支持一键恢复)
}

/** [toProtocolAgentState] 的「进行中」档（RUNNING / PENDING_DISPATCH）——消费方按常量取值，不许抄字面量。 */
const val QUEST_AGENT_STATE_ACTIVE: String = "accept"

/**
 * 支线任务状态转协议 [CodecMsg.GROUP_AGENT_STATES] 四态闭集 (accept / blocked / completed / failed)。
 */
fun QuestStatus.toProtocolAgentState(): String = when (this) {
    QuestStatus.RUNNING, QuestStatus.PENDING_DISPATCH -> QUEST_AGENT_STATE_ACTIVE
    QuestStatus.WAITING_INPUT, QuestStatus.PAUSED, QuestStatus.INTERRUPTED, QuestStatus.CONFLICT -> "blocked"
    QuestStatus.COMPLETED -> "completed"
    QuestStatus.FAILED, QuestStatus.CANCELLED -> "failed"
}

/**
 * 支线任务状态转端侧卡片展示状态四态闭集 (working / blocked / review_ready / aborted)。
 */
fun QuestStatus.toAgentCardState(): String = when (this) {
    QuestStatus.RUNNING, QuestStatus.PENDING_DISPATCH -> "working"
    QuestStatus.WAITING_INPUT, QuestStatus.PAUSED, QuestStatus.INTERRUPTED, QuestStatus.CONFLICT -> "blocked"
    QuestStatus.COMPLETED -> "review_ready"
    QuestStatus.FAILED, QuestStatus.CANCELLED -> "aborted"
}

/**
 * 支线任务轻量通知帧。
 *
 * 序列化后体积严格受控于 [Limits.QUEST_NOTICE_FRAME_BUDGET] (650 字节)。
 */
@Serializable
data class AssistantQuestNotice(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val summary: String,
    val convId: String = ConvId.ASSISTANT,
    val clientToken: String? = null,
    val questionText: String? = null,
    /**
     * 提问的**结构化种类**（取值域单点：卡片动作由此决定，不从自然语言里嗅探）。
     * [QUESTION_MERGE_APPROVAL] = 请机主批准合流 → 卡片出 [Limits.SYSTEM_ACTION_MERGE_PATCH] 审批按钮；
     * null = 普通追问 → 回复按钮。生产侧发问时必须带上它；缺省时卡片侧才回退到
     * questionText 的关键词匹配（老通知兼容，见 :app 卡片载荷构建方的 KDoc）。
     */
    val questionKind: String? = null,
    val diffStats: String? = null,
    val patchSha256: String? = null,
    val rev: Long = 0L,
) {
    companion object {
        /** [questionKind] 的取值域：请机主批准合流。 */
        const val QUESTION_MERGE_APPROVAL: String = "merge_approval"
    }
}
