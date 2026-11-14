// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

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
import androidx.compose.ui.text.font.FontFamily
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.assistant_name_label
import com.kapp.tenon.generated.resources.assistant_persona_desc
import com.kapp.tenon.generated.resources.assistant_persona_title
import com.kapp.tenon.generated.resources.assistant_tasks_shortcut
import com.kapp.tenon.generated.resources.conv_info_assistant_title
import com.kapp.tenon.generated.resources.conv_info_node_id
import com.kapp.tenon.generated.resources.conv_info_peer_title
import com.kapp.tenon.generated.resources.conv_info_remark_label
import com.kapp.tenon.generated.resources.persona_architect
import com.kapp.tenon.generated.resources.persona_docs
import com.kapp.tenon.generated.resources.persona_leader
import com.kapp.tenon.generated.resources.persona_tester
import com.kapp.tenon.generated.resources.quest_input_dialog_cancel
import com.kapp.tenon.generated.resources.quest_input_dialog_confirm
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/** 预置员工名册项。 */
internal data class PersonaRosterItem(
    val key: String,
    val nameRes: org.jetbrains.compose.resources.StringResource,
)

internal val DEFAULT_PERSONA_ROSTER: List<PersonaRosterItem> = listOf(
    PersonaRosterItem("leader", Res.string.persona_leader),
    PersonaRosterItem("architect", Res.string.persona_architect),
    PersonaRosterItem("tester", Res.string.persona_tester),
    PersonaRosterItem("docs", Res.string.persona_docs),
)

/**
 * 1:1 会话详情对话框：
 * 1. 普通私聊：修改好友备注、查看节点 ID；
 * 2. 私人助理：修改助理昵称、在员工名册中切换当班角色、配置云端备用 Key、快捷查看关联自动化任务。
 */
@Composable
internal fun ConversationInfoDialog(
    isAssistant: Boolean,
    nodeId: String,
    currentDisplayName: String,
    currentRemark: String = "",
    currentAssistantRoleKey: String? = null,
    automationTaskCount: Int = 0,
    onDismiss: () -> Unit,
    onSaveRemark: ((String) -> Unit)? = null,
    onSaveAssistantConfig: ((customName: String?, roleKey: String?) -> Unit)? = null,
    onOpenAutomationConsole: (() -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    var remarkText by remember { mutableStateOf(currentRemark) }
    var assistantNameText by remember { mutableStateOf(currentDisplayName) }
    var selectedRoleKey by remember { mutableStateOf(currentAssistantRoleKey ?: "leader") }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isAssistant) Res.string.conv_info_assistant_title else Res.string.conv_info_peer_title,
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
                if (isAssistant) {
                    OutlinedTextField(
                        value = assistantNameText,
                        onValueChange = { assistantNameText = it },
                        label = { Text(stringResource(Res.string.assistant_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Text(
                        text = stringResource(Res.string.assistant_persona_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                    Text(
                        text = stringResource(Res.string.assistant_persona_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    DEFAULT_PERSONA_ROSTER.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRoleKey = item.key },
                        ) {
                            RadioButton(
                                selected = selectedRoleKey == item.key,
                                onClick = { selectedRoleKey = item.key },
                            )
                            Text(
                                text = stringResource(item.nameRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = sp.xs),
                            )
                        }
                    }

                    if (onOpenAutomationConsole != null) {
                        AppTextButton(
                            text = stringResource(Res.string.assistant_tasks_shortcut, automationTaskCount),
                            onClick = {
                                onDismiss()
                                onOpenAutomationConsole()
                            },
                            size = AppButtonSize.Medium,
                            modifier = Modifier.padding(top = sp.xs),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.conv_info_node_id),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = nodeId,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    OutlinedTextField(
                        value = remarkText,
                        onValueChange = { remarkText = it },
                        label = { Text(stringResource(Res.string.conv_info_remark_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.quest_input_dialog_confirm),
                onClick = {
                    if (isAssistant) {
                        onSaveAssistantConfig?.invoke(
                            assistantNameText.trim().takeIf { it.isNotEmpty() },
                            selectedRoleKey,
                        )
                    } else {
                        onSaveRemark?.invoke(remarkText.trim())
                    }
                    onDismiss()
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
