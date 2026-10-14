// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.relay.core.protocol.GroupMeta
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_group_add_member
import app.relay.generated.resources.chat_group_admin_label
import app.relay.generated.resources.chat_group_announce_label
import app.relay.generated.resources.chat_group_desc_label
import app.relay.generated.resources.chat_group_edit_meta
import app.relay.generated.resources.chat_group_id_label
import app.relay.generated.resources.chat_group_info_hint
import app.relay.generated.resources.chat_group_info_sub
import app.relay.generated.resources.chat_group_leave
import app.relay.generated.resources.chat_group_me
import app.relay.generated.resources.chat_group_members_header
import app.relay.generated.resources.chat_group_none
import app.relay.generated.resources.chat_group_online
import app.relay.generated.resources.chat_group_owner_label
import app.relay.generated.resources.chat_group_remove_member
import app.relay.generated.resources.common_close
import app.relay.generated.resources.common_offline
import app.relay.generated.resources.common_online
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
import app.relay.app.model.PeerUi

/**
 * 群信息与成员面板（对话框形态）：展示群名、公告、简介、群主与管理员、成员在线状态，
 * 并支持邀请新成员与群主/管理员移出成员、退出群聊。
 */
@Composable
fun GroupInfoDialog(
    group: GroupMeta,
    selfNodeId: String,
    peers: List<PeerUi>,
    memberOnlineCount: Int,
    onDismiss: () -> Unit,
    onAddMembers: ((List<String>) -> Unit)? = null,
    onRemoveMember: ((String) -> Unit)? = null,
    onUpdateMeta: ((String, String?, String?) -> Unit)? = null,
    onLeaveGroup: (() -> Unit)? = null,
) {
    val peerMap = peers.associateBy { it.nodeId }
    val meText = stringResource(Res.string.chat_group_me)
    val noneText = stringResource(Res.string.chat_group_none)
    val ownerNick = group.ownerId?.let { if (it == selfNodeId) meText else peerMap[it]?.displayName ?: it.take(8) } ?: noneText

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditMetaDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<String?>(null) }
    val isSelfOwner = group.ownerId == selfNodeId || (group.ownerId == null && group.creatorId == selfNodeId)
    val isSelfAdmin = group.adminIds?.contains(selfNodeId) == true
    val canManageMembers = isSelfOwner || isSelfAdmin

    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见同目录 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                // 群名的样式由骨架给（16sp/700）；下面那行副标题自带 `labelSmall`，不受影响。
                Text(text = group.name)
                Text(
                    text = stringResource(Res.string.chat_group_info_sub),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(LocalAppSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PeerAvatar(name = group.name, isAgent = false, size = 44.dp, isGroup = true)
                                Column(Modifier.padding(start = LocalAppSpacing.current.md)) {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = stringResource(
                                            Res.string.chat_group_online,
                                            memberOnlineCount,
                                            group.members.size,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                                        modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                                    ) {
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                                            Text(
                                                text = "rev ${group.rev}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.xxs),
                                            )
                                        }
                                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp)) {
                                            Text(
                                                text = "${stringResource(Res.string.chat_group_owner_label)} $ownerNick",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.xxs),
                                            )
                                        }
                                    }
                                }
                            }

                            val desc = group.description
                            if (!desc.isNullOrBlank()) {
                                Row(Modifier.padding(top = LocalAppSpacing.current.xs)) {
                                    Text(
                                        text = "${stringResource(Res.string.chat_group_desc_label)}: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(text = desc, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            val ann = group.announce
                            if (!ann.isNullOrBlank()) {
                                Row {
                                    Text(
                                        text = "${stringResource(Res.string.chat_group_announce_label)}: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(text = ann, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${stringResource(Res.string.chat_group_id_label)}: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = group.groupId,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    )
                                }
                            }
                            // 入口**另起一行**，不跟 groupId 挤同一行：groupId 是 36 字符定宽
                            // （等宽字体约 464px），手机宽度下同一行只剩 ~95px，按钮的文字会被
                            // 压成 0 宽 —— 真机 uiautomator 里能看到一个 `clickable=true`、
                            // 却**没有任何文字**的 126x344 空块（真机实测，
                            // 1080px 宽的对话框：[802,806]-[928,1150]），界面上就是一个空按钮，
                            // 谁也点不出「编辑群公告与简介」。
                            if (canManageMembers && onUpdateMeta != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        onClick = { showEditMetaDialog = true },
                                        contentPadding = PaddingValues(horizontal = LocalAppSpacing.current.sm, vertical = LocalAppSpacing.current.xxs),
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.chat_group_edit_meta),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LocalAppSpacing.current.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.chat_group_members_header, group.members.size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onAddMembers != null) {
                            TextButton(onClick = { showAddDialog = true }) {
                                Text(stringResource(Res.string.chat_group_add_member), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                items(group.members) { id ->
                    val isMe = id == selfNodeId
                    val peer = peerMap[id]
                    val nick = if (isMe) meText else peer?.displayName ?: id.take(8)
                    val online = if (isMe) true else peer?.online ?: false
                    val isOwner = id == group.ownerId
                    val isAdmin = group.adminIds?.contains(id) == true

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = LocalAppSpacing.current.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
                    ) {
                        PeerAvatar(name = nick, isAgent = peer?.isAgent ?: false, size = 32.dp, avatar = peer?.avatar)
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                            ) {
                                Text(
                                    text = nick,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isMe) {
                                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            text = meText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = 1.dp),
                                        )
                                    }
                                }
                                if (isOwner) {
                                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            text = stringResource(Res.string.chat_group_owner_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = 1.dp),
                                        )
                                    }
                                } else if (isAdmin) {
                                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            text = stringResource(Res.string.chat_group_admin_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "$id · ${if (online) stringResource(Res.string.common_online) else stringResource(Res.string.common_offline)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 移出成员按钮（仅管理员/群主且不是自己、不是群主）
                        if (canManageMembers && !isMe && !isOwner && onRemoveMember != null) {
                            TextButton(
                                onClick = { memberToRemove = id },
                            ) {
                                Text(
                                    text = stringResource(Res.string.chat_group_remove_member),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(Res.string.chat_group_info_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                if (onLeaveGroup != null) {
                    // 原型 `.btn.danger-o`（描边 + `--on-error-c` 文字）。
                    RelayDialogButton(
                        text = stringResource(Res.string.chat_group_leave),
                        onClick = { showLeaveConfirm = true },
                        danger = true,
                    )
                }
                RelayDialogButton(text = stringResource(Res.string.common_close), onClick = onDismiss)
            }
        },
    )

    // 邀请新成员对话框
    val addMembers = onAddMembers
    if (showAddDialog && addMembers != null) {
        AddGroupMembersDialog(
            peers = peers,
            existingMembers = group.members,
            onDismiss = { showAddDialog = false },
            onConfirm = { toAdd ->
                showAddDialog = false
                addMembers(toAdd)
            },
        )
    }

    // 确认移出成员对话框
    val removeMember = onRemoveMember
    val targetId = memberToRemove
    if (targetId != null && removeMember != null) {
        val targetNick = peerMap[targetId]?.displayName ?: targetId.take(8)
        ConfirmRemoveMemberDialog(
            targetNick = targetNick,
            onDismiss = { memberToRemove = null },
            onConfirm = {
                memberToRemove = null
                removeMember(targetId)
            },
        )
    }

    // 确认退出群聊对话框
    val leaveGroup = onLeaveGroup
    if (showLeaveConfirm && leaveGroup != null) {
        ConfirmLeaveGroupDialog(
            onDismiss = { showLeaveConfirm = false },
            onConfirm = {
                showLeaveConfirm = false
                onDismiss()
                leaveGroup()
            },
        )
    }

    // 编辑群简介与群公告对话框
    val updateMeta = onUpdateMeta
    if (showEditMetaDialog && updateMeta != null) {
        EditGroupMetaDialog(
            initialDesc = group.description.orEmpty(),
            initialAnnounce = group.announce.orEmpty(),
            onDismiss = { showEditMetaDialog = false },
            onConfirm = { desc, announce ->
                showEditMetaDialog = false
                updateMeta(group.groupId, desc, announce)
            },
        )
    }
}
