// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_group_add_no_peers
import app.relay.generated.resources.chat_group_add_title
import app.relay.generated.resources.chat_group_announce_hint
import app.relay.generated.resources.chat_group_announce_label
import app.relay.generated.resources.chat_group_confirm_leave
import app.relay.generated.resources.chat_group_confirm_remove
import app.relay.generated.resources.chat_group_desc_hint
import app.relay.generated.resources.chat_group_desc_label
import app.relay.generated.resources.chat_group_edit_meta
import app.relay.generated.resources.chat_group_edit_save
import app.relay.generated.resources.chat_group_leave
import app.relay.generated.resources.chat_group_remove_member
import app.relay.generated.resources.common_cancel
import app.relay.generated.resources.common_confirm
import app.relay.generated.resources.common_offline
import app.relay.generated.resources.common_online
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
import app.relay.app.model.PeerUi

@Composable
internal fun AddGroupMembersDialog(
    peers: List<PeerUi>,
    existingMembers: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val candidates = remember(peers, existingMembers) {
        peers.filter { it.nodeId !in existingMembers }
    }
    val chosen = remember { mutableStateListOf<String>() }

    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_group_add_title)) },
        content = {
            Column {
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.chat_group_add_no_peers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(candidates, key = { it.nodeId }) { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!chosen.remove(peer.nodeId)) chosen.add(peer.nodeId)
                                    }
                                    .padding(vertical = LocalAppSpacing.current.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = peer.nodeId in chosen, onCheckedChange = null)
                                Column(Modifier.weight(1f).padding(start = LocalAppSpacing.current.sm)) {
                                    Text(
                                        text = peer.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(
                                            if (peer.online) Res.string.common_online else Res.string.common_offline,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                text = stringResource(Res.string.common_confirm),
                onClick = { onConfirm(chosen.toList()) },
                primary = true,
                enabled = chosen.isNotEmpty(),
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

@Composable
internal fun ConfirmRemoveMemberDialog(
    targetNick: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_group_confirm_remove)) },
        content = {
            Text(
                text = targetNick,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            // 原型 `.btn.danger-o`（描边 + `--on-error-c` 文字）—— 危险动作。
            RelayDialogButton(
                text = stringResource(Res.string.chat_group_remove_member),
                onClick = onConfirm,
                danger = true,
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

@Composable
internal fun ConfirmLeaveGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_group_leave)) },
        content = {
            Text(
                text = stringResource(Res.string.chat_group_confirm_leave),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.chat_group_leave),
                onClick = onConfirm,
                danger = true,
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

@Composable
internal fun EditGroupMetaDialog(
    initialDesc: String,
    initialAnnounce: String,
    onDismiss: () -> Unit,
    onConfirm: (desc: String?, announce: String?) -> Unit,
) {
    var editDesc by remember { mutableStateOf(initialDesc) }
    var editAnnounce by remember { mutableStateOf(initialAnnounce) }

    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_group_edit_meta)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
            ) {
                OutlinedTextField(
                    value = editDesc,
                    onValueChange = { if (it.length <= 200) editDesc = it },
                    label = { Text(stringResource(Res.string.chat_group_desc_label)) },
                    placeholder = { Text(stringResource(Res.string.chat_group_desc_hint)) },
                    supportingText = { Text("${editDesc.length}/200") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editAnnounce,
                    onValueChange = { if (it.length <= 1024) editAnnounce = it },
                    label = { Text(stringResource(Res.string.chat_group_announce_label)) },
                    placeholder = { Text(stringResource(Res.string.chat_group_announce_hint)) },
                    supportingText = { Text("${editAnnounce.length}/1024") },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.chat_group_edit_save),
                primary = true,
                onClick = {
                    onConfirm(
                        editDesc.trim().ifEmpty { null },
                        editAnnounce.trim().ifEmpty { null },
                    )
                },
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}
