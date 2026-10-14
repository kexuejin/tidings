// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package app.relay.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.core.nowMillis
import app.relay.generated.resources.Res
import app.relay.generated.resources.agent_approval_allow
import app.relay.generated.resources.agent_approval_deny
import app.relay.generated.resources.agent_approval_expired
import app.relay.generated.resources.agent_approval_status_approved
import app.relay.generated.resources.agent_approval_status_denied
import app.relay.generated.resources.agent_approval_title
import app.relay.generated.resources.agent_approval_tool_label
import app.relay.generated.resources.agent_artifact_no_file
import app.relay.generated.resources.agent_artifacts_title
import app.relay.generated.resources.agent_count_items
import app.relay.generated.resources.agent_queue_cancel
import app.relay.generated.resources.agent_queue_title
import app.relay.generated.resources.agent_run_state_cancelled
import app.relay.generated.resources.agent_run_state_failed
import app.relay.generated.resources.agent_run_state_running
import app.relay.generated.resources.agent_run_state_succeeded
import app.relay.generated.resources.agent_runs_title
import app.relay.ui.components.SectionHeader
import app.relay.ui.components.formatClock
import app.relay.ui.components.formatCount
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.stringResource
import app.relay.app.model.AgentApprovalUi
import app.relay.app.model.AgentArtifactUi
import app.relay.app.model.AgentQueueTaskUi
import app.relay.app.model.AgentRunUi
import app.relay.ui.theme.relayCardShadow

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
@Composable
public fun AgentApprovalsSection(
    approvals: List<AgentApprovalUi>,
    onRespond: ((approvalId: String, allow: Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (approvals.isEmpty()) return

    val spacing = LocalAppSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader(
            text = stringResource(Res.string.agent_approval_title),
            // 原型 `m11-agents` 在这一节右上角有「3 项」；空表时整段不画（上面已 return），
            // 所以这里的计数只在"这一节真的在"的时候出现，与图一致。
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
                ApprovalRow(approval = approval, now = now, onRespond = onRespond)
            }
        }
    }
}

/** 审批**一条**（行，不是卡）：`工具: {tool}` + 摘要 + 首行右端的两颗按钮 / 状态胶囊。 */
@Composable
private fun ApprovalRow(
    approval: AgentApprovalUi,
    now: Long,
    onRespond: ((approvalId: String, allow: Boolean) -> Unit)?,
) {
    val spacing = LocalAppSpacing.current
    val isExpired = approval.expiresAt > 0L && now > approval.expiresAt
    val statusText = when {
        approval.status == "approved" -> stringResource(Res.string.agent_approval_status_approved)
        approval.status == "denied" -> stringResource(Res.string.agent_approval_status_denied)
        isExpired -> stringResource(Res.string.agent_approval_expired)
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg14, vertical = spacing.md10),
        horizontalArrangement = Arrangement.spacedBy(spacing.md10),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${stringResource(Res.string.agent_approval_tool_label)}: ${approval.tool ?: "—"}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            approval.summary?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }
        }

        // 首行右端那一格：有待办时是两颗按钮，办完了换成状态胶囊 —— 两种形态占同一格，
        // 所以竖着扫下来这一列始终是一条直线（原型 `.appr .rt` 的注释，`prototype/relay.html:444`）。
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (approval.status == "pending" && !isExpired && onRespond != null) {
                OutlinedButton(onClick = { onRespond(approval.approvalId, false) }) {
                    Text(stringResource(Res.string.agent_approval_deny))
                }
                Button(onClick = { onRespond(approval.approvalId, true) }) {
                    Text(stringResource(Res.string.agent_approval_allow))
                }
            } else if (statusText != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (approval.status == "approved") {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (approval.status == "approved") {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
                    )
                }
            }
        }
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
            OutlinedButton(onClick = { onCancelTask(task.taskId) }) {
                Text(stringResource(Res.string.agent_queue_cancel))
            }
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
