// SPDX-License-Identifier: GPL-3.0-only
package app.relay.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.relay.app.model.AgentRunUi
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_group_progress_working
import app.relay.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 群顶部 Agent 协同工作状态指示条（Goal 5 / PPE-2）：
 * 当群内有活跃的 Agent 正在执行任务时，在群聊顶部常驻展示动态工作指示，如 `[Coder 正在编译核心模块...]`。
 */
@Composable
fun GroupProgressBar(
    agentName: String,
    activeRun: AgentRunUi,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalAppSpacing.current
    val taskSummary = activeRun.note?.takeIf { it.isNotBlank() } ?: activeRun.state

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp),
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
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
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
        }
    }
}
