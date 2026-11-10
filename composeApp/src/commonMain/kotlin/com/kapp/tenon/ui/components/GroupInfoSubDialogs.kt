// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

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
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.chat_group_confirm_dissolve
import com.kapp.tenon.generated.resources.chat_group_dissolve
import com.kapp.tenon.generated.resources.chat_group_add_no_peers
import com.kapp.tenon.generated.resources.chat_group_add_title
import com.kapp.tenon.generated.resources.chat_group_announce_hint
import com.kapp.tenon.generated.resources.chat_group_announce_label
import com.kapp.tenon.generated.resources.chat_group_confirm_leave
import com.kapp.tenon.generated.resources.chat_group_confirm_remove
import com.kapp.tenon.generated.resources.chat_group_desc_hint
import com.kapp.tenon.generated.resources.chat_group_desc_label
import com.kapp.tenon.generated.resources.chat_group_desc_path_warning
import com.kapp.tenon.generated.resources.chat_group_edit_meta
import com.kapp.tenon.generated.resources.chat_group_edit_save
import com.kapp.tenon.generated.resources.chat_group_leave
import com.kapp.tenon.generated.resources.chat_group_remove_member
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.common_confirm
import com.kapp.tenon.generated.resources.common_offline
import com.kapp.tenon.generated.resources.common_online
import org.jetbrains.compose.resources.stringResource

import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.domain.model.PeerUi

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
                    // 高度上限走比例收口（小屏/横屏）：默认档 240dp。
                    RelayDialogContentHeight(defaultMaxHeight = 240.dp) { cap ->
                        LazyColumn(Modifier.heightIn(max = cap)) {
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

/**
 * 「解散群聊」的前置确认（仅群主）。
 *
 * 与 [ConfirmLeaveGroupDialog] 分成两个对话框，因为**后果不同**：退出只是自己离开
 * （群对别人继续存在，群主退出还会转交群主）；解散对**所有成员**生效，且不可撤销。
 * 把两者塞进同一个对话框，用户点危险按钮时根本分不清自己在做哪一件。
 */
@Composable
internal fun ConfirmDissolveGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_group_dissolve)) },
        content = {
            Text(
                text = stringResource(Res.string.chat_group_confirm_dissolve),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.chat_group_dissolve),
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
                // 简介会被**原样同步给全群**：填了本机路径就等于把目录结构发出去。
                // 不拦（那是用户的内容），只在他看得见的地方说一次。
                if (looksLikeLocalPath(editDesc)) {
                    Text(
                        text = stringResource(Res.string.chat_group_desc_path_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
 * 默认名由 `AgentMemberName.base` 生成（`dsh-coder`），冲突时自动加序号。
 * **不给手改入口**（产品决定：默认生成唯一名字，后面再加的不允许冲突）。
 *
 * 上一版这里还有一个 `AddGroupWorkspaceDialog`（同形状、按勾选提交 wsId 列表）——
 * 全仓 0 调用点（真实的暴露入口是 `SetGroupAgentWorkspacesDialog`，挂在 [GroupInfoDialog] 里），
 * 已删除：一份没人走的第二实现只会让人在改「加 agent」交互时改错那一份。
 */

// ── 群简介里的本机路径提醒（M5 真机发现的缺陷）─────────────────────────────

/**
 * 这段文字里有没有「本机绝对路径」的形态。
 *
 * **真机现场**：群简介被填成了本机仓库目录，然后**原样同步给了全群** ——
 * 对别人来说那串字符既没有意义，又暴露了本机的目录结构（`/Volumes/…`、用户名、
 * 仓库名全在里面）。它不是授权面板的 `wsPath`（那条 M1-T3 已经收口），
 * 而是**群简介字段本身**被填成了路径。
 *
 * 这里**不做拦截**（简介是用户的内容，拦了会莫名其妙），只做提醒 ——
 * 编辑时看见就告诉他这段会同步给所有成员。
 *
 * 判据保守：只认「像路径」的 token，且要求它**以分隔符开头**
 * （`/` 后还有一段、`~/`、`X:\`）。这样 `2026/10/30`、`1/2` 这类日期与比例不会误报。
 */
internal fun looksLikeLocalPath(text: String): Boolean {
    if (text.isBlank()) return false
    return text.split(PATH_TOKEN_SEPARATORS).any { raw ->
        val token = raw.trim(*PATH_TRIM_CHARS)
        when {
            token.length < 3 -> false
            token.startsWith("~/") -> true
            token.startsWith("/") -> token.drop(1).contains('/')
            PATH_DRIVE_PREFIX.containsMatchIn(token) -> true
            else -> false
        }
    }
}

private val PATH_TOKEN_SEPARATORS = Regex("[\\s,;]+")

/** 只剥 ASCII 引号与括号：中日韩标点不进字面量（CJK 门禁的边界，也让判据不依赖语言）。 */
private val PATH_TRIM_CHARS = charArrayOf('"', '\'', '`', '(', ')')

private val PATH_DRIVE_PREFIX = Regex("^[A-Za-z]:[\\\\/]")
