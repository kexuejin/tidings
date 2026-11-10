// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.AgentMemberName
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.TeamRosterField
import com.kapp.tenon.core.protocol.TeamRosterIssue
import com.kapp.tenon.core.protocol.TeamRosterIssueKind
import com.kapp.tenon.core.protocol.TeamRosterValidation
import com.kapp.tenon.core.protocol.VirtualAgentMember
import org.jetbrains.compose.resources.StringResource
import com.kapp.tenon.core.protocol.isTeam
import com.kapp.tenon.core.protocol.newId
import com.kapp.tenon.domain.TeamExecutionKindsUi
import com.kapp.tenon.domain.TeamExecutionMemberUi
import com.kapp.tenon.domain.TeamExecutionPort
import com.kapp.tenon.domain.TeamExecutionProfileUi
import com.kapp.tenon.domain.TeamExecutionSaveResult
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.group_auth_save
import com.kapp.tenon.generated.resources.team_roster_add_member
import com.kapp.tenon.generated.resources.team_roster_conflict_warning
import com.kapp.tenon.generated.resources.team_roster_dialog_title
import com.kapp.tenon.generated.resources.team_roster_disable_note
import com.kapp.tenon.generated.resources.team_roster_duplicate_warning
import com.kapp.tenon.generated.resources.team_roster_enable_desc
import com.kapp.tenon.generated.resources.team_roster_enable_switch
import com.kapp.tenon.generated.resources.team_roster_leader_label
import com.kapp.tenon.generated.resources.team_roster_limit_warning
import com.kapp.tenon.generated.resources.team_roster_max_members
import com.kapp.tenon.generated.resources.team_roster_member_index
import com.kapp.tenon.generated.resources.team_roster_member_name_label
import com.kapp.tenon.generated.resources.team_roster_member_note
import com.kapp.tenon.generated.resources.team_roster_member_role_label
import com.kapp.tenon.generated.resources.team_roster_members_section
import com.kapp.tenon.generated.resources.team_roster_mobile_notice
import com.kapp.tenon.generated.resources.team_roster_no_ws_warning
import com.kapp.tenon.generated.resources.team_roster_role_architect
import com.kapp.tenon.generated.resources.team_roster_role_backend
import com.kapp.tenon.generated.resources.team_roster_role_code
import com.kapp.tenon.generated.resources.team_roster_role_frontend
import com.kapp.tenon.generated.resources.team_roster_role_review
import com.kapp.tenon.generated.resources.team_roster_role_security
import com.kapp.tenon.generated.resources.team_roster_role_test
import com.kapp.tenon.generated.resources.team_roster_quick_roles
import com.kapp.tenon.generated.resources.team_roster_remove_member
import com.kapp.tenon.generated.resources.team_roster_required_warning
import com.kapp.tenon.generated.resources.team_exec_config_label
import com.kapp.tenon.generated.resources.team_exec_kind_reviewer
import com.kapp.tenon.generated.resources.team_exec_kind_worker
import com.kapp.tenon.generated.resources.team_exec_model_default
import com.kapp.tenon.generated.resources.team_exec_no_drivers
import com.kapp.tenon.generated.resources.team_exec_pick_driver
import com.kapp.tenon.generated.resources.team_roster_seat_label
import com.kapp.tenon.generated.resources.team_roster_slug_warning
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 团队编制对话框（团队创建方案 §2.2；v1 只做**声明与展示**）。
 *
 * 与 [SetGroupAgentWorkspacesDialog] 同族（同一骨架、同一保存节奏：一次保存 =
 * 一次 rev+1、一次广播）。手机与桌面**共用这一份实现**（AGENTS §2.6：名册是内容，
 * 不是外壳）—— 双端唯一的差异是底部那行宿主注解（[showHostManagedNotice]，手机壳
 * 传 true；桌面宿主自己就是执行方，不画），参数化而不是复制。
 *
 * ## v1 的如实边界（写进文案，不写进暗示）
 *
 * - 组员是**分工标签**：不占群席位、不单独执行（§6.2），注解明说；
 * - `@` 仍然只指向领队 —— 组员名 v1 不进 mention 池（那是 v2）；
 * - 不展示「运行中/任务结束后生效」这类没有数据来源的状态（无团队级 BUSY）。
 *
 * ## 编辑保全契约（§3.2）
 *
 * 既有组员的 `agentId` 严格沿用（[TeamMemberDraft] 承诺改名不重算）—— 重算会让
 * v2 执行档索引失联。只有新增行由界面生成 id。
 *
 * ## 席位与预算
 *
 * - 席位定位键 = `(groupId, defaultWsId)`；多席位时顶部显式选择（§3.5），默认选中
 *   「代表席位」（调用方用 core 的 `representativeSeat` 算好传进来）；
 * - 没绑默认工作区的席位**不在可编辑列表里**（§3.1：不替它猜一个工作区）；
 *   一个可编辑席位都没有时，整个对话框退化为引导态（[team_roster_no_ws_warning]），
 *   开关与保存置灰 —— Zero-Workspace Guard。
 */
@Composable
fun TeamRosterDialog(
    /** 对话框标题里的 agent 显示名（群信息页那一行的名字）。 */
    agentName: String,
    /** 本机在该群的全部席位（含没绑工作区的；没绑的不进可编辑列表）。 */
    seats: List<VirtualAgentMember>,
    /** 默认选中的席位（调用方按 `representativeSeat` 算）；null = 取第一个可编辑席位。 */
    initialSeatWsId: String?,
    /** 群内全部可被 @ 的 token（大小写不敏感比较；由调用方从 mentionKeys + 声明汇总）。 */
    takenTokens: Set<String>,
    onDismiss: () -> Unit,
    /**
     * 保存：`(defaultWsId, roster)`。roster 为 null = 关闭团队。一次保存 = 一次
     * rev+1、一次广播（域层 [com.kapp.tenon.domain.RelayController.handleSetGroupTeamDeclaration]）。
     */
    onSave: (defaultWsId: String, roster: List<TeamMemberDraft>?) -> Unit,
    /** 手机壳传 true：底部注解「执行驱动由桌面宿主托管」（§2.2 双端形态契约）。 */
    showHostManagedNotice: Boolean = false,
    /**
     * 本机 nodeId：执行配置段只对**席位宿主**开放（§7.1 —— 执行配置是宿主本地事实，
     * 非宿主看到的是只读投影）。
     */
    selfNodeId: String? = null,
    /** 群 id：执行档主键 `(groupId, defaultWsId, leaderAgentId)` 之一（§3.2）。 */
    groupId: String? = null,
    /**
     * 团队执行档端口（§6.3）：null = 该壳没有执行面（手机等非宿主）⇒ 执行配置段
     * 整段不画（声明编辑不受影响）。
     */
    executionPort: TeamExecutionPort? = null,
) {
    val sp = LocalAppSpacing.current
    val editableSeats = remember(seats) { seats.filter { it.defaultWsId != null } }
    val initialSeat = editableSeats.firstOrNull { it.defaultWsId == initialSeatWsId }
        ?: editableSeats.firstOrNull()
    var selectedWsId by remember(editableSeats) { mutableStateOf(initialSeat?.defaultWsId) }
    val selectedSeat = editableSeats.firstOrNull { it.defaultWsId == selectedWsId } ?: initialSeat

    // 开关初值 = 该席位当前是不是团队；关闭再打开保留已保存的编制。
    var enabled by remember(selectedSeat) { mutableStateOf(selectedSeat?.isTeam == true) }
    // 预设三行的角色标签在 Composable 作用域解析一次（stringResource 不能进普通 lambda）。
    val presetRoleLabels = listOf(
        stringResource(Res.string.team_roster_role_code),
        stringResource(Res.string.team_roster_role_review),
        stringResource(Res.string.team_roster_role_test),
    )
    // 名册初值：既有组员严格沿用 agentId（编辑保全）；从零开启时给经典研发组预设。
    var drafts by remember(selectedSeat) {
        mutableStateOf(initialRosterDrafts(selectedSeat, takenTokens, presetRoleLabels))
    }
    // 校验问题：保存时算一次，就地指出行/字段（§2.3 —— 不允许「存下去再说」）。
    var issues by remember(selectedSeat) { mutableStateOf<List<TeamRosterIssue>>(emptyList()) }
    // **执行配置段**（§7.1）：仅席位宿主 + 端口在位。初值 = 既有执行档沿同 agentId
    // 对齐（编辑保全：改名/改角色不重算执行配置）；保存时按 agentId 映射回去。
    val executionEditable = executionPort != null &&
        selectedSeat != null && selectedSeat.hostNodeId == selfNodeId &&
        selectedSeat.defaultWsId != null
    var execMembers by remember(selectedSeat) {
        mutableStateOf(
            if (executionEditable && selectedSeat != null && executionPort != null && groupId != null) {
                // 既有执行档沿同 agentId 对齐（编辑保全）；没配过 ⇒ 按声明顺序给默认
                // （第一名 worker、其余 reviewer）＋ 空驱动让宿主显式选。
                val seat = selectedSeat
                val seatWsId = seat.defaultWsId
                val existing = if (seatWsId == null) {
                    null
                } else {
                    executionPort.profileOf(groupId, seatWsId, seat.agentId)
                }
                seat.teamMembers.mapIndexed { index, member ->
                    val prior = existing?.members?.firstOrNull {
                        it.agentId.trim().lowercase() == member.agentId.trim().lowercase()
                    }
                    TeamExecutionMemberUi(
                        agentId = member.agentId,
                        kind = prior?.kind
                            ?: if (index == 0) TeamExecutionKindsUi.WORKER else TeamExecutionKindsUi.REVIEWER,
                        driverKey = prior?.driverKey ?: "",
                        models = prior?.models ?: emptyList(),
                    )
                }
            } else {
                emptyList()
            },
        )
    }
    var execReasons by remember(selectedSeat) { mutableStateOf<List<String>>(emptyList()) }
    val driverCatalog = remember(selectedSeat) { executionPort?.driverCatalog().orEmpty() }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.team_roster_dialog_title, agentName)) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                if (selectedSeat == null) {
                    // Zero-Workspace Guard：没有可编辑席位 —— 引导去工作区授权，开关置灰。
                    Text(
                        text = stringResource(Res.string.team_roster_no_ws_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = sp.md),
                    )
                } else {
                    if (editableSeats.size > 1) {
                        SeatSelector(
                            seats = editableSeats,
                            selectedWsId = selectedWsId,
                            onSelect = { selectedWsId = it },
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AppSwitch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                // 从零开启时才给预设；已有的编制不动（§2.3）。
                                if (it && drafts.isEmpty()) {
                                    drafts = teamRosterPreset(takenTokens, presetRoleLabels)
                                }
                                issues = emptyList()
                            },
                            enabled = true,
                        )
                        Column {
                            Text(
                                text = stringResource(Res.string.team_roster_enable_switch),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(Res.string.team_roster_enable_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = stringResource(Res.string.team_roster_leader_label, selectedSeat.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (enabled) {
                        Text(
                            text = stringResource(Res.string.team_roster_members_section),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = sp.sm6),
                        )
                        drafts.forEachIndexed { index, draft ->
                            MemberDraftRow(
                                index = index,
                                draft = draft,
                                issue = issues.firstOrNull { it.index == index },
                                onNameChange = { value ->
                                    drafts = drafts.updateAt(index) { it.copy(name = value) }
                                },
                                onRoleChange = { value ->
                                    drafts = drafts.updateAt(index) { it.copy(role = value) }
                                },
                                onRemove = {
                                    drafts = drafts.filterIndexed { i, _ -> i != index }
                                    issues = emptyList()
                                },
                            )
                            // **执行配置行**（§7.1，仅席位宿主 + 端口在位时画）：
                            // kind（执行/审查）+ 驱动下拉（只在位驱动）。模型 P1/P2 才开放，
                            // P0-A 一律「默认（继承）」。
                            if (executionEditable && index < execMembers.size) {
                                ExecutionConfigRow(
                                    member = execMembers[index],
                                    drivers = driverCatalog,
                                    onKindChange = { kind ->
                                        execMembers = execMembers.updateExecAt(index) { it.copy(kind = kind) }
                                    },
                                    onDriverChange = { key ->
                                        execMembers = execMembers.updateExecAt(index) { it.copy(driverKey = key) }
                                    },
                                )
                            }
                        }
                        if (execReasons.isNotEmpty()) {
                            Column {
                                execReasons.forEach { reason ->
                                    Text(
                                        text = reason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        if (issues.any { it.kind == TeamRosterIssueKind.TOO_MANY }) {
                            Text(
                                text = stringResource(Res.string.team_roster_max_members, Limits.TEAM_MEMBERS_MAX),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (drafts.size < Limits.TEAM_MEMBERS_MAX) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                                modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
                            ) {
                                AppTextButton(
                                    text = stringResource(Res.string.team_roster_add_member),
                                    onClick = {
                                        drafts = drafts + newDraft(name = "", role = "", takenTokens, drafts)
                                        issues = emptyList()
                                    },
                                    size = AppButtonSize.Small,
                                )
                                Text(
                                    text = stringResource(Res.string.team_roster_quick_roles),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                quickRoles.forEach { quick ->
                                    // 标签在 Composable 作用域解析（onClick 不是 Composable 上下文）。
                                    val roleLabel = stringResource(quick.roleRes)
                                    AppTextButton(
                                        text = "+" + roleLabel,
                                        onClick = {
                                            if (drafts.size < Limits.TEAM_MEMBERS_MAX) {
                                                drafts = drafts + quick.toDraft(
                                                    roleLabel = roleLabel,
                                                    takenTokens = takenTokens,
                                                    existing = drafts,
                                                )
                                                issues = emptyList()
                                            }
                                        },
                                        size = AppButtonSize.Small,
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(Res.string.team_roster_member_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = sp.xs),
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.team_roster_disable_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showHostManagedNotice) {
                    Text(
                        text = stringResource(Res.string.team_roster_mobile_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = sp.sm6),
                    )
                }
            }
        },
        confirmButton = {
            val seat = selectedSeat
            RelayDialogButton(
                text = stringResource(Res.string.group_auth_save),
                enabled = seat != null,
                primary = true,
                onClick = {
                    // 保存流程用 if/else 链而非早退标签（onClick 标签在非 inline
                    // 传递链上会被编译器判 unresolved —— 本仓踩过一次的形态）。
                    val proceed = if (seat == null) {
                        false
                    } else if (!enabled) {
                        onSave(seat.defaultWsId ?: "", null)
                        onDismiss()
                        false
                    } else {
                        // 保存前校验（§2.3）：错误就地指出行/字段，不允许存下去再说。
                        val pool = takenTokens - seat.teamMembers
                            .flatMap { listOf(it.name, it.name.lowercase()) }.toSet()
                        val found = TeamRosterValidation.validate(
                            drafts.map { it.copy(name = it.name.trim(), role = it.role.trim()) },
                            pool,
                        )
                        if (found.isNotEmpty()) {
                            issues = found
                            false
                        } else {
                            // **执行档先写、声明后广播**（§7.1 一条动作：任一步失败整次不保存）。
                            val seatWsId = seat.defaultWsId
                            if (executionEditable && executionPort != null && groupId != null &&
                                seatWsId != null
                            ) {
                                val execProfile = TeamExecutionProfileUi(
                                    groupId = groupId,
                                    defaultWsId = seatWsId,
                                    leaderAgentId = seat.agentId,
                                    members = execMembers,
                                )
                                // 档 ⊆ 声明（§3.4.6）：按**保存后的稳定 agentId**对齐。
                                val declared = drafts.map { it.agentId.trim().lowercase() }.toSet()
                                when (val verdict = executionPort.save(execProfile, declared)) {
                                    is TeamExecutionSaveResult.Rejected -> {
                                        execReasons = verdict.reasons
                                        false
                                    }
                                    TeamExecutionSaveResult.Saved -> true
                                }
                            } else {
                                true
                            }
                        }
                    }
                    if (proceed) {
                        val ws = seat?.defaultWsId
                        if (ws != null) {
                            onSave(ws, drafts.map { it.copy(name = it.name.trim(), role = it.role.trim()) })
                        }
                        onDismiss()
                    }
                },
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

/** 多席位选择：一行可点的席位 chip（§2.1 —— 「第一条」不是用户意图）。 */
@Composable
private fun SeatSelector(
    seats: List<VirtualAgentMember>,
    selectedWsId: String?,
    onSelect: (String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Column {
        Text(
            text = stringResource(Res.string.team_roster_seat_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            modifier = Modifier.fillMaxWidth().padding(top = sp.xxs),
        ) {
            seats.forEach { seat ->
                val wsId = seat.defaultWsId ?: return@forEach
                val selected = wsId == selectedWsId
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    onClick = { onSelect(wsId) },
                ) {
                    Text(
                        // 席位用 @ token 展示（§2.1）—— 那是群里叫得应的名字。
                        text = "@" + seat.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                    )
                }
            }
        }
    }
}

/** 一行组员：名字（@ token）+ 角色标签 + 删除；问题就地显示在字段下。 */
@Composable
private fun MemberDraftRow(
    index: Int,
    draft: TeamMemberDraft,
    issue: TeamRosterIssue?,
    onNameChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(sp.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(Res.string.team_roster_member_index, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppTextButton(
                    text = stringResource(Res.string.team_roster_remove_member),
                    onClick = onRemove,
                    tone = AppButtonTone.Danger,
                    size = AppButtonSize.Small,
                )
            }
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(Res.string.team_roster_member_name_label)) },
                    isError = issue?.field == TeamRosterField.NAME,
                    supportingText = issue?.takeIf { it.field == TeamRosterField.NAME }?.let {
                        { Text(issueText(it), color = MaterialTheme.colorScheme.error) }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.role,
                    onValueChange = onRoleChange,
                    label = { Text(stringResource(Res.string.team_roster_member_role_label)) },
                    isError = issue?.field == TeamRosterField.ROLE,
                    supportingText = issue?.takeIf { it.field == TeamRosterField.ROLE }?.let {
                        { Text(issueText(it), color = MaterialTheme.colorScheme.error) }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 组员的执行配置行（§7.1）：`(●) 执行 ( ) 审查` + 驱动下拉。
 * 只在席位宿主 + 端口在位时渲染；模型下拉 P1/P2 才开放，本期固定「默认（继承）」。
 */
@Composable
private fun ExecutionConfigRow(
    member: TeamExecutionMemberUi,
    drivers: Set<String>,
    onKindChange: (String) -> Unit,
    onDriverChange: (String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        modifier = Modifier.fillMaxWidth().padding(vertical = sp.xxs),
    ) {
        Text(
            text = stringResource(Res.string.team_exec_config_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // kind 选择：两个互斥 chip（执行 / 审查）。SegmentedButton 依赖较新 M3，
        // 这里用既有 chip 形状与选中色 —— 与 SeatSelector 同一套。
        listOf(
            TeamExecutionKindsUi.WORKER to stringResource(Res.string.team_exec_kind_worker),
            TeamExecutionKindsUi.REVIEWER to stringResource(Res.string.team_exec_kind_reviewer),
        ).forEach { (kind, label) ->
            val selected = member.kind == kind
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                onClick = { onKindChange(kind) },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                )
            }
        }
        Spacer(Modifier.width(sp.sm6))
        if (drivers.isEmpty()) {
            Text(
                text = stringResource(Res.string.team_exec_no_drivers),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            // 驱动下拉：宿主在位驱动（§7.1 —— "装了没"是当下的事实）。
            var expanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { expanded = true },
                ) {
                    Text(
                        text = member.driverKey.ifBlank { stringResource(Res.string.team_exec_pick_driver) },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                    )
                }
                if (expanded) {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(sp.xxs),
                    ) {
                        drivers.sorted().forEach { key ->
                            Text(
                                text = key,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDriverChange(key)
                                        expanded = false
                                    }
                                    .padding(horizontal = sp.sm6, vertical = sp.xxs),
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = stringResource(Res.string.team_exec_model_default),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun issueText(issue: TeamRosterIssue): String = when (issue.kind) {
    TeamRosterIssueKind.EMPTY -> stringResource(Res.string.team_roster_required_warning)
    TeamRosterIssueKind.TOO_LONG -> stringResource(Res.string.team_roster_limit_warning, issue.limit ?: 0)
    TeamRosterIssueKind.BAD_SLUG -> stringResource(Res.string.team_roster_slug_warning)
    TeamRosterIssueKind.DUPLICATE_IN_TEAM -> stringResource(Res.string.team_roster_duplicate_warning)
    TeamRosterIssueKind.TAKEN ->
        stringResource(Res.string.team_roster_conflict_warning, issue.conflictWith.orEmpty())
    TeamRosterIssueKind.TOO_MANY ->
        stringResource(Res.string.team_roster_max_members, issue.limit ?: Limits.TEAM_MEMBERS_MAX)
}

// ---------------------------------------------------------------- 纯函数（可测）

private fun List<TeamMemberDraft>.updateAt(index: Int, transform: (TeamMemberDraft) -> TeamMemberDraft): List<TeamMemberDraft> =
    mapIndexed { i, d -> if (i == index) transform(d) else d }

// 与上面的 updateAt 在 JVM 层同签名（(List, int, Function1) -> List）—— 平台声明冲突。
// @JvmName 只改 JVM 方法名，Kotlin 调用点不动。
@JvmName("updateExecMemberAt")
private fun List<TeamExecutionMemberUi>.updateExecAt(index: Int, transform: (TeamExecutionMemberUi) -> TeamExecutionMemberUi): List<TeamExecutionMemberUi> =
    mapIndexed { i, d -> if (i == index) transform(d) else d }

/** 新建行的内部 id（§3.2）：`m-<slug>-<4 位随机>`；之后改名不重算。 */
internal fun newDraftId(name: String): String =
    "m-" + AgentMemberName.slug(name).ifBlank { "member" } + "-" + newId().take(4)

/** 新增一行的初值：名字留空等用户填，id 现在就定死（编辑保全）。 */
internal fun newDraft(
    name: String,
    role: String,
    takenTokens: Set<String>,
    existing: List<TeamMemberDraft>,
): TeamMemberDraft {
    val uniqueName = AgentMemberName.unique(AgentMemberName.slug(name).ifBlank { "member" }, takenNames(takenTokens, existing))
    return TeamMemberDraft(agentId = newDraftId(uniqueName), name = uniqueName, role = role, isNew = true)
}

private fun takenNames(takenTokens: Set<String>, existing: List<TeamMemberDraft>): Set<String> =
    takenTokens + existing.map { it.name.lowercase() }

/** 经典研发组预设的**名字**（§2.2：coder / reviewer / tester）；角色标签由调用方给。 */
internal val teamRosterPresetBase: List<String> = listOf("coder", "reviewer", "tester")

/**
 * 快捷角色 chip（§2.2：架构 / 前端 / 后端 / 审查 / 安全）。
 *
 * 角色文案住 strings.xml（cjkLiteralGate：用户可见文案不进源码）——
 * [roleRes] 是资源引用，Composable 在点击时解析成标签传给 [toDraft]；
 * 名字自动 slug 化并与群内 token / 已有名册错开。
 */
internal val quickRoles: List<QuickRole> = listOf(
    QuickRole(Res.string.team_roster_role_architect, "architect"),
    QuickRole(Res.string.team_roster_role_frontend, "frontend"),
    QuickRole(Res.string.team_roster_role_backend, "backend"),
    QuickRole(Res.string.team_roster_role_review, "reviewer"),
    QuickRole(Res.string.team_roster_role_security, "security"),
)

internal data class QuickRole(val roleRes: StringResource, val nameBase: String) {
    fun toDraft(roleLabel: String, takenTokens: Set<String>, existing: List<TeamMemberDraft>): TeamMemberDraft {
        val name = AgentMemberName.unique(nameBase, takenNames(takenTokens, existing))
        return TeamMemberDraft(agentId = newDraftId(name), name = name, role = roleLabel, isNew = true)
    }
}

/**
 * 打开对话框时的名册初值：既有组员**严格沿用**（agentId / 名字 / 角色原样，编辑保全）；
 * 从零开启（没编过队）给经典研发组预设，名字与群内 token 撞了就自动让开一位。
 *
 * @param presetRoleLabels 预设三行的角色标签（调用方从 strings.xml 解析 —— 文案不进源码）。
 */
internal fun initialRosterDrafts(
    seat: VirtualAgentMember?,
    takenTokens: Set<String>,
    presetRoleLabels: List<String>,
): List<TeamMemberDraft> {
    val existing = seat?.teamMembers.orEmpty()
    if (existing.isNotEmpty()) {
        return existing.map { TeamMemberDraft(agentId = it.agentId, name = it.name, role = it.role, isNew = false) }
    }
    if (seat?.entityType == EntityTypes.TEAM) {
        // 声明是团队但组员清单为空（手改帧/旧数据）：给预设，别让用户对着空白猜。
        return teamRosterPreset(takenTokens, presetRoleLabels)
    }
    return emptyList()
}

/** 预设：三个经典组员（coder/reviewer/tester）；名字与群内既有 token 撞了自动 -2。 */
internal fun teamRosterPreset(takenTokens: Set<String>, presetRoleLabels: List<String>): List<TeamMemberDraft> {
    val taken = takenTokens.toMutableSet()
    return teamRosterPresetBase.mapIndexed { i, base ->
        val name = AgentMemberName.unique(base, taken)
        taken += name.lowercase()
        TeamMemberDraft(agentId = newDraftId(name), name = name, role = presetRoleLabels.getOrElse(i) { base }, isNew = true)
    }
}

/**
 * agent 行上的「团队 (N)」徽标文案（§2.1：按该 agent 的席位聚合 —— 任一席位 isTeam
 * 即显示，N = 那条团队的组员数）。返回 null = 不是团队，不画徽标。
 */
internal fun teamBadgeCountOf(seats: List<VirtualAgentMember>): Int? =
    seats.firstOrNull { it.isTeam }?.teamMembers?.size?.takeIf { seats.any { s -> s.isTeam } }
