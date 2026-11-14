// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.quest_input_dialog_cancel
import com.kapp.tenon.generated.resources.quest_input_dialog_confirm
import com.kapp.tenon.generated.resources.quest_input_dialog_hint
import com.kapp.tenon.generated.resources.quest_input_dialog_title
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 支线任务人类插话/微调介入回复对话框。
 *
 * 当支线任务处于 WAITING_INPUT 态或机主点击卡片上的【回复微调】动作时唤起，
 * 供机主输入回答并唤醒挂起的支线任务协程。
 */
@Composable
internal fun QuestInterventionDialog(
    questTitle: String,
    prompt: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    var inputText by remember { mutableStateOf("") }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.quest_input_dialog_title)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = questTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!prompt.isNullOrBlank()) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text(stringResource(Res.string.quest_input_dialog_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.quest_input_dialog_confirm),
                enabled = inputText.isNotBlank(),
                onClick = {
                    val trimmed = inputText.trim()
                    if (trimmed.isNotEmpty()) {
                        onSubmit(trimmed)
                    }
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
