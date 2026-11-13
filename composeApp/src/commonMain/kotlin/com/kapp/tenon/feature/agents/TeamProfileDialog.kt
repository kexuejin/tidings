// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.group_auth_save
import com.kapp.tenon.generated.resources.team_profile_dialog_title_edit
import com.kapp.tenon.generated.resources.team_profile_dialog_title_new
import com.kapp.tenon.generated.resources.team_profile_groups_hint
import com.kapp.tenon.generated.resources.team_profile_groups_in
import com.kapp.tenon.generated.resources.team_profile_groups_label
import com.kapp.tenon.generated.resources.team_profile_groups_none
import com.kapp.tenon.generated.resources.team_profile_groups_optional
import com.kapp.tenon.generated.resources.team_profile_leader_hint
import com.kapp.tenon.generated.resources.team_profile_leader_label
import com.kapp.tenon.generated.resources.team_profile_member_empty
import com.kapp.tenon.generated.resources.team_profile_members_label
import com.kapp.tenon.generated.resources.team_profile_name_hint
import com.kapp.tenon.generated.resources.team_profile_name_label
import com.kapp.tenon.generated.resources.team_profile_no_leaders
import com.kapp.tenon.generated.resources.team_roster_add_member
import com.kapp.tenon.generated.resources.team_roster_member_name_label
import com.kapp.tenon.generated.resources.team_roster_member_role_label
import com.kapp.tenon.generated.resources.team_roster_remove_member
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.AppButtonTone
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.components.newDraft
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.stringResource

/** 领队候选：一个本机可上报的 agent（`agentKey` 是档案键，`label` 是人看的名）。 */
internal data class TeamLeaderOption(val agentKey: String, val label: String)

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
)

/**
 * 群候选。
 *
 * @param joined 这个群已经在引用这支队伍（席位 `teamId` 相同）。勾上它没有意义，
 *   取消勾也不会把它摘出去 —— 从群里移除是那个群的决定（`DetachTeam`），
 *   不该藏在「编辑团队」里。
 */
internal data class TeamGroupOption(val groupId: String, val name: String, val joined: Boolean)

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
 * @param leaders 本机可当领队的 agent（空 = 本机还没有 agent，保存置灰并给出路）。
 * @param initialLeaderAgentId 编辑态的领队（新建时传 null ⇒ 取第一个候选）。
 */
@Composable
internal fun TeamProfileDialog(
    profileKey: String?,
    initialName: String,
    leaders: List<TeamLeaderOption>,
    initialLeaderAgentId: String?,
    initialMembers: List<TeamMemberDraft>,
    groups: List<TeamGroupOption>,
    onSave: (name: String, leaderAgentId: String, members: List<TeamMemberDraft>, groupIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    var name by remember { mutableStateOf(initialName) }
    var leaderKey by remember(leaders) {
        mutableStateOf(
            leaders.firstOrNull { it.agentKey == initialLeaderAgentId }?.agentKey
                ?: initialLeaderAgentId
                ?: leaders.firstOrNull()?.agentKey,
        )
    }
    var members by remember { mutableStateOf(initialMembers) }
    // 只记「这次要新加进去的群」；已加入的群不进这个集合（勾了也不重复加）。
    var pickedGroups by remember { mutableStateOf(emptySet<String>()) }

    val addableGroups = addableTeamGroups(groups)
    // **不勾群也能保存**（「先建档、后加群」）：没有席位的团队在群里/手机上确实看不见，
    // 但那是一个**知情选择**，不是非法输入 —— 出路是群信息的「添加团队」。
    // 界面的责任是说清代价（见下方 `team_profile_groups_optional`），不是把按钮置灰。
    val canSave = name.isNotBlank() &&
        leaderKey != null &&
        members.isNotEmpty() &&
        members.all { it.name.isNotBlank() }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (profileKey == null) {
                        Res.string.team_profile_dialog_title_new
                    } else {
                        Res.string.team_profile_dialog_title_edit
                    },
                ),
            )
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.team_profile_name_label)) },
                    placeholder = { Text(stringResource(Res.string.team_profile_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionLabel(stringResource(Res.string.team_profile_leader_label))
                if (leaders.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.team_profile_no_leaders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (profileKey != null) {
                    // 编辑态**不给换领队**：换领队 = 换席位（旧 leader 的 teamId 要摘、
                    // 新 leader 的席位要建），那是另一条命令（DetachTeam + AttachTeam），
                    // 不是「改个名字」。这里画成只读，而不是给一排点了不生效的 chip ——
                    // 那正是本仓最贵的故障形态（改了没反应、也没报错）。
                    Text(
                        text = leaders.firstOrNull { it.agentKey == leaderKey }?.label ?: (leaderKey ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                        verticalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        leaders.forEach { option ->
                            SelectChip(
                                label = option.label,
                                selected = option.agentKey == leaderKey,
                                onClick = { leaderKey = option.agentKey },
                            )
                        }
                    }
                    Text(
                        text = stringResource(Res.string.team_profile_leader_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionLabel(stringResource(Res.string.team_profile_members_label))
                if (members.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.team_profile_member_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 行索引就是身份：不按名字找（改名字不该让光标跳行）。
                members.forEachIndexed { index, draft ->
                    MemberRow(
                        draft = draft,
                        onNameChange = { value ->
                            members = members.mapIndexed { i, d ->
                                if (i == index) d.copy(name = value) else d
                            }
                        },
                        onRoleChange = { value ->
                            members = members.mapIndexed { i, d ->
                                if (i == index) d.copy(role = value) else d
                            }
                        },
                        onRemove = { members = members.filterIndexed { i, _ -> i != index } },
                    )
                }
                AppTextButton(
                    text = stringResource(Res.string.team_roster_add_member),
                    onClick = {
                        // id 现在就定死（编辑保全）：之后改名不重算，执行档才对得上。
                        members = members + newDraft(
                            name = "",
                            role = "",
                            takenTokens = emptySet(),
                            existing = members,
                        )
                    },
                    size = AppButtonSize.Small,
                )

                SectionLabel(stringResource(Res.string.team_profile_groups_label))
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
                        // 已加入的群画成只读 chip：把「它已经在哪」和「这次还能加哪」
                        // 放在同一行，用户不必去别的屏对。
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
                    Text(
                        text = stringResource(Res.string.team_profile_groups_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 新建且一个群都没勾：如实说代价与出路。这不是「不推荐」，是**两个都成立**的
                    // 选择 —— 先建档的人往往还没决定这支队进哪个群。
                    if (profileKey == null && pickedGroups.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.team_profile_groups_optional),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.group_auth_save),
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

/** 段标题：一句话 + 与正文字号差一档（与团队编制对话框同一套层次）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LocalAppSpacing.current.sm6),
    )
}

/** 一行组员：组内名 + 角色 + 移除。两列等宽（窄屏也不折行）。 */
@Composable
private fun MemberRow(
    draft: TeamMemberDraft,
    onNameChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Surface(
        // 卡片圆角走既有 token（与团队卡、员工卡同一档）：这里是"一块卡"，不是按钮。
        shape = LocalCardShape.current,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(sp.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(Res.string.team_roster_member_name_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.role,
                    onValueChange = onRoleChange,
                    label = { Text(stringResource(Res.string.team_roster_member_role_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            AppTextButton(
                text = stringResource(Res.string.team_roster_remove_member),
                onClick = onRemove,
                tone = AppButtonTone.Danger,
                size = AppButtonSize.Small,
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
