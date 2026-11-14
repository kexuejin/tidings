// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tenon.core.protocol.CronValidation
import com.kapp.tenon.domain.AutomationTask
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.automation_task_automerge_confirm
import com.kapp.tenon.generated.resources.automation_task_automerge_title
import com.kapp.tenon.generated.resources.automation_task_automerge_unattended
import com.kapp.tenon.generated.resources.automation_task_gate_hint
import com.kapp.tenon.generated.resources.automation_task_gate_label
import com.kapp.tenon.generated.resources.automation_task_create
import com.kapp.tenon.generated.resources.automation_task_cron_error
import com.kapp.tenon.generated.resources.automation_task_cron_label
import com.kapp.tenon.generated.resources.automation_task_edit
import com.kapp.tenon.generated.resources.automation_task_name_label
import com.kapp.tenon.generated.resources.automation_task_persona_label
import com.kapp.tenon.generated.resources.automation_task_prompt_label
import com.kapp.tenon.generated.resources.automation_task_workspace_label
import com.kapp.tenon.generated.resources.quest_input_dialog_cancel
import com.kapp.tenon.generated.resources.quest_input_dialog_confirm
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 自动化定时任务创建与编辑弹窗。
 */
@Composable
internal fun AutomationTaskEditDialog(
    initialTask: AutomationTask? = null,
    onDismiss: () -> Unit,
    onSave: (AutomationTask) -> Unit,
) {
    val sp = LocalAppSpacing.current
    var name by remember { mutableStateOf(initialTask?.name.orEmpty()) }
    var cron by remember { mutableStateOf(initialTask?.cron.orEmpty()) }
    var workspacePath by remember { mutableStateOf(initialTask?.workspacePath.orEmpty()) }
    var prompt by remember { mutableStateOf(initialTask?.prompt.orEmpty()) }
    var assignedPersona by remember { mutableStateOf(initialTask?.assignedPersona.orEmpty()) }
    var gateCommand by remember { mutableStateOf(initialTask?.gateCommand.orEmpty()) }
    var autoMerge by remember { mutableStateOf(initialTask?.autoMerge ?: false) }
    val cronError = remember(cron) {
        if (cron.isBlank()) null else CronValidation.validate(cron)
    }

    val isEditing = initialTask != null

    RelayDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isEditing) Res.string.automation_task_edit else Res.string.automation_task_create,
                ),
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.automation_task_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = cron,
                    onValueChange = { cron = it },
                    label = { Text(stringResource(Res.string.automation_task_cron_label)) },
                    isError = cronError != null,
                    supportingText = if (cronError != null) {
                        { Text(stringResource(Res.string.automation_task_cron_error), color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = workspacePath,
                    onValueChange = { workspacePath = it },
                    label = { Text(stringResource(Res.string.automation_task_workspace_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = assignedPersona,
                    onValueChange = { assignedPersona = it },
                    label = { Text(stringResource(Res.string.automation_task_persona_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = gateCommand,
                    onValueChange = { gateCommand = it },
                    label = { Text(stringResource(Res.string.automation_task_gate_label)) },
                    placeholder = { Text(stringResource(Res.string.automation_task_gate_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(Res.string.automation_task_prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )

                Text(
                    text = stringResource(Res.string.automation_task_automerge_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = sp.xs),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { autoMerge = false },
                ) {
                    RadioButton(
                        selected = !autoMerge,
                        onClick = { autoMerge = false },
                    )
                    Text(
                        text = stringResource(Res.string.automation_task_automerge_confirm),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = sp.xs),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { autoMerge = true },
                ) {
                    RadioButton(
                        selected = autoMerge,
                        onClick = { autoMerge = true },
                    )
                    Text(
                        text = stringResource(Res.string.automation_task_automerge_unattended),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = sp.xs),
                    )
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.quest_input_dialog_confirm),
                enabled = name.isNotBlank() && prompt.isNotBlank() && cronError == null,
                onClick = {
                    val now = com.kapp.tenon.core.nowMillis()
                    val taskId = initialTask?.id ?: com.kapp.tenon.core.protocol.newId()
                    val task = AutomationTask(
                        id = taskId,
                        name = name.trim(),
                        cron = cron.trim(),
                        workspacePath = workspacePath.trim(),
                        prompt = prompt.trim(),
                        assignedPersona = assignedPersona.trim(),
                        gateCommand = gateCommand.trim(),
                        autoMerge = autoMerge,
                        enabled = initialTask?.enabled ?: true,
                        createdAt = initialTask?.createdAt ?: now,
                        updatedAt = now,
                        lastRunAt = initialTask?.lastRunAt,
                        lastStatus = initialTask?.lastStatus,
                    )
                    onSave(task)
                },
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.quest_input_dialog_cancel),
                onClick = onDismiss,
            )
        },
    )
}
