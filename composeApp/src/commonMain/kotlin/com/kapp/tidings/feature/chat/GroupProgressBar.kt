// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.model.AgentRunUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_group_progress_working
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import com.kapp.tidings.generated.resources.agent_interrupt_btn

/**
 * 群顶部 Agent 协同工作状态指示条（Goal 5 / PPE-2 / 规范 v3.1）：
 * 当群内有活跃的 Agent 正在执行任务时，在群聊顶部常驻展示动态工作指示，如 `[Coder 正在编译核心模块...]`，
 * 并支持全局一键打断。
 */
@Composable
fun GroupProgressBar(
    agentName: String,
    activeRun: AgentRunUi,
    modifier: Modifier = Modifier,
    onInterrupt: (() -> Unit)? = null,
) {
    val spacing = LocalAppSpacing.current
    val taskSummary = activeRun.note?.takeIf { it.isNotBlank() } ?: activeRun.state

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            CircularProgressIndicator(
                strokeWidth = ProgressIndicatorStroke,
                modifier = Modifier.size(ProgressIndicatorSize),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(Res.string.chat_group_progress_working, agentName, taskSummary),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 按钮**始终画**、没接线时置灰：
            //
            // 从前它接的是 `onSend(peer.nodeId, "/cancel")` —— 一个把控制意图当群文本发的假动作。
            // 改成控制面（agent-cancel）之后，宿主没接线时宁可让人看见"这个动作现在不可用"，
            // 也不要让按钮凭空消失（消失会让人以为"这个群没有在跑的任务"）。
            // 更不要退回"发一条假消息"：那会广播给所有成员，而且根本没中断。
            OutlinedButton(
                onClick = { onInterrupt?.invoke() },
                enabled = onInterrupt != null,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.agent_interrupt_btn),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (onInterrupt != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val ProgressIndicatorStroke = 2.dp
private val ProgressIndicatorSize = 14.dp
