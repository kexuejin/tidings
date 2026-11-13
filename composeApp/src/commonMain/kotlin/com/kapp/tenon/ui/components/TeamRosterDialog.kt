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
import com.kapp.tenon.core.protocol.TeamRosterName
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
import com.kapp.tenon.generated.resources.team_roster_alias_hint
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
import com.kapp.tenon.generated.resources.seat_directory_resolved
import com.kapp.tenon.generated.resources.seat_directory_unresolved
import com.kapp.tenon.generated.resources.team_exec_config_label
import com.kapp.tenon.generated.resources.team_exec_custom_prompt_placeholder
import com.kapp.tenon.generated.resources.team_exec_kind_reviewer
import com.kapp.tenon.generated.resources.team_exec_kind_worker
import com.kapp.tenon.generated.resources.team_exec_model_default
import com.kapp.tenon.generated.resources.team_exec_model_placeholder
import com.kapp.tenon.generated.resources.team_exec_no_drivers
import com.kapp.tenon.generated.resources.team_exec_pick_driver
import com.kapp.tenon.generated.resources.team_exec_prompt_badge
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
 * - 席位定位键 = `(groupId, hostNodeId, agentId)`（D4 的席位身份；`defaultWsId` 只是
 *   执行投影，可为 null —— 不再是身份的一半）；多席位时顶部显式选择（§3.5），默认
 *   选中「代表席位」（调用方用 core 的 `representativeSeat` 算好 agentId 传进来）；
 * - **所有席位都可编辑**（继承指针后，未暴露目录的席位同样能编团队 —— 执行面另行
 *   提示）；一个席位都没有时，整个对话框退化为引导态（[team_roster_no_ws_warning]）；
 * - **Zero-Workspace Guard**：群没设项目目录指针（宿主端口答 null）时保存置灰并
 *   提示 —— 执行档挂在一个解析不出来的目录上没有意义。
 */
@Composable
fun TeamRosterDialog(
    /** 对话框标题里的 agent 显示名（群信息页那一行的名字）。 */
    agentName: String,
    /** 本机在该群的全部席位（D4：一个 agent 一条；含未暴露目录的）。 */
    seats: List<VirtualAgentMember>,
    /** 默认选中的席位 agentId（调用方按 `representativeSeat` 算）；null = 取第一个席位。 */
    initialSeatAgentId: String?,
    /** 群内全部可被 @ 的 token（大小写不敏感比较；由调用方从 mentionKeys + 声明汇总）。 */
    takenTokens: Set<String>,
    onDismiss: () -> Unit,
    /**
     * 保存：`(agentId, roster)`。roster 为 null = 关闭团队。一次保存 = 一次
     * rev+1、一次广播（域层 [com.kapp.tenon.domain.RelayController.handleSetGroupTeamDeclaration]）。
     */
    onSave: (agentId: String, roster: List<TeamMemberDraft>?) -> Unit,
    /** 手机壳传 true：底部注解「执行驱动由桌面宿主托管」（§2.2 双端形态契约）。 */
    showHostManagedNotice: Boolean = false,
    /**
     * 本机 nodeId：执行配置段只对**席位宿主**开放（§7.1 —— 执行配置是宿主本地事实，
     * 非宿主看到的是只读投影）。
     */
    selfNodeId: String? = null,
    /** 群 id：**用来解析项目目录指针**（执行档主键是 `(目录, 领队)`，群只决定目录）。 */
    groupId: String? = null,
    /**
     * 团队执行档端口（§6.3）：null = 该壳没有执行面（手机等非宿主）⇒ 执行配置段
     * 整段不画（声明编辑不受影响）。
     */
    executionPort: TeamExecutionPort? = null,
) {
    val sp = LocalAppSpacing.current
    // D4：席位身份不再要求 defaultWsId —— 未暴露目录的席位同样可以编团队。
    val editableSeats = remember(seats) { seats }
    val initialSeat = initialSelectedSeatOf(editableSeats, initialSeatAgentId)
    var selectedAgentId by remember(editableSeats) { mutableStateOf(initialSeat?.agentId) }
    val selectedSeat = selectedSeatOf(editableSeats, selectedAgentId) ?: initialSeat
    // **Zero-Workspace Guard**（指针判据只在宿主端口在位时可用）：群没设项目目录
    // ⇒ 保存置灰 + 提示（执行档主键的一半解析不出来，存了也是死档）。
    // 手机壳没有端口 —— 指针是宿主本机事实，手机上不据此置灰（投影状态在席位
    // 选择器上如实展示）。
    val pointerMissing = executionPort != null && groupId != null &&
        executionPort.groupPointerDirId(groupId) == null

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
    // 读档键按 D16 = `(dirId, leaderAgentId)` —— dirId 来自群指针，不含群：
    // 同一支队在这个群配过，另一个群里 `@团队` 读到的就是同一份档。
    val executionEditable = executionPort != null &&
        selectedSeat != null && selectedSeat.hostNodeId == selfNodeId
    var execMembers by remember(selectedSeat) {
        mutableStateOf(
            if (executionEditable && selectedSeat != null && executionPort != null && groupId != null) {
                // 既有执行档沿同 agentId 对齐（编辑保全）；没配过 ⇒ 按声明顺序给默认
                // （第一名 worker、其余 reviewer）＋ 空驱动让宿主显式选。
                val seat = selectedSeat
                val pointerDirId = executionPort.groupPointerDirId(groupId)
                val existing = if (pointerDirId == null) {
                    null
                } else {
                    executionPort.profileOf(pointerDirId, seat.agentId)
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
                    // Zero-Workspace Guard（无席位）：引导去群设置选项目目录。
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
                            selectedAgentId = selectedAgentId,
                            onSelect = { selectedAgentId = it },
                        )
                    }
                    if (pointerMissing) {
                        // Zero-Workspace Guard（指针）：席位可看，执行档与保存置灰。
                        Text(
                            text = stringResource(Res.string.team_roster_no_ws_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = sp.xs),
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
                            enabled = !pointerMissing,
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
                            // kind（执行/审查）+ 驱动下拉（只在位驱动）+ 模型 + 人设。
                            // 模型从本轮起真的可写：`TeamExecutionMemberSpec.models`
                            // 一路带到 `executeCliTask(model=)`，两条保存期判据
                            // （驱动不支持 / 多值）会在这里把原因显示出来。
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
                                    onModelChange = { model ->
                                        execMembers = execMembers.updateExecAt(index) {
                                            it.copy(models = model?.let { m -> listOf(m) } ?: emptyList())
                                        }
                                    },
                                    onPromptChange = { prompt ->
                                        execMembers = execMembers.updateExecAt(index) {
                                            it.copy(customPrompt = prompt?.trim()?.take(2000)?.ifBlank { null })
                                        }
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
                        // **撞名不是错，是本群别名**：先把「保存后在这里叫什么」摆出来，
                        // 别让用户按档案里的名字去 @ 一个在本群叫别的名字的人。
                        val ownTokens = selectedSeat?.teamMembers.orEmpty()
                            .flatMap { listOf(it.name, it.name.lowercase()) }
                            .toSet()
                        val aliased = TeamRosterValidation
                            .resolveNames(drafts, takenTokens - ownTokens)
                            .filter { it.isAliased }
                        RosterAliasNotes(aliased)
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
                enabled = seat != null && !pointerMissing,
                primary = true,
                onClick = {
                    // 保存流程用 if/else 链而非早退标签（onClick 标签在非 inline
                    // 传递链上会被编译器判 unresolved —— 本仓踩过一次的形态）。
                    val proceed = if (seat == null) {
                        false
                    } else if (!enabled) {
                        onSave(seat.agentId, null)
                        onDismiss()
                        false
                    } else {
                        // 保存前校验（§2.3）：错误就地指出行/字段，不允许存下去再说。
                        // 撞名不在这里拦 —— 名字的最终形态由写路径的 `resolveNames` 决定，
                        // 这里只预览（上面的别名提示行），不另写一份判据。
                        val found = TeamRosterValidation.validate(
                            drafts.map { it.copy(name = it.name.trim(), role = it.role.trim()) },
                        )
                        if (found.isNotEmpty()) {
                            issues = found
                            false
                        } else {
                            // **执行档先写、声明后广播**（§7.1 一条动作：任一步失败整次不保存）。
                            // D16：执行档主键的一半 = 群指针的 dirId（不是席位 wsId）——
                            // 群没设指针就不落档（对话框保存路径的 fail-closed）。
                            val pointerDirId = groupId?.let { executionPort?.groupPointerDirId(it) }
                            if (executionEditable && executionPort != null && groupId != null &&
                                pointerDirId != null
                            ) {
                                val execProfile = TeamExecutionProfileUi(
                                    dirId = pointerDirId,
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
                        val agentId = seat?.agentId
                        if (agentId != null) {
                            onSave(agentId, drafts.map { it.copy(name = it.name.trim(), role = it.role.trim()) })
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
    selectedAgentId: String?,
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
                val selected = seat.agentId.equals(selectedAgentId, ignoreCase = true)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    onClick = { onSelect(seat.agentId) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.xxs),
                        modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                    ) {
                        Text(
                            // 席位用 @ token 展示（§2.1）—— 那是群里叫得应的名字。
                            text = "@" + seat.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 执行点状态：指针继承后席位可能未解析 —— chip 上如实带上
                        //（与入驻面板同一套状态口径，文案同源 strings.xml）。
                        Text(
                            text = if (seat.defaultWsId != null) {
                                stringResource(Res.string.seat_directory_resolved)
                            } else {
                                stringResource(Res.string.seat_directory_unresolved)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (seat.defaultWsId != null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                        )
                    }
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
 * 组员的执行配置行（§7.1）：`(●) 执行 ( ) 审查` + 驱动下拉 + 模型 + 人设。
 * 只在席位宿主 + 端口在位时渲染。
 *
 * ## 模型为什么是一个自由输入框，而不是下拉
 *
 * 本仓**没有**模型目录（各 CLI 的可用模型由厂商账号决定，没有任何一处声明过），
 * 造一份下拉等于编数据。所以这里只提供「写什么」这一格，合法性交给两道既有判据：
 * 保存期 `TeamExecutionValidation` 按 [com.kapp.tenon.agent.drivers.DriverModelFlags]
 * 拒（不支持按次指定模型的驱动 ⇔ 结构化原因），驱动侧 `withModel` 再响亮失败一次。
 *
 * 不在 UI 里判「这个驱动支不支持模型」是**刻意的**：那份词表只有一份，
 * 抄到 Compose 里必然两处漂移（本仓的 `CardVocabularyGuardTest` 就是为这类事立的）。
 */
@Composable
private fun ExecutionConfigRow(
    member: TeamExecutionMemberUi,
    drivers: Set<String>,
    onKindChange: (String) -> Unit,
    onDriverChange: (String) -> Unit,
    onPromptChange: (String?) -> Unit,
    onModelChange: (String?) -> Unit,
) {
    val sp = LocalAppSpacing.current
    var showPromptInput by remember { mutableStateOf(!member.customPrompt.isNullOrBlank()) }
    val currentModel = member.models.firstOrNull()
    var showModelInput by remember { mutableStateOf(!currentModel.isNullOrBlank()) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = sp.xxs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            modifier = Modifier.fillMaxWidth(),
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
            // 模型：留空 = 跟随驱动默认（「继承」）；填了就必须真的生效（见 KDoc）。
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (showModelInput) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                onClick = { showModelInput = !showModelInput },
            ) {
                Text(
                    text = currentModel ?: stringResource(Res.string.team_exec_model_default),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (showPromptInput) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                onClick = { showPromptInput = !showPromptInput },
            ) {
                Text(
                    text = stringResource(Res.string.team_exec_prompt_badge),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
                )
            }
        }
        if (showPromptInput) {
            OutlinedTextField(
                value = member.customPrompt.orEmpty(),
                onValueChange = { onPromptChange(it) },
                placeholder = {
                    Text(
                        text = stringResource(Res.string.team_exec_custom_prompt_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }
        if (showModelInput) {
            OutlinedTextField(
                value = currentModel.orEmpty(),
                onValueChange = { raw -> onModelChange(raw.trim().take(80).ifBlank { null }) },
                placeholder = {
                    Text(
                        text = stringResource(Res.string.team_exec_model_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
            )
        }
    }
}

@Composable
private fun issueText(issue: TeamRosterIssue): String = when (issue.kind) {
    TeamRosterIssueKind.EMPTY -> stringResource(Res.string.team_roster_required_warning)
    TeamRosterIssueKind.TOO_LONG -> stringResource(Res.string.team_roster_limit_warning, issue.limit ?: 0)
    TeamRosterIssueKind.BAD_SLUG -> stringResource(Res.string.team_roster_slug_warning)
    TeamRosterIssueKind.DUPLICATE_IN_TEAM -> stringResource(Res.string.team_roster_duplicate_warning)
    TeamRosterIssueKind.TOO_MANY ->
        stringResource(Res.string.team_roster_max_members, issue.limit ?: Limits.TEAM_MEMBERS_MAX)
}

/**
 * 本群别名的提示行（非错误色）：`coder` 在本群已经有别人用了，这里会叫 `coder-2`。
 *
 * 用 `for` 而不是 `forEach`：`stringResource` 是 @Composable，只能在 Composable 上下文
 * 里直接调用，塞进 lambda 会编译不过。
 */
@Composable
private fun RosterAliasNotes(names: List<TeamRosterName>) {
    if (names.isEmpty()) return
    Column {
        for (name in names) {
            Text(
                text = stringResource(
                    Res.string.team_roster_alias_hint,
                    name.aliasedFrom.orEmpty(),
                    name.name,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- 纯函数（可测）

/**
 * 打开对话框时的**默认选中席位**（D4：按 agentId 定位，大小写不敏感；`defaultWsId`
 * 不再参与 —— 未暴露目录的席位同样可选中）。找不到就取第一条（不猜第二条）。
 */
internal fun initialSelectedSeatOf(
    seats: List<VirtualAgentMember>,
    initialSeatAgentId: String?,
): VirtualAgentMember? =
    seats.firstOrNull { it.agentId.equals(initialSeatAgentId?.trim(), ignoreCase = true) }
        ?: seats.firstOrNull()

/** 选中态的席位定位（与 [initialSelectedSeatOf] 同一套匹配判据）。 */
internal fun selectedSeatOf(
    seats: List<VirtualAgentMember>,
    selectedAgentId: String?,
): VirtualAgentMember? =
    seats.firstOrNull { it.agentId.equals(selectedAgentId?.trim(), ignoreCase = true) }

private fun List<TeamMemberDraft>.updateAt(index: Int, transform: (TeamMemberDraft) -> TeamMemberDraft): List<TeamMemberDraft> =
    mapIndexed { i, d -> if (i == index) transform(d) else d }

// 与上面的 updateAt 是两份同形（`(List, int, Function1) -> List`）的扩展函数，
// 但 Kotlin 名不同 ⇒ JVM 方法名不同，不需要 `@JvmName` 消歧。
// **别再加 `@JvmName`**：它住在 `kotlin.jvm`，commonMain 里写会让 iOS 目标编译不过
// （`Unresolved reference 'JvmName'`）—— 这一行就是那么挂掉的。
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
