// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui

import com.kapp.tenon.domain.RelayUiTexts
import com.kapp.tenon.domain.model.ScreenRecordUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.orchestration_adjust_or_new_task
import com.kapp.tenon.generated.resources.orchestration_send_as_adjustment
import com.kapp.tenon.generated.resources.orchestration_send_as_new_task
import com.kapp.tenon.generated.resources.orchestration_local_assignee
import com.kapp.tenon.generated.resources.orchestration_stage_proto_1
import com.kapp.tenon.generated.resources.orchestration_stage_proto_2
import com.kapp.tenon.generated.resources.orchestration_stage_proto_3
import com.kapp.tenon.generated.resources.orchestration_stage_impl_1
import com.kapp.tenon.generated.resources.orchestration_stage_impl_2
import com.kapp.tenon.generated.resources.orchestration_stage_impl_3
import com.kapp.tenon.generated.resources.orchestration_team_ready
import com.kapp.tenon.generated.resources.orchestration_diff_ready_prompt
import com.kapp.tenon.generated.resources.orchestration_diff_ready_detail
import com.kapp.tenon.generated.resources.orchestration_patch_hint_exists
import com.kapp.tenon.generated.resources.orchestration_patch_hint_mismatch
import com.kapp.tenon.generated.resources.orchestration_patch_hint_file_missing
import com.kapp.tenon.generated.resources.orchestration_patch_hint_workspace_missing
import com.kapp.tenon.generated.resources.orchestration_patch_hint_failed
import com.kapp.tenon.generated.resources.agent_event_thinking_only_fallback
import com.kapp.tenon.generated.resources.agent_invoke_action_fallback_local
import com.kapp.tenon.generated.resources.agent_invoke_action_retry
import com.kapp.tenon.generated.resources.agent_tag_no_match_title
import com.kapp.tenon.generated.resources.agent_invoke_timeout_title
import com.kapp.tenon.generated.resources.agent_action_denied_notice
import com.kapp.tenon.generated.resources.agent_not_completed_notice
import com.kapp.tenon.generated.resources.agent_unavailable_notice
import com.kapp.tenon.generated.resources.chat_nudge_peer
import com.kapp.tenon.generated.resources.workspace_group_initial_greeting
import com.kapp.tenon.generated.resources.workspace_group_session_brief
import com.kapp.tenon.generated.resources.workspace_group_session_busy
import com.kapp.tenon.generated.resources.workspace_group_session_ready
import com.kapp.tenon.ui.components.screenCardText
import org.jetbrains.compose.resources.getString

/**
 * [RelayUiTexts] 的**生产实现**：把领域侧要的语义落成 strings.xml 里的措辞。
 *
 * ## 为什么这个实现住在 `ui/` 而不是放在 App.kt 里当 lambda
 *
 * 它有四个方法、每个都对应一条真实的资源键。塞进 App.kt 会让那个文件多出一段与
 * 布局无关的资源映射；而这段映射**是产品文案的一部分**，与其它 UI 文案同属一层。
 *
 * ## 降级一律用 `runCatching`
 *
 * 与改动前的行为逐字一致：取不到资源就返回空串 / 兜底串，**不抛**。
 * 一条投屏记录、一次戳一碰通知，都比一句文案重要。兜底串里刻意**不含中文**
 * —— 它只在资源真的坏掉时出现，而那时编一句中文会绕开 CJK 门禁与多语言对账。
 */
internal object ResourceUiTexts : RelayUiTexts {

    override suspend fun screenCardText(record: ScreenRecordUi): String =
        runCatching { com.kapp.tenon.ui.components.screenCardText(record) }.getOrNull().orEmpty()

    override suspend fun nudgeNotice(): String =
        runCatching { getString(Res.string.chat_nudge_peer) }.getOrNull().orEmpty()

    override suspend fun workspaceGreeting(agentKind: String, workspaceTitle: String): String =
        runCatching {
            getString(Res.string.workspace_group_initial_greeting, agentKind, workspaceTitle)
        }.getOrNull() ?: ("[" + agentKind + "] " + workspaceTitle)

    override suspend fun workspaceSessionBrief(sessionTitle: String, busy: Boolean): String {
        val stateText = runCatching {
            getString(
                if (busy) Res.string.workspace_group_session_busy
                else Res.string.workspace_group_session_ready,
            )
        }.getOrNull().orEmpty()
        return runCatching {
            getString(Res.string.workspace_group_session_brief, sessionTitle, stateText)
        }.getOrNull() ?: sessionTitle
    }

    override suspend fun invokeTimeoutTitle(agentName: String): String =
        runCatching { getString(Res.string.agent_invoke_timeout_title, agentName) }.getOrNull().orEmpty()

    override suspend fun invokeActionRetryLabel(): String =
        runCatching { getString(Res.string.agent_invoke_action_retry) }.getOrNull().orEmpty()

    override suspend fun invokeActionFallbackLabel(): String =
        runCatching { getString(Res.string.agent_invoke_action_fallback_local) }.getOrNull().orEmpty()

    override suspend fun tagNoMatchTitle(tag: String): String =
        runCatching { getString(Res.string.agent_tag_no_match_title, tag) }.getOrNull().orEmpty()

    override suspend fun agentUnavailableNotice(agentName: String): String =
        runCatching { getString(Res.string.agent_unavailable_notice, agentName) }.getOrNull().orEmpty()

    override suspend fun agentNotCompletedNotice(agentName: String): String =
        runCatching { getString(Res.string.agent_not_completed_notice, agentName) }.getOrNull().orEmpty()

    override suspend fun agentActionDeniedNotice(actor: String, agentName: String): String =
        runCatching { getString(Res.string.agent_action_denied_notice, actor, agentName) }.getOrNull().orEmpty()

    override suspend fun agentEventThinkingOnlyFallback(): String =
        runCatching { getString(Res.string.agent_event_thinking_only_fallback) }.getOrNull().orEmpty()

    // ---- 编排面文案（task-9）：内容语义由 :orchestration 声明，措辞在这里 ----

    override suspend fun adjustOrNewTaskPrompt(excerpt: String): String =
        runCatching { getString(Res.string.orchestration_adjust_or_new_task, excerpt) }.getOrNull().orEmpty()

    override suspend fun sendAsAdjustmentLabel(): String =
        runCatching { getString(Res.string.orchestration_send_as_adjustment) }.getOrNull().orEmpty()

    override suspend fun sendAsNewTaskLabel(): String =
        runCatching { getString(Res.string.orchestration_send_as_new_task) }.getOrNull().orEmpty()

    override suspend fun localAssignee(role: String): String =
        runCatching { getString(Res.string.orchestration_local_assignee, role) }.getOrNull() ?: role

    override suspend fun stageRoles(prototype: Boolean): List<String> = runCatching {
        if (prototype) {
            listOf(
                getString(Res.string.orchestration_stage_proto_1),
                getString(Res.string.orchestration_stage_proto_2),
                getString(Res.string.orchestration_stage_proto_3),
            )
        } else {
            listOf(
                getString(Res.string.orchestration_stage_impl_1),
                getString(Res.string.orchestration_stage_impl_2),
                getString(Res.string.orchestration_stage_impl_3),
            )
        }
    }.getOrNull().orEmpty()

    override suspend fun teamReadyBanner(role: String, seconds: Long): String =
        runCatching { getString(Res.string.orchestration_team_ready, role, seconds) }.getOrNull().orEmpty()

    override suspend fun diffReadyPrompt(): String =
        runCatching { getString(Res.string.orchestration_diff_ready_prompt) }.getOrNull().orEmpty()

    override suspend fun diffReadyDetail(summary: String?, additions: Long, deletions: Long): String =
        runCatching {
            getString(Res.string.orchestration_diff_ready_detail, summary.orEmpty(), additions, deletions)
        }.getOrNull().orEmpty()

    override suspend fun patchMergeHint(kind: String, detail: String): String {
        val key = when (kind) {
            "already-exists" -> Res.string.orchestration_patch_hint_exists
            "does-not-apply" -> Res.string.orchestration_patch_hint_mismatch
            "patch-missing" -> Res.string.orchestration_patch_hint_file_missing
            "workspace-missing" -> Res.string.orchestration_patch_hint_workspace_missing
            else -> Res.string.orchestration_patch_hint_failed
        }
        return runCatching { getString(key, detail.take(160)) }.getOrNull().orEmpty()
    }
}
