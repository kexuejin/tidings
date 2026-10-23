// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.agent_approval_allow
import com.kapp.tidings.generated.resources.agent_approval_deny
import com.kapp.tidings.generated.resources.agent_approval_expired
import com.kapp.tidings.generated.resources.agent_approval_readonly
import com.kapp.tidings.generated.resources.agent_approval_status_approved
import com.kapp.tidings.generated.resources.agent_approval_status_denied
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import com.kapp.tidings.ui.components.AppButton
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.RelayDialog
import com.kapp.tidings.ui.components.RelayDialogButton
import com.kapp.tidings.generated.resources.agent_approval_allow_session
import com.kapp.tidings.generated.resources.agent_approval_diff_title
import com.kapp.tidings.generated.resources.agent_approval_risk_l1
import com.kapp.tidings.generated.resources.agent_approval_risk_l2
import com.kapp.tidings.generated.resources.agent_approval_risk_l3
import com.kapp.tidings.generated.resources.agent_approval_status_allowed_session
import com.kapp.tidings.generated.resources.agent_approval_title
import com.kapp.tidings.generated.resources.agent_approval_tool_label
import com.kapp.tidings.generated.resources.agent_approval_view_diff
import com.kapp.tidings.generated.resources.agent_artifact_no_file
import com.kapp.tidings.generated.resources.agent_artifacts_title
import com.kapp.tidings.generated.resources.agent_count_items
import com.kapp.tidings.generated.resources.agent_queue_cancel
import com.kapp.tidings.generated.resources.agent_queue_title
import com.kapp.tidings.generated.resources.agent_run_state_cancelled
import com.kapp.tidings.generated.resources.agent_run_state_failed
import com.kapp.tidings.generated.resources.agent_run_state_running
import com.kapp.tidings.generated.resources.agent_run_state_succeeded
import com.kapp.tidings.generated.resources.agent_runs_title
import com.kapp.tidings.generated.resources.common_close
import com.kapp.tidings.ui.components.SectionHeader
import com.kapp.tidings.ui.components.formatClock
import com.kapp.tidings.ui.components.formatCount
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.LocalCardShape
import com.kapp.tidings.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentArtifactUi
import com.kapp.tidings.domain.model.AgentQueueTaskUi
import com.kapp.tidings.domain.model.AgentRunUi

/**
 * 待处理 Agent 审批请求列表（PPE-1 §6.5）。
 *
 * **版面照原型**：整段**一张卡**、一行一条、行间发丝线，按钮并到首行右端
 * （`prototype/relay.html:431-449` 的 `.lst` / `.appr` / `.appr .rt`，以及
 * `apprSectionHTML`（`:2206`）：「整段一张卡、行间发丝线」）。
 *
 * 原型在那一段里**逐字写明这是它刻意偏离源码的一处**，理由是可读性：
 * 「源码每条是独立一张 Card（自己的阴影 + 14dp 内边距 + 按钮另起一行），单条 112px、
 * 三条 336px —— 手机上「待处理审批」这一块自己就吃掉整屏，而那三条里真正要读的字只有
 * 两行。改成一张 `.lst` 卡 + 行间发丝线，按钮并到首行右端：单条 61px，且**按钮跨条目
 * 竖直对齐**，连点几下批准不会点飞。」（`prototype/relay.html:420-430`；偏离记在原型
 * README §12）
 * 样式按原型收敛为单卡列表行结构以提升空间利用率。
 */
internal fun resolveRiskLevel(risk: String?, tool: String?): String {
    if (!risk.isNullOrBlank()) return risk.uppercase()
    val t = tool?.lowercase().orEmpty()
    return when {
        t.contains("bash") || t.contains("exec") || t.contains("command") || t.contains("shell") || t.contains("terminal") -> "L3"
        t.contains("write") || t.contains("edit") || t.contains("replace") || t.contains("delete") || t.contains("remove") -> "L2"
        else -> "L1"
    }
}

@Composable
internal fun RiskLevelBadge(level: String, modifier: Modifier = Modifier) {
    val (labelRes, containerColor, contentColor) = when (level) {
        "L3" -> Triple(
            Res.string.agent_approval_risk_l3,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        "L2" -> Triple(
            Res.string.agent_approval_risk_l2,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        else -> Triple(
            Res.string.agent_approval_risk_l1,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
public fun AgentApprovalsSection(
    approvals: List<AgentApprovalUi>,
    onRespond: ((approvalId: String, allow: Boolean) -> Unit)? = null,
    onRespondChoice: ((approvalId: String, allow: Boolean, allowSession: Boolean) -> Unit)? = null,
    isReadOnly: ((AgentApprovalUi) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    if (approvals.isEmpty()) return

    val spacing = LocalAppSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader(
            text = stringResource(Res.string.agent_approval_title),
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(approvals.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        val now = nowMillis()
        SectionListCard {
            approvals.forEachIndexed { index, approval ->
                if (index > 0) SectionListDivider()
                ApprovalRow(
                    approval = approval,
                    now = now,
                    onRespond = onRespond,
                    onRespondChoice = onRespondChoice,
                    isReadOnly = isReadOnly?.invoke(approval) == true,
                )
            }
        }
    }
}

/** 审批**一条**（行）：风险等级 + `工具: {tool}` + 摘要 + Diff查看 + 首行右端的按钮 / 状态胶囊。 */
@Composable
private fun ApprovalRow(
    approval: AgentApprovalUi,
    now: Long,
    onRespond: ((approvalId: String, allow: Boolean) -> Unit)?,
    onRespondChoice: ((approvalId: String, allow: Boolean, allowSession: Boolean) -> Unit)? = null,
    isReadOnly: Boolean = false,
) {
    val spacing = LocalAppSpacing.current
    val isExpired = approval.expiresAt > 0L && now > approval.expiresAt
    var showDiffDialog by remember { mutableStateOf(false) }

    val statusText = when {
        approval.status == "approved" -> stringResource(Res.string.agent_approval_status_approved)
        approval.status == "allow_session" -> stringResource(Res.string.agent_approval_status_allowed_session)
        approval.status == "denied" -> stringResource(Res.string.agent_approval_status_denied)
        isExpired -> stringResource(Res.string.agent_approval_expired)
        else -> null
    }

    val riskLevel = resolveRiskLevel(approval.riskLevel, approval.tool)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg14, vertical = spacing.md10),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    RiskLevelBadge(riskLevel)
                    Text(
                        text = "${stringResource(Res.string.agent_approval_tool_label)}: ${approval.tool ?: "—"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                approval.summary?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.xxs),
                    )
                }
            }

            // 首行右端：操作按钮或状态胶囊
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (approval.status == "pending" && !isExpired && isReadOnly) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = stringResource(Res.string.agent_approval_readonly),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
                        )
                    }
                } else if (approval.status == "pending" && !isExpired && (onRespond != null || onRespondChoice != null)) {
                    // 原型这一行是 `.btn sm out` + `.btn sm pri`（`prototype/relay.html:2223`）：
                    // 审批行是列表行，三颗都走小档。拒绝用中性描边（原型是 `.out`，不是 `.danger-o`）。
                    AppOutlinedButton(
                        text = stringResource(Res.string.agent_approval_deny),
                        onClick = {
                            if (onRespondChoice != null) onRespondChoice(approval.approvalId, false, false)
                            else onRespond?.invoke(approval.approvalId, false)
                        },
                        size = AppButtonSize.Small,
                    )
                    AppButton(
                        text = stringResource(Res.string.agent_approval_allow),
                        onClick = {
                            if (onRespondChoice != null) onRespondChoice(approval.approvalId, true, false)
                            else onRespond?.invoke(approval.approvalId, true)
                        },
                        size = AppButtonSize.Small,
                    )
                    if (onRespondChoice != null) {
                        // 同一行里语义最低的一颗（只在本次会话内免审）：走同一小档，
                        // 不再单独压字阶 —— 层级靠 tone 与位置表达，不靠第五个字号。
                        AppTextButton(
                            text = stringResource(Res.string.agent_approval_allow_session),
                            onClick = { onRespondChoice(approval.approvalId, true, true) },
                            size = AppButtonSize.Small,
                        )
                    }
                } else if (statusText != null) {
                    val badgeColor = when (approval.status) {
                        "approved" -> MaterialTheme.colorScheme.primaryContainer
                        "allow_session" -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                    val textColor = when (approval.status) {
                        "approved" -> MaterialTheme.colorScheme.onPrimaryContainer
                        "allow_session" -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onErrorContainer
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor,
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
                        )
                    }
                }
            }
        }

        // 如果包含 diff / 详细参数变更，提供点击查看入口
        if (!approval.diff.isNullOrBlank()) {
            Text(
                text = stringResource(Res.string.agent_approval_view_diff),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = spacing.xs)
                    .clickable { showDiffDialog = true },
            )
        }
    }

    if (showDiffDialog) {
        RelayDialog(
            onDismissRequest = { showDiffDialog = false },
            title = { Text(stringResource(Res.string.agent_approval_diff_title)) },
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = approval.diff.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }
            },
            confirmButton = {
                RelayDialogButton(
                    onClick = { showDiffDialog = false },
                    text = stringResource(Res.string.common_close),
                )
            },
        )
    }
}

/**
 * Agent 任务队列列表（PPE-1 §6.3）。
 *
 * 与审批同一张形状 —— 原型把两者写在同一个注释里：「审批与队列：整段**一张卡**，一行一条」
 * （`prototype/relay.html:420-423`）：一张 `.lst` 卡 + 行间发丝线。
 *
 * **一处保留差异**：原型的队列行只画 `{op}: {taskId 前 12 位}` + 行尾时刻 + `prompt`
 * （`.qk`，`:453-458`），没有取消按钮；源码的取消能力是真的（`AgentQueueSection` 的
 * `onCancelTask`），所以这里把它留在**行尾**而不是删掉 —— 删功能去对齐一张没画它的原型
 * 是本末倒置。
 */
@Composable
public fun AgentQueueSection(
    tasks: List<AgentQueueTaskUi>,
    onCancelTask: ((taskId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) return

    val spacing = LocalAppSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader(
            text = stringResource(Res.string.agent_queue_title),
            // 同审批节：原型 `m11-agents` 在「任务队列」右上角有「3 项」。
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(tasks.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        SectionListCard {
            tasks.forEachIndexed { index, task ->
                if (index > 0) SectionListDivider()
                QueueRow(task = task, onCancelTask = onCancelTask)
            }
        }
    }
}

/** 队列**一条**（行，不是卡）：`{op}: {taskId 前 12 位}` + 行尾时刻 / `prompt` / 取消。 */
@Composable
private fun QueueRow(task: AgentQueueTaskUi, onCancelTask: ((taskId: String) -> Unit)?) {
    val spacing = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg14, vertical = spacing.md10),
        horizontalArrangement = Arrangement.spacedBy(spacing.md10),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${task.op}: ${task.taskId.take(12)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (task.queuedAt > 0L) {
                    Text(
                        text = formatClock(task.queuedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            task.prompt?.let { prompt ->
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
        if (onCancelTask != null) {
            // 队列卡的行末动作：原型队列行没有按钮，这里按「列表行内动作」走小档，
            // 与同屏审批行的 `.btn sm` 保持同一级。
            AppOutlinedButton(
                text = stringResource(Res.string.agent_queue_cancel),
                onClick = { onCancelTask(task.taskId) },
                size = AppButtonSize.Small,
            )
        }
    }
}

/**
 * 原型的 `.lst`（`prototype/relay.html:431-432`）：**整段一张卡、行间发丝线**。
 *
 * `.card{background:var(--surface);border-radius:var(--r-card);box-shadow:var(--shadow)}`
 * （`:119`）+ `.lst > * + *{border-top:1px solid var(--hair)}`（`:432`）。
 */
@Composable
private fun SectionListCard(content: @Composable () -> Unit) {
    val shape = LocalCardShape.current
    Card(
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .relayCardShadow(shape = shape),
    ) {
        Column { content() }
    }
}

/** 行间发丝线：原型 `.lst > * + *{border-top:1px solid var(--hair)}`（`prototype/relay.html:432`）。 */
@Composable
private fun SectionListDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}


/**
 * Agent 执行状态列表（PPE-1 §6.4 的 `agent-run`）。
 *
 * 每一行是「某个 runId 现在到哪一步了」。`state` 只认识四档时给本地化文案，
 * **不认识就原样透传** —— 协议允许对端多报一档，界面不该因此显示空白。
 */
@Composable
public fun AgentRunsSection(
    runs: List<AgentRunUi>,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
        SectionHeader(
            text = stringResource(Res.string.agent_runs_title),
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(runs.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        runs.forEach { run ->
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .relayCardShadow(shape = RoundedCornerShape(12.dp)),
            ) {
                Column(modifier = Modifier.padding(LocalAppSpacing.current.lg14)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = run.runId.take(12),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = runStateLabel(run.state),
                            style = MaterialTheme.typography.bodySmall,
                            color = runStateColor(run.state),
                        )
                    }
                    run.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Spacer(Modifier.height(LocalAppSpacing.current.xs))
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Agent 产物元数据列表（PPE-1 §6.7 的 `agent-artifact`）。
 *
 * **只列元数据**。`fileId` 为空时明说"只有元数据，没有关联文件" —— 因为这一帧按协议
 * 就是不传二进制的，界面上不能让人以为点一下能打开。
 */
@Composable
public fun AgentArtifactsSection(
    artifacts: List<AgentArtifactUi>,
    modifier: Modifier = Modifier,
) {
    if (artifacts.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
        SectionHeader(
            text = stringResource(Res.string.agent_artifacts_title),
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(artifacts.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        artifacts.forEach { artifact ->
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .relayCardShadow(shape = RoundedCornerShape(12.dp)),
            ) {
                Column(modifier = Modifier.padding(LocalAppSpacing.current.lg14)) {
                    Text(
                        text = artifact.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(LocalAppSpacing.current.xs))
                    Text(
                        text = artifactSubtitle(artifact),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** `agent-run.state` 的本地化标签；未知取值原样透传（协议允许对端多报一档）。 */
@Composable
private fun runStateLabel(state: String): String = when (state) {
    "running" -> stringResource(Res.string.agent_run_state_running)
    "succeeded" -> stringResource(Res.string.agent_run_state_succeeded)
    "failed" -> stringResource(Res.string.agent_run_state_failed)
    "cancelled" -> stringResource(Res.string.agent_run_state_cancelled)
    else -> state
}

@Composable
private fun runStateColor(state: String) = when (state) {
    "failed" -> MaterialTheme.colorScheme.error
    "cancelled" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 产物副行：`mime · 大小`，没有 `fileId` 时明说只有元数据。 */
@Composable
private fun artifactSubtitle(artifact: AgentArtifactUi): String {
    val size = artifact.bytes?.let { formatCount(it) }
    val parts = listOfNotNull(artifact.mime, size)
    return if (artifact.fileId == null) {
        stringResource(Res.string.agent_artifact_no_file)
    } else {
        parts.joinToString(" · ")
    }
}
