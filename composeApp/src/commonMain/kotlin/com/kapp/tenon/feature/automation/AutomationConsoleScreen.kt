// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.kapp.tenon.domain.AutomationTask
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.automation_console_subtitle
import com.kapp.tenon.generated.resources.automation_console_title
import com.kapp.tenon.generated.resources.automation_empty_desc
import com.kapp.tenon.generated.resources.automation_empty_title
import com.kapp.tenon.generated.resources.automation_task_automerge_confirm
import com.kapp.tenon.generated.resources.automation_task_automerge_unattended
import com.kapp.tenon.generated.resources.automation_task_create
import com.kapp.tenon.generated.resources.automation_task_delete
import com.kapp.tenon.generated.resources.automation_task_edit
import com.kapp.tenon.generated.resources.automation_task_run_now
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.SectionCard
import com.kapp.tenon.ui.centerCapped
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.LargeTitleBar
import com.kapp.tenon.ui.components.TitleBarDivider
import com.kapp.tenon.ui.components.TopBarIconAction
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 自动化任务管理控制台主面板 (Automation Console)。
 */
@Composable
public fun AutomationConsoleScreen(
    tasks: List<AutomationTask>,
    onSaveTask: (AutomationTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onRunTaskNow: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    var editingTask by remember { mutableStateOf<AutomationTask?>(null) }
    var isCreating by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            LargeTitleBar(
                title = stringResource(Res.string.automation_console_title),
                subtitle = stringResource(Res.string.automation_console_subtitle),
                onBack = onBack,
                actions = {
                    TopBarIconAction(
                        icon = Icons.Default.Add,
                        contentDescription = stringResource(Res.string.automation_task_create),
                        onClick = { isCreating = true },
                    )
                },
            )
            TitleBarDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .centerCapped(ContentWidths.Settings)
                    .padding(sp.lg),
                verticalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                if (tasks.isEmpty()) {
                    SectionCard(title = stringResource(Res.string.automation_empty_title)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(sp.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(sp.sm),
                        ) {
                            Text(
                                text = stringResource(Res.string.automation_empty_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AppTextButton(
                                text = stringResource(Res.string.automation_task_create),
                                onClick = { isCreating = true },
                                size = AppButtonSize.Medium,
                                modifier = Modifier.padding(top = sp.sm),
                            )
                        }
                    }
                } else {
                    tasks.forEach { task ->
                        SectionCard(
                            title = task.name,
                            trailing = {
                                Switch(
                                    checked = task.enabled,
                                    onCheckedChange = { onToggleTask(task.id, it) },
                                )
                            },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(sp.md),
                                verticalArrangement = Arrangement.spacedBy(sp.sm),
                            ) {
                                val strategyLabel = stringResource(
                                    if (task.autoMerge) {
                                        Res.string.automation_task_automerge_unattended
                                    } else {
                                        Res.string.automation_task_automerge_confirm
                                    },
                                )
                                Text(
                                    text = strategyLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )

                                if (task.cron.isNotBlank()) {
                                    Text(
                                        text = task.cron,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (task.workspacePath.isNotBlank()) {
                                    Text(
                                        text = task.workspacePath,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Text(
                                    text = task.prompt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(sp.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppTextButton(
                                        text = stringResource(Res.string.automation_task_run_now),
                                        onClick = { onRunTaskNow(task.id) },
                                        size = AppButtonSize.Small,
                                    )
                                    AppTextButton(
                                        text = stringResource(Res.string.automation_task_edit),
                                        onClick = { editingTask = task },
                                        size = AppButtonSize.Small,
                                    )
                                    AppTextButton(
                                        text = stringResource(Res.string.automation_task_delete),
                                        onClick = { onDeleteTask(task.id) },
                                        size = AppButtonSize.Small,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isCreating) {
            AutomationTaskEditDialog(
                initialTask = null,
                onDismiss = { isCreating = false },
                onSave = {
                    onSaveTask(it)
                    isCreating = false
                },
            )
        }

        editingTask?.let { task ->
            AutomationTaskEditDialog(
                initialTask = task,
                onDismiss = { editingTask = null },
                onSave = {
                    onSaveTask(it)
                    editingTask = null
                },
            )
        }
    }
}
