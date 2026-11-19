// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import com.kapp.tenon.generated.resources.ic_groups
import com.kapp.tenon.generated.resources.ic_user
import com.kapp.tenon.generated.resources.team_profile_desc_hint
import com.kapp.tenon.generated.resources.team_profile_leader_focus_title
import com.kapp.tenon.generated.resources.team_profile_leader_role_desc
import com.kapp.tenon.generated.resources.team_profile_leader_select_placeholder
import com.kapp.tenon.generated.resources.team_profile_member_select_placeholder
import com.kapp.tenon.generated.resources.team_profile_members_sub_desc
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import com.kapp.tenon.ui.components.PeerAvatar
import org.jetbrains.compose.resources.painterResource
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.domain.TeamProfile
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.team_profile_member_name_label
import com.kapp.tenon.generated.resources.team_profile_member_role_label
import com.kapp.tenon.generated.resources.agent_empty_exit_title
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.team_profile_dialog_title_edit
import com.kapp.tenon.generated.resources.team_profile_dialog_title_new
import com.kapp.tenon.generated.resources.team_profile_groups_in
import com.kapp.tenon.generated.resources.team_profile_groups_label
import com.kapp.tenon.generated.resources.team_profile_groups_none
import com.kapp.tenon.generated.resources.team_profile_groups_optional
import com.kapp.tenon.generated.resources.team_profile_members_label
import com.kapp.tenon.generated.resources.team_profile_name_hint
import com.kapp.tenon.generated.resources.team_profile_no_leaders
import com.kapp.tenon.generated.resources.team_profile_prereq_body
import com.kapp.tenon.generated.resources.team_profile_save_edit
import com.kapp.tenon.generated.resources.team_profile_save_new
import com.kapp.tenon.generated.resources.team_roster_remove_member
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.ic_chevd
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.components.newDraft
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.stringResource

/** 领队候选：一个本机可上报的 agent（`agentKey` 是档案键，`label` 是人看的名）。 */
public data class TeamLeaderOption(val agentKey: String, val label: String)

/**
 * 打开「编辑团队」时喂给对话框的初值。
 *
 * @param profileKey 团队档案键；null = 新建。
 * @param leaderAgentId 编辑态从档案取；档案丢了就退回卡上的领队席位（如实降级，
 *   不拦着用户改名 —— 拦住只会让他连名字都改不了）。
 */
internal data class TeamEditTarget(
    val profileKey: String?,
    val name: String,
    val leaderAgentId: String?,
    val members: List<TeamMemberDraft>,
    val description: String = "",
)

/**
 * 群候选。
 *
 * @param joined 这个群已经在引用这支队伍（席位 `teamId` 相同）。勾上它没有意义，
 *   取消勾也不会把它摘出去 —— 从群里移除是那个群的决定（`DetachTeam`），
 *   不该藏在「编辑团队」里。
 */
public data class TeamGroupOption(val groupId: String, val name: String, val joined: Boolean)

/**
 * 这一屏能勾选的群 = **还没引用这支队伍**的那些。
 *
 * 抽成纯函数是为了让「已在的群不能再勾」这条判据可测：它同时决定了「点一下会不会
 * 重复加一条席位」和「保存按钮能不能亮」，写在 Composable 里就只能靠人眼。
 */
internal fun addableTeamGroups(options: List<TeamGroupOption>): List<TeamGroupOption> =
    options.filterNot { it.joined }

/**
 * 「新建团队 / 编辑团队」对话框：**团队自己的名字** + 领队 + 名册 + 去哪些群。
 *
 * ## 为什么领队要显式选
 *
 * 团队不是派单目标（派单只认领队或某个组员的 token），所以「谁带队」是这支队伍
 * 唯一的执行入口 —— 不选它，团队就只是一张名单。候选来自本机卡片流（宿主 = 本机），
 * 不是自由文本：造一个本机不存在的 agent 当领队，等于造一个永远不会执行的头。
 *
 * ## 为什么群是多选而不是「先建团队再一个个加」
 *
 * 一份档案进 N 个群就是 N 条席位（各自 rev、各自广播），但用户的心智是「这支队伍
 * 在 A、B 两个群」。这里勾两个 = 一次动作建两次席位；已经在的群只标注「已在」，
 * 再勾一遍不会重复加（域层按 `teamId` 去重）。
 *
 * ## 只增不减
 *
 * 编辑态不给「取消勾选已加入的群」：那会让「改个名字」顺手把一个群清空。
 * 移出某个群走那个群的席位操作。
 *
 * @param leaders 本机可当领队的 agent。新建时为空 = 本机还没有 agent ⇒ **整张表单不展开**，
 *   只画前置条件与「去看看节点」；不画一张永远存不下去的表单。
 * @param initialLeaderAgentId 编辑态的领队（新建时传 null ⇒ 取第一个候选）。
 */
// 公开面：androidApp 的 screenshotTest 跨模块组装预览；生产入口仍在本模块。
@Composable
public fun TeamProfileDialog(
    profileKey: String?,
    initialName: String,
    leaders: List<TeamLeaderOption>,
    initialLeaderAgentId: String?,
    initialMembers: List<TeamMemberDraft>,
    initialDescription: String = "",
    groups: List<TeamGroupOption>,
    onSave: (
        name: String,
        leaderAgentId: String,
        members: List<TeamMemberDraft>,
        groupIds: List<String>,
        description: String,
    ) -> Unit,
    onDismiss: () -> Unit,
    /**
     * 前置条件不满足时的出路：去节点页确认宿主在线。
     *
     * null = 这个壳没有节点页（预览）⇒ 只保留「取消」。
     */
    onFindNodes: (() -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    // 新建团队但没有领队候选：**不展开整张表单**。
    //
    // 领队是团队唯一的执行入口，档案模型里它是必填（`TeamProfile.leaderAgentId`），
    // 所以「先随便填、之后再补」在这里不是合法路径。此前这一支仍然把团队名、组员、
    // 群选择全画出来，保存键却永远置灰 —— 用户填完一屏才发现做不了，是一次假操作。
    // 这里只回答两件事：为什么不行、下一步去哪。
    if (profileKey == null && leaders.isEmpty()) {
        RelayDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.team_profile_dialog_title_new)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(sp.sm6)) {
                    Text(
                        text = stringResource(Res.string.team_profile_prereq_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.team_profile_no_leaders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                if (onFindNodes != null) {
                    RelayDialogButton(
                        text = stringResource(Res.string.agent_empty_exit_title),
                        primary = true,
                        onClick = {
                            onDismiss()
                            onFindNodes()
                        },
                    )
                }
            },
            dismissButton = {
                RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
            },
        )
        return
    }
    var name by remember { mutableStateOf(initialName) }
    var leaderKey by remember(leaders) {
        mutableStateOf(
            leaders.firstOrNull { it.agentKey == initialLeaderAgentId }?.agentKey
                ?: initialLeaderAgentId
                ?: leaders.firstOrNull()?.agentKey,
        )
    }
    var members by remember { mutableStateOf(initialMembers) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    // 只记「这次要新加进去的群」；已加入的群不进这个集合（勾了也不重复加）。
    var pickedGroups by remember { mutableStateOf(emptySet<String>()) }

    val addableGroups = addableTeamGroups(groups)
    // **不勾群也能保存**（「先建档、后加群」）：没有席位的团队在群里/手机上确实看不见，
    // 但那是一个**知情选择**，不是非法输入 —— 出路是群信息的「添加团队」。
    // 界面的责任是说清代价（下方群段落的 `team_profile_groups_optional`），不是把按钮置灰。
    val canSave = name.isNotBlank() &&
        leaderKey != null &&
        members.isNotEmpty() &&
        members.all { it.name.isNotBlank() }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        if (profileKey == null) {
                            Res.string.team_profile_dialog_title_new
                        } else {
                            Res.string.team_profile_dialog_title_edit
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AppIconButton(
                    icon = Res.drawable.ic_close,
                    contentDescription = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                )
            }
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                // 1. 头像 + 团队名称（同一行对齐，对齐 Multica 与原型）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.md),
                ) {
                    Surface(
                        shape = LocalCardShape.current,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_groups),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(sp.lg),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = {
                            Text(
                                text = stringResource(Res.string.team_profile_name_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }

                // 2. 团队简介（全宽文本域，紧接头像+名称下方，对齐原型与 Multica）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(sp.xxs),
                ) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { if (it.length <= TeamProfile.MAX_DESCRIPTION_LENGTH) description = it },
                        placeholder = {
                            Text(
                                text = stringResource(Res.string.team_profile_desc_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        minLines = 2,
                        maxLines = 3,
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${description.length} / ${TeamProfile.MAX_DESCRIPTION_LENGTH}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                }

                // 4. 队长智能体
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Text(
                        text = stringResource(Res.string.team_profile_leader_focus_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.team_profile_leader_role_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (leaders.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.team_profile_no_leaders),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val selectedLeader = leaders.firstOrNull { it.agentKey == leaderKey }
                        var leaderMenuExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (profileKey == null) Modifier.clickable { leaderMenuExpanded = true } else Modifier),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.sm6),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                                    ) {
                                        if (selectedLeader != null) {
                                            PeerAvatar(
                                                name = selectedLeader.label,
                                                avatar = AVATAR_LEGACY_INITIAL_VALUE,
                                                size = sp.md10,
                                                shape = CircleShape,
                                            )
                                            Text(
                                                text = selectedLeader.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_user),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(sp.sm),
                                            )
                                            Text(
                                                text = stringResource(Res.string.team_profile_leader_select_placeholder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (profileKey == null) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_chevd),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(sp.sm),
                                        )
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = leaderMenuExpanded,
                                onDismissRequest = { leaderMenuExpanded = false },
                            ) {
                                leaders.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(sp.xs),
                                            ) {
                                                PeerAvatar(
                                                    name = option.label,
                                                    avatar = AVATAR_LEGACY_INITIAL_VALUE,
                                                    size = sp.md10,
                                                    shape = CircleShape,
                                                )
                                                Text(text = option.label)
                                            }
                                        },
                                        onClick = {
                                            leaderKey = option.agentKey
                                            leaderMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. 附加成员（可选）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Text(
                        text = stringResource(Res.string.team_profile_members_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.team_profile_members_sub_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (members.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(sp.xs),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            members.forEachIndexed { index, draft ->
                                MemberCompactRow(
                                    draft = draft,
                                    onNameChange = { newName ->
                                        members = members.mapIndexed { i, d ->
                                            if (i == index) d.copy(name = newName) else d
                                        }
                                    },
                                    onRoleChange = { newRole ->
                                        members = members.mapIndexed { i, d ->
                                            if (i == index) d.copy(role = newRole) else d
                                        }
                                    },
                                    onRemove = {
                                        members = members.filterIndexed { i, _ -> i != index }
                                    },
                                )
                            }
                        }
                    }

                    // 附加成员选择触发器（对齐 Multica 与原型）
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                members = members + newDraft(
                                    name = "",
                                    role = "",
                                    takenTokens = emptySet(),
                                    existing = members,
                                )
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.sm6),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(sp.xs),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_user),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(sp.sm),
                                )
                                Text(
                                    text = stringResource(Res.string.team_profile_member_select_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                painter = painterResource(Res.drawable.ic_chevd),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(sp.sm),
                            )
                        }
                    }
                }

                // 6. 入驻协同群聊
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Text(
                        text = stringResource(Res.string.team_profile_groups_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (groups.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.team_profile_groups_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(sp.xs),
                            verticalArrangement = Arrangement.spacedBy(sp.xs),
                        ) {
                            addableGroups.forEach { option ->
                                SelectChip(
                                    label = option.name,
                                    selected = option.groupId in pickedGroups,
                                    onClick = {
                                        pickedGroups = if (option.groupId in pickedGroups) {
                                            pickedGroups - option.groupId
                                        } else {
                                            pickedGroups + option.groupId
                                        }
                                    },
                                )
                            }
                            groups.filter { it.joined }.forEach { option ->
                                Text(
                                    text = option.name + " · " +
                                        stringResource(Res.string.team_profile_groups_in),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = sp.xxs, vertical = sp.xxs),
                                )
                            }
                        }
                        if (pickedGroups.isEmpty() && groups.none { it.joined }) {
                            Text(
                                text = stringResource(Res.string.team_profile_groups_optional),
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
                // 「保存并广播」是协议动作，不该出现在团队档案的表单上：新建就是
                // 创建一支队伍，编辑就是保存改动。广播与否是写路径的实现细节。
                text = stringResource(
                    if (profileKey == null) {
                        Res.string.team_profile_save_new
                    } else {
                        Res.string.team_profile_save_edit
                    },
                ),
                primary = true,
                enabled = canSave,
                onClick = {
                    val leader = leaderKey
                    if (leader != null) {
                        onSave(
                            name.trim(),
                            leader,
                            members.map { it.copy(name = it.name.trim(), role = it.role.trim()) },
                            pickedGroups.toList(),
                            description.trim(),
                        )
                    }
                },
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

/** 一行紧凑协同组员：头像 + 组内名/角色两个内联输入 + 移除按钮。 */
@Composable
private fun MemberCompactRow(
    draft: TeamMemberDraft,
    onNameChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        unfocusedBorderColor = Color.Transparent,
        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    )
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
            modifier = Modifier.padding(horizontal = sp.xs, vertical = sp.xxs),
        ) {
            PeerAvatar(
                name = draft.name.ifBlank { stringResource(Res.string.team_profile_member_name_label) },
                avatar = AVATAR_LEGACY_INITIAL_VALUE,
                size = sp.xl,
                shape = CircleShape,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(sp.xxs),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = {
                        Text(
                            text = stringResource(Res.string.team_profile_member_name_label),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.role,
                    onValueChange = onRoleChange,
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = {
                        Text(
                            text = stringResource(Res.string.team_profile_member_role_label),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AppIconButton(
                icon = Res.drawable.ic_close,
                contentDescription = stringResource(Res.string.team_roster_remove_member),
                onClick = onRemove,
                modifier = Modifier.size(sp.md10),
            )
        }
    }
}

/** 单选 / 多选都用的 chip（选中 = 主色底，与职责选择同一套颜色）。 */
@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val sp = LocalAppSpacing.current
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = CircleShape,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
        )
    }
}
