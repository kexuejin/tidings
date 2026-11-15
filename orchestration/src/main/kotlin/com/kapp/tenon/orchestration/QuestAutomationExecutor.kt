// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AssistantQuestNotice
import com.kapp.tenon.core.protocol.QuestStatus
import java.io.File

/**
 * 支线任务自动化与合流执行体 (QuestAutomationExecutor)。
 *
 * 契约规范与状态机闭环：
 * 1. 变更前确认 (autoMerge = false)：
 *    门禁通过后推送状态为 [QuestStatus.WAITING_INPUT] 的待确认通知卡片，状态机绝不提前谎报 COMPLETED，
 *    等待机主在 UI 上点击【一键合入】；
 * 2. 全自动无人值守合流 (autoMerge = true)：
 *    门禁通过后进入 [QuestSerialMergeLock] 串行快进合入主分支；
 *    仅在合入成功产生真提交后才通知 [QuestStatus.COMPLETED]；
 *    若触发主工作区未提交冲突或无法 fast-forward 则通知 PAUSED / CONFLICT / FAILED 并报警。
 */
class QuestAutomationExecutor(
    private val mergeLock: QuestSerialMergeLock,
    private val onNotice: suspend (notice: AssistantQuestNotice) -> Unit = {},
    /**
     * 派单端的便签回执键：随本执行器发出的每一条通知带回。wire 派单的通知投递
     * 靠它定位派单端（onNotice 的 receipt lookup），不带 = 通知只翻本机卡，
     * 派单端的便签永远停在 RUNNING（真机实测的缺陷）。
     */
    private val clientToken: String? = null,
) {
    sealed interface AutomationOutcome {
        data class Merged(val commitSha: String) : AutomationOutcome
        object AwaitingConfirmation : AutomationOutcome
        data class Conflict(val reason: String) : AutomationOutcome
        data class HostWorkspaceDirty(val dirtyFiles: List<String>) : AutomationOutcome
        data class Failed(val error: String) : AutomationOutcome
    }

    suspend fun handleQuestCompletion(
        questId: String,
        convId: String,
        title: String,
        worktreeDir: File,
        autoMerge: Boolean,
        summary: String = "",
    ): AutomationOutcome {
        if (!autoMerge) {
            // 变更前确认模式：推送 WAITING_INPUT 状态等待机主审查确认，绝不谎报 COMPLETED
            val notice = AssistantQuestNotice(
                questId = questId,
                convId = convId,
                status = QuestStatus.WAITING_INPUT,
                title = title,
                summary = summary.ifBlank { "Gate checks passed. Waiting for human approval to merge." },
                questionText = "Approve merging changes into main branch?",
                // 结构化提问种类：卡片据此出 merge-patch 审批按钮（不从自然语言嗅探）。
                questionKind = AssistantQuestNotice.QUESTION_MERGE_APPROVAL,
                clientToken = clientToken,
                rev = 1L,
            )
            onNotice(notice)
            return AutomationOutcome.AwaitingConfirmation
        }

        // 全自动无人值守模式：通过串行锁合入主分支
        val mergeRes = mergeLock.rebaseAndMerge(
            questId = questId,
            questWorktreeDir = worktreeDir,
        )

        return when (mergeRes) {
            is QuestSerialMergeLock.MergeResult.Success -> {
                val notice = AssistantQuestNotice(
                    questId = questId,
                    convId = convId,
                    status = QuestStatus.COMPLETED,
                    title = title,
                    summary = summary.ifBlank { "Fast-forward merged commit ${mergeRes.mergedCommit.take(8)} into main." },
                    clientToken = clientToken,
                    rev = 2L,
                )
                onNotice(notice)
                AutomationOutcome.Merged(mergeRes.mergedCommit)
            }
            is QuestSerialMergeLock.MergeResult.HostWorkspaceDirty -> {
                val notice = AssistantQuestNotice(
                    questId = questId,
                    convId = convId,
                    status = QuestStatus.PAUSED,
                    title = title,
                    summary = "Auto-merge blocked: uncommitted local changes exist in main workspace.",
                    clientToken = clientToken,
                    rev = 2L,
                )
                onNotice(notice)
                AutomationOutcome.HostWorkspaceDirty(mergeRes.dirtyFiles)
            }
            is QuestSerialMergeLock.MergeResult.Conflict -> {
                val notice = AssistantQuestNotice(
                    questId = questId,
                    convId = convId,
                    status = QuestStatus.CONFLICT,
                    title = title,
                    summary = "Auto-merge failed due to conflict: " + mergeRes.reason,
                    clientToken = clientToken,
                    rev = 2L,
                )
                onNotice(notice)
                AutomationOutcome.Conflict(mergeRes.reason)
            }
            is QuestSerialMergeLock.MergeResult.PreMergeFailed -> {
                val notice = AssistantQuestNotice(
                    questId = questId,
                    convId = convId,
                    status = QuestStatus.FAILED,
                    title = title,
                    summary = "Pre-merge checks failed: " + mergeRes.reason,
                    clientToken = clientToken,
                    rev = 2L,
                )
                onNotice(notice)
                AutomationOutcome.Failed(mergeRes.reason)
            }
        }
    }
}
