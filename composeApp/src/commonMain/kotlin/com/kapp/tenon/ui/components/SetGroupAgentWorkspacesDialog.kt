// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.DispatchPreflight
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.isTeam
import com.kapp.tenon.core.protocol.resolveVirtualAgentKey
import com.kapp.tenon.domain.HostDirectoryPort
import com.kapp.tenon.domain.pointedDirectoryOf
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.capsule_entity_team
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.group_auth_foreign_section
import com.kapp.tenon.generated.resources.group_auth_note
import com.kapp.tenon.generated.resources.group_auth_readonly_note
import com.kapp.tenon.generated.resources.group_auth_stationed_empty
import com.kapp.tenon.generated.resources.group_auth_stationed_section
import com.kapp.tenon.generated.resources.group_auth_title
import com.kapp.tenon.generated.resources.group_project_directory_change
import com.kapp.tenon.generated.resources.group_project_directory_clear
import com.kapp.tenon.generated.resources.group_project_directory_host_authorized
import com.kapp.tenon.generated.resources.group_project_directory_invalid_path
import com.kapp.tenon.generated.resources.group_project_directory_label
import com.kapp.tenon.generated.resources.group_project_directory_picker_title
import com.kapp.tenon.generated.resources.group_project_directory_readonly
import com.kapp.tenon.generated.resources.group_project_directory_register
import com.kapp.tenon.generated.resources.group_project_directory_path_label
import com.kapp.tenon.generated.resources.group_project_directory_unset
import com.kapp.tenon.generated.resources.ic_folder
import com.kapp.tenon.generated.resources.workspace_create_group_browse
import com.kapp.tenon.generated.resources.seat_directory_resolved
import com.kapp.tenon.generated.resources.seat_directory_unresolved
import com.kapp.tenon.generated.resources.seat_duty_label
import com.kapp.tenon.generated.resources.seat_not_executable_unexposed
import com.kapp.tenon.generated.resources.seat_reexpose
import com.kapp.tenon.generated.resources.seat_remove_button
import com.kapp.tenon.generated.resources.seat_station_button
import com.kapp.tenon.generated.resources.seat_status_stationed
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.ShapeRoles
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 群设置 · **项目目录授权与智能体入驻面板**（项目目录决策稿 §4.1/§4.2；M3 交付 3 的
 * 「智能体与工作区授权面板」按新模型改写 —— 勾选白名单的旧三参保存流已删）。
 *
 * ## 三段语义（§4.1）
 *
 * 1. **顶部 = 本群项目目录**：与群信息里的目录行共用 [GroupProjectDirectoryRow]
 *    一份实现（AGENTS §2.6：同一块像素不许两份）；
 * 2. **中部 = 本机 agent 入驻清单**（D6）：入驻不再问目录 —— 席位就地继承群项目
 *    目录；未暴露时标「不可执行」，可一键「去暴露」（= 重新解析投影）；
 * 3. **底部 = 其它设备的声明**（只读）：外来席位看得见、动不了 —— 撤回是机主主权。
 *
 * ## 编辑权在宿主
 *
 * 宿主 = 有目录端口（[hostDirectoryPort] 非空）**且**有入驻动作通路（[onStation]
 * 非 null），判据在 [groupAuthEditable]。其它成员打开同一面板**只读**：看的是声明
 * 投影（`virtualAgents` 随 group.info 广播的那份），动作按钮一律不画。
 *
 * ## 路径不进协议，也不进非宿主的视图
 *
 * 目录选择器只对宿主打开（机主看自己的机器，天经地义）；非宿主只见席位声明投影，
 * 沿用 `group_auth_path_host_only` 的语义 —— 路径本来就不进协议。
 */
/** 入驻清单里的一条本机 agent 行（纯数据；行集由 [groupAgentStationRows] 构造）。 */
data class GroupAgentStationRow(
    /** 本机 agent 身份（`resolveVirtualAgentKey` 口径：agentId 兜底 agentKind）。 */
    val agentId: String,
    /** 行上展示的 `@` token：已入驻 = 席位名（既有 @token 不漂）；未入驻 = kind/身份键。 */
    val displayToken: String,
    /** agent 类型徽标（`DSH`）；未上报时为 null，不编。 */
    val kind: String?,
    /** 该 agent 在本群的本机席位；null = 未入驻。 */
    val seat: VirtualAgentMember?,
) {
    val stationed: Boolean get() = seat != null
    val resolved: Boolean get() = seat?.defaultWsId != null
}

/** 席位行的目录状态（决策稿 §4.2 的三态；判据口径见 [seatDirectoryStatusOf]。 */
internal enum class SeatDirectoryStatus { NOT_STATIONED, RESOLVED, UNEXPOSED }

/**
 * 席位的「项目目录」一问（决策稿 §4.3 preflight 四问之一）：**口径单源**
 * [DispatchPreflight.evaluate] —— 这里只取 `DIRECTORY_RESOLVED` 那一问的答案映射成
 * 三态，不另写第二份判据。席位未入驻不进 preflight（还没有执行点可言）。
 */
internal fun seatDirectoryStatusOf(seat: VirtualAgentMember?): SeatDirectoryStatus {
    if (seat == null) return SeatDirectoryStatus.NOT_STATIONED
    val answer = DispatchPreflight.evaluate(
        hostOnline = true,
        agentPresent = true,
        directoryResolved = seat.defaultWsId != null,
    ).first { it.key == DispatchPreflight.Key.DIRECTORY_RESOLVED }
    return if (answer.ok) SeatDirectoryStatus.RESOLVED else SeatDirectoryStatus.UNEXPOSED
}

/**
 * 入驻清单的行集：从**本机工作区台账**出发（能入驻的是本机的 agent），按
 * `resolveVirtualAgentKey` 去重（同一 agent 多个工作区宣告 = 一行），对上本机席位。
 *
 * 名字不编：已入驻用席位名（换名 = 把别人正在打的 mention 打断）；未入驻退
 * agentKind，再退身份键。
 */
fun groupAgentStationRows(
    localWorkspaces: List<WorkspaceUi>,
    declaredAgents: List<VirtualAgentMember>,
    selfNodeId: String,
): List<GroupAgentStationRow> {
    val byAgentId = LinkedHashMap<String, GroupAgentStationRow>()
    localWorkspaces.forEach { ws ->
        val agentId = resolveVirtualAgentKey(null, ws.agentId, ws.agentKind)?.trim().orEmpty()
        if (agentId.isEmpty()) return@forEach
        val key = agentId.lowercase()
        if (key in byAgentId) return@forEach
        val seat = declaredAgents.firstOrNull {
            it.hostNodeId == selfNodeId && it.agentId.equals(agentId, ignoreCase = true)
        }
        byAgentId[key] = GroupAgentStationRow(
            agentId = agentId,
            displayToken = seat?.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: ws.agentKind?.trim()?.takeIf { it.isNotEmpty() }
                ?: agentId,
            kind = ws.agentKind?.trim()?.takeIf { it.isNotEmpty() },
            seat = seat,
        )
    }
    return byAgentId.values.toList()
}

/**
 * 面板是不是可编辑的 —— 也就是**画不画入驻/去暴露/移除这些动作**。
 *
 * 两个条件缺一不可：没有目录端口（手机等非宿主）谈不上解析与继承；有端口但没有
 * 动作通路（预览/截图夹具）也不该摆一颗按下去没反应的按钮。
 */
internal fun groupAuthEditable(hasDirectoryPort: Boolean, hasActionChannel: Boolean): Boolean =
    hasDirectoryPort && hasActionChannel

@Composable
fun SetGroupAgentWorkspacesDialog(
    /** 群元数据（席位声明的唯一读侧来源）。 */
    group: GroupMeta,
    /** 本机 nodeId（判席位归属）。 */
    selfNodeId: String,
    /** 本机工作区台账（入驻清单的行来源；宿主的编辑对象）。 */
    localWorkspaces: List<WorkspaceUi>,
    /** 宿主目录端口；null = 本机不是宿主（手机常态）⇒ 面板只读。 */
    hostDirectoryPort: HostDirectoryPort?,
    /** 非宿主顶部目录行用的声明投影标题（`workspaceOfGroup` 的 wsTitle）；不编路径。 */
    declaredDirectoryTitle: String?,
    /** 入驻：`(agentId, duty)`。null = 这个壳没有动作通路（按钮不画）。 */
    onStation: ((agentId: String, duty: String?) -> Unit)?,
    /** 重新解析本机席位投影（「去暴露」落点）。null 同上。 */
    onReparse: (() -> Unit)?,
    /** 移除本机席位（撤回入驻）。null 同上。 */
    onRemove: ((agentId: String) -> Unit)?,
    /** 设 / 清本群项目目录指针（D5）。null 同上。 */
    onSetDirectory: ((dirId: String?) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    val editable = groupAuthEditable(
        hasDirectoryPort = hostDirectoryPort != null,
        hasActionChannel = onStation != null,
    )
    val declared = group.virtualAgents.orEmpty()

    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.group_auth_title)) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                Text(
                    text = stringResource(Res.string.group_auth_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = sp.sm),
                )
                if (!editable) {
                    Surface(
                        shape = ShapeRoles.DayCell,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(Res.string.group_auth_readonly_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.sm6),
                        )
                    }
                }

                // ── 本群项目目录（与群信息行同一份实现）───────────────────
                GroupProjectDirectoryRow(
                    groupId = group.groupId,
                    hostDirectoryPort = hostDirectoryPort,
                    declaredTitle = declaredDirectoryTitle,
                    onSetDirectory = onSetDirectory?.takeIf { editable },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (editable) {
                    StationingSection(
                        localWorkspaces = localWorkspaces,
                        group = group,
                        selfNodeId = selfNodeId,
                        onStation = onStation,
                        onReparse = onReparse,
                        onRemove = onRemove,
                    )
                    val foreignSeats = declared.filter { it.hostNodeId != selfNodeId }
                    if (foreignSeats.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.group_auth_foreign_section),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = sp.sm6),
                        )
                        foreignSeats.forEach { seat ->
                            DeclaredAgentRow(seat = seat)
                        }
                    }
                } else {
                    // 非宿主只读：声明投影逐条列出（名称 + 执行点状态），没有动作。
                    if (declared.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.group_auth_stationed_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = sp.md),
                        )
                    }
                    declared.forEach { seat -> DeclaredAgentRow(seat = seat) }
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/** 宿主视角的入驻清单段：本机 agent 逐行 + 动作。 */
@Composable
private fun StationingSection(
    localWorkspaces: List<WorkspaceUi>,
    group: GroupMeta,
    selfNodeId: String,
    onStation: ((agentId: String, duty: String?) -> Unit)?,
    onReparse: (() -> Unit)?,
    onRemove: ((agentId: String) -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    Text(
        text = stringResource(Res.string.group_auth_stationed_section),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = sp.sm6),
    )
    val rows = remember(localWorkspaces, group.virtualAgents, selfNodeId) {
        groupAgentStationRows(localWorkspaces, group.virtualAgents.orEmpty(), selfNodeId)
    }
    if (rows.isEmpty()) {
        Text(
            text = stringResource(Res.string.group_auth_stationed_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = sp.md),
        )
        return
    }
    rows.forEach { row ->
        StationRow(
            row = row,
            canStation = onStation != null,
            canReparse = onReparse != null,
            canRemove = onRemove != null,
            onStation = onStation,
            onReparse = onReparse,
            onRemove = onRemove,
        )
    }
}

/** 入驻清单的一行：`@token` + 类型/团队徽标 + 执行点状态 + 动作。 */
@Composable
private fun StationRow(
    row: GroupAgentStationRow,
    canStation: Boolean,
    canReparse: Boolean,
    canRemove: Boolean,
    onStation: ((agentId: String, duty: String?) -> Unit)?,
    onReparse: (() -> Unit)?,
    onRemove: ((agentId: String) -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        modifier = Modifier.fillMaxWidth().padding(vertical = sp.xxs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                Text(
                    text = "@" + row.displayToken,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                row.kind?.let {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = ShapeRoles.DayCell) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = LocalAppSpacing.current.sm6,
                                vertical = LocalAppSpacing.current.xxs,
                            ),
                        )
                    }
                }
                if (row.seat?.isTeam == true) {
                    RelayTagBadge(stringResource(Res.string.capsule_entity_team))
                }
            }
            Text(
                text = seatStatusText(row),
                style = MaterialTheme.typography.labelSmall,
                color = if (row.stationed && !row.resolved) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 动作竖排（与群信息 agent 行同一取舍：横排会把名字挤没）。
        Column(
            verticalArrangement = Arrangement.spacedBy(sp.xxs),
            horizontalAlignment = Alignment.End,
        ) {
            when {
                // 未入驻 → 「入驻」+ 职责选择（D13：职责进名字；默认单兵）。
                !row.stationed && canStation && onStation != null ->
                    StationDutyPicker(
                        onStation = { duty -> onStation(row.agentId, duty) },
                    )
                // 已入驻未解析 → 「去暴露」（重新解析投影）+「移除」。
                row.stationed && !row.resolved -> {
                    if (canReparse && onReparse != null) {
                        AppTextButton(
                            text = stringResource(Res.string.seat_reexpose),
                            onClick = onReparse,
                            size = AppButtonSize.Small,
                        )
                    }
                    if (canRemove && onRemove != null) {
                        AppTextButton(
                            text = stringResource(Res.string.seat_remove_button),
                            onClick = { onRemove(row.agentId) },
                            tone = AppButtonTone.Danger,
                            size = AppButtonSize.Small,
                        )
                    }
                }
                // 已解析 → 「移除」（撤回入驻）。
                row.stationed && canRemove && onRemove != null ->
                    AppTextButton(
                        text = stringResource(Res.string.seat_remove_button),
                        onClick = { onRemove(row.agentId) },
                        tone = AppButtonTone.Danger,
                        size = AppButtonSize.Small,
                    )
            }
        }
    }
}

/** 行上的状态句（按 [seatDirectoryStatusOf] 的三态取 strings.xml 的口径）。 */
@Composable
private fun seatStatusText(row: GroupAgentStationRow): String = when (seatDirectoryStatusOf(row.seat)) {
    SeatDirectoryStatus.NOT_STATIONED -> stringResource(Res.string.seat_status_stationed)
    SeatDirectoryStatus.RESOLVED -> stringResource(Res.string.seat_directory_resolved)
    SeatDirectoryStatus.UNEXPOSED -> stringResource(Res.string.seat_not_executable_unexposed)
}

/**
 * 入驻时的职责下拉（D13：8 个取值域值显示**原文 slug** —— ASCII token 不翻译，
 * 取值域单源 [AgentDuties.ALL]，UI 不另抄一份清单）。
 */
@Composable
private fun StationDutyPicker(onStation: (String?) -> Unit) {
    val sp = LocalAppSpacing.current
    var expanded by remember { mutableStateOf(false) }
    var duty by remember { mutableStateOf(AgentDuties.DEFAULT_SINGLE) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Box {
            Surface(
                shape = ShapeRoles.DayCell,
                color = MaterialTheme.colorScheme.surfaceVariant,
                onClick = { expanded = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.xxs),
                    modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                ) {
                    Text(
                        text = stringResource(Res.string.seat_duty_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = duty,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .padding(top = sp.xxs)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(sp.xxs),
                ) {
                    AgentDuties.ALL.forEach { candidate ->
                        Text(
                            text = candidate,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (candidate == duty) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    duty = candidate
                                    expanded = false
                                }
                                .padding(horizontal = sp.sm6, vertical = sp.xxs),
                        )
                    }
                }
            }
        }
        AppTextButton(
            text = stringResource(Res.string.seat_station_button),
            onClick = { onStation(duty) },
            size = AppButtonSize.Small,
        )
    }
}

/** 只读的声明行（非宿主全列表 / 宿主的外来段）：`@name` + 团队徽标 + 执行点状态。 */
@Composable
private fun DeclaredAgentRow(seat: VirtualAgentMember) {
    val sp = LocalAppSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        modifier = Modifier.fillMaxWidth().padding(vertical = sp.xxs),
    ) {
        Text(
            text = "@" + seat.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (seat.isTeam) {
            RelayTagBadge(stringResource(Res.string.capsule_entity_team))
        }
        Text(
            text = when (seatDirectoryStatusOf(seat)) {
                SeatDirectoryStatus.RESOLVED -> stringResource(Res.string.seat_directory_resolved)
                else -> stringResource(Res.string.seat_directory_unresolved)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「本项目目录」行 —— 群信息卡与入驻面板**共用这一份**（§4.1；AGENTS §2.6）。
 *
 * - 宿主（[hostDirectoryPort] 非空且 [onSetDirectory] 非 null）：显示本机指针指向的
 *   目录名（`pointedDirectoryOf`），未设时如实说「本群还没有项目目录」，右侧给
 *   「更换/设置」入口（弹 [ProjectDirectoryPickerDialog]）；
 * - 非宿主：只显示**席位声明投影**（[declaredTitle]，即 `workspaceOfGroup` 的 wsTitle）
 *   或「由宿主授权」—— **路径永远不上屏**（沿用 `group_auth_path_host_only` 语义）。
 */
@Composable
internal fun GroupProjectDirectoryRow(
    groupId: String,
    hostDirectoryPort: HostDirectoryPort?,
    declaredTitle: String?,
    onSetDirectory: ((dirId: String?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    var showPicker by remember { mutableStateOf(false) }
    val editable = hostDirectoryPort != null && onSetDirectory != null
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.group_project_directory_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val value: String = if (editable) {
                val pointed = hostDirectoryPort?.snapshot().pointedDirectoryOf(groupId)
                pointed?.displayName
                    ?: stringResource(Res.string.group_project_directory_unset)
            } else {
                declaredTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: stringResource(Res.string.group_project_directory_host_authorized)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editable) {
            AppTextButton(
                text = stringResource(Res.string.group_project_directory_change),
                onClick = { showPicker = true },
                size = AppButtonSize.Small,
            )
        }
    }

    if (showPicker && hostDirectoryPort != null && onSetDirectory != null) {
        ProjectDirectoryPickerDialog(
            port = hostDirectoryPort,
            onDismiss = { showPicker = false },
            onPick = { dirId ->
                showPicker = false
                onSetDirectory(dirId)
            },
        )
    }
}

/**
 * 项目目录选择器（只对宿主打开）：列本机目录台账（名字 + 只读标记 + 路径 ——
 * 机主看自己的机器）、底部「清除（不设目录）」、「登记新目录…」走
 * `port.register(path, null)`（幂等；登记即入白名单）。选中即设指针 ——
 * 写路径在壳层回调（指针 + 投影重建 + 广播必须一次完成）。
 */
@Composable
private fun ProjectDirectoryPickerDialog(
    port: HostDirectoryPort,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val sp = LocalAppSpacing.current
    val snapshot = remember { port.snapshot() }
    var newPath by remember { mutableStateOf("") }
    var invalidPath by remember { mutableStateOf(false) }
    // 系统选目录窗：登记项目目录的另一半 —— 从前只有手输框，macOS 上的绝对路径
    // 基本打不对，而原生选目录窗早就实现了，只是没有任何界面接它。
    val scope = rememberCoroutineScope()
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.group_project_directory_picker_title)) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                snapshot?.directories.orEmpty().forEach { dir ->
                    val readOnly = snapshot?.whitelist.orEmpty()
                        .firstOrNull { it.dirId == dir.dirId }?.readOnly == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(dir.dirId) }
                            .padding(vertical = sp.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dir.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = dir.path,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (readOnly) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = ShapeRoles.DayCell,
                            ) {
                                Text(
                                    text = stringResource(Res.string.group_project_directory_readonly),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = LocalAppSpacing.current.sm6,
                                        vertical = LocalAppSpacing.current.xxs,
                                    ),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(Res.string.group_project_directory_clear),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(null) }
                        .padding(vertical = sp.xxs),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column {
                    Text(
                        text = stringResource(Res.string.group_project_directory_register),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = sp.xxs),
                    )
                    OutlinedTextField(
                        value = newPath,
                        onValueChange = {
                            newPath = it
                            invalidPath = false
                        },
                        label = { Text(stringResource(Res.string.group_project_directory_path_label)) },
                        isError = invalidPath,
                        supportingText = if (invalidPath) {
                            { Text(stringResource(Res.string.group_project_directory_invalid_path)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        // 选中的路径直接落进同一个输入框（用户还能改），登记仍走下面那颗
                        // 「登记」—— 选目录不说话，只回答「哪个目录」。
                        trailingIcon = if (port.canPickDirectory) {
                            {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            port.pickDirectory()?.takeIf { it.isNotBlank() }?.let {
                                                newPath = it
                                                invalidPath = false
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_folder),
                                        contentDescription = stringResource(
                                            // 复用建群表单那颗图标的同一句文案：同一动作不写第二份。
                                            Res.string.workspace_create_group_browse,
                                        ),
                                        modifier = Modifier.size(IconSizes.Label),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.group_project_directory_register),
                primary = true,
                enabled = newPath.isNotBlank(),
                onClick = {
                    val registered = port.register(newPath.trim(), null)
                    if (registered == null) {
                        // 登记失败（路径无法归一）就地指出，不关窗 —— 不是「已存在」。
                        invalidPath = true
                    } else {
                        onPick(registered.dirId)
                    }
                },
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

/** 面板里的轻量徽标（团队 / 只读）：同一形状一处定义。 */
@Composable
private fun RelayTagBadge(text: String) {
    Surface(
        shape = ShapeRoles.DayCell,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(
                horizontal = LocalAppSpacing.current.sm6,
                vertical = LocalAppSpacing.current.xxs,
            ),
        )
    }
}
