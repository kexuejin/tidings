// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_close
import com.kapp.tidings.generated.resources.ic_chevd
import com.kapp.tidings.generated.resources.workspace_bar_switch
import com.kapp.tidings.generated.resources.workspace_branch_label
import com.kapp.tidings.generated.resources.workspace_new_segment
import com.kapp.tidings.generated.resources.workspace_unassigned
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 工作区顶栏（Goal 4 / PPE-2）：
 * 常驻展示当前会话关联的主机工作区、路径与 Git 分支（如 `📁 teahouse-mobile (git: main 🟢)`），
 * 并提供工作区切换抽屉与重置会话 Prompt 上下文的分段入口。
 */
@Composable
fun WorkspaceBar(
    activeWorkspace: WorkspaceUi?,
    gitBranch: String?,
    availableWorkspaces: List<WorkspaceUi>,
    onSelectWorkspace: (WorkspaceUi) -> Unit,
    onNewSegment: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalAppSpacing.current
    var showPickerDialog by remember { mutableStateOf(false) }

    val displayTitle = activeWorkspace?.wsTitle
        ?: activeWorkspace?.wsPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.workspace_unassigned)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable { showPickerDialog = true },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!gitBranch.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "${stringResource(Res.string.workspace_branch_label)}: $gitBranch",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onNewSegment != null) {
                    AppTextButton(
                        text = stringResource(Res.string.workspace_new_segment),
                        onClick = onNewSegment,
                        size = AppButtonSize.Small,
                    )
                }

                Icon(
                    painter = painterResource(Res.drawable.ic_chevd),
                    contentDescription = stringResource(Res.string.workspace_bar_switch),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizes.Label),
                )
            }
        }
    }

    if (showPickerDialog) {
        WorkspacePickerDialog(
            workspaces = availableWorkspaces,
            activeWsId = activeWorkspace?.wsId,
            onPick = {
                onSelectWorkspace(it)
                showPickerDialog = false
            },
            onDismiss = { showPickerDialog = false },
        )
    }
}

@Composable
internal fun WorkspacePickerDialog(
    workspaces: List<WorkspaceUi>,
    activeWsId: String?,
    onPick: (WorkspaceUi) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalAppSpacing.current
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.workspace_bar_switch)) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (workspaces.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.workspace_unassigned),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.md),
                    )
                } else {
                    workspaces.forEach { ws ->
                        val isSelected = ws.wsId == activeWsId
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(ws) },
                        ) {
                            Column(Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
                                Text(
                                    text = ws.wsTitle ?: ws.wsId,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                                ws.wsPath?.let { path ->
                                    Text(
                                        text = path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_close),
                onClick = onDismiss,
            )
        },
    )
}
