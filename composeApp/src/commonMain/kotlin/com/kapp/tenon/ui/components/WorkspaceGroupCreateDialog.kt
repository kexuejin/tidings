// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 榫卯 Tenon contributors

package com.kapp.tenon.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.kapp.tenon.domain.groupBoundToWorkspace
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.ic_folder
import com.kapp.tenon.generated.resources.ic_plus
import com.kapp.tenon.generated.resources.ic_qr_code_scanner
import com.kapp.tenon.generated.resources.workspace_agent_section_sessions
import com.kapp.tenon.generated.resources.workspace_create_group_confirm
import com.kapp.tenon.generated.resources.workspace_create_group_dedup_hint
import com.kapp.tenon.generated.resources.workspace_create_group_in_group
import com.kapp.tenon.generated.resources.workspace_create_group_local_only
import com.kapp.tenon.generated.resources.workspace_create_group_mobile_hint
import com.kapp.tenon.generated.resources.workspace_create_group_name_label
import com.kapp.tenon.generated.resources.workspace_create_group_new_title
import com.kapp.tenon.generated.resources.workspace_create_group_no_workspaces
import com.kapp.tenon.generated.resources.workspace_create_group_quick_btn
import com.kapp.tenon.generated.resources.workspace_create_group_quick_title
import com.kapp.tenon.generated.resources.workspace_create_group_share_qr
import com.kapp.tenon.generated.resources.workspace_create_group_sync_devices
import com.kapp.tenon.generated.resources.workspace_create_group_tab_existing
import com.kapp.tenon.generated.resources.workspace_create_group_title
import com.kapp.tenon.generated.resources.workspace_group_prompt_body
import com.kapp.tenon.generated.resources.workspace_group_prompt_create
import com.kapp.tenon.generated.resources.workspace_group_prompt_skip
import com.kapp.tenon.generated.resources.workspace_group_prompt_title
import com.kapp.tenon.generated.resources.workspace_group_default_name
import com.kapp.tenon.generated.resources.workspace_group_default_name_fallback
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.RelayShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 分段切换的两支：内容共用一份，只有「有没有本地目录端口」这一处外壳差异。 */
private enum class CreateGroupTab { Existing, NewDirectory }

/**
 * 创建项目协同群对话框（原型 `prototype/relay.html` 的 `ascreatews` 弹窗）。
 *
 * - 已有项目目录：平铺卡片，整卡可点（已建群进群聊 / 未建群一键建群），右侧不再
 *   并排压两颗按钮，横向空间还给项目名与路径。
 * - 关联本地新目录：**只有持有宿主目录端口的壳才画**。手机等非宿主没有这条物理能力，
 *   让它手输绝对路径只会得到一个按下去也没结果的输入框。
 *
 * 桌面用分段 Tab 把两支分开，上下堆叠与「或」分割线就没了；手机连 Tab 都不画。
 * 两支的卡片与表单只各有一份，差异全部收在 [allowNewDirectory] 这个参数上（AGENTS §2.6）。
 */
@Composable
public fun WorkspaceGroupCreateDialog(
    workspaces: List<WorkspaceUi>,
    groups: List<GroupMeta> = emptyList(),
    onDismiss: () -> Unit,
    onOpenChat: (groupId: String) -> Unit,
    onShowQr: (workspace: WorkspaceUi) -> Unit,
    onQuickCreate: (workspace: WorkspaceUi, syncMyDevice: Boolean) -> Unit,
    onCreateNew: (path: String, groupName: String, syncMyDevice: Boolean) -> Unit,
    /**
     * 宿主目录端口是否存在。false（手机等非宿主）= 只画已有项目列表 + 一句归属说明，
     * 不画手输路径的表单，也不画「立即创建」按钮 —— 那是画不出来的能力。
     * **默认关**：漏传的调用点不会凭空长出一个没有落点的输入。
     */
    allowNewDirectory: Boolean = false,
    initialNewPath: String = "",
) {
    val sp = LocalAppSpacing.current
    var selectedTab by remember { mutableStateOf(CreateGroupTab.Existing) }
    val activeTab = if (allowNewDirectory) selectedTab else CreateGroupTab.Existing
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
            // 高度上限走比例收口：小屏/横屏下 500dp 默认档 + 标题 + 动作条会顶出屏幕。
            // 整个对话框只保留这一个滚动容器 —— 分段切换之后不再有「列表里再滚一层」。
            RelayDialogContentHeight(defaultMaxHeight = 500.dp) { cap ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = cap),
                    verticalArrangement = Arrangement.spacedBy(sp.md),
                ) {
                    if (allowNewDirectory) {
                        item {
                            CreateGroupTabs(
                                selected = activeTab,
                                existingCount = workspaces.size,
                                onSelect = { selectedTab = it },
                            )
                        }
                    }
                    when (activeTab) {
                        CreateGroupTab.Existing -> {
                            item {
                                // 原型 `.grphead` 是左右两个 span，各占一行；左侧 weight(1f)
                                // 单行省略、右侧单行 —— 此前两个 Text 都不约束宽度，窄屏/大字下
                                // 右侧「防重绑定」会从中间断成两截。
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
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = stringResource(Res.string.workspace_create_group_dedup_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = sp.sm),
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
                                    // 反查绑定群只认席位声明（groupBoundToWorkspace，单源）；子串反猜已删。
                                    val boundGroup = groupBoundToWorkspace(ws.wsId, groups)
                                    WorkspaceItemCard(
                                        workspace = ws,
                                        boundGroup = boundGroup,
                                        onOpenChat = { boundGroup?.let { onOpenChat(it.groupId) } },
                                        onShowQr = { onShowQr(ws) },
                                        onQuickCreate = { onQuickCreate(ws, syncMyDevice) },
                                    )
                                }
                            }

                            if (!allowNewDirectory) {
                                item {
                                    Text(
                                        text = stringResource(Res.string.workspace_create_group_mobile_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        CreateGroupTab.NewDirectory -> {
                            item {
                                NewDirectoryForm(
                                    newPath = newPath,
                                    groupName = groupName,
                                    syncMyDevice = syncMyDevice,
                                    onPathChange = { newPath = it },
                                    onNameChange = { groupName = it },
                                    onSyncChange = { syncMyDevice = it },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 「立即创建」只属于新目录那一支；停在已有项目列表时，动作在卡片上，
            // 底部不该摆一颗 enabled=false 却看不出为什么的按钮。
            if (activeTab == CreateGroupTab.NewDirectory) {
                RelayDialogButton(
                    text = stringResource(Res.string.workspace_create_group_confirm),
                    primary = true,
                    enabled = newPath.isNotBlank() && groupName.isNotBlank(),
                    onClick = {
                        onCreateNew(
                            newPath.trim(),
                            groupName.trim(),
                            syncMyDevice,
                        )
                    },
                )
            }
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/** 桌面分段 Tab：两颗共用一份形态，只有选中态不同。 */
@Composable
private fun CreateGroupTabs(
    selected: CreateGroupTab,
    existingCount: Int,
    onSelect: (CreateGroupTab) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        CreateGroupTabChip(
            label = stringResource(Res.string.workspace_create_group_tab_existing, existingCount),
            selected = selected == CreateGroupTab.Existing,
            onClick = { onSelect(CreateGroupTab.Existing) },
            modifier = Modifier.weight(1f),
        )
        CreateGroupTabChip(
            label = stringResource(Res.string.workspace_create_group_new_title),
            selected = selected == CreateGroupTab.NewDirectory,
            onClick = { onSelect(CreateGroupTab.NewDirectory) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CreateGroupTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    // 分段 chip 走「胶囊」语义（CircleShape / 原型 --r-chip:999px），不是 M3 的
    // extraLarge 槽 —— 后者在规范里是底部弹层 28dp，用在这里会凭空多出一档半径。
    val shape = CircleShape
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = shape,
        modifier = modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            // 未选中沿用 labelMedium 自带的字重；写 FontWeight.Normal 会另起 400 一档，
            // 本屏原型没有这一档（逐屏差集会把它记成新增的 code-only 值）。
            fontWeight = if (selected) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xs),
        )
    }
}

/** 新目录表单：状态由对话框持有，这里只画，方便两支共用同一份校验口径。 */
@Composable
private fun NewDirectoryForm(
    newPath: String,
    groupName: String,
    syncMyDevice: Boolean,
    onPathChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onSyncChange: (Boolean) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        Text(
            text = stringResource(Res.string.workspace_create_group_new_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // 这里原先还有一颗「浏览」按钮，点下去什么都不发生（空实现）。本仓的纪律是
        // "有真实动作就接上，没有就整颗不画"，所以整颗删掉而不是留一颗没反应的按钮。
        OutlinedTextField(
            value = newPath,
            onValueChange = onPathChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(Res.string.workspace_create_group_name_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = sp.xxs),
            )
            OutlinedTextField(
                value = groupName,
                onValueChange = onNameChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onSyncChange(!syncMyDevice) }
                .padding(vertical = sp.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
        ) {
            Checkbox(
                checked = syncMyDevice,
                onCheckedChange = onSyncChange,
            )
            Text(
                text = stringResource(Res.string.workspace_create_group_sync_devices),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
    // 整卡可点：已绑定的进群聊，未绑定的一键建群。右侧只留一颗轻量动作，
    // 不再并排两颗按钮把项目名与路径挤成省略号。
    val cardAction = if (boundGroup != null) onOpenChat else onQuickCreate
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RelayShapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(RelayShapes.small).clickable(onClick = cardAction),
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
                                modifier = Modifier.padding(horizontal = sp.xs, vertical = 1.dp),
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
                                modifier = Modifier.padding(horizontal = sp.xs, vertical = 1.dp),
                            )
                        }
                    }
                }
                Text(
                    // 末尾的「N 个会话」复用既有资源：同一句话在别处已经译过，
                    // 再写死一份中文就是第二个真源。
                    text = "🤖 ${workspace.agentKind ?: "Agent"} · ${workspace.wsPath ?: workspace.wsId} · " +
                        stringResource(
                            Res.string.workspace_agent_section_sessions,
                            workspace.sessionCount ?: 0,
                        ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = sp.xxs),
                )
            }

            if (boundGroup != null) {
                // 分享码退成一颗图标按钮：动作不丢，横向也不再占两颗文字按钮的宽度。
                IconButton(onClick = onShowQr) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_qr_code_scanner),
                        contentDescription = stringResource(Res.string.workspace_create_group_share_qr),
                        modifier = Modifier.size(IconSizes.Label),
                    )
                }
            } else {
                CreateGroupPill(onClick = onQuickCreate)
            }
        }
    }
}

/** 未建群卡片右侧的小药丸：形状与配色全走既有 token，不新增尺寸档。 */
@Composable
private fun CreateGroupPill(onClick: () -> Unit) {
    val sp = LocalAppSpacing.current
    val shape = CircleShape
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = shape,
        modifier = Modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_plus),
                contentDescription = null,
                modifier = Modifier.size(IconSizes.Inline),
            )
            Text(
                text = stringResource(Res.string.workspace_create_group_quick_btn),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
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
