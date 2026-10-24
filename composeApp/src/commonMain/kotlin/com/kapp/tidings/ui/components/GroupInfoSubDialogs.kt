// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_group_add_no_peers
import com.kapp.tidings.generated.resources.chat_group_add_title
import com.kapp.tidings.generated.resources.chat_group_announce_hint
import com.kapp.tidings.generated.resources.chat_group_announce_label
import com.kapp.tidings.generated.resources.chat_group_confirm_leave
import com.kapp.tidings.generated.resources.chat_group_confirm_remove
import com.kapp.tidings.generated.resources.chat_group_desc_hint
import com.kapp.tidings.generated.resources.chat_group_desc_label
import com.kapp.tidings.generated.resources.chat_group_edit_meta
import com.kapp.tidings.generated.resources.chat_group_edit_save
import com.kapp.tidings.generated.resources.chat_group_leave
import com.kapp.tidings.generated.resources.chat_group_remove_member
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.common_confirm
import com.kapp.tidings.generated.resources.common_offline
import com.kapp.tidings.generated.resources.common_online
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.domain.model.PeerUi

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
                    // **空串 = 清空，不是"不改"。**
                    //
                    // 过去 `ifEmpty { null }` 把空串折成 null，而控制器（handleUpdateGroupMeta）
                    // 对 null 的解释是「保持原值」—— 于是简介/公告**永远无法清空**：
                    // 用户删光文字点保存，界面毫无反应，旧文案还挂在群里。
                    //
                    // 协议面支持清空：`GroupMeta.description/announce` 的 KDoc 明写
                    // 「空串表示未设置」，`validateGroup` 对这两个字段用 `isStrAllowEmpty`
                    // （空串合法、照发），解码侧 `strAllowEmptyOrNull` 把空串解成 ""
                    // —— 全量快照按 rev 覆盖，对端跟着清空。
                    // null 仍然是「这个字段这次不动」（只改公告时简介不被误清）。
                    onConfirm(
                        editDesc.trim(),
                        editAnnounce.trim(),
                    )
                },
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

/**
 * 「添加智能体」对话框：列出**本机可以暴露给这个群**的 workspace。
 *
 * 暴露单位是 `(agentId, wsId)` —— 一台设备可以装多个 agent，不同 agent 也可能操作同一个
 * workspace（见 `VirtualAgentMember` 的 KDoc）。这里每一行就是一个可被 @ 的成员。
 *
 * 默认名由 `AgentMemberName.base` 生成（`dsh-teahouse-mobile`），冲突时自动加序号。
 * **不给手改入口**（产品决定：默认生成唯一名字，后面再加的不允许冲突）。
 *
 * 上一版这里还有一个 `AddGroupWorkspaceDialog`（同形状、按勾选提交 wsId 列表）——
 * 全仓 0 调用点（真实的暴露入口是 `ExposeAgentDialog`，挂在 [GroupInfoDialog] 里），
 * 已删除：一份没人走的第二实现只会让人在改「加 agent」交互时改错那一份。
 */

