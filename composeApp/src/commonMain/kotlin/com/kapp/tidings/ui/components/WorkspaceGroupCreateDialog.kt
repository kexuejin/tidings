// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.ic_chat_bubble
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_plus
import com.kapp.tidings.generated.resources.ic_qr_code_scanner
import com.kapp.tidings.generated.resources.workspace_agent_section_sessions
import com.kapp.tidings.generated.resources.workspace_create_group_agent_label
import com.kapp.tidings.generated.resources.workspace_create_group_confirm
import com.kapp.tidings.generated.resources.workspace_create_group_dedup_hint
import com.kapp.tidings.generated.resources.workspace_create_group_enter_chat
import com.kapp.tidings.generated.resources.workspace_create_group_in_group
import com.kapp.tidings.generated.resources.workspace_create_group_local_only
import com.kapp.tidings.generated.resources.workspace_create_group_name_label
import com.kapp.tidings.generated.resources.workspace_create_group_new_title
import com.kapp.tidings.generated.resources.workspace_create_group_no_workspaces
import com.kapp.tidings.generated.resources.workspace_create_group_or_new
import com.kapp.tidings.generated.resources.workspace_create_group_quick_btn
import com.kapp.tidings.generated.resources.workspace_create_group_quick_title
import com.kapp.tidings.generated.resources.workspace_create_group_share_qr
import com.kapp.tidings.generated.resources.workspace_create_group_sync_devices
import com.kapp.tidings.generated.resources.workspace_create_group_title
import com.kapp.tidings.generated.resources.workspace_default_resident_agent
import com.kapp.tidings.generated.resources.workspace_group_prompt_body
import com.kapp.tidings.generated.resources.workspace_group_prompt_create
import com.kapp.tidings.generated.resources.workspace_group_prompt_skip
import com.kapp.tidings.generated.resources.workspace_group_prompt_title
import com.kapp.tidings.generated.resources.workspace_group_default_name
import com.kapp.tidings.generated.resources.workspace_group_default_name_fallback
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.RelayShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 关联工作区建群对话框（原型 `prototype/relay.html` 的 `ascreatews` 弹窗）。
 *
 * 遵循极简双分支交互原则：
 * - 90% 场景（分支 B）：已有项目平铺卡片，一键建群 / 共享；已建群的提供防重跳进与分享码。
 * - 10% 场景（分支 A）：全新目录挑选路径，自动联动群名，默认自动同步信任设备。
 */
@Composable
public fun WorkspaceGroupCreateDialog(
    workspaces: List<WorkspaceUi>,
    groups: List<GroupMeta> = emptyList(),
    onDismiss: () -> Unit,
    onOpenChat: (groupId: String) -> Unit,
    onShowQr: (workspace: WorkspaceUi) -> Unit,
    onQuickCreate: (workspace: WorkspaceUi, syncMyDevice: Boolean) -> Unit,
    onCreateNew: (path: String, agentKind: String, groupName: String, syncMyDevice: Boolean) -> Unit,
    initialNewPath: String = "",
) {
    val sp = LocalAppSpacing.current
    var newPath by remember { mutableStateOf(initialNewPath) }
    val defaultNameFormat = stringResource(Res.string.workspace_group_default_name, "%s")
    val defaultFallback = stringResource(Res.string.workspace_group_default_name_fallback)
    var groupName by remember(newPath) {
        val folder = newPath.trimEnd('/', '\\').substringAfterLast('/', "").substringAfterLast('\\', "")
        mutableStateOf(if (folder.isNotBlank()) defaultNameFormat.replace("%s", folder) else defaultFallback)
    }
    var syncMyDevice by remember { mutableStateOf(true) }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.workspace_create_group_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        content = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                item {
                    // 分支 B：已有工作区快速建群
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.workspace_create_group_quick_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(Res.string.workspace_create_group_dedup_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (workspaces.isEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RelayShapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(Res.string.workspace_create_group_no_workspaces),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(sp.md),
                            )
                        }
                    }
                } else {
                    items(workspaces, key = { "${it.peerId}:${it.agentKey}:${it.wsId}" }) { ws ->
                        val boundGroup = groups.firstOrNull { g ->
                            // 跨模块 public 属性先落成局部量，否则 smart cast 不成立。
                            val wsPath = ws.wsPath
                            val wsTitle = ws.wsTitle
                            val matchPath = !wsPath.isNullOrBlank() && g.description?.contains(wsPath) == true
                            val matchTitle = !wsTitle.isNullOrBlank() && g.name.contains(wsTitle)
                            matchPath || matchTitle
                        }
                        WorkspaceItemCard(
                            workspace = ws,
                            boundGroup = boundGroup,
                            onOpenChat = { boundGroup?.let { onOpenChat(it.groupId) } },
                            onShowQr = { onShowQr(ws) },
                            onQuickCreate = { onQuickCreate(ws, syncMyDevice) },
                        )
                    }
                }

                item {
                    // 分割线：或关联本地全新目录
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = sp.xs),
                        contentAlignment = Alignment.Center,
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.padding(horizontal = sp.sm),
                        ) {
                            Text(
                                text = stringResource(Res.string.workspace_create_group_or_new),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    // 分支 A：全新目录挑路径
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(sp.sm),
                    ) {
                        Text(
                            text = stringResource(Res.string.workspace_create_group_new_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(sp.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 这里原先还有一颗「浏览」按钮，点下去什么都不发生（空实现）。
                            // 本仓的纪律是"有真实动作就接上，没有就整颗不画"：这台设备上的
                            // 目录选择能力（`SaveDirectoryPicker`）拿不到这条对话框的调用链上
                            // （三个活调用点里，会话侧栏那一个的 controller 是窄接口
                            // `DesktopPaneActions`，取不到平台 picker），所以**整颗删掉**，
                            // 而不是留一颗按下去没反应的按钮。路径仍可手动输入。
                            OutlinedTextField(
                                value = newPath,
                                onValueChange = { newPath = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.workspace_create_group_agent_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = LocalAppSpacing.current.xxs),
                                )
                                OutlinedTextField(
                                    value = stringResource(Res.string.workspace_default_resident_agent),
                                    onValueChange = {},
                                    readOnly = true,
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.workspace_create_group_name_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = LocalAppSpacing.current.xxs),
                                )
                                OutlinedTextField(
                                    value = groupName,
                                    onValueChange = { groupName = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { syncMyDevice = !syncMyDevice }
                                .padding(vertical = sp.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.xs),
                        ) {
                            Checkbox(
                                checked = syncMyDevice,
                                onCheckedChange = { syncMyDevice = it },
                            )
                            Text(
                                text = stringResource(Res.string.workspace_create_group_sync_devices),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.workspace_create_group_confirm),
                primary = true,
                enabled = newPath.isNotBlank() && groupName.isNotBlank(),
                onClick = { onCreateNew(newPath.trim(), "Codex", groupName.trim(), syncMyDevice) },
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun WorkspaceItemCard(
    workspace: WorkspaceUi,
    boundGroup: GroupMeta?,
    onOpenChat: () -> Unit,
    onShowQr: () -> Unit,
    onQuickCreate: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RelayShapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(sp.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = sp.xs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_folder),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(IconSizes.Label),
                    )
                    Text(
                        text = workspace.wsTitle ?: workspace.wsId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (boundGroup != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RelayShapes.extraSmall,
                        ) {
                            Text(
                                text = stringResource(Res.string.workspace_create_group_in_group),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = 1.dp),
                            )
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RelayShapes.extraSmall,
                        ) {
                            Text(
                                text = stringResource(Res.string.workspace_create_group_local_only),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = 1.dp),
                            )
                        }
                    }
                }
                Text(
                    // 末尾的「N 个会话」复用既有资源（`%1$d 个会话` / `%1$d conversations`）：
                    // 同一句话在别处已经译过，再写死一份中文就是第二个真源。
                    text = "🤖 ${workspace.agentKind ?: "Agent"} · ${workspace.wsPath ?: workspace.wsId} · " +
                        stringResource(
                            Res.string.workspace_agent_section_sessions,
                            workspace.sessionCount ?: 0,
                        ),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                )
            }

            if (boundGroup != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs)) {
                    AppButton(
                        text = stringResource(Res.string.workspace_create_group_enter_chat),
                        onClick = onOpenChat,
                        size = AppButtonSize.Small,
                        leadingIcon = Res.drawable.ic_chat_bubble,
                    )
                    AppOutlinedButton(
                        text = stringResource(Res.string.workspace_create_group_share_qr),
                        onClick = onShowQr,
                        size = AppButtonSize.Small,
                        leadingIcon = Res.drawable.ic_qr_code_scanner,
                    )
                }
            } else {
                AppButton(
                    text = stringResource(Res.string.workspace_create_group_quick_btn),
                    onClick = onQuickCreate,
                    size = AppButtonSize.Small,
                    leadingIcon = Res.drawable.ic_plus,
                )
            }
        }
    }
}
/**
 * 工作区没有绑定协同群时的建群提示（issue 7）。
 *
 * ## 为什么要有它，而不是复用 [WorkspaceGroupCreateDialog]
 *
 * 后者的形态是"列出**所有**工作区让你挑一个" —— 它是**独立入口**（工作区看板的
 * 「协同群」按钮、桌面会话栏那一颗），不是一个"针对当前工作区"的动作。用户在某一行
 * 工作区点「打开聊天」，弹出的是跟这一行无关的全局列表：这正是用户反馈 #7 的原话。
 * 本支只讲**传入的这一个工作区**：一句说明 + 一颗「建群并进入」。
 * 群名与成员的默认口径由调用方复用快速建群那一套（onCreate 里接同一个成员过滤）。
 *
 * ## 为什么留一颗「先直接聊天」
 *
 * 没绑定群不等于不能跟这个工作区的 agent 说话（两条壳原来都是直接进聊天）。
 * 建群是**附加**动作，不能把原来的路堵死 —— 否则"我只是想看会话"的用户被逼着先建群。
 */
@Composable
public fun WorkspaceGroupPromptDialog(
    workspace: WorkspaceUi,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onOpenWithoutGroup: (() -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.workspace_group_prompt_title)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = stringResource(
                        Res.string.workspace_group_prompt_body,
                        workspace.wsTitle ?: workspace.wsId,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 跨模块 public 属性先落成局部量，否则 smart cast 不成立。
                val workspacePath = workspace.wsPath
                if (!workspacePath.isNullOrBlank()) {
                    Text(
                        text = workspacePath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onOpenWithoutGroup != null) {
                    AppTextButton(
                        text = stringResource(Res.string.workspace_group_prompt_skip),
                        onClick = onOpenWithoutGroup,
                        size = AppButtonSize.Small,
                    )
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.workspace_group_prompt_create),
                primary = true,
                onClick = onCreate,
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}
